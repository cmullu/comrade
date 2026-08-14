package mullu.comrade.together

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache

/**
 * The music that is already on this phone, as something the Together tab can
 * browse.
 *
 * The sibling of [LibraryResolver], and the division between them is worth
 * stating because they read the same provider: that one **searches** for one
 * named recording a peer asked about, this one **lists** what is here so someone
 * can choose. Searching wants a narrow `LIKE` and a confidence bar; listing
 * wants everything, in a stable order, with artwork. Folding them together would
 * mean one of the two doing the other's query badly.
 *
 * Nothing here decides anything. The rows come back as
 * [TogetherDecisions.Track], and how they are filtered, labelled and ordered is
 * that file's — pure, and checked by the JVM lane before CI, which none of this
 * file is.
 */
object MusicLibrary {

    /**
     * What one read of the library found.
     *
     * `truncated` exists so the screen can say so. A list silently cut at 2000
     * reads as "that is all your music", and someone whose album is on row 2400
     * concludes the feature cannot see it — which is the same
     * report-what-was-dropped rule the rest of this codebase applies to caps.
     */
    data class Page(val tracks: List<TogetherDecisions.Track>, val truncated: Boolean)

    /**
     * Every music file `MediaStore` will admit to, title-ordered.
     *
     * `IS_MUSIC != 0` is doing real work: without it the list is full of
     * notification tones and ringtones, which is what "all audio on the device"
     * actually means and is not what anybody wants to listen to with a friend.
     * It is the same column the system's own music pickers filter on.
     *
     * Ordered by the provider rather than by us, and with `COLLATE NOCASE`, so
     * `apple` sorts next to `Apple` instead of after `Zoo` — SQLite's default
     * ordering is byte-wise, which puts every lowercase title after every
     * uppercase one.
     *
     * Returns an empty page rather than throwing on a refused permission: this
     * is called from a screen that has to draw something either way, and
     * [LibraryResolver.mayRead] is what the caller asks about the permission.
     */
    fun page(context: Context, limit: Int = MAX_ROWS): Page {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
        )
        val out = mutableListOf<TogetherDecisions.Track>()
        var truncated = false
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext()) {
                    if (out.size >= limit) {
                        truncated = true
                        break
                    }
                    out += TogetherDecisions.Track(
                        uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(id),
                        ).toString(),
                        title = cursor.getString(title).orEmpty(),
                        artist = cursor.getString(artist).orEmpty(),
                        album = cursor.getString(album),
                        durationMs = cursor.getLong(duration),
                        albumId = cursor.getLong(albumId).takeIf { it != 0L },
                    )
                }
            }
        }.onFailure {
            // A refused permission, or a device that answers oddly. An empty
            // list plus the permission question the screen already asks is a
            // better answer than a crash inside a tab.
            Log.d(TAG, "library listing unavailable: ${it.message}")
        }
        return Page(out, truncated)
    }

    /**
     * The `Recording` to send with an invitation for this row.
     *
     * Built from the columns already in hand rather than by re-opening the file
     * with a `MediaMetadataRetriever` — [LibraryResolver.describe] does that for
     * a file picked through SAF, where there is no provider row to read. Here
     * there is one, and it is the same data.
     *
     * `<unknown>` becomes an empty artist for the reason
     * [TogetherDecisions.MEDIASTORE_UNKNOWN] records: it is the provider's
     * placeholder, and sending it would put the string "<unknown>" on the other
     * person's screen as though the file had claimed it.
     */
    fun recordingOf(track: TogetherDecisions.Track): uniffi.comrade_core.Recording =
        uniffi.comrade_core.Recording(
            // MediaStore has no ISRC column — see LibraryResolver's class comment.
            isrc = null,
            title = track.title,
            artist = track.artist.takeIf { it != TogetherDecisions.MEDIASTORE_UNKNOWN }.orEmpty(),
            album = track.album?.takeIf { it != TogetherDecisions.MEDIASTORE_UNKNOWN },
        )

    /**
     * The cover, or `null` when the file carries none.
     *
     * Two paths, because the sanctioned one did not exist at this app's minimum:
     * `loadThumbnail` from API 29, and the `albumart` provider below it. The old
     * URI is deprecated rather than gone, and on the versions that need it there
     * is nothing else — a `MediaMetadataRetriever` per row would open every file
     * on the device to draw one list.
     *
     * Takes the URI and the album id rather than a [TogetherDecisions.Track],
     * because the live player has the first and not the second: a session
     * remembers what it opened (`UiState.Live.sourceUri`) and never learns which
     * `MediaStore` album that was. Passing `albumId = null` costs the cover only
     * below API 29, where the legacy provider is keyed on it and there is nothing
     * else to ask — a stated gap rather than a silent one.
     *
     * Call this off the main thread: both paths do IO.
     */
    fun artwork(context: Context, uri: String, albumId: Long?, sizePx: Int): Bitmap? {
        // **The size is part of the key, and leaving it out was a bug the album
        // grid made visible.** Three sizes are asked for now — a 48 dp row, a
        // 144 dp tile and the 320 dp sleeve — and a key that named only the album
        // handed whichever was decoded first to all three. Browsing rows and then
        // opening a record drew the sleeve from a 48 px thumbnail, upscaled;
        // going the other way held a sleeve-sized bitmap for every row. Neither
        // reads as a caching bug from a screenshot.
        val key = "${albumId?.toString() ?: uri}@$sizePx"
        cache.get(key)?.let { return it }
        val loaded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.contentResolver.loadThumbnail(
                    Uri.parse(uri),
                    android.util.Size(sizePx, sizePx),
                    null,
                )
            }.getOrNull()
        } else {
            legacyAlbumArt(context, albumId, sizePx)
        }
        if (loaded != null) cache.put(key, loaded)
        return loaded
    }

    /** The cover for a library row, which knows both keys. */
    fun artwork(context: Context, track: TogetherDecisions.Track, sizePx: Int): Bitmap? =
        artwork(context, track.uri, track.albumId, sizePx)

    /**
     * The pre-API-29 cover: the `albumart` provider, downsampled.
     *
     * Two passes over the stream rather than one — bounds first, then decode —
     * because a full-size cover is several megabytes and a list of them is an
     * `OutOfMemoryError` on the devices that need this path. The same
     * `inJustDecodeBounds` shape `ui/AttachmentPreview.kt` uses.
     */
    private fun legacyAlbumArt(context: Context, albumId: Long?, sizePx: Int): Bitmap? {
        val id = albumId ?: return null
        val uri = ContentUris.withAppendedId(LEGACY_ALBUM_ART, id)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }.getOrNull()
        val widest = maxOf(bounds.outWidth, bounds.outHeight)
        if (widest <= 0) return null
        var sample = 1
        while (widest / sample > sizePx * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()
    }

    private const val CACHE_BYTES = 12 * 1024 * 1024

    /**
     * Covers already decoded, so scrolling back up does not decode them again.
     *
     * Sized in bytes rather than in entries, which is the only bound that means
     * anything for bitmaps — 64 rows of wildly different cover sizes is not a
     * memory budget. `LruCache` evicts rather than growing.
     *
     * **Four megabytes was right for a list of 48 dp thumbnails and is not right
     * for a grid of covers.** A 144 dp tile on a 3× screen is a 432 px square,
     * which is at least 750 kB as `ARGB_8888` — `loadThumbnail` guarantees only
     * *at least* the size asked for, so that is a floor. A screenful of six is
     * then about 4.5 MB, and the old budget could not hold even one screen:
     * scrolling re-decoded every tile it had just evicted.
     *
     * Twelve is **two to three** screenfuls, not four, and it is worth stating
     * what else competes for it now that the key carries the size: the sleeve at
     * 320 dp is a 960 px square, near 3.7 MB, so a session's own cover is
     * something like a third of the whole budget on its own. That is the
     * trade-off accepted for drawing the sleeve at the size it is shown rather
     * than upscaling a list thumbnail — and `LruCache` evicts, so the cost of
     * being wrong here is re-decoding, not a crash.
     */
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Dropped when the tab is left, so a library browse is not a resident cost. */
    fun forgetArtwork() = cache.evictAll()

    /** `content://media/external/audio/albumart`, for API 26–28. */
    private val LEGACY_ALBUM_ART: Uri = Uri.parse("content://media/external/audio/albumart")

    /**
     * A ceiling on rows read in one go.
     *
     * Not a memory bound — the rows are small — but a bound on how long a tab
     * takes to draw on a phone with a very large collection. Reported through
     * [Page.truncated] rather than swallowed.
     */
    private const val MAX_ROWS = 2_000

    private const val TAG = "MusicLibrary"
}
