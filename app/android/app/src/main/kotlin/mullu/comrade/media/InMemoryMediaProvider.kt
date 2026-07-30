package mullu.comrade.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves a decrypted attachment to another app **from memory** — no plaintext
 * copy on disk, ever.
 *
 * This is the piece the Compose app did not have. There, opening a received
 * document meant writing the decrypted bytes to `cacheDir/media` and handing
 * out a `FileProvider` URI, which is why that app also needed an explicit purge
 * on backgrounding (AUDIT S-4) and why forgetting to call it would leave
 * plaintext on the device. The unified app keeps decrypted bytes in a bounded
 * in-memory cache (`media_attachment.dart`'s `DecryptedMediaCache`), and this
 * provider is how those bytes reach an external viewer without breaking that
 * property: there is no file to purge because there is no file.
 *
 * ## Why a proxy file descriptor rather than a pipe
 *
 * `openPipeHelper` is the usual trick for serving generated content, but a pipe
 * is not seekable, and the apps this exists for — PDF readers, video players —
 * seek. [StorageManager.openProxyFileDescriptor] (API 26+, which is this
 * module's `minSdk`) gives a *seekable* descriptor backed by callbacks, so the
 * bytes can stay in the heap and still satisfy a viewer that jumps to the end
 * of the file first.
 *
 * ## Lifetime and access
 *
 * Each staged blob gets a single-use random token, is readable only through a
 * URI carrying that token, and the provider is `exported=false` +
 * `grantUriPermissions=true`: the only way another app sees one is the
 * `FLAG_GRANT_READ_URI_PERMISSION` on the Intent this app hands it. Tokens are
 * dropped by [release] once the viewer is done with them, by [clear] on vault
 * lock / backgrounding, and oldest-first past [MAX_STAGED] so a session of
 * opening attachments cannot grow without bound.
 */
class InMemoryMediaProvider : ContentProvider() {

    private class Blob(val name: String, val mimeType: String, val bytes: ByteArray)

    companion object {
        /** How many decrypted blobs may be staged for external viewers at once. */
        private const val MAX_STAGED = 4

        /**
         * Insertion-ordered so eviction is oldest-first. Guarded by its own
         * monitor rather than being a `ConcurrentHashMap`, because eviction has
         * to read and write it as one step.
         */
        private val staged = LinkedHashMap<String, Blob>()

        /** Handler threads serving open descriptors, keyed by token. */
        private val servers = ConcurrentHashMap<String, HandlerThread>()

        private fun authority(context: Context) = "${context.packageName}.media"

        /**
         * Stage [bytes] and return the `content://` URI to hand out. The
         * returned URI is the only handle to it.
         */
        fun stage(context: Context, name: String, mimeType: String, bytes: ByteArray): Uri {
            val token = UUID.randomUUID().toString()
            synchronized(staged) {
                staged[token] = Blob(name, mimeType, bytes)
                while (staged.size > MAX_STAGED) {
                    val oldest = staged.keys.firstOrNull() ?: break
                    staged.remove(oldest)
                    servers.remove(oldest)?.quitSafely()
                }
            }
            return Uri.parse("content://${authority(context)}/$token")
        }

        /** Drop one staged blob — the caller is done handing it out. */
        fun release(uri: Uri) {
            val token = uri.lastPathSegment ?: return
            synchronized(staged) { staged.remove(token) }
            servers.remove(token)?.quitSafely()
        }

        /**
         * Drop every staged plaintext. Called when the app leaves the
         * foreground or the vault locks, alongside the Dart-side cache purge —
         * the two together are the whole of "the app is not sitting on
         * decrypted media".
         */
        fun clear() {
            synchronized(staged) { staged.clear() }
            servers.values.forEach { it.quitSafely() }
            servers.clear()
        }

        private fun blob(token: String?): Blob? =
            token?.let { synchronized(staged) { staged[it] } }
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = blob(uri.lastPathSegment)?.mimeType

    /**
     * `DISPLAY_NAME` and `SIZE` only. Many viewers query these before opening
     * (a share sheet shows the name; a player pre-allocates from the size), and
     * a provider that answers `null` here reads to them as a broken file.
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val blob = blob(uri.lastPathSegment) ?: return null
        val columns = projection?.filter {
            it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE
        }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        cursor.addRow(
            columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> blob.name
                    OpenableColumns.SIZE -> blob.bytes.size.toLong()
                    else -> null
                }
            }.toTypedArray(),
        )
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        // Read-only, always: nothing outside this app may write into a
        // conversation's media.
        if (mode.contains('w')) {
            throw java.io.FileNotFoundException("comrade media is read-only")
        }
        val token = uri.lastPathSegment
            ?: throw java.io.FileNotFoundException("no attachment token in $uri")
        val blob = blob(token) ?: throw java.io.FileNotFoundException("no such attachment")
        val context = context ?: throw java.io.FileNotFoundException("provider has no context")
        val storage = context.getSystemService(StorageManager::class.java)
            ?: throw java.io.FileNotFoundException("no StorageManager")

        // One handler thread per open blob: the callbacks below are invoked on
        // it, and must not run on the main thread.
        val thread = HandlerThread("comrade-media-$token").also { it.start() }
        servers.put(token, thread)?.quitSafely()

        return storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = blob.bytes.size.toLong()

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    if (offset >= blob.bytes.size) return 0
                    val available = (blob.bytes.size - offset).coerceAtMost(size.toLong()).toInt()
                    System.arraycopy(blob.bytes, offset.toInt(), data, 0, available)
                    return available
                }

                override fun onRelease() {
                    servers.remove(token)?.quitSafely()
                }
            },
            Handler(thread.looper),
        )
    }

    // ── Not a database ───────────────────────────────────────────────────────

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
