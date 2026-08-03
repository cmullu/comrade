package mullu.comrade.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calming-line rotation on the breathing screen. Pure, so it can be pinned
 * here rather than on an emulator — and worth pinning, because the failure it
 * guards is an index out of bounds on a screen someone reached for while having
 * a bad minute.
 */
class BreathingScreenTest {

    private val phase = 4
    private val cycle = phase * 3 // in → hold → out

    @Test
    fun theFirstLineIsAlreadySettledOnArrival() {
        // Not mid-change, and not blank: second zero is a line someone can read.
        assertEquals(0, breathingLineIndex(0, phase, 5))
    }

    @Test
    fun aLineLastsAWholeCycleNotAPhase() {
        // Swapping every four seconds would be one more thing to keep up with,
        // on the one screen in the app with nothing to keep up with.
        for (second in 0 until cycle) {
            assertEquals("second $second", 0, breathingLineIndex(second, phase, 5))
        }
        assertEquals(1, breathingLineIndex(cycle, phase, 5))
        assertEquals(1, breathingLineIndex(cycle * 2 - 1, phase, 5))
        assertEquals(2, breathingLineIndex(cycle * 2, phase, 5))
    }

    @Test
    fun aSixtySecondSitNeverRepeatsALine() {
        // Five lines, five cycles in the sixty seconds the screen runs for. If
        // a line is ever removed from the array this fails, which is the point:
        // seeing the same sentence come round again reads as the screen having
        // run out of things to say.
        val seen = (0 until 60 step cycle).map { breathingLineIndex(it, phase, 5) }
        assertEquals(listOf(0, 1, 2, 3, 4), seen)
    }

    @Test
    fun theIndexStaysInsideTheArrayWhateverTheInputs() {
        // stringArrayResource decides the count, not this function, so it must
        // hold for any of them — including the degenerate ones.
        for (count in 1..7) {
            for (second in -5..200) {
                val i = breathingLineIndex(second, phase, count)
                assertTrue("index $i out of bounds for $count lines", i in 0 until count)
            }
        }
    }

    @Test
    fun anEmptyArrayIsSurvivedRatherThanThrown() {
        // A translation that shipped the array empty must not crash the pause
        // screen; the caller gets 0 and can decide what to draw.
        assertEquals(0, breathingLineIndex(30, phase, 0))
        assertEquals(0, breathingLineIndex(30, 0, 5))
    }
}
