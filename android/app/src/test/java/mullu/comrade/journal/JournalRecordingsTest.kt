package mullu.comrade.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the journal's framework-free rules for both kinds of recording: what one
 * is called on disk, what its card is headed with, how its length, size and
 * playback position read, and which files a sweep is allowed to delete.
 *
 * The sweep is the one that matters most — it is the only code in this feature
 * that deletes a user's recording, and it runs unattended on every open of the
 * Journal tab. It is asserted per kind *and* across kinds, because two
 * directories mean two sweeps and the interesting failure is one deleting the
 * other's files.
 */
class JournalRecordingsTest {

    // ── Kinds ───────────────────────────────────────────────────────────────

    @Test
    fun theStoredMimeSaysWhichKindARecordingIs() {
        assertEquals(RecordingKind.Audio, recordingKindOf(JOURNAL_AUDIO_MIME))
        assertEquals(RecordingKind.Video, recordingKindOf(JOURNAL_VIDEO_MIME))
        assertEquals(RecordingKind.Audio, recordingKindOf("audio/aac"))
        assertEquals(RecordingKind.Video, recordingKindOf("video/webm"))
        // Anything that does not announce itself as audio is treated as video:
        // a black screen that still plays beats losing the picture in silence.
        assertEquals(RecordingKind.Video, recordingKindOf(""))
        assertEquals(RecordingKind.Video, recordingKindOf("application/octet-stream"))
    }

    @Test
    fun eachKindHasItsOwnFolderAndContainer() {
        assertEquals(JOURNAL_AUDIO_DIR, journalRecordingDir(RecordingKind.Audio))
        assertEquals(JOURNAL_VIDEO_DIR, journalRecordingDir(RecordingKind.Video))
        assertEquals(JOURNAL_AUDIO_MIME, journalRecordingMime(RecordingKind.Audio))
        assertEquals(JOURNAL_VIDEO_MIME, journalRecordingMime(RecordingKind.Video))
        // Neither is the capture directory another process writes into.
        assertFalse(JOURNAL_AUDIO_DIR == JOURNAL_CAPTURE_DIR)
        assertFalse(JOURNAL_VIDEO_DIR == JOURNAL_CAPTURE_DIR)
        assertFalse(JOURNAL_AUDIO_DIR == JOURNAL_VIDEO_DIR)
    }

    // ── Naming ──────────────────────────────────────────────────────────────

    @Test
    fun aMintedNameSortsByTimeAndIsRecognisedAsOurs() {
        val video = journalRecordingFileName(RecordingKind.Video, 1_723_800_000_000L, 0x2f1aL)
        assertEquals("jv-1723800000000-2f1a.mp4", video)
        assertTrue(isJournalRecordingFile(RecordingKind.Video, video))

        val audio = journalRecordingFileName(RecordingKind.Audio, 1_723_800_000_000L, 0x2f1aL)
        assertEquals("ja-1723800000000-2f1a.m4a", audio)
        assertTrue(isJournalRecordingFile(RecordingKind.Audio, audio))
    }

    @Test
    fun neitherKindClaimsTheOthersFiles() {
        // The redundant prefix is what makes this true even though the two
        // kinds already live in separate directories.
        val video = journalRecordingFileName(RecordingKind.Video, 1L, 2L)
        val audio = journalRecordingFileName(RecordingKind.Audio, 1L, 2L)
        assertFalse(isJournalRecordingFile(RecordingKind.Audio, video))
        assertFalse(isJournalRecordingFile(RecordingKind.Video, audio))
    }

    @Test
    fun twoRecordingsInTheSameMillisecondGetDifferentNames() {
        val at = 1_723_800_000_000L
        for (kind in RecordingKind.values()) {
            assertFalse(
                journalRecordingFileName(kind, at, 1L) == journalRecordingFileName(kind, at, 2L),
            )
        }
    }

    @Test
    fun aNegativeNonceStillMintsOneCleanName() {
        // Long.toHexString is unsigned, so a negative nonce cannot smuggle a
        // '-' into the middle of the name.
        val name = journalRecordingFileName(RecordingKind.Video, 1L, -1L)
        assertEquals("jv-1-ffffffffffffffff.mp4", name)
        assertTrue(isJournalRecordingFile(RecordingKind.Video, name))
        assertEquals(2, name.count { it == '-' })
    }

    @Test
    fun nothingElseIsMistakenForARecording() {
        for (stranger in listOf(
            "",
            "jv-.mp4",
            "ja-.m4a",
            ".mp4",
            "jv-123",
            "holiday.mp4",
            "vault.redb",
            "jv-123-a.mp4.tmp",
            "../jv-123-a.mp4",
            "sub/jv-123-a.mp4",
            "sub\\jv-123-a.mp4",
        )) {
            for (kind in RecordingKind.values()) {
                assertFalse("$kind / $stranger", isJournalRecordingFile(kind, stranger))
            }
        }
    }

    // ── Titles ──────────────────────────────────────────────────────────────

    @Test
    fun aTitleIsTrimmedAndBlankMeansNone() {
        assertEquals("Sunday morning", journalRecordingTitle("  Sunday morning  "))
        assertNull(journalRecordingTitle(null))
        assertNull(journalRecordingTitle(""))
        assertNull(journalRecordingTitle("   \n\t "))
    }

    @Test
    fun aPastedParagraphBecomesOneLineAndStopsAtTheCap() {
        val pasted = "the walk\nafter   the\r\nargument"
        assertEquals("the walk after the argument", journalRecordingTitle(pasted))

        val long = "x".repeat(JOURNAL_TITLE_MAX_CHARS + 40)
        val title = journalRecordingTitle(long)!!
        assertEquals(JOURNAL_TITLE_MAX_CHARS, title.length)
        // A hard cut, with no "…" written into the data — an ellipsis stored
        // here would come back as part of the title next time it is edited.
        assertFalse(title.endsWith("…"))
    }

    @Test
    fun anUnnamedRecordingIsHeadedWithTheDayItWasTaken() {
        assertEquals("Yesterday", journalRecordingHeading(null, "Yesterday"))
        assertEquals("Yesterday", journalRecordingHeading("   ", "Yesterday"))
        assertEquals("Sunday morning", journalRecordingHeading("Sunday morning", "Yesterday"))
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

    // ── Playback ────────────────────────────────────────────────────────────

    @Test
    fun theCounterUnderThePlayerReadsElapsedOverTotal() {
        assertEquals("0:00 / 0:23", playbackLabel(0, 23_000))
        assertEquals("0:07 / 0:23", playbackLabel(7_200, 23_000))
        assertEquals("0:23 / 0:23", playbackLabel(23_000, 23_000))
    }

    @Test
    fun thePlayerNeverClaimsToBePastTheEnd() {
        // MediaPlayer can report a position beyond the duration it gave
        // earlier; 0:24 of a 0:23 clip reads as a broken player.
        assertEquals("0:23 / 0:23", playbackLabel(24_000, 23_000))
        assertEquals("0:00 / 0:23", playbackLabel(-500, 23_000))
    }

    @Test
    fun anUnknownTotalShowsTheElapsedTimeAlone() {
        // Dropping the total beats "0:07 / 0:00", which says the clip ended.
        assertEquals("0:07", playbackLabel(7_200, 0))
        assertEquals("0:00", playbackLabel(0, 0))
        assertEquals("0:00", playbackLabel(-1, -1))
    }

    @Test
    fun progressIsAFractionAndNeverOutOfRange() {
        assertEquals(0f, clipProgress(0, 23_000), 0.0001f)
        assertEquals(0.5f, clipProgress(11_500, 23_000), 0.0001f)
        assertEquals(1f, clipProgress(23_000, 23_000), 0.0001f)
        // A slider handed 1.04 throws; pin it to the end instead.
        assertEquals(1f, clipProgress(24_000, 23_000), 0.0001f)
        assertEquals(0f, clipProgress(-500, 23_000), 0.0001f)
        // No divide by zero when the container would not say how long it is.
        assertEquals(0f, clipProgress(7_000, 0), 0.0001f)
        assertEquals(0f, clipProgress(7_000, -1), 0.0001f)
    }

    // ── The sweep ───────────────────────────────────────────────────────────

    @Test
    fun aCaptureStillBeingRecordedIsNeverStale() {
        // The bug this rule exists to prevent: an ungated sweep of the capture
        // directory deletes the file being written into, whenever the journal
        // composes while a capture is in flight (a low-memory kill with the
        // camera in front). Anything recent is off limits.
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
    fun aFileNoEntryPointsAtIsAnOrphan() {
        val onDisk = listOf("jv-1-a.mp4", "jv-2-b.mp4", "jv-3-c.mp4")
        assertEquals(
            listOf("jv-2-b.mp4"),
            orphanedRecordingFiles(
                RecordingKind.Video,
                onDisk,
                setOf("jv-1-a.mp4", "jv-3-c.mp4"),
            ),
        )
        val audioOnDisk = listOf("ja-1-a.m4a", "ja-2-b.m4a")
        assertEquals(
            listOf("ja-2-b.m4a"),
            orphanedRecordingFiles(RecordingKind.Audio, audioOnDisk, setOf("ja-1-a.m4a")),
        )
    }

    @Test
    fun oneKindsSweepNeverDeletesTheOthersRecordings() {
        // Callers read one set of file names off the entries and hand it to
        // both sweeps. If either sweep considered the other's names, an audio
        // entry would make every unreferenced video look like a keeper — or
        // worse, the reverse.
        val everything = listOf("jv-1-a.mp4", "ja-1-a.m4a", "jv-2-b.mp4", "ja-2-b.m4a")
        assertEquals(
            listOf("jv-1-a.mp4", "jv-2-b.mp4"),
            orphanedRecordingFiles(RecordingKind.Video, everything, emptySet()),
        )
        assertEquals(
            listOf("ja-1-a.m4a", "ja-2-b.m4a"),
            orphanedRecordingFiles(RecordingKind.Audio, everything, emptySet()),
        )
    }

    @Test
    fun theSweepNeverTouchesAFileItDidNotMint() {
        // The bias is entirely towards keeping files: a name this module did
        // not create is left alone even though nothing references it.
        val onDisk = listOf("holiday.mp4", "notes.txt", ".nomedia", "jv-9-z.mp4")
        assertEquals(
            listOf("jv-9-z.mp4"),
            orphanedRecordingFiles(RecordingKind.Video, onDisk, emptySet()),
        )
    }

    @Test
    fun anIntactJournalSweepsNothing() {
        val onDisk = listOf("jv-1-a.mp4", "jv-2-b.mp4")
        assertTrue(
            orphanedRecordingFiles(RecordingKind.Video, onDisk, onDisk.toSet()).isEmpty(),
        )
        // …and an entry whose file is already gone is not an error here: the
        // sweep answers about disk, not about the journal.
        assertTrue(
            orphanedRecordingFiles(RecordingKind.Video, emptyList(), setOf("jv-7-q.mp4")).isEmpty(),
        )
    }
}
