package mullu.comrade.attention

/**
 * Pacing for the guided stretch break — the pure half of `StretchScreen`.
 *
 * The routine itself (which stretches, how long, which have a left and a
 * right side) is `comrade_core::attention::STRETCH_ROUTINE`, crossed over the
 * FFI; nothing here invents a step. What this file decides is presentation
 * pacing: flattening mirrored steps into left/right segments and answering
 * "which segment is the clock in, and how far along?" — the same questions
 * `desktop/ui/stretch_view.mjs` answers for the desktop player, with the same
 * semantics, pinned by `StretchPacingTest` the way the desktop's are by
 * `stretch_view.test.mjs`.
 *
 * Deliberately framework-free (Kotlin stdlib only), like `TogetherDecisions`:
 * this is the half of the screen that can be compiled and *run* before CI.
 */
object StretchPacing {

    /** The screen's view of one engine step — plain values, no FFI types. */
    data class Step(
        val key: String,
        val name: String,
        val cue: String,
        /** Seconds to stay with it — per side, when [mirrored]. */
        val seconds: Int,
        val mirrored: Boolean,
    )

    enum class Side { LEFT, RIGHT }

    /** One playable stretch: a mirrored step becomes two of these. */
    data class Segment(
        val key: String,
        val name: String,
        val cue: String,
        val seconds: Int,
        /** null for a stretch with no sides. */
        val side: Side?,
    )

    /** Where the clock is: which segment, and whether the routine is over. */
    data class Moment(
        val index: Int,
        val segment: Segment,
        val intoSeconds: Double,
        val remainingSeconds: Double,
        val done: Boolean,
    )

    /**
     * Flatten the engine's routine into playable segments — left then right
     * for a mirrored step. A malformed row (blank key, non-positive duration)
     * is skipped rather than crashing the break: the routine crosses a
     * bridge, and one bad row must not cost the rest of the stretch.
     */
    fun segments(routine: List<Step>): List<Segment> = buildList {
        for (step in routine) {
            if (step.key.isBlank() || step.seconds <= 0) continue
            if (step.mirrored) {
                add(Segment(step.key, step.name, step.cue, step.seconds, Side.LEFT))
                add(Segment(step.key, step.name, step.cue, step.seconds, Side.RIGHT))
            } else {
                add(Segment(step.key, step.name, step.cue, step.seconds, side = null))
            }
        }
    }

    /** The whole break's length in seconds, sides included. */
    fun totalSeconds(segments: List<Segment>): Int = segments.sumOf { it.seconds }

    /**
     * Where the clock is in the break. Null with no segments (the bridge
     * failed — show no player rather than an empty one); past the end it pins
     * to the last segment with `done = true`, so the closing frame shows the
     * final stretch at rest instead of blanking.
     */
    fun at(segments: List<Segment>, elapsedSeconds: Double): Moment? {
        val total = totalSeconds(segments)
        if (total == 0) return null
        val at = if (elapsedSeconds.isFinite()) elapsedSeconds.coerceAtLeast(0.0) else 0.0
        var start = 0.0
        segments.forEachIndexed { index, segment ->
            val end = start + segment.seconds
            if (at < end) {
                return Moment(
                    index = index,
                    segment = segment,
                    intoSeconds = at - start,
                    remainingSeconds = end - at,
                    done = false,
                )
            }
            start = end
        }
        val last = segments.lastIndex
        return Moment(
            index = last,
            segment = segments[last],
            intoSeconds = segments[last].seconds.toDouble(),
            remainingSeconds = 0.0,
            done = true,
        )
    }

    /**
     * How far through the whole break, `0..1` — feeds a progress bar which
     * (like the breathing screen's) carries no digits: a stretch with a
     * countdown on it becomes a race.
     */
    fun progress(segments: List<Segment>, elapsedSeconds: Double): Float {
        val total = totalSeconds(segments)
        if (total == 0) return 0f
        val at = if (elapsedSeconds.isFinite()) elapsedSeconds.coerceAtLeast(0.0) else 0.0
        return (at / total).coerceAtMost(1.0).toFloat()
    }
}
