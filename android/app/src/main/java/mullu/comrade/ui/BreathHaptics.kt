package mullu.comrade.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

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
 * The waveform itself is pure and lives in [ramp], with a JVM test
 * ([BreathHapticsTest]), because two of its properties are easy to get wrong
 * and impossible to see: an amplitude outside 1..255 throws at the platform
 * boundary, and a ramp longer than the phase it belongs to would still be
 * buzzing when the next one starts.
 */
object BreathHaptics {

    /** How many steps a ramp is built from — enough to feel continuous. */
    const val STEPS = 6

    /** Duration of each step, so a whole ramp is [STEPS] × this. */
    const val STEP_MS = 90L

    /**
     * Quietest and loudest amplitude in a ramp (the platform's scale is 1..255).
     *
     * The top deliberately sits well below full: this is a companion to a
     * pause, and a breathing cue that makes the phone jump on the table is
     * working against the thing it is for.
     */
    const val MIN_AMPLITUDE = 24
    const val MAX_AMPLITUDE = 120

    /** A whole ramp must finish inside the phase that owns it. */
    val RAMP_MS: Long = STEPS * STEP_MS

    /** One phase of the cycle, as [BreathingScreen] counts them. */
    enum class Phase { IN, HOLD, OUT }

    /**
     * The waveform for [phase]: rising for the in-breath, falling for the
     * out-breath, and `null` for the hold — nothing to play, not a silent
     * pattern, so the caller skips the platform call entirely.
     *
     * Timings are uniform; only the amplitudes carry the shape.
     */
    fun ramp(phase: Phase): Pair<LongArray, IntArray>? {
        val amplitudes = when (phase) {
            Phase.IN -> IntArray(STEPS) { step -> amplitudeAt(step) }
            Phase.OUT -> IntArray(STEPS) { step -> amplitudeAt(STEPS - 1 - step) }
            Phase.HOLD -> return null
        }
        return LongArray(STEPS) { STEP_MS } to amplitudes
    }

    /**
     * Amplitude of `step` on a rising ramp, linearly interpolated between
     * [MIN_AMPLITUDE] and [MAX_AMPLITUDE] and never leaving 1..255 — the
     * platform throws on anything outside that.
     */
    private fun amplitudeAt(step: Int): Int {
        val span = (MAX_AMPLITUDE - MIN_AMPLITUDE).toLong()
        val value = MIN_AMPLITUDE + (span * step / (STEPS - 1)).toInt()
        return value.coerceIn(1, 255)
    }

    /**
     * How long a fallback one-shot lasts for [phase], on a device whose
     * vibrator cannot vary amplitude. The shape is lost, so the two are made
     * distinguishable by *length* instead — a longer pulse to breathe in
     * against, a shorter one to let go on — rather than firing the same buzz
     * twice and leaving the person guessing.
     */
    fun fallbackMs(phase: Phase): Long? = when (phase) {
        Phase.IN -> RAMP_MS
        Phase.OUT -> RAMP_MS / 2
        Phase.HOLD -> null
    }

    /**
     * Play the cue for [phase]. Silent — and harmless — on a device with no
     * vibrator, and wrapped in [runCatching] because a breathing screen must
     * never be the thing that crashes: some OEM vibrators throw on waveforms
     * they dislike, and there is nothing here worth propagating that for.
     */
    fun play(context: Context, phase: Phase) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            val effect = if (vibrator.hasAmplitudeControl()) {
                val (timings, amplitudes) = ramp(phase) ?: return
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
