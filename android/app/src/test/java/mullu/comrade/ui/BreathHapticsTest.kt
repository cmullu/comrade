package mullu.comrade.ui

import mullu.comrade.ui.BreathHaptics.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the breathing waveform. Both properties here are invisible on screen and
 * only show up on a real device — one as a crash at the platform boundary, the
 * other as a buzz still running when the next phase starts.
 */
class BreathHapticsTest {

    /** How long one phase of box breathing lasts (`BreathingScreen.PHASE_SECONDS`). */
    private val phaseMs = 4_000L

    @Test
    fun aRampFinishesWellInsideThePhaseItBelongsTo() {
        // A ramp that outlives its phase would still be buzzing "breathe in"
        // while the screen says hold.
        assertTrue(
            "ramp of ${BreathHaptics.RAMP_MS}ms must fit inside a ${phaseMs}ms phase",
            BreathHaptics.RAMP_MS < phaseMs,
        )
        for (phase in listOf(Phase.IN, Phase.OUT)) {
            val (timings, _) = BreathHaptics.ramp(phase)!!
            assertEquals(BreathHaptics.RAMP_MS, timings.sum())
            assertTrue(BreathHaptics.fallbackMs(phase)!! <= phaseMs)
        }
    }

    @Test
    fun everyAmplitudeIsInsideThePlatformsRange() {
        // VibrationEffect.createWaveform throws on anything outside 1..255, and
        // it throws on the device, not here.
        for (phase in listOf(Phase.IN, Phase.OUT)) {
            val (timings, amplitudes) = BreathHaptics.ramp(phase)!!
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
        val (_, inward) = BreathHaptics.ramp(Phase.IN)!!
        val (_, outward) = BreathHaptics.ramp(Phase.OUT)!!

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
    fun theHoldIsSilent() {
        // Not a zero-amplitude pattern — nothing at all, so the caller skips
        // the platform entirely. A buzz through the hold makes a pause feel
        // like something to do.
        assertNull(BreathHaptics.ramp(Phase.HOLD))
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
    fun theCueStaysGentleEnoughForAPause() {
        // A breathing cue that makes the phone jump on a table is working
        // against the thing it is for.
        assertTrue(BreathHaptics.MAX_AMPLITUDE < 255 / 2)
        assertTrue(BreathHaptics.MIN_AMPLITUDE >= 1)
    }
}
