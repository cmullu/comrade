package mullu.comrade.transfer

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mullu.comrade.ComradeCore
import mullu.comrade.call.CallManager
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import uniffi.comrade_core.RefusalReason
import uniffi.comrade_core.ShareOffer
import uniffi.comrade_core.TransferSignal

/**
 * Moving one file from this device to one other device, over a data channel with
 * nobody in the middle.
 *
 * This is `together`'s file handover with the session pulled out of it. Every
 * line of the transfer itself — the receiver-driven request loop, the flow
 * control against `bufferedAmount`, the [RandomAccessFile] assembly, the
 * whole-file hash at the end, the ICE trickling, the relay policy — is the code
 * that shipped for watch-together; what used to be four hard-wired facts about a
 * listening session are now [Wiring]:
 *
 *  * **how a signal reaches the other side** — a session envelope for
 *    `together`, a transfer id for an attachment handoff,
 *  * **where the sender's bytes come from** — a [Source], so a handoff can read
 *    a `content://` descriptor without first copying 400 MB into the cache,
 *  * **where the receiver's bytes land** — one staging directory per caller, so
 *    each can be swept whole (AUDIT S-4),
 *  * **who is told when it finishes**.
 *
 * ## Why this is its own PeerConnection
 *
 * A transfer never shares the call's connection, for two reasons that both
 * matter on their own:
 *
 * - **Congestion.** One connection means one SCTP association under one
 *   congestion controller, where a multi-gigabyte push and a voice stream
 *   compete and the voice loses. A second connection costs one extra ICE
 *   negotiation and buys complete isolation: a call cannot be degraded by a
 *   transfer it knows nothing about.
 * - **Policy.** This connection is built from its own ICE server list. Under
 *   the default relay policy it is given **no TURN at all**, so a relay
 *   candidate is never gathered and the rule holds structurally rather than by
 *   later inspection. The call keeps its TURN fallback, because a relayed
 *   *call* is a few tens of kilobits and entirely reasonable while a relayed
 *   film is gigabytes through a machine that volunteered for neither.
 *
 * The [org.webrtc.PeerConnectionFactory] *is* shared (see
 * [CallManager.sharedFactory]) because its native initialisation is
 * process-global and must happen once. Sharing a factory is not sharing a
 * connection.
 *
 * ## Flow control
 *
 * `DataChannel.send` accepts writes long after it has stopped putting them on
 * the wire; the bytes queue in the SCTP send buffer and `bufferedAmount`
 * climbs. A naive loop over a 2 GB file queues the whole thing in memory in
 * milliseconds — and it looks fine on a 50 MB test file, which is how that bug
 * reaches production. So the sender fills to a high-water mark and then waits
 * for `onBufferedAmountChange` to drop it under the low-water mark, and the
 * budget arithmetic comes from `comrade_core::share::transport` via
 * [ComradeCore.shareChunksToSend] rather than being guessed here.
 *
 * ## What this does not own
 *
 * The chunking, the resume arithmetic and the "can we play yet" question are
 * [ShareDecisions] (pure, unit-tested, ported from the same Rust the desktop
 * ports). Which paths may carry a transfer is core's. Which *signals* carry the
 * negotiation is the caller's. This file is the wiring.
 *
 * One instance carries one transfer at a time, exactly as the single `object`
 * did: [armSend] or [armReceive] replaces whatever came before.
 */
class FileTransfer(private val wiring: Wiring) {

    /**
     * What the engine cannot know on its own: who to talk to, where the bytes
     * come from and go, and who to tell when it lands.
     */
    interface Wiring {
        /** One step of the WebRTC negotiation, on the caller's own channel. */
        fun sendTransport(signal: TransferSignal)

        /** The network said no, and this is which network fact. */
        fun sendRefuse(reason: RefusalReason)

        /**
         * Where incoming bytes land. **One directory per caller** and nothing
         * else in it: decrypted media on disk is what AUDIT S-4 is about, and
         * having a single directory to sweep is what makes sweeping possible.
         */
        fun stagingDir(context: Context): File

        /**
         * The name to stage an offer under, or null to refuse it.
         *
         * The input is the peer's content hash and the output becomes a path, so
         * this is always a tested function and never an interpolation — see
         * [ShareDecisions.incomingFileName] and
         * `mullu.comrade.handoff.HandoffDecisions.stagedFileName`.
         */
        fun stagedName(sha256: String): String?

        /**
         * Where the caller's playhead is, so the next request is anchored there.
         * Zero for anything that is not being played while it arrives.
         */
        fun playheadMs(): Long

        /** The file is here and its hash checked out. */
        fun onReceived(path: String)
    }

    /**
     * The sender's bytes, addressed by offset.
     *
     * An interface rather than a path because the two callers genuinely differ:
     * `together` holds a file it opened itself, while an attachment is a
     * `content://` URI whose only readable form is a descriptor. Copying that
     * into the cache first would mean a second 400 MB on disk, in plaintext, for
     * no gain.
     */
    interface Source : java.io.Closeable {
        /** Exactly [length] bytes at [offset], or a throw. */
        fun read(offset: Long, length: Int): ByteArray
    }

    /** A file this device can open by name. `together`'s case. */
    class PathSource(path: String) : Source {
        private val file = RandomAccessFile(path, "r")

        override fun read(offset: Long, length: Int): ByteArray {
            val bytes = ByteArray(length)
            file.seek(offset)
            file.readFully(bytes)
            return bytes
        }

        override fun close() {
            file.close()
        }
    }

    /** Which side of one transfer this device is. */
    private enum class Role { SENDER, RECEIVER }

    private class Session(
        val role: Role,
        val offer: ShareOffer,
        /** Sender: how to open the bytes. Receiver: null. */
        val openSource: (() -> Source)?,
        /** Receiver: where the bytes are landing. Sender: null. */
        val path: String?,
    ) {
        var pc: PeerConnection? = null
        var channel: DataChannel? = null
        var tracker: ShareDecisions.Tracker? = null
        val pendingIce = mutableListOf<IceCandidate>()
        var remoteSet = false
        var judged = false
        var stopped = false
        /**
         * Set only by [grantConsent], and only ever passed to core as the
         * answer to a question core asked. Core treats it as an answer, not an
         * override: it can turn `needs_consent` into `allow` and nothing else.
         */
        var consentGranted = false
        var stoppedForConsent = false
        /** Sender: the half-open range the receiver last asked for. */
        var cursorNext = 0
        var cursorEnd = 0
    }

    @Volatile private var session: Session? = null

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What the screen shows about the transfer, if anything. */
    @Volatile var status: String? = null
        private set

    /** Progress 0..1 while receiving, or null. */
    @Volatile var progress: Float? = null
        private set

    /** Whether a transfer is armed. A signal for anything else is not ours. */
    val active: Boolean get() = session != null

    /** Say something about the transfer that only the caller could know. */
    fun note(message: String?) {
        status = message
    }

    /**
     * The relay question the screen is showing, or null when there is none.
     *
     * A flow rather than a `@Volatile` like [status], because this one has to
     * *arrive* — a question nobody is told about is a transfer that stalls in
     * silence, which is the failure this whole change exists to remove.
     */
    private val _consentQuestion = MutableStateFlow<String?>(null)
    val consentQuestion: StateFlow<String?> = _consentQuestion.asStateFlow()

    // ── Arming ──────────────────────────────────────────────────────────────

    /**
     * We hold the file and have told them so. Nothing is negotiated until they
     * accept — this only records what we would send.
     */
    fun armSend(offer: ShareOffer, openSource: () -> Source) {
        session = Session(Role.SENDER, offer, openSource, path = null)
    }

    /**
     * They hold the file and we have agreed to take it. Stage a place for it and
     * drop whatever an earlier transfer left behind.
     *
     * Returns the staging path, or null when the offer's hash is not a hash — a
     * caller with no name must refuse the transfer, not invent one.
     */
    fun armReceive(context: Context, offer: ShareOffer): String? {
        val name = wiring.stagedName(offer.sha256) ?: return null
        val dir = wiring.stagingDir(context)
        dir.mkdirs()
        val target = File(dir, name)
        runCatching { target.delete() }
        // Anything left by an earlier transfer goes now rather than lingering
        // until the next one needs the space.
        purgeStaging(context, target)
        val s = Session(Role.RECEIVER, offer, openSource = null, path = target.absolutePath)
        s.tracker = ShareDecisions.Tracker(
            offer.totalBytes.toLong(),
            offer.chunkBytes.toInt(),
            offer.durationMs.toLong(),
        )
        session = s
        progress = 0f
        return target.absolutePath
    }

    /** Drop every staged file except `keep`, which is the live one. */
    private fun purgeStaging(context: Context, keep: File?) {
        wiring.stagingDir(context).listFiles()?.forEach { f ->
            if (f.absolutePath != keep?.absolutePath) runCatching { f.delete() }
        }
    }

    /** Drop everything staged. Nothing is in flight after this. */
    fun purgeStaging(context: Context) {
        purgeStaging(context, keep = null)
    }

    // ── Consent, refusal, teardown ──────────────────────────────────────────

    /** They said yes to this transfer. Re-judge; nothing is assumed from here. */
    fun grantConsent() {
        val s = session ?: return
        if (!s.stoppedForConsent) return
        s.consentGranted = true
        s.stoppedForConsent = false
        _consentQuestion.value = null
        // Re-ask rather than proceed: the path may have changed while the
        // question was on screen, and the answer that matters is the one for
        // the route we actually have now.
        io.launch { judgePath(s) }
    }

    /** They said no. That is a real outcome, and the far side is told. */
    fun refuseConsent() {
        val s = session ?: return
        if (!s.stoppedForConsent) return
        s.stoppedForConsent = false
        _consentQuestion.value = null
        wiring.sendRefuse(RefusalReason.RelayForbidden)
        status = "Didn't send it."
        end()
    }

    /** The far side's network gave up. Say which fact, and stop. */
    fun onRefused(reason: RefusalReason) {
        status = ShareDecisions.describeVerdict(
            uniffi.comrade_ui.ShareVerdictDto("refuse", "relay", reason, null),
        ).message
        end()
    }

    /** Tear everything down. Safe to call twice, and from any thread. */
    fun end() {
        val s = session ?: return
        session = null
        s.stopped = true
        runCatching { s.channel?.close() }
        runCatching { s.pc?.close() }
        progress = null
        // Answering a question about a transfer that no longer exists would act
        // on a session that is already gone.
        _consentQuestion.value = null
    }

    // ── Sender ──────────────────────────────────────────────────────────────

    /** They accepted. Build the connection and offer the channel. */
    fun beginNegotiation(context: Context) {
        val s = session ?: return
        if (s.role != Role.SENDER || s.pc != null) return
        val pc = newTransferPeer(context, s) ?: run {
            status = "Couldn't start the transfer."
            return
        }
        s.pc = pc
        // Ordered and reliable: the receiver asks for ranges and expects them
        // whole. Unreliable delivery would mean re-implementing retransmission
        // on top of a stack that already has it.
        s.channel = pc.createDataChannel("comrade-share", DataChannel.Init().apply { ordered = true })
        s.channel?.registerObserver(senderChannelObserver(s))
        pc.createOffer(
            onCreated("create offer") { sdp ->
                pc.setLocalDescription(onSet("set local offer"), sdp)
                wiring.sendTransport(TransferSignal.Offer(sdp.description))
            },
            MediaConstraints(),
        )
    }

    private fun senderChannelObserver(s: Session) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previous: Long) {
            // The drain event, not a poll. Resuming on every few bytes would be
            // an event per chunk, which is the busy loop the threshold exists
            // to prevent — so only wake when it has actually drained.
            if ((s.channel?.bufferedAmount() ?: 0L) <= LOW_WATER_BYTES) io.launch { pump(s) }
        }

        override fun onStateChange() {
            if (s.channel?.state() == DataChannel.State.OPEN) io.launch { pump(s) }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            // The receiver's request, as `from:count`. Deliberately not JSON:
            // this is two integers on a hot path, and a parser here is a
            // parser a peer controls.
            val raw = ByteArray(buffer.data.remaining())
            buffer.data.get(raw)
            val text = String(raw).trim()
            val parts = text.split(':')
            val from = parts.getOrNull(0)?.toIntOrNull() ?: return
            val count = parts.getOrNull(1)?.toIntOrNull() ?: return
            s.cursorNext = from
            s.cursorEnd = from + count
            io.launch { pump(s) }
        }
    }

    /**
     * Push what the receiver asked for, stopping at the high-water mark.
     *
     * `@Synchronized` because two triggers — the drain callback and a fresh
     * request — can arrive on different threads, and two pumps sharing one
     * cursor would send the same chunk twice and skip the next.
     */
    @Synchronized
    private fun pump(s: Session) {
        val channel = s.channel ?: return
        if (s.stopped || channel.state() != DataChannel.State.OPEN) return
        // The policy gate, and it belongs *here* rather than only at the call
        // sites. The receiver asks the moment the channel opens, which is while
        // [judgePath] is still running, so a request can reach the pump before
        // the path has been judged. Refusing to send until it has is what makes
        // "never relay bulk" true rather than merely intended; the cursor is
        // already recorded, so the pump picks it up when the verdict lands.
        if (!s.judged) return
        val open = s.openSource ?: return
        val source = runCatching { open() }.getOrNull() ?: return
        try {
            while (!s.stopped && s.cursorNext < s.cursorEnd) {
                val budget = ComradeCore.shareChunksToSend(channel.bufferedAmount())
                if (budget <= 0) return // wait for the drain callback
                var sent = 0
                while (sent < budget && s.cursorNext < s.cursorEnd) {
                    val index = s.cursorNext
                    val range = ShareDecisions.chunkRange(
                        s.offer.totalBytes.toLong(),
                        s.offer.chunkBytes.toInt(),
                        index,
                    ) ?: return
                    val bytes = runCatching { source.read(range.first, range.second) }.getOrNull()
                        ?: return
                    channel.send(
                        DataChannel.Buffer(
                            ByteBuffer.wrap(ShareDecisions.frameChunk(index, bytes)),
                            true,
                        ),
                    )
                    s.cursorNext += 1
                    sent += 1
                    // Re-check inside the batch: `bufferedAmount` moves as we
                    // write, and a batch sized against a stale reading is how
                    // the ceiling gets overshot on a slow link.
                    if (ComradeCore.shareChunksToSend(channel.bufferedAmount()) <= 0) return
                }
            }
        } finally {
            runCatching { source.close() }
        }
    }

    // ── Receiver ────────────────────────────────────────────────────────────

    private fun receiverChannelObserver(s: Session) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previous: Long) = Unit

        override fun onStateChange() {
            if (s.channel?.state() == DataChannel.State.OPEN) requestNext(s, 0)
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            onChunk(s, bytes)
        }
    }

    private fun onChunk(s: Session, message: ByteArray) {
        val tracker = s.tracker ?: return
        val path = s.path ?: return
        val frame = ShareDecisions.parseChunkFrame(message) ?: return
        val (index, payload) = frame
        // A peer can put anything on this channel. A wrong index writes bytes
        // into the wrong place in the file and a wrong length silently shifts
        // everything after it, so both are checked now rather than left to the
        // whole-file hash at the very end.
        if (!ShareDecisions.chunkFrameFits(
                s.offer.totalBytes.toLong(),
                s.offer.chunkBytes.toInt(),
                index,
                payload.size,
            )
        ) {
            return
        }
        if (!tracker.accept(index)) return
        val range = ShareDecisions.chunkRange(
            s.offer.totalBytes.toLong(),
            s.offer.chunkBytes.toInt(),
            index,
        ) ?: return
        runCatching {
            RandomAccessFile(path, "rw").use {
                it.setLength(s.offer.totalBytes.toLong())
                it.seek(range.first)
                it.write(payload)
            }
        }.onFailure {
            status = "Couldn't write the file."
            end()
            return
        }
        progress = tracker.fraction()
        status = "Receiving — ${(tracker.fraction() * 100).toInt()}%"
        if (tracker.isComplete()) {
            finishReceive(s)
        } else {
            requestNext(s, wiring.playheadMs())
        }
    }

    private fun requestNext(s: Session, posMs: Long) {
        val request = s.tracker?.nextRequest(posMs, ShareDecisions.REQUEST_WINDOW) ?: return
        val channel = s.channel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        runCatching {
            channel.send(
                DataChannel.Buffer(
                    ByteBuffer.wrap("${request.from}:${request.count}".toByteArray()),
                    false,
                ),
            )
        }
    }

    private fun finishReceive(s: Session) {
        val path = s.path ?: return
        // Integrity, once, at the end. This is why the per-chunk checks above
        // exist: they catch the failures a whole-file hash would only report
        // after the whole file.
        val actual = runCatching { sha256(File(path)) }.getOrNull()
        if (actual == null || !actual.equals(s.offer.sha256, ignoreCase = true)) {
            status = "The file that arrived isn't the one that was sent."
            runCatching { File(path).delete() }
            end()
            return
        }
        progress = 1f
        wiring.onReceived(path)
    }

    // ── Negotiation ─────────────────────────────────────────────────────────

    fun onTransport(context: Context, signal: TransferSignal) {
        val s = session ?: return
        when (signal) {
            is TransferSignal.Offer -> {
                val pc = s.pc ?: newTransferPeer(context, s) ?: return
                s.pc = pc
                pc.setRemoteDescription(
                    onSet("set remote offer") {
                        s.remoteSet = true
                        flushIce(s)
                        pc.createAnswer(
                            onCreated("create answer") { answer ->
                                pc.setLocalDescription(onSet("set local answer"), answer)
                                wiring.sendTransport(TransferSignal.Answer(answer.description))
                            },
                            MediaConstraints(),
                        )
                    },
                    SessionDescription(SessionDescription.Type.OFFER, signal.sdp),
                )
            }
            is TransferSignal.Answer -> {
                val pc = s.pc ?: return
                pc.setRemoteDescription(
                    onSet("set remote answer") {
                        s.remoteSet = true
                        flushIce(s)
                    },
                    SessionDescription(SessionDescription.Type.ANSWER, signal.sdp),
                )
            }
            is TransferSignal.Ice -> {
                val candidate = IceCandidate(
                    signal.sdpMid ?: "",
                    signal.sdpMLineIndex?.toInt() ?: 0,
                    signal.candidate,
                )
                // Buffer until the remote description exists — an early
                // candidate is dropped otherwise, exactly as on the call path.
                if (!s.remoteSet) s.pendingIce.add(candidate) else s.pc?.addIceCandidate(candidate)
            }
        }
    }

    private fun flushIce(s: Session) {
        val queued = s.pendingIce.toList()
        s.pendingIce.clear()
        for (c in queued) runCatching { s.pc?.addIceCandidate(c) }
    }

    private fun newTransferPeer(context: Context, s: Session): PeerConnection? {
        val factory = CallManager.sharedFactory(context) ?: return null
        // The structural half of the policy: a direct-only device gets a
        // STUN-only list, so a relay candidate is never gathered at all.
        val strategy = if (ComradeCore.shareIceServersAllowed()) {
            uniffi.comrade_core.IceStrategy.STUN_AND_TURN
        } else {
            uniffi.comrade_core.IceStrategy.STUN_ONLY
        }
        val servers = ComradeCore.callIceServersForTyped(strategy).map {
            // Same shape as CallManager's `toWebRtc`: leave the auth fields
            // untouched when they are absent rather than setting them empty,
            // which some stacks read as "authenticate with a blank password".
            PeerConnection.IceServer.builder(it.urls)
                .apply {
                    it.username?.let { u -> setUsername(u) }
                    it.credential?.let { c -> setPassword(c) }
                }
                .createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return factory.createPeerConnection(config, transferObserver(s))
    }

    private fun transferObserver(s: Session) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            wiring.sendTransport(
                TransferSignal.Ice(
                    candidate.sdp,
                    candidate.sdpMid,
                    candidate.sdpMLineIndex.toUShort(),
                ),
            )
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> io.launch { judgePath(s) }
                PeerConnection.PeerConnectionState.FAILED -> {
                    status = "Couldn't open a route to send the file."
                    end()
                }
                else -> Unit
            }
        }

        override fun onDataChannel(channel: DataChannel) {
            // The receiver's side: the sender opens the channel.
            s.channel = channel
            channel.registerObserver(receiverChannelObserver(s))
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit
    }

    /**
     * Inspect the path ICE actually chose and ask core whether it may carry
     * this. The second line of the policy: even with no TURN configured, a
     * peer-reflexive path can be relayed at the far end, and core classifies a
     * pair as relayed if *either* end is.
     */
    private suspend fun judgePath(s: Session) {
        if (s.judged || s.stopped) return
        val pc = s.pc ?: return
        repeat(RETRY_WHILE_UNSETTLED) {
            if (s.judged || s.stopped) return
            val types = selectedPairTypes(pc)
            val verdict = runCatching {
                ComradeCore.shareTransferVerdict(
                    types?.first.orEmpty(),
                    types?.second.orEmpty(),
                    s.offer.totalBytes.toLong(),
                    s.consentGranted,
                )
            }.getOrNull()
            val plan = ShareDecisions.describeVerdict(verdict)
            if (plan.proceed) {
                s.judged = true
                status = "Sending over a direct connection…"
                if (s.role == Role.SENDER) pump(s)
                return
            }
            if (plan.needsConsent) {
                // The policy wants a person to agree to this specific transfer.
                // Stop here and wait: no bytes move, and no refusal is sent,
                // because neither has been decided yet. [grantConsent] or
                // [refuseConsent] restarts or ends this.
                s.stoppedForConsent = true
                _consentQuestion.value = plan.message
                status = plan.message
                return
            }
            if (!plan.retryable) {
                // Tell them why too — they are watching a progress bar that
                // would otherwise sit at zero forever.
                verdict?.reason?.let { wiring.sendRefuse(it) }
                status = plan.message
                end()
                return
            }
            delay(1000)
        }
        status = "Couldn't work out a route to them."
        end()
    }

    /**
     * The candidate types on the selected pair, or null if ICE has not settled.
     *
     * Null reads as *unknown*, which core refuses — never as direct.
     */
    private suspend fun selectedPairTypes(pc: PeerConnection): Pair<String, String>? {
        val report = kotlinx.coroutines.suspendCancellableCoroutine<org.webrtc.RTCStatsReport?> { cont ->
            runCatching { pc.getStats { cont.resumeWith(Result.success(it)) } }
                .onFailure { cont.resumeWith(Result.success(null)) }
        } ?: return null
        val stats = report.statsMap
        val pair = stats.values.firstOrNull {
            it.type == "candidate-pair" && it.members["nominated"] == true &&
                it.members["state"] == "succeeded"
        } ?: return null
        val localId = pair.members["localCandidateId"] as? String ?: return null
        val remoteId = pair.members["remoteCandidateId"] as? String ?: return null
        val local = stats[localId] ?: return null
        val remote = stats[remoteId] ?: return null
        return (local.members["candidateType"] as? String).orEmpty() to
            (remote.members["candidateType"] as? String).orEmpty()
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    /**
     * Two observers, not one with both callbacks wired to the same lambda.
     * `SdpObserver` covers create *and* set, and a single shared lambda fires
     * on whichever lands — which for `setLocalDescription` means re-sending the
     * offer, and for `createOffer` means acting on an SDP that was never set.
     */
    private fun onCreated(what: String, then: (SessionDescription) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = then(sdp)
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = fail(what, error)
        override fun onSetFailure(error: String?) = fail(what, error)
    }

    private fun onSet(what: String, then: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = then()
        override fun onCreateFailure(error: String?) = fail(what, error)
        override fun onSetFailure(error: String?) = fail(what, error)
    }

    private fun fail(what: String, error: String?) {
        Log.w(TAG, "$what failed: $error")
    }

    companion object {
        private const val TAG = "FileTransfer"
        private const val LOW_WATER_BYTES = 256L * 1024

        /** Mirrors `comrade_core::share::SHARE_CHUNK_BYTES`. */
        const val CHUNK_BYTES = 16 * 1024

        /** How many one-second looks to take before giving up on ICE settling. */
        private const val RETRY_WHILE_UNSETTLED = 10

        /**
         * The whole-file hash, streamed.
         *
         * Never on the main thread: a 400 MB SHA-256 is seconds of CPU, and the
         * callers run it on [Dispatchers.IO] for that reason.
         */
        fun sha256(file: File): String = file.inputStream().use { sha256(it) }

        /** The same hash, for bytes that have no path — a `content://` stream. */
        fun sha256(stream: java.io.InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
