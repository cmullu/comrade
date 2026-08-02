package mullu.comrade.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quiet-hours window arithmetic. The overnight case is the *normal*
 * configuration here, so it gets the most attention: a naive comparison would
 * make the default 22:00→07:00 window match nothing at all.
 */
class QuietHoursTest {

    private val tenPm = 22 * 60
    private val sevenAm = 7 * 60

    @Test
    fun `an overnight window covers both sides of midnight`() {
        assertTrue(QuietHours.isQuietAt(22 * 60, tenPm, sevenAm))
        assertTrue(QuietHours.isQuietAt(23 * 60 + 59, tenPm, sevenAm))
        assertTrue(QuietHours.isQuietAt(0, tenPm, sevenAm))
        assertTrue(QuietHours.isQuietAt(3 * 60, tenPm, sevenAm))
        assertTrue(QuietHours.isQuietAt(6 * 60 + 59, tenPm, sevenAm))
    }

    @Test
    fun `an overnight window excludes the day`() {
        assertFalse(QuietHours.isQuietAt(sevenAm, tenPm, sevenAm))
        assertFalse(QuietHours.isQuietAt(12 * 60, tenPm, sevenAm))
        assertFalse(QuietHours.isQuietAt(21 * 60 + 59, tenPm, sevenAm))
    }

    @Test
    fun `a same-day window is a plain half-open range`() {
        val start = 13 * 60
        val end = 15 * 60
        assertFalse(QuietHours.isQuietAt(start - 1, start, end))
        assertTrue(QuietHours.isQuietAt(start, start, end))
        assertTrue(QuietHours.isQuietAt(end - 1, start, end))
        assertFalse(QuietHours.isQuietAt(end, start, end))
    }

    @Test
    fun `an empty window silences nothing`() {
        // A mis-set picker (start == end) must not silence the phone for a full
        // day — the failure that reads as "my notifications are broken".
        for (minute in listOf(0, 6 * 60, 12 * 60, 22 * 60, 23 * 60 + 59)) {
            assertFalse(QuietHours.isQuietAt(minute, tenPm, tenPm))
        }
    }

    @Test
    fun `formatting is zero-padded 24-hour and wraps safely`() {
        assertEquals("00:00", QuietHours.formatMinute(0))
        assertEquals("07:00", QuietHours.formatMinute(sevenAm))
        assertEquals("22:30", QuietHours.formatMinute(22 * 60 + 30))
        assertEquals("23:59", QuietHours.formatMinute(23 * 60 + 59))
        // Out-of-range input wraps rather than rendering nonsense.
        assertEquals("00:00", QuietHours.formatMinute(QuietHours.MINUTES_PER_DAY))
        assertEquals("23:00", QuietHours.formatMinute(-60))
    }

    @Test
    fun `the defaults are an overnight window`() {
        assertTrue(
            QuietHours.isQuietAt(
                2 * 60,
                QuietHours.DEFAULT_START_MINUTE,
                QuietHours.DEFAULT_END_MINUTE,
            ),
        )
        assertFalse(
            QuietHours.isQuietAt(
                14 * 60,
                QuietHours.DEFAULT_START_MINUTE,
                QuietHours.DEFAULT_END_MINUTE,
            ),
        )
    }
}
