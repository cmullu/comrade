package mullu.comrade.together

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import mullu.comrade.R

/**
 * Writes a downloaded track into the phone's own music library.
 *
 * The Android half of `comrade_core::download`: core decides whether the licence
 * permits a copy and fetches the bytes; this puts them somewhere every music app
 * on the device can see, which is what makes a download behave like the local
 * files [MusicLibrary] already lists rather than like a file hidden in app
 * storage.
 *
 * ## `MediaStore`, not `filesDir`
 *
 * App-private storage would have been three lines shorter and would have made
 * the download invisible: not in [MusicLibrary]'s query, not in any other player,
 * and gone when the app is uninstalled. Writing through `MediaStore.Audio` is
 * what makes the track a track. It also needs no storage permission on API 29+ —
 * an app may always insert its own media — which matters because the library
 * *read* permission is separately refusable and a refusal must not cost the
 * download.
 *
 * ## `IS_PENDING`, and why it is not optional
 *
 * The row is created with `IS_PENDING = 1` and cleared only after the bytes are
 * written. Without it the media scanner can index a half-written file, and what
 * that produces is not a missing track but a **corrupt** one that shows up in
 * every music app on the phone with the right name and a broken decode. A failed
 * download deletes its own row rather than leaving that behind.
 *
 * ## The metadata columns are the tags
 *
 * `comrade_core::download` deliberately writes no in-file ID3 frames (see its
 * header: `id3` is MP3-only and archives serve FLAC/Ogg/M4A just as often). So
 * `TITLE`/`ARTIST`/`ALBUM` here are what make the track read correctly, and they
 * come from the same [uniffi.comrade_ui.DownloadedTrackDto] the filename was
 * built from — not re-derived, so the row and the file cannot disagree.
 */
object MusicDownloads {

    /** Where downloads land, under the public Music collection. */
    private const val SUBDIRECTORY = "Comrade"

    /**
     * What happened. Not a `Result`, for `CatalogueResult`'s reason: the caller
     * has three different sentences to say and a `Result` collapses two of them.
     */
    sealed interface Outcome {
        /** In the library, at [uri], and playable now. */
        data class Saved(val uri: Uri, val displayName: String) : Outcome

        /**
         * The library already has a file by that name.
         *
         * Its own outcome because it is not a failure and must not read as one —
         * the useful answer is "you already have this", not "download failed".
         */
        data class AlreadyThere(val displayName: String) : Outcome

        data class Failed(val reason: String) : Outcome
    }

    /**
     * Write [track] into the music library.
     *
     * **Blocking.** Call it on `Dispatchers.IO`; it copies up to
     * `comrade_core::download::MAX_TRACK_BYTES` through a `ContentResolver`
     * stream.
     */
    fun save(context: Context, track: uniffi.comrade_ui.DownloadedTrackDto): Outcome {
        val resolver = context.contentResolver
        // `VOLUME_EXTERNAL_PRIMARY` is API 29+; below that the legacy
        // `EXTERNAL_CONTENT_URI` is the only collection there is, and it needs
        // `WRITE_EXTERNAL_STORAGE` — which this app does not request. So a
        // pre-29 device is told plainly rather than throwing a SecurityException
        // from inside the insert.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Outcome.Failed(
                context.getString(R.string.together_download_needs_q),
            )
        }
        val collection = MediaStore.Audio.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY,
        )
        val relative = "${Environment.DIRECTORY_MUSIC}/$SUBDIRECTORY"

        if (existing(context, collection, track.filename, relative) != null) {
            return Outcome.AlreadyThere(track.filename)
        }

        val pending = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, track.filename)
            put(MediaStore.Audio.Media.MIME_TYPE, track.mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relative)
            // The tags, since the file carries none of its own.
            put(MediaStore.Audio.Media.TITLE, track.title)
            if (track.artist.isNotBlank()) {
                put(MediaStore.Audio.Media.ARTIST, track.artist)
            }
            track.album?.takeIf { it.isNotBlank() }?.let {
                put(MediaStore.Audio.Media.ALBUM, it)
            }
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            // Hidden from every scanner until the bytes are all there.
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri = runCatching { resolver.insert(collection, pending) }.getOrNull()
            ?: return Outcome.Failed(context.getString(R.string.together_download_no_row))

        val wrote = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(track.bytes)
                out.flush()
            } ?: error("no output stream")
        }
        if (wrote.isFailure) {
            // Delete rather than leave a pending row: a pending row is invisible
            // but it still occupies the name, so the retry would report
            // `AlreadyThere` for a file that was never written.
            runCatching { resolver.delete(uri, null, null) }
            return Outcome.Failed(
                wrote.exceptionOrNull()?.message
                    ?: context.getString(R.string.together_download_write_failed),
            )
        }

        val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
        val published = runCatching { resolver.update(uri, done, null, null) }
        if (published.isFailure) {
            runCatching { resolver.delete(uri, null, null) }
            return Outcome.Failed(context.getString(R.string.together_download_write_failed))
        }
        return Outcome.Saved(uri, track.filename)
    }

    /**
     * The existing row for this name in our own directory, or `null`.
     *
     * Scoped to [relative] on purpose: a track called `A. R. Rahman - Kun Faya
     * Kun.flac` that the person already had from somewhere else is not ours to
     * report as a duplicate, and refusing to download because an unrelated file
     * shares a name would be wrong.
     */
    private fun existing(
        context: Context,
        collection: Uri,
        displayName: String,
        relative: String,
    ): Uri? = runCatching {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
            // MediaStore stores RELATIVE_PATH with a trailing separator.
            arrayOf(displayName, "$relative/"),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                MediaStore.Audio.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    c.getLong(0),
                )
            } else {
                null
            }
        }
    }.getOrNull()
}
