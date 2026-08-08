package mullu.comrade.transfer

import uniffi.comrade_core.RefusalReason
import uniffi.comrade_ui.ShareVerdictDto

/**
 * The file-handover arithmetic, with **zero Android imports** so it runs on the
 * JVM in [mullu.comrade.transfer.ShareDecisionsTest].
 *
 * Every function here is a port of `comrade_core::share`, and the Rust test
 * vectors are ported alongside them — the same arrangement
 * `TogetherDecisions`/`together_sync.mjs` already use. A port rather than an
 * FFI call because the receiver touches this once per 16 KiB chunk (tens of
 * thousands of times for one film) from inside a WebRTC callback thread, where
 * a `runBlocking` into the core is exactly the shape that froze calls on
 * "Connecting…" once already.
 *
 * A divergence from the Rust is a red test here, not a file that arrives
 * corrupted in the field.
 */
object ShareDecisions {

    /** Bytes of header on a chunk message. Mirrors `CHUNK_FRAME_HEADER_BYTES`. */
    const val CHUNK_HEADER_BYTES = 4

    /**
     * Contiguous playback to have in hand before starting.
     *
     * Aliased rather than restated: the number belongs to [ShareReadPolicy],
     * which is where the *pair* of thresholds lives and where the relationship
     * between them is asserted. Two copies of a threshold is how the start and
     * the stop rule end up disagreeing by a release.
     */
    const val PLAYABLE_RUNWAY_MS = ShareReadPolicy.PLAYABLE_RUNWAY_MS

    /** How many chunks to keep asking for at a time. */
    const val REQUEST_WINDOW = 64

    /**
     * How long the receiver waits with no chunk before it decides the sender has
     * stopped. See [stallAction] for what it does about it.
     *
     * Wide enough that a slow link between two 16 KiB chunks is not mistaken for
     * a dead one, and narrow enough that the whole bounded sequence finishes
     * inside [ShareReadPolicy.READ_PATIENCE_MS] — a test asserts that inequality
     * rather than leaving it to survive as a coincidence.
     */
    const val STALL_AFTER_MS: Long = 8_000

    /**
     * How many times a stalled receive asks again before giving up.
     *
     * One. A request is two integers on an open channel: if the first re-ask
     * does not produce a chunk, the sender is not busy, it is gone — and more
     * attempts only lengthen the time a person spends watching a bar that will
     * never move.
     */
    const val STALL_REASKS: Int = 1

    /**
     * The file name an incoming transfer lands under, from the offer's hash.
     *
     * Pure and here rather than beside the file I/O so it can be tested, because
     * the input is **peer-supplied and used to build a path**. Everything
     * outside lowercase hex is dropped, which is what makes `../../databases/x`
     * impossible rather than merely unlikely; the length cap keeps a peer from
     * choosing a name longer than the filesystem will take. An offer whose hash
     * survives as nothing at all still gets a name, because refusing to name it
     * would mean refusing a transfer over a cosmetic detail.
     */
    fun incomingFileName(sha256: String): String {
        val safe = sha256.lowercase()
            .filter { it.isDigit() || it in 'a'..'f' }
            .take(64)
        return if (safe.isEmpty()) "incoming.bin" else "$safe.bin"
    }

    // ── Telling a re-delivered signal from a new transfer ───────────────────

    /**
     * What identifies one transfer: what is being moved, in what shape, and
     * which end of it this device is.
     *
     * The hash is the strong half — two offers of the same file agree on it by
     * construction, since it is a SHA-256 of the bytes. The two sizes come along
     * because a hash that did not survive sanitising (see [incomingFileName])
     * would otherwise match anything, and [sending] because the same file moving
     * the other way is a different transfer, not the same one seen twice.
     */
    data class TransferIdentity(
        val sending: Boolean,
        val sha256: String,
        val totalBytes: Long,
        val chunkBytes: Int,
    )

    /** What arming should do to the transfer that is already live, if any. */
    enum class ArmDecision {
        /** Nothing was running. Build it. */
        START,

        /** Something else was running. Tear that down first, then build. */
        REPLACE,

        /** This exact transfer is already running. Leave it alone. */
        REDELIVERY,
    }

    /**
     * Whether an arriving offer starts a transfer, replaces one, or is a signal
     * this device has already acted on. `AUDIT.md` Q18.
     *
     * Comrade replays gift-wraps on purpose — `inbox_since` widens the
     * subscription floor back to the persisted watermark on every reconnect, so
     * a device that was offline loses nothing and a device that was not sees
     * recent envelopes twice. Nothing upstream de-duplicates a `Share`, so this
     * is where the second copy has to stop.
     *
     * **Erring restrictive, deliberately.** Anything that cannot be told apart
     * from the live transfer is treated as a redelivery and ignored, including
     * the two offers that agree on an unusable hash and a size. The two mistakes
     * are not symmetric: ignoring a genuine retry costs a wait that the stall
     * watchdog ([stallAction]) already ends — once a transfer is actually dead
     * the session is gone, so the very same offer arms fresh — while acting on a
     * duplicate ends a transfer that was working and leaks the `PeerConnection`,
     * `DataChannel` and open file behind it, which nothing recovers.
     */
    fun decideArm(live: TransferIdentity?, incoming: TransferIdentity): ArmDecision {
        if (live == null) return ArmDecision.START
        return if (isSameTransfer(live, incoming)) ArmDecision.REDELIVERY else ArmDecision.REPLACE
    }

    private fun isSameTransfer(a: TransferIdentity, b: TransferIdentity): Boolean =
        a.sending == b.sending &&
            a.totalBytes == b.totalBytes &&
            a.chunkBytes == b.chunkBytes &&
            // Hex from a peer, so case is theirs to choose and not a difference.
            a.sha256.trim().equals(b.sha256.trim(), ignoreCase = true)

    // ── Noticing that the chunks stopped ────────────────────────────────────

    /** What a receiver that has not seen a chunk for a while should do. */
    enum class StallAction {
        /** Not long enough yet. */
        WAIT,

        /** Ask again — a request or a chunk can be lost without the connection
         *  noticing, and one more request is cheap. */
        REASK,

        /** Asking again did not help. End it, and say so. */
        GIVE_UP,
    }

    /**
     * The receiver's stall policy, from the two facts a watchdog can see.
     * `AUDIT.md` Q19.
     *
     * The only condition that used to end a transfer was the connection itself
     * failing, so a sender that quietly stopped reading its file — a revoked
     * `content://` grant, removable storage pulled — froze both progress bars
     * with no error on either device. This is what turns that silence into a
     * sentence.
     *
     * Each re-ask buys its own [STALL_AFTER_MS] window, so the deadline widens
     * with `reasksSoFar` rather than the answer flipping from `REASK` to
     * `GIVE_UP` on the very next tick, before the re-ask could possibly have
     * been answered.
     *
     * It is **not** a claim that the sender is gone: a link slow enough to leave
     * eight seconds between chunks is indistinguishable from one that stopped,
     * from here. It is the point at which continuing to say nothing is worse
     * than being wrong.
     */
    fun stallAction(
        idleMs: Long,
        reasksSoFar: Int,
        maxReasks: Int = STALL_REASKS,
    ): StallAction {
        val attempts = reasksSoFar.coerceAtLeast(0)
        if (idleMs < STALL_AFTER_MS * (attempts + 1)) return StallAction.WAIT
        return if (attempts < maxReasks) StallAction.REASK else StallAction.GIVE_UP
    }

    /** Prefix `bytes` with its big-endian chunk index. */
    fun frameChunk(index: Int, bytes: ByteArray): ByteArray {
        val out = ByteArray(CHUNK_HEADER_BYTES + bytes.size)
        out[0] = (index ushr 24).toByte()
        out[1] = (index ushr 16).toByte()
        out[2] = (index ushr 8).toByte()
        out[3] = index.toByte()
        bytes.copyInto(out, CHUNK_HEADER_BYTES)
        return out
    }

    /** The `(index, payload)` of a received message, or null if it is too short. */
    fun parseChunkFrame(message: ByteArray): Pair<Int, ByteArray>? {
        if (message.size < CHUNK_HEADER_BYTES) return null
        // Masked to keep a high bit from sign-extending: index 0xFFFFFFFF must
        // read as a large index this offer rejects, never as a negative one
        // that would index backwards into the array.
        val index = (message[0].toInt() and 0xFF shl 24) or
            (message[1].toInt() and 0xFF shl 16) or
            (message[2].toInt() and 0xFF shl 8) or
            (message[3].toInt() and 0xFF)
        return index to message.copyOfRange(CHUNK_HEADER_BYTES, message.size)
    }

    /** How many chunks an offer of `totalBytes` divides into. */
    fun chunkCount(totalBytes: Long, chunkBytes: Int): Int {
        if (totalBytes <= 0 || chunkBytes <= 0) return 0
        return ((totalBytes + chunkBytes - 1) / chunkBytes).toInt()
    }

    /** The `(start, length)` of chunk `index`, or null past the end. */
    fun chunkRange(totalBytes: Long, chunkBytes: Int, index: Int): Pair<Long, Int>? {
        if (index < 0 || index >= chunkCount(totalBytes, chunkBytes)) return null
        val start = index.toLong() * chunkBytes
        return start to minOf(totalBytes - start, chunkBytes.toLong()).toInt()
    }

    /**
     * Whether a received chunk is one this offer could have produced.
     *
     * Both halves matter: a wrong index writes bytes into the wrong place, and
     * a wrong length silently shifts everything after it. The whole-file hash
     * catches both — after the whole transfer, which is much too late.
     */
    fun chunkFrameFits(totalBytes: Long, chunkBytes: Int, index: Int, payloadLength: Int): Boolean =
        chunkRange(totalBytes, chunkBytes, index)?.second == payloadLength

    /**
     * Which chunks have arrived, and what that means for playback.
     *
     * Requests are anchored at the **playhead**, not at the start of the file,
     * so a seek into un-fetched territory costs one request rather than a
     * re-download; once everything past the playhead is here it falls back to
     * the earliest gap, so a session that seeked forward still ends with a
     * whole file rather than one with a hole in the middle.
     */
    class Tracker(val totalBytes: Long, val chunkBytes: Int, val durationMs: Long) {
        val chunkCount = chunkCount(totalBytes, chunkBytes)
        private val have = BooleanArray(chunkCount)
        private var received = 0

        val chunkMs: Long = if (chunkCount == 0) 0 else durationMs / chunkCount

        /** Record a chunk. A duplicate is not an error — a re-ask can deliver both. */
        fun accept(index: Int): Boolean {
            if (index < 0 || index >= chunkCount || have[index]) return false
            have[index] = true
            received += 1
            return true
        }

        fun has(index: Int): Boolean = index in 0 until chunkCount && have[index]

        fun isComplete(): Boolean = received == chunkCount

        /** An empty file is complete, not divided by zero. */
        fun fraction(): Float = if (chunkCount == 0) 1f else received.toFloat() / chunkCount

        fun chunkAtMs(posMs: Long): Int {
            if (chunkMs == 0L) return 0
            return minOf((posMs / chunkMs).toInt(), maxOf(chunkCount - 1, 0))
        }

        /**
         * Milliseconds of *uninterrupted* playback from `posMs`. A gap ends the
         * runway however much lies beyond it — audio after a hole is not
         * playable, it is a stutter waiting to happen.
         */
        fun runwayMs(posMs: Long): Long {
            var contiguous = 0L
            for (i in chunkAtMs(posMs) until chunkCount) {
                if (!have[i]) break
                contiguous += 1
            }
            return contiguous * chunkMs
        }

        /**
         * Whether everything from `posMs` to the end of the file is here.
         *
         * Named rather than folded into [playableAt] because it is the arm that
         * means *nothing more is coming*: a two-second runway with the last
         * chunk inside it is not a transfer running behind, it is a track nearly
         * over, and waiting for more would wait forever.
         */
        fun tailCompleteAt(posMs: Long): Boolean {
            if (isComplete()) return true
            for (i in chunkAtMs(posMs) until chunkCount) if (!have[i]) return false
            return true
        }

        /**
         * Whether playback may start at `posMs` — either there is enough
         * runway, or the rest of the file is here and the runway is simply all
         * that remains. A track with two seconds left is playable.
         *
         * Built **on** [tailCompleteAt] and [runwayMs] rather than beside them,
         * the same construction `ShareTracker::playable_at` uses, so this and
         * [readVerdictAt] cannot drift apart.
         */
        fun playableAt(posMs: Long): Boolean =
            tailCompleteAt(posMs) || runwayMs(posMs) >= PLAYABLE_RUNWAY_MS

        /**
         * What a reader at `posMs` should do. The tracker-side spelling of
         * [ShareReadPolicy.verdict], mirroring `ShareTracker::read_verdict_at`.
         *
         * The thresholds are not this class's to hold: they live next door in a
         * file with no imports at all, so the rule runs under `kotlinc` before
         * CI ever sees it.
         */
        fun readVerdictAt(posMs: Long, playing: Boolean): ShareReadPolicy.Verdict =
            ShareReadPolicy.verdict(
                playing = playing,
                runwayMs = runwayMs(posMs),
                tailComplete = tailCompleteAt(posMs),
            )

        /** What to ask for next, given where the listener is. */
        fun nextRequest(posMs: Long, maxCount: Int): Request? {
            if (isComplete() || maxCount <= 0) return null
            val from = firstGapAtOrAfter(chunkAtMs(posMs)) ?: firstGapAtOrAfter(0) ?: return null
            var span = 0
            for (i in from until chunkCount) {
                if (have[i] || span == maxCount) break
                span += 1
            }
            return Request(from, span)
        }

        private fun firstGapAtOrAfter(start: Int): Int? {
            for (i in start until chunkCount) if (!have[i]) return i
            return null
        }
    }

    /** "Send me these." The receiver drives; see the Rust module comment. */
    data class Request(val from: Int, val count: Int)

    /** What to do about a verdict, and what to say if the answer is no. */
    data class Plan(
        val proceed: Boolean,
        val retryable: Boolean = false,
        val needsConsent: Boolean = false,
        val message: String? = null,
    )

    /**
     * Turn a verdict into what the person should be told.
     *
     * A refused transfer gets a specific sentence rather than "transfer
     * failed", because each of these has a different thing the person could do
     * about it: change the policy, use a smaller file, or wait a moment longer.
     */
    fun describeVerdict(verdict: ShareVerdictDto?): Plan {
        if (verdict == null) return Plan(proceed = false, message = "Couldn't work out how to send this.")
        return when (verdict.verdict) {
            "allow" -> Plan(proceed = true)
            "needs_consent" -> Plan(
                proceed = false,
                needsConsent = true,
                message = "There's no direct route to them, so ${megabytes(verdict.relayedBytes)}" +
                    " would go through a relay server. Send it anyway?",
            )
            else -> when (val reason = verdict.reason) {
                is RefusalReason.RelayForbidden -> Plan(
                    proceed = false,
                    message = "No direct route to them — the file would have to go through a " +
                        "relay, which this device doesn't do.",
                )
                is RefusalReason.TooLargeForRelay -> Plan(
                    proceed = false,
                    message = "No direct route to them, and this is over the " +
                        "${megabytes(reason.limit)} relay limit.",
                )
                is RefusalReason.PathUnknown -> Plan(
                    proceed = false,
                    retryable = true,
                    message = "Still working out a route to them…",
                )
                null -> Plan(proceed = false, message = "Couldn't send this over the route we have.")
            }
        }
    }

    private fun megabytes(bytes: ULong?): String =
        "${((bytes ?: 0uL).toLong() + 524_288L) / 1_048_576L} MB"
}
