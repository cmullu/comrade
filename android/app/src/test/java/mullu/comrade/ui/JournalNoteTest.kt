package mullu.comrade.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two frontend rules for a shared journal note: how much of it a
 * bubble shows, and who the share sheet is allowed to offer.
 *
 * `desktop/ui/journal_note.test.mjs` and `app/test/journal_note_test.dart`
 * assert the same cases with the same numbers.
 */
class JournalNoteTest {

    // ── The collapsed card ──────────────────────────────────────────────────

    @Test
    fun aShortNoteArrivesWholeAndOffersNothingToExpand() {
        val note = "rough morning, but I went for the walk"
        val preview = notePreview(note)
        assertEquals(note, preview.text)
        assertFalse(preview.truncated)
    }

    @Test
    fun aLongNoteIsFoldedAtTheLineLimit() {
        val note = (1..10).joinToString("\n") { "line $it" }
        val preview = notePreview(note)
        assertEquals(NOTE_PREVIEW_LINES, preview.text.lines().size)
        assertTrue(preview.truncated)
        assertTrue(preview.text.startsWith("line 1"))
    }

    @Test
    fun oneVeryLongParagraphIsFoldedToo() {
        // The line limit alone would let this through whole: it is one line.
        val note = "word ".repeat(200).trim()
        val preview = notePreview(note)
        assertTrue(preview.truncated)
        assertTrue(preview.text.length <= NOTE_PREVIEW_CHARS)
    }

    @Test
    fun theFoldFallsOnAWordBoundary() {
        val note = "supercalifragilistic ".repeat(40).trim()
        val preview = notePreview(note)
        assertFalse("cut mid-word", preview.text.endsWith("superc"))
        assertTrue(preview.text.split(" ").last().isNotEmpty())
    }

    @Test
    fun aNoteThatFitsExactlyIsNotCalledTruncated() {
        // A "show more" that expands nothing tells the reader words are being
        // withheld when none are.
        val note = "x".repeat(NOTE_PREVIEW_CHARS)
        val preview = notePreview(note)
        assertEquals(note, preview.text)
        assertFalse(preview.truncated)
    }

    @Test
    fun aSingleUnbrokenWordIsCutRatherThanShownWhole() {
        // No usable boundary: a hard cut is right, and one word must never
        // stand in for the whole note.
        val note = "x".repeat(NOTE_PREVIEW_CHARS * 2)
        val preview = notePreview(note)
        assertEquals(NOTE_PREVIEW_CHARS, preview.text.length)
        assertTrue(preview.truncated)
    }

    // ── Who may be offered the note ─────────────────────────────────────────

    private fun candidate(npub: String, alias: String? = null, comrade: Boolean = false) =
        ShareCandidate(npub = npub, alias = alias, name = null, comrade = comrade)

    @Test
    fun comradesLeadAndEachGroupIsAlphabetical() {
        val targets = shareTargets(
            listOf(
                candidate("npub1zoe", alias = "Zoe"),
                candidate("npub1ana", alias = "Ana"),
                candidate("npub1bo", alias = "Bo", comrade = true),
                candidate("npub1mira", alias = "mira", comrade = true),
            ),
        )
        assertEquals(listOf("Bo", "mira", "Ana", "Zoe"), targets.map { it.label })
        assertEquals(listOf(true, true, false, false), targets.map { it.comrade })
    }

    @Test
    fun labelsFollowTheSameTrustOrderAsEveryOtherScreen() {
        // Your alias first, then their self-declared handle, then the key —
        // `peerTitle`'s rule, so a note is addressed to the name the chat list
        // shows.
        val npub = "npub1abcdefghijklmnopqrstuvwxyz"
        assertEquals(
            "Ana",
            shareTargets(listOf(ShareCandidate(npub, "Ana", "anahandle", false)))[0].label,
        )
        assertEquals(
            "@anahandle",
            shareTargets(listOf(ShareCandidate(npub, null, "anahandle", false)))[0].label,
        )
        assertEquals(
            shortNpub(npub),
            shareTargets(listOf(ShareCandidate(npub, null, null, false)))[0].label,
        )
    }

    @Test
    fun nobodyIsInventedAndNobodyIsDropped() {
        // The picker is exactly the saved contacts — a stranger is not offered
        // here, and a contact is never quietly missing from a list you are
        // about to disclose to.
        assertTrue(shareTargets(emptyList()).isEmpty())
        val two = shareTargets(listOf(candidate("npub1a", "A"), candidate("npub1b", "B")))
        assertEquals(listOf("npub1a", "npub1b"), two.map { it.npub })
    }
}
