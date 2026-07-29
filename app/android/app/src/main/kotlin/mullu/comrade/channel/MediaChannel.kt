package mullu.comrade.channel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mullu.comrade.media.InMemoryMediaProvider

/**
 * Attachments: choosing one, capturing one, playing one, handing one out.
 *
 * The three seams the Dart media UI declares — `AttachmentPicker`,
 * `MediaCaptureDelegate` and `MediaPlaybackDelegate` — all land here. Each had a
 * do-nothing default that said "not wired up on this platform yet"; this is the
 * Android half that replaces those messages with behaviour.
 *
 * ## Bytes cross the channel; files mostly do not
 *
 * `upload_and_send_media` takes bytes, and the Rust core caps a payload at 10 MB
 * ([MAX_MEDIA_BYTES]), so a picked attachment is read here, checked against the
 * cap, and handed over as a `ByteArray` — the standard codec maps that to a Dart
 * `Uint8List` without a base64 round trip. This deliberately does *not* mirror
 * [VoiceRecorderChannel]'s path-not-bytes rule: there is no file here to own or
 * delete. Content URIs from a document picker belong to the other app.
 *
 * The one exception is camera capture, which cannot avoid a file: a camera app
 * writes full-resolution output to `EXTRA_OUTPUT` (the alternative, the
 * thumbnail bitmap in the result extras, is a few hundred pixels wide). That
 * file is written into the same `cacheDir/media` the app already purges, read
 * once, and deleted in a `finally` — so the plaintext window is the seconds
 * between the shutter and the send, not "until something remembers to purge".
 *
 * ## Playback and open-externally keep plaintext off disk
 *
 * Audio plays through a [MediaDataSource] over the byte array, so a voice note
 * is never written out to be played. Anything handed to *another* app goes
 * through [InMemoryMediaProvider], which serves a seekable descriptor straight
 * from memory. Between them, the unified app has no disk plaintext path at all —
 * see `PLATFORM_CHANNELS.md` §11.
 */
internal class MediaChannel(
    messenger: BinaryMessenger,
    private val appContext: Context,
    private val activityResults: ActivityResultBridge,
) : MethodChannel.MethodCallHandler {

    companion object {
        /**
         * Mirrors `comrade_core::media::MAX_MEDIA_BYTES`. Checked here as well
         * as in Rust so an oversized pick fails before its bytes are copied
         * into the Dart heap, with a message naming the actual size.
         */
        const val MAX_MEDIA_BYTES = 10 * 1024 * 1024

        private const val FALLBACK_MIME = "application/octet-stream"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The one live audio player, and the attachment it is playing. */
    private var player: MediaPlayer? = null
    private var playingToken: String? = null

    /** URIs currently staged for an external viewer, so they can be released. */
    private val stagedUris = mutableListOf<Uri>()

    private val methods = MethodChannel(messenger, ChannelNames.MEDIA)
        .also { it.setMethodCallHandler(this) }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "capabilities" -> result.postSuccess(capabilities())
            "pick" -> pick(result)
            "capturePhoto" -> capture(result, photo = true)
            "captureVideo" -> capture(result, photo = false)
            "toggleAudio" -> toggleAudio(call, result)
            "stopAudio" -> {
                stopAudio()
                result.postSuccess()
            }
            "openExternally" -> openExternally(call, result)
            "purge" -> {
                purge()
                result.postSuccess()
            }
            else -> result.postNotImplemented()
        }
    }

    /**
     * What this device can actually do, so the UI can hide what it cannot
     * instead of offering a button that fails on tap (the settings screen's own
     * "no fake switches" rule, applied to the composer).
     */
    private fun capabilities(): Map<String, Boolean> {
        val pm = appContext.packageManager
        val hasCamera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        return mapOf(
            "pick" to true,
            // A camera *app* is a separate question from a camera: a device can
            // have the sensor and no activity that answers the capture intent.
            "capturePhoto" to (hasCamera && canHandle(Intent(MediaStore.ACTION_IMAGE_CAPTURE))),
            "captureVideo" to (hasCamera && canHandle(Intent(MediaStore.ACTION_VIDEO_CAPTURE))),
            // Voice notes go through VoiceRecorderChannel, but whether the
            // composer offers them is one capability question, answered here.
            "recordVoice" to pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
            "playAudio" to true,
            "openExternally" to true,
        )
    }

    private fun canHandle(intent: Intent): Boolean =
        intent.resolveActivity(appContext.packageManager) != null

    // ── Picking ──────────────────────────────────────────────────────────────

    /**
     * `ACTION_OPEN_DOCUMENT`, not `GetContent`: it is the modern picker and it
     * yields a stable URI. The type filter is any-type, matching the Compose
     * composer — the core sends arbitrary MIME types, so a narrower filter would
     * be a UI pretending the backend is more limited than it is (divergence
     * D14).
     */
    private fun pick(result: MethodChannel.Result) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val launched = activityResults.launch(intent) { code, data ->
            val uri = data?.data
            if (code != Activity.RESULT_OK || uri == null) {
                // Cancelled is not an error — the user closed the picker.
                result.postSuccess(null)
                return@launch
            }
            scope.replyOnIo(result, ErrorCodes.CORE_ERROR) { readAttachment(uri) }
        }
        if (!launched) {
            result.postError(
                ErrorCodes.NO_ACTIVITY,
                "no file picker is available on this device",
            )
        }
    }

    /**
     * Read a picked URI into the payload Dart sends.
     *
     * Size is checked from the provider's own `SIZE` column first so an
     * oversized pick is refused without reading it, then again from what was
     * actually read — a content provider is free to report nothing, or to lie.
     */
    private fun readAttachment(uri: Uri): Map<String, Any> {
        val resolver = appContext.contentResolver
        var name = "attachment"
        var declaredSize = -1L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }
                    ?.let { cursor.getString(it) }
                    ?.let { name = it }
                cursor.getColumnIndex(OpenableColumns.SIZE)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { declaredSize = cursor.getLong(it) }
            }
        }
        if (declaredSize > MAX_MEDIA_BYTES) {
            throw IllegalStateException(tooLarge(declaredSize))
        }
        val bytes = resolver.openInputStream(uri)?.use { stream ->
            // Read one byte past the cap so "exactly at the limit" is accepted
            // and "over it" is detectable without buffering the whole thing.
            val buffer = stream.readAtMost(MAX_MEDIA_BYTES + 1)
            if (buffer.size > MAX_MEDIA_BYTES) throw IllegalStateException(tooLarge(buffer.size.toLong()))
            buffer
        } ?: throw IllegalStateException("could not read the chosen file")
        if (bytes.isEmpty()) {
            // An empty read is nearly always a permission or provider failure
            // rather than a genuinely empty file, and the core refuses it
            // anyway — say so here, where the cause is known.
            throw IllegalStateException("could not read the chosen file")
        }
        return mapOf(
            "name" to name,
            "mimeType" to (resolver.getType(uri) ?: mimeFromName(name)),
            "bytes" to bytes,
        )
    }

    private fun tooLarge(size: Long): String =
        "that file is ${"%.1f".format(size / 1_048_576.0)} MB; the limit is 10 MB"

    // ── Camera ───────────────────────────────────────────────────────────────

    private fun capture(result: MethodChannel.Result, photo: Boolean) {
        val dir = File(appContext.cacheDir, "media").apply { mkdirs() }
        val target = File(dir, "capture-${UUID.randomUUID()}.${if (photo) "jpg" else "mp4"}")
        val output = try {
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", target)
        } catch (e: IllegalArgumentException) {
            result.postError(ErrorCodes.CORE_ERROR, "cannot stage the capture: ${e.message}")
            return
        }
        val action = if (photo) MediaStore.ACTION_IMAGE_CAPTURE else MediaStore.ACTION_VIDEO_CAPTURE
        val intent = Intent(action)
            .putExtra(MediaStore.EXTRA_OUTPUT, output)
            // Video only: let the camera app stop itself at the size we can
            // actually send, rather than recording three minutes we then refuse.
            .apply { if (!photo) putExtra(MediaStore.EXTRA_SIZE_LIMIT, MAX_MEDIA_BYTES.toLong()) }
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val launched = activityResults.launch(intent) { code, data ->
            if (code != Activity.RESULT_OK) {
                target.delete()
                result.postSuccess(null)
                return@launch
            }
            scope.replyOnIo(result, ErrorCodes.CORE_ERROR) {
                try {
                    // Some camera apps return their own URI in the result
                    // instead of writing to EXTRA_OUTPUT; honour whichever
                    // actually has the capture.
                    val fromResult = data?.data
                    if (target.length() > 0) {
                        if (target.length() > MAX_MEDIA_BYTES) throw IllegalStateException(tooLarge(target.length()))
                        mapOf(
                            // A human name, not the staging file's UUID: this
                            // becomes the attachment's caption, which the
                            // recipient sees.
                            "name" to if (photo) "photo.jpg" else "video.mp4",
                            "mimeType" to if (photo) "image/jpeg" else "video/mp4",
                            "bytes" to target.readBytes(),
                        )
                    } else if (fromResult != null) {
                        readAttachment(fromResult)
                    } else {
                        throw IllegalStateException("the camera returned no capture")
                    }
                } finally {
                    // The plaintext capture must not outlive this read.
                    target.delete()
                }
            }
        }
        if (!launched) {
            target.delete()
            result.postError(ErrorCodes.NOT_FOUND, "no camera app is available on this device")
        }
    }

    // ── Playback (in memory) ─────────────────────────────────────────────────

    /**
     * Play, pause, or resume one attachment. Returns whether it is playing
     * after the call, which is what the bubble's play/pause glyph renders.
     *
     * A second attachment replaces the first rather than overlapping it — two
     * voice notes at once is never what was meant.
     */
    private fun toggleAudio(call: MethodCall, result: MethodChannel.Result) {
        val token = call.argument<String>("eventId")
        val bytes = call.argument<ByteArray>("bytes")
        if (token == null || bytes == null) {
            result.postError(ErrorCodes.BAD_ARGUMENT, "toggleAudio needs eventId and bytes")
            return
        }
        val existing = player
        if (existing != null && playingToken == token) {
            if (existing.isPlaying) existing.pause() else existing.start()
            result.postSuccess(existing.isPlaying)
            return
        }
        stopAudio()
        // `prepare()` blocks (it parses the container), so keep it off the
        // platform thread; `start()` afterwards is cheap.
        scope.replyOnIo(result, ErrorCodes.CORE_ERROR) {
            val created = MediaPlayer().apply {
                setDataSource(ByteArrayMediaDataSource(bytes))
                setOnCompletionListener {
                    it.seekTo(0)
                    // Nothing to notify: Dart re-reads state on its next tap,
                    // and a completion callback racing a dispose is how a
                    // released player gets touched.
                }
                prepare()
            }
            player = created
            playingToken = token
            created.start()
            true
        }
    }

    private fun stopAudio() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        playingToken = null
    }

    // ── Handing an attachment to another app ─────────────────────────────────

    private fun openExternally(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name") ?: "attachment"
        val mime = call.argument<String>("mimeType")?.takeIf { it.isNotBlank() } ?: FALLBACK_MIME
        val bytes = call.argument<ByteArray>("bytes")
        if (bytes == null) {
            result.postError(ErrorCodes.BAD_ARGUMENT, "openExternally needs bytes")
            return
        }
        val activity = activityResults.activity
        if (activity == null) {
            result.postError(ErrorCodes.NO_ACTIVITY, "no Activity to open the attachment from")
            return
        }
        val uri = InMemoryMediaProvider.stage(appContext, name, mime, bytes)
        stagedUris += uri
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            // A chooser rather than a bare ACTION_VIEW: with no default handler
            // the bare intent throws, and "nothing opened, no explanation" is
            // the worst answer for a document someone was sent.
            activity.startActivity(Intent.createChooser(view, name))
            result.postSuccess(true)
        } catch (_: android.content.ActivityNotFoundException) {
            InMemoryMediaProvider.release(uri)
            stagedUris.remove(uri)
            result.postSuccess(false)
        }
    }

    /**
     * Drop every decrypted plaintext this layer holds — the audio player's
     * buffer and anything staged for an external viewer.
     *
     * Called when the app leaves the foreground or the vault locks, in step with
     * the Dart-side `DecryptedMediaCache.clear()`.
     */
    fun purge() {
        stopAudio()
        stagedUris.clear()
        InMemoryMediaProvider.clear()
    }

    fun dispose() {
        purge()
        scope.cancel()
        methods.setMethodCallHandler(null)
    }
}

/**
 * A [MediaDataSource] over a byte array, so audio plays out of the decrypted
 * cache instead of a file. `MediaPlayer.setDataSource(MediaDataSource)` needs
 * API 23; this module's `minSdk` is 26.
 */
private class ByteArrayMediaDataSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= bytes.size) return -1
        val available = (bytes.size - position).coerceAtMost(size.toLong()).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, available)
        return available
    }

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() = Unit
}

/**
 * Read at most [limit] bytes. `readBytes()` would buffer whatever the stream
 * offers, which for a picked file is attacker-adjacent input: the point of the
 * cap is not to allocate 200 MB before rejecting it.
 */
private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    var total = 0
    while (total < limit) {
        val read = read(chunk, 0, chunk.size.coerceAtMost(limit - total))
        if (read <= 0) break
        out.write(chunk, 0, read)
        total += read
    }
    return out.toByteArray()
}

/** Last-resort MIME guess for a provider that reports none. */
private fun mimeFromName(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: "application/octet-stream"
}
