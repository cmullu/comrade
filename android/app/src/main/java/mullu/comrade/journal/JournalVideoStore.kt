package mullu.comrade.journal

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * The video journal's own folder on this device, and the moves in and out of
 * it.
 *
 * ## Where the recordings live, and why there
 *
 * `filesDir/journal-videos` — app-private **internal** storage, one directory
 * of its own, holding nothing but journal recordings.
 *
 * That location is the whole answer to "it must not show up in the gallery",
 * and it is a stronger answer than the usual one. A `.nomedia` file asks the
 * media scanner to skip a folder it can otherwise see, and it is only ever as
 * good as the scanner's cooperation. Internal app storage is not part of any
 * shared media volume at all: `MediaStore` does not index it, no gallery can
 * enumerate it, and no other app on the device can open a file in it. So this
 * writes no marker file — there is nothing to ask.
 *
 * The camera still has to be able to write *somewhere*, and it is another app.
 * It is pointed at `cacheDir/journal-capture` through the existing
 * `FileProvider` ([JOURNAL_CAPTURE_DIR]) and the finished recording is moved
 * into the journal folder by [adopt]. So the only thing another app is ever
 * granted is one file it is in the middle of writing, never the directory that
 * holds someone's history.
 *
 * ## What this does not promise
 *
 * The recording is **not** sealed by the vault passcode. The entry's title,
 * words and mood are — they are sealed values in the encrypted store like every
 * other journal entry — but the footage is a plain file protected by the app
 * sandbox and whatever full-disk encryption the device has. That is real, and
 * it is a weaker promise than the rest of the journal makes. It is written down
 * as AUDIT J-1 with the condition that would close it; until then no copy on
 * this screen may describe a recording as sealed or encrypted.
 *
 * Every call here touches the filesystem, and [adopt] additionally decodes
 * container metadata — run them off the main thread.
 */
object JournalVideoStore {

    private const val TAG = "JournalVideoStore"

    /** The journal's own directory, created on first use. */
    fun dir(context: Context): File =
        File(context.filesDir, JOURNAL_VIDEO_DIR).apply { mkdirs() }

    /** Where a capture is written while the camera app still owns it. */
    fun captureDir(context: Context): File =
        File(context.cacheDir, JOURNAL_CAPTURE_DIR).apply { mkdirs() }

    /**
     * A fresh, empty path for the camera app to record into.
     *
     * Named like a capture rather than like a recording ([isJournalVideoFile]
     * says no to it) so that a file left here by a crash can never be mistaken
     * for something the journal owns.
     */
    fun newCaptureFile(context: Context, nonce: Long): File =
        File(captureDir(context), "capture-$nonce.mp4")

    /**
     * The file behind an entry's stored [fileName], or `null` if the name is
     * not one this app minted or nothing is there.
     *
     * The name check is not paranoia about the store, which is sealed and
     * local — it is what stops a single corrupt or hand-edited record from
     * turning a path into a read outside this directory. `null` also covers
     * the ordinary case of a recording that is genuinely gone, which the
     * caller must draw as a missing clip rather than as a blank player.
     */
    fun fileFor(context: Context, fileName: String): File? {
        if (!isJournalVideoFile(fileName)) return null
        val file = File(dir(context), fileName)
        return if (file.isFile && file.length() > 0L) file else null
    }

    /**
     * Move a finished capture into the journal folder and describe it.
     *
     * Returns `null` — having deleted the capture — when there is nothing worth
     * keeping: an empty file is what a camera app leaves behind when the user
     * backed out of it, and saving an entry over zero bytes would put an
     * unplayable card in someone's journal.
     *
     * The file is in place before this returns, and the entry is written after
     * it. That order is deliberate: a crash in between leaves a file with no
     * entry, which [sweepOrphans] cleans up silently on the next open. The
     * other order would leave an entry with no file, which is a recording the
     * user is told they have and cannot play.
     */
    fun adopt(context: Context, captured: File, createdAtMs: Long, nonce: Long): AdoptedVideo? {
        if (!captured.isFile || captured.length() == 0L) {
            captured.delete()
            return null
        }
        val target = File(dir(context), journalVideoFileName(createdAtMs, nonce))
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
        return AdoptedVideo(
            fileName = target.name,
            durationMs = durationMs(target),
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
    fun delete(context: Context, fileName: String): Boolean {
        if (!isJournalVideoFile(fileName)) return false
        return File(dir(context), fileName).delete()
    }

    /**
     * Delete every recording no journal entry refers to. Returns how many went.
     *
     * See [orphanedVideoFiles] for the rule and for the one thing a caller must
     * get right: [referenced] has to be a *complete* list of the journal's
     * video file names, read after any capture in flight has been saved. A
     * partial list here deletes a recording someone still has an entry for.
     */
    fun sweepOrphans(context: Context, referenced: Set<String>): Int {
        val onDisk = dir(context).list()?.toList() ?: return 0
        var removed = 0
        for (orphan in orphanedVideoFiles(onDisk, referenced)) {
            if (File(dir(context), orphan).delete()) removed++
        }
        if (removed > 0) Log.d(TAG, "Swept $removed orphaned journal recording(s)")
        return removed
    }

    /**
     * Drop captures older than [olderThanMs] that nothing is coming back for.
     *
     * A capture the user abandoned, or one interrupted by a kill, is plaintext
     * video sitting in the cache with no entry pointing at it — the same rule
     * voice notes follow (AUDIT S-4).
     *
     * Age-gated, and [isStaleCapture] is where the reasoning for that lives —
     * an ungated sweep deletes the file the camera app is writing into.
     *
     * The case this does **not** rescue: a process killed mid-capture loses the
     * `captureTarget` the result callback needs, so the returning recording is
     * dropped and lands here as an orphan. Cleaned up, not recovered — the same
     * limitation the chat composer's capture has.
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
            Log.d(TAG, "Could not read the recording's duration", e)
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/** A recording that is now in the journal folder, as the entry will record it. */
data class AdoptedVideo(
    val fileName: String,
    val durationMs: Long,
    val sizeBytes: Long,
)
