package mullu.comrade.together

/**
 * Syncing whatever the device is *already* playing, decided without Android.
 *
 * The idea this file exists for: instead of Comrade owning a player for every
 * source — a `MediaPlayer` for files, an embed for YouTube, a vendor SDK per
 * streaming service — it drives the media session the user's own app already
 * published. Android exposes those system-wide, so one implementation reaches
 * Spotify, YouTube Music, a podcast app, VLC and whatever else is installed,
 * with no OAuth, no client id and no vendored binary. `docs/TOGETHER.md` §13.
 *
 * Comrade never learns what the other app is or where its bytes came from, and
 * that is the point rather than a loophole: this is the same posture a car head
 * unit or a watch companion takes.
 *
 * **No Android imports, deliberately.** The framework half maps
 * `PlaybackState`'s int constants to the strings below at the boundary, so
 * everything here compiles and runs under plain `kotlinc` + JUnit with no SDK —
 * see `CLAUDE.md`. On the priority frontend that is the difference between
 * "checked" and "CI will tell us".
 */
object MediaSessionDecisions {

    /**
     * How far past its last update a session's position may be carried forward.
     *
     * Same reasoning and same number as [TogetherDecisions.COARSE_EXTRAPOLATE_MAX_MS]:
     * an app that stopped updating has stalled or been frozen, and advancing its
     * playhead anyway invents a position rather than reporting one.
     */
    const val EXTRAPOLATE_MAX_MS: Long = TogetherDecisions.COARSE_EXTRAPOLATE_MAX_MS

    /** One active media session, flattened to the fields a decision needs. */
    data class Candidate(
        /** Owning app, e.g. `com.spotify.music`. Only ever compared, never parsed. */
        val packageName: String,
        /** `playing` · `paused` · `buffering` · `stopped` · `none`. */
        val state: String,
        /** Whether the app advertises `ACTION_SEEK_TO`. Decides [Control]. */
        val canSeek: Boolean,
        /** The position the app last reported. */
        val positionMs: Long,
        /** Our monotonic clock when it reported that. */
        val reportedAtMs: Long,
        /**
         * Playback speed. **Not decoration**: a podcast at 1.5× advances its
         * playhead half again as fast, and extrapolating at 1.0 would drift by
         * half a second per second — worse than not extrapolating at all.
         */
        val speed: Float,
        /**
         * A stable identity for what is playing, built by the caller from the
         * session's metadata. Compared, never shown.
         */
        val trackKey: String,
    )

    /**
     * How tightly this device can hold the playhead. Mirrors
     * `comrade_core::together::PlayheadControl` — the names are deliberately the
     * same three, so the mapping at the FFI boundary is a rename and not a
     * judgement.
     */
    enum class Control { FULL, START_ONLY, NONE }

    /**
     * Which session a session should follow, or `null` for none.
     *
     * `ourPackage` is **not** an optimisation. Comrade publishes its own
     * `MediaSession` for the foreground service that keeps a file session
     * playing, so a picker that did not exclude it would find Comrade, sync
     * Comrade to Comrade, and feed every correction straight back into the
     * player that produced it. That is the one bug in here that would be
     * genuinely hard to diagnose from a bug report.
     *
     * Otherwise: something actually playing beats something paused, and among
     * equals the framework's own priority order wins — it puts the session the
     * user last touched first, which is the best available answer to "which of
     * my three music apps did I mean".
     */
    fun pick(candidates: List<Candidate>, ourPackage: String): Candidate? {
        val theirs = candidates.filter { it.packageName != ourPackage }
        // `buffering` counts as playing here: a session mid-stall is one the
        // user is listening to, and demoting it would hand the session to a
        // paused app in another tab the moment the network hiccuped.
        return theirs.firstOrNull { it.state == "playing" || it.state == "buffering" }
            ?: theirs.firstOrNull { it.state == "paused" }
    }

    /**
     * Where that session's playhead is now.
     *
     * `PlaybackState` reports a position and the instant it was measured; the
     * caller is expected to keep those together. Only a *playing* session
     * advances, at its own speed, and only up to [EXTRAPOLATE_MAX_MS] past the
     * last report — after that the honest answer is the last one we actually
     * had. A clock that stepped backwards reads as no elapsed time, the same
     * saturating choice `direct_path_live` makes in core.
     */
    fun positionAtMs(
        candidate: Candidate,
        nowMs: Long,
        maxExtrapolateMs: Long = EXTRAPOLATE_MAX_MS,
    ): Long {
        if (candidate.state != "playing") return candidate.positionMs.coerceAtLeast(0)
        val elapsed = (nowMs - candidate.reportedAtMs).coerceIn(0, maxExtrapolateMs)
        val advanced = (elapsed * candidate.speed.toDouble()).toLong()
        return (candidate.positionMs + advanced).coerceAtLeast(0)
    }

    /** Whether the session is running, for the drift ladder's purposes. */
    fun isPlaying(candidate: Candidate): Boolean = candidate.state == "playing"

    /**
     * Whether this state is ours to tell the other person about.
     *
     * `buffering` is not, for the reason `docs/TOGETHER.md` §10 gives and
     * [TogetherDecisions.embedState] already encodes: a stall reported as a
     * pause makes them pause, which makes us re-evaluate, which makes them
     * re-evaluate. A stall is ridden out locally.
     */
    fun stateIsWorthSending(candidate: Candidate): Boolean =
        candidate.state == "playing" || candidate.state == "paused"

    /**
     * What we can promise about a session on this app.
     *
     * An app that does not advertise `ACTION_SEEK_TO` can be started and never
     * *placed*, which is exactly `PlayheadControl::StartOnly` — so the ladder
     * must not run against it, or it emits corrections nothing applies and a
     * screen that says "catching up…" while nothing catches up.
     */
    fun control(candidate: Candidate?): Control = when {
        candidate == null -> Control.NONE
        candidate.canSeek -> Control.FULL
        else -> Control.START_ONLY
    }

    /** Whether the ladder may run — the Kotlin twin of `PlayheadControl::corrects`. */
    fun corrects(control: Control): Boolean = control == Control.FULL

    /**
     * Whether the two of us are on the same thing at all.
     *
     * A session that keeps syncing after one side skips to the next track is
     * worse than one that stops: it drives a stranger's playhead into somebody
     * else's song and calls it "together". Blank on either side is *not* a
     * match — an app that publishes no metadata has told us nothing, and
     * treating silence as agreement is the invention this whole design refuses.
     */
    fun sameTrack(ours: String?, theirs: String?): Boolean {
        val a = ours?.trim().orEmpty()
        val b = theirs?.trim().orEmpty()
        return a.isNotEmpty() && b.isNotEmpty() && a.equals(b, ignoreCase = true)
    }

    /**
     * Build the key [sameTrack] compares, from a session's own metadata.
     *
     * Here rather than at the `MediaMetadata` boundary because it is a decision,
     * not a read: which fields identify a track, and what an app that publishes
     * half of them has told us. Title and artist, because those are the two
     * fields every app fills — album is patchy, and a media id is the *app's*
     * identifier, so two people on two different apps would never match on one
     * even when they are listening to the same song.
     *
     * **A blank title yields a blank key, and a blank key never matches**
     * ([sameTrack] refuses it). That is the whole rule about silence stated
     * once: an app publishing nothing has told us nothing, and reading that as
     * agreement is the invention this design refuses. A missing *artist* is not
     * silence, though — plenty of podcast apps omit it — so a title alone is a
     * usable key, just a weaker one.
     *
     * Case and surrounding space are [sameTrack]'s to ignore; this only has to
     * put the two fields together the same way on both devices.
     */
    fun trackKey(title: String?, artist: String?): String {
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return ""
        val a = artist?.trim().orEmpty()
        return if (a.isEmpty()) t else "$t — $a"
    }

    /**
     * Whether a difference in playback speed makes the session a lie.
     *
     * Nothing can fix this: if they are at 1.5× and we are at 1.0×, no seek
     * holds for more than a moment, because the gap reopens at half a second
     * per second. The ladder would chase it forever and the screen would say
     * "catching up…" forever. Better to stop claiming and say why — the same
     * call [Control.START_ONLY] makes for a player that cannot seek.
     *
     * The tolerance is generous because reported speed is a float and apps are
     * casual with it; the case this catches is 1.0 against 1.25, not rounding.
     */
    fun speedsAgree(ours: Float, theirs: Float): Boolean =
        kotlin.math.abs(ours - theirs) <= SPEED_TOLERANCE

    const val SPEED_TOLERANCE: Float = 0.05f
}
