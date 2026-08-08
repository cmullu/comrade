package mullu.comrade

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Holds the WiFi multicast lock for as long as the Saathi mesh is running.
 *
 * **This is what makes same-WiFi delivery work on a real phone at all.**
 * Android's WiFi driver drops multicast and broadcast frames that are not
 * addressed to the device unless a [WifiManager.MulticastLock] is held — a
 * battery optimisation, and an invisible one: the socket binds, the code runs,
 * `sendto` succeeds, and *nothing is ever received*. mDNS is multicast, so
 * without this lock a device announces itself and never hears anyone else
 * announce back. Two phones on one network each see an empty mesh and every
 * message falls through to a relay that isn't there.
 *
 * The manifest has carried `CHANGE_WIFI_MULTICAST_STATE` since the mesh
 * landed; nothing ever took the lock it grants. The in-process Rust test
 * passes because a Linux host has no such filter — which is exactly why this
 * class of bug survives CI and only shows up on hardware.
 *
 * Held only while the mesh is up, never for the life of the process: a held
 * multicast lock costs real battery (the WiFi chip must wake for every
 * multicast frame on the network, not just ours), so a locked vault must not
 * pay for it. [setActive] is driven from the one place that already knows —
 * [MeshStatusMonitor], which sees every `MeshStatusChanged` the core emits.
 *
 * Idempotent and safe before [attach]: a JVM unit test has no Android context
 * and simply gets a no-op rather than a mock-me-first crash.
 */
object MeshRadio {
    private const val TAG = "MeshRadio"

    /** Named so it is identifiable in `dumpsys wifi` when someone is hunting a battery drain. */
    private const val LOCK_TAG = "comrade-saathi-mdns"

    private var appContext: Context? = null
    private var lock: WifiManager.MulticastLock? = null

    /**
     * Remember the application context so [setActive] can reach `WifiManager`.
     * Call once, early, from a component that has one; safe to call again.
     *
     * Takes the *application* context deliberately — the lock outlives any one
     * Activity, and holding an Activity here would leak it.
     */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** Whether the multicast lock is currently held — for tests and diagnostics. */
    val isHeld: Boolean get() = lock?.isHeld == true

    /**
     * Take the lock while the mesh is running, drop it when it stops.
     *
     * Failures are logged and swallowed: no multicast lock means local
     * discovery is degraded, which is worth a log line, but it must never take
     * down the event pump that reports the rest of the app's connectivity.
     */
    @Synchronized
    fun setActive(active: Boolean) {
        if (active) acquire() else release()
    }

    private fun acquire() {
        if (lock?.isHeld == true) return
        val context = appContext ?: return
        runCatching {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val fresh = lock ?: wifi.createMulticastLock(LOCK_TAG).also {
                // Not reference counted: this object tracks held/not-held
                // itself, and a counted lock would need acquire and release to
                // be perfectly balanced across event delivery that is not.
                it.setReferenceCounted(false)
                lock = it
            }
            fresh.acquire()
            Log.i(TAG, "multicast lock held — mDNS can hear the local network")
        }.onFailure { Log.w(TAG, "could not take the multicast lock: ${it.message}") }
    }

    private fun release() {
        val held = lock ?: return
        runCatching {
            if (held.isHeld) {
                held.release()
                Log.i(TAG, "multicast lock released")
            }
        }.onFailure { Log.w(TAG, "could not release the multicast lock: ${it.message}") }
    }
}
