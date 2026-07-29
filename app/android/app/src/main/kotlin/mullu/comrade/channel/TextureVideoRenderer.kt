package mullu.comrade.channel

import android.graphics.SurfaceTexture
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.VideoFrame

/**
 * Renders an `org.webrtc.VideoTrack` into a [SurfaceTexture] that Flutter
 * composites through a `Texture` widget.
 *
 * This is the mechanism behind option (b) in `../PLATFORM_CHANNELS.md` §6:
 * `CallManager` keeps the `PeerConnection`, and the native `VideoTrack` — which
 * cannot cross a `MethodChannel` — never has to. It is sunk here, on the native
 * side, and Dart is handed only an integer texture id.
 *
 * **Provenance.** The shape is `flutter_webrtc`'s own Android renderer
 * (`SurfaceTextureRenderer extends EglRenderer`): subclass [EglRenderer],
 * create its EGL surface against a `SurfaceTexture` rather than a
 * `SurfaceView`, and override [onFrame] to report rotated frame dimensions
 * before delegating. That is worth stating plainly, because it is the argument
 * for (b) over (a): this is the renderer the `flutter_webrtc` rewrite would
 * have given us, without the rewrite.
 *
 * **Threading.** [EglRenderer.init] and [surfaceCreated] must be called on the
 * main thread (libwebrtc asserts it). [onFrame] arrives on a WebRTC decode
 * thread and is safe there — [EglRenderer] posts to its own render thread
 * internally. [reportEvents] therefore fires on that decode thread, so
 * [CallVideoChannel] hops to main before touching an `EventSink`.
 *
 * **Uncompiled.** See `../PLATFORM_CHANNELS.md` §10. The members used here
 * (`init(EglBase.Context, int[], RendererCommon.GlDrawer)`,
 * `createEglSurface(SurfaceTexture)`, `release()`, `setMirror`, `onFrame`) are
 * stable public API of the libwebrtc Android SDK, and this project already
 * depends on `io.github.webrtc-sdk:android:125.6422.07`, which keeps the
 * `org.webrtc.*` namespace — but none of it has been compiled here.
 */
internal class TextureVideoRenderer(
    name: String,
    private val onResolution: (width: Int, height: Int, rotation: Int) -> Unit,
) : EglRenderer(name) {

    private val lock = Any()
    private var texture: SurfaceTexture? = null
    private var rotatedWidth = 0
    private var rotatedHeight = 0
    private var rotation = -1

    /** True once [initialise] has run — EGL is lazy, see [CallVideoChannel]. */
    @Volatile
    var initialised: Boolean = false
        private set

    /**
     * Bring the renderer up against the shared EGL context and start drawing
     * into [surfaceTexture]. Main thread only, and only once.
     *
     * Deferred rather than done at construction because
     * [mullu.comrade.call.CallManager.eglBaseContext] is null until
     * `ensureFactory` has run (`CallManager.kt:266-267`), which happens during
     * call setup — so a renderer created by a widget's `initState` before the
     * call connects has no context to initialise against yet. Both video flows
     * are only populated inside `setupPeer`, i.e. strictly after
     * `ensureFactory`, so "first non-null track" is a safe trigger.
     */
    fun initialise(sharedContext: EglBase.Context, surfaceTexture: SurfaceTexture) {
        if (initialised) return
        synchronized(lock) {
            texture = surfaceTexture
            rotatedWidth = 0
            rotatedHeight = 0
            rotation = -1
        }
        init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        createEglSurface(surfaceTexture)
        initialised = true
    }

    override fun onFrame(frame: VideoFrame) {
        reportEvents(frame)
        super.onFrame(frame)
    }

    /**
     * Report the *rotated* dimensions — a 1280×720 frame with `rotation == 90`
     * is 720×1280 on screen, and it is the on-screen figure the Dart side needs
     * to pick an `AspectRatio`. Also resizes the backing [SurfaceTexture], or
     * the composited texture would be stretched to whatever default buffer size
     * it was created with.
     */
    private fun reportEvents(frame: VideoFrame) {
        val width = frame.rotatedWidth
        val height = frame.rotatedHeight
        val changed: Boolean
        synchronized(lock) {
            changed = width != rotatedWidth || height != rotatedHeight || frame.rotation != rotation
            if (changed) {
                rotatedWidth = width
                rotatedHeight = height
                rotation = frame.rotation
                texture?.setDefaultBufferSize(width, height)
            }
        }
        if (changed) onResolution(width, height, frame.rotation)
    }

    /**
     * Tear down EGL and stop drawing. Idempotent — the channel calls it from
     * both `disposeRenderer` and `onDetachedFromEngine`, and those can race a
     * hot restart.
     */
    fun shutdown() {
        if (initialised) {
            initialised = false
            release()
        }
        synchronized(lock) { texture = null }
    }
}

/**
 * Aspect/rotation reporting contract, kept as a named type so a future
 * PlatformView fallback ([CallVideoPlatformView]) can implement the same one.
 */
internal typealias RendererEvents = RendererCommon.RendererEvents
