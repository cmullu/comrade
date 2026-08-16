package mullu.comrade.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the video journal's framework-free rules: what a recording is called on
 * disk, what its card is headed with, how its length and size read, and which
 * files the orphan sweep is allowed to delete.
 *
 * The last of those is the one that matters most — it is the only code in this
 * feature that deletes a user's recording, and it runs unattended on every
 * open of the Journal tab.
 */
class JournalVideosTest {

    // ── Naming ──────────────────────────────────────────────────────────────

    @Test
    fun aMintedNameSortsByTimeAndIsRecognisedAsOurs() {
        val name = journalVideoFileName(1_723_800_000_000L, 0x2f1aL)
        assertEquals("jv-1723800000000-2f1a.mp4", name)
        assertTrue(isJournalVideoFile(name))
    }

    @Test
    fun twoRecordingsInTheSameMillisecondGetDifferentNames() {
        val at = 1_723_800_000_000L
        assertFalse(journalVideoFileName(at, 1L) == journalVideoFileName(at, 2L))
    }

    @Test
    fun aNegativeNonceStillMintsOneCleanName() {
        // Long.toHexString is unsigned, so a negative nonce cannot smuggle a
        // '-' into the middle of the name.
        val name = journalVideoFileName(1L, -1L)
        assertEquals("jv-1-ffffffffffffffff.mp4", name)
        assertTrue(isJournalVideoFile(name))
        assertEquals(2, name.count { it == '-' })
    }

    @Test
    fun nothingElseIsMistakenForARecording() {
        for (stranger in listOf(
            "",
            "jv-.mp4",
            ".mp4",
            "jv-123",
            "holiday.mp4",
            "vault.redb",
            "jv-123-a.mp4.tmp",
            "../jv-123-a.mp4",
            "sub/jv-123-a.mp4",
            "sub\\jv-123-a.mp4",
        )) {
            assertFalse(stranger, isJournalVideoFile(stranger))
        }
    }

    // ── Titles ──────────────────────────────────────────────────────────────

    @Test
    fun aTitleIsTrimmedAndBlankMeansNone() {
        assertEquals("Sunday morning", journalVideoTitle("  Sunday morning  "))
        assertNull(journalVideoTitle(null))
        assertNull(journalVideoTitle(""))
        assertNull(journalVideoTitle("   \n\t "))
    }

    @Test
    fun aPastedParagraphBecomesOneLineAndStopsAtTheCap() {
        val pasted = "the walk\nafter   the\r\nargument"
        assertEquals("the walk after the argument", journalVideoTitle(pasted))

        val long = "x".repeat(JOURNAL_TITLE_MAX_CHARS + 40)
        val title = journalVideoTitle(long)!!
        assertEquals(JOURNAL_TITLE_MAX_CHARS, title.length)
        // A hard cut, with no "…" written into the data — an ellipsis stored
        // here would come back as part of the title next time it is edited.
        assertFalse(title.endsWith("…"))
    }

    @Test
    fun anUnnamedRecordingIsHeadedWithTheDayItWasTaken() {
        assertEquals("Yesterday", journalVideoHeading(null, "Yesterday"))
        assertEquals("Yesterday", journalVideoHeading("   ", "Yesterday"))
        assertEquals("Sunday morning", journalVideoHeading("Sunday morning", "Yesterday"))
    }

    // ── Length and size ─────────────────────────────────────────────────────

    @Test
    fun clipLengthReadsAsAPlayerWouldShowIt() {
        assertEquals("0:07", formatClipLength(7_400))
        assertEquals("0:47", formatClipLength(47_000))
        assertEquals("1:03", formatClipLength(63_000))
        assertEquals("10:00", formatClipLength(600_000))
        assertEquals("1:00:00", formatClipLength(3_600_000))
        assertEquals("2:05:09", formatClipLength(7_509_000))
    }

    @Test
    fun anUnknownLengthDrawsNothingRatherThanClaimingAnEmptyClip() {
        assertEquals("", formatClipLength(0))
        assertEquals("", formatClipLength(-1))
    }

    @Test
    fun sizeReadsTheWayAFileManagerOnTheSamePhoneWould() {
        assertEquals("512 B", formatClipSize(512))
        assertEquals("840 KB", formatClipSize(860_160))
        assertEquals("12.3 MB", formatClipSize(12_897_485))
        assertEquals("12 MB", formatClipSize(12_582_912))
        assertEquals("1.1 GB", formatClipSize(1_181_116_006))
        assertEquals("", formatClipSize(0))
    }

    // ── The sweep ───────────────────────────────────────────────────────────

    @Test
    fun aFileNoEntryPointsAtIsAnOrphan() {
        val onDisk = listOf("jv-1-a.mp4", "jv-2-b.mp4", "jv-3-c.mp4")
        assertEquals(
            listOf("jv-2-b.mp4"),
            orphanedVideoFiles(onDisk, setOf("jv-1-a.mp4", "jv-3-c.mp4")),
        )
    }

    @Test
    fun theSweepNeverTouchesAFileItDidNotMint() {
        // The bias is entirely towards keeping files: a name this module did
        // not create is left alone even though nothing references it.
        val onDisk = listOf("holiday.mp4", "notes.txt", ".nomedia", "jv-9-z.mp4")
        assertEquals(listOf("jv-9-z.mp4"), orphanedVideoFiles(onDisk, emptySet()))
    }

    @Test
    fun aCaptureStillBeingRecordedIsNeverStale() {
        // The bug this rule exists to prevent: an ungated sweep of the capture
        // directory deletes the file the camera app is writing into, whenever
        // the journal composes while a capture is in flight (a low-memory kill
        // with the camera in front). Anything recent is off limits.
        val now = 1_723_800_000_000L
        assertFalse(isStaleCapture(lastModifiedMs = now, nowMs = now))
        assertFalse(isStaleCapture(lastModifiedMs = now - 60_000, nowMs = now))
        // …including a genuinely long recording.
        assertFalse(isStaleCapture(lastModifiedMs = now - 30 * 60_000, nowMs = now))
        // A file from a previous session is fair game.
        assertTrue(isStaleCapture(lastModifiedMs = now - STALE_CAPTURE_MS - 1, nowMs = now))
        // The threshold is measured in hours, not minutes: a shorter one is
        // the bug above wearing a smaller number.
        assertTrue(STALE_CAPTURE_MS >= 60L * 60 * 1000)
    }

    @Test
    fun anIntactJournalSweepsNothing() {
        val onDisk = listOf("jv-1-a.mp4", "jv-2-b.mp4")
        assertTrue(orphanedVideoFiles(onDisk, onDisk.toSet()).isEmpty())
        // …and an entry whose file is already gone is not an error here: the
        // sweep answers about disk, not about the journal.
        assertTrue(orphanedVideoFiles(emptyList(), setOf("jv-7-q.mp4")).isEmpty())
    }
}
