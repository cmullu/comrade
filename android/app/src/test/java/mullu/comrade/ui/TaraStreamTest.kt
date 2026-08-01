package mullu.comrade.ui

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TaraStream]'s chunking contract — what makes a reply *read* as if it were
 * being written, without ever changing what was written.
 *
 * The load-bearing property is losslessness: whatever the chunk size, the
 * pieces reassemble into the exact reply the engine produced. A streaming
 * animation that silently dropped or duplicated a character would corrupt what
 * the user is told — including a crisis hand-off's helpline numbers.
 */
class TaraStreamTest {

    private val reply =
        "That sounds anxious, and it makes sense you'd feel that way. " +
            "What do you think is underneath that?"

    @Test
    fun `chunks reassemble into exactly the original text`() {
        for (size in 1..24) {
            assertEquals(
                "lossless at chunk size $size",
                reply,
                TaraStream.chunk(reply, size).joinToString(""),
            )
        }
    }

    @Test
    fun `newlines and unicode survive chunking`() {
        val text = "Line one\nLine two — with an em dash, ellipsis…\n\nAnd 🙂 an emoji."
        assertEquals(text, TaraStream.chunk(text, 4).joinToString(""))
    }

    @Test
    fun `every chunk but the last ends on whitespace so words are never split`() {
        val chunks = TaraStream.chunk(reply, TaraStream.DEFAULT_CHUNK_CHARS)
        assertTrue("a sentence this long must stream in pieces", chunks.size > 1)
        chunks.dropLast(1).forEach { chunk ->
            assertTrue("chunk must end at a word boundary: '$chunk'", chunk.last().isWhitespace())
            assertTrue(
                "chunk must reach the target length: '$chunk'",
                chunk.length >= TaraStream.DEFAULT_CHUNK_CHARS,
            )
        }
    }

    @Test
    fun `text with no whitespace stays in one piece`() {
        val solid = "supercalifragilistic"
        assertEquals(listOf(solid), TaraStream.chunk(solid, 4))
    }

    @Test
    fun `empty text produces no chunks`() {
        assertEquals(emptyList<String>(), TaraStream.chunk(""))
    }

    @Test
    fun `a non-positive chunk size is rejected rather than looping forever`() {
        assertThrows(IllegalArgumentException::class.java) { TaraStream.chunk(reply, 0) }
        assertThrows(IllegalArgumentException::class.java) { TaraStream.chunk(reply, -3) }
    }

    // ── the emitted stream ───────────────────────────────────────────────────

    @Test
    fun `stream emits growing prefixes and ends with the whole reply`() = runBlocking {
        val emissions = TaraStream.stream(reply, perChunkDelayMs = 0L).toList()

        assertEquals("one emission per chunk", TaraStream.chunk(reply).size, emissions.size)
        assertEquals("the last frame is the complete reply", reply, emissions.last())
        emissions.zipWithNext { shorter, longer ->
            assertTrue("each frame must extend the previous one", longer.startsWith(shorter))
            assertTrue("each frame must actually grow", longer.length > shorter.length)
        }
        // Every intermediate frame is a genuine prefix of the final text, so
        // nothing ever renders that the engine did not produce.
        emissions.forEach { frame -> assertTrue(reply.startsWith(frame)) }
    }

    @Test
    fun `an empty reply streams nothing`() = runBlocking {
        assertEquals(emptyList<String>(), TaraStream.stream("", perChunkDelayMs = 0L).toList())
    }

    // ── pacing rhythm ────────────────────────────────────────────────────────

    @Test
    fun `pauses breathe at sentence boundaries and lean at clauses`() {
        val base = TaraStream.DEFAULT_DELAY_MS
        assertEquals("plain word: base pace", base, TaraStream.delayAfter("sounds "))
        assertEquals(
            "full stop: a real breath",
            base * TaraStream.SENTENCE_PAUSE_FACTOR,
            TaraStream.delayAfter("way. "),
        )
        assertEquals(base * TaraStream.SENTENCE_PAUSE_FACTOR, TaraStream.delayAfter("really? "))
        assertEquals(base * TaraStream.SENTENCE_PAUSE_FACTOR, TaraStream.delayAfter("lot! "))
        assertEquals(base * TaraStream.SENTENCE_PAUSE_FACTOR, TaraStream.delayAfter("well… "))
        assertEquals(
            "comma: a lean, not a stop",
            base * TaraStream.CLAUSE_PAUSE_FACTOR,
            TaraStream.delayAfter("that, "),
        )
        assertEquals(base * TaraStream.CLAUSE_PAUSE_FACTOR, TaraStream.delayAfter("carry — "))
        assertEquals("empty previous chunk: base", base, TaraStream.delayAfter(""))
    }
}
