package mullu.comrade.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The breathing cycle and the calming-line rotation. Pure, so they can be pinned
 * here rather than on an emulator — and worth pinning: this screen has now
 * shipped two rhythm bugs, and both were arithmetic nobody could see.
 */
class BreathingScreenTest {

    @Test
    fun theCycleIsFourFourFourTwo() {
        // These four numbers are a decision, not an implementation detail, so
        // changing them has to trip a test that points at the reasoning.
        //
        // The last phase is deliberately HALF the others. A hold after an exhale
        // starts from a smaller gas reservoir and without the Hering-Breuer
        // stretch-receptor brake on inspiratory drive, so the urge to breathe
        // arrives soonest there — and the people most likely to open this screen
        // are the ones with the highest CO2 sensitivity and the shortest hold
        // tolerance. See BreathPhase's KDoc for the citations. If you are about
        // to make it four again for symmetry: symmetry is what made it feel
        // forced.
        assertEquals(4, BreathPhase.IN.seconds)
        assertEquals(4, BreathPhase.HOLD_FULL.seconds)
        assertEquals(4, BreathPhase.OUT.seconds)
        assertEquals(2, BreathPhase.HOLD_EMPTY.seconds)
        assertEquals(14, CYCLE_SECONDS)
    }

    @Test
    fun theHardestPhaseIsNotTheLongestOne() {
        // The property, stated independently of the numbers above: whatever the
        // timings become, the pause on empty must never outlast the pause on
        // full. That is the whole finding.
        assertTrue(BreathPhase.HOLD_EMPTY.seconds < BreathPhase.HOLD_FULL.seconds)
    }

    @Test
    fun thePaceStaysInsideTheBandSlowBreathingResearchActuallySupports() {
        // Vagal/HRV effects of paced breathing peak around 5.5-6 breaths/min,
        // and a head-to-head trial found 4-4-4-4 (3.75/min) produced higher
        // heart rate and higher perceived exertion than 6/min. Slower is not
        // automatically calmer, so the cycle has a floor as well as a ceiling.
        val breathsPerMinute = 60.0 / CYCLE_SECONDS
        assertTrue("$breathsPerMinute/min is too fast to be a pause", breathsPerMinute <= 6.0)
        assertTrue("$breathsPerMinute/min is slower than the evidence", breathsPerMinute >= 4.0)
    }

    @Test
    fun anInhaleNeverFollowsAnExhaleWithoutTheSettleBetween() {
        // THE regression. This cycle ran `% 3` for two releases — in, hold, out,
        // and straight back into the next inhale — while both this file's
        // ancestor and docs/ATTENTION.md called it *box* breathing. Reported
        // from a handset as "it feels forced", which is what a missing pause
        // feels like.
        var previous = breathingPhase(0)
        for (second in 1..(CYCLE_SECONDS * 5)) {
            val current = breathingPhase(second)
            if (previous == BreathPhase.OUT && current != BreathPhase.OUT) {
                assertEquals(
                    "second $second: an exhale must empty into a pause, not an inhale",
                    BreathPhase.HOLD_EMPTY,
                    current,
                )
            }
            previous = current
        }
    }

    @Test
    fun everyPhaseLastsExactlyItsOwnDeclaredLength() {
        // The phases are no longer equal, so a single `/ phaseSeconds` no longer
        // works — this walks the whole cycle second by second and checks each
        // phase occupies precisely `seconds` of it, in declaration order.
        var second = 0
        for (phase in BreathPhase.entries) {
            for (offset in 0 until phase.seconds) {
                assertEquals(
                    "second $second (offset $offset of $phase)",
                    phase,
                    breathingPhase(second),
                )
                second++
            }
        }
        assertEquals("the phases must account for the whole cycle", CYCLE_SECONDS, second)
    }

    @Test
    fun theScreenOpensOnAnInhale() {
        // Not mid-cycle, and not on a pause: second zero is the start of a breath.
        assertEquals(BreathPhase.IN, breathingPhase(0))
        // The cycle repeats rather than running out.
        assertEquals(BreathPhase.IN, breathingPhase(CYCLE_SECONDS))
        assertEquals(BreathPhase.IN, breathingPhase(CYCLE_SECONDS * 7))
    }

    @Test
    fun aNegativeClockDoesNotFallOffTheCycle() {
        assertEquals(BreathPhase.IN, breathingPhase(-5))
    }

    @Test
    fun theInhaleLineIsShowingOnArrival() {
        // Second zero is the start of a breath, so it is the "draw on this" half
        // of the first pair — not mid-change and not the exhale line.
        assertTrue(showsInhaleLine(breathingPhase(0)))
        assertEquals(0, breathingPairIndex(0, 8))
    }

    @Test
    fun theLineTurnsOverExactlyTwiceEachBreath() {
        // The ask: one line going in, another coming out. Two changes per cycle
        // — no more (a line per phase would swap during a pause, when nothing is
        // being asked of the reader) and no fewer (one per cycle is what this
        // replaced).
        val shown = (0 until CYCLE_SECONDS).map { second ->
            breathingPairIndex(second, 8) to showsInhaleLine(breathingPhase(second))
        }
        // One change inside the cycle: the inhale half handing over to the exhale
        // half, as the out-breath starts.
        assertEquals(1, shown.zipWithNext().count { (a, b) -> a != b })
        // The second change is the wrap the zip above cannot see — into the next
        // pair's inhale half, on the next breath.
        assertEquals(1, breathingPairIndex(CYCLE_SECONDS, 8))
        assertTrue(showsInhaleLine(breathingPhase(CYCLE_SECONDS)))
    }

    @Test
    fun aLineHoldsThroughThePauseThatFollowsIt() {
        // The inhale line carries through the hold-on-full; the exhale line
        // carries through the settle. Changing mid-pause would put a new sentence
        // on screen in the one moment there is nothing to do with it.
        assertTrue(showsInhaleLine(BreathPhase.IN))
        assertTrue(showsInhaleLine(BreathPhase.HOLD_FULL))
        assertFalse(showsInhaleLine(BreathPhase.OUT))
        assertFalse(showsInhaleLine(BreathPhase.HOLD_EMPTY))
    }

    @Test
    fun aPairIsNeverSplitAcrossABreath() {
        // Whatever someone reads on the way out must be the partner of what they
        // read on the way in, so the index has to hold still for a whole cycle
        // and advance on the inhale.
        for (second in 0 until CYCLE_SECONDS) {
            assertEquals("second $second", 0, breathingPairIndex(second, 8))
        }
        assertEquals(1, breathingPairIndex(CYCLE_SECONDS, 8))
        assertEquals(1, breathingPairIndex(CYCLE_SECONDS * 2 - 1, 8))
        assertEquals(2, breathingPairIndex(CYCLE_SECONDS * 2, 8))
    }

    @Test
    fun theSetRepeatingOnALongSitIsAllowed() {
        // Deliberately NOT the "never repeats" assertion this replaced. Five
        // minutes is 21 cycles against eight pairs, so the set comes round two
        // and a half times — which for someone waiting out a bad few minutes is
        // mantra rather than the screen running out of things to say. The test
        // exists to stop a future reader "fixing" the repetition by reference to
        // the old rule.
        val cyclesInLongestSit = (5 * 60) / CYCLE_SECONDS
        assertTrue("a five-minute sit should outlast the set", cyclesInLongestSit > 8)
        assertEquals(0, breathingPairIndex(CYCLE_SECONDS * 8, 8))
    }

    @Test
    fun thePairIndexStaysInsideTheArrayWhateverTheInputs() {
        // stringArrayResource decides the count, not this function, so it must
        // hold for any of them — including the degenerate ones.
        for (count in 1..13) {
            for (second in -5..400) {
                val i = breathingPairIndex(second, count)
                assertTrue("index $i out of bounds for $count pairs", i in 0 until count)
            }
        }
    }

    @Test
    fun noPairsAtAllIsSurvivedRatherThanThrown() {
        // A translation that shipped an array empty — or the two arrays at
        // different lengths, which the caller resolves with minOf — must not
        // crash the pause screen. The caller gets 0 and draws nothing.
        assertEquals(0, breathingPairIndex(30, 0))
    }

    @Test
    fun progressFillsTheChosenLengthAndStopsThere() {
        assertEquals(0f, breathingProgress(0, 60), 0.0001f)
        assertEquals(0.5f, breathingProgress(30, 60), 0.0001f)
        assertEquals(1f, breathingProgress(60, 60), 0.0001f)
    }

    @Test
    fun progressIsClampedBecauseShorteningASitJumpsElapsedPastTheTotal() {
        // The counter stops at the total now, so it no longer walks past 1 by
        // itself — but dropping from 5 min to 1 min mid-sit moves the total under
        // elapsed in a single tap. A progress bar handed 3.4 is a wrong drawing
        // at best.
        assertEquals(1f, breathingProgress(200, 60), 0.0001f)
        assertEquals(1f, breathingProgress(300, 60), 0.0001f)
        assertEquals(0f, breathingProgress(-10, 60), 0.0001f)
        // A zero-length sit is already over rather than a division by zero.
        assertEquals(1f, breathingProgress(0, 0), 0.0001f)
    }

    @Test
    fun aSitEndsOnAWholeBreath() {
        // THE regression for this round. The screen used to count on for ever:
        // reaching the chosen length changed the button's word and nothing else,
        // so a one-minute sit kept breathing indefinitely. Reported from a
        // handset as "it doesn't seem to be stopping after 1 min".
        //
        // Every offered length has to land on a cycle boundary, because that is
        // what lets the sit end on an out-breath and a settle instead of freezing
        // part-way up an inhale with the haptic mid-ramp.
        for (minutes in listOf(1, 2, 3, 5)) {
            val run = breathingRunSeconds(minutes)
            assertEquals("$minutes min must end on a whole breath", 0, run % CYCLE_SECONDS)
            assertTrue("$minutes min must run at least one breath", run >= CYCLE_SECONDS)
        }
    }

    @Test
    fun theChipsStayHonestInBothDirections() {
        // Rounding to nearest, not up: no chip may be more than half a cycle from
        // the number it shows. Someone who chose one minute because that is all
        // they had should not be held for eighty seconds.
        for (minutes in listOf(1, 2, 3, 5)) {
            val drift = breathingRunSeconds(minutes) - minutes * 60
            assertTrue(
                "$minutes min drifts ${drift}s from the chip",
                kotlin.math.abs(drift) <= CYCLE_SECONDS / 2,
            )
        }
        // The real numbers, pinned so a cycle change shows up as a diff here.
        assertEquals(56, breathingRunSeconds(1))
        assertEquals(126, breathingRunSeconds(2))
        assertEquals(182, breathingRunSeconds(3))
        assertEquals(294, breathingRunSeconds(5))
    }

    @Test
    fun aSitIsNeverZeroLength() {
        // A sit always gets at least one full breath — a zero or negative minute
        // count is a caller bug, and rounding it to "already over" would open the
        // screen on the completed state with the circle still.
        assertEquals(CYCLE_SECONDS, breathingRunSeconds(0))
        assertEquals(CYCLE_SECONDS, breathingRunSeconds(-3))
    }

    @Test
    fun theSitStopsAtTheEndOfABreathNotPartWayUpAnInhale() {
        // The property the completed state leans on: because a run is a whole
        // number of cycles, the final second is the last of HOLD_EMPTY, and the
        // circle is therefore already at its smallest when the screen goes still.
        // BreathingScreen asserts this by snapping to MIN_SCALE on completion —
        // if this ever stops holding, that snap becomes a visible jump.
        for (minutes in listOf(1, 2, 3, 5)) {
            val run = breathingRunSeconds(minutes)
            assertEquals(
                "$minutes min must finish on the settle",
                BreathPhase.HOLD_EMPTY,
                breathingPhase(run - 1),
            )
        }
    }
}
