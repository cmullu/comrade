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
 * - **`ExternalAudioProcessingFactory.setCapturePostProcessing`**, which
 *   `io.github.webrtc-sdk:android` adds and upstream libwebrtc does not. It runs
 *   on the capture path *after* the whole audio processing chain, which is what
 *   lets the microphone keep every calling feature while the media audio is
 *   touched by none of them. The dependency being that fork is load-bearing
 *   rather than incidental — swapping it takes this with it.
 *
 *   The first cut used `JavaAudioDeviceModule.AudioBufferCallback` instead, and
 *   it was wrong: that sits in `WebRtcAudioRecord`'s read loop, *before* the
 *   processing, so a film would have gone through a noise suppressor that gates
 *   sustained music and a gain control that pumps its dynamics.
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
 *
 * ## Talking over it
 *
 * A session has **one** audio track, so the sender's voice and what they are
 * playing arrive as one thing: [micEnabled] decides whether the microphone is
 * summed in ([PcmMix]) or overwritten. Mixing is the default shape rather than
 * an addition, because watching something together and not being able to say
 * anything about it is not the feature.
 *
 * **One honest limit, and it is not fixable in software here.** If the sender
 * has the microphone on and is listening on speakers, the other person hears
 * the film twice — once injected cleanly, once through the sender's room, a
 * fraction of a second apart. WebRTC's echo canceller does not help: it cancels
 * what *it* played out, and the film goes through `MediaPlayer`, which it knows
 * nothing about. Headphones are the answer, and the UI should say so rather
 * than let it be discovered.
 */
class PlaybackCapture {

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
     * Whether what we are playing should reach the other person at all.
     *
     * Separate from [running] so a session can hold the capture open across a
     * pause without tearing down an `AudioRecord` and asking for consent again.
     */
    @Volatile
    var injecting: Boolean = false

    /**
     * Whether the sender's voice goes out **alongside** what they are playing.
     *
     * This is the point of the whole feature — people watch things together to
     * talk about them — and it is why [onBuffer] mixes rather than replaces.
     * Off means the microphone's samples are overwritten and nothing of the
     * sender's room is sent; on means the two are summed, with saturation, into
     * the one audio track a session has.
     *
     * **Off by default**, because a session that starts with an open microphone
     * is one that has decided something about a room it cannot see.
     */
    @Volatile
    var micEnabled: Boolean = false

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
     * Add what we are playing into a frame the microphone has already been
     * processed into, or replace it.
     *
     * Called from [AudioInjection] on WebRTC's capture thread, **after** the
     * echo canceller, noise suppressor and gain control — which is the whole
     * point: the voice keeps every calling feature and the media audio is
     * touched by none of them (`docs/TOGETHER.md` §15).
     *
     * The buffer is float and channel-major: `samplesPerChannel` floats for the
     * left, then the same again for the right. Our capture is mono, so each
     * channel gets the same samples, which is exact — where narrowing a stereo
     * source to mono would be a judgement.
     */
    fun mixIntoProcessed(buffer: ByteBuffer, samplesPerChannel: Int, channels: Int) {
        if (!injecting || samplesPerChannel <= 0) return
        val wantBytes = samplesPerChannel * 2
        val mono = ByteArray(wantBytes)
        val got = synchronized(lock) { ring.read(mono, wantBytes) }
        // Underrun leaves the frame as it is rather than writing part of one:
        // with the microphone on that is a moment of pure voice, and with it off
        // a moment of silence. Both are better than a fragment of audio followed
        // by whatever was in the buffer.
        if (got == 0) return

        for (channel in 0 until channels) {
            val offset = channel * samplesPerChannel
            if (micEnabled) {
                // Both: the processed voice, and the film added to it.
                PcmMix.mixIntoFloat(buffer, mono, wantBytes, offset)
            } else {
                // The film alone. The processed microphone is overwritten, so
                // nothing of the sender's room leaves the device.
                PcmMix.replaceIntoFloat(buffer, mono, wantBytes, offset)
            }
        }
    }

    private companion object {
        const val TAG = "PlaybackCapture"
    }
}
