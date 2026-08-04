package mullu.comrade.together

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import mullu.comrade.ComradeCore
import uniffi.comrade_core.Recording

/**
 * Finds the listener's **own copy** of what the other person named.
 *
 * This is the half of the Antra idea worth having. Antra resolves a streaming
 * link and then goes and acquires the audio; here a link — or an invitation —
 * resolves only to *which recording is meant*, and then we look in the library
 * that is already on this phone. Nothing is downloaded, no service is
 * authenticated against, and no DRM is involved: the answer to "how do we both
 * have this file" is "you already did".
 *
 * The scoring is not here. It is `comrade_core::together::match_score`, shared
 * with the desktop frontend, so the two cannot disagree about what counts as the
 * same recording. This file only supplies candidates from `MediaStore` and
 * applies the answer.
 *
 * ## Why matching is on artist/title/duration and not on ISRC
 * ISRC is the right key and the invitation carries it — but `MediaStore` has no
 * ISRC column, and `MediaMetadataRetriever` exposes no key for it either, so
 * getting one per candidate would mean parsing tags for every track in the
 * library. Artist/title/duration is what Android can answer cheaply. The ISRC
 * still earns its place on the wire: when both sides happen to have one it makes
 * the comparison exact, and it is what makes a mismatch precise ("a different
 * recording") instead of vague ("a different length").
 */
object LibraryResolver {

    /** A candidate from the device's own media library. */
    data class Candidate(val uri: Uri, val recording: Recording, val durationMs: Long)

    /**
     * The best match for `want` in this device's library, or `null` if nothing
     * is close enough to open on the listener's behalf.
     *
     * Deliberately conservative: opening the wrong track is worse than asking,
     * so anything below the shared confidence bar returns `null` and the UI
     * falls back to the file picker.
     */
    fun resolve(context: Context, want: Recording, wantMs: Long): Candidate? {
        val candidates = query(context, want)
        if (candidates.isEmpty()) return null
        val bar = ComradeCore.togetherMatchConfident()
        return candidates
            .map { it to ComradeCore.togetherMatchScore(want, it.recording, wantMs, it.durationMs) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= bar }
            ?.first
    }

    /**
     * Read a picked file's own tags, so an invitation can say *what* is being
     * played rather than just how long it runs.
     *
     * Tags only, and deliberately never the filename: a filename carries release
     * group, language and sometimes a path fragment, and `main.js` already takes
     * the position that it is the one piece of local filesystem vocabulary the
     * sender never chose to share. A file with no tags simply names nothing, and
     * the session falls back to duration matching.
     */
    fun describe(context: Context, uri: Uri): Recording? {
        val retriever = android.media.MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(context, uri)
            val title = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            Recording(
                // No METADATA_KEY_ISRC exists — see the class comment.
                isrc = null,
                title = title,
                artist = retriever
                    .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    .orEmpty(),
                album = retriever
                    .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM),
            )
        }.getOrNull().also { runCatching { retriever.release() } }
    }

    /**
     * Ask `MediaStore` for plausible rows rather than the whole library.
     *
     * The title is used as a `LIKE` filter so a large collection is narrowed by
     * the database rather than by us — the scorer then does the real work on a
     * handful of rows. A blank title means there is nothing to search on, and
     * returning everything would be worse than returning nothing.
     */
    private fun query(context: Context, want: Recording): List<Candidate> {
        val firstToken = want.title
            .split(' ', '(', '-', '[')
            .firstOrNull { it.isNotBlank() && it.length >= 3 }
            ?: return emptyList()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
        val out = mutableListOf<Candidate>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.TITLE} LIKE ?",
                arrayOf("%$firstToken%"),
                null,
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext() && out.size < MAX_CANDIDATES) {
                    out += Candidate(
                        uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon()
                            .appendPath(cursor.getLong(id).toString())
                            .build(),
                        recording = Recording(
                            isrc = null, // MediaStore has no ISRC column — see the class comment
                            title = cursor.getString(title).orEmpty(),
                            artist = cursor.getString(artist).orEmpty(),
                            album = cursor.getString(album),
                        ),
                        durationMs = cursor.getLong(duration),
                    )
                }
            }
        }.onFailure {
            // No permission, or a device that answers oddly. A library lookup is
            // a convenience — never let it break starting a session by hand.
            Log.d(TAG, "library lookup unavailable: ${it.message}")
        }
        return out
    }

    /**
     * A ceiling on rows scored, so a `LIKE` that happens to match half a large
     * collection cannot turn joining a session into a long pause.
     */
    private const val MAX_CANDIDATES = 200

    private const val TAG = "LibraryResolver"
}
