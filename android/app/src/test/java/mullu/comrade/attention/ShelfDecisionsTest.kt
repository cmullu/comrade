package mullu.comrade.attention

import mullu.comrade.attention.ShelfDecisions.RowAction
import mullu.comrade.attention.ShelfDecisions.RowFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfDecisionsTest {

    @Test
    fun `a host is read off a url and a credentials shape is refused`() {
        assertEquals("example.com", ShelfDecisions.hostOf("https://www.Example.com/a/b?c=1"))
        assertEquals("sub.example.co.uk", ShelfDecisions.hostOf("http://sub.example.co.uk:8443/x"))
        assertEquals("instagram.com", ShelfDecisions.hostOf("HTTPS://instagram.com/p/Cx1/"))
        // The part after the `@` is what a browser resolves, so labelling a row
        // with the part before it would name the one component that does not
        // decide where the link goes.
        assertNull(ShelfDecisions.hostOf("https://user@evil.example/real.example.com"))
        assertNull(ShelfDecisions.hostOf("not a url"))
        assertNull(ShelfDecisions.hostOf(null))
        assertNull(ShelfDecisions.hostOf(""))
        // A dotless host is a LAN name, not something to label a row with.
        assertNull(ShelfDecisions.hostOf("https://router/x"))
    }

    @Test
    fun `a row with no title falls back to something scannable`() {
        assertEquals(
            "The attention economy",
            ShelfDecisions.rowTitle("  The attention economy  ", "https://example.com/a"),
        )
        // Half of an export archive is bare links; the host is the difference
        // between a shelf someone can scan and a column of identical prefixes.
        assertEquals(
            "instagram.com",
            ShelfDecisions.rowTitle("", "https://www.instagram.com/p/CxAbc123/"),
        )
        // Not a URL at all: show it, and let the caller decide about "Untitled".
        assertEquals("mailto:someone", ShelfDecisions.rowTitle("", "mailto:someone"))
        assertEquals("", ShelfDecisions.rowTitle("   ", null))
    }

    @Test
    fun `a bookmark is never offered a reader it has nothing to fill`() {
        // The load-bearing case. Comrade does not fetch pages, so a "Read" on a
        // link-only row opens an empty reader — which reads as a broken app
        // rather than as a deliberate boundary.
        val bookmark = ShelfDecisions.rowActions(
            hasText = false,
            hasUrl = true,
            position = 0,
            finished = false,
        )
        assertFalse(bookmark.contains(RowAction.READ))
        assertFalse(bookmark.contains(RowAction.RESUME))
        assertTrue(bookmark.contains(RowAction.OPEN_LINK))
        // And it offers the one thing that *can* fill it: the text, by hand.
        assertTrue(bookmark.contains(RowAction.ADD_TEXT))
        assertEquals(RowAction.REMOVE, bookmark.last())
    }

    @Test
    fun `a readable row offers read, and resume once there is a position`() {
        assertEquals(
            listOf(RowAction.READ, RowAction.OPEN_LINK, RowAction.REMOVE),
            ShelfDecisions.rowActions(hasText = true, hasUrl = true, position = 0, finished = false),
        )
        assertEquals(
            listOf(RowAction.RESUME, RowAction.OPEN_LINK, RowAction.REMOVE),
            ShelfDecisions.rowActions(hasText = true, hasUrl = true, position = 3, finished = false),
        )
        // "Resume" on something read to the end is an offer to continue
        // something with no more of itself left.
        assertEquals(
            listOf(RowAction.READ, RowAction.REMOVE),
            ShelfDecisions.rowActions(hasText = true, hasUrl = false, position = 9, finished = true),
        )
        // A row with text already in it is not offered more of it.
        assertFalse(
            ShelfDecisions.rowActions(hasText = true, hasUrl = true, position = 0, finished = false)
                .contains(RowAction.ADD_TEXT),
        )
    }

    @Test
    fun `every row can always be removed`() {
        for (hasText in listOf(true, false)) {
            for (hasUrl in listOf(true, false)) {
                val actions =
                    ShelfDecisions.rowActions(hasText, hasUrl, position = 0, finished = false)
                assertTrue(
                    "text=$hasText url=$hasUrl",
                    actions.contains(RowAction.REMOVE),
                )
                assertEquals("text=$hasText url=$hasUrl", RowAction.REMOVE, actions.last())
            }
        }
    }

    @Test
    fun `a bookmark does not claim a reading time of zero minutes`() {
        val bookmark = ShelfDecisions.rowFacts(
            hasText = false,
            estimatedMinutes = 0,
            finished = false,
            isOpen = false,
        )
        assertEquals(listOf(RowFact.SOURCE, RowFact.LINK_ONLY), bookmark)

        val article = ShelfDecisions.rowFacts(
            hasText = true,
            estimatedMinutes = 7,
            finished = false,
            isOpen = false,
        )
        assertEquals(listOf(RowFact.SOURCE, RowFact.READING_TIME), article)

        // Text too short to round to a minute says nothing about its length
        // rather than saying "0 min".
        assertEquals(
            listOf(RowFact.SOURCE),
            ShelfDecisions.rowFacts(hasText = true, estimatedMinutes = 0, finished = false, isOpen = false),
        )
    }

    @Test
    fun `finished and open are mutually exclusive, and finished wins`() {
        val both = ShelfDecisions.rowFacts(
            hasText = true,
            estimatedMinutes = 5,
            finished = true,
            isOpen = true,
        )
        assertTrue(both.contains(RowFact.FINISHED))
        assertFalse(both.contains(RowFact.OPEN))
        assertTrue(
            ShelfDecisions.rowFacts(true, 5, finished = false, isOpen = true)
                .contains(RowFact.OPEN),
        )
    }

    @Test
    fun `a blank paste is not a save, and everything else is the engine's call`() {
        assertFalse(ShelfDecisions.worthSaving(""))
        assertFalse(ShelfDecisions.worthSaving("   \n  "))
        // Deliberately permissive: whether this is a link, an article or a scrap
        // is `library::parse_shared`'s answer, and second-guessing it here is how
        // the two frontends would come to disagree about what counts as a save.
        assertTrue(ShelfDecisions.worthSaving("https://example.com/a"))
        assertTrue(ShelfDecisions.worthSaving("read the Bergson chapter"))
    }
}
