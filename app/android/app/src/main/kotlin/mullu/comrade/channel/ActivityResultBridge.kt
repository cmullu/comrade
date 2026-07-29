package mullu.comrade.channel

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import io.flutter.plugin.common.PluginRegistry
import java.util.concurrent.atomic.AtomicInteger

/**
 * `startActivityForResult` for the channel layer — the sibling of
 * [PermissionBridge], and for the same reason.
 *
 * Picking a file and taking a photo both hand control to another app and come
 * back through the *Activity*, not the engine. Compose got this for free from
 * `rememberLauncherForActivityResult`; a Flutter plugin has to route
 * `onActivityResult` itself, and the request codes of everything that does so
 * must not collide — hence one shared bridge rather than one per channel.
 *
 * The pending map is keyed by request code and cleared on detach, so a result
 * can never be delivered to a callback that outlived its Activity.
 */
internal class ActivityResultBridge : PluginRegistry.ActivityResultListener {

    /** Activity, when one is attached. Null while the engine runs headless. */
    var activity: Activity? = null

    /** Distinct from [PermissionBridge]'s 0x0C00 block. */
    private val nextCode = AtomicInteger(0x0D00)
    private val pending = HashMap<Int, (resultCode: Int, data: Intent?) -> Unit>()

    /**
     * Start [intent], calling [onResult] when it comes back.
     *
     * Returns false — without invoking [onResult] — when there is no Activity to
     * start it from, or no app on the device that can handle it. Both are
     * "cannot do this here", not failures of a launched flow, and the caller
     * turns them into a typed channel error.
     */
    fun launch(intent: Intent, onResult: (resultCode: Int, data: Intent?) -> Unit): Boolean {
        val a = activity ?: return false
        val code = nextCode.incrementAndGet() and 0xFFFF
        pending[code] = onResult
        return try {
            a.startActivityForResult(intent, code)
            true
        } catch (_: ActivityNotFoundException) {
            pending.remove(code)
            false
        } catch (_: SecurityException) {
            // A picker the OEM restricts, or a camera app that refuses the
            // caller. Same shape as "no such app" from here.
            pending.remove(code)
            false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val callback = pending.remove(requestCode) ?: return false
        callback(resultCode, data)
        return true
    }

    /**
     * Cancel every in-flight request when the Activity goes away, so a `pick`
     * that is still awaiting in Dart completes (as "cancelled") instead of
     * hanging for the life of the app.
     */
    fun detachActivity() {
        activity = null
        pending.values.forEach { it(Activity.RESULT_CANCELED, null) }
        pending.clear()
    }
}
