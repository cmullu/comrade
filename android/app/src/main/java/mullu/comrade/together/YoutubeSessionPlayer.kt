package mullu.comrade.together

import android.util.Log
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * A watch-together session over the official YouTube IFrame embed.
 *
 * **The only source that gets close to "play something neither of us has"** —
 * effectively the whole commercial catalogue, no account on either side, and a
 * playhead we can actually drive. `docs/TOGETHER.md` §11b is the design; this is
 * the wiring, and everything in it that could be decided without Android was
 * decided in [TogetherDecisions] so the JVM lane could pin it.
 *
 * ## The reporting rate is the whole problem
 *
 * `onCurrentSecond` fires about once a second. The manager's poll calls
 * `together_report_position` four times a second, and the drift ladder compares
 * whatever it was last handed against the peer — so handing it a reading up to a
 * second old invents a second of drift that is not there, on a source whose
 * deadband is already the coarse one. [TogetherDecisions.CoarsePlayhead] is the
 * answer and [positionMs] reads its estimate, never the raw last tick.
 *
 * ## What this player is not allowed to do
 *
 * **Keep the standard embed, with its controls and its ads.** YouTube's API
 * Services Terms prohibit hiding the player or stripping ads, and §11a records
 * why the ReVanced/InnerTube route is declined. If ad-free is wanted the answer
 * is §11a's `Stream` sources, not a modified embed — so `controls(1)` below is a
 * term of use rather than a preference, and `enableBackgroundPlayback` stays off
 * for the same reason.
 *
 * That last one has a consequence worth stating rather than discovering:
 * **an embed session pauses when the app goes to the background**, unlike a file
 * session, which the foreground service keeps playing
 * ([TogetherManager.onAppBackgrounded]). Backgrounded playback of a YouTube
 * embed is a Premium feature of *their* client and not ours to grant.
 *
 * ## Threading
 *
 * Every callback below arrives on the main thread — they originate in a
 * `WebView`'s JavaScript bridge — and the manager's poll runs on
 * `Dispatchers.Main.immediate`. That is what makes the deliberately
 * non-thread-safe [TogetherDecisions.CoarsePlayhead] and
 * [TogetherDecisions.StallWatch] correct here rather than lucky: one thread both
 * observes the player and reports for it, which is the arrangement they document
 * as their requirement.
 */
class YoutubeSessionPlayer(
    private val videoId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : SessionPlayer {

    /** What the session needs to hear about, in the manager's vocabulary. */
    interface Listener {
        /**
         * The video is loaded and its length is known.
         *
         * Unlike a file, the length arrives *after* the session opens — a
         * YouTube duration is the player's to report, not the inviter's to
         * claim (`TogetherContent::duration_ms` returns `None` for it), so the
         * screen starts with `0` and grows a scrubber when this fires.
         */
        fun onPrepared(durationMs: Long)

        /** A state change the other person should hear about. */
        fun onStateChanged(posMs: Long, playing: Boolean)

        fun onError(message: String)
    }

    private var view: YouTubePlayerView? = null
    private var player: YouTubePlayer? = null
    private var listener: Listener? = null

    private val playhead = TogetherDecisions.CoarsePlayhead()
    private val stall = TogetherDecisions.StallWatch()

    /** The raw last tick, for [TogetherDecisions.StallWatch] only. */
    private var lastTickMs: Long = 0

    /** What the embed last told us it was doing. */
    private var embed: TogetherDecisions.EmbedState = TogetherDecisions.EmbedState.NotReady

    override var durationMs: Long = 0
        private set

    /**
     * True once the embed has handed over a [YouTubePlayer].
     *
     * The same meaning [SessionPlayer.prepared] has for a `MediaPlayer` — "can
     * this be acted on at all" — and it matters for the same reason: a `seekTo`
     * before the IFrame player exists goes nowhere silently, which is worse than
     * throwing because the session then holds a position nothing ever applied.
     */
    override val prepared: Boolean
        get() = player != null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /**
     * Hand the session the view the screen built, or take it away.
     *
     * The player outlives the view on purpose, exactly as [TogetherPlayer] does
     * with its `Surface`: a session must not end because a screen was disposed,
     * and the view is destroyed and rebuilt on every rotation. Whichever arrives
     * second wires the other up.
     */
    fun attach(view: YouTubePlayerView?) {
        if (view == null) {
            detach()
            return
        }
        this.view = view
        view.addYouTubePlayerListener(callbacks)
    }

    /** The screen went away. The session has not. */
    private fun detach() {
        view?.let { v -> runCatching { v.removeYouTubePlayerListener(callbacks) } }
        view = null
        // Deliberately **not** cleared: the IFrame player belongs to the
        // released WebView, so holding it would be driving a dead page. The next
        // `attach` gets a fresh one through `onReady`.
        player = null
    }

    private val callbacks = object : AbstractYouTubePlayerListener() {
        override fun onReady(youTubePlayer: YouTubePlayer) {
            player = youTubePlayer
            // `cueVideo`, not `loadVideo`: the session decides when playback
            // starts, and a video that autoplays on arrival would start one
            // person's evening before the other has joined — and would emit a
            // state change nobody asked for.
            runCatching { youTubePlayer.cueVideo(videoId, 0f) }
                .onFailure { listener?.onError("could not load this video") }
        }

        override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
            durationMs = (duration * 1000f).toLong().coerceAtLeast(0)
            listener?.onPrepared(durationMs)
        }

        override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
            val now = clock()
            lastTickMs = (second * 1000f).toLong().coerceAtLeast(0)
            playhead.onTick(lastTickMs, now)
            // Sampled here as well as from [onPoll] because the two failures are
            // different: ticks that keep arriving with the *same* second are an
            // ad break, and ticks that stop arriving at all are a frozen player.
            // Watching only the poll would miss the first, because a repeating
            // tick keeps resetting the estimate's clock and the estimate never
            // goes flat.
            stall.onSample(lastTickMs, playingPerEmbed(), now)
        }

        override fun onStateChange(
            youTubePlayer: YouTubePlayer,
            state: PlayerConstants.PlayerState,
        ) {
            val now = clock()
            embed = TogetherDecisions.embedState(stateName(state))
            when (val e = embed) {
                is TogetherDecisions.EmbedState.Live -> playhead.onPlaying(e.playing, now)
                // Reaching the end is a pause, and the playhead stops with it.
                TogetherDecisions.EmbedState.Ended -> playhead.onPlaying(false, now)
                // A stall is ridden out locally: the estimate must stop
                // advancing, but the peer is told nothing (§10).
                TogetherDecisions.EmbedState.Stalled -> playhead.onPlaying(false, now)
                TogetherDecisions.EmbedState.NotReady -> Unit
            }
            // A state change means the player did something, so whatever the
            // stall watch was counting is over.
            stall.reset()
            if (!TogetherDecisions.embedStateIsWorthSending(embed)) return
            listener?.onStateChanged(playhead.estimateMs(now), isPlaying)
        }

        /**
         * Reported as a *kind* rather than a sentence.
         *
         * It used to hand up prose, which meant the manager had nothing to
         * branch on and the screen had nothing to offer — so a video its owner
         * simply does not allow outside YouTube (the overwhelmingly common
         * case, and the one whose panel reads "This video is unavailable") got
         * the same silence as a genuine playback fault. The words are
         * `res/values/strings.xml`'s, chosen from
         * [TogetherDecisions.embedFailure], the same division [stateName] makes.
         */
        override fun onError(
            youTubePlayer: YouTubePlayer,
            error: PlayerConstants.PlayerError,
        ) {
            Log.w(TAG, "embed error: $error")
            listener?.onError(errorName(error))
        }
    }

    /**
     * The embed's own view of whether it is running, before the stall watch has
     * a say. [isPlaying] is the one the session uses.
     */
    private fun playingPerEmbed(): Boolean =
        (embed as? TogetherDecisions.EmbedState.Live)?.playing == true

    override val positionMs: Long
        get() = playhead.estimateMs(clock())

    /**
     * Whether **our video** is advancing — which is not the same question as
     * whether the player is busy.
     *
     * During an ad break the embed reports itself playing and the video's
     * playhead stands still. Reporting that as playing is what makes the other
     * device run away by exactly the length of the break (`docs/TOGETHER.md`
     * §9a): the ladder sees a peer that has fallen behind and corrects, the
     * embed cannot seek past an ad, and the next verdict corrects again — a
     * screen saying "catching up…" while nothing catches up.
     *
     * Reporting it as *not playing* costs nothing and fixes it, because the
     * manager's poll turns that into `together_report_position(pos, false, …)`
     * — a heartbeat, which the peer's `sync_verdict` reads as "they are not
     * playing" and answers by holding. That is the same instruction §12 gives a
     * byte-starved reader, and it is deliberately **not**
     * `together_set_state(.., playing: false, ..)`, which is a command and would
     * pause the other person for the length of *our* ad.
     */
    override val isPlaying: Boolean
        get() = playingPerEmbed() && !stall.isStalled

    /**
     * The poll's heartbeat.
     *
     * Needed because the other stall the embed produces is silence: a player the
     * system froze, or a page that stopped running its timer, sends no ticks at
     * all — so nothing would ever call [TogetherDecisions.StallWatch.onSample]
     * and the session would keep claiming to play forever, against the last
     * position it happened to hear.
     */
    override fun onPoll(nowMs: Long) {
        stall.onSample(lastTickMs, playingPerEmbed(), nowMs)
    }

    override fun play() {
        runCatching { player?.play() }.onFailure { Log.w(TAG, "play failed", it) }
    }

    override fun pause() {
        runCatching { player?.pause() }.onFailure { Log.w(TAG, "pause failed", it) }
    }

    /**
     * Place the playhead, and move the estimate with it **immediately**.
     *
     * The second half is the one that would otherwise be a real bug rather than
     * a rounding error: waiting for the next tick means up to a second of
     * reporting the position we just left, so the ladder sees the gap it has
     * already closed and corrects a second time — the sticky rate-trim sawtooth
     * `AUDIT.md` records, in a different costume. §11b.
     */
    override fun seekTo(posMs: Long) {
        val at = posMs.coerceAtLeast(0)
        val now = clock()
        playhead.onSeek(at, now)
        // A seek is us moving the playhead, so the stall watch's count is void —
        // otherwise a correction landing during a break would look like the
        // break ending.
        stall.reset()
        lastTickMs = at
        runCatching { player?.seekTo(at / 1000f) }.onFailure { Log.w(TAG, "seek failed", it) }
    }

    /**
     * Deliberately nothing.
     *
     * The embed takes discrete rates only (`PlaybackRate` is an enum of seven
     * values), so there is no honest way to express the ±10 % trim the ladder
     * asks for. `TogetherContent::Youtube.tuning()` already sets
     * `can_rate_trim: false`, so a `Nudge` is never produced for this source and
     * this is the second line of that defence rather than the mechanism —
     * exactly what [SessionPlayer.setRate] asks an implementation to do instead
     * of approximating.
     */
    override fun setRate(rate: Float) = Unit

    override fun release() {
        detach()
        playhead.reset()
        stall.reset()
        listener = null
        lastTickMs = 0
        embed = TogetherDecisions.EmbedState.NotReady
    }

    /**
     * Zero, and honest.
     *
     * The sound leaves a `WebView` we do not own and there is no `AudioTrack` to
     * ask, so any figure here would be invented. [SessionPlayer.outputLatencyMs]
     * documents zero as meaning "unmeasured", which the drift arithmetic knows
     * to ignore — a fabricated offset would be worse than the accuracy it
     * pretends to add.
     */
    override val outputLatencyMs: Long = 0

    private companion object {
        const val TAG = "YoutubeSessionPlayer"

        /**
         * The library's enum, flattened to the strings
         * [TogetherDecisions.embedState] takes.
         *
         * Mapped **here and nowhere else**, which is the whole reason that
         * function takes text: `TogetherDecisions` has no third-party imports,
         * and that is what lets the JVM lane run it with no SDK.
         */
        /**
         * The library's error enum, flattened to the strings
         * [TogetherDecisions.embedFailure] takes.
         *
         * `VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER` is the one that matters: it
         * is what the IFrame player's own 101/150 becomes, and it is what is
         * behind the "This video is unavailable" panel almost every time — the
         * video plays perfectly well, just not here. Everything else is folded
         * together on purpose, because the next step for all of it is the same
         * and inventing distinctions the person cannot act on is noise.
         */
        fun errorName(error: PlayerConstants.PlayerError): String = when (error) {
            PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER -> "not_embeddable"
            PlayerConstants.PlayerError.VIDEO_NOT_FOUND -> "video_not_found"
            else -> "unknown"
        }

        fun stateName(state: PlayerConstants.PlayerState): String = when (state) {
            PlayerConstants.PlayerState.PLAYING -> "playing"
            PlayerConstants.PlayerState.PAUSED -> "paused"
            PlayerConstants.PlayerState.BUFFERING -> "buffering"
            PlayerConstants.PlayerState.ENDED -> "ended"
            PlayerConstants.PlayerState.UNSTARTED -> "unstarted"
            PlayerConstants.PlayerState.VIDEO_CUED -> "video_cued"
            PlayerConstants.PlayerState.UNKNOWN -> "unknown"
        }
    }
}
