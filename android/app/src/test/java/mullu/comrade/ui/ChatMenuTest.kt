package mullu.comrade.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the conversation ⋮ menu: the comrade row names the action rather than
 * the state, destructive entries stay last, and nothing is offered twice.
 *
 * The Dart port (`app/test/chat_menu_test.dart`) asserts the same order, so a
 * change here that isn't mirrored there shows up as a failing test on the
 * other frontend rather than as silent UI drift.
 */
class ChatMenuTest {

    @Test
    fun nonComradeIsOfferedTheAdd() {
        val menu = conversationMenu(isComrade = false)
        assertTrue(ChatMenuAction.AddComrade in menu)
        assertFalse(ChatMenuAction.RemoveComrade in menu)
    }

    @Test
    fun comradeIsOfferedTheRemoval() {
        val menu = conversationMenu(isComrade = true)
        assertTrue(ChatMenuAction.RemoveComrade in menu)
        assertFalse(ChatMenuAction.AddComrade in menu)
    }

    @Test
    fun orderIsStableAndSizeDoesNotChangeWithComradeState() {
        assertEquals(
            listOf(
                ChatMenuAction.SetAlias,
                ChatMenuAction.AddComrade,
                ChatMenuAction.CopyKey,
                ChatMenuAction.EncryptionInfo,
                ChatMenuAction.Block,
            ),
            conversationMenu(isComrade = false),
        )
        // Toggling comrade swaps one row in place — it must not reorder or
        // resize the menu, or a tap lands on a different action than the one
        // the user reached for.
        assertEquals(
            conversationMenu(isComrade = false).size,
            conversationMenu(isComrade = true).size,
        )
        assertEquals(
            conversationMenu(isComrade = false).indexOf(ChatMenuAction.AddComrade),
            conversationMenu(isComrade = true).indexOf(ChatMenuAction.RemoveComrade),
        )
    }

    @Test
    fun noActionAppearsTwice() {
        for (isComrade in listOf(true, false)) {
            val menu = conversationMenu(isComrade)
            assertEquals(menu.size, menu.toSet().size)
        }
    }

    @Test
    fun blockIsTheOnlyDestructiveEntryAndItIsLast() {
        for (isComrade in listOf(true, false)) {
            val menu = conversationMenu(isComrade)
            assertEquals(ChatMenuAction.Block, menu.last())
            assertEquals(listOf(ChatMenuAction.Block), menu.filter { it.destructive })
        }
    }

    @Test
    fun everyActionIsReachableFromSomeMenu() {
        val offered = conversationMenu(isComrade = true) + conversationMenu(isComrade = false)
        // A menu entry nobody can reach is a dead code path; an action missing
        // from the enum is an unhandled `when` branch at the call site.
        assertEquals(ChatMenuAction.entries.toSet(), offered.toSet())
    }
}
