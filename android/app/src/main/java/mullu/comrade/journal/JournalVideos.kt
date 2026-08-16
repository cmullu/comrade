package mullu.comrade.journal

/**
 * Pure rules for the video journal — kept free of Android imports so plain JVM
 * unit tests (`JournalVideosTest`) can pin them, the same discipline
 * `together/TogetherDecisions.kt` keeps and for the same reason: this is the
 * half of the Android frontend that can be checked before CI.
 *
 * Everything that needs a `Context` — the directory itself, the move off the
 * camera's temp file, reading a clip's length — is [JournalVideoStore]'s. What
 * is here is naming, formatting and the sweep decision, which is where the
 * mistakes that lose someone's recording actually live.
 */

/**
 * The directory, under the app's **private internal** storage, that every
 * video journal recording lives in.
 *
 * Its own directory rather than a shared one because that is what makes
 * [orphanedVideoFiles] a single listing rather than a search: everything in
 * here is a journal recording, so anything in here the journal does not
 * reference is garbage, and nothing else's file can be caught by the sweep.
 *
 * **Why the phone's gallery never shows these.** Not a `.nomedia` marker, which
 * only asks the media scanner to skip a folder it can already see. `filesDir`
 * is app-private internal storage: it is not part of any shared media volume,
 * `MediaStore` does not index it, and no other app on the device can read it at
 * all. There is nothing for a gallery to find.
 *
 * What that does **not** mean: the file is not sealed by the vault passcode the
 * way an entry's words are. See `comrade_storage::JournalVideo` and AUDIT J-1.
 */
const val JOURNAL_VIDEO_DIR = "journal-videos"

/**
 * Where the camera app is pointed while it is still recording — a cache
 * directory, exposed to it through the existing `FileProvider`.
 *
 * Deliberately not [JOURNAL_VIDEO_DIR] itself. A capture that is cancelled or
 * that dies half-written would otherwise leave a broken file sitting in the
 * journal's own folder, and pointing another app's write at the directory that
 * holds every recording is a wider grant than this needs.
 */
const val JOURNAL_CAPTURE_DIR = "journal-capture"

/** The container every recording is written in. */
const val JOURNAL_VIDEO_MIME = "video/mp4"

/**
 * How long a capture file may sit in [JOURNAL_CAPTURE_DIR] before it is
 * presumed abandoned.
 *
 * Six hours: far longer than any journal entry anybody records, and far shorter
 * than "forever", which is what leaving them there would mean for plaintext
 * video in a cache (AUDIT S-4). The size of this number is the whole safety
 * argument for [isStaleCapture] — see there.
 */
const val STALE_CAPTURE_MS = 6L * 60 * 60 * 1000

/**
 * Whether a capture file is old enough that nothing can still be writing it.
 *
 * The obvious sweep — empty the capture directory whenever the journal opens —
 * deletes the file the camera app is *at that moment recording into*, in the
 * one case where the journal composes while a capture is in flight: the process
 * was killed for memory while the camera was in front and has just been
 * recreated. The recording then fails, or comes back as zero bytes, and reads
 * as a broken camera rather than as a sweep.
 *
 * An age gate makes that case impossible instead of unlikely, which is why the
 * comparison is strict and why [STALE_CAPTURE_MS] is measured in hours: nothing
 * shorter can be argued to be safe, and nothing longer buys anything.
 */
fun isStaleCapture(lastModifiedMs: Long, nowMs: Long, olderThanMs: Long = STALE_CAPTURE_MS): Boolean =
    lastModifiedMs < nowMs - olderThanMs

private const val JOURNAL_VIDEO_PREFIX = "jv-"
private const val JOURNAL_VIDEO_EXTENSION = ".mp4"

/**
 * How long a title may be. Long enough for a sentence someone actually types
 * to survive whole, short enough that a paste of a whole paragraph does not
 * become a heading that fills the card — that is what the entry's text is for.
 */
const val JOURNAL_TITLE_MAX_CHARS = 80

/**
 * The name a new recording is filed under: `jv-<millis>-<nonce>.mp4`.
 *
 * Timestamp first so an `ls` of the directory reads chronologically, which is
 * what makes an orphan obvious when someone is looking at one. The nonce is
 * what actually keeps names apart — two recordings started in the same
 * millisecond is not a case worth losing a file over — and it is rendered in
 * hex, unsigned, so a negative [nonce] cannot put a `-` in the middle of a
 * name that [isJournalVideoFile] then has to reason about.
 *
 * Deliberately **not** derived from the title. A file named after what someone
 * called their entry would put those words in a filename, where they are not
 * encrypted and where a crash report or a backup tool might carry them off;
 * the title belongs in the sealed store and nowhere else.
 */
fun journalVideoFileName(createdAtMs: Long, nonce: Long): String =
    JOURNAL_VIDEO_PREFIX + createdAtMs + "-" + java.lang.Long.toHexString(nonce) +
        JOURNAL_VIDEO_EXTENSION

/**
 * Whether [name] is one of this app's own recordings.
 *
 * The gate on [orphanedVideoFiles], so it is deliberately strict: a name it
 * does not recognise is left alone rather than deleted. A directory that only
 * ever holds journal videos should make that unreachable, and a sweep that
 * trusts "should" is how a user loses a file to a bug somewhere else.
 */
fun isJournalVideoFile(name: String): Boolean =
    name.startsWith(JOURNAL_VIDEO_PREFIX) &&
        name.endsWith(JOURNAL_VIDEO_EXTENSION) &&
        name.length > JOURNAL_VIDEO_PREFIX.length + JOURNAL_VIDEO_EXTENSION.length &&
        !name.contains('/') &&
        !name.contains('\\')

/**
 * A title as it should be stored, or `null` for "no title".
 *
 * Blank is `null` rather than `""`, so "has a title" is one question with one
 * answer everywhere. Newlines collapse to spaces because a heading is one line
 * by construction and a pasted paragraph would otherwise silently become a
 * multi-line one. Truncation is a hard cut with no ellipsis: the store keeps
 * what it was given, and a "…" written into the data would come back as part
 * of the title the next time it is edited.
 */
fun journalVideoTitle(raw: String?): String? {
    val collapsed = raw
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?: return null
    if (collapsed.isEmpty()) return null
    return collapsed.take(JOURNAL_TITLE_MAX_CHARS).trimEnd()
}

/**
 * What a recording's card is headed with when the user never named it.
 *
 * A recording always has *something* to be called — a card headed with nothing
 * is a card that looks broken — and the date it was taken is the one fact
 * about it that is always true and never a guess. [dayLabel] is the caller's,
 * so the heading matches the day heading the entry already sits under rather
 * than inventing a second date format on the same screen.
 */
fun journalVideoHeading(title: String?, dayLabel: String): String =
    journalVideoTitle(title) ?: dayLabel

/**
 * A clip's length as `m:ss`, or `h:mm:ss` past an hour.
 *
 * Empty string when [durationMs] is zero or negative, which is how the core
 * spells "the frontend could not read one". The caller must draw nothing at
 * all in that case: `0:00` would claim the recording is empty, which is a
 * different and wrong statement about someone's file.
 */
fun formatClipLength(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSeconds = durationMs / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "$hours:" + minutes.toString().padStart(2, '0') + ":" + seconds.toString().padStart(2, '0')
    } else {
        "$minutes:" + seconds.toString().padStart(2, '0')
    }
}

/**
 * A file size for the card's second line — `840 KB`, `12.3 MB`, `1.1 GB`.
 *
 * Powers of 1024 with the customary labels, matching what a file manager on the
 * same phone will say about the same file. One decimal place from MB up, none
 * below: the difference between 840 KB and 840.3 KB is not information anyone
 * needs, while 12 MB versus 12.3 MB is the difference between two recordings.
 *
 * Empty string for a non-positive size, for the same reason as
 * [formatClipLength]: an unknown is not a zero.
 */
fun formatClipSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> trimDecimal(bytes / gb) + " GB"
        bytes >= mb -> trimDecimal(bytes / mb) + " MB"
        bytes >= kb -> Math.round(bytes / kb).toString() + " KB"
        else -> "$bytes B"
    }
}

/** One decimal place, with a trailing `.0` dropped — `12.3`, but `12` not `12.0`. */
private fun trimDecimal(value: Double): String {
    val rounded = Math.round(value * 10.0) / 10.0
    return if (rounded == Math.floor(rounded)) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}

/**
 * The files in the journal-video directory that no entry refers to any more.
 *
 * Deleting an entry is two writes that cannot be made one: the core removes the
 * sealed record and the frontend removes the file. Anything that interrupts the
 * app between them — a kill, a crash, a vault that locked — leaves footage on
 * disk that the user has no way to see and therefore no way to delete. This is
 * what finds it on the next open.
 *
 * The bias is entirely towards keeping files. Only names this module itself
 * minted are candidates ([isJournalVideoFile]), and a *newly captured* file is
 * not yet referenced by anything, so the caller must run the sweep when no
 * capture is in flight — the fresh recording would otherwise be exactly the
 * shape of an orphan.
 */
fun orphanedVideoFiles(onDisk: List<String>, referenced: Set<String>): List<String> =
    onDisk.filter { isJournalVideoFile(it) && it !in referenced }
