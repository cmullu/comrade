package mullu.comrade.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mullu.comrade.AppNavigation
import mullu.comrade.MainActivity
import mullu.comrade.Notifier
import mullu.comrade.R
import mullu.comrade.model.downloadPercent

/**
 * Streams the downloaded APK into a `PackageInstaller` session, in the
 * foreground, with progress.
 *
 * ## The gap this closes
 *
 * Handing an APK to the installer is not instant. The archive is parsed (to check
 * package, version and signer), then sixty-odd megabytes are copied into the
 * session, and only *then* does the platform show its confirmation dialog or begin
 * a dialog-less install. That work used to happen on a bare worker thread with an
 * invisible Activity sitting on top of the app: nothing on screen moved, the app
 * behind could not be touched, and the only visible event was a dialog appearing
 * some seconds later. Reported from the field as "I clicked install and the app
 * stopped responding", which is a fair description of what it looked like.
 *
 * A foreground service fixes all three halves of that:
 *
 * - the copy is **visible**, as a progress notification and as progress on the
 *   settings card, so the wait is accounted for rather than mysterious;
 * - the invisible Activity **gets out of the way** the moment this starts, so the
 *   app stays usable while the copy runs;
 * - the process is **not killable** mid-copy, so backgrounding the app during the
 *   wait no longer risks a half-written session.
 *
 * ## Why `dataSync` and not `shortService`
 *
 * `shortService` is arguably the better semantic fit — this is short, user-
 * initiated and cannot be deferred — but it carries a hard platform timeout and an
 * `onTimeout` contract, and the foreground-service contract is the most
 * bug-prone thing in this codebase. `dataSync` is what [UpdateDownloadService] and
 * `ModelDownloadService` already use and it is proven here. Started from a resumed
 * Activity, so the Android 12+ background-start restriction never applies.
 *
 * **The path is not an argument.** Like [UpdateDownloadService] reading the
 * release out of [UpdateChecker], this reads what to install out of
 * [UpdateDownloads] — an intent extra naming a file would make "install an APK of
 * my choosing" reachable from anything that can start this service.
 */
class UpdateInstallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifications by lazy { NotificationManagerCompat.from(this) }

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Launched with startForegroundService(): the platform wants
        // startForeground() promptly, on *every* delivery and before any early
        // return below. A missed promotion strands the service and the process is
        // killed with ForegroundServiceDidNotStartInTimeException.
        val pending = UpdateDownloads.state.value as? UpdateDownloadState.Installing
        // Progress read from the state rather than assumed to be zero: on a second
        // delivery this notification replaces the running copy's, and rewinding it
        // to 0% would be a visible lie until the next report.
        startForegroundNotified(
            pending?.version,
            staged = pending?.bytesStaged ?: 0L,
            total = pending?.totalBytes ?: 0L,
        )

        if (pending == null) {
            // Nothing was handed over — the state moved on (a failure, or a
            // reconcile that found the APK gone) between the tap and this start.
            Log.i(TAG, "no install pending; nothing to stage")
            finish()
            return START_NOT_STICKY
        }
        if (!UpdateInstaller.claim()) {
            // A second delivery while one is staging. This is not the instance
            // that owns it, but tearing down here would drop the *owner's*
            // foreground notification with it — so return without finishing and
            // let the running copy report as normal.
            Log.i(TAG, "an install is already staging; ignoring the second start")
            return START_NOT_STICKY
        }
        scope.launch { stage(pending) }
        return START_NOT_STICKY
    }

    private fun stage(pending: UpdateDownloadState.Installing) {
        val apk = File(pending.path)
        var lastPercent = -1
        var committed = false
        try {
            committed = UpdateInstaller.stageAndCommit(this, apk, pending.version) { staged, total ->
                UpdateDownloads.update(
                    UpdateDownloadState.Installing(pending.version, pending.path, staged, total),
                )
                val percent = downloadPercent(staged, total)
                if (percent != lastPercent) {
                    lastPercent = percent
                    updateProgressNotification(pending.version, staged, total, percent)
                }
            }
        } catch (failure: Exception) {
            // stageAndCommit reports its own expected failures; this is the
            // backstop for the unexpected ones, which must still leave the card
            // in a state the user can act on rather than at 40% forever.
            Log.w(TAG, "staging the update failed", failure)
            UpdateDownloads.update(
                UpdateDownloadState.Failed(
                    failure.message ?: getString(R.string.update_install_failed_generic),
                ),
            )
        } finally {
            UpdateInstaller.release()
            if (committed) {
                // Progress back to zero, which is this state's way of saying
                // "committed, the platform owns it" — see UpdateDownloadState
                // .Installing. What happens next is its confirmation dialog or
                // (on a device that skips it) this process being replaced, and
                // neither is something we can report progress on.
                UpdateDownloads.update(UpdateDownloadState.Installing(pending.version, pending.path))
            }
            finish()
        }
    }

    /** Drop the foreground notification and stop — the only teardown path. */
    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notifications ────────────────────────────────────────────────────────

    private fun progressBuilder(version: String?) =
        NotificationCompat.Builder(this, Notifier.CHANNEL_UPDATES)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(
                if (version.isNullOrBlank()) {
                    getString(R.string.update_installing_title_generic)
                } else {
                    getString(R.string.update_installing_title, version)
                },
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openSettingsIntent())

    private fun startForegroundNotified(version: String?, staged: Long, total: Long) {
        val percent = downloadPercent(staged, total)
        val notification = progressBuilder(version)
            .setProgress(100, percent, total <= 0L)
            .setContentText(getString(R.string.update_installing_text))
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(STAGING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(STAGING_ID, notification)
        }
    }

    @SuppressLint("MissingPermission") // the foreground-service notification is exempt
    private fun updateProgressNotification(version: String, staged: Long, total: Long, percent: Int) {
        val notification = progressBuilder(version)
            .setProgress(100, percent, total <= 0L)
            .setContentText(
                getString(
                    R.string.update_installing_progress,
                    Formatter.formatShortFileSize(this, staged),
                    Formatter.formatShortFileSize(this, total.coerceAtLeast(staged)),
                ),
            )
            .build()
        notifications.notify(STAGING_ID, notification)
    }

    /** Tapping progress lands on the card that owns this. */
    private fun openSettingsIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AppNavigation.EXTRA_OPEN_TAB, AppNavigation.SCREEN_SETTINGS)
        }
        return PendingIntent.getActivity(
            this,
            "screen:${AppNavigation.SCREEN_SETTINGS}".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "UpdateInstallService"

        /** Distinct from every other notification id this app uses. */
        private const val STAGING_ID = 0xC0DE26

        /**
         * Hand [apk] to the installer.
         *
         * Publishes the hand-off to [UpdateDownloads] *before* starting the
         * service, because that state is how the service learns what to install —
         * see the class doc on why it is not an intent extra.
         */
        fun start(context: Context, version: String, apk: File) {
            UpdateDownloads.update(
                UpdateDownloadState.Installing(version, apk.absolutePath, 0L, apk.length()),
            )
            context.startForegroundService(Intent(context, UpdateInstallService::class.java))
        }
    }
}
