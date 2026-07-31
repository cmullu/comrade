package mullu.comrade.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Progressive delivery of Tara's reply, so the conversation reads like a
 * companion thinking out loud rather than a form submission returning a block
 * of text.
 *
 * [chunk] is the whole rule, kept pure so it is pinned by unit tests: split on
 * whitespace boundaries into roughly word-sized pieces, never mid-word, and
 * never losing or adding a character (`chunk(t).joinToString("") == t`).
 *
 * **What is and isn't happening here.** Today's reply comes from the
 * deterministic on-device reflective engine, which computes the whole sentence
 * at once — so this paces out text that already exists. That is a presentation
 * choice, not a token stream, and it is deliberately built as
 * `Flow<String>` of cumulative text: when a real generative backend lands
 * (AUDIT §8 OQ9) it emits its tokens into this same shape and the UI does not
 * change.
 */
object TaraStream {

    /** Roughly a short word per chunk — fast enough to read, slow enough to feel alive. */
    const val DEFAULT_CHUNK_CHARS = 8

    /** Base pause between chunks. ~28 ms lands near a brisk but readable typing speed. */
    const val DEFAULT_DELAY_MS = 28L

    /** Extra breath at a sentence boundary — a human pauses at a full stop. */
    const val SENTENCE_PAUSE_FACTOR = 5L

    /** Smaller breath at a clause boundary (comma, dash, colon…). */
    const val CLAUSE_PAUSE_FACTOR = 2L

    /**
     * Split [text] into successive pieces of at least [targetChars] that each
     * end on whitespace (the final piece takes whatever is left). Concatenating
     * the result reproduces [text] exactly.
     */
    fun chunk(text: String, targetChars: Int = DEFAULT_CHUNK_CHARS): List<String> {
        require(targetChars > 0) { "targetChars must be positive" }
        if (text.isEmpty()) return emptyList()
        val chunks = ArrayList<String>()
        val buffer = StringBuilder()
        for (ch in text) {
            buffer.append(ch)
            if (buffer.length >= targetChars && ch.isWhitespace()) {
                chunks.add(buffer.toString())
                buffer.setLength(0)
            }
        }
        if (buffer.isNotEmpty()) chunks.add(buffer.toString())
        return chunks
    }

    /**
     * How long to pause before the chunk that follows [previous]. A human
     * doesn't type at metronome pace: they breathe at a full stop and lean
     * on a comma. Pure so the rhythm rule is unit-tested.
     */
    fun delayAfter(previous: String, baseMs: Long = DEFAULT_DELAY_MS): Long {
        val tail = previous.trimEnd().lastOrNull() ?: return baseMs
        return when (tail) {
            '.', '!', '?', '…' -> baseMs * SENTENCE_PAUSE_FACTOR
            ',', ';', ':', '—', '–' -> baseMs * CLAUSE_PAUSE_FACTOR
            else -> baseMs
        }
    }

    /**
     * Emit [text] as a growing prefix — the value to render as the reply
     * bubble's contents. The first chunk lands immediately (no dead air after
     * the thinking indicator); the rest are paced by [delayAfter], so the
     * rhythm slows at sentence boundaries instead of ticking uniformly.
     * Cancelling the collecting coroutine simply stops it mid-sentence.
     */
    fun stream(
        text: String,
        perChunkDelayMs: Long = DEFAULT_DELAY_MS,
        targetChars: Int = DEFAULT_CHUNK_CHARS,
    ): Flow<String> = flow {
        val builder = StringBuilder()
        var previous = ""
        chunk(text, targetChars).forEachIndexed { index, piece ->
            if (index > 0) delay(delayAfter(previous, perChunkDelayMs))
            builder.append(piece)
            previous = piece
            emit(builder.toString())
        }
    }
}
