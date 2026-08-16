package mullu.comrade.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import mullu.comrade.voice.MicHolder
import mullu.comrade.voice.WakeWordService

/**
 * A minimal push-to-talk audio recorder for chat voice notes, built on the
 * platform [MediaRecorder].
 *
 * The output is AAC in an ADTS stream (`OutputFormat.AAC_ADTS` + `Encoder.AAC`):
 * a single, seekable elementary stream that [android.media.MediaPlayer] plays
 * back directly (see `MediaAttachment.InlineAudio`) and whose `audio/aac` MIME
 * type the media pipeline already knows how to name on disk. Bit rate and
 * sample rate are tuned for speech, not music — small clips that upload fast
 * over a relay yet stay perfectly intelligible.
 *
 * The clip is written to a throwaway file in the app's [Context.getCacheDir]
 * so it never touches shared/backed-up storage; the caller reads the bytes and
 * is expected to delete the file the moment the encrypted send resolves (the
 * plaintext voice note must not outlive the send — mirrors the decrypt-cache
 * cleanup for the receive side, AUDIT S-4).
 *
 * Not thread-safe: drive it from a single UI gesture (press to [start], release
 * to [stop]). One instance records one clip at a time.
 */
class VoiceRecorder(
    private val context: Context,
    private val profile: Profile = Profile.ChatVoiceNote,
) {

    /**
     * What container a recorder writes, where it writes it, and what the result
     * is called.
     *
     * Two, because the two callers want genuinely different things out of the
     * same encoder — see each profile. Everything else about recording (the
     * mic, the bit rate, the contention with the wake word) is identical, which
     * is why this is a parameter rather than a second recorder class.
     */
    enum class Profile(
        internal val subdirectory: String,
        internal val namePrefix: String,
        internal val extension: String,
        /** The MIME type of the clips this profile produces. */
        val mimeType: String,
    ) {
        /**
         * Chat voice notes: AAC in an ADTS stream, in `cacheDir/voice-notes`.
         *
         * A raw elementary stream, which is what suits a clip that is recorded,
         * encrypted, sent and played once. It carries no duration header and
         * cannot be seeked accurately, and neither matters for a bubble that
         * plays start to finish.
         */
        ChatVoiceNote("voice-notes", "vn-", "aac", "audio/aac"),

        /**
         * Journal voice entries: AAC inside MPEG-4, in the journal's capture
         * directory.
         *
         * A kept recording that gets listed with its length and scrubbed back
         * and forth, so it needs the duration and seek index that MPEG-4 writes
         * and ADTS does not — see `journal/JournalRecordings.kt`'s
         * `JOURNAL_AUDIO_MIME`.
         */
        JournalEntry("journal-capture", "capture-", "m4a", "audio/mp4"),
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0L

    /** Whether a recording is currently in progress. */
    val isRecording: Boolean get() = recorder != null

    /**
     * How long the current recording has been running, in milliseconds, or `0`
     * when nothing is recording.
     *
     * Drives the elapsed counter the journal shows while a voice entry is being
     * spoken, and is the fallback length when a container will not say how long
     * it is. Read it as often as a UI wants — it is two field reads.
     */
    val elapsedMs: Long
        get() = if (recorder == null) 0L else System.currentTimeMillis() - startedAtMs

    /**
     * How long the clip [stop] last returned ran for, in milliseconds.
     *
     * A stopwatch reading, not a container's: it counts from the tap, so it is
     * a few tens of milliseconds longer than the audio actually captured. Only
     * good enough as a fallback when the file itself will not say — which is
     * exactly how `JournalRecordingStore.adopt` uses it.
     */
    var lastClipMs: Long = 0L
        private set

    /** The MIME type of the clips this instance produces, from its profile. */
    val mimeType: String get() = profile.mimeType

    /**
     * Begin capturing from the microphone into a fresh cache file.
     *
     * Returns `true` once capture has started, or `false` if the recorder could
     * not be set up (device without a mic, `RECORD_AUDIO` not granted, encoder
     * busy). A `false` return leaves nothing to clean up — no file is created
     * and no partial recorder is left running.
     */
    fun start(): Boolean {
        if (recorder != null) return false // already recording

        // Release the wake-word recogniser's hold on the mic first — two
        // consumers fighting over MediaRecorder.AudioSource.MIC is exactly
        // the kind of contention that used to make this fail with a busy
        // device. Restored in stop()/cancel(), and on a failed start below.
        WakeWordService.pause(MicHolder.VOICE_NOTE)

        val dir = File(context.cacheDir, profile.subdirectory).apply { mkdirs() }
        // A non-colliding name. For a chat note the file is short-lived and
        // deleted after send; for a journal entry it is moved into the journal's
        // own folder and renamed there (JournalRecordingStore.adopt).
        val file = File(dir, "${profile.namePrefix}${System.nanoTime()}.${profile.extension}")

        val rec = newRecorder()
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(
                when (profile) {
                    Profile.ChatVoiceNote -> MediaRecorder.OutputFormat.AAC_ADTS
                    Profile.JournalEntry -> MediaRecorder.OutputFormat.MPEG_4
                },
            )
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // Speech-tuned: mono, 32 kbps AAC at 44.1 kHz. Small and clear.
            rec.setAudioChannels(1)
            rec.setAudioEncodingBitRate(32_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            outputFile = file
            startedAtMs = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            // prepare()/start() throw IllegalState/IOException/RuntimeException
            // on a busy or mic-less device. Roll back cleanly.
            Log.w(TAG, "Could not start voice recording", e)
            runCatching { rec.release() }
            file.delete()
            recorder = null
            outputFile = null
            WakeWordService.resume(MicHolder.VOICE_NOTE) // never got the mic — give it back
            false
        }
    }

    /**
     * Stop capturing and return the recorded clip, or `null` if the recording
     * was too short to be usable.
     *
     * A [MediaRecorder] that is stopped almost immediately after starting has
     * captured no frames and throws from `stop()`; that (an accidental tap
     * rather than a held press) is treated as "no voice note" — the file is
     * deleted and `null` returned. On success the caller owns the returned file
     * and must delete it (chat) or move it somewhere it belongs (journal).
     *
     * [lastClipMs] is set from the same stopwatch before this returns, because
     * [elapsedMs] necessarily reads zero once the recorder is released and the
     * journal needs the length *after* the stop, not during it.
     */
    fun stop(): File? {
        val rec = recorder ?: return null
        val file = outputFile
        val heldMs = System.currentTimeMillis() - startedAtMs
        lastClipMs = heldMs
        recorder = null
        outputFile = null

        return try {
            rec.stop()
            rec.release()
            // Guard against a too-brief press even when stop() didn't throw.
            if (file != null && file.exists() && file.length() > 0 && heldMs >= MIN_CLIP_MS) {
                file
            } else {
                file?.delete()
                null
            }
        } catch (e: RuntimeException) {
            // stop() throws when no valid data was captured (very short press).
            Log.d(TAG, "Voice recording too short; discarding", e)
            runCatching { rec.release() }
            file?.delete()
            null
        } finally {
            WakeWordService.resume(MicHolder.VOICE_NOTE)
        }
    }

    /** Abort an in-progress recording and delete its partial file. */
    fun cancel() {
        val rec = recorder ?: return
        recorder = null
        val file = outputFile
        outputFile = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
        file?.delete()
        WakeWordService.resume(MicHolder.VOICE_NOTE)
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    companion object {
        private const val TAG = "VoiceRecorder"

        /**
         * The MIME type of a **chat voice note**, which is what this class
         * produced when it had only one output format.
         *
         * Kept because both callers of it record chat notes and are correct.
         * New code should read [Profile.mimeType] off the profile it chose — a
         * journal entry is `audio/mp4`, not this, and a caller that reaches for
         * the constant out of habit would label an M4A as an ADTS stream.
         */
        const val MIME_TYPE = "audio/aac"

        /** Shorter presses than this are treated as accidental taps, not notes. */
        private const val MIN_CLIP_MS = 500L
    }
}
