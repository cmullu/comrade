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

    /**
     * How long a *moving* phase lasts — `BreathPhase.IN.seconds` and
     * `OUT.seconds`, the only two that ramp. The settle is shorter but silent,
     * so no waveform is built for it.
     */
    private val phaseMs = BreathPhase.IN.seconds * 1_000L

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
    fun theRampStaysStrictlyMonotonicNowThatItIsEased() {
        // The curve flattens at both ends, so the first and last increments are
        // the smallest ones in the ramp — with this span and step count they come
        // to exactly 1, which is the margin the strict `b > a` above is living
        // on. Reducing STEPS or narrowing MIN..MAX would round two neighbours to
        // the same amplitude, and a swell that repeats a value reads as a stall.
        // This fails first, and says why, rather than leaving the test above to
        // fail with no explanation.
        val (_, inward) = BreathHaptics.ramp(Phase.IN, phaseMs)!!
        val smallestStep = inward.toList().zipWithNext().minOf { (a, b) -> b - a }
        assertTrue(
            "eased ramp has a flat step: $smallestStep",
            smallestStep >= 1,
        )
    }

    @Test
    fun theBreathCurveIsTheSameOneTheCircleUses() {
        // BreathingScreen builds its animation easing out of exactly this
        // function, so these are the properties the *visible* motion depends on
        // as much as the felt one. Both ends pinned: an easing that did not
        // reach 0 and 1 would leave the circle short of full or short of empty
        // at every phase boundary, which the holds would then snap away.
        assertEquals(0f, BreathHaptics.curve(0f), 0.0001f)
        assertEquals(1f, BreathHaptics.curve(1f), 0.0001f)
        // Symmetric about the midpoint, where it is exactly half way: the rise
        // and the fall are the same shape reversed, which is what lets one
        // function serve the in-breath and the out-breath.
        assertEquals(0.5f, BreathHaptics.curve(0.5f), 0.0001f)
        for (i in 0..10) {
            val f = i / 10f
            assertEquals(
                "curve is not symmetric at $f",
                BreathHaptics.curve(f),
                1f - BreathHaptics.curve(1f - f),
                0.0001f,
            )
        }
        // Monotonic, and never outside 0..1 — an easing that overshot would send
        // the circle past full, and Animatable would clamp it into a visible
        // stall at the top of the in-breath.
        var previous = -1f
        for (i in 0..100) {
            val value = BreathHaptics.curve(i / 100f)
            assertTrue("curve left 0..1 at ${i / 100f}: $value", value in 0f..1f)
            assertTrue("curve is not monotonic at ${i / 100f}", value >= previous)
            previous = value
        }
    }

    @Test
    fun theBreathCurveSurvivesNonsense() {
        // Compose hands an easing fractions in 0..1, but the contract is not
        // enforced and a rounding error at a phase boundary is exactly the sort
        // of thing that produces 1.0000001. Clamped rather than extrapolated:
        // this is the pause screen.
        assertEquals(0f, BreathHaptics.curve(-0.5f), 0.0001f)
        assertEquals(1f, BreathHaptics.curve(1.5f), 0.0001f)
    }

    @Test
    fun theEasedRampStillStartsGentlyAndEndsStrong() {
        // The shape's purpose, stated independently of the maths: the quietest
        // part of the in-breath is its beginning and the strongest is its end,
        // and the eased version must not have moved the peak into the middle.
        val (_, inward) = BreathHaptics.ramp(Phase.IN, phaseMs)!!
        assertEquals(inward.max(), inward.last())
        assertEquals(inward.min(), inward.first())
        // And the ease is real, not a straight line wearing its name: at the
        // midpoint an eased ramp sits close to half, but the quarter point is
        // well *below* a quarter of the span, which a linear ramp could never be.
        val span = BreathHaptics.MAX_AMPLITUDE - BreathHaptics.MIN_AMPLITUDE
        val quarter = inward[BreathHaptics.STEPS / 4] - BreathHaptics.MIN_AMPLITUDE
        assertTrue("ramp is still linear at the quarter point", quarter < span / 4)
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
