package mullu.comrade.update

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mullu.comrade.BuildConfig
import mullu.comrade.Notifier

/** Where a check has got to. Observed by both frontends' settings screens. */
sealed class UpdateStatus {
    /** Nothing looked yet this process. */
    object Unknown : UpdateStatus()

    object Checking : UpdateStatus()

    /** The newest published release is the one running (or older). */
    data class UpToDate(val checkedAt: Long) : UpdateStatus()

    data class Available(val release: UpdateCheck.ReleaseInfo, val checkedAt: Long) : UpdateStatus()

    /** The check itself failed — offline, rate-limited, unreadable body. */
    data class Failed(val message: String, val attemptedAt: Long) : UpdateStatus()
}

/**
 * Tells the user when a newer Comrade has been published, and hands them to it.
 *
 * ## What this does and does not do
 *
 * It reads one public GitHub endpoint, compares versions ([UpdateCheck]), and
 * — at most once per version — posts a notification.
 *
 * The APK itself is fetched by [UpdateDownloadService] (a foreground service, so
 * the transfer survives backgrounding and shows progress in the shade) and
 * handed to the system installer by [UpdateInstaller]. That needs
 * `REQUEST_INSTALL_PACKAGES`, which the owner asked for after using the
 * link-out version: the browser round trip meant leaving the app, finding the
 * file in Downloads, and granting *the browser* the same permission anyway.
 * [openRelease] remains as the fallback for a release with no single APK asset,
 * and for anyone who would rather not grant the install permission at all.
 *
 * ## The privacy cost, stated
 *
 * Contacting api.github.com discloses this device's IP address to GitHub, on a
 * schedule, along with the fact that something asks about Comrade releases.
 * That is a real disclosure in an app whose whole point is not phoning home, so
 * it is a setting ([isAutoCheckEnabled]) with copy that says so, and turning it
 * off leaves a manual "Check now" that only runs when tapped. The request
 * carries no identity: no token, no install id, no version in a query
 * parameter, and the `User-Agent` names the app and nothing about the device.
 *
 * On by default, because the alternative — a sideloaded privacy app whose users
 * never hear that a security fix shipped — is the worse failure.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val PREFS = "mullu.comrade.Updates"
    private const val KEY_AUTO = "auto_check"
    private const val KEY_LAST_CHECKED = "last_checked_at"
    private const val KEY_SKIPPED = "skipped_version"
    private const val KEY_NOTIFIED = "notified_version"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicBoolean(false)

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
    val status: StateFlow<UpdateStatus> = _status

    /** The version this build reports — what a release is compared against. */
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Settings ─────────────────────────────────────────────────────────────

    fun isAutoCheckEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply()
    }

    fun lastCheckedAt(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECKED, 0L)

    fun skippedVersion(context: Context): String? =
        prefs(context).getString(KEY_SKIPPED, null)?.takeIf { it.isNotBlank() }

    /**
     * Stop mentioning this version. Deliberately not "never check again": a
     * later release is announced normally (see [UpdateCheck.shouldNotify]).
     */
    fun skip(context: Context, version: String) {
        prefs(context).edit().putString(KEY_SKIPPED, version).apply()
        Notifier.clearUpdate(context)
    }

    /** Undo a [skip] — the settings card offers this once something is skipped. */
    fun unskip(context: Context) {
        prefs(context).edit().remove(KEY_SKIPPED).apply()
    }

    // ── Checking ─────────────────────────────────────────────────────────────

    /**
     * Check if one is due (or [force]d by the user tapping "Check now").
     *
     * Safe to call on every foreground: the cadence gate and the single-flight
     * flag mean a user who switches back and forth all day still causes one
     * request per [UpdateCheck.CHECK_INTERVAL_MS]. A forced check ignores the
     * cadence but not the flag — two overlapping requests would only race to
     * write the same state.
     */
    fun maybeCheck(context: Context, force: Boolean = false, now: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        if (!force) {
            if (!isAutoCheckEnabled(app)) return
            if (!UpdateCheck.shouldCheck(lastCheckedAt(app), now)) return
        }
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                // A user who tapped "Check now" is looking at the answer; a
                // notification about what is already on their screen is noise.
                // It also leaves the "already notified" watermark alone, so a
                // later automatic check still announces this version once.
                runCheck(app, notify = !force, now = now)
            } finally {
                inFlight.set(false)
            }
        }
    }

    private suspend fun runCheck(context: Context, notify: Boolean, now: Long) {
        _status.value = UpdateStatus.Checking
        val body = try {
            withContext(Dispatchers.IO) { fetchLatestRelease() }
        } catch (failure: IOException) {
            // A failed check must not count as a check: leaving
            // last_checked_at alone means the next foreground retries rather
            // than waiting a day because the user happened to be on a train.
            Log.i(TAG, "update check failed", failure)
            _status.value = UpdateStatus.Failed(failure.message ?: "Could not reach GitHub", now)
            return
        }
        val release = UpdateCheck.parseRelease(body)
        prefs(context).edit().putLong(KEY_LAST_CHECKED, now).apply()
        if (release == null) {
            _status.value = UpdateStatus.Failed("Could not read the latest release", now)
            return
        }
        if (!UpdateCheck.isNewer(currentVersion, release.versionName)) {
            _status.value = UpdateStatus.UpToDate(now)
            // An update that was pending and has now been installed should not
            // leave its notice sitting in the shade — nor its APK in the cache,
            // nor an "Install" button offering to install what is running.
            Notifier.clearUpdate(context)
            UpdateInstaller.clearReady(context)
            UpdateDownloads.update(UpdateDownloadState.Idle)
            UpdateDownloadService.purgeOtherApks(context)
            return
        }
        _status.value = UpdateStatus.Available(release, now)
        // A finding that supersedes what was downloaded invalidates it: an
        // "Install 0.0.50" button next to "0.0.51 is available" is a trap.
        val downloaded = UpdateDownloads.state.value
        val staleVersion = when (downloaded) {
            is UpdateDownloadState.Ready -> downloaded.version
            is UpdateDownloadState.Installing -> downloaded.version
            else -> null
        }
        if (staleVersion != null && staleVersion != release.versionName) {
            UpdateDownloads.update(UpdateDownloadState.Idle)
            UpdateInstaller.clearReady(context)
            UpdateDownloadService.purgeOtherApks(context)
        }
        if (!notify) return
        val store = prefs(context)
        if (UpdateCheck.shouldNotify(release, skippedVersion(context), store.getString(KEY_NOTIFIED, null))) {
            Notifier.notifyUpdateAvailable(context, release.versionName)
            store.edit().putString(KEY_NOTIFIED, release.versionName).apply()
        }
    }

    /**
     * GET the latest release. Throws [IOException] for every failure the caller
     * should report as "the check failed" — including a non-200, which for this
     * endpoint means rate-limited (403/429), no releases yet (404), or GitHub
     * having a bad day.
     */
    private fun fetchLatestRelease(): String {
        val connection = URI(UpdateCheck.LATEST_RELEASE_URL).toURL().openConnection() as HttpURLConnection
        return try {
            connection.apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                // Names the app, not the device: no model, no OS build, no id.
                setRequestProperty("User-Agent", "Comrade/${BuildConfig.VERSION_NAME}")
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException(
                    when (code) {
                        HttpURLConnection.HTTP_NOT_FOUND -> "No published release yet"
                        HttpURLConnection.HTTP_FORBIDDEN, 429 -> "GitHub rate-limited the check — try later"
                        else -> "GitHub returned HTTP $code"
                    },
                )
            }
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                // Bytes first, decoded once at the end: release notes are full
                // of em dashes and the odd emoji, and decoding chunk by chunk
                // would mangle any multi-byte character straddling a boundary.
                val bytes = java.io.ByteArrayOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (bytes.size() + read > UpdateCheck.MAX_BODY_BYTES) {
                        throw IOException("Release payload too large")
                    }
                    bytes.write(buffer, 0, read)
                }
                bytes.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    // ── Downloading and installing ───────────────────────────────────────────

    /**
     * Fetch the update APK in the background. The service reads the release from
     * [status] itself, so nothing here (or in either frontend) can name a URL
     * for it to download.
     *
     * Safe to call twice: a second request while one is in flight is a no-op on
     * the running transfer.
     */
    fun download(context: Context) {
        val app = context.applicationContext
        if (status.value !is UpdateStatus.Available) return
        UpdateDownloadService.start(app)
    }

    /** Abort an in-flight download; the partial file is deleted. */
    fun cancelDownload() = UpdateDownloadService.cancel()

    /**
     * Install what has been downloaded. Verifies package, version and signer
     * again first — this can be tapped days after the download, from a
     * notification, against a file that has sat in the cache since.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val ready = UpdateDownloads.state.value as? UpdateDownloadState.Ready ?: return
        UpdateInstaller.install(app, java.io.File(ready.path), ready.version)
    }

    /** Whether the OS will let this app install an APK (the per-source grant). */
    fun canInstall(context: Context): Boolean = UpdateInstaller.canInstall(context)

    /** Take the user to the system screen that grants it. */
    fun openInstallPermissionSettings(context: Context) {
        runCatching { context.startActivity(UpdateInstaller.installPermissionSettingsIntent(context)) }
            .onFailure { Log.w(TAG, "no screen to grant the install permission", it) }
    }

    /**
     * Drop a "ready to install" that no longer has a file behind it (the OS
     * reaped the cache, or the install went through and the APK was deleted).
     */
    fun refreshDownloadState() {
        UpdateDownloads.forgetIfGone { path -> java.io.File(path).exists() }
    }

    /**
     * Open the release page in the browser — the fallback path. Used when a
     * release carries no single identifiable APK, and offered alongside the
     * in-app download for anyone who would rather install from their browser
     * than grant this app the install permission.
     */
    fun openRelease(context: Context, release: UpdateCheck.ReleaseInfo?) {
        val url = release?.pageUrl ?: UpdateCheck.RELEASES_PAGE_URL
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "no browser to open $url", it) }
    }
}
