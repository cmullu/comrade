package mullu.comrade.together

import android.content.Context
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer

/**
 * The picture of what we are playing, on its way to the other person.
 *
 * `docs/TOGETHER.md` §15. WebRTC takes video from a [VideoCapturer]; a
 * `MediaPlayer` gives video to a [Surface]. This is the joint: a
 * [SurfaceTextureHelper] owns a `SurfaceTexture`, [outputSurface] wraps it for
 * the player to decode into, and every frame the player draws arrives on the
 * helper's thread and goes straight to the [CapturerObserver].
 *
 * There is no capture *loop* here, and that is the point: nothing polls, nothing
 * copies, and the decoder's own cadence is the capture rate. A film that pauses
 * simply stops producing frames.
 *
 * ## The leader still has to see it
 *
 * A `MediaPlayer` draws into one surface, and this takes it — so the sender
 * cannot also point it at a `SurfaceView` on their own screen. The answer is
 * not a second output but the track this feeds: the sender renders their **own
 * outgoing `VideoTrack`** with a `SurfaceViewRenderer`, exactly as the call
 * screen renders local camera video, and the receiver renders the remote one.
 * Both sides then draw a `VideoTrack` and there is one picture path instead of
 * two.
 *
 * ## `isScreencast` is false, deliberately
 *
 * WebRTC treats a screencast differently: it assumes mostly-static content and
 * degrades frame rate before resolution. A film is the opposite case — motion is
 * the content — so it must degrade resolution and keep the frame rate, which is
 * what `false` selects. Getting this backwards produces a sharp slideshow.
 */
class PlayerVideoCapturer : VideoCapturer {

    private var helper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null
    private var surface: Surface? = null

    @Volatile
    private var capturing = false

    /**
     * Where the player should decode, or `null` before [startCapture].
     *
     * Handed to `TogetherPlayer.attachSurface` in place of the screen's own
     * surface. The player holds it across a rebuild of the UI, which is the
     * same lifetime rule the on-screen surface already has.
     */
    val outputSurface: Surface? get() = surface

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        applicationContext: Context,
        capturerObserver: CapturerObserver,
    ) {
        helper = surfaceTextureHelper
        observer = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        val h = helper ?: return
        val o = observer ?: return
        if (capturing) return
        // The texture has to be sized before frames arrive or the first ones
        // come through at whatever the default was — visible as a picture that
        // snaps size a moment after playback starts.
        h.setTextureSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        h.startListening { frame -> o.onFrameCaptured(frame) }
        surface = Surface(h.surfaceTexture)
        capturing = true
        o.onCapturerStarted(true)
    }

    /**
     * The video size the decoder actually reported.
     *
     * `MediaPlayer` only knows its dimensions after it has opened the file, so
     * the capture starts at a guess and is corrected here — which is why
     * `TogetherPlayer.Listener.onVideoSize` exists at all.
     */
    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        helper?.setTextureSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    override fun stopCapture() {
        if (!capturing) return
        capturing = false
        helper?.stopListening()
        observer?.onCapturerStopped()
        // Released before the helper's texture goes: a Surface outliving the
        // SurfaceTexture it wraps is the same use-after-free in the media
        // server that `TogetherPlayer.attachSurface(null)` exists to avoid.
        runCatching { surface?.release() }
        surface = null
    }

    override fun dispose() {
        runCatching { stopCapture() }
        // The helper is **not** disposed here: it is created by whoever built
        // this capturer and may outlive it, exactly as `CallManager` reuses one
        // helper across a camera-to-screen-share swap.
        helper = null
        observer = null
    }

    /** False: this is motion video, not a screen. See the class comment. */
    override fun isScreencast(): Boolean = false
}
