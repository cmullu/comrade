package mullu.comrade.channel

import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import io.flutter.plugin.common.PluginRegistry
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runtime-permission gating, kept native.
 *
 * Under Compose this was `MainActivity.withCallPermissions`
 * (`android/…/MainActivity.kt:352-364`): collect the missing permissions, ask,
 * then run the deferred action. That shape is what matters and why this did
 * **not** become a Dart `permission_handler` call — see
 * `../PLATFORM_CHANNELS.md` §5.3. `accept` must run *after* the grant, in the
 * same gesture, while the call is still ringing against a 45 s ring timeout;
 * round-tripping the grant through Dart adds two channel hops to that path and
 * hands the pending action's lifetime to a widget that can be disposed
 * mid-dialog.
 *
 * One [PermissionBridge] is shared by every handler that needs a grant, so the
 * request codes cannot collide.
 */
internal class PermissionBridge : PluginRegistry.RequestPermissionsResultListener {

    /**
     * Activity, when one is attached. Null while the engine runs headless —
     * `ActivityAware` sets and clears it. A permission-gated method with no
     * Activity replies [ErrorCodes.NO_ACTIVITY] rather than silently no-oping.
     */
    var activity: Activity? = null

    private val nextCode = AtomicInteger(0x0C00)
    private val pending = HashMap<Int, (granted: Boolean, denied: List<String>) -> Unit>()

    /** True when every one of [permissions] is already granted. */
    fun hasAll(permissions: List<String>): Boolean {
        val a = activity ?: return false
        return permissions.all {
            ActivityCompat.checkSelfPermission(a, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Ensure [permissions], then invoke [onResult] on the main thread.
     *
     * Grants that are already held short-circuit synchronously — the common
     * case, and the one that must not add a frame of latency to answering a
     * ringing call.
     */
    fun ensure(permissions: List<String>, onResult: (granted: Boolean, denied: List<String>) -> Unit) {
        val a = activity
        if (a == null) {
            onResult(false, permissions)
            return
        }
        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(a, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onResult(true, emptyList())
            return
        }
        val code = nextCode.incrementAndGet() and 0xFFFF
        pending[code] = onResult
        ActivityCompat.requestPermissions(a, missing.toTypedArray(), code)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        val callback = pending.remove(requestCode) ?: return false
        val denied = permissions.filterIndexed { i, _ ->
            grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED
        }
        callback(denied.isEmpty(), denied)
        return true
    }

    /**
     * Drop every pending request when the Activity goes away. The callbacks
     * would otherwise fire against a dead Activity on the next attach — or,
     * worse, resume a call-accept for a call that ended minutes ago.
     */
    fun detachActivity() {
        activity = null
        pending.values.forEach { it(false, emptyList()) }
        pending.clear()
    }
}
