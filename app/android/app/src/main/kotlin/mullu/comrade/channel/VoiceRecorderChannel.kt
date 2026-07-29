package mullu.comrade.channel

import android.Manifest
import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import mullu.comrade.media.VoiceRecorder

/**
 * Push-to-talk voice notes.
 *
 * `VoiceRecorder` is documented as *not thread-safe* — "drive it from a single
 * UI gesture (press to start, release to stop)" (`VoiceRecorder.kt:28-30`) — so
 * this handler keeps one instance and does all its work inline on the platform
 * thread. `MethodChannel` handlers are already serialised there, which gives
 * the single-driver discipline for free; dispatching to IO would break it.
 *
 * The `MicHolder.VOICE_NOTE` acquire/release around the recording is
 * `VoiceRecorder`'s own (`:55`, `:124`, `:137`) and is not exposed here — see
 * [WakeWordChannel] for why that matters.
 *
 * **Returns a path, never bytes.** The clip is a plaintext voice note in
 * `cacheDir`; the caller must delete it the moment the encrypted send resolves
 * (AUDIT S-4 — the plaintext must not outlive the send). Handing base64 over a
 * channel would put a second plaintext copy in the Dart heap with no deletion
 * discipline attached to it at all.
 */
internal class VoiceRecorderChannel(
    messenger: BinaryMessenger,
    appContext: Context,
    private val permissions: PermissionBridge,
) : MethodChannel.MethodCallHandler {

    private val recorder = VoiceRecorder(appContext)

    private val methods = MethodChannel(messenger, ChannelNames.RECORDER)
        .also { it.setMethodCallHandler(this) }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "start" -> start(result)
            "stop" -> {
                val file = recorder.stop()
                result.postSuccess(
                    // null when the press was too short to be a note (< 500 ms)
                    // — an accidental tap, not an error. The file is already
                    // deleted by then.
                    file?.let { mapOf("path" to it.absolutePath, "mimeType" to VoiceRecorder.MIME_TYPE) },
                )
            }
            "cancel" -> {
                recorder.cancel()
                result.postSuccess()
            }
            "isRecording" -> result.postSuccess(recorder.isRecording)
            else -> result.postNotImplemented()
        }
    }

    private fun start(result: MethodChannel.Result) {
        if (permissions.activity == null) {
            result.postError(ErrorCodes.NO_ACTIVITY, "no Activity attached to request RECORD_AUDIO")
            return
        }
        permissions.ensure(listOf(Manifest.permission.RECORD_AUDIO)) { granted, denied ->
            if (!granted) {
                result.postError(ErrorCodes.PERMISSION_DENIED, "microphone permission denied", denied)
                return@ensure
            }
            // false means the recorder could not be set up (mic busy, no mic).
            // Nothing to clean up on that path — no file, no partial recorder.
            result.postSuccess(recorder.start())
        }
    }

    /**
     * An engine detaching mid-recording must not leave the mic open and the
     * wake-word recogniser paused forever: `cancel()` releases both and deletes
     * the partial file.
     */
    fun dispose() {
        if (recorder.isRecording) recorder.cancel()
        methods.setMethodCallHandler(null)
    }
}
