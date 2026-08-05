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

    /** Contiguous playback to have in hand before starting. */
    const val PLAYABLE_RUNWAY_MS = 5_000L

    /** How many chunks to keep asking for at a time. */
    const val REQUEST_WINDOW = 64

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
         * Whether playback may start at `posMs` — either there is enough
         * runway, or the rest of the file is here and the runway is simply all
         * that remains. A track with two seconds left is playable.
         */
        fun playableAt(posMs: Long): Boolean {
            if (isComplete()) return true
            val start = chunkAtMs(posMs)
            for (i in start until chunkCount) if (!have[i]) return runwayMs(posMs) >= PLAYABLE_RUNWAY_MS
            return true
        }

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
