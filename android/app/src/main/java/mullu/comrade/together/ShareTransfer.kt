package mullu.comrade.together

import android.content.Context
import android.util.Log
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import uniffi.comrade_core.ShareOffer
import uniffi.comrade_core.ShareSignal
import uniffi.comrade_core.TransferSignal

/**
 * Handing the file over, when only one of you has what you are playing.
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
 * ports). Which paths may carry a transfer is core's. This file is the wiring.
 */
object ShareTransfer {

    private const val TAG = "ShareTransfer"
    private const val LOW_WATER_BYTES = 256L * 1024

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Which side of one handover this device is. */
    private enum class Role { SENDER, RECEIVER }

    private class Session(
        val role: Role,
        val offer: ShareOffer,
        /** Sender: the file being read. Receiver: where the bytes are landing. */
        val path: String,
    ) {
        var pc: PeerConnection? = null
        var channel: DataChannel? = null
        var tracker: ShareDecisions.Tracker? = null
        val pendingIce = mutableListOf<IceCandidate>()
        var remoteSet = false
        var judged = false
        var stopped = false
        /** Sender: the half-open range the receiver last asked for. */
        var cursorNext = 0
        var cursorEnd = 0
    }

    @Volatile private var session: Session? = null

    /** What the screen shows about the handover, if anything. */
    @Volatile var status: String? = null
        private set

    /** Progress 0..1 while receiving, or null. */
    @Volatile var progress: Float? = null
        private set

    // ── Entry points ────────────────────────────────────────────────────────

    /** We joined a session for something we do not have. Ask for it. */
    fun ask() {
        runCatching { ComradeCore.togetherShareTyped(ShareSignal.Ask) }
            .onFailure { Log.w(TAG, "ask failed", it) }
        status = "Asking them to send it…"
    }

    /**
     * One step of the handover arrived. Everything routes through here, so the
     * protocol lives in one readable place rather than five callbacks.
     */
    fun onSignal(context: Context, signal: ShareSignal, localPath: String?, durationMs: Long) {
        when (signal) {
            is ShareSignal.Ask -> io.launch { offerOurCopy(localPath, durationMs) }
            is ShareSignal.Offer -> io.launch { acceptTheirCopy(context, signal.offer) }
            is ShareSignal.Accept -> io.launch { beginNegotiation(context, localPath) }
            is ShareSignal.Refuse -> {
                status = ShareDecisions.describeVerdict(
                    uniffi.comrade_ui.ShareVerdictDto("refuse", "relay", signal.reason, null),
                ).message
                end()
            }
            is ShareSignal.Transport -> io.launch { onTransport(context, signal.signal) }
        }
    }

    /** Tear everything down. Safe to call twice, and from any thread. */
    fun end() {
        val s = session ?: return
        session = null
        s.stopped = true
        runCatching { s.channel?.close() }
        runCatching { s.pc?.close() }
        progress = null
    }

    // ── Sender ──────────────────────────────────────────────────────────────

    private fun offerOurCopy(localPath: String?, durationMs: Long) {
        if (localPath == null) return // we do not have it either; nothing to offer
        val file = java.io.File(localPath)
        if (!file.isFile) return
        val sha = runCatching { sha256(file) }.getOrNull() ?: return
        val offer = ShareOffer(
            totalBytes = file.length().toULong(),
            chunkBytes = CHUNK_BYTES.toUInt(),
            sha256 = sha,
            durationMs = durationMs.toULong(),
        )
        session = Session(Role.SENDER, offer, localPath)
        status = "Waiting for them to accept…"
        runCatching { ComradeCore.togetherShareTyped(ShareSignal.Offer(offer)) }
            .onFailure { Log.w(TAG, "offer failed", it) }
    }

    private fun beginNegotiation(context: Context, localPath: String?) {
        val s = session ?: return
        if (s.role != Role.SENDER || s.pc != null || localPath == null) return
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
                send(ShareSignal.Transport(TransferSignal.Offer(sdp.description)))
            },
            MediaConstraints(),
        )
    }

    private fun senderChannelObserver(s: Session) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previous: Long) {
            // The drain event, not a poll. Resuming on every few bytes would be
            // an event per chunk, which is the busy loop the threshold exists
            // to prevent — so only wake when it has actually drained.
            if ((s.channel?.bufferedAmount() ?: 0) <= LOW_WATER_BYTES) io.launch { pump(s) }
        }

        override fun onStateChange() {
            if (s.channel?.state() == DataChannel.State.OPEN) io.launch { pump(s) }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            // The receiver's request, as `from:count`. Deliberately not JSON:
            // this is two integers on a hot path, and a parser here is a
            // parser a peer controls.
            val text = String(buffer.data.toByteArray()).trim()
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
        val file = runCatching { RandomAccessFile(s.path, "r") }.getOrNull() ?: return
        file.use {
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
                    val bytes = ByteArray(range.second)
                    it.seek(range.first)
                    it.readFully(bytes)
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
        }
    }

    // ── Receiver ────────────────────────────────────────────────────────────

    private fun acceptTheirCopy(context: Context, offer: ShareOffer) {
        val target = java.io.File(context.cacheDir, "together-incoming.bin")
        runCatching { target.delete() }
        val s = Session(Role.RECEIVER, offer, target.absolutePath)
        s.tracker = ShareDecisions.Tracker(
            offer.totalBytes.toLong(),
            offer.chunkBytes.toInt(),
            offer.durationMs.toLong(),
        )
        session = s
        progress = 0f
        status = "They can send it — ${offer.totalBytes.toLong() / 1_048_576} MB."
        send(ShareSignal.Accept)
    }

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
            RandomAccessFile(s.path, "rw").use {
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
            requestNext(s, TogetherManager.currentPositionMs())
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
        // Integrity, once, at the end. This is why the per-chunk checks above
        // exist: they catch the failures a whole-file hash would only report
        // after the whole file.
        val actual = runCatching { sha256(java.io.File(s.path)) }.getOrNull()
        if (actual == null || !actual.equals(s.offer.sha256, ignoreCase = true)) {
            status = "The file that arrived isn't the one that was sent."
            runCatching { java.io.File(s.path).delete() }
            end()
            return
        }
        status = "Ready — you both have it now."
        progress = 1f
        TogetherManager.onSharedFileReady(s.path)
    }

    // ── Negotiation ─────────────────────────────────────────────────────────

    private fun onTransport(context: Context, signal: TransferSignal) {
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
                                send(ShareSignal.Transport(TransferSignal.Answer(answer.description)))
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
            PeerConnection.IceServer.builder(it.urls)
                .setUsername(it.username ?: "")
                .setPassword(it.credential ?: "")
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
            send(
                ShareSignal.Transport(
                    TransferSignal.Ice(
                        candidate.sdp,
                        candidate.sdpMid,
                        candidate.sdpMLineIndex.toUShort(),
                    ),
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
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
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
                )
            }.getOrNull()
            val plan = ShareDecisions.describeVerdict(verdict)
            if (plan.proceed) {
                s.judged = true
                status = "Sending over a direct connection…"
                if (s.role == Role.SENDER) pump(s)
                return
            }
            if (!plan.retryable) {
                // Tell them why too — they are watching a progress bar that
                // would otherwise sit at zero forever.
                verdict?.reason?.let { send(ShareSignal.Refuse(it)) }
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
        val local = stats[pair.members["localCandidateId"] as? String]
        val remote = stats[pair.members["remoteCandidateId"] as? String]
        if (local == null || remote == null) return null
        return (local.members["candidateType"] as? String).orEmpty() to
            (remote.members["candidateType"] as? String).orEmpty()
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private fun send(signal: ShareSignal) {
        io.launch {
            runCatching { ComradeCore.togetherShareTyped(signal) }
                .onFailure { Log.w(TAG, "share signal failed", it) }
        }
    }

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

    private fun sha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Mirrors `comrade_core::share::SHARE_CHUNK_BYTES`. */
    private const val CHUNK_BYTES = 16 * 1024

    /** How many one-second looks to take before giving up on ICE settling. */
    private const val RETRY_WHILE_UNSETTLED = 10
}
