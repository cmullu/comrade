package mullu.comrade.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the emoji picker's insertion rule and the integrity of its table.
 *
 * Inserting at the end instead of at the caret is the classic emoji-picker bug:
 * it looks right every time you test it by typing, and wrong the moment anyone
 * moves the cursor.
 */
class EmojiPickerTest {

    @Test
    fun insertsAtTheCaretNotTheEnd() {
        val value = TextFieldValue("ab", selection = TextRange(1))
        val next = insertEmoji(value, "🙂")
        assertEquals("a🙂b", next.text)
    }

    @Test
    fun leavesTheCaretAfterTheEmoji() {
        val next = insertEmoji(TextFieldValue("ab", selection = TextRange(1)), "🙂")
        // So picking three in a row reads as typing three in a row.
        assertEquals(1 + "🙂".length, next.selection.start)
        assertTrue(next.selection.collapsed)
        val third = insertEmoji(insertEmoji(next, "🎉"), "🔥")
        assertEquals("a🙂🎉🔥b", third.text)
    }

    @Test
    fun replacesTheSelection() {
        val value = TextFieldValue("hello", selection = TextRange(0, 5))
        assertEquals("🙂", insertEmoji(value, "🙂").text)
    }

    @Test
    fun appendsWhenThereIsNoCaretYet() {
        // A field that has never been focused reports an empty selection at 0;
        // an emoji then belongs at the start, which is also the end.
        assertEquals("🙂", insertEmoji(TextFieldValue(""), "🙂").text)
    }

    @Test
    fun anOutOfRangeSelectionIsClampedRatherThanCrashing() {
        // Defensive: state restored across a process death can carry a selection
        // that no longer fits the text. Better a sane insert than an exception
        // in the composer.
        val value = TextFieldValue("ab", selection = TextRange(99, 120))
        assertEquals("ab🙂", insertEmoji(value, "🙂").text)
    }

    @Test
    fun theTableIsNonEmptyAndSplitsCleanly() {
        assertTrue(EmojiGroups.isNotEmpty())
        for (group in EmojiGroups) {
            assertTrue("${group.label} is empty", group.emoji.isNotEmpty())
            // A stray double space would otherwise yield a blank "emoji" that
            // renders as an invisible, tappable gap in the grid.
            assertTrue(
                "${group.label} has a blank entry",
                group.emoji.none { it.isBlank() },
            )
        }
    }

    @Test
    fun groupLabelsAreUnique() {
        val labels = EmojiGroups.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }
}
