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
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
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

/** Thrown when the user cancels mid-transfer. */
private class DownloadCancelledException : InterruptedIOException("update download cancelled")

/**
 * Downloads the update APK in the background, with live progress in the
 * notification bar, then offers to install it.
 *
 * A foreground service for the same two reasons `ModelDownloadService` is one,
 * and they are the reasons this exists at all: the transfer keeps running when
 * the app is backgrounded (Android would otherwise be free to kill the process
 * mid-download), and the ongoing notification *is* the progress UI — nobody has
 * to sit on the settings card watching a bar. When it finishes, the ongoing
 * notification is replaced by a tappable "ready to install" one
 * ([UpdateInstaller.notifyReady]), so the update installs from the shade with
 * no browser and no file manager in the way.
 *
 * **The URL is not an argument.** The service reads the release out of
 * [UpdateChecker.status] itself. An intent extra carrying a download URL would
 * make "install an APK of my choosing" reachable from anything that can send
 * this app an intent, which is the one thing an update path must never be.
 */
class UpdateDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifications by lazy { NotificationManagerCompat.from(this) }

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Launched with startForegroundService(): the platform wants
        // startForeground() promptly, before any early return below.
        startForegroundNotified(currentPercent())

        if (!running.compareAndSet(false, true)) {
            // A second tap while one is in flight. This is the instance that
            // owns the transfer, so never take a teardown path here — report
            // through UpdateDownloads and let the running download continue.
            return START_NOT_STICKY
        }

        val release = (UpdateChecker.status.value as? UpdateStatus.Available)?.release
        val url = release?.apkUrl
        if (release == null || url == null) {
            // Nothing to fetch: either no finding, or a release with no single
            // identifiable APK (see UpdateCheck.parseRelease) — that case is
            // the release page's job, not this service's.
            UpdateDownloads.update(UpdateDownloadState.Failed(getString(R.string.update_no_apk_to_download)))
            running.set(false)
            finish()
            return START_NOT_STICKY
        }

        UpdateDownloads.clearCancel()
        UpdateDownloads.update(UpdateDownloadState.Downloading(0L, release.apkBytes))
        scope.launch { run(release, url) }
        return START_NOT_STICKY
    }

    private fun currentPercent(): Int =
        (UpdateDownloads.state.value as? UpdateDownloadState.Downloading)
            ?.let { downloadPercent(it.bytesRead, it.totalBytes) } ?: 0

    private fun run(release: UpdateCheck.ReleaseInfo, url: String) {
        val target = apkFile(this, release.versionName)
        var lastPercent = -1
        try {
            purgeOtherApks(this, keep = target)
            download(url, target, release.apkBytes) { read, total ->
                UpdateDownloads.update(UpdateDownloadState.Downloading(read, total))
                val percent = downloadPercent(read, total)
                if (percent != lastPercent) {
                    lastPercent = percent
                    updateProgressNotification(release.versionName, read, total, percent)
                }
            }

            UpdateDownloads.update(UpdateDownloadState.Verifying)
            notifyVerifying(release.versionName)
            if (!UpdateInstall.sizeLooksRight(release.apkBytes, target.length())) {
                throw IOException(getString(R.string.update_refused_size))
            }
            val verdict = UpdateInstall.verify(
                UpdateInstaller.readArchive(this, target),
                UpdateInstaller.readInstalled(this),
            )
            if (verdict is UpdateInstall.Verdict.Refused) {
                throw IOException(UpdateInstaller.reasonText(this, verdict.reason))
            }
            if ((verdict as UpdateInstall.Verdict.Ok).signatureChecked) {
                Log.i(TAG, "update ${release.versionName}: signer matches the installed app")
            } else {
                // Said out loud rather than implied: the platform still refuses
                // a mismatched signature at install time, but we did not manage
                // to check it ourselves on this device.
                Log.i(TAG, "update ${release.versionName}: archive signers unreadable, relying on the installer")
            }

            UpdateDownloads.update(UpdateDownloadState.Ready(release.versionName, target.absolutePath))
            stopForegroundCompat()
            notifications.cancel(PROGRESS_ID)
            UpdateInstaller.notifyReady(this, release.versionName, target)
        } catch (cancelled: DownloadCancelledException) {
            Log.i(TAG, "update download cancelled", cancelled)
            target.delete()
            UpdateDownloads.update(UpdateDownloadState.Idle)
        } catch (failure: Exception) {
            Log.w(TAG, "update download failed", failure)
            target.delete()
            val message = failure.message ?: failure.javaClass.simpleName
            UpdateDownloads.update(UpdateDownloadState.Failed(message))
            notifyFailed(message)
        } finally {
            UpdateDownloads.clearCancel()
            running.set(false)
            finish()
        }
    }

    /**
     * Stream [url] into [dest]. Same shape as `ModelInstaller.download` — the
     * proven one in this codebase — with the release's own size as the progress
     * denominator until the server reports one.
     */
    private fun download(url: String, dest: File, expectedBytes: Long, onProgress: (Long, Long) -> Unit) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val reported = connection.contentLengthLong
            val total = if (reported > 0) reported else expectedBytes
            dest.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastReported = 0L
                    onProgress(0L, total)
                    while (true) {
                        if (UpdateDownloads.isCancelled()) throw DownloadCancelledException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        // Every 256 KiB is plenty for a progress bar.
                        if (copied - lastReported >= 256 * 1024) {
                            lastReported = copied
                            onProgress(copied, total)
                        }
                    }
                    onProgress(copied, total)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Drop the foreground notification and stop — the only teardown path. */
    private fun finish() {
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Torn down with a transfer in flight (process pressure rather than our
        // own finish()): ask it to abort. The read loop is blocking, so
        // cancelling the scope alone would leave it writing bytes for a service
        // that no longer exists.
        if (running.get()) UpdateDownloads.cancel()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notifications ────────────────────────────────────────────────────────

    private fun progressBuilder(version: String?) =
        NotificationCompat.Builder(this, Notifier.CHANNEL_UPDATES)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(
                if (version != null) {
                    getString(R.string.update_downloading_title, version)
                } else {
                    getString(R.string.update_downloading_title_generic)
                },
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openSettingsIntent())

    private fun startForegroundNotified(percent: Int) {
        val version = (UpdateChecker.status.value as? UpdateStatus.Available)?.release?.versionName
        val notification = progressBuilder(version)
            .setProgress(100, percent, percent <= 0)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_ID, notification)
        }
    }

    @SuppressLint("MissingPermission") // the foreground-service notification is exempt
    private fun updateProgressNotification(version: String, read: Long, total: Long, percent: Int) {
        val notification = progressBuilder(version)
            .setProgress(100, percent, total <= 0)
            .setContentText(
                getString(
                    R.string.update_downloading_text,
                    Formatter.formatShortFileSize(this, read),
                    Formatter.formatShortFileSize(this, total.coerceAtLeast(read)),
                ),
            )
            .build()
        notifications.notify(PROGRESS_ID, notification)
    }

    @SuppressLint("MissingPermission")
    private fun notifyVerifying(version: String) {
        val notification = progressBuilder(version)
            .setProgress(100, 100, true)
            .setContentText(getString(R.string.update_verifying_text))
            .build()
        notifications.notify(PROGRESS_ID, notification)
    }

    @SuppressLint("MissingPermission")
    private fun notifyFailed(message: String) {
        if (!notifications.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(this, Notifier.CHANNEL_UPDATES)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.update_download_failed_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openSettingsIntent())
            .build()
        notifications.notify(PROGRESS_ID + 1, notification)
    }

    /** Tapping progress (or a failure) lands on the card that owns this. */
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

    private fun stopForegroundCompat() = stopForeground(STOP_FOREGROUND_REMOVE)

    companion object {
        private const val TAG = "UpdateDownloadService"

        /** The foreground (progress) id. Distinct from every other service's. */
        private const val PROGRESS_ID = 0xC0DE23

        private val running = AtomicBoolean(false)

        /** Where an update APK is cached: app-private, and cleared with the cache. */
        fun apkFile(context: Context, version: String): File =
            File(File(context.cacheDir, "updates"), "comrade-$version.apk")

        /**
         * Delete every cached update APK except [keep] — a half-finished
         * download from a version that has since been superseded is dead weight
         * in a cache the user cannot see.
         */
        fun purgeOtherApks(context: Context, keep: File? = null) {
            val dir = File(context.cacheDir, "updates")
            dir.listFiles()?.forEach { file ->
                if (keep == null || file.absolutePath != keep.absolutePath) file.delete()
            }
        }

        /** Start (or re-observe) the download. Idempotent. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, UpdateDownloadService::class.java))
        }

        /** Abort an in-flight download; the partial file is deleted. */
        fun cancel() {
            UpdateDownloads.cancel()
        }
    }
}
