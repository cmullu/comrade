package mullu.comrade.attention

import kotlin.math.PI
import kotlin.math.cos

/**
 * Where the stretch guide's figure should be — pure, with no Android imports, so
 * the JVM lane can pin it.
 *
 * The choreography is not here: which movements, in which order, for how long,
 * and where a running routine has got to are all `comrade_core::stretch`'s,
 * reached over the FFI once a second. What is here is the *pose*, and it is here
 * for two reasons.
 *
 * The first is that it is the only part of the animation that can be checked
 * before CI. `StretchScreen` is Compose, so nothing in this sandbox runs it; this
 * file compiles and its tests execute in about a minute
 * (`.claude/scripts/android-typecheck.sh` covers the rest).
 *
 * The second is parity. `desktop/ui/stretch_view.mjs` holds the same maths for
 * the same figure, and the numbers below are the ones its tests assert — so a
 * change here that is not made there is a guide that leans one way on a phone and
 * another on a laptop. That is the divergence `docs/COMMS_ARCHITECTURE.md` ADR-2
 * is about, and the amplitudes are named constants on both sides so the two are
 * comparable by eye.
 */
object StretchGuide {

    /** How long a held stretch takes to ease into, in seconds. */
    const val HOLD_TRAVEL_SECS: Float = 2.5f

    /** One out-and-back of a cycling movement, in seconds. */
    const val CYCLE_SECS: Float = 4f

    /**
     * How far into the movement the guide is, 0..1, at [secsIntoStep].
     *
     * - `hold` travels out over [HOLD_TRAVEL_SECS] and stays. The travel is not
     *   decoration: a figure that snaps to the end position demonstrates the one
     *   thing a stretch routine most wants not to be done.
     * - `cycle` is `(1 - cos)/2` over [CYCLE_SECS], so it *starts at rest*, peaks
     *   at half a period and returns. Starting at zero matters — the first thing
     *   the guide does must be to begin, which is the same argument
     *   `BreathingScreen`'s circle records for opening at its minimum.
     * - anything else, `still` included, is 0. Stillness is the instruction for
     *   the eye steps, and an unrecognised motion must be still rather than
     *   undefined: a NaN in a Compose transform is a figure that vanishes.
     */
    fun extent(motion: String, secsIntoStep: Int, stepSeconds: Int): Float {
        val at = secsIntoStep.coerceAtLeast(0).toFloat()
        val length = if (stepSeconds > 0) stepSeconds.toFloat() else 1f
        return when (motion) {
            "hold" -> {
                val travel = minOf(HOLD_TRAVEL_SECS, length)
                (at / travel).coerceIn(0f, 1f)
            }
            "cycle" -> {
                val phase = (at % CYCLE_SECS) / CYCLE_SECS
                ((1f - cos(phase * 2f * PI.toFloat())) / 2f).coerceIn(0f, 1f)
            }
            else -> 0f
        }
    }

    /**
     * Where to draw the figure's moving part.
     *
     * Degrees and density-independent-pixel offsets, so the composable is a
     * `graphicsLayer` and nothing more. [Pose.rotation] is signed by the step's
     * side, which is the whole reason one drawing serves a left movement and a
     * right one — the figure is mirrored, not duplicated, and a sign error would
     * have it lean away from the side the instruction names.
     *
     * The amplitudes are small on purpose. This is a diagram of a movement, and a
     * figure flinging itself forty degrees sideways is a cartoon of one.
     */
    fun pose(
        part: String,
        side: String,
        motion: String,
        secsIntoStep: Int,
        stepSeconds: Int,
        stepKey: String = "",
    ): Pose {
        val extent = extent(motion, secsIntoStep, stepSeconds)
        val sign = when (side) {
            "left" -> -1f
            "right" -> 1f
            else -> 0f
        }
        return when (part) {
            // A turn is a rotation about the vertical axis, which a flat figure
            // cannot show — so it is drawn as a smaller lean and the words carry
            // the rest.
            "neck" -> Pose(
                rotation = sign * 18f * extent,
                offsetX = sign * 6f * extent,
                offsetY = if (stepKey == "chin_tuck") -4f * extent else 0f,
                scale = 1f,
            )
            "shoulders" -> Pose(
                rotation = sign * 8f * extent,
                offsetX = sign * 10f * extent,
                offsetY = -8f * extent,
                scale = 1f,
            )
            "arms" -> Pose(sign * 12f * extent, sign * 8f * extent, 0f, 1f)
            "back" -> Pose(sign * 14f * extent, sign * 12f * extent, 0f, 1f)
            "hips", "legs" -> Pose(sign * 6f * extent, sign * 6f * extent, 6f * extent, 1f)
            // Nothing moves. The step whose instruction is to look away from the
            // screen must not put the one animated thing in the room on it.
            "eyes" -> Pose(0f, 0f, 0f, 1f)
            else -> Pose(0f, 0f, -4f * extent, 1f + 0.02f * extent)
        }
    }

    /** A figure's position, in degrees and dp. */
    data class Pose(
        val rotation: Float,
        val offsetX: Float,
        val offsetY: Float,
        val scale: Float,
    )
}
