package mullu.comrade.channel

import android.content.Context
import android.view.View
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mullu.comrade.call.CallManager
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * `PlatformView` fallback for call video — a `SurfaceViewRenderer` hosted
 * directly in the Flutter view hierarchy, i.e. the closest possible port of
 * what `CallScreen.kt:589-628` does today with Compose's `AndroidView`.
 *
 * **Not the default path.** [CallVideoChannel]'s `Texture` renderer is, for the
 * reasons in `../PLATFORM_CHANNELS.md` §6.3: no hybrid-composition raster
 * penalty, transforms and z-order that work like any other widget, and no
 * `SurfaceView`-versus-Flutter-surface conflict. This file exists so that a
 * device where the texture path misbehaves has a one-line switch rather than a
 * feature gap, and so the two paths can be compared on real hardware — which
 * has not happened, because none of this has been run (§10).
 *
 * Note the z-order line below. It is the specific wart the texture path
 * removes: the picture-in-picture tile's surface has to be explicitly stacked
 * above the full-screen renderer's, because two `SurfaceView`s have undefined
 * z-order otherwise. With `Texture`s, widget order decides and this disappears.
 *
 * View type id: `mullu.comrade/call/video/surface`.
 * Creation params: `{ "source": "local"|"remote", "mirror": bool, "overlay": bool }`.
 */
internal class CallVideoViewFactory : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        @Suppress("UNCHECKED_CAST")
        val params = (args as? Map<String, Any?>).orEmpty()
        return CallVideoPlatformView(
            context = context,
            source = params["source"] as? String ?: "remote",
            mirror = params["mirror"] as? Boolean ?: false,
            overlay = params["overlay"] as? Boolean ?: false,
        )
    }

    companion object {
        const val VIEW_TYPE = "mullu.comrade/call/video/surface"
    }
}

private class CallVideoPlatformView(
    context: Context,
    private val source: String,
    mirror: Boolean,
    overlay: Boolean,
) : PlatformView {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var attached: VideoTrack? = null
    private var initialised = false

    private val renderer = SurfaceViewRenderer(context).apply {
        setEnableHardwareScaler(true)
        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        // Must be set before the view attaches: two SurfaceViews have undefined
        // z-order, so the PiP tile has to be explicitly stacked above the
        // full-screen renderer. Carried over verbatim from CallScreen.kt:612-615.
        setZOrderMediaOverlay(overlay)
        setMirror(mirror)
    }

    init {
        job = scope.launch {
            val flow = if (source == "local") CallManager.localVideo else CallManager.remoteVideo
            flow.collect { track ->
                attached?.let { runCatching { it.removeSink(renderer) } }
                attached = null
                if (track == null) return@collect
                if (!initialised) {
                    // Same lazy-EGL reasoning as CallVideoChannel.observe.
                    val egl = CallManager.eglBaseContext ?: return@collect
                    renderer.init(egl, null)
                    initialised = true
                }
                runCatching { track.addSink(renderer) }.onSuccess { attached = track }
            }
        }
    }

    override fun getView(): View = renderer

    override fun dispose() {
        job?.cancel()
        scope.cancel()
        attached?.let { runCatching { it.removeSink(renderer) } }
        attached = null
        // The GPU/native cleanup the webview got for free — same release the
        // Compose DisposableEffect did.
        if (initialised) renderer.release()
    }
}
