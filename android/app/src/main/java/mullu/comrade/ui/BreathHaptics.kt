package mullu.comrade.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * The wrist-style haptic behind [BreathingScreen] — a swell that rises as you
 * breathe in and fades as you breathe out, so the pace can be followed with the
 * screen off or the phone face-down in a palm.
 *
 * Shaped after the Pixel Watch's breathing haptic rather than a plain buzz: the
 * point is something to *follow*, and a single pulse tells you a phase changed
 * without telling you which way it went. So the in-breath ramps up, the
 * out-breath ramps down, and the hold is silent — a hold with a buzz in it is
 * something to do rather than a pause.
 *
 * **The ramp spans the whole phase**, because it is pacing the same breath the
 * circle on screen is pacing: the buzz is faintest when the circle is smallest
 * and strongest when it is fullest, so following one is following the other.
 * It used to be a 540ms burst at the top of each phase, which said "a phase
 * began" — a thing you can only obey after the fact, and useless with your eyes
 * shut, which is exactly when this is meant to be used.
 *
 * The waveform itself is pure and lives in [ramp], with a JVM test
 * ([BreathHapticsTest]), because its properties are easy to get wrong and
 * impossible to see: an amplitude outside 1..255 throws at the platform
 * boundary, and a ramp that did not land exactly on the phase boundary would
 * drift out of step with the circle over a minute of breathing.
 */
object BreathHaptics {

    /**
     * How many amplitude steps a ramp is built from.
     *
     * These are spread across the entire phase, so this is the resolution of
     * the swell rather than its length: 20 steps over four seconds is a change
     * every 200ms, fine enough to feel like a rise rather than a staircase.
     */
    const val STEPS = 20

    /**
     * Quietest and loudest amplitude in a ramp (the platform's scale is 1..255).
     *
     * The top deliberately sits well below full: this is a companion to a
     * pause, and a breathing cue that makes the phone jump on the table is
     * working against the thing it is for.
     */
    const val MIN_AMPLITUDE = 24
    const val MAX_AMPLITUDE = 120

    /** One phase of the cycle, as [BreathingScreen] counts them. */
    enum class Phase { IN, HOLD, OUT }

    /**
     * The waveform for [phase] stretched over [phaseMs]: rising for the
     * in-breath, falling for the out-breath, and `null` for the hold — nothing
     * to play, not a silent pattern, so the caller skips the platform call
     * entirely.
     *
     * Timings are as uniform as the phase divides; only the amplitudes carry
     * the shape. `null` too for a phase shorter than [STEPS] milliseconds,
     * where a step would round to nothing — not a case this screen produces,
     * but a zero-length waveform is not worth handing to a vibrator to find out.
     */
    fun ramp(phase: Phase, phaseMs: Long): Pair<LongArray, IntArray>? {
        if (phaseMs < STEPS) return null
        val amplitudes = when (phase) {
            Phase.IN -> IntArray(STEPS) { step -> amplitudeAt(step) }
            Phase.OUT -> IntArray(STEPS) { step -> amplitudeAt(STEPS - 1 - step) }
            Phase.HOLD -> return null
        }
        return stepTimings(phaseMs) to amplitudes
    }

    /**
     * [STEPS] step lengths summing to exactly [phaseMs].
     *
     * The remainder of the division is handed out a millisecond at a time
     * rather than dropped, because dropping it would end every ramp a few
     * milliseconds early and the error compounds: five cycles into a
     * sixty-second sit, the buzz would be visibly ahead of the circle.
     */
    fun stepTimings(phaseMs: Long): LongArray {
        val base = phaseMs / STEPS
        val remainder = (phaseMs % STEPS).toInt()
        return LongArray(STEPS) { step -> base + if (step < remainder) 1L else 0L }
    }

    /**
     * The shape of a breath, as 0..1 over 0..1 — **and the one place it is
     * written down.**
     *
     * `BreathingScreen` builds its animation easing straight out of this
     * function, so the circle on screen and the buzz in the hand are not merely
     * intended to match: they cannot diverge without this returning something
     * different to both. That mattered enough to unify, because the previous
     * curve was a straight line in both places and a straight line is what made
     * the motion feel mechanical — the circle shrank at a constant rate and then
     * stopped dead at the settle, reported as the change from out-breath to
     * settle "not being natural".
     *
     * Sinusoidal ease-in-out: velocity is zero at both ends and greatest in the
     * middle. That is not a stylistic pick — airflow through a real breath has
     * very nearly this profile, which is why a breath has no sharp edge where it
     * turns around. It also makes the phase boundary forgiving: the circle is
     * barely moving as it arrives at full or empty, so the snap the holds perform
     * has almost nothing left to absorb.
     */
    fun curve(fraction: Float): Float {
        val clamped = fraction.coerceIn(0f, 1f)
        return ((1.0 - cos(PI * clamped)) / 2.0).toFloat()
    }

    /**
     * Amplitude of `step` on a rising ramp, following [curve] between
     * [MIN_AMPLITUDE] and [MAX_AMPLITUDE] and never leaving 1..255 — the
     * platform throws on anything outside that.
     *
     * Still strictly monotonic despite the curve flattening at both ends: over
     * [STEPS] steps and this amplitude span the smallest increment is 1, which
     * [BreathHapticsTest] pins, because an eased ramp that repeated a value would
     * feel like a stall rather than a swell.
     */
    private fun amplitudeAt(step: Int): Int {
        val span = (MAX_AMPLITUDE - MIN_AMPLITUDE).toFloat()
        val fraction = step.toFloat() / (STEPS - 1)
        val value = MIN_AMPLITUDE + (span * curve(fraction)).roundToInt()
        return value.coerceIn(1, 255)
    }

    /**
     * How long a fallback one-shot lasts for [phase], on a device whose
     * vibrator cannot vary amplitude.
     *
     * The shape is unavailable there, so the two are made distinguishable by
     * *length* instead — a longer pulse to breathe in against, a shorter one to
     * let go on. It deliberately does **not** fill the phase the way the ramp
     * does: a flat four-second buzz at whatever amplitude the OEM picked is not
     * a swell, it is a phone that will not stop, and on a pause screen that is
     * worse than a short cue and silence.
     */
    const val FALLBACK_IN_MS = 400L
    const val FALLBACK_OUT_MS = 200L

    fun fallbackMs(phase: Phase): Long? = when (phase) {
        Phase.IN -> FALLBACK_IN_MS
        Phase.OUT -> FALLBACK_OUT_MS
        Phase.HOLD -> null
    }

    /**
     * Play the cue for [phase], shaped to last [phaseMs]. Silent — and harmless
     * — on a device with no vibrator, and wrapped in [runCatching] because a
     * breathing screen must never be the thing that crashes: some OEM vibrators
     * throw on waveforms they dislike, and there is nothing here worth
     * propagating that for.
     */
    fun play(context: Context, phase: Phase, phaseMs: Long) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            val effect = if (vibrator.hasAmplitudeControl()) {
                val (timings, amplitudes) = ramp(phase, phaseMs) ?: return
                VibrationEffect.createWaveform(timings, amplitudes, NO_REPEAT)
            } else {
                VibrationEffect.createOneShot(
                    fallbackMs(phase) ?: return,
                    VibrationEffect.DEFAULT_AMPLITUDE,
                )
            }
            vibrator.vibrate(effect)
        }
    }

    /** Stop anything still playing — for leaving the screen mid-breath. */
    fun cancel(context: Context) {
        runCatching { vibrator(context)?.cancel() }
    }

    private const val NO_REPEAT = -1

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
