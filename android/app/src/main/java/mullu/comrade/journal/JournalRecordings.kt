package mullu.comrade.journal

/**
 * Pure rules for journal recordings — audio and video alike — kept free of
 * Android imports so plain JVM unit tests (`JournalRecordingsTest`) can pin
 * them, the same discipline `together/TogetherDecisions.kt` keeps and for the
 * same reason: this is the half of the Android frontend that can be checked
 * before CI.
 *
 * Everything needing a `Context` — the directories, the move off a capture
 * file, reading a clip's length — is [JournalRecordingStore]'s. What is here is
 * naming, formatting, playback arithmetic and the sweep decision, which is
 * where the mistakes that lose someone's recording actually live.
 *
 * **Audio and video are one concept here on purpose.** They differ in exactly
 * three places — which directory, which extension, which mime — and are
 * identical in every rule that matters (how a file is named, what may be swept,
 * how a length reads). Two parallel copies would have drifted the first time
 * one of those rules changed.
 */

/**
 * What kind of recording an entry carries.
 *
 * The split is not cosmetic: video is *watched*, so it opens full screen from a
 * poster, and audio is *listened to*, so it plays in place with a scrubber. A
 * full-screen black rectangle with a slider on it is what pretending they are
 * the same medium would produce.
 */
enum class RecordingKind { Audio, Video }

/**
 * The directories, under the app's **private internal** storage, that journal
 * recordings live in — one per kind, holding nothing else.
 *
 * A directory of its own per kind is what makes [orphanedRecordingFiles] a
 * single listing rather than a search: everything in one is a journal recording
 * of that kind, so anything in it the journal does not reference is garbage,
 * and nothing else's file can be caught by the sweep.
 *
 * **Two directories rather than one shared `journal-recordings/`,** which would
 * read more tidily. Renaming the video directory would strand every recording
 * already on a phone from the version that shipped with `journal-videos`, and a
 * migration that moves someone's journal is a worse thing to get wrong than a
 * folder name is to leave imperfect.
 *
 * **Why the phone's gallery never shows these.** Not a `.nomedia` marker, which
 * only asks the media scanner to skip a folder it can already see. `filesDir`
 * is app-private internal storage: it is not part of any shared media volume,
 * `MediaStore` does not index it, and no other app on the device can read it at
 * all. There is nothing for a gallery to find. The same is true of the audio
 * directory, which is why a voice entry does not turn up in a music player
 * either.
 *
 * What that does **not** mean: the file is not sealed by the vault passcode the
 * way an entry's words are. See `comrade_storage::JournalRecording`, AUDIT J-1.
 */
const val JOURNAL_VIDEO_DIR = "journal-videos"

/** @see JOURNAL_VIDEO_DIR — the same guarantees, for voice entries. */
const val JOURNAL_AUDIO_DIR = "journal-audio"

/**
 * Where a capture is written while something else still owns it — the camera
 * app for video, this app's own [android.media.MediaRecorder] for audio.
 *
 * Deliberately neither recording directory. A capture that is cancelled or that
 * dies half-written would otherwise leave a broken file sitting among the real
 * ones, and pointing the camera app's write at the directory that holds every
 * recording is a wider grant than this needs.
 */
const val JOURNAL_CAPTURE_DIR = "journal-capture"

/** The container a video entry is written in. */
const val JOURNAL_VIDEO_MIME = "video/mp4"

/**
 * The container a voice entry is written in: AAC inside MPEG-4, an `.m4a`.
 *
 * **Deliberately not the ADTS stream chat voice notes use.** Both are AAC and
 * `media/VoiceRecorder` produces either, but the two containers answer
 * different questions. ADTS is a raw elementary stream with no header saying
 * how long it is, so `MediaMetadataRetriever` returns nothing for it and
 * `seekTo` has to guess by extrapolating a bitrate. That is a fine trade for a
 * chat clip — recorded, sent, played once, never scrubbed.
 *
 * A journal entry is the opposite: it is kept, listed with its length beside
 * it, and scrubbed back and forth. MPEG-4 carries a real duration and a seek
 * index, which is what makes the card able to say `1:12` and the scrubber able
 * to land where it was dragged. Getting this wrong shows up as a voice entry
 * with no length and a slider that jumps.
 */
const val JOURNAL_AUDIO_MIME = "audio/mp4"

/** Where recordings of [kind] are kept. */
fun journalRecordingDir(kind: RecordingKind): String = when (kind) {
    RecordingKind.Audio -> JOURNAL_AUDIO_DIR
    RecordingKind.Video -> JOURNAL_VIDEO_DIR
}

/** The mime a new recording of [kind] is saved under. */
fun journalRecordingMime(kind: RecordingKind): String = when (kind) {
    RecordingKind.Audio -> JOURNAL_AUDIO_MIME
    RecordingKind.Video -> JOURNAL_VIDEO_MIME
}

/**
 * Which kind a stored recording is, read from the mime the entry carries.
 *
 * The mime is the single source of truth rather than a second stored enum that
 * could disagree with it. Anything not announcing itself as audio is treated as
 * video, which is the safe way round: a video played by the audio bar would
 * lose the picture silently, while an audio file opened in the video player
 * shows a black screen that still plays and still says how long it is.
 */
fun recordingKindOf(mime: String): RecordingKind =
    if (mime.startsWith("audio/")) RecordingKind.Audio else RecordingKind.Video

/**
 * How long a capture file may sit in [JOURNAL_CAPTURE_DIR] before it is
 * presumed abandoned.
 *
 * Six hours: far longer than any journal entry anybody records, and far shorter
 * than "forever", which is what leaving them there would mean for plaintext
 * media in a cache (AUDIT S-4). The size of this number is the whole safety
 * argument for [isStaleCapture] — see there.
 */
const val STALE_CAPTURE_MS = 6L * 60 * 60 * 1000

/**
 * Whether a capture file is old enough that nothing can still be writing it.
 *
 * The obvious sweep — empty the capture directory whenever the journal opens —
 * deletes the file that is *at that moment being recorded into*, in the one
 * case where the journal composes while a capture is in flight: the process was
 * killed for memory while the camera was in front and has just been recreated.
 * The recording then fails, or comes back as zero bytes, and reads as a broken
 * camera rather than as a sweep.
 *
 * An age gate makes that case impossible instead of unlikely, which is why the
 * comparison is strict and why [STALE_CAPTURE_MS] is measured in hours: nothing
 * shorter can be argued to be safe, and nothing longer buys anything.
 */
fun isStaleCapture(lastModifiedMs: Long, nowMs: Long, olderThanMs: Long = STALE_CAPTURE_MS): Boolean =
    lastModifiedMs < nowMs - olderThanMs

private fun prefixFor(kind: RecordingKind) = when (kind) {
    RecordingKind.Audio -> "ja-"
    RecordingKind.Video -> "jv-"
}

private fun extensionFor(kind: RecordingKind) = when (kind) {
    RecordingKind.Audio -> ".m4a"
    RecordingKind.Video -> ".mp4"
}

/**
 * How long a title may be. Long enough for a sentence someone actually types
 * to survive whole, short enough that a paste of a whole paragraph does not
 * become a heading that fills the card — that is what the entry's text is for.
 */
const val JOURNAL_TITLE_MAX_CHARS = 80

/**
 * The name a new recording is filed under: `jv-<millis>-<nonce>.mp4` for video,
 * `ja-<millis>-<nonce>.m4a` for audio.
 *
 * Timestamp first so an `ls` of the directory reads chronologically, which is
 * what makes an orphan obvious when someone is looking at one. The nonce is
 * what actually keeps names apart — two recordings started in the same
 * millisecond is not a case worth losing a file over — and it is rendered in
 * hex, unsigned, so a negative [nonce] cannot put a `-` in the middle of a name
 * that [isJournalRecordingFile] then has to reason about.
 *
 * The prefix differs by kind even though the two live in different directories.
 * That redundancy is the point: it is what stops the audio sweep from ever
 * deleting a video, whatever else goes wrong.
 *
 * Deliberately **not** derived from the title. A file named after what someone
 * called their entry would put those words in a filename, where they are not
 * encrypted and where a crash report or a backup tool might carry them off;
 * the title belongs in the sealed store and nowhere else.
 */
fun journalRecordingFileName(kind: RecordingKind, createdAtMs: Long, nonce: Long): String =
    prefixFor(kind) + createdAtMs + "-" + java.lang.Long.toHexString(nonce) + extensionFor(kind)

/**
 * Whether [name] is one of this app's own recordings of [kind].
 *
 * The gate on [orphanedRecordingFiles], so it is deliberately strict: a name it
 * does not recognise is left alone rather than deleted. A directory that only
 * ever holds journal recordings of one kind should make that unreachable, and a
 * sweep that trusts "should" is how a user loses a file to a bug somewhere else.
 */
fun isJournalRecordingFile(kind: RecordingKind, name: String): Boolean {
    val prefix = prefixFor(kind)
    val extension = extensionFor(kind)
    return name.startsWith(prefix) &&
        name.endsWith(extension) &&
        name.length > prefix.length + extension.length &&
        !name.contains('/') &&
        !name.contains('\\')
}

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
fun journalRecordingTitle(raw: String?): String? {
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
fun journalRecordingHeading(title: String?, dayLabel: String): String =
    journalRecordingTitle(title) ?: dayLabel

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
 * How far through a clip playback is, as `0f..1f` for a progress bar.
 *
 * Zero for an unknown or absent duration rather than a divide by zero, and
 * clamped at both ends: `MediaPlayer.getCurrentPosition` can report a position
 * past the duration it reported earlier, and a slider handed 1.04 throws rather
 * than pinning itself to the end.
 */
fun clipProgress(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * The counter under a player: `0:07 / 0:23`, or just `0:07` when the total is
 * unknown.
 *
 * Dropping the total rather than showing `0:07 / 0:00` is the same rule
 * [formatClipLength] follows — a clip whose container will not say how long it
 * is still plays, and a zero total would say it has already ended.
 *
 * The position is clamped to the duration, because a player that reports 0:24
 * of a 0:23 clip is a player the user reads as broken.
 */
fun playbackLabel(positionMs: Long, durationMs: Long): String {
    val safePosition = positionMs.coerceAtLeast(0L)
    if (durationMs <= 0L) return formatClipLength(safePosition).ifEmpty { "0:00" }
    val shown = safePosition.coerceAtMost(durationMs)
    val elapsed = if (shown <= 0L) "0:00" else formatClipLength(shown)
    return elapsed + " / " + formatClipLength(durationMs)
}

/**
 * The files in one recording directory that no entry refers to any more.
 *
 * Deleting an entry is two writes that cannot be made one: the core removes the
 * sealed record and the frontend removes the file. Anything that interrupts the
 * app between them — a kill, a crash, a vault that locked — leaves a recording
 * on disk that the user has no way to see and therefore no way to delete. This
 * is what finds it on the next open.
 *
 * The bias is entirely towards keeping files. Only names this module itself
 * minted for this [kind] are candidates ([isJournalRecordingFile]), and a
 * *newly captured* file is not yet referenced by anything, so the caller must
 * run the sweep when no capture is in flight — the fresh recording would
 * otherwise be exactly the shape of an orphan.
 *
 * [referenced] must name every recording the journal holds, of **both** kinds.
 * Passing only this kind's names would still be correct, since the other kind's
 * names cannot pass [isJournalRecordingFile] here — but a caller reading one
 * list of file names off the entries and passing it to both sweeps is the
 * obvious way to use this, and it has to be the safe one.
 */
fun orphanedRecordingFiles(
    kind: RecordingKind,
    onDisk: List<String>,
    referenced: Set<String>,
): List<String> = onDisk.filter { isJournalRecordingFile(kind, it) && it !in referenced }
