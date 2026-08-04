package mullu.comrade.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure half of reactions and swipe-to-reply.
 *
 * Mirrored by `app/test/message_reactions_test.dart`, case for case. The point
 * of keeping both is that the two frontends implement the same two gestures, and
 * the numbers behind them (how far a drag must go, which emoji the row offers,
 * how ties in the chip order break) have to be the same or the same message
 * behaves differently depending on which app you opened.
 */
class MessageReactionsTest {

    private val me = "npub1me"
    private val ana = "npub1ana"
    private val bo = "npub1bo"

    private fun row(reactor: String, emoji: String, target: String = "m1") =
        ReactionRow(targetId = target, reactor = reactor, emoji = emoji)

    @Test
    fun `the quick row is six emoji and matches Flutter`() {
        assertEquals(6, QUICK_REACTIONS.size)
        assertEquals(listOf("👍", "❤️", "🔥", "😂", "😮", "🙏"), QUICK_REACTIONS)
        assertEquals("no duplicates", QUICK_REACTIONS.size, QUICK_REACTIONS.distinct().size)
    }

    @Test
    fun `nothing in, nothing out`() {
        assertTrue(summariseReactions(emptyList(), me).isEmpty())
    }

    @Test
    fun `the same emoji from several people is one chip with a count`() {
        val chips = summariseReactions(listOf(row(ana, "🔥"), row(bo, "🔥")), me)
        assertEquals(1, chips.size)
        assertEquals(ReactionChip("🔥", 2, mine = false), chips.single())
    }

    @Test
    fun `your own reaction is marked so tapping the chip can take it back`() {
        val chips = summariseReactions(listOf(row(ana, "🔥"), row(me, "🔥")), me)
        assertEquals(ReactionChip("🔥", 2, mine = true), chips.single())
        // …and not marked when it is someone else's.
        assertFalse(summariseReactions(listOf(row(ana, "👍")), me).single().mine)
    }

    @Test
    fun `chips order by count, then by first appearance`() {
        // The tie-break is what stops the row reshuffling between reloads. Two
        // emoji on one each must come back in the order they arrived, not in
        // codepoint order.
        val chips = summariseReactions(
            listOf(row(ana, "😂"), row(bo, "🔥"), row(me, "🔥")),
            me,
        )
        assertEquals(listOf("🔥", "😂"), chips.map { it.emoji })
        assertEquals(listOf(2, 1), chips.map { it.count })

        val tied = summariseReactions(listOf(row(ana, "🙏"), row(bo, "😮")), me)
        assertEquals("first seen wins a tie", listOf("🙏", "😮"), tied.map { it.emoji })
    }

    @Test
    fun `one person counted once even if handed to us twice`() {
        // Merging a live IncomingReaction into an already-loaded list can double
        // a row for a frame. A chip reading "2" when one person reacted would be
        // a lie the next reload silently corrects.
        val chips = summariseReactions(listOf(row(ana, "🔥"), row(ana, "🔥")), me)
        assertEquals(1, chips.single().count)
    }

    @Test
    fun `a withdrawn reaction draws no chip`() {
        // An empty emoji is the withdrawal on the wire; if it ever reaches the
        // UI list, it must not render as a blank chip.
        val chips = summariseReactions(listOf(row(ana, ""), row(bo, "🔥")), me)
        assertEquals(listOf("🔥"), chips.map { it.emoji })
    }

    // ── Swipe to reply ───────────────────────────────────────────────────────

    @Test
    fun `a leftward drag moves nothing and replies to nothing`() {
        assertEquals(0f, replySwipeOffsetDp(-40f), 0.001f)
        assertFalse(replySwipeTriggered(-100f))
        assertEquals(0f, replySwipeOffsetDp(0f), 0.001f)
    }

    @Test
    fun `the bubble follows the finger up to a cap`() {
        assertEquals(20f, replySwipeOffsetDp(20f), 0.001f)
        assertEquals(REPLY_SWIPE_MAX_DP, replySwipeOffsetDp(REPLY_SWIPE_MAX_DP), 0.001f)
        assertEquals(
            "a long drag must not push the bubble off screen",
            REPLY_SWIPE_MAX_DP,
            replySwipeOffsetDp(1000f),
            0.001f,
        )
    }

    @Test
    fun `a short wobble does not open a reply but a real flick does`() {
        // A vertical scroll carries some horizontal noise; the threshold is what
        // keeps that from opening a reply nobody asked for.
        assertFalse(replySwipeTriggered(8f))
        assertFalse(replySwipeTriggered(REPLY_SWIPE_TRIGGER_DP - 0.5f))
        assertTrue(replySwipeTriggered(REPLY_SWIPE_TRIGGER_DP))
        assertTrue(replySwipeTriggered(200f))
    }

    @Test
    fun `the bubble can always be dragged as far as the trigger`() {
        // If the cap were below the trigger the gesture would be unreachable —
        // the bubble would stop moving before the drag ever counted.
        assertTrue(
            "cap $REPLY_SWIPE_MAX_DP must not be below trigger $REPLY_SWIPE_TRIGGER_DP",
            REPLY_SWIPE_MAX_DP >= REPLY_SWIPE_TRIGGER_DP,
        )
        assertTrue(replySwipeTriggered(replySwipeOffsetDp(1000f)))
    }
}
