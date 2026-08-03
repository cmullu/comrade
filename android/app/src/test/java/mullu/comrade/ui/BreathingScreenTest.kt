package mullu.comrade.ui

import org.junit.Assert.assertEquals
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
    fun theFirstLineIsAlreadySettledOnArrival() {
        // Not mid-change, and not blank: second zero is a line someone can read.
        assertEquals(0, breathingLineIndex(0, 11))
    }

    @Test
    fun aLineOutlastsSeveralBreathsRatherThanOne() {
        // Swapping every phase — or even every cycle — would be one more thing
        // to keep up with, on the one screen in the app with nothing to keep up
        // with. Two full cycles is 28 seconds.
        assertEquals(28, LINE_SECONDS)
        for (second in 0 until LINE_SECONDS) {
            assertEquals("second $second", 0, breathingLineIndex(second, 11))
        }
        assertEquals(1, breathingLineIndex(LINE_SECONDS, 11))
        assertEquals(1, breathingLineIndex(LINE_SECONDS * 2 - 1, 11))
        assertEquals(2, breathingLineIndex(LINE_SECONDS * 2, 11))
    }

    @Test
    fun theLongestOfferedSitGetsThroughTheListWithoutRepeating() {
        // Five minutes is the longest duration chip. Eleven lines at 28s covers
        // 308s; ten would have repeated one in the last half-minute. If a line
        // is ever removed from the array this fails, which is the point: the
        // same sentence coming round again reads as the screen having run out of
        // things to say.
        val longestSit = 5 * 60
        val seen = (0..longestSit step LINE_SECONDS).map { breathingLineIndex(it, 11) }
        assertEquals(seen.size, seen.distinct().size)
        assertTrue("eleven lines should cover a five-minute sit", seen.size <= 11)
    }

    @Test
    fun theLineIndexStaysInsideTheArrayWhateverTheInputs() {
        // stringArrayResource decides the count, not this function, so it must
        // hold for any of them — including the degenerate ones.
        for (count in 1..13) {
            for (second in -5..400) {
                val i = breathingLineIndex(second, count)
                assertTrue("index $i out of bounds for $count lines", i in 0 until count)
            }
        }
    }

    @Test
    fun anEmptyLineArrayIsSurvivedRatherThanThrown() {
        // A translation that shipped the array empty must not crash the pause
        // screen; the caller gets 0 and can decide what to draw.
        assertEquals(0, breathingLineIndex(30, 0))
    }

    @Test
    fun progressFillsTheChosenLengthAndStopsThere() {
        assertEquals(0f, breathingProgress(0, 60), 0.0001f)
        assertEquals(0.5f, breathingProgress(30, 60), 0.0001f)
        assertEquals(1f, breathingProgress(60, 60), 0.0001f)
    }

    @Test
    fun progressIsClampedBecauseTheClockOutlivesTheSit() {
        // Elapsed deliberately keeps counting after the sit completes, so the
        // ratio goes past 1 — and shortening the duration mid-sit can put it
        // well past. A progress bar handed 3.4 is a wrong drawing at best.
        assertEquals(1f, breathingProgress(200, 60), 0.0001f)
        assertEquals(1f, breathingProgress(300, 60), 0.0001f)
        assertEquals(0f, breathingProgress(-10, 60), 0.0001f)
        // A zero-length sit is already over rather than a division by zero.
        assertEquals(1f, breathingProgress(0, 0), 0.0001f)
    }
}
