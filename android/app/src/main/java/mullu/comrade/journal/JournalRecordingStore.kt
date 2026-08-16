package mullu.comrade.journal

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * The journal's own folders on this device, and the moves in and out of them.
 *
 * ## Where the recordings live, and why there
 *
 * `filesDir/journal-videos` and `filesDir/journal-audio` — app-private
 * **internal** storage, a directory per kind, holding nothing but journal
 * recordings.
 *
 * That location is the whole answer to "it must not show up in the gallery",
 * and it is a stronger answer than the usual one. A `.nomedia` file asks the
 * media scanner to skip a folder it can otherwise see, and it is only ever as
 * good as the scanner's cooperation. Internal app storage is not part of any
 * shared media volume: `MediaStore` does not index it, no gallery can enumerate
 * it, and no other app on the device can open a file in it. So this writes no
 * marker file — there is nothing to ask. A voice entry gets the same treatment
 * and for the same reason does not turn up in a music player either.
 *
 * Recording still has to happen *somewhere* first. Video is filmed by the
 * camera app, which is another app and is handed one file at a time in
 * `cacheDir/journal-capture` through the existing `FileProvider`; audio is
 * recorded in this process by [mullu.comrade.media.VoiceRecorder] into the same
 * capture directory. Either way the finished file is moved into the journal's
 * own folder by [adopt], so the only thing another app is ever granted is one
 * file it is in the middle of writing, never the directory that holds someone's
 * history.
 *
 * ## What this does not promise
 *
 * The recording is **not** sealed by the vault passcode. The entry's title,
 * words and mood are — they are sealed values in the encrypted store like every
 * other journal entry — but the file is plain, protected by the app sandbox and
 * whatever full-disk encryption the device has. That is real, and it is a
 * weaker promise than the rest of the journal makes. It is written down as
 * AUDIT J-1 with the condition that would close it; until then no copy on this
 * screen may describe a recording as sealed or encrypted.
 *
 * Every call here touches the filesystem, and [adopt] additionally decodes
 * container metadata — run them off the main thread.
 */
object JournalRecordingStore {

    private const val TAG = "JournalRecordingStore"

    /** The journal's directory for [kind], created on first use. */
    fun dir(context: Context, kind: RecordingKind): File =
        File(context.filesDir, journalRecordingDir(kind)).apply { mkdirs() }

    /** Where a capture is written while something else still owns it. */
    fun captureDir(context: Context): File =
        File(context.cacheDir, JOURNAL_CAPTURE_DIR).apply { mkdirs() }

    /**
     * A fresh, empty path to record into.
     *
     * Named like a capture rather than like a recording ([isJournalRecordingFile]
     * says no to it, for either kind) so that a file left here by a crash can
     * never be mistaken for something the journal owns.
     */
    fun newCaptureFile(context: Context, kind: RecordingKind, nonce: Long): File {
        val extension = if (kind == RecordingKind.Audio) "m4a" else "mp4"
        return File(captureDir(context), "capture-$nonce.$extension")
    }

    /**
     * The file behind an entry's stored [fileName], or `null` if the name is
     * not one this app minted for [kind] or nothing is there.
     *
     * The name check is not paranoia about the store, which is sealed and
     * local — it is what stops a single corrupt or hand-edited record from
     * turning a path into a read outside these directories. `null` also covers
     * the ordinary case of a recording that is genuinely gone, which the
     * caller must draw as a missing clip rather than as a blank player.
     */
    fun fileFor(context: Context, kind: RecordingKind, fileName: String): File? {
        if (!isJournalRecordingFile(kind, fileName)) return null
        val file = File(dir(context, kind), fileName)
        return if (file.isFile && file.length() > 0L) file else null
    }

    /** [fileFor], reading the kind off the mime the entry stored. */
    fun fileFor(context: Context, mime: String, fileName: String): File? =
        fileFor(context, recordingKindOf(mime), fileName)

    /**
     * Move a finished capture into the journal folder and describe it.
     *
     * Returns `null` — having deleted the capture — when there is nothing worth
     * keeping: an empty file is what a camera app leaves behind when the user
     * backed out of it, and what a microphone that was never granted produces,
     * and saving an entry over zero bytes would put an unplayable card in
     * someone's journal.
     *
     * The file is in place before this returns, and the entry is written after
     * it. That order is deliberate: a crash in between leaves a file with no
     * entry, which [sweepOrphans] cleans up silently on the next open. The
     * other order would leave an entry with no file, which is a recording the
     * user is told they have and cannot play.
     */
    fun adopt(
        context: Context,
        kind: RecordingKind,
        captured: File,
        createdAtMs: Long,
        nonce: Long,
        knownDurationMs: Long = 0L,
    ): AdoptedRecording? {
        if (!captured.isFile || captured.length() == 0L) {
            captured.delete()
            return null
        }
        val target = File(dir(context, kind), journalRecordingFileName(kind, createdAtMs, nonce))
        try {
            // cacheDir and filesDir normally share a partition so the rename is
            // atomic; fall back to copy+delete rather than losing the recording
            // if this device puts them apart. Same shape as ModelInstaller.
            if (!captured.renameTo(target)) {
                captured.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                captured.delete()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not move the recording into the journal folder", e)
            // Leave neither half behind: a partial copy is an unplayable file
            // that the sweep would keep finding and the user could never see.
            target.delete()
            captured.delete()
            return null
        }
        // The container first, the stopwatch second. A probe of a real MPEG-4
        // reads the duration the encoder wrote, which is exact; [knownDurationMs]
        // is how long the caller held the recorder, which includes the moment
        // between the tap and the first captured frame. Preferring the probe
        // keeps the card's length and the player's total the same number, and
        // falling back to the stopwatch means a container that will not answer
        // costs a rounding error rather than the whole label.
        return AdoptedRecording(
            kind = kind,
            fileName = target.name,
            mime = journalRecordingMime(kind),
            durationMs = durationMs(target).takeIf { it > 0L } ?: knownDurationMs.coerceAtLeast(0L),
            sizeBytes = target.length(),
        )
    }

    /**
     * Delete one recording. Returns whether a file went away.
     *
     * The caller deletes the entry too; this half is the one the core cannot
     * do. Called after the record is gone, so that the failure mode is an
     * orphaned file (invisible, swept on next open) rather than an entry
     * pointing at nothing.
     */
    fun delete(context: Context, kind: RecordingKind, fileName: String): Boolean {
        if (!isJournalRecordingFile(kind, fileName)) return false
        return File(dir(context, kind), fileName).delete()
    }

    /** [delete], reading the kind off the mime the entry stored. */
    fun delete(context: Context, mime: String, fileName: String): Boolean =
        delete(context, recordingKindOf(mime), fileName)

    /**
     * Delete every recording, of either kind, that no journal entry refers to.
     * Returns how many went.
     *
     * See [orphanedRecordingFiles] for the rule and for the one thing a caller
     * must get right: [referenced] has to be a *complete* list of the journal's
     * recording file names — both kinds together — read after any capture in
     * flight has been saved. A partial list here deletes a recording someone
     * still has an entry for.
     */
    fun sweepOrphans(context: Context, referenced: Set<String>): Int {
        var removed = 0
        for (kind in RecordingKind.values()) {
            val folder = dir(context, kind)
            val onDisk = folder.list()?.toList() ?: continue
            for (orphan in orphanedRecordingFiles(kind, onDisk, referenced)) {
                if (File(folder, orphan).delete()) removed++
            }
        }
        if (removed > 0) Log.d(TAG, "Swept $removed orphaned journal recording(s)")
        return removed
    }

    /**
     * Drop captures older than [olderThanMs] that nothing is coming back for.
     *
     * A capture the user abandoned, or one interrupted by a kill, is plaintext
     * media sitting in the cache with no entry pointing at it — the same rule
     * voice notes follow (AUDIT S-4).
     *
     * Age-gated, and [isStaleCapture] is where the reasoning for that lives —
     * an ungated sweep deletes the file currently being recorded into.
     *
     * The case this does **not** rescue: a process killed mid-capture loses the
     * pending-capture state the result callback needs, so the returning
     * recording is dropped and lands here as an orphan. Cleaned up, not
     * recovered — the same limitation the chat composer's capture has.
     */
    fun discardStaleCaptures(context: Context, olderThanMs: Long = STALE_CAPTURE_MS) {
        val now = System.currentTimeMillis()
        captureDir(context).listFiles()?.forEach { file ->
            if (isStaleCapture(file.lastModified(), now, olderThanMs)) file.delete()
        }
    }

    /**
     * How long [file] runs, in milliseconds, or `0` when the container will not
     * say.
     *
     * Zero is a real answer here, not a failure to handle: a recording whose
     * metadata is unreadable still plays, and the card simply shows no length
     * ([formatClipLength] returns empty for it). Refusing to save the entry
     * over a missing duration would throw away the recording to protect a
     * label.
     */
    private fun durationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
        } catch (e: RuntimeException) {
            // setDataSource throws IllegalArgumentException on a container it
            // cannot parse, and IllegalStateException on a half-written one.
            // AAC/ADTS is a raw stream with no duration header, so this is the
            // *expected* path for a voice entry rather than an error — hence
            // debug, not warn, and hence a zero that the UI must render as
            // "unknown" rather than as an empty clip.
            Log.d(TAG, "Could not read the recording's duration", e)
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/** A recording that is now in the journal folder, as the entry will record it. */
data class AdoptedRecording(
    val kind: RecordingKind,
    val fileName: String,
    val mime: String,
    val durationMs: Long,
    val sizeBytes: Long,
)
