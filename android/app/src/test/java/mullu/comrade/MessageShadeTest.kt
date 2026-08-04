package mullu.comrade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the accumulation behind a `MessagingStyle` message notification.
 *
 * The bug this replaces was quiet and easy to mistake for lost delivery: every
 * message from a peer re-posted that peer's notification id with a fresh
 * `setContentText`, so the second message *overwrote* the first. Three messages
 * arriving while the phone sat face-down left one line in the shade, and the
 * other two existed only inside the app.
 */
class MessageShadeTest {

    private val ana = "npub1ana"
    private val bo = "npub1bo"

    @Before
    fun reset() {
        // Process-global by design (see MessageShade), so each test starts clean.
        MessageShade.clearAll()
    }

    @Test
    fun `lines accumulate instead of replacing each other`() {
        assertEquals(listOf("one"), MessageShade.record(ana, ShadeLine("one", 1)).map { it.text })
        assertEquals(
            listOf("one", "two"),
            MessageShade.record(ana, ShadeLine("two", 2)).map { it.text },
        )
        assertEquals(
            listOf("one", "two", "three"),
            MessageShade.record(ana, ShadeLine("three", 3)).map { it.text },
        )
    }

    @Test
    fun `each peer keeps its own conversation`() {
        MessageShade.record(ana, ShadeLine("hi", 1))
        MessageShade.record(bo, ShadeLine("hey", 2))
        assertEquals(listOf("hi", "yo"), MessageShade.record(ana, ShadeLine("yo", 3)).map { it.text })
        assertEquals(listOf("hey", "sup"), MessageShade.record(bo, ShadeLine("sup", 4)).map { it.text })
    }

    @Test
    fun `opening the conversation forgets its lines`() {
        MessageShade.record(ana, ShadeLine("one", 1))
        MessageShade.record(ana, ShadeLine("two", 2))
        MessageShade.clear(ana)
        // Not "one, two, three": the user read those, and re-posting them as
        // unread would be the notification disagreeing with the app.
        assertEquals(listOf("three"), MessageShade.record(ana, ShadeLine("three", 3)).map { it.text })
    }

    @Test
    fun `clearing one peer leaves the others alone`() {
        MessageShade.record(ana, ShadeLine("a", 1))
        MessageShade.record(bo, ShadeLine("b", 2))
        MessageShade.clear(ana)
        assertEquals(listOf("b", "b2"), MessageShade.record(bo, ShadeLine("b2", 3)).map { it.text })
    }

    // ── The pure bound ───────────────────────────────────────────────────────

    @Test
    fun `the newest lines win once the bound is reached`() {
        var lines = emptyList<ShadeLine>()
        for (i in 1..MAX_SHADE_LINES + 3) lines = appendShadeLine(lines, ShadeLine("m$i", i.toLong()))
        assertEquals(MAX_SHADE_LINES, lines.size)
        assertEquals("m4", lines.first().text)
        assertEquals("m${MAX_SHADE_LINES + 3}", lines.last().text)
    }

    @Test
    fun `timestamps ride along so the shade can order the conversation`() {
        val lines = appendShadeLine(listOf(ShadeLine("first", 100)), ShadeLine("second", 200))
        assertEquals(listOf(100L, 200L), lines.map { it.atMs })
    }

    @Test
    fun `a nonsensical bound still keeps the newest line rather than nothing`() {
        // Defensive: a zero or negative max must not silently produce an empty
        // notification, which would read as a message that never arrived.
        val lines = appendShadeLine(listOf(ShadeLine("old", 1)), ShadeLine("new", 2), max = 0)
        assertTrue(lines.isNotEmpty())
        assertEquals("new", lines.single().text)
    }
}
