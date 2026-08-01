package mullu.comrade.ui

import android.graphics.Bitmap
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import mullu.comrade.ComradeCore

/**
 * A photo or a video, full screen.
 *
 * Tapping a photo or video in a thread opens this: black background, pinch-and-
 * drag zoom for photos, playback controls for video, the caption if there is
 * one, and a way out. Every messenger does this and its absence is felt
 * immediately — a 240 dp bubble is a thumbnail, not a look at the picture.
 *
 * Ported to `app/lib/src/widgets/media_viewer.dart`; the two agree on which
 * types open here ([opensFullScreen]) and on the black backdrop, which is not
 * decoration: a photo is judged against black and the chrome must not tint it.
 *
 * Nothing new touches disk. Photos come from the same in-memory bitmap LRU the
 * bubble reads, so opening one costs no second decrypt; video already needed a
 * file path for `VideoView` and reuses the same cached file, swept by
 * [purgeDecryptedMedia] exactly as before.
 */
@Composable
fun MediaViewerDialog(info: ComradeCore.MediaMessageInfo, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("media-viewer"),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (info.mimeType.startsWith("image/")) {
                    ZoomablePhoto(info)
                } else {
                    FullScreenVideo(info)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("media-viewer-close")) {
                    Text("✕", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    mediaKindLabel(info.mimeType),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (info.caption.isNotBlank()) {
                Text(
                    info.caption,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                        .testTag("media-viewer-caption"),
                )
            }
        }
    }
}

/** Pinch to zoom, drag to pan, within bounds that cannot strand the photo. */
@Composable
private fun ZoomablePhoto(info: ComradeCore.MediaMessageInfo) {
    var bitmap by remember(info.eventId) { mutableStateOf<Bitmap?>(null) }
    var error by remember(info.eventId) { mutableStateOf<String?>(null) }
    var scale by remember(info.eventId) { mutableFloatStateOf(1f) }
    var offsetX by remember(info.eventId) { mutableFloatStateOf(0f) }
    var offsetY by remember(info.eventId) { mutableFloatStateOf(0f) }

    LaunchedEffect(info.eventId) {
        runCatching { MediaCache.decodeImage(info) }
            .onSuccess { bitmap = it }
            .onFailure { error = it.message ?: "Could not load image" }
    }

    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        // Clamped so a photo can never be pinched into nothing or flung off
        // screen with no way back — the pan resets with the zoom.
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        if (scale <= 1f) {
            offsetX = 0f
            offsetY = 0f
        } else {
            offsetX += panChange.x
            offsetY += panChange.y
        }
    }

    val shown = bitmap
    when {
        shown != null -> Image(
            bitmap = shown.asImageBitmap(),
            contentDescription = info.caption.ifBlank { "Photo attachment" },
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                )
                .transformable(transform),
        )
        error != null -> Text("⚠ $error", color = Color.White, modifier = Modifier.padding(24.dp))
        else -> CircularProgressIndicator(Modifier.size(32.dp))
    }
}

/** Video, full screen, with the system's own transport controls. */
@Composable
private fun FullScreenVideo(info: ComradeCore.MediaMessageInfo) {
    val context = LocalContext.current
    var file by remember(info.eventId) { mutableStateOf<File?>(null) }
    var error by remember(info.eventId) { mutableStateOf<String?>(null) }

    LaunchedEffect(info.eventId) {
        runCatching { MediaCache.resolveFile(context, info) }
            .onSuccess { file = it }
            .onFailure { error = it.message ?: "Could not load video" }
    }

    val loaded = file
    when {
        loaded != null -> {
            var view by remember(info.eventId) { mutableStateOf<VideoView?>(null) }
            DisposableEffect(info.eventId) { onDispose { view?.stopPlayback() } }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoPath(loaded.absolutePath)
                        setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                        start()
                    }.also { view = it }
                },
            )
        }
        error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⚠ $error", color = Color.White, modifier = Modifier.padding(24.dp))
        }
        else -> CircularProgressIndicator(Modifier.size(32.dp))
    }
}
