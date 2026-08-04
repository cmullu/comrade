package mullu.comrade.handoff

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mullu.comrade.ComradeCore
import mullu.comrade.transfer.FileTransfer
import mullu.comrade.ui.formatAttachmentSize
import uniffi.comrade_core.AttachmentHandoff
import uniffi.comrade_core.HandoffSignal
import uniffi.comrade_core.RefusalReason
import uniffi.comrade_core.ShareOffer
import uniffi.comrade_core.TransferSignal

/**
 * The live large-attachment transfer on this device: one file, one peer, one
 * state flow.
 *
 * Shaped after [mullu.comrade.together.TogetherManager] and
 * [mullu.comrade.call.CallManager] for the reason that matters most here — **a
 * 400 MB transfer must not die because a Compose screen was disposed.** Nothing
 * about it lives in a screen: the thread renders this flow and answers it, and
 * navigating away leaves the bytes moving.
 *
 * The bytes themselves are [FileTransfer]'s, unchanged from the watch-together
 * handover. What this adds is the three-step protocol from
 * `comrade_core::handoff` — offer → accept → transport — and the honest
 * bookkeeping around it:
 *
 *  * **The sender initiates**, because they picked the file. There is no `Ask`.
 *  * **The receiver's yes comes from a person**, never from this class. An
 *    `Accept` is what authorises a peer connection at all, so it is only ever
 *    sent from [accept].
 *  * **One transfer at a time.** A second offer arriving mid-transfer is
 *    dropped and logged rather than answered: `Decline` would claim a person
 *    said no and `Refuse` would claim a network fact, and neither is true.
 *
 * ## Two costs this road cannot avoid
 *
 * The recipient must be **online now** — a data channel needs both devices
 * present, and there is deliberately no store-and-forward — and a **direct path
 * must exist**, which `AUDIT.md` §8.1 measures as failing for 30-40% of remote
 * pairs. Both are stated to the person by [HandoffDecisions.routeNote] before
 * they send, and named again with [HandoffDecisions.fallbackAdvice] when a
 * transfer actually fails that way.
 */
object AttachmentHandoffManager {

    private const val TAG = "AttachmentHandoff"

    /** Where incoming bytes land. Its own directory, so it can be swept whole. */
    private const val STAGING_DIR = "attachment-handoff"

    /** How often the screen is refreshed from the engine's status and progress. */
    private const val POLL_MS = 300L

    /** How the thread reads. Idle means there is nothing to show at all. */
    sealed interface UiState {
        /** Whose thread this belongs in. Empty for [Idle]. */
        val peer: String

        data object Idle : UiState {
            override val peer: String = ""
        }

        /** Sender: hashing the file. Nothing has been offered yet. */
        data class Preparing(
            override val peer: String,
            val fileName: String,
            val sizeBytes: Long,
        ) : UiState

        /** Sender: offered, waiting for a person on the other end to answer. */
        data class Offered(
            override val peer: String,
            val fileName: String,
            val sizeBytes: Long,
        ) : UiState

        /**
         * Receiver: they have offered, and nobody has answered yet.
         *
         * [refusal] is non-null when this device cannot take the offer at all —
         * a malformed hash, a chunk size we do not speak, more bytes than there
         * is room for. Accept is not offered in that case: a button that cannot
         * work is worse than no button.
         */
        data class Incoming(
            override val peer: String,
            val headline: String,
            val caption: String,
            val sizeBytes: Long,
            val refusal: String?,
        ) : UiState

        /** Either side: bytes are moving, or a route is being worked out. */
        data class Moving(
            override val peer: String,
            val fileName: String,
            val sending: Boolean,
            val status: String?,
            val fraction: Float?,
        ) : UiState

        /**
         * Either side: it is over. [advice] carries what would work instead when
         * the reason was the road rather than the file.
         */
        data class Ended(
            override val peer: String,
            val message: String,
            val advice: String? = null,
            /** The staged plaintext, when one arrived. Droppable — AUDIT S-4. */
            val receivedPath: String? = null,
            val receivedMime: String = "",
        ) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    /** Which side of one transfer this device is. */
    private enum class Role { SENDER, RECEIVER }

    /**
     * The transfer this device is party to.
     *
     * The `transferId` is the replay guard: a signal naming an id we do not have
     * is dropped rather than reviving an abandoned transfer, exactly as the Rust
     * module's doc comment requires.
     */
    private class Live(
        val role: Role,
        val peer: String,
        val transferId: String,
        val offer: ShareOffer,
        val fileName: String,
        val mimeType: String,
        val caption: String,
    )

    @Volatile private var live: Live? = null

    private val engine: FileTransfer = FileTransfer(
        object : FileTransfer.Wiring {
            override fun sendTransport(signal: TransferSignal) =
                send(HandoffSignal.Transport(signal))

            override fun sendRefuse(reason: RefusalReason) = send(HandoffSignal.Refuse(reason))

            override fun stagingDir(context: Context): File = File(context.cacheDir, STAGING_DIR)

            /**
             * Null refuses the transfer. The hash is peer-supplied and this is a
             * path — see [HandoffDecisions.stagedFileName].
             */
            override fun stagedName(sha256: String): String? =
                HandoffDecisions.stagedFileName(sha256)

            /** Nothing is being played while it arrives, so there is no playhead. */
            override fun playheadMs(): Long = 0

            // Through a method because the body reads `engine`, and this literal
            // is `engine`'s own initialiser.
            override fun onReceived(path: String) = onIncomingReady(path)
        },
    )

    /**
     * The relay question, when the policy wants a person to agree to *this*
     * transfer. Surfaced by the thread; unanswered it stalls in silence.
     */
    val consentQuestion: StateFlow<String?> get() = engine.consentQuestion

    fun grantRelayConsent() = engine.grantConsent()

    fun refuseRelayConsent() = engine.refuseConsent()

    // ── Sender ──────────────────────────────────────────────────────────────

    /**
     * Offer a file that is too large for the hosted road.
     *
     * Returns why it could not be offered, or null when the offer is on its way.
     * Nothing is negotiated here — the sender may not build a peer connection
     * until a person on the other end says yes.
     *
     * The hash runs on [Dispatchers.IO]: a 400 MB SHA-256 is seconds of CPU and
     * has no business on the thread drawing the composer.
     */
    fun offer(
        context: Context,
        peer: String,
        uri: Uri,
        fileName: String,
        mimeType: String,
        caption: String,
        sizeBytes: Long,
    ): String? {
        if (live != null || engine.active) {
            return "One large file at a time — this one has to finish first."
        }
        val app = context.applicationContext
        _state.value = UiState.Preparing(peer, HandoffDecisions.displayFileName(fileName), sizeBytes)
        io.launch {
            val offer = runCatching {
                // Read the far end of the file first. A provider that hands back
                // a pipe rather than a seekable descriptor cannot serve a
                // receiver-driven transfer at all, and finding that out *now*
                // means the person is told before an offer exists rather than
                // watching a stalled progress bar afterwards.
                ContentUriSource(app, uri).use { it.read(maxOf(sizeBytes - 1, 0), 1) }
                val sha = app.contentResolver.openInputStream(uri)?.use { FileTransfer.sha256(it) }
                    ?: throw IOException("could not read the file")
                ShareOffer(
                    totalBytes = sizeBytes.toULong(),
                    chunkBytes = FileTransfer.CHUNK_BYTES.toUInt(),
                    sha256 = sha,
                    // A handoff carries no duration: nothing plays while it
                    // arrives, so the tracker's playable-early arithmetic has
                    // nothing to be early for.
                    durationMs = 0uL,
                )
            }.getOrElse {
                Log.w(TAG, "could not prepare $fileName", it)
                _state.value = UiState.Ended(
                    peer,
                    "Couldn't read that file well enough to send it directly.",
                    HandoffDecisions.fallbackAdvice(),
                )
                return@launch
            }
            val transferId = runCatching { ComradeCore.newAttachmentTransferId() }.getOrNull()
                ?: run {
                    _state.value = UiState.Ended(peer, "Couldn't start a transfer.")
                    return@launch
                }
            live = Live(Role.SENDER, peer, transferId, offer, fileName, mimeType, caption)
            engine.armSend(offer) { ContentUriSource(app, uri) }
            val sent = runCatching {
                ComradeCore.attachmentHandoffSendTyped(
                    peer,
                    transferId,
                    HandoffSignal.Offer(
                        AttachmentHandoff(
                            shape = offer,
                            mimeType = mimeType,
                            fileName = fileName,
                            caption = caption,
                        ),
                    ),
                )
            }
            if (sent.isFailure) {
                Log.w(TAG, "offer failed", sent.exceptionOrNull())
                reset()
                _state.value = UiState.Ended(peer, "Couldn't reach them to offer the file.")
                return@launch
            }
            _state.value = UiState.Offered(
                peer,
                HandoffDecisions.displayFileName(fileName),
                sizeBytes,
            )
        }
        return null
    }

    /** Take the offer back, or stop a transfer that is already running. */
    fun cancel(context: Context) {
        val l = live ?: run {
            dismiss(context)
            return
        }
        // Only the sender has a signal for this. A receiver stopping partway has
        // nothing to say in the protocol — the channel closing is what the sender
        // sees — so it stops locally and drops what it staged rather than sending
        // a `Decline` that would claim the answer had been no all along.
        if (l.role == Role.SENDER) send(HandoffSignal.Withdraw)
        reset()
        engine.purgeStaging(context.applicationContext)
        _state.value = UiState.Idle
    }

    // ── Receiver ────────────────────────────────────────────────────────────

    /**
     * A person said yes. This is the only place an `Accept` is sent, because it
     * is the only thing that authorises the other device to open a connection.
     */
    fun accept(context: Context) {
        val l = live ?: return
        val showing = _state.value as? UiState.Incoming ?: return
        if (l.role != Role.RECEIVER || showing.refusal != null) return
        val app = context.applicationContext
        if (engine.armReceive(app, l.offer) == null) {
            // Only reachable if the offer changed under us; the same check ran
            // before this card was ever drawn.
            reset()
            _state.value = UiState.Ended(l.peer, "That offer can't be staged safely.")
            return
        }
        _state.value = UiState.Moving(
            l.peer,
            HandoffDecisions.displayFileName(l.fileName),
            sending = false,
            status = "Waiting for them to start sending…",
            fraction = 0f,
        )
        send(HandoffSignal.Accept)
        startPolling()
    }

    /** A person said no. `Decline`, never `Refuse` — this is not a network fact. */
    fun decline() {
        val l = live ?: return
        if (l.role != Role.RECEIVER) return
        send(HandoffSignal.Decline)
        reset()
        _state.value = UiState.Idle
    }

    /** Clear a finished card, and with it the plaintext it left staged. */
    fun dismiss(context: Context) {
        reset()
        engine.purgeStaging(context.applicationContext)
        _state.value = UiState.Idle
    }

    // ── Incoming, from the bridge ───────────────────────────────────────────

    /**
     * One step of a handoff arrived.
     *
     * The runtime has already established that `peer` is an accepted
     * conversation — the same gate a call signal clears. That gate says nothing
     * about the *contents* being well-formed, so every field is still checked
     * here before it reaches a filesystem or a screen.
     */
    fun onSignal(context: Context, peer: String, transferId: String, signal: HandoffSignal) {
        val app = context.applicationContext
        if (signal is HandoffSignal.Offer) {
            onOffer(app, peer, transferId, signal.attachment)
            return
        }
        val l = live
        if (l == null || l.transferId != transferId || l.peer != peer) {
            // An id we do not have, or the right id from the wrong peer. Either
            // way this is not our transfer and reviving one would be the bug.
            Log.i(TAG, "dropped a handoff signal for a transfer this device does not have")
            return
        }
        when (signal) {
            is HandoffSignal.Offer -> Unit // handled above
            // Sender-only signals, checked rather than trusted: a peer sending
            // its own `Accept` for a transfer it is receiving is confused or
            // probing, and either way the answer is to drop it.
            is HandoffSignal.Accept -> if (l.role == Role.SENDER) onAccepted(app, l)
            is HandoffSignal.Decline -> if (l.role == Role.SENDER) {
                reset()
                _state.value = UiState.Ended(l.peer, "They didn't want it.")
            }
            is HandoffSignal.Withdraw -> if (l.role == Role.RECEIVER) {
                reset()
                engine.purgeStaging(app)
                _state.value = UiState.Ended(l.peer, "They took the offer back.")
            }
            is HandoffSignal.Refuse -> {
                engine.onRefused(signal.reason)
                val message = engine.status ?: "There's no route for this file right now."
                reset()
                _state.value = UiState.Ended(l.peer, message, HandoffDecisions.fallbackAdvice())
            }
            is HandoffSignal.Transport -> io.launch { engine.onTransport(app, signal.signal) }
        }
    }

    private fun onOffer(
        context: Context,
        peer: String,
        transferId: String,
        attachment: AttachmentHandoff,
    ) {
        if (live != null || engine.active) {
            // Neither `Decline` (no person said no) nor `Refuse` (no network
            // fact) is true, so the honest answer is silence: their offer simply
            // goes unanswered and their own UI says so.
            Log.i(TAG, "dropped an offer arriving during another transfer")
            return
        }
        val shape = attachment.shape
        val totalBytes = shape.totalBytes.toLong()
        val refusal = HandoffDecisions.offerRefusal(
            totalBytes,
            shape.chunkBytes.toInt(),
            shape.sha256,
        ) ?: roomRefusal(context, totalBytes)
        live = Live(
            Role.RECEIVER,
            peer,
            transferId,
            shape,
            attachment.fileName,
            attachment.mimeType,
            HandoffDecisions.displayCaption(attachment.caption),
        )
        _state.value = UiState.Incoming(
            peer = peer,
            headline = HandoffDecisions.offerHeadline(
                attachment.mimeType,
                attachment.fileName,
                totalBytes,
            ),
            caption = HandoffDecisions.displayCaption(attachment.caption),
            sizeBytes = totalBytes,
            refusal = refusal,
        )
    }

    /**
     * Whether there is room for this, asked of the filesystem the bytes would
     * land on rather than assumed. A transfer that fails at 90% because the cache
     * filled up wastes the whole download and the sender's upload with it.
     */
    private fun roomRefusal(context: Context, totalBytes: Long): String? {
        val dir = File(context.cacheDir, STAGING_DIR)
        dir.mkdirs()
        val usable = runCatching { dir.usableSpace }.getOrDefault(0L)
        if (usable in 1 until totalBytes) {
            return "There isn't room on this device for it — " +
                "${formatAttachmentSize(totalBytes)} needs more free space " +
                "than there is."
        }
        return null
    }

    private fun onAccepted(context: Context, l: Live) {
        _state.value = UiState.Moving(
            l.peer,
            HandoffDecisions.displayFileName(l.fileName),
            sending = true,
            status = "Working out a route to them…",
            fraction = null,
        )
        io.launch { engine.beginNegotiation(context) }
        startPolling()
    }

    /**
     * The file arrived and its hash checked out.
     *
     * The state lands *before* [reset], so the poll loop below can never catch a
     * torn-down engine with a still-moving state and report a finished transfer
     * as a failed one.
     */
    private fun onIncomingReady(path: String) {
        val l = live
        _state.value = UiState.Ended(
            peer = l?.peer.orEmpty(),
            message = "Got it — ${HandoffDecisions.displayFileName(l?.fileName.orEmpty())}.",
            receivedPath = path,
            receivedMime = l?.mimeType.orEmpty(),
        )
        reset()
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    /**
     * The engine reports through `@Volatile` fields, which nothing recomposes on,
     * so the flow is refreshed from them while a transfer is live. Progress comes
     * from the tracker's own `fraction` rather than from a byte counter kept here.
     */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = io.launch {
            while (true) {
                val moving = _state.value as? UiState.Moving ?: break
                if (!engine.active) {
                    // The engine stopped by itself: a route the relay policy
                    // refused, a write that failed, a connection that never
                    // opened. Its own last sentence is the honest one, and the
                    // advice names the thing that would have worked — otherwise a
                    // person is left with a stalled bar and no next move.
                    _state.value = UiState.Ended(
                        peer = moving.peer,
                        message = engine.status ?: "The transfer stopped.",
                        advice = HandoffDecisions.fallbackAdvice(),
                    )
                    live = null
                    break
                }
                _state.value = moving.copy(status = engine.status, fraction = engine.progress)
                delay(POLL_MS)
            }
        }
    }

    private fun reset() {
        pollJob?.cancel()
        pollJob = null
        live = null
        engine.end()
    }

    private fun send(signal: HandoffSignal) {
        val l = live ?: return
        io.launch {
            runCatching {
                ComradeCore.attachmentHandoffSendTyped(l.peer, l.transferId, signal)
            }.onFailure { Log.w(TAG, "handoff signal failed", it) }
        }
    }

    /**
     * A `content://` file read by offset.
     *
     * A descriptor rather than a copy: staging 400 MB into the cache to give the
     * sender a path would put a second plaintext copy on this device (AUDIT S-4)
     * and cost the disk twice. Positional reads leave the channel's own position
     * alone, so one open descriptor serves every chunk request.
     *
     * A provider that hands back a pipe throws here, which is why [offer] reads
     * the last byte before it offers anything.
     */
    private class ContentUriSource(context: Context, uri: Uri) : FileTransfer.Source {
        private val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("no descriptor for $uri")
        private val channel = FileInputStream(descriptor.fileDescriptor).channel

        override fun read(offset: Long, length: Int): ByteArray {
            val buffer = ByteBuffer.allocate(length)
            var at = offset
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, at)
                if (read < 0) throw IOException("short read at $at")
                at += read
            }
            return buffer.array()
        }

        override fun close() {
            runCatching { channel.close() }
            runCatching { descriptor.close() }
        }
    }
}
