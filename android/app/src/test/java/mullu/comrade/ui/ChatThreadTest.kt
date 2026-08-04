package mullu.comrade.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the chat-thread UX rules: when a day separator is due, when fresh
 * messages may auto-scroll the reader, and how bubble times render.
 */
class ChatThreadTest {

    private val utc = java.time.ZoneId.of("UTC")
    private val noon = 1_752_321_600L // 2025-07-12T12:00:00Z

    // ── Day separators ──────────────────────────────────────────────────────

    @Test
    fun firstMessageAlwaysOpensADay() {
        assertTrue(startsNewDay(null, noon, utc))
    }

    @Test
    fun sameDayDoesNotRepeatTheHeader() {
        assertFalse(startsNewDay(noon, noon + 3600, utc))
    }

    @Test
    fun crossingMidnightOpensANewDay() {
        // 23:30 → 00:30 the next day: only a one-hour gap, but a new date.
        val lateEvening = noon + 11 * 3600 + 1800
        assertTrue(startsNewDay(lateEvening, lateEvening + 3600, utc))
    }

    @Test
    fun dayBoundaryFollowsTheZoneNotUtc() {
        // 23:30 UTC and 00:30 UTC straddle midnight in UTC, but both are
        // afternoon of the same day in UTC+10.
        val lateEvening = noon + 11 * 3600 + 1800
        assertFalse(startsNewDay(lateEvening, lateEvening + 3600, java.time.ZoneId.of("+10:00")))
    }

    // ── Auto-scroll on new messages ─────────────────────────────────────────

    @Test
    fun readerAtTheNewestMessageAutoScrolls() {
        assertTrue(isNearBottom(lastVisibleIndex = 9, totalCount = 10))
    }

    @Test
    fun readerWithinSlackOfTheBottomAutoScrolls() {
        assertTrue(isNearBottom(lastVisibleIndex = 7, totalCount = 10, slack = 2))
    }

    @Test
    fun readerScrolledUpInHistoryIsNotYanked() {
        assertFalse(isNearBottom(lastVisibleIndex = 6, totalCount = 10, slack = 2))
        assertFalse(isNearBottom(lastVisibleIndex = 0, totalCount = 100))
    }

    @Test
    fun emptyOrUnmeasuredListCountsAsBottom() {
        assertTrue(isNearBottom(lastVisibleIndex = -1, totalCount = 0))
    }

    // ── Bubble timestamps ───────────────────────────────────────────────────

    @Test
    fun clockTimeRendersWallClockInTheGivenZone() {
        assertEquals("12:00", clockTime(noon, utc))
        assertEquals("12:05", clockTime(noon + 300, utc))
        assertEquals("22:00", clockTime(noon, java.time.ZoneId.of("+10:00")))
        // 24-hour clock, zero-padded: 00:xx just after midnight.
        assertEquals("00:30", clockTime(noon + 12 * 3600 + 1800, utc))
    }

    // ── Opening position (Telegram's first-unread rule) ──────────────────────
    //
    // Mirrored in `app/test/chat_thread_test.dart`.

    /** A thread of `(createdAt, outgoing)` pairs, as the merged list renders. */
    private fun thread(vararg items: Pair<Long, Boolean>) =
        items.map { it.first } to items.map { it.second }

    @Test
    fun opensAtTheFirstMessageNewerThanTheReadPosition() {
        val (at, out) = thread(
            100L to false,
            200L to false,
            300L to false,
            400L to false,
        )
        // Read up to 200 → the 300 message is the first unseen one.
        assertEquals(2, firstUnreadIndex(at, out, lastReadAt = 200))
    }

    @Test
    fun aFirstVisitOpensAtTheNewestMessage() {
        val (at, out) = thread(100L to false, 200L to false)
        // No stored position: there is no honest "where you left off", and the
        // top of a long history is a worse guess than the bottom.
        assertNull(firstUnreadIndex(at, out, lastReadAt = 0))
    }

    @Test
    fun aFullyReadThreadOpensAtTheNewestMessage() {
        val (at, out) = thread(100L to false, 200L to false)
        assertNull(firstUnreadIndex(at, out, lastReadAt = 200))
        // A watermark past everything (clock skew between two devices) is still
        // "nothing unread", not a crash or a bogus index.
        assertNull(firstUnreadIndex(at, out, lastReadAt = 9_999))
    }

    @Test
    fun yourOwnMessagesAreNeverUnread() {
        val (at, out) = thread(
            100L to false,
            200L to true, // sent by you, after the read point
            300L to true,
        )
        // Sending from another device must not make the thread claim unread mail.
        assertNull(firstUnreadIndex(at, out, lastReadAt = 100))
    }

    @Test
    fun theDividerLandsOnTheFirstIncomingMessagePastYourOwn() {
        val (at, out) = thread(
            100L to false,
            200L to true, // you replied
            300L to false, // they wrote back — this is what's new
            400L to false,
        )
        assertEquals(2, firstUnreadIndex(at, out, lastReadAt = 100))
    }

    @Test
    fun anEmptyThreadHasNothingToOpenAt() {
        assertNull(firstUnreadIndex(emptyList(), emptyList(), lastReadAt = 100))
    }

    @Test
    fun theDividerRendersOnlyAtTheBoundary() {
        assertTrue(startsUnread(index = 2, firstUnread = 2))
        assertFalse(startsUnread(index = 1, firstUnread = 2))
        assertFalse(startsUnread(index = 3, firstUnread = 2))
        // Nothing unread → no divider anywhere.
        assertFalse(startsUnread(index = 0, firstUnread = null))
    }

    // ── Tapping a quote to reach the original ─────────────────────────────────

    @Test
    fun aQuoteTapFindsTheMessageItPointsAt() {
        val ids = listOf("a", "b", "c")
        assertEquals(0, indexOfEventId(ids, "a"))
        assertEquals(2, indexOfEventId(ids, "c"))
    }

    /**
     * The case that decides the feature's behaviour: a reply to something older
     * than the loaded window quotes fine but has nowhere to scroll to. The caller
     * must leave the thread where it is rather than land somewhere arbitrary.
     */
    @Test
    fun anOriginalOutsideTheLoadedThreadHasNoDestination() {
        assertNull(indexOfEventId(listOf("a", "b"), "older"))
        assertNull(indexOfEventId(emptyList(), "a"))
    }

    @Test
    fun aMessageThatIsNotAReplyHasNothingToGoTo() {
        assertNull(indexOfEventId(listOf("a", "b"), null))
        // A blank id is the bridge's "absent", and must not match a blank entry.
        assertNull(indexOfEventId(listOf("a", ""), ""))
    }

    @Test
    fun theFirstMatchWins() {
        // Duplicate ids should not happen — the thread is deduped upstream — but
        // if one ever slips through, the earlier item is the one quoted.
        assertEquals(1, indexOfEventId(listOf("a", "dup", "dup"), "dup"))
    }
}
