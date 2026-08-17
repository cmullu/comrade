package mullu.comrade.travel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Where the phone is, for the Travel tab.
 *
 * **The platform [LocationManager], not Play Services' fused client** — and
 * that is a deliberate choice rather than an oversight. Adding
 * `play-services-location` would mean adding it *twice*: `app/`'s
 * `stagePreservedServices` compiles every non-Compose file in this package into
 * the Flutter APK, so a dependency declared only here fails the Flutter Android
 * lane with an `Unresolved reference` against a package name, in a file nobody
 * edited (`CLAUDE.md`, Traps). The platform API needs no dependency at all, and
 * for a guide with a kilometre-wide radius its accuracy is beside the point.
 *
 * **Coarse permission is what is asked for.** `ACCESS_COARSE_LOCATION` gives a
 * fix good to a few hundred metres, and `comrade_core::travel` immediately
 * rounds whatever it gets to a ~150 m cell before anything leaves the device.
 * Asking for `ACCESS_FINE_LOCATION` would be asking for precision this feature
 * then throws away — the same reasoning the manifest's BLE entries make with
 * `neverForLocation`, one step further.
 */
object TravelLocation {

    private const val TAG = "TravelLocation"

    /** The permission the Travel tab asks for. Coarse, deliberately — see above. */
    const val PERMISSION: String = Manifest.permission.ACCESS_COARSE_LOCATION

    /**
     * How long to wait for a live fix before giving up and reporting what the
     * last-known providers had, if anything.
     *
     * Somebody is standing on a street with the tab open. Twelve seconds is
     * roughly a cold network fix indoors; past that, an old fix with a "checked
     * N min ago" line beats a spinner.
     */
    private const val FIX_TIMEOUT_MS: Long = 12_000

    /** Has the user granted a location permission of either precision? */
    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The best fix available: the freshest last-known reading if it is recent
     * enough to still be true, otherwise one live update.
     *
     * `null` when there is no permission, no enabled provider, or nothing
     * arrived inside [FIX_TIMEOUT_MS] — three different reasons that the screen
     * deliberately renders the same way ([TravelDecisions.State.NoFix]), because
     * the user's next action is identical in all three.
     */
    suspend fun currentFix(context: Context): TravelDecisions.Fix? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        val cached = lastKnown(manager)
        if (cached != null && TravelDecisions.isFresh(cached, System.currentTimeMillis())) {
            return cached
        }
        // A stale cached fix is still better than nothing, so keep it as the
        // fallback rather than discarding it before the live attempt.
        return awaitFix(manager) ?: cached
    }

    /** The freshest last-known reading across every provider we may read. */
    private fun lastKnown(manager: LocationManager): TravelDecisions.Fix? =
        providers(manager)
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }
                    .onFailure { Log.w(TAG, "last known from $provider refused: ${it.message}") }
                    .getOrNull()
            }
            .maxByOrNull { it.time }
            ?.toFix()

    /**
     * One live update, or `null` if none arrives in time.
     *
     * The listener is removed in every exit path — on the first fix, on
     * cancellation, and on the timeout — because a location callback that
     * outlives the screen keeps the radio awake for a tab nobody is looking at.
     */
    private suspend fun awaitFix(manager: LocationManager): TravelDecisions.Fix? =
        withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val provider = providers(manager).firstOrNull { manager.isProviderEnabled(it) }
                if (provider == null) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching { manager.removeUpdates(this) }
                        if (continuation.isActive) continuation.resume(location.toFix())
                    }

                    // Required on API < 30; a no-op here because a provider going
                    // quiet is already covered by the timeout above.
                    @Deprecated("Required by the pre-30 LocationListener contract")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

                    override fun onProviderEnabled(provider: String) = Unit

                    override fun onProviderDisabled(provider: String) = Unit
                }
                continuation.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
                val requested = runCatching {
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }
                if (requested.isFailure) {
                    Log.w(TAG, "location updates refused: ${requested.exceptionOrNull()?.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    /**
     * Providers worth reading, coarsest-cheapest first.
     *
     * `FUSED_PROVIDER` is the platform's own (API 31+), not Play Services'.
     * Network before GPS because this feature does not need a satellite lock
     * and asking for one costs battery and a minute of waiting indoors.
     */
    private fun providers(manager: LocationManager): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.PASSIVE_PROVIDER)
    }.filter { it in manager.allProviders }

    private fun Location.toFix() = TravelDecisions.Fix(
        lat = latitude,
        lon = longitude,
        // `time` is the provider's own clock for the fix, not when we read it —
        // which is exactly what the staleness rule needs to be measured against.
        atMs = time,
    )
}
