package mullu.comrade.channel

import android.app.Activity
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import mullu.comrade.call.PipController

/**
 * Dart's view of picture-in-picture.
 *
 * A facade over [PipController], which is where the behaviour lives — the
 * aspect-ratio clamp, the API-31 auto-enter split, "is a video call even live".
 * That code is shared with the Compose frontend and holds no Flutter types, so
 * both apps float a call into the same window under the same rules; this class
 * only translates.
 *
 * ## What is *not* here
 *
 * Entering PiP when the user leaves the app. That is `MainActivity`'s
 * `onUserLeaveHint` plus the platform's own auto-enter flag, because the window
 * has to enter PiP **before** the Activity stops — a round trip to Dart does
 * not reliably fit in that window, and `CallManager` is process-scoped, so the
 * native path works with the engine detached. Dart learns about it from
 * [ChannelNames.PIP_STATE] like any other transition.
 */
internal class PipChannel(
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler {

    private val methods = MethodChannel(messenger, ChannelNames.PIP)
        .also { it.setMethodCallHandler(this) }

    /**
     * Mirrors [PipController.inPip] — a `StateFlow`, so Dart gets the current
     * value on subscribe and is correct on its first frame even if it attached
     * while the call was already floating.
     */
    private val state = EventChannelRelay(messenger, ChannelNames.PIP_STATE) {
        PipController.inPip
    }

    /** Set by `ComradePlugin` on attach/detach; null while headless. */
    var activity: Activity? = null
        set(value) {
            val previous = field
            field = value
            when {
                value != null -> PipController.attachActivity(value)
                previous != null -> PipController.detachActivity(previous)
            }
        }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isSupported" -> result.success(PipController.isSupported())
            "enter" -> {
                val width = (call.argument<Number>("aspectWidth"))?.toInt() ?: 9
                val height = (call.argument<Number>("aspectHeight"))?.toInt() ?: 16
                result.success(PipController.enter(width, height))
            }
            "close" -> {
                PipController.close()
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    fun dispose() {
        methods.setMethodCallHandler(null)
        state.dispose()
    }
}
