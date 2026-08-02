package mullu.comrade.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calm-feed contract's one behavioural rule. These are the tests that stop
 * the gentle stop quietly becoming either a nag or a no-op.
 */
class ScrollSittingTest {

    private val start = 1_700_000_000_000L

    @Test
    fun `no offer before the threshold`() {
        assertFalse(ScrollSitting.shouldOffer(start, start, dismissed = false))
        assertFalse(
            ScrollSitting.shouldOffer(start, start + ScrollSitting.THRESHOLD_MS - 1, dismissed = false),
        )
    }

    @Test
    fun `offer once the threshold is reached`() {
        assertTrue(
            ScrollSitting.shouldOffer(start, start + ScrollSitting.THRESHOLD_MS, dismissed = false),
        )
        assertTrue(
            ScrollSitting.shouldOffer(start, start + 30 * 60_000L, dismissed = false),
        )
    }

    @Test
    fun `a dismissal is honoured for the rest of the sitting`() {
        // Gate 3: the card offers once. Re-showing it after a dismissal is how
        // a wellbeing feature turns into a nag.
        assertFalse(
            ScrollSitting.shouldOffer(start, start + 60 * 60_000L, dismissed = true),
        )
    }

    @Test
    fun `not on the feed means no offer`() {
        assertFalse(ScrollSitting.shouldOffer(0L, start, dismissed = false))
    }

    @Test
    fun `a first visit starts the clock`() {
        assertEquals(start, ScrollSitting.resumeOrRestart(previousStart = 0L, leftAt = 0L, now = start))
    }

    @Test
    fun `a short round trip keeps the same sitting`() {
        // Bouncing to a chat and back must not reset the clock — otherwise the
        // threshold is unreachable and the limit is theatre.
        val leftAt = start + 5 * 60_000L
        val backAt = leftAt + 10_000L
        assertEquals(
            start,
            ScrollSitting.resumeOrRestart(previousStart = start, leftAt = leftAt, now = backAt),
        )
    }

    @Test
    fun `a long absence starts a fresh sitting`() {
        val leftAt = start + 5 * 60_000L
        val backAt = leftAt + ScrollSitting.RESET_AFTER_AWAY_MS
        assertEquals(
            backAt,
            ScrollSitting.resumeOrRestart(previousStart = start, leftAt = leftAt, now = backAt),
        )
    }

    @Test
    fun `a sitting with no recorded departure is kept`() {
        // First composition after a recomposition: nothing was left, so the
        // original start stands.
        assertEquals(
            start,
            ScrollSitting.resumeOrRestart(previousStart = start, leftAt = 0L, now = start + 1_000L),
        )
    }
}
