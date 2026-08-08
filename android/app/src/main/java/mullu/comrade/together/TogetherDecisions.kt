package mullu.comrade.together

/**
 * The watch-together decisions that must not live in a listener — pure, with no
 * Android imports, so the JVM lane can pin them.
 *
 * The drift arithmetic is **not** here: it lives in `comrade_core::together`, so
 * this frontend and the desktop one inherit one answer instead of two that drift
 * apart (the mistake `docs/COMMS_ARCHITECTURE.md` ADR-2 exists to stop
 * repeating). What is genuinely local is everything about *this* player's event
 * semantics, and there are three traps in it.
 *
 * **Echo.** Applying a remote position makes our own player fire the callbacks
 * we listen to, and re-broadcasting those is a feedback loop between two
 * devices. A boolean guard cannot close it: `seekTo` completes asynchronously,
 * so the flag is already clear by the time `onSeekComplete` runs, and seeking to
 * the position the player already holds may fire nothing at all — which leaves a
 * latch set forever and the session deaf to the user. So an apply records what
 * it expects to see, matched by value and bounded by a deadline, and anything
 * unexplained is the user.
 *
 * **The scrubber.** A drag emits continuously. Only the release is a command,
 * the position poll must not fight the thumb, and a remote seek arriving
 * mid-drag must not yank it out of someone's finger.
 *
 * **The `MediaPlayer` footguns**, encoded here rather than trusted to a comment:
 * `setPlaybackParams` on a *paused* player starts playback on several Android
 * versions, and `seekTo` before `prepare()` throws.
 */
object TogetherDecisions {

    /** Positions this close are the same position — `MediaPlayer` reports in ms
     *  but seeks land on sample boundaries. */
    const val EPSILON_MS: Long = 250

    /** How long an apply may wait for the callback it expects before we assume
     *  it produced none. */
    const val SUPPRESS_TTL_MS: Long = 1_500

    /** How often the UI reads the playhead while playing. Local only — it is not
     *  the wire heartbeat, which is ten seconds and lives in Rust. */
    const val POLL_MS: Long = 250

    /** A drift correction must never produce chipmunk audio, whatever the core
     *  asks for. Mirrors `TOGETHER_MAX_TRIM` on the Rust side and
     *  `RATE_MIN`/`RATE_MAX` in `desktop/ui/together_sync.mjs`. */
    const val RATE_MIN: Float = 0.9f
    const val RATE_MAX: Float = 1.1f

    /** What the player is being asked to do. */
    sealed interface Op {
        data class Seek(val posMs: Long) : Op
        data class Rate(val value: Float) : Op
        data object Play : Op
        data object Pause : Op
    }

    /** What a player callback should tell the other person, if anything. */
    data class Emit(val posMs: Long, val playing: Boolean)

    /** One thing an apply expects the player to report back. */
    data class Expectation(val kind: String, val posMs: Long?, val deadlineMs: Long)

    /**
     * A ledger of callbacks an apply is about to cause.
     *
     * Not thread-safe on purpose: it belongs to the player thread that both
     * applies and observes. Anything else would be a lock in a callback.
     */
    class EchoSuppressor(private val ttlMs: Long = SUPPRESS_TTL_MS) {
        private val pending = ArrayDeque<Expectation>()

        fun expect(kind: String, posMs: Long?, nowMs: Long) {
            reap(nowMs)
            pending.addLast(Expectation(kind, posMs, nowMs + ttlMs))
            // Bounded: an apply storm must not grow this without limit.
            while (pending.size > 16) pending.removeFirst()
        }

        /**
         * Is this observed callback explained by something we did? Consumes the
         * entry if so — one apply explains exactly one callback.
         */
        fun explains(kind: String, posMs: Long?, nowMs: Long): Boolean {
            reap(nowMs)
            val idx = pending.indexOfFirst {
                it.kind == kind &&
                    (it.posMs == null || posMs == null || kotlin.math.abs(it.posMs - posMs) <= EPSILON_MS)
            }
            if (idx < 0) return false
            pending.removeAt(idx)
            return true
        }

        fun clear() = pending.clear()

        val size: Int get() = pending.size

        /**
         * Drop entries whose apply never produced a callback — reaped by
         * deadline rather than popped in order, because two quick seeks can
         * coalesce into one callback and strand the other, and a stranded entry
         * must expire rather than swallow a later genuine seek.
         */
        private fun reap(nowMs: Long) {
            while (pending.isNotEmpty() && pending.first().deadlineMs <= nowMs) {
                pending.removeFirst()
            }
            // The stranded entry may not be the oldest, so sweep too.
            pending.removeAll { it.deadlineMs <= nowMs }
        }
    }

    /** The player state a plan is computed against. */
    data class Local(val posMs: Long, val playing: Boolean, val prepared: Boolean)

    /** Operations to run, and the expectations to arm alongside them. */
    data class Plan(val ops: List<Op>, val expect: List<Pair<String, Long?>>)

    private val NOTHING = Plan(emptyList(), emptyList())

    /**
     * Turn a correction from `comrade_core::together::sync_verdict` into player
     * operations **and the ledger entries that must be armed with them**.
     *
     * Returning a value rather than acting inline is the point: an operation
     * that can raise a callback without a matching expectation *is* the feedback
     * loop, and a test can assert the pairing without a player.
     *
     * @param kind the verdict's `kind` string as it crosses the bridge
     */
    fun planCorrection(kind: String, posMs: Long, rate: Float, playing: Boolean, local: Local): Plan {
        if (!local.prepared) return NOTHING // seekTo before prepare() throws
        return when (kind) {
            "hold" -> NOTHING

            // A rate trim raises no seek callback at all, so it arms nothing —
            // the common case is silent by construction rather than suppressed.
            // Guarded because setPlaybackParams on a paused player starts
            // playback on several Android versions.
            "nudge" ->
                if (local.playing) {
                    Plan(listOf(Op.Rate(rate.coerceIn(RATE_MIN, RATE_MAX))), emptyList())
                } else {
                    NOTHING
                }

            // Deliberately no Play: a drift correction must never start a player
            // the user paused.
            "seek" -> Plan(listOf(Op.Seek(posMs)), listOf("seek" to posMs))

            // A command we missed — take their state wholesale, playing included.
            "adopt" -> {
                val ops = mutableListOf<Op>()
                val expect = mutableListOf<Pair<String, Long?>>()
                if (kotlin.math.abs(posMs - local.posMs) > EPSILON_MS) {
                    ops += Op.Seek(posMs)
                    expect += "seek" to posMs
                }
                if (playing != local.playing) {
                    if (playing) {
                        ops += Op.Play
                        expect += "play" to null
                    } else {
                        ops += Op.Pause
                        expect += "pause" to null
                    }
                }
                Plan(ops, expect)
            }

            else -> NOTHING // an unrecognised verdict is ignored, not guessed at
        }
    }

    /**
     * A command from the other person: where to be, and whether to wait first.
     *
     * `applyInMs` comes from the Rust side, which has already carried the
     * position forward through the message's flight time — so this applies
     * `posMs` as given and must not compensate again.
     */
    fun planCommand(posMs: Long, playing: Boolean, local: Local): Plan {
        if (!local.prepared) return NOTHING
        val ops = mutableListOf<Op>()
        val expect = mutableListOf<Pair<String, Long?>>()
        if (kotlin.math.abs(posMs - local.posMs) > EPSILON_MS) {
            ops += Op.Seek(posMs)
            expect += "seek" to posMs
        }
        if (playing != local.playing) {
            if (playing) {
                ops += Op.Play
                expect += "play" to null
            } else {
                ops += Op.Pause
                expect += "pause" to null
            }
        }
        return Plan(ops, expect)
    }

    /**
     * What a local player callback should tell the other person.
     *
     * `ratechange` has no Android callback, so unlike the desktop module there
     * is nothing to filter here — the rate is set directly and never observed.
     */
    fun classifyCallback(
        kind: String,
        posMs: Long,
        playing: Boolean,
        suppressor: EchoSuppressor,
        nowMs: Long,
    ): Emit? {
        // Reaching the end is a pause, never a seek: the playhead did not move
        // anywhere the other person needs to follow.
        if (kind == "completion") return Emit(posMs, false)
        if (kind != "seek" && kind != "play" && kind != "pause") return null
        if (suppressor.explains(kind, if (kind == "seek") posMs else null, nowMs)) return null
        return Emit(posMs, if (kind == "pause") false else if (kind == "play") true else playing)
    }

    /** What the scrubber and the position poll are allowed to do to each other. */
    data class ScrubState(val scrubbing: Boolean, val pendingRemoteMs: Long?)

    /**
     * Whether the periodic poll may move the slider. It may not while a finger
     * is on it — otherwise the thumb fights the user every 250 ms.
     */
    fun pollMayMoveSlider(state: ScrubState): Boolean = !state.scrubbing

    /**
     * A remote seek that arrives mid-drag is queued, not applied: yanking the
     * playhead out from under someone's finger is worse than being briefly out
     * of step, and they are about to name a position anyway.
     */
    fun onRemoteSeek(state: ScrubState, posMs: Long): ScrubState =
        if (state.scrubbing) state.copy(pendingRemoteMs = posMs) else state.copy(pendingRemoteMs = null)

    /**
     * On release the *user's* position wins — they were the one holding it — and
     * any queued remote seek is dropped rather than applied after the fact.
     */
    fun onScrubRelease(state: ScrubState): ScrubState = ScrubState(scrubbing = false, pendingRemoteMs = null)

    /** Whether a queued remote seek should now be applied (drag ended, nothing newer). */
    fun pendingRemoteToApply(state: ScrubState): Long? = if (state.scrubbing) null else state.pendingRemoteMs

    // ── What the two measured numbers are allowed to say ────────────────────
    //
    // Mirrors `desktop/ui/player_view.mjs` — `TOGETHER_MS`, `READING_STALE_MS`,
    // `driftLabel`, `qualityLabel`, `measurementLines` — against the same
    // vectors, because the rule they encode is the one the whole design rests
    // on and two frontends disagreeing about it is worse than neither showing
    // it. What is *not* mirrored is the wording: these return decisions, and
    // `ui/TogetherScreen.kt` turns them into localised strings, the same
    // division `Status` already uses.

    /** A gap smaller than this is not worth narrating even when we can see it. */
    const val TOGETHER_MS: Long = 400

    /**
     * How long a measurement is worth showing after it was taken.
     *
     * Corrections cross the bridge **only** when the verdict is not `hold`, so
     * the steady state emits nothing — and a screen that keeps the last pair
     * therefore prints a gap that was closed minutes ago, underneath the word
     * "Together". Two heartbeats, matching `READING_STALE_MS` on desktop and
     * `together::TOGETHER_DIRECT_SILENCE_MS` in core, and derived the same way:
     * two heartbeats of silence means at least one verdict said the gap was
     * inside the deadband.
     */
    const val READING_STALE_MS: Long = 20_000

    /** The floor on what a direct path may claim about itself, in ms. */
    const val QUALITY_FLOOR_MS: Long = 50

    /** Above this the path is a relay, and says so. */
    const val QUALITY_DIRECT_MAX_MS: Long = 150

    /**
     * The gap, when it is bigger than our own ability to measure it.
     *
     * [Silent] is not "they are together" — it is "we have nothing honest to
     * say", which covers both being genuinely in step and not being able to
     * tell. [Quality] carries the difference; this must not try to.
     */
    sealed interface Drift {
        data object Silent : Drift

        /** Rendered to one decimal place, as `%.1f`. */
        data class Gap(val ms: Long, val weAreAhead: Boolean) : Drift
    }

    /** How well we can measure, which is what makes a [Drift.Gap] mean anything. */
    sealed interface Quality {
        data object Unknown : Quality

        /**
         * @param ms floored at [QUALITY_FLOOR_MS] — under that the figure is
         *   below what either player can report, so claiming it would be the
         *   same invention the deadband exists to prevent.
         * @param direct a peer channel or a mesh hop, rather than a relay.
         * @param decimals 2 for a direct path, 1 for a relayed one — a relayed
         *   `±0.62s` implies a precision the second digit does not have.
         */
        data class Known(val ms: Long, val direct: Boolean, val decimals: Int) : Quality
    }

    /** Both lines, or neither. */
    data class Measurement(val drift: Drift, val quality: Quality)

    /**
     * What the readout may say, given a correction that arrived [ageMs] ago.
     *
     * One call rather than two because it is one measurement: a drift figure
     * without its error is unreadable, and an error left on screen after the
     * drift aged out would claim we are still measuring after we stopped
     * hearing verdicts. So they age out as a pair.
     *
     * A session with no correction yet passes a large [ageMs] and gets blanks —
     * the same answer as a stale reading, which is right: both mean there is no
     * current number.
     *
     * @param driftMs signed; positive means this device is ahead.
     */
    fun measurement(driftMs: Long, qualityMs: Long, ageMs: Long): Measurement {
        if (ageMs < 0 || ageMs >= READING_STALE_MS) {
            return Measurement(Drift.Silent, Quality.Unknown)
        }
        return Measurement(driftOf(driftMs, qualityMs), qualityOf(qualityMs))
    }

    /** Reported only above our own error — see the desktop module's long note. */
    private fun driftOf(driftMs: Long, qualityMs: Long): Drift {
        val gap = kotlin.math.abs(driftMs)
        val floor = maxOf(kotlin.math.abs(qualityMs), TOGETHER_MS)
        return if (gap <= floor) Drift.Silent else Drift.Gap(gap, weAreAhead = driftMs > 0)
    }

    private fun qualityOf(qualityMs: Long): Quality = when {
        qualityMs <= 0 -> Quality.Unknown
        qualityMs <= QUALITY_FLOOR_MS -> Quality.Known(QUALITY_FLOOR_MS, direct = true, decimals = 2)
        qualityMs <= QUALITY_DIRECT_MAX_MS -> Quality.Known(qualityMs, direct = true, decimals = 2)
        else -> Quality.Known(qualityMs, direct = false, decimals = 1)
    }

    // ── Picture ─────────────────────────────────────────────────────────────

    /**
     * What this recording turned out to be, decided from the only thing
     * `MediaPlayer` will tell us: the video track's dimensions, which are `0`
     * when there is no video track.
     *
     * This exists because the screen has to answer "video or not" *after*
     * opening the file rather than before. The picked MIME type is not a usable
     * answer — an `.mkv` of an album is `video/x-matroska` with no video track,
     * and a `.mp4` podcast is `video/mp4` — so trusting it shows a permanent
     * black rectangle to someone listening to music.
     */
    sealed interface Picture {
        /** A video track, with the dimensions the decoder reported. */
        data class Video(val width: Int, val height: Int) : Picture

        /** Audio only, or not yet known — both mean "draw no surface". */
        data object None : Picture
    }

    /**
     * Classify from reported dimensions.
     *
     * Non-positive is the audio-only signal, and it is also what `MediaPlayer`
     * reports *before* the first frame is decoded, so this deliberately makes
     * "not yet" and "never" the same answer: the surface appears when there is
     * something to put on it, which is the transition [Picture.Video] describes.
     */
    fun pictureOf(width: Int, height: Int): Picture =
        if (width > 0 && height > 0) Picture.Video(width, height) else Picture.None

    /**
     * The shape to give the video surface, or `null` when there is no picture.
     *
     * Compose's `aspectRatio` modifier throws on a non-positive ratio, so the
     * clamp is not decoration — a corrupt header reporting `1x1000000` would
     * otherwise take the whole screen down. The bounds are wider than any real
     * recording (32:9 ultrawide is 3.56, a vertical phone clip is 0.56) and
     * exist only to keep a hostile or broken file inside something drawable.
     */
    fun aspectRatioOf(picture: Picture): Float? = when (picture) {
        is Picture.None -> null
        is Picture.Video -> (picture.width.toFloat() / picture.height.toFloat())
            .takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(MIN_ASPECT, MAX_ASPECT)
    }

    const val MIN_ASPECT: Float = 0.25f
    const val MAX_ASPECT: Float = 4.0f

    /**
     * Whether to hold the screen awake.
     *
     * Only for video that is actually playing. An audio session must *not* hold
     * it: "listen together" over a two-hour album is the case where a burnt-out
     * battery is the whole failure, and there is nothing to look at anyway.
     */
    fun keepScreenOn(picture: Picture, playing: Boolean): Boolean =
        playing && picture is Picture.Video
}
