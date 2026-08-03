package mullu.comrade.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The breathing cycle and the calming-line rotation. Pure, so they can be pinned
 * here rather than on an emulator — and both are worth pinning: one guards the
 * bug this screen actually shipped with, the other an index out of bounds on a
 * screen someone reached for while having a bad minute.
 */
class BreathingScreenTest {

    private val phase = 4
    private val cycle = phase * 4 // in → hold → out → hold

    @Test
    fun anInhaleNeverFollowsAnExhaleWithoutTheEmptyHold() {
        // THE regression. This cycle ran `% 3` for two releases — in, hold, out,
        // and straight back into the next inhale — while both this file's
        // ancestor and docs/ATTENTION.md called it *box* breathing. Reported
        // from a handset as "it feels forced", which is what a missing pause
        // feels like.
        var previous = breathingPhase(0, phase)
        for (second in 1..(cycle * 5)) {
            val current = breathingPhase(second, phase)
            if (previous == BreathPhase.OUT && current != BreathPhase.OUT) {
                assertEquals(
                    "second $second: an exhale must empty into a hold, not an inhale",
                    BreathPhase.HOLD_EMPTY,
                    current,
                )
            }
            previous = current
        }
    }

    @Test
    fun theBoxHasFourEqualSidesInOrder() {
        assertEquals(4, BreathPhase.entries.size)
        val expected = listOf(
            BreathPhase.IN,
            BreathPhase.HOLD_FULL,
            BreathPhase.OUT,
            BreathPhase.HOLD_EMPTY,
        )
        assertEquals(expected, BreathPhase.entries.toList())

        // Every side is exactly `phase` seconds long — equal sides are what
        // makes it a box rather than any old four-part rhythm.
        for ((i, side) in expected.withIndex()) {
            for (offset in 0 until phase) {
                assertEquals(
                    "side $i, offset $offset",
                    side,
                    breathingPhase(i * phase + offset, phase),
                )
            }
        }
    }

    @Test
    fun theScreenOpensOnAnInhale() {
        // Not mid-cycle, and not on a hold: second zero is the start of a breath.
        assertEquals(BreathPhase.IN, breathingPhase(0, phase))
        // The cycle repeats rather than running out.
        assertEquals(BreathPhase.IN, breathingPhase(cycle, phase))
        assertEquals(BreathPhase.IN, breathingPhase(cycle * 7, phase))
    }

    @Test
    fun aDegeneratePhaseLengthDoesNotDivideByZero() {
        assertEquals(BreathPhase.IN, breathingPhase(30, 0))
        assertEquals(BreathPhase.IN, breathingPhase(-5, phase))
    }

    @Test
    fun theFirstLineIsAlreadySettledOnArrival() {
        // Not mid-change, and not blank: second zero is a line someone can read.
        assertEquals(0, breathingLineIndex(0, phase, 10))
    }

    @Test
    fun aLineOutlastsSeveralBreathsRatherThanOne() {
        // Swapping every phase — or even every cycle — would be one more thing
        // to keep up with, on the one screen in the app with nothing to keep up
        // with. Two full cycles is 32 seconds.
        val lineSeconds = cycle * 2
        for (second in 0 until lineSeconds) {
            assertEquals("second $second", 0, breathingLineIndex(second, phase, 10))
        }
        assertEquals(1, breathingLineIndex(lineSeconds, phase, 10))
        assertEquals(1, breathingLineIndex(lineSeconds * 2 - 1, phase, 10))
        assertEquals(2, breathingLineIndex(lineSeconds * 2, phase, 10))
    }

    @Test
    fun theLongestOfferedSitGetsThroughTheListWithoutRepeating() {
        // Five minutes is the longest duration chip, and ten lines at 32s each
        // covers 320s. If a line is ever removed from the array this fails,
        // which is the point: the same sentence coming round again reads as the
        // screen having run out of things to say.
        val longestSit = 5 * 60
        val lineSeconds = cycle * 2
        val seen = (0..longestSit step lineSeconds).map { breathingLineIndex(it, phase, 10) }
        assertEquals(seen.size, seen.distinct().size)
        assertTrue("ten lines should cover a five-minute sit", seen.size <= 10)
    }

    @Test
    fun theLineIndexStaysInsideTheArrayWhateverTheInputs() {
        // stringArrayResource decides the count, not this function, so it must
        // hold for any of them — including the degenerate ones.
        for (count in 1..12) {
            for (second in -5..400) {
                val i = breathingLineIndex(second, phase, count)
                assertTrue("index $i out of bounds for $count lines", i in 0 until count)
            }
        }
    }

    @Test
    fun anEmptyLineArrayIsSurvivedRatherThanThrown() {
        // A translation that shipped the array empty must not crash the pause
        // screen; the caller gets 0 and can decide what to draw.
        assertEquals(0, breathingLineIndex(30, phase, 0))
        assertEquals(0, breathingLineIndex(30, 0, 10))
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
