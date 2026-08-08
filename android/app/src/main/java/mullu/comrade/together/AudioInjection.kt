package mullu.comrade.together

import java.nio.ByteBuffer
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The one thing installed on the process-wide audio device module, and the
 * reason installing it does not change how calls sound.
 *
 * `docs/TOGETHER.md` §15. WebRTC's `JavaAudioDeviceModule` is built once, with
 * the `PeerConnectionFactory` that every call and every session shares
 * (`CallManager.sharedFactory`). A [PlaybackCapture], by contrast, belongs to
 * one streamed session and comes and goes with it. Something stable has to sit
 * between them, and this is it.
 *
 * **When nothing is streaming this is a no-op, exactly.** [onBuffer] returns the
 * timestamp it was handed and does not touch the buffer, so the microphone
 * reaches the encoder unmodified and a call behaves as it did before any of this
 * existed. That property is the whole argument for it being safe to install
 * process-wide, and it is what the tests pin.
 *
 * `@Volatile` rather than a lock: this is read on WebRTC's record thread, on the
 * audio deadline, once per buffer. A lock here would be a lock in the audio
 * path, taken thousands of times a minute, to guard a single reference write.
 */
object AudioInjection : JavaAudioDeviceModule.AudioBufferCallback {

    @Volatile
    private var active: PlaybackCapture? = null

    /** Whether anything is currently replacing or mixing into the microphone. */
    val isActive: Boolean get() = active != null

    /**
     * Route the record buffer through `capture`, or `null` to hand the
     * microphone back.
     *
     * Idempotent, and safe from any thread. A session ending **must** call this
     * with `null`: a stale capture left installed would keep mixing a released
     * player's audio into the next call.
     */
    fun install(capture: PlaybackCapture?) {
        active = capture
    }

    /**
     * Called by WebRTC for every record buffer, on its own thread.
     *
     * Deliberately holds no reference across the call and does no allocation of
     * its own — whatever work happens is the capture's, and the capture is
     * written to do the least it can here.
     */
    override fun onBuffer(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
        captureTimeNs: Long,
    ): Long {
        val capture = active ?: return captureTimeNs
        return capture.onBuffer(
            buffer,
            audioFormat,
            channelCount,
            sampleRate,
            bytesRead,
            captureTimeNs,
        )
    }
}
