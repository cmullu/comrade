package mullu.comrade.together

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log
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

/**
 * The live watch-together session on this device: one player, one peer, one
 * state flow.
 *
 * Shaped after [mullu.comrade.call.CallManager] on purpose — an object holding a
 * `StateFlow`, owning its own hardware, and starting/stopping its foreground
 * service from the same points it starts and stops playback, rather than from
 * any Compose tree. A session must not end because a screen was disposed.
 *
 * **What this does not own:** the drift arithmetic (that is
 * `comrade_core::together`, shared with desktop) and the echo/scrubber decisions
 * (those are [TogetherDecisions], pure and unit-tested). This is the wiring
 * between them and the player.
 */
object TogetherManager {

    /** How the screen reads. Mirrors `sessionStatusLabel` in the desktop module. */
    sealed interface UiState {
        data object Idle : UiState

        data class Invited(
            val peer: String,
            val peerLabel: String,
            val title: String,
            val youtube: Boolean,
        ) : UiState

        data class Live(
            val peer: String,
            val peerLabel: String,
            val title: String,
            val weLead: Boolean,
            val joined: Boolean,
            val ready: Boolean,
            val playing: Boolean,
            val positionMs: Long,
            val durationMs: Long,
            val status: Status,
        ) : UiState
    }

    /** The honest vocabulary — never "synced", never "in sync". */
    enum class Status { WaitingForThem, OpenYourCopy, Together, CatchingUp, LostTrack, TheyPaused }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null

    private var player: TogetherPlayer? = null
    private val suppressor = TogetherDecisions.EchoSuppressor()
    private var scrub = TogetherDecisions.ScrubState(scrubbing = false, pendingRemoteMs = null)
    private var appContext: Context? = null

    /** What the peer named, kept so the library can be searched for it. */
    private var wanted: uniffi.comrade_core.Recording? = null
    private var wantedMs: Long = 0
    private var focusRequest: AudioFocusRequest? = null

    /**
     * The file this device actually has open, if it is one we can read back —
     * which is what makes us able to *send* it. A content:// URI we were handed
     * by a picker is not, so this stays null for those and the handover simply
     * does not offer, rather than failing halfway through.
     */
    private var openedPath: String? = null
    private var openedDurationMs: Long = 0

    /**
     * Set by tests that must not touch the foreground-service contract, exactly
     * as `CallManager.disableCallServiceForTest` does. Keep it: a test that
     * genuinely exercises promotion should be its own test rather than flipping
     * this one.
     */
    @Volatile
    var disableServiceForTest: Boolean = false

    // ── Incoming, from the bridge ───────────────────────────────────────────

    /**
     * They invited us.
     *
     * Before asking the listener to go and find a file, look for it: if the
     * invitation named a recording and this device's own library holds a
     * confident match, the session can just start. That is the whole point of
     * carrying a recording identity rather than a bare duration — the Antra idea
     * (`docs/TOGETHER.md` §2), minus the acquiring.
     */
    fun onInvited(
        context: Context,
        peer: String,
        peerLabel: String,
        recording: uniffi.comrade_core.Recording?,
        durationMs: Long,
        youtube: Boolean,
    ) {
        appContext = context.applicationContext
        wanted = recording
        wantedMs = durationMs
        val title = recording?.let { if (it.artist.isBlank()) it.title else "${it.artist} — ${it.title}" }.orEmpty()
        _state.value = UiState.Invited(peer, peerLabel, title, youtube)

        if (recording == null || youtube) return
        val found = runCatching { LibraryResolver.resolve(context, recording, durationMs) }.getOrNull()
        if (found != null) join(context, found.uri)
    }

    /**
     * Look again for the invitation's recording, now that we may read the
     * library, and join if it is here.
     *
     * [onInvited] runs this once on arrival, but at that moment the answer is
     * whatever the permission happened to be — and the invitation is exactly the
     * moment someone is most willing to grant it. Returns whether a copy was
     * found, so the screen can say "not on this phone" rather than leaving a tap
     * that appears to do nothing.
     *
     * A no-op unless we are still holding an invitation: by the time a grant
     * comes back the session may already be live or abandoned, and re-opening a
     * file under a running player is the one thing this must not do.
     */
    fun lookAgain(context: Context): Boolean {
        if (_state.value !is UiState.Invited) return false
        val recording = wanted ?: return false
        val found = runCatching { LibraryResolver.resolve(context, recording, wantedMs) }.getOrNull()
            ?: return false
        join(context, found.uri)
        return true
    }

    fun onJoined() {
        (_state.value as? UiState.Live)?.let { _state.value = it.copy(joined = true, status = Status.OpenYourCopy) }
    }

    /**
     * They played, paused or seeked.
     *
     * `posMs` has already been carried forward through the message's flight time
     * by `comrade_core::together` — this applies it as given and must **not**
     * compensate again. `applyInMs` is non-zero only when the sender was on a
     * transport fast enough to schedule ahead, in which case both players change
     * state on the same instant instead of one chasing the other.
     */
    fun onCommand(posMs: Long, playing: Boolean, applyInMs: Long) {
        val p = player ?: return
        if (applyInMs > 0) {
            scope.launch {
                delay(applyInMs)
                applyCommand(p, posMs, playing)
            }
        } else {
            applyCommand(p, posMs, playing)
        }
    }

    private fun applyCommand(p: TogetherPlayer, posMs: Long, playing: Boolean) {
        // A remote seek must not yank the thumb out of a finger mid-drag.
        scrub = TogetherDecisions.onRemoteSeek(scrub, posMs)
        if (scrub.scrubbing) return
        val plan = TogetherDecisions.planCommand(
            posMs,
            playing,
            TogetherDecisions.Local(p.positionMs, p.isPlaying, p.prepared),
        )
        run(p, plan)
        refreshLive(playing = playing, status = if (playing) Status.Together else Status.TheyPaused)
    }

    /** A drift correction. Emitted only when the verdict is not "hold". */
    fun onCorrection(kind: String, posMs: Long, rate: Float, playing: Boolean) {
        val p = player ?: return
        val plan = TogetherDecisions.planCorrection(
            kind,
            posMs,
            rate,
            playing,
            TogetherDecisions.Local(p.positionMs, p.isPlaying, p.prepared),
        )
        run(p, plan)
        refreshLive(status = Status.CatchingUp)
    }

    fun onEnded(byPeer: Boolean) {
        ShareTransfer.end()
        stopPlayback()
        _state.value = UiState.Idle
    }

    // ── Handing the file over ───────────────────────────────────────────────

    /**
     * Where our own player is, for the receiver's next request. Requests are
     * anchored at the playhead, so a seek costs one request rather than a
     * re-download — which only works if the transfer can ask.
     */
    fun currentPositionMs(): Long = player?.positionMs ?: 0

    /**
     * "I don't have this — send me yours."
     *
     * Joining first is deliberate and not just ordering: the handover rides the
     * session envelope, so there has to *be* a session before a byte can be
     * negotiated. That is what stops this from being a way to open a
     * peer-to-peer connection to someone who never agreed to watch anything.
     */
    fun askForTheirCopy(context: Context) {
        appContext = context.applicationContext
        val invited = _state.value as? UiState.Invited ?: return
        runCatching { ComradeCore.togetherJoinTyped() }
            .onFailure { Log.w("TogetherManager", "join before asking failed", it) }
        _state.value = UiState.Live(
            peer = invited.peer,
            peerLabel = invited.peerLabel,
            title = invited.title,
            weLead = false,
            joined = true,
            // Not ready: we have nothing to play yet. The status line says so
            // rather than showing a player that cannot start.
            ready = false,
            playing = false,
            positionMs = 0,
            durationMs = 0,
            status = Status.OpenYourCopy,
        )
        ShareTransfer.ask()
    }

    /** What the handover is doing, for the screen. Null when nothing is. */
    fun shareStatus(): String? = ShareTransfer.status

    /** One step of the handover arrived on the session channel. */
    fun onShareSignal(context: Context, signal: uniffi.comrade_core.ShareSignal) {
        appContext = context.applicationContext
        ShareTransfer.onSignal(context, signal, localPath = openedPath, durationMs = openedDurationMs)
    }

    /**
     * The file finished arriving and its hash checked out. Open it and carry on
     * from where the session already is — the point of the handover is that the
     * session does not restart, it simply stops being one-sided.
     */
    fun onSharedFileReady(path: String) {
        if (appContext == null) return
        val live = _state.value as? UiState.Live
        openPlayer(Uri.fromFile(java.io.File(path))) { durationMs ->
            openedPath = path
            openedDurationMs = durationMs
            if (live != null) _state.value = live.copy(ready = true, durationMs = durationMs)
        }
        // Whatever they are doing now is what we should be doing. No command is
        // sent: the next heartbeat's drift verdict closes the gap, and a
        // command from the side that just arrived would move *them*.
    }

    /** Apply a plan, arming its expectations first so nothing echoes back out. */
    private fun run(p: TogetherPlayer, plan: TogetherDecisions.Plan) {
        val now = System.currentTimeMillis()
        plan.expect.forEach { (kind, pos) -> suppressor.expect(kind, pos, now) }
        for (op in plan.ops) {
            when (op) {
                is TogetherDecisions.Op.Seek -> p.seekTo(op.posMs)
                is TogetherDecisions.Op.Rate -> p.setRate(op.value)
                TogetherDecisions.Op.Play -> p.play()
                TogetherDecisions.Op.Pause -> p.pause()
            }
        }
    }

    // ── Outgoing, from this device ──────────────────────────────────────────

    fun start(
        context: Context,
        peer: String,
        peerLabel: String,
        uri: Uri,
        recording: uniffi.comrade_core.Recording?,
    ) {
        appContext = context.applicationContext
        val title = recording?.title.orEmpty()
        openPlayer(uri) { durationMs ->
            ComradeCore.togetherStartTyped(
                peer,
                uniffi.comrade_core.TogetherContent.LocalFile(durationMs.toULong(), recording),
            )
            _state.value = UiState.Live(
                peer = peer,
                peerLabel = peerLabel,
                title = title,
                weLead = true,
                joined = false,
                ready = true,
                playing = false,
                positionMs = 0,
                durationMs = durationMs,
                status = Status.WaitingForThem,
            )
            startService()
        }
    }

    /** Accept an invitation, once the user has opened their own copy. */
    fun join(context: Context, uri: Uri) {
        appContext = context.applicationContext
        val invited = _state.value as? UiState.Invited ?: return
        openPlayer(uri) { durationMs ->
            ComradeCore.togetherJoinTyped()
            _state.value = UiState.Live(
                peer = invited.peer,
                peerLabel = invited.peerLabel,
                title = invited.title,
                weLead = false,
                joined = true,
                ready = true,
                playing = false,
                positionMs = 0,
                durationMs = durationMs,
                status = Status.Together,
            )
            startService()
        }
    }

    /**
     * A local play/pause/seek the user asked for.
     *
     * Deferred by [SCHEDULE_AHEAD_MS] on both devices rather than applied here
     * and chased there: a few tens of milliseconds is imperceptible on a button
     * press, and it is what makes both playheads change on the *same* instant.
     */
    fun setState(posMs: Long, playing: Boolean) {
        val p = player ?: return
        ComradeCore.togetherSetStateTyped(posMs, playing, SCHEDULE_AHEAD_MS)
        scope.launch {
            delay(SCHEDULE_AHEAD_MS)
            val now = System.currentTimeMillis()
            if (kotlin.math.abs(posMs - p.positionMs) > TogetherDecisions.EPSILON_MS) {
                suppressor.expect("seek", posMs, now)
                p.seekTo(posMs)
            }
            if (playing != p.isPlaying) {
                suppressor.expect(if (playing) "play" else "pause", null, now)
                if (playing) p.play() else p.pause()
            }
            refreshLive(playing = playing)
        }
    }

    fun onScrubStart() {
        scrub = scrub.copy(scrubbing = true)
    }

    fun onScrubRelease(posMs: Long) {
        scrub = TogetherDecisions.onScrubRelease(scrub)
        setState(posMs, (_state.value as? UiState.Live)?.playing ?: false)
    }

    fun leave() {
        runCatching { ComradeCore.togetherEndTyped() }
        stopPlayback()
        _state.value = UiState.Idle
    }

    /**
     * The app went to the background.
     *
     * Playback deliberately **continues** — that is what the foreground service
     * is for — so this does nothing but exist as the documented answer to "does
     * leaving the app pause it?". The answer is no; the notification is how you
     * get back.
     */
    fun onAppBackgrounded() = Unit

    // ── Player plumbing ─────────────────────────────────────────────────────

    private fun openPlayer(uri: Uri, onReady: (Long) -> Unit) {
        val ctx = appContext ?: return
        // Remember the readable path, if there is one: it is the difference
        // between being able to hand this file over and only being able to
        // receive one.
        openedPath = if (uri.scheme == null || uri.scheme == "file") uri.path else null
        val p = player ?: TogetherPlayer(ctx).also { player = it }
        p.setListener(object : TogetherPlayer.Listener {
            override fun onPrepared(durationMs: Long) {
                requestAudioFocus()
                openedDurationMs = durationMs
                onReady(durationMs)
                startPolling()
            }

            override fun onSeekComplete(posMs: Long) {
                emitIfUserCaused("seek", posMs)
            }

            override fun onCompletion(posMs: Long) {
                emitIfUserCaused("completion", posMs)
            }

            override fun onError(message: String) {
                Log.w(TAG, "player: $message")
            }
        })
        p.open(uri)
    }

    /** The one place a player callback becomes an outbound signal, or does not. */
    private fun emitIfUserCaused(kind: String, posMs: Long) {
        val p = player ?: return
        val emit = TogetherDecisions.classifyCallback(
            kind,
            posMs,
            p.isPlaying,
            suppressor,
            System.currentTimeMillis(),
        ) ?: return
        ComradeCore.togetherSetStateTyped(emit.posMs, emit.playing, 0)
    }

    /**
     * Feed the core our playhead and our output-latency estimate.
     *
     * This sends nothing — the ten-second wire heartbeat is the Rust side's job.
     * It only keeps the next drift verdict comparing against something true.
     */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                val p = player ?: break
                ComradeCore.togetherReportPosition(p.positionMs, p.isPlaying, p.outputLatencyMs)
                if (TogetherDecisions.pollMayMoveSlider(scrub)) {
                    refreshLive(positionMs = p.positionMs)
                }
                delay(TogetherDecisions.POLL_MS)
            }
        }
    }

    private fun refreshLive(
        playing: Boolean? = null,
        positionMs: Long? = null,
        status: Status? = null,
    ) {
        val live = _state.value as? UiState.Live ?: return
        _state.value = live.copy(
            playing = playing ?: live.playing,
            positionMs = positionMs ?: live.positionMs,
            status = status ?: live.status,
        )
    }

    private fun stopPlayback() {
        pollJob?.cancel()
        pollJob = null
        player?.release()
        player = null
        suppressor.clear()
        wanted = null
        wantedMs = 0
        scrub = TogetherDecisions.ScrubState(scrubbing = false, pendingRemoteMs = null)
        abandonAudioFocus()
        stopService()
    }

    // ── Audio focus ─────────────────────────────────────────────────────────

    /**
     * Required even though playback is foreground-service backed: without it an
     * incoming phone call plays over the film for one person and not the other,
     * and the two silently diverge. Losing focus pauses **and tells the peer**,
     * so what they see is "they paused" rather than an unexplained drift.
     */
    private fun requestAudioFocus() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) {
                player?.let { setState(it.positionMs, false) }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener(listener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        }
    }

    private fun abandonAudioFocus() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        }
        focusRequest = null
    }

    // ── The foreground service ──────────────────────────────────────────────

    private fun startService() {
        if (disableServiceForTest) return
        val ctx = appContext ?: return
        TogetherService.start(ctx)
    }

    private fun stopService() {
        if (disableServiceForTest) return
        val ctx = appContext ?: return
        TogetherService.stop(ctx)
    }

    /**
     * How far ahead a local command is scheduled.
     *
     * Imperceptible on a button press, and comfortably more than a local-network
     * round trip — so on the mesh both devices genuinely change state on the same
     * instant. Over a relay the other side will usually receive it late and
     * project instead, which is still correct, just not simultaneous.
     */
    const val SCHEDULE_AHEAD_MS: Long = 80

    private const val TAG = "TogetherManager"
}
