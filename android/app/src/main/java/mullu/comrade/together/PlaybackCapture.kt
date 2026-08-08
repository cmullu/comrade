package mullu.comrade.together

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The sound of what Comrade is playing, on its way to the other person.
 *
 * `docs/TOGETHER.md` §15. Streaming a session means sending the picture *and*
 * the sound, and the sound is the half that does not fall out of the existing
 * call machinery: libwebrtc's Android audio path captures from the
 * **microphone**, so a film shared with the pieces as they stand arrives as a
 * picture with the noise of the sender's room over it.
 *
 * Two things make this possible, and both were checked against the artefacts
 * rather than assumed:
 *
 * - **`JavaAudioDeviceModule.AudioBufferCallback`**, which
 *   `io.github.webrtc-sdk:android` adds and upstream libwebrtc does not. It is
 *   handed the record buffer *before* the encoder sees it, so writing into that
 *   buffer replaces the microphone. The dependency being that fork is
 *   load-bearing rather than incidental — swapping it takes this with it.
 * - **`AudioPlaybackCapture` with [AudioPlaybackCaptureConfiguration.Builder.addMatchingUid]**
 *   set to our own uid. An app may always capture *itself*, whatever any other
 *   app's capture policy says, so this needs no cooperation from anybody and
 *   works for the one case §15 allows: a file the sender holds and is playing.
 *   Widening the configuration to other uids is §15's "screen capture" and
 *   brings the whole `FLAG_SECURE` / `ALLOW_CAPTURE_BY_NONE` argument with it.
 *
 * ## What this is not wired into yet
 *
 * The [JavaAudioDeviceModule] this installs on has to be handed to
 * `PeerConnectionFactory.builder().setAudioDeviceModule(...)`, and that factory
 * is **shared with calls** (`CallManager.sharedFactory`). Replacing the default
 * audio device module changes how every call captures — echo canceller, noise
 * suppressor, audio source and sample rate all come from it — so that is a
 * deliberate change to the area `.claude/rules/android.md` names as the most
 * bug-prone in the repo, and it is not made as a side effect of adding this
 * file. This component is complete and inert until something installs it.
 *
 * ## Threading
 *
 * Two threads meet here and neither may wait for the other: [readLoop] is ours
 * and blocks in `AudioRecord.read`, while [onBuffer] belongs to WebRTC's record
 * thread and runs on the audio deadline. So they share one lock, held only for
 * an array copy, and an underrun writes **silence** rather than blocking or
 * repeating — a late buffer is a click, a repeated one is a stutter that sounds
 * like a fault in the file.
 */
class PlaybackCapture : JavaAudioDeviceModule.AudioBufferCallback {

    /**
     * WebRTC's native rate, and what the capture is configured at.
     *
     * Fixed rather than negotiated because [onBuffer] only learns the rate it
     * wants at call time, on the audio deadline, which is much too late to
     * reconfigure an `AudioRecord` — and far too late to start resampling.
     */
    private val captureRateHz = 48_000

    /**
     * Mono. The record buffer WebRTC hands us is whatever it asked for, and a
     * mono source can be written into a stereo buffer by duplication, which is
     * cheap and correct; the reverse needs a downmix this does not do.
     */
    private val captureChannels = 1

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /** Guards [ring] only, and is never held across a read or a write to WebRTC. */
    private val lock = Any()
    private var ring = PcmRing(0)

    /**
     * Whether captured audio should replace the microphone right now.
     *
     * Separate from [running] so a session can hold the capture open across a
     * pause without tearing down an `AudioRecord` and asking for consent again.
     */
    @Volatile
    var injecting: Boolean = false

    /**
     * Begin capturing this app's own playback.
     *
     * @param projection consent from `MediaProjectionManager.createScreenCaptureIntent`.
     *   Required even to capture ourselves — there is no quieter API for it, and
     *   the dialog it implies is a real cost to state in the UI rather than a
     *   detail.
     * @return whether capture started. `false` is a first-class answer: below
     *   API 29 there is no playback capture at all, and a device may refuse the
     *   record.
     */
    @SuppressLint("MissingPermission") // playback capture of our own uid needs no RECORD_AUDIO grant path of its own; the projection is the consent
    fun start(projection: MediaProjection): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (running) return true
        val config = runCatching {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                // **Ourselves, and nobody else.** This is the line that keeps
                // §15's boundary in the code rather than in a comment: no other
                // app's audio is reachable through this object, so widening it
                // is an edit somebody has to make on purpose.
                .addMatchingUid(Process.myUid())
                .build()
        }.getOrElse {
            Log.w(TAG, "could not configure playback capture", it)
            return false
        }

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(captureRateHz)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBytes = AudioRecord.getMinBufferSize(
            captureRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) return false

        val r = runCatching {
            AudioRecord.Builder()
                .setAudioFormat(format)
                // Four times the minimum: the read loop is an ordinary thread
                // and a scheduling hiccup here costs dropped audio the peer
                // hears, unlike a hiccup on our own playback which costs
                // nothing at all.
                .setBufferSizeInBytes(minBytes * 4)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        }.getOrElse {
            Log.w(TAG, "could not open the playback record", it)
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { r.release() }
            return false
        }

        synchronized(lock) {
            // A quarter of a second of slack. Enough to ride out a scheduling
            // gap, short enough that the audio the peer hears is never a
            // noticeable amount behind the picture — this buffer is latency,
            // and latency here is lip-sync error.
            ring = PcmRing(captureRateHz * 2 * captureChannels / 4)
        }
        record = r
        running = true
        runCatching { r.startRecording() }.onFailure {
            Log.w(TAG, "could not start the playback record", it)
            stop()
            return false
        }
        thread = Thread({ readLoop(r, minBytes) }, "PlaybackCapture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    /** Stop capturing and release the record. Safe to call twice. */
    fun stop() {
        running = false
        injecting = false
        thread?.let { runCatching { it.join(500) } }
        thread = null
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
        synchronized(lock) { ring.clear() }
    }

    private fun readLoop(r: AudioRecord, chunkBytes: Int) {
        val buf = ByteArray(chunkBytes)
        while (running) {
            val n = runCatching { r.read(buf, 0, buf.size) }.getOrDefault(-1)
            if (n <= 0) {
                // A negative read is an error and a zero read is a stopped
                // record; neither is worth spinning on.
                if (n < 0) break else continue
            }
            synchronized(lock) { ring.write(buf, n) }
        }
    }

    /**
     * WebRTC is about to send `buffer`. Replace it with what we are playing.
     *
     * The return value is the capture timestamp WebRTC will stamp the frame
     * with; handing back the one we were given is right, because the audio in
     * the buffer is being sent *now* whatever its origin.
     *
     * **Passes through untouched** unless [injecting] is set and the format is
     * one we actually have. A mismatch is left alone rather than approximated:
     * writing 48 kHz mono into a buffer that wants something else produces
     * noise, and noise is worse than the microphone.
     */
    override fun onBuffer(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
        captureTimeNs: Long,
    ): Long {
        if (!injecting || bytesRead <= 0) return captureTimeNs
        if (audioFormat != AudioFormat.ENCODING_PCM_16BIT || sampleRate != captureRateHz) {
            return captureTimeNs
        }
        if (channelCount != 1 && channelCount != 2) return captureTimeNs

        // Mono source, so a stereo buffer takes each sample twice.
        val wantMono = if (channelCount == 2) bytesRead / 2 else bytesRead
        val mono = ByteArray(wantMono)
        val got = synchronized(lock) { ring.read(mono, wantMono) }
        // Underrun is silence, deliberately: the rest of `mono` is already zero,
        // and a click is honest where repeated audio is a stutter that sounds
        // like a damaged file.
        if (got == 0) return captureTimeNs

        buffer.clear()
        if (channelCount == 2) {
            var i = 0
            while (i + 1 < wantMono) {
                val lo = mono[i]
                val hi = mono[i + 1]
                buffer.put(lo).put(hi).put(lo).put(hi)
                i += 2
            }
        } else {
            buffer.put(mono, 0, wantMono)
        }
        buffer.rewind()
        return captureTimeNs
    }


    private companion object {
        const val TAG = "PlaybackCapture"
    }
}
