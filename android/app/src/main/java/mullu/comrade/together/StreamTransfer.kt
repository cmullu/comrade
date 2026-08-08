package mullu.comrade.together

import android.content.Context
import android.util.Log
import mullu.comrade.ComradeCore
import mullu.comrade.call.CallManager
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import uniffi.comrade_core.ShareSignal
import uniffi.comrade_core.TransferSignal

/**
 * Carrying the picture and the sound of what one person is playing to the other.
 *
 * `docs/TOGETHER.md` §15. A second `PeerConnection` between the same two
 * devices, alongside the one a file handover uses, and negotiated over the same
 * signals — `ShareSignal::Transport` carries `TransferSignal`'s offer, answer
 * and ICE, none of which say anything about *what* is being negotiated.
 *
 * ## The SDP is the intent
 *
 * There is no new wire type for "stream it instead of sending it", and there did
 * not need to be. The handover's exchange is `Ask` → `Offer` → `Accept` →
 * `Transport…`, so a **`Transport` offer that arrives with no armed transfer
 * cannot be a file** — nothing asked for one and nothing accepted one. That is
 * the signal, and it costs no protocol change, no `frb` regeneration and no
 * version skew: an older build simply drops an offer it has no session for,
 * which is what it already does.
 *
 * The offer's own m-lines then say the rest. `FileTransfer` opens a data
 * channel; this adds audio and video tracks, so the receiver knows from the SDP
 * what it has been handed.
 *
 * ## Why its own connection rather than the transfer's
 *
 * The same reason the transfer does not share the call's, one level along:
 * congestion. A film is a continuous encode with a deadline and a handover is a
 * bulk push, and putting them under one congestion controller means the bulk
 * push wins and the film stutters. Two connections cost one more negotiation and
 * buy isolation.
 *
 * ## What it deliberately does not do
 *
 * It does not decide *whether* to stream — that is the session's, and
 * [TogetherManager] asks [PlaybackModeDecision] — and it does not own the
 * capture. It is the pipe.
 */
object StreamTransfer {

    private const val TAG = "StreamTransfer"
    private const val STREAM_ID = "comrade_together_stream"

    /** What the receiving side does with the tracks that arrive. */
    interface Sink {
        fun onRemoteVideo(track: VideoTrack?)
        fun onRemoteAudio(track: AudioTrack?)

        /** The connection failed or the far side went away. */
        fun onLost()
    }

    private class Session(val sending: Boolean) {
        var pc: PeerConnection? = null
        val pendingIce = mutableListOf<IceCandidate>()
        var remoteSet = false
        var stopped = false
    }

    @Volatile
    private var session: Session? = null
    private var sink: Sink? = null

    /** Whether a stream connection is up or being negotiated. */
    val active: Boolean get() = session != null

    fun setSink(sink: Sink?) {
        this.sink = sink
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    /**
     * Offer the other side a live stream of `video` and `audio`.
     *
     * Called by the sender when they choose to stream rather than hand the file
     * over. The offer goes out immediately: unlike a transfer there is nothing
     * to describe first, because the tracks describe themselves.
     */
    fun offer(context: Context, video: VideoTrack?, audio: AudioTrack?): Boolean {
        end()
        val s = Session(sending = true)
        val pc = newPeer(context, s) ?: return false
        s.pc = pc
        session = s
        video?.let { pc.addTrack(it, listOf(STREAM_ID)) }
        audio?.let { pc.addTrack(it, listOf(STREAM_ID)) }
        // Named so a reader does not go looking for where the constraints are
        // set: unified plan with tracks already added needs none.
        val none = MediaConstraints()
        pc.createOffer(
            object : SimpleSdpObserver("createOffer") {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    pc.setLocalDescription(SimpleSdpObserver("setLocal"), sdp)
                    send(ShareSignal.Transport(TransferSignal.Offer(sdp.description)))
                }
            },
            none,
        )
        return true
    }

    // ── Receiving ───────────────────────────────────────────────────────────

    /**
     * One step of a stream negotiation.
     *
     * Reached from [ShareTransfer] only for signals the file engine has no
     * session for — see "the SDP is the intent" above.
     */
    fun onTransport(context: Context, signal: TransferSignal) {
        when (signal) {
            is TransferSignal.Offer -> onOffer(context, signal.sdp)
            is TransferSignal.Answer -> {
                val pc = session?.pc ?: return
                pc.setRemoteDescription(
                    object : SimpleSdpObserver("setRemote(answer)") {
                        override fun onSetSuccess() = drainIce()
                    },
                    SessionDescription(SessionDescription.Type.ANSWER, signal.sdp),
                )
            }
            is TransferSignal.Ice -> {
                val candidate = IceCandidate(
                    signal.sdpMid.orEmpty(),
                    signal.sdpMLineIndex?.toInt() ?: 0,
                    signal.candidate,
                )
                val s = session
                if (s == null || !s.remoteSet) {
                    // Trickled candidates routinely arrive before the answer
                    // they belong to; dropping them costs the connection its
                    // best path rather than failing loudly.
                    s?.pendingIce?.add(candidate)
                    return
                }
                runCatching { s.pc?.addIceCandidate(candidate) }
            }
        }
    }

    private fun onOffer(context: Context, sdp: String) {
        end()
        val s = Session(sending = false)
        val pc = newPeer(context, s) ?: return
        s.pc = pc
        session = s
        pc.setRemoteDescription(
            object : SimpleSdpObserver("setRemote(offer)") {
                override fun onSetSuccess() {
                    s.remoteSet = true
                    drainIce()
                    pc.createAnswer(
                        object : SimpleSdpObserver("createAnswer") {
                            override fun onCreateSuccess(answer: SessionDescription) {
                                pc.setLocalDescription(SimpleSdpObserver("setLocal"), answer)
                                send(ShareSignal.Transport(TransferSignal.Answer(answer.description)))
                            }
                        },
                        MediaConstraints(),
                    )
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, sdp),
        )
    }

    private fun drainIce() {
        val s = session ?: return
        s.remoteSet = true
        val pending = s.pendingIce.toList()
        s.pendingIce.clear()
        pending.forEach { runCatching { s.pc?.addIceCandidate(it) } }
    }

    /** Tear the connection down. Safe to call twice, and from any thread. */
    fun end() {
        val s = session ?: return
        session = null
        s.stopped = true
        runCatching { s.pc?.close() }
        sink?.onRemoteVideo(null)
        sink?.onRemoteAudio(null)
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private fun newPeer(context: Context, s: Session): PeerConnection? {
        val factory = CallManager.sharedFactory(context) ?: return null
        // The same structural policy the file transfer uses: a direct-only
        // device gathers no relay candidate at all, rather than gathering one
        // and declining to use it. A stream is far more bytes than a call, so
        // if anything the argument for not relaying it is stronger.
        val strategy = if (ComradeCore.shareIceServersAllowed()) {
            uniffi.comrade_core.IceStrategy.STUN_AND_TURN
        } else {
            uniffi.comrade_core.IceStrategy.STUN_ONLY
        }
        val servers = ComradeCore.callIceServersForTyped(strategy).map {
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
        return factory.createPeerConnection(config, observer(s))
    }

    private fun observer(s: Session) = object : PeerConnection.Observer {
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

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            when (val track = receiver.track()) {
                is VideoTrack -> sink?.onRemoteVideo(track)
                is AudioTrack -> sink?.onRemoteAudio(track)
                else -> Unit
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            if (newState == PeerConnection.PeerConnectionState.FAILED ||
                newState == PeerConnection.PeerConnectionState.CLOSED
            ) {
                if (!s.stopped) sink?.onLost()
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) = Unit
    }

    private fun send(signal: ShareSignal) {
        runCatching { ComradeCore.togetherShareTyped(signal) }
            .onFailure { Log.w(TAG, "stream signal failed", it) }
    }

    /** The three callbacks nobody needs, written once instead of four times. */
    private open class SimpleSdpObserver(private val what: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) {
            Log.w(TAG, "$what failed: $error")
        }

        override fun onSetFailure(error: String?) {
            Log.w(TAG, "$what failed: $error")
        }
    }
}
