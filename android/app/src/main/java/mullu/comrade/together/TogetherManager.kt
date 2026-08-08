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
import mullu.comrade.transfer.ShareReadPolicy

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
            /**
             * The `TogetherContent` variant's tag, as
             * [PlaybackModeDecision.ownershipFor] takes it — `local_file` ·
             * `youtube` · `service` · `stream`.
             *
             * Carried rather than reduced to booleans because the mode decision
             * is core's shape and this screen should be asking it, not
             * re-deriving it from two flags that happen to line up today.
             */
            val contentKind: String,
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
            /**
             * Whether this recording turned out to have a picture, and how big.
             * Known only after the decoder reports it, so it starts as
             * [TogetherDecisions.Picture.None] and the surface appears when
             * there is something to put on it.
             *
             * An embed session is the exception and sets it up front
             * ([TogetherDecisions.EMBED_PICTURE]) — there is no decoder of ours
             * to ask, and there is always a picture.
             */
            val picture: TogetherDecisions.Picture = TogetherDecisions.Picture.None,
            /**
             * Whether the picture is drawn by a `WebView` we host rather than by
             * our own decoder.
             *
             * The screen needs it because the two draw with completely different
             * views — a `SurfaceView` the decoder writes into, against the
             * IFrame player with its own controls — and because an embed has no
             * file to hand over, so the handover affordances have nothing to
             * offer. Nothing else branches on it: the session, the ladder and
             * the readout are the same for both.
             */
            val embed: Boolean = false,
            /**
             * Whether another app entirely is holding the playback and Comrade
             * is only following it (`docs/TOGETHER.md` §13).
             *
             * The screen draws control-and-status for one of these: there is no
             * sleeve, no surface and no scrubber, because there is nothing of
             * ours to render, the picture is in somebody else's window, and a
             * `MediaSession` carries no length to scrub against.
             */
            val external: Boolean = false,
            /**
             * The last measured gap between the two playheads, signed —
             * positive means this device is ahead — with the error on it and
             * when it was taken.
             *
             * Kept raw rather than pre-rendered because whether any of it may
             * be *shown* depends on how old it is by the time the screen draws,
             * which only the screen knows. [correctedAtMs] of zero is the
             * honest starting state: nothing measured yet, which
             * [TogetherDecisions.measurement] reads as stale and shows as
             * blank rather than as a reassuring zero.
             */
            val driftMs: Long = 0,
            val qualityMs: Long = 0,
            val correctedAtMs: Long = 0,
        ) : UiState
    }

    /** The honest vocabulary — never "synced", never "in sync". */
    enum class Status { WaitingForThem, OpenYourCopy, Together, CatchingUp, LostTrack, TheyPaused }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null

    /**
     * Whoever is holding this session's playback, behind the [SessionPlayer]
     * seam (`docs/TOGETHER.md` §14) — our own [TogetherPlayer] today, an embed
     * or an external session once those are built. Everything below drives it
     * through the interface; the two places that genuinely need the concrete
     * player ([openPlayer] and [attachSurface]) narrow it back and say why.
     */
    private var player: SessionPlayer? = null
    private val suppressor = TogetherDecisions.EchoSuppressor()
    private var scrub = TogetherDecisions.ScrubState(scrubbing = false, pendingRemoteMs = null)
    private var appContext: Context? = null

    /** What the peer named, kept so the library can be searched for it. */
    private var wanted: uniffi.comrade_core.Recording? = null
    private var wantedMs: Long = 0

    /**
     * The video an invitation named, when it was a YouTube one.
     *
     * Kept for the same reason [wanted] is: the invitation arrives before the
     * person decides, and the id is what [joinEmbed] needs when they do.
     */
    private var wantedVideoId: String? = null

    /** The invitation's content kind, for [PlaybackModeDecision.ownershipFor]. */
    private var invitedKind: String = ""
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
        contentKind: String,
        videoId: String?,
    ) {
        appContext = context.applicationContext
        wanted = recording
        wantedMs = durationMs
        wantedVideoId = videoId
        invitedKind = contentKind
        val youtube = videoId != null
        val title = recording?.let { if (it.artist.isBlank()) it.title else "${it.artist} — ${it.title}" }.orEmpty()
        _state.value = UiState.Invited(peer, peerLabel, title, youtube, contentKind)

        // A YouTube invitation is deliberately **not** auto-joined, even though
        // this device could certainly play it and no library lookup is needed.
        // Opening a video and starting to report a playhead is agreeing to watch
        // something with someone; the file path only ever does that when this
        // phone already held the recording, which is a much smaller claim.
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

    private fun applyCommand(p: SessionPlayer, posMs: Long, playing: Boolean) {
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

    /**
     * A drift correction. Emitted only when the verdict is not "hold".
     *
     * `driftMs` and `qualityMs` are the two measured figures the correction
     * carries, and they are recorded even though nothing here renders them: the
     * screen decides what they are worth saying, and the pair is worthless
     * without knowing when it was taken.
     */
    fun onCorrection(
        kind: String,
        posMs: Long,
        rate: Float,
        playing: Boolean,
        driftMs: Long = 0,
        qualityMs: Long = 0,
    ) {
        val p = player ?: return
        val plan = TogetherDecisions.planCorrection(
            kind,
            posMs,
            rate,
            playing,
            TogetherDecisions.Local(p.positionMs, p.isPlaying, p.prepared),
        )
        run(p, plan)
        refreshLive(
            status = Status.CatchingUp,
            driftMs = driftMs,
            qualityMs = qualityMs,
            correctedAtMs = System.currentTimeMillis(),
        )
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
        // On the session's own scope, like [onSharedFileStreaming] and for the
        // same reason: the caller is the transfer, finishing on
        // `Dispatchers.IO`, and [player] is otherwise only touched from here.
        // That was survivable while this ran once at the very end of a transfer
        // and nothing else held the player; it is not, now that a partial-file
        // player is live at exactly this moment.
        scope.launch { openFinishedFile(path) }
    }

    private fun openFinishedFile(path: String) {
        if (appContext == null) return
        val live = _state.value as? UiState.Live
        // Where the partial file had got to, if it was already playing
        // (`docs/TOGETHER.md` §12). Reopening resets a `MediaPlayer` to zero, so
        // without this the transfer *finishing* would throw the listener back to
        // the start of the track they were already halfway through — a
        // regression that only appears once early playback works, which is
        // exactly the kind that ships.
        val resumeAtMs = player?.positionMs ?: 0
        val wasPlaying = player?.isPlaying ?: false
        openPlayer(Uri.fromFile(java.io.File(path))) { durationMs ->
            openedPath = path
            openedDurationMs = durationMs
            if (live != null) _state.value = live.copy(ready = true, durationMs = durationMs)
            if (resumeAtMs > TogetherDecisions.EPSILON_MS) {
                // Armed, not silent: reopening produces a real `onSeekComplete`,
                // and an unexplained one is re-broadcast as the user having
                // seeked — which would move the other person to a position they
                // are already at, for no reason anyone could explain.
                suppressor.expect("seek", resumeAtMs, System.currentTimeMillis())
                player?.seekTo(resumeAtMs)
            }
            if (wasPlaying) player?.play()
        }
        // Whatever they are doing now is what we should be doing. No command is
        // sent: the next heartbeat's drift verdict closes the gap, and a
        // command from the side that just arrived would move *them*.
    }

    /** Apply a plan, arming its expectations first so nothing echoes back out. */
    private fun run(p: SessionPlayer, plan: TogetherDecisions.Plan) {
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

    /**
     * Start a session on a YouTube video — the one route to "play something
     * neither of us has" that needs no account on either side.
     *
     * A sibling of [start] rather than a branch inside it, the same shape §14
     * asks of [openEmbed] and [openPlayer], because almost nothing is shared:
     * there is no file to remember, no length to report (the player discovers
     * that), and no handover to offer.
     */
    fun startEmbed(context: Context, peer: String, peerLabel: String, videoId: String) {
        appContext = context.applicationContext
        openEmbed(videoId)
        runCatching {
            ComradeCore.togetherStartTyped(peer, uniffi.comrade_core.TogetherContent.Youtube(videoId))
        }.onFailure { Log.w(TAG, "could not invite them to a video", it) }
        _state.value = UiState.Live(
            peer = peer,
            peerLabel = peerLabel,
            // The video's own title is the embed's to know and it does not tell
            // us, so the screen shows the peer and the player shows the title —
            // rather than this inventing one from an eleven-character id.
            title = "",
            weLead = true,
            joined = false,
            ready = true,
            playing = false,
            positionMs = 0,
            // Not the inviter's to claim: `TogetherContent::duration_ms` returns
            // `None` for a video, and the scrubber grows when the player says.
            durationMs = 0,
            status = Status.WaitingForThem,
            picture = TogetherDecisions.EMBED_PICTURE,
            embed = true,
        )
        startService()
    }

    /** Accept a YouTube invitation. Nothing to look for and nothing to ask for. */
    fun joinEmbed(context: Context) {
        appContext = context.applicationContext
        val invited = _state.value as? UiState.Invited ?: return
        val videoId = wantedVideoId ?: return
        openEmbed(videoId)
        runCatching { ComradeCore.togetherJoinTyped() }
            .onFailure { Log.w(TAG, "join failed", it) }
        _state.value = UiState.Live(
            peer = invited.peer,
            peerLabel = invited.peerLabel,
            title = invited.title,
            weLead = false,
            joined = true,
            ready = true,
            playing = false,
            positionMs = 0,
            durationMs = 0,
            status = Status.Together,
            picture = TogetherDecisions.EMBED_PICTURE,
            embed = true,
        )
        startService()
    }

    /**
     * Accept an invitation by following whatever this phone is **already**
     * playing — Spotify, a podcast app, VLC, whatever is installed.
     *
     * `docs/TOGETHER.md` §13. Comrade holds no player at all in this mode: it
     * reads the other app's published `MediaSession` and drives its transport,
     * which is what a car head unit does. That reaches every service at once
     * instead of one integration per vendor, and it needs no client id, no OAuth
     * and no vendored SDK.
     *
     * Returns why it could not start, or `null` when a session opened — the
     * screen needs to tell those apart, because "turn the permission on" and
     * "press play in your music app first" are different next steps and offering
     * the wrong one is worse than offering none.
     *
     * **The mode is [PlaybackModeDecision]'s to decide, not this function's.**
     * A file we hold is always ours even with an external session running, and a
     * file we do *not* hold is nothing yet rather than external — claiming an
     * external player there would start a session against whatever happened to
     * be on the phone, which is not what was invited.
     */
    fun followExternal(context: Context): FollowRefusal? {
        appContext = context.applicationContext
        val invited = _state.value as? UiState.Invited ?: return FollowRefusal.NoInvitation
        if (!MediaSessionAccess.hasAccess(context)) return FollowRefusal.NeedsAccess
        val ownership = PlaybackModeDecision.ownershipFor(
            contentKind = invitedKind,
            // This branch exists precisely because we do not hold it. If we did,
            // the file path would already have started and this would be the
            // wrong answer — see the rule above.
            haveOurCopy = false,
            externalSessionAvailable = MediaSessionAccess.anySession(
                context,
                android.os.SystemClock.elapsedRealtime(),
            ),
        )
        if (ownership != PlaybackOwnership.EXTERNAL) return FollowRefusal.NothingPlaying

        val p = ExternalSessionPlayer(context)
        p.setListener(externalListener)
        if (!p.bind()) return FollowRefusal.NothingPlaying
        player?.release()
        player = p
        openedPath = null
        openedDurationMs = 0
        runCatching { ComradeCore.togetherJoinTyped() }
            .onFailure { Log.w(TAG, "join failed", it) }
        _state.value = UiState.Live(
            peer = invited.peer,
            peerLabel = invited.peerLabel,
            title = invited.title,
            weLead = false,
            joined = true,
            ready = true,
            playing = p.isPlaying,
            positionMs = p.positionMs,
            // A `MediaSession` carries no length we can trust, so the screen
            // shows a position without a bound rather than a bound we invented.
            durationMs = 0,
            status = Status.Together,
            // Nothing of ours to draw: the picture, if any, is in another app's
            // window. §14 calls this the price of reaching every service at once.
            external = true,
        )
        startService()
        return null
    }

    /** Why following what is playing could not start. Each needs a different sentence. */
    enum class FollowRefusal {
        /** Not holding an invitation any more. */
        NoInvitation,

        /** Notification-listener access has not been granted. Send them to settings. */
        NeedsAccess,

        /** Granted, and nothing is playing to follow. Tell them to press play. */
        NothingPlaying,
    }

    private val externalListener = object : ExternalSessionPlayer.Listener {
        override fun onStateChanged(posMs: Long, playing: Boolean) {
            ComradeCore.togetherSetStateTyped(posMs, playing, 0)
            refreshLive(playing = playing, positionMs = posMs)
        }

        /**
         * They skipped, or the app moved on. The claim ends rather than
         * following into a different song and calling it "together".
         */
        override fun onTrackChanged(trackKey: String) {
            refreshLive(status = Status.LostTrack)
        }

        /**
         * Nothing to be done about it, so the screen says so and the session
         * stops claiming — a seek cannot hold two different playback speeds
         * together, because the gap reopens as fast as it closes.
         */
        override fun onSpeedsDisagree(ours: Float, theirs: Float) {
            Log.i(TAG, "speeds disagree: ours $ours, theirs $theirs")
            refreshLive(status = Status.LostTrack)
        }

        override fun onLost() {
            refreshLive(status = Status.LostTrack)
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

    /**
     * Open the file **as it arrives**, so the session starts on the head of it
     * instead of waiting for the whole thing (`docs/TOGETHER.md` §12).
     *
     * Called when the transfer is armed rather than when it finishes. The player
     * is the ordinary one; only the bytes are different, and the two things that
     * make it work are elsewhere — `PartialFileDataSource` blocks the decoder on
     * chunks that have not landed, and [applyShareVerdict] below holds the
     * playhead when it should not be moving.
     *
     * A no-op when nothing is being received, which is every session where both
     * sides already have the file.
     */
    fun onSharedFileStreaming() {
        // Hopped onto the session's own scope rather than run inline: the caller
        // is the transfer, which arms a receive on `Dispatchers.IO`, and [player]
        // is otherwise only ever touched from here — by the poll, by every
        // incoming command and by every correction. One more thread writing it
        // would make a field that is currently single-threaded by construction
        // into one that merely looks that way.
        scope.launch { openStreamingPlayer() }
    }

    private fun openStreamingPlayer() {
        val ctx = appContext ?: return
        val source = ShareTransfer.streamingSource() ?: return
        val live = _state.value as? UiState.Live
        // The path stays null: a partial file in the cache is not something this
        // device can turn round and offer to somebody else, and `openedPath` is
        // exactly the flag that decides whether it tries.
        openedPath = null
        openPlayerWith(
            ctx,
            onReady = { durationMs ->
                if (live != null) _state.value = live.copy(ready = true, durationMs = durationMs)
            },
            feed = { it.open(source) },
        )
    }

    private fun openPlayer(uri: Uri, onReady: (Long) -> Unit) {
        val ctx = appContext ?: return
        // Remember the readable path, if there is one: it is the difference
        // between being able to hand this file over and only being able to
        // receive one.
        openedPath = if (uri.scheme == null || uri.scheme == "file") uri.path else null
        openPlayerWith(ctx, onReady) { it.open(uri) }
    }

    /**
     * The file path's player, wherever its bytes come from.
     *
     * `feed` is the only difference between a file on this device and one still
     * arriving over the wire (`docs/TOGETHER.md` §12) — everything else, the
     * listener included, is `MediaPlayer` semantics that both share.
     */
    private fun openPlayerWith(
        ctx: Context,
        onReady: (Long) -> Unit,
        feed: (TogetherPlayer) -> Unit,
    ) {
        // This is the **file** path's construction, deliberately concrete: the
        // listener callbacks below are `MediaPlayer` semantics and do not
        // survive being made abstract, so a new mode gets a sibling of this
        // function rather than a generalisation of it (`docs/TOGETHER.md` §14).
        // The reuse arm therefore narrows: today [player] is only ever a
        // [TogetherPlayer], so `as?` never fails and this is the same reuse it
        // has always been. When a second implementation can occupy that field,
        // this arm needs to release what it is replacing before it overwrites
        // it — mode is fixed for a session
        // ([PlaybackModeDecision.mayChangeMidSession]), so that only happens if
        // that rule is broken.
        val p = (player as? TogetherPlayer) ?: TogetherPlayer(ctx).also { player = it }
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

            override fun onVideoSize(width: Int, height: Int) {
                refreshLive(picture = TogetherDecisions.pictureOf(width, height))
            }
        })
        feed(p)
    }

    /**
     * The **embed** path's construction, and a sibling of [openPlayer] rather
     * than a generalisation of it (`docs/TOGETHER.md` §14).
     *
     * Nothing here is shared with the file path: there is no `Uri`, no surface,
     * no readable path to offer in a handover, and the callbacks are IFrame
     * semantics rather than `MediaPlayer` ones. Trying to fold the two together
     * would mean an abstraction over two sets of events that do not correspond.
     *
     * Whatever was playing is released first. The mode never changes mid-session
     * ([PlaybackModeDecision.mayChangeMidSession]), so in practice this only
     * ever replaces a *previous* session's player — but leaving the old one to
     * be garbage-collected would leave its `WebView` running audio in a window
     * nothing draws.
     */
    private fun openEmbed(videoId: String) {
        player?.release()
        openedPath = null
        openedDurationMs = 0
        val p = YoutubeSessionPlayer(videoId)
        p.setListener(object : YoutubeSessionPlayer.Listener {
            override fun onPrepared(durationMs: Long) {
                requestAudioFocus()
                refreshLive(durationMs = durationMs)
                startPolling()
            }

            /**
             * Straight out, with no echo suppression — and that is not an
             * oversight.
             *
             * The suppressor exists because a `MediaPlayer` reports back the
             * seeks and pauses *we* asked it for, so an apply would otherwise
             * re-broadcast itself between two devices. The IFrame player does
             * the same for state, which is why an apply arms nothing here: this
             * fires on the embed's own transitions, and [YoutubeSessionPlayer]
             * has already dropped the ones that are not worth sending
             * (`buffering`, `unstarted`) before it calls.
             */
            override fun onStateChanged(posMs: Long, playing: Boolean) {
                ComradeCore.togetherSetStateTyped(posMs, playing, 0)
                refreshLive(playing = playing, positionMs = posMs)
            }

            override fun onError(message: String) {
                Log.w(TAG, "embed: $message")
            }
        })
        player = p
        // Nothing starts until the screen hands over a view: the session exists
        // before there is anywhere to draw it, exactly as a file session exists
        // before the surface arrives.
        startPolling()
    }

    /**
     * Hand the embed the view the screen built, or take it away.
     *
     * The twin of [attachSurface], narrowed for the same reason and with the
     * same lifetime: the view is destroyed and rebuilt on every rotation while
     * the session must survive both. For a file session this is correctly
     * nothing at all.
     */
    fun attachEmbedView(
        view: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView?,
    ) {
        (player as? YoutubeSessionPlayer)?.attach(view)
    }

    /**
     * Core asking us to carry a signal over the direct peer channel.
     *
     * **Nothing arrives here yet, by construction.** Core only emits an outbound
     * signal after a frontend has reported a live channel via
     * `togetherDirectReady(true)`, and this frontend never does — a session-long
     * peer connection is not built here yet, only the file-handover one that
     * lives for the length of a transfer. So this drops, and says so, rather
     * than looking like a wired path that silently loses signals.
     *
     * When the connection lands this becomes a `send` on it, and the only other
     * thing it must do is report `togetherDirectReady(false)` the moment the
     * channel closes — there is no timeout behind that flag, so a stale `true`
     * would send every signal into a socket nobody reads and let the session die
     * on its TTL instead of falling back to the relay.
     */
    fun onOutbound(json: String) {
        Log.d(TAG, "dropping a direct together signal: no session channel on this frontend (${json.length}B)")
    }

    /**
     * Hand the player the window to draw into, or take it away.
     *
     * Called by the screen as its surface is created and destroyed — which
     * happens on every rotation, independently of the session. The player holds
     * the last value, so the order the two arrive in does not matter.
     *
     * Narrowed rather than lifted onto [SessionPlayer]: a surface is meaningful
     * only to the player that decodes into one. An embed draws into a `WebView`
     * we host and an external session draws in another app's window, so for
     * those this is correctly nothing at all rather than an override that has to
     * pretend (`docs/TOGETHER.md` §14).
     */
    fun attachSurface(surface: android.view.Surface?) {
        (player as? TogetherPlayer)?.attachSurface(surface)
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
                // Before the read, not after: a player that is told where it is
                // on somebody else's schedule needs a clock that keeps running
                // when the reports stop, and this poll is it. See
                // [SessionPlayer.onPoll].
                p.onPoll(System.currentTimeMillis())
                applyShareVerdict(p)
                ComradeCore.togetherReportPosition(p.positionMs, p.isPlaying, p.outputLatencyMs)
                if (TogetherDecisions.pollMayMoveSlider(scrub)) {
                    refreshLive(positionMs = p.positionMs)
                }
                delay(TogetherDecisions.POLL_MS)
            }
        }
    }

    /**
     * Hold the playhead when the bytes ran out, and let it go when they arrive.
     *
     * `docs/TOGETHER.md` §12. A no-op in every session where this device is not
     * *receiving* a file — [ShareTransfer.readVerdictAt] answers `null` and
     * nothing here runs.
     *
     * **The whole trap is in what a hold is expressed as.** It pauses the local
     * player and nothing else: the very next line of the poll reports
     * `together_report_position(pos, playing = false, latency)`, a heartbeat,
     * which the peer's next `sync_verdict` reads as "they are not playing" and
     * answers by holding rather than correcting. It is **not**
     * `together_set_state(.., playing: false, ..)` — that is a command, it takes
     * the next sequence number, and it would pause the other person because our
     * download fell behind. That is precisely the ping-pong §10 rules out, and
     * it is the single easiest thing to get wrong here.
     *
     * Resuming is deliberately conditional on the *session* wanting to play.
     * `Start` is permission, not an instruction: a player the person paused, or
     * one a `pause` command from the peer paused, must stay paused however many
     * bytes arrive.
     */
    private fun applyShareVerdict(p: SessionPlayer) {
        val verdict = ShareTransfer.readVerdictAt(p.positionMs, p.isPlaying) ?: return
        val wantsToPlay = (_state.value as? UiState.Live)?.playing ?: false
        when (verdict) {
            ShareReadPolicy.Verdict.HOLD -> if (p.isPlaying) p.pause()
            ShareReadPolicy.Verdict.START -> if (wantsToPlay && !p.isPlaying) p.play()
            // Already running with enough in hand. Nothing to do — and nothing
            // to say, which is the point of there being no fourth arm.
            ShareReadPolicy.Verdict.CONTINUE -> Unit
        }
    }

    private fun refreshLive(
        playing: Boolean? = null,
        positionMs: Long? = null,
        /**
         * Only an embed passes this.
         *
         * A file session knows its length the moment the decoder prepares, and
         * sets it when the [UiState.Live] is built — so this parameter did not
         * exist until a player turned up whose length arrives *after* the
         * session opens. A YouTube duration is the player's to report and
         * `TogetherContent::duration_ms` returns `None` for one, so the scrubber
         * starts at zero and grows when the embed says.
         */
        durationMs: Long? = null,
        status: Status? = null,
        picture: TogetherDecisions.Picture? = null,
        driftMs: Long? = null,
        qualityMs: Long? = null,
        correctedAtMs: Long? = null,
    ) {
        val live = _state.value as? UiState.Live ?: return
        _state.value = live.copy(
            playing = playing ?: live.playing,
            positionMs = positionMs ?: live.positionMs,
            durationMs = durationMs ?: live.durationMs,
            status = status ?: live.status,
            picture = picture ?: live.picture,
            driftMs = driftMs ?: live.driftMs,
            qualityMs = qualityMs ?: live.qualityMs,
            correctedAtMs = correctedAtMs ?: live.correctedAtMs,
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
        wantedVideoId = null
        invitedKind = ""
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
