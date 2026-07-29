package mullu.comrade.channel

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import mullu.comrade.call.CallManager
import org.webrtc.VideoTrack

/**
 * The answer to "a `VideoTrack` cannot cross a `MethodChannel`".
 *
 * Dart asks for a renderer bound to `local` or `remote`; it gets back an
 * integer texture id and renders it with a `Texture` widget. This class
 * observes `CallManager.localVideo` / `CallManager.remoteVideo` and
 * adds/removes the track's sink as those flows emit. **The track itself never
 * leaves Kotlin.** See `../PLATFORM_CHANNELS.md` §6 for why this (option b) was
 * chosen over migrating `CallManager` onto `flutter_webrtc` (option a).
 *
 * ## Lifecycle — the one thing that legitimately dies with the engine
 *
 * `SurfaceTextureEntry`s belong to the engine's [TextureRegistry].
 * [dispose] (called from `onDetachedFromEngine`) releases every renderer. The
 * *call* is untouched by that: audio, signalling, `CallService` and its
 * notification all keep running, because they are `CallManager`'s and the
 * service's, not this class's. There is no visible surface to draw to while the
 * engine is detached, so nothing is lost. On reattach Dart re-creates its
 * renderers and the sinks re-attach from the still-live flows.
 */
internal class CallVideoChannel(
    messenger: BinaryMessenger,
    private val textures: TextureRegistry,
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    private companion object {
        const val SOURCE_LOCAL = "local"
        const val SOURCE_REMOTE = "remote"
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val methods = MethodChannel(messenger, ChannelNames.CALL_VIDEO)
        .also { it.setMethodCallHandler(this) }
    private val events = EventChannel(messenger, ChannelNames.CALL_VIDEO_EVENTS)
        .also { it.setStreamHandler(this) }

    private var sink: EventChannel.EventSink? = null

    private var nextId = 1
    private val renderers = HashMap<Int, Binding>()

    /** One renderer: its texture, its EGL sink, and the flow subscription feeding it. */
    private inner class Binding(
        val id: Int,
        val source: String,
        val entry: TextureRegistry.SurfaceTextureEntry,
        val renderer: TextureVideoRenderer,
    ) {
        var job: Job? = null
        var attached: VideoTrack? = null

        fun detachTrack() {
            attached?.let { runCatching { it.removeSink(renderer) } }
            attached = null
        }

        fun release() {
            job?.cancel()
            job = null
            detachTrack()
            renderer.shutdown()
            entry.release()
        }
    }

    // ── Control ──────────────────────────────────────────────────────────────

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "createRenderer" -> createRenderer(call, result)
                "setMirror" -> {
                    val binding = renderers[call.requireInt("rendererId")]
                    if (binding == null) {
                        result.postError(ErrorCodes.NOT_FOUND, "no such renderer")
                    } else {
                        binding.renderer.setMirror(call.requireBool("mirror"))
                        result.postSuccess()
                    }
                }
                "disposeRenderer" -> {
                    renderers.remove(call.requireInt("rendererId"))?.release()
                    // Disposing an already-gone renderer is a no-op, not an
                    // error: a widget dispose can legitimately race the engine
                    // detach that already released everything.
                    result.postSuccess()
                }
                else -> result.postNotImplemented()
            }
        } catch (e: MissingArgument) {
            result.postError(ErrorCodes.BAD_ARGUMENT, e.message)
        }
    }

    private fun createRenderer(call: MethodCall, result: MethodChannel.Result) {
        val source = call.requireString("source")
        if (source != SOURCE_LOCAL && source != SOURCE_REMOTE) {
            result.postError(ErrorCodes.BAD_ARGUMENT, "unknown video source: $source")
            return
        }
        val mirror = call.argument<Boolean>("mirror") ?: false

        val id = nextId++
        val entry = textures.createSurfaceTexture()
        val renderer = TextureVideoRenderer("comrade-$source-$id") { w, h, rot ->
            // Fired on a WebRTC decode thread — hop to main before the sink.
            main.post { sink?.success(mapOf("rendererId" to id, "width" to w, "height" to h, "rotation" to rot)) }
        }
        renderer.setMirror(mirror)

        val binding = Binding(id, source, entry, renderer)
        renderers[id] = binding
        binding.job = scope.launch { observe(binding, flowFor(source)) }

        result.postSuccess(mapOf("rendererId" to id, "textureId" to entry.id()))
    }

    private fun flowFor(source: String): StateFlow<VideoTrack?> =
        if (source == SOURCE_LOCAL) CallManager.localVideo else CallManager.remoteVideo

    /**
     * Swap the sunk track as the flow emits, initialising EGL lazily on the
     * first non-null track.
     *
     * The laziness is required, not an optimisation: `CallManager.eglBaseContext`
     * is null until `ensureFactory` runs. It is also *safe*, because both video
     * flows are only ever populated inside `setupPeer`, which runs after
     * `ensureFactory` — so a non-null track implies a non-null context.
     * The `?: return@collect` is a real guard rather than dead code only in the
     * sense that a future refactor could break that ordering; if it ever fires,
     * the renderer stays black instead of crashing.
     */
    private suspend fun observe(binding: Binding, flow: StateFlow<VideoTrack?>) {
        flow.collect { track ->
            binding.detachTrack()
            if (track == null) return@collect
            if (!binding.renderer.initialised) {
                val egl = CallManager.eglBaseContext ?: return@collect
                binding.renderer.initialise(egl, binding.entry.surfaceTexture())
            }
            runCatching { track.addSink(binding.renderer) }
                .onSuccess { binding.attached = track }
        }
    }

    // ── Frame-geometry events ────────────────────────────────────────────────

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        sink = events
    }

    override fun onCancel(arguments: Any?) {
        sink = null
    }

    fun dispose() {
        renderers.values.forEach { it.release() }
        renderers.clear()
        sink = null
        methods.setMethodCallHandler(null)
        events.setStreamHandler(null)
        scope.cancel()
    }
}
