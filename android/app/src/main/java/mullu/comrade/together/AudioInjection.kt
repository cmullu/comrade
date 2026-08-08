package mullu.comrade.together

import java.nio.ByteBuffer
import org.webrtc.ExternalAudioProcessingFactory

/**
 * Where a session's media audio joins the outgoing stream — **after** the echo
 * canceller, the noise suppressor and the gain control, and therefore without
 * any of them touching it.
 *
 * `docs/TOGETHER.md` §15. This is the second design: the first one wrote into
 * WebRTC's *record* buffer through `JavaAudioDeviceModule.AudioBufferCallback`,
 * which sits in `WebRtcAudioRecord`'s read loop — **before** the audio
 * processing module. That would have fed a film through machinery built for
 * speech, and the results are not subtle: the noise suppressor treats sustained
 * music as noise and gates it, and the automatic gain control pumps the
 * dynamics, lifting quiet scenes and flattening loud ones. The mistake is easy
 * to make and hard to hear as a *bug* rather than as "the stream sounds bad".
 *
 * `ExternalAudioProcessingFactory.setCapturePostProcessing` is the right seam:
 * it runs on the capture path after the whole processing chain and before the
 * encoder. So the two halves each get what they need —
 *
 * - **the microphone keeps every calling feature**, because it has already been
 *   through them by the time this is called, and
 * - **the media audio is untouched**, because it is added here and nothing
 *   downstream processes it.
 *
 * ## Failing to silence, never to noise
 *
 * The buffer's layout is the one thing here that cannot be checked in this
 * sandbox: libwebrtc's `AudioBuffer` is float, channel-major, on the int16
 * scale, but the exact shape handed across this JNI boundary is a property of
 * the fork. So [process] *derives* the sample width from the buffer's own size
 * and **leaves the buffer completely alone** when it does not recognise it.
 *
 * That direction is deliberate. A wrong guess that writes produces full-scale
 * noise directly into somebody's ear; a wrong guess that declines produces a
 * stream with no film audio, which is a bug report rather than an injury.
 */
object AudioInjection : ExternalAudioProcessingFactory.AudioProcessing {

    /**
     * How long one processing frame is. WebRTC's APM works in 10 ms frames
     * throughout, which is what makes the sample width derivable at all: the
     * frame's sample count is fixed by the rate, so the only free variable in
     * the buffer's size is how many bytes each sample takes.
     */
    private const val FRAME_MS = 10

    @Volatile
    private var active: PlaybackCapture? = null

    /** Whether anything is currently adding to or replacing the microphone. */
    val isActive: Boolean get() = active != null

    /**
     * Route this stage through `capture`, or `null` to leave the microphone
     * alone.
     *
     * A session ending **must** call this with `null`: a stale capture left
     * installed would go on mixing a released player's audio into the next call.
     */
    fun install(capture: PlaybackCapture?) {
        active = capture
    }

    override fun initialize(sampleRateHz: Int, channels: Int) = Unit

    override fun reset(newRate: Int) = Unit

    /**
     * Add what we are playing to what the microphone (already processed) is
     * sending, or replace it.
     *
     * Called on WebRTC's capture thread, on the audio deadline, once per 10 ms
     * frame. It does no allocation of its own beyond the frame it asks the
     * capture for, and takes no lock the capture's own reader can be holding.
     */
    override fun process(channels: Int, sampleRate: Int, buffer: ByteBuffer) {
        val capture = active ?: return
        if (channels <= 0 || sampleRate <= 0) return

        val samplesPerChannel = sampleRate * FRAME_MS / 1000
        if (samplesPerChannel <= 0) return
        val bytesPerSample = buffer.capacity() / (samplesPerChannel * channels)
        // 4 is the float layout libwebrtc's APM uses internally. Anything else
        // is a shape this build does not recognise, and the buffer is left as it
        // is — see the class comment on why that direction and not the other.
        if (bytesPerSample != 4) return

        capture.mixIntoProcessed(buffer, samplesPerChannel, channels)
    }
}
