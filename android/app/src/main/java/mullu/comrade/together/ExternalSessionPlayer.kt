package mullu.comrade.together

import android.content.Context
import android.media.session.MediaController
import android.os.SystemClock
import android.util.Log

/**
 * A session held against a player Comrade does not own.
 *
 * `docs/TOGETHER.md` §13. The user starts Spotify, YouTube Music, a podcast app,
 * VLC — whatever they already have — and Comrade follows its published
 * `MediaSession`: reading position from `PlaybackState` and driving `play`,
 * `pause` and `seekTo` through the same transport controls a car head unit uses.
 *
 * **This is the implementation §14 says trades our own picture away.** There is
 * no sleeve and no video surface, because there is nothing of ours to draw and
 * the picture, if any, is in another app's window. The Together tab becomes
 * control-and-status. That is a product change, and it is the price of reaching
 * every service at once instead of one per vendor.
 *
 * ## What it must not do
 *
 * **Never follow ourselves.** [MediaSessionAccess.pick] excludes Comrade's own
 * package, because the foreground service that keeps a *file* session alive
 * publishes a `MediaSession` too — and a picker without that exclusion would
 * sync Comrade to Comrade and feed every correction back into the player that
 * produced it. From a bug report it reads as "it randomly jumps".
 *
 * **Never run the ladder against a session that cannot seek.** An app not
 * advertising `ACTION_SEEK_TO` is `PlayheadControl::StartOnly`: it can be
 * started and never *placed*, so corrections would be emitted that nothing
 * applies and the screen would say "catching up…" while nothing catches up.
 * [control] is the answer, and [seekTo] refuses rather than silently dropping.
 *
 * ## The clock
 *
 * `PlaybackState` measures `lastPositionUpdateTime` against
 * `SystemClock.elapsedRealtime`, so every timestamp in here is on that base and
 * **not** on `System.currentTimeMillis` — mixing them would compare a monotonic
 * clock against a wall clock and produce an extrapolation window that is wrong
 * by however long the device has been up. This is also why the clock is
 * injected: it makes the base a stated decision rather than whichever call was
 * nearest.
 */
class ExternalSessionPlayer(
    private val context: Context,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : SessionPlayer {

    /** What the session needs to hear about. */
    interface Listener {
        /** The followed app changed state, and the peer should know. */
        fun onStateChanged(posMs: Long, playing: Boolean)

        /**
         * They skipped, or the app moved on to the next track.
         *
         * A session that keeps syncing past this drives a stranger's playhead
         * into somebody else's song and calls it "together", so the caller ends
         * the claim rather than correcting through it.
         */
        fun onTrackChanged(trackKey: String)

        /**
         * Playback speeds disagree by more than
         * [MediaSessionDecisions.SPEED_TOLERANCE].
         *
         * Reported rather than corrected because **no seek fixes it**: at 1.5×
         * against 1.0× the gap reopens at half a second per second, so the
         * ladder would chase it forever. Stopping and saying why is the same
         * call `START_ONLY` makes for a player that cannot seek.
         */
        fun onSpeedsDisagree(ours: Float, theirs: Float)

        fun onLost()
    }

    private var controller: MediaController? = null
    private var listener: Listener? = null

    /** The last flattened reading, refreshed by [onPoll]. */
    private var candidate: MediaSessionDecisions.Candidate? = null

    /** What we were following when the session started. A skip ends the claim. */
    private var boundTrackKey: String = ""

    /** So a single disagreement is announced once rather than four times a second. */
    private var announcedSpeedMismatch = false

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /**
     * Find something to follow. Returns whether anything was found.
     *
     * Called once, at the start of a session: the mode never changes mid-session
     * ([PlaybackModeDecision.mayChangeMidSession]), so a device with nothing
     * playing gets no session rather than one that might acquire a player later.
     */
    fun bind(): Boolean {
        val now = clock()
        val found = MediaSessionAccess.pick(context, now) ?: return false
        controller = found
        val c = MediaSessionAccess.candidateOf(found, now)
        candidate = c
        boundTrackKey = c?.trackKey.orEmpty()
        // The `Handler` is not optional tidiness: the one-argument overload
        // posts to the *calling* thread's `Looper` and throws when there is
        // none. Binding happens from the session, which is main-thread today and
        // is exactly the kind of thing that quietly moves to a background
        // coroutine later — at which point the crash would look like a media
        // bug rather than a threading one.
        found.registerCallback(callbacks, android.os.Handler(android.os.Looper.getMainLooper()))
        return true
    }

    private val callbacks = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
            refresh()
            val c = candidate ?: return
            // Buffering is not a pause, and this is the second place that rule
            // is enforced rather than merely stated (§10): telling the peer
            // "they paused" because another app stalled makes them pause, which
            // makes us re-evaluate, which makes them re-evaluate.
            if (!MediaSessionDecisions.stateIsWorthSending(c)) return
            listener?.onStateChanged(positionMs, MediaSessionDecisions.isPlaying(c))
        }

        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            refresh()
            val key = candidate?.trackKey.orEmpty()
            // Blank is not a match and is also not a *change*: an app that
            // momentarily publishes nothing between tracks would otherwise end
            // the session a beat before it publishes the next title.
            if (key.isEmpty()) return
            if (!MediaSessionDecisions.sameTrack(boundTrackKey, key)) listener?.onTrackChanged(key)
        }

        override fun onSessionDestroyed() {
            candidate = null
            controller = null
            listener?.onLost()
        }
    }

    /** Re-read the followed session. Cheap: it is a flattening, not a query. */
    private fun refresh() {
        val c = controller ?: return
        candidate = MediaSessionAccess.candidateOf(c, clock())
    }

    override fun onPoll(nowMs: Long) {
        refresh()
        val c = candidate ?: return
        // Our own rate is always 1.0: nothing here trims, because a rate trim on
        // another app's player is not something the transport controls express.
        if (MediaSessionDecisions.speedsAgree(OUR_SPEED, c.speed)) {
            announcedSpeedMismatch = false
            return
        }
        if (!announcedSpeedMismatch) {
            announcedSpeedMismatch = true
            listener?.onSpeedsDisagree(OUR_SPEED, c.speed)
        }
    }

    /**
     * Where the followed app's playhead is now.
     *
     * Carried forward from its last report at *its own speed*, and only while it
     * is playing, and only up to [MediaSessionDecisions.EXTRAPOLATE_MAX_MS] —
     * after which the honest answer is the last one we actually had, because an
     * app that stopped updating has stalled or been frozen and advancing its
     * playhead anyway invents a position rather than reporting one.
     */
    override val positionMs: Long
        get() = candidate?.let { MediaSessionDecisions.positionAtMs(it, clock()) } ?: 0

    /**
     * Zero, always.
     *
     * A `MediaSession` carries no duration we can trust: `METADATA_KEY_DURATION`
     * is optional, apps that stream often publish `-1`, and a wrong length draws
     * a scrubber that lies about where the end is. [SessionPlayer.durationMs]
     * already documents `0` as "the player cannot say yet", which is exactly the
     * situation, so the screen draws a position without a bound rather than a
     * bound it made up.
     */
    override val durationMs: Long = 0

    override val isPlaying: Boolean
        get() = candidate?.let { MediaSessionDecisions.isPlaying(it) } == true

    /**
     * Whether there is a session to act on at all.
     *
     * The external flavour of "not yet" [SessionPlayer.prepared] describes: not
     * a decoder that has not finished preparing, but an app that may simply not
     * be running one.
     */
    override val prepared: Boolean
        get() = controller != null && candidate != null

    /** How tightly this app lets us hold the playhead. */
    fun control(): MediaSessionDecisions.Control = MediaSessionDecisions.control(candidate)

    /** Whether the drift ladder may run — the Kotlin twin of `PlayheadControl::corrects`. */
    fun corrects(): Boolean = MediaSessionDecisions.corrects(control())

    override fun play() {
        runCatching { controller?.transportControls?.play() }
            .onFailure { Log.w(TAG, "play failed", it) }
    }

    override fun pause() {
        runCatching { controller?.transportControls?.pause() }
            .onFailure { Log.w(TAG, "pause failed", it) }
    }

    /**
     * Place the playhead, if this app lets us.
     *
     * The guard is the point. A `START_ONLY` app accepts `seekTo` and does
     * nothing with it, so without this the ladder would emit corrections, see no
     * movement, and emit them again — the "catching up…" that never catches up.
     * Refusing here means the session's own [corrects] answer is the only thing
     * that decides, in one place.
     */
    override fun seekTo(posMs: Long) {
        if (!corrects()) return
        runCatching { controller?.transportControls?.seekTo(posMs.coerceAtLeast(0)) }
            .onFailure { Log.w(TAG, "seek failed", it) }
    }

    /**
     * Deliberately nothing, and unlike the embed there is not even a discrete
     * ladder to round to: `MediaController.TransportControls` has no rate call.
     * A service track's `SyncTuning` already sets `can_rate_trim: false`, so a
     * `Nudge` is never produced for one.
     */
    override fun setRate(rate: Float) = Unit

    override fun release() {
        runCatching { controller?.unregisterCallback(callbacks) }
        controller = null
        candidate = null
        boundTrackKey = ""
        announcedSpeedMismatch = false
        listener = null
    }

    /**
     * Zero, and honest. The sound leaves another app's `AudioTrack` and there is
     * no API that would tell us how far behind its own reported position it is.
     */
    override val outputLatencyMs: Long = 0

    private companion object {
        const val TAG = "ExternalSessionPlayer"

        /**
         * What *we* are playing at, which is always exactly one: Comrade holds
         * no player in this mode, so the only speed in the session is theirs and
         * the comparison is against normal.
         */
        const val OUR_SPEED = 1.0f
    }
}
