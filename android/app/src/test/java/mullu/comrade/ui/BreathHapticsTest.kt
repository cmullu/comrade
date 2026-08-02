package mullu.comrade.ui

import mullu.comrade.ui.BreathHaptics.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the breathing waveform. Every property here is invisible on screen and
 * only shows up on a real device — one as a crash at the platform boundary, the
 * rest as a buzz that has drifted out of step with the circle it is pacing.
 */
class BreathHapticsTest {

    /** How long one phase of box breathing lasts (`BreathingScreen.PHASE_MS`). */
    private val phaseMs = 4_000L

    @Test
    fun aRampFillsExactlyThePhaseItPaces() {
        // The buzz and the circle are two renderings of one breath. A ramp that
        // ended early would leave the last of the in-breath silent; one that
        // ran long would still be swelling while the screen says hold. And
        // because the error repeats every phase, "a few milliseconds out" is
        // visibly out by the end of a sixty-second sit.
        for (phase in listOf(Phase.IN, Phase.OUT)) {
            val (timings, _) = BreathHaptics.ramp(phase, phaseMs)!!
            assertEquals("ramp must land on the phase boundary", phaseMs, timings.sum())
            assertEquals(BreathHaptics.STEPS, timings.size)
        }
    }

    @Test
    fun anAwkwardPhaseLengthIsStillSpentToTheLastMillisecond() {
        // 4001 does not divide by the step count. The remainder has to be
        // handed out rather than dropped — see BreathHaptics.stepTimings.
        val odd = 4_001L
        assertEquals(odd, BreathHaptics.stepTimings(odd).sum())
        assertEquals(odd, BreathHaptics.ramp(Phase.IN, odd)!!.first.sum())
        // No step may be zero-length; a vibrator has nothing to do with one.
        assertTrue(BreathHaptics.stepTimings(odd).all { it > 0 })
    }

    @Test
    fun aPhaseTooShortToRampIsSilentRatherThanZeroLength() {
        // Not a case this screen produces, but a waveform of zero-length steps
        // is not worth handing to an OEM vibrator to find out what it does.
        assertNull(BreathHaptics.ramp(Phase.IN, BreathHaptics.STEPS - 1L))
        assertNull(BreathHaptics.ramp(Phase.OUT, 0L))
    }

    @Test
    fun everyAmplitudeIsInsideThePlatformsRange() {
        // VibrationEffect.createWaveform throws on anything outside 1..255, and
        // it throws on the device, not here.
        for (phase in listOf(Phase.IN, Phase.OUT)) {
            val (timings, amplitudes) = BreathHaptics.ramp(phase, phaseMs)!!
            assertEquals(timings.size, amplitudes.size)
            for (a in amplitudes) {
                assertTrue("amplitude $a out of range", a in 1..255)
            }
        }
    }

    @Test
    fun theInBreathSwellsAndTheOutBreathFades() {
        // The whole point of the shape: a single pulse would say "a phase
        // changed" without saying which way.
        val (_, inward) = BreathHaptics.ramp(Phase.IN, phaseMs)!!
        val (_, outward) = BreathHaptics.ramp(Phase.OUT, phaseMs)!!

        // `.toList()` because zipWithNext is an Iterable extension — IntArray
        // has no such thing.
        assertTrue(inward.toList().zipWithNext().all { (a, b) -> b > a })
        assertTrue(outward.toList().zipWithNext().all { (a, b) -> b < a })
        assertEquals(inward.first(), outward.last())
        assertEquals(inward.last(), outward.first())
        assertEquals(BreathHaptics.MIN_AMPLITUDE, inward.first())
        assertEquals(BreathHaptics.MAX_AMPLITUDE, inward.last())
    }

    @Test
    fun theSwellIsFineGrainedEnoughToFeelLikeARiseNotAStaircase() {
        // Spread over a whole phase, too few steps would be felt as separate
        // taps. This is the resolution that makes it one continuous thing.
        val (timings, _) = BreathHaptics.ramp(Phase.IN, phaseMs)!!
        assertTrue("steps of ${timings[0]}ms are too coarse to read as a swell", timings[0] <= 250)
    }

    @Test
    fun theHoldIsSilent() {
        // Not a zero-amplitude pattern — nothing at all, so the caller skips
        // the platform entirely. A buzz through the hold makes a pause feel
        // like something to do.
        assertNull(BreathHaptics.ramp(Phase.HOLD, phaseMs))
        assertNull(BreathHaptics.fallbackMs(Phase.HOLD))
    }

    @Test
    fun aVibratorWithNoAmplitudeControlStillTellsTheTwoApart() {
        // The shape is unavailable there, so length has to carry it.
        assertNotEquals(
            BreathHaptics.fallbackMs(Phase.IN),
            BreathHaptics.fallbackMs(Phase.OUT),
        )
    }

    @Test
    fun theFallbackIsACueAndNotAPhoneThatWillNotStop() {
        // Deliberately NOT phase-length like the ramp: a flat four-second buzz
        // at whatever amplitude the OEM picked is not a swell, and on a pause
        // screen it is worse than a short cue followed by quiet.
        for (phase in listOf(Phase.IN, Phase.OUT)) {
            val ms = BreathHaptics.fallbackMs(phase)!!
            assertTrue("fallback of ${ms}ms should be a fraction of the phase", ms <= phaseMs / 4)
            assertTrue(ms > 0)
        }
    }

    @Test
    fun theCueStaysGentleEnoughForAPause() {
        // A breathing cue that makes the phone jump on a table is working
        // against the thing it is for.
        assertTrue(BreathHaptics.MAX_AMPLITUDE < 255 / 2)
        assertTrue(BreathHaptics.MIN_AMPLITUDE >= 1)
    }
}
