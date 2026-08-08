package mullu.comrade.together

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import mullu.comrade.ComradeCore
import mullu.comrade.transfer.FileTransfer
import mullu.comrade.transfer.ShareDecisions
import uniffi.comrade_core.RefusalReason
import uniffi.comrade_core.ShareOffer
import uniffi.comrade_core.ShareSignal
import uniffi.comrade_core.TransferSignal

/**
 * Handing the file over, when only one of you has what you are playing.
 *
 * The bytes are moved by [FileTransfer], which is the same engine an attachment
 * handoff drives. What lives here is only what is *about a session*: the four
 * facts that used to be baked into the engine —
 *
 *  * signals ride the session envelope ([ComradeCore.togetherShareTyped]), which
 *    is what stops this being a way to open a peer-to-peer connection to someone
 *    who never agreed to watch anything,
 *  * the file we can send is the one the player has open,
 *  * incoming bytes stage in `cache/together-share/`,
 *  * a finished file goes to [TogetherManager] to be played.
 *
 * The sequence is `together`'s four steps and not the handoff's three: the side
 * *lacking* the file discovers that first, so it [ask]s.
 */
object ShareTransfer {

    private const val TAG = "ShareTransfer"

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The type is spelled out because [FileTransfer.Wiring.onReceived] refers
    // back to `engine`, and inference cannot close that loop.
    private val engine: FileTransfer = FileTransfer(
        object : FileTransfer.Wiring {
            override fun sendTransport(signal: TransferSignal) = send(ShareSignal.Transport(signal))

            override fun sendRefuse(reason: RefusalReason) = send(ShareSignal.Refuse(reason))

            override fun stagingDir(context: Context): File = File(context.cacheDir, "together-share")

            /**
             * The hash is peer-supplied and this is a path — see
             * [ShareDecisions.incomingFileName]'s own tests. Never null here:
             * a hash that survives sanitising as nothing at all still gets a
             * name, because refusing a handover over a cosmetic detail would
             * strand a session that is otherwise fine.
             */
            override fun stagedName(sha256: String): String =
                ShareDecisions.incomingFileName(sha256)

            /**
             * Requests are anchored at the playhead, so a seek costs one request
             * rather than a re-download.
             */
            override fun playheadMs(): Long = TogetherManager.currentPositionMs()

            // Through a method rather than inline, because the body reads
            // `engine` and this literal is `engine`'s own initialiser.
            override fun onReceived(path: String) = onIncomingReady(path)
        },
    )

    /**
     * The bytes as they land, for a player that should start now rather than
     * when the transfer finishes (`docs/TOGETHER.md` §12). Null unless this
     * device is receiving.
     */
    fun streamingSource(): mullu.comrade.transfer.PartialFileDataSource? = engine.streamingSource()

    /**
     * What a reader at `posMs` should do about the bytes that have arrived, or
     * `null` when nothing is being received and the question does not apply.
     *
     * Core owns the thresholds and this side owns the bitmap — the transfer's
     * usual division of labour, and the reason the answer is
     * [mullu.comrade.transfer.ShareReadPolicy]'s rather than this file's.
     */
    fun readVerdictAt(posMs: Long, playing: Boolean) = engine.readVerdictAt(posMs, playing)

    /** What the screen shows about the handover, if anything. */
    val status: String? get() = engine.status

    /** Progress 0..1 while receiving, or null. */
    val progress: Float? get() = engine.progress

    /** The relay question the screen is showing, or null when there is none. */
    val consentQuestion: StateFlow<String?> get() = engine.consentQuestion

    // ── Entry points ────────────────────────────────────────────────────────

    /** We joined a session for something we do not have. Ask for it. */
    fun ask() {
        runCatching { ComradeCore.togetherShareTyped(ShareSignal.Ask) }
            .onFailure { Log.w(TAG, "ask failed", it) }
        engine.note("Asking them to send it…")
    }

    /**
     * One step of the handover arrived. Everything routes through here, so the
     * protocol lives in one readable place rather than five callbacks.
     */
    fun onSignal(context: Context, signal: ShareSignal, localPath: String?, durationMs: Long) {
        when (signal) {
            is ShareSignal.Ask -> io.launch { offerOurCopy(localPath, durationMs) }
            is ShareSignal.Offer -> io.launch { acceptTheirCopy(context, signal.offer) }
            is ShareSignal.Accept -> io.launch { engine.beginNegotiation(context) }
            is ShareSignal.Refuse -> engine.onRefused(signal.reason)
            // **The SDP is the intent** (`docs/TOGETHER.md` §15). A transport
            // signal only reaches the file engine if a transfer was armed — and
            // arming needs an `Offer` this side answered with `Accept`. So an
            // offer arriving with nothing armed cannot be a file: nobody asked
            // for one. That is what a live stream looks like on this wire, and
            // it costs no new signal, no `frb` regeneration and no version skew
            // — an older build drops an offer it has no session for, which is
            // exactly what it does today.
            is ShareSignal.Transport ->
                if (engine.active) {
                    io.launch { engine.onTransport(context, signal.signal) }
                } else {
                    io.launch { StreamTransfer.onTransport(context, signal.signal) }
                }
        }
    }

    /** They said yes to this transfer. Re-judge; nothing is assumed from here. */
    fun grantShareConsent() = engine.grantConsent()

    /** They said no. That is a real outcome, and the far side is told. */
    fun refuseShareConsent() = engine.refuseConsent()

    /** Tear everything down. Safe to call twice, and from any thread. */
    fun end() = engine.end()

    // ── Sender ──────────────────────────────────────────────────────────────

    private fun offerOurCopy(localPath: String?, durationMs: Long) {
        if (localPath == null) return // we do not have it either; nothing to offer
        val file = File(localPath)
        if (!file.isFile) return
        val sha = runCatching { FileTransfer.sha256(file) }.getOrNull() ?: return
        val offer = ShareOffer(
            totalBytes = file.length().toULong(),
            chunkBytes = FileTransfer.CHUNK_BYTES.toUInt(),
            sha256 = sha,
            durationMs = durationMs.toULong(),
        )
        engine.armSend(offer) { FileTransfer.PathSource(localPath) }
        engine.note("Waiting for them to accept…")
        runCatching { ComradeCore.togetherShareTyped(ShareSignal.Offer(offer)) }
            .onFailure { Log.w(TAG, "offer failed", it) }
    }

    // ── Receiver ────────────────────────────────────────────────────────────

    /**
     * Accepted without asking a person, unlike an attachment handoff: this side
     * *asked* for the file, so the offer is the answer to its own question.
     */
    private fun acceptTheirCopy(context: Context, offer: ShareOffer) {
        if (engine.armReceive(context, offer) == null) return
        engine.note("They can send it — ${offer.totalBytes.toLong() / 1_048_576} MB.")
        // Open the player on the partial file *now*, before a byte has moved.
        // This is what makes §12 real rather than a note in a doc: the session
        // starts on the head of the file and the decoder blocks on the rest,
        // which is what core's `playable_at`/`runway_ms` have been ready for
        // since the transfer landed.
        //
        // Ordered before `Accept` deliberately — the first chunk can arrive as
        // soon as they hear it, and a source armed afterwards would miss the
        // wake-ups for whatever landed in between and wait out a full timeout
        // slice for bytes it already had.
        TogetherManager.onSharedFileStreaming()
        send(ShareSignal.Accept)
    }

    /**
     * The file finished arriving and its hash checked out. The session does not
     * restart; it simply stops being one-sided.
     */
    private fun onIncomingReady(path: String) {
        engine.note("Ready — you both have it now.")
        TogetherManager.onSharedFileReady(path)
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private fun send(signal: ShareSignal) {
        io.launch {
            runCatching { ComradeCore.togetherShareTyped(signal) }
                .onFailure { Log.w(TAG, "share signal failed", it) }
        }
    }
}
