package mullu.comrade.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the conversation ⋮ menu: the comrade and mute rows name the action
 * rather than the state, destructive entries stay last, and nothing is offered
 * twice.
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
    fun unmutedChatIsOfferedMuteAndViceVersa() {
        assertTrue(ChatMenuAction.Mute in conversationMenu(isComrade = false, isMuted = false))
        assertFalse(ChatMenuAction.Unmute in conversationMenu(isComrade = false, isMuted = false))
        assertTrue(ChatMenuAction.Unmute in conversationMenu(isComrade = false, isMuted = true))
        assertFalse(ChatMenuAction.Mute in conversationMenu(isComrade = false, isMuted = true))
    }

    @Test
    fun orderIsStableAndSizeDoesNotChangeWithComradeOrMuteState() {
        assertEquals(
            listOf(
                ChatMenuAction.SetAlias,
                ChatMenuAction.AddComrade,
                ChatMenuAction.Mute,
                ChatMenuAction.CopyKey,
                ChatMenuAction.EncryptionInfo,
                ChatMenuAction.Block,
            ),
            conversationMenu(isComrade = false, isMuted = false),
        )
        // Toggling comrade or mute swaps one row in place — it must not reorder
        // or resize the menu, or a tap lands on a different action than the one
        // the user reached for.
        val sizes = allMenus().map { it.size }.toSet()
        assertEquals(1, sizes.size)
        for (menu in allMenus()) {
            assertEquals(1, menu.indexOfFirst { it.comradeRow })
            assertEquals(2, menu.indexOfFirst { it.muteRow })
        }
    }

    @Test
    fun noActionAppearsTwice() {
        for (menu in allMenus()) {
            assertEquals(menu.size, menu.toSet().size)
        }
    }

    @Test
    fun blockIsTheOnlyDestructiveEntryAndItIsLast() {
        for (menu in allMenus()) {
            assertEquals(ChatMenuAction.Block, menu.last())
            assertEquals(listOf(ChatMenuAction.Block), menu.filter { it.destructive })
        }
    }

    @Test
    fun everyActionIsReachableFromSomeMenu() {
        val offered = allMenus().flatten().toSet()
        // A menu entry nobody can reach is a dead code path; an action missing
        // from the enum is an unhandled `when` branch at the call site.
        assertEquals(ChatMenuAction.entries.toSet(), offered)
    }

    /** Every combination of the two toggles the menu is built from. */
    private fun allMenus(): List<List<ChatMenuAction>> =
        listOf(true, false).flatMap { comrade ->
            listOf(true, false).map { muted -> conversationMenu(isComrade = comrade, isMuted = muted) }
        }

    private val ChatMenuAction.comradeRow: Boolean
        get() = this == ChatMenuAction.AddComrade || this == ChatMenuAction.RemoveComrade

    private val ChatMenuAction.muteRow: Boolean
        get() = this == ChatMenuAction.Mute || this == ChatMenuAction.Unmute
}
