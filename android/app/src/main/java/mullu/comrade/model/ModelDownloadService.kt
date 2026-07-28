package mullu.comrade.model

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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mullu.comrade.AppNavigation
import mullu.comrade.MainActivity
import mullu.comrade.Notifier
import mullu.comrade.R

/**
 * Downloads an on-device model in the background, with live progress in the
 * notification bar.
 *
 * A foreground service rather than a bare thread for two reasons the user can
 * feel: the transfer keeps running when the app is backgrounded (Android would
 * otherwise be free to kill the process mid-download), and the ongoing
 * notification *is* the progress UI — no need to sit on the prompt watching a
 * bar. When the install finishes the ongoing notification is replaced by a
 * tappable one that takes the user straight back to where the model is used
 * (see [ModelSpec.returnTab]).
 *
 * One download runs at a time: these are tens to hundreds of megabytes and
 * racing two of them helps nobody. A second request while one is in flight is
 * reported through [ModelDownloads] rather than silently dropped.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifications by lazy { NotificationManagerCompat.from(this) }

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val spec = intent?.getStringExtra(EXTRA_MODEL_ID)?.let(ModelCatalog::byId)
        val inFlight = activeSpec()

        // We were launched with startForegroundService(), so the platform wants
        // startForeground() promptly — before any of the early returns below, or
        // it kills the process with "did not then call startForeground()".
        //
        // When a transfer is already running we re-post *its* notification, at
        // *its* current progress: a request for some other model must not be
        // able to relabel the running download or reset its bar.
        startForegroundNotified(inFlight ?: spec, if (inFlight != null) progressOf(inFlight) else 0)

        if (inFlight != null) {
            // A Service is a singleton, so this call arrived on the instance
            // that owns the running transfer. Never take a teardown path here:
            // stopSelf() would destroy the service and abort that download.
            if (spec != null && spec.id != inFlight.id) {
                ModelDownloads.update(
                    spec.id,
                    ModelDownloadState.Failed(getString(R.string.model_download_busy)),
                )
            }
            return START_NOT_STICKY
        }

        if (spec == null) {
            finish()
            return START_NOT_STICKY
        }
        if (!spec.configured) {
            // Fail fast and visibly: an unpinned model must never be fetched.
            ModelDownloads.update(
                spec.id,
                ModelDownloadState.Failed(getString(R.string.model_not_available)),
            )
            finish()
            return START_NOT_STICKY
        }
        if (ModelCatalog.isInstalled(this, spec)) {
            ModelDownloads.update(spec.id, ModelDownloadState.Ready)
            finish()
            return START_NOT_STICKY
        }
        if (!active.compareAndSet(null, spec.id)) {
            // Lost a race against a concurrent start; the winner owns the
            // foreground notification, so again: report, don't tear down.
            if (active.get() != spec.id) {
                ModelDownloads.update(
                    spec.id,
                    ModelDownloadState.Failed(getString(R.string.model_download_busy)),
                )
            }
            return START_NOT_STICKY
        }

        ModelDownloads.clearCancel(spec.id)
        ModelDownloads.update(spec.id, ModelDownloadState.Downloading(0L, spec.downloadBytes))
        scope.launch { runDownload(spec) }
        return START_NOT_STICKY
    }

    /** The model whose transfer is running on this service, if any. */
    private fun activeSpec(): ModelSpec? = active.get()?.let(ModelCatalog::byId)

    /** Current whole-percent progress for [spec], from the shared state. */
    private fun progressOf(spec: ModelSpec): Int =
        (ModelDownloads.current(spec.id) as? ModelDownloadState.Downloading)
            ?.let { downloadPercent(it.bytesRead, it.totalBytes) }
            ?: 0

    /** Drop the foreground notification and stop — the only teardown path. */
    private fun finish() {
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        // If we are being torn down with a transfer still in flight (process
        // pressure rather than our own finish()), ask it to abort. The download
        // loop is blocking, so cancelling the scope alone would not stop it —
        // it would keep writing bytes for a service that no longer exists.
        active.get()?.let(ModelDownloads::cancel)
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runDownload(spec: ModelSpec) {
        var lastPercent = -1
        try {
            ModelInstaller.fetchAndInstall(
                spec = spec,
                cacheDir = cacheDir,
                filesDir = filesDir,
                onProgress = { read, total ->
                    val denominator = if (total > 0) total else spec.downloadBytes
                    ModelDownloads.update(spec.id, ModelDownloadState.Downloading(read, denominator))
                    val percent = downloadPercent(read, denominator)
                    // Renotify only when the bar would actually move — a
                    // notification update per 256 KiB chunk is pure churn.
                    if (percent != lastPercent) {
                        lastPercent = percent
                        updateProgressNotification(spec, read, denominator, percent)
                    }
                },
                onInstalling = {
                    ModelDownloads.update(spec.id, ModelDownloadState.Installing)
                    notifyInstalling(spec)
                },
                isCancelled = { ModelDownloads.isCancelled(spec.id) },
            )
            ModelDownloads.update(spec.id, ModelDownloadState.Ready)
            notifyFinished(spec)
        } catch (cancelled: InstallCancelledException) {
            Log.i(TAG, "${spec.id} model download cancelled", cancelled)
            ModelDownloads.update(spec.id, ModelDownloadState.Idle)
        } catch (failure: Exception) {
            Log.w(TAG, "${spec.id} model download failed", failure)
            val message = failure.message ?: failure.javaClass.simpleName
            ModelDownloads.update(spec.id, ModelDownloadState.Failed(message))
            notifyFailed(spec, message)
        } finally {
            ModelDownloads.clearCancel(spec.id)
            active.compareAndSet(spec.id, null)
            finish()
        }
    }

    // ── Notifications ────────────────────────────────────────────────────────

    private fun progressBuilder(spec: ModelSpec?) =
        NotificationCompat.Builder(this, Notifier.CHANNEL_MODELS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(
                if (spec != null) {
                    getString(R.string.model_downloading_title, spec.displayName)
                } else {
                    // Only reachable for a moment on a malformed start intent,
                    // but it still has to read as something sane in the shade.
                    getString(R.string.model_preparing_title)
                },
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(spec))

    private fun startForegroundNotified(spec: ModelSpec?, percent: Int) {
        val notification = progressBuilder(spec)
            .setProgress(100, percent, percent <= 0)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_ID, notification)
        }
    }

    @SuppressLint("MissingPermission") // the foreground-service notification is exempt
    private fun updateProgressNotification(spec: ModelSpec, read: Long, total: Long, percent: Int) {
        val notification = progressBuilder(spec)
            .setProgress(100, percent, false)
            .setContentText(
                getString(
                    R.string.model_downloading_text,
                    Formatter.formatShortFileSize(this, read),
                    Formatter.formatShortFileSize(this, total),
                ),
            )
            .build()
        notifications.notify(PROGRESS_ID, notification)
    }

    @SuppressLint("MissingPermission")
    private fun notifyInstalling(spec: ModelSpec) {
        val notification = progressBuilder(spec)
            .setProgress(100, 100, true)
            .setContentText(getString(R.string.model_installing_text))
            .build()
        notifications.notify(PROGRESS_ID, notification)
    }

    @SuppressLint("MissingPermission")
    private fun notifyFinished(spec: ModelSpec) {
        if (!notifications.areNotificationsEnabled()) return
        val text = if (spec.returnTab != null) {
            getString(R.string.model_ready_text_return)
        } else {
            getString(R.string.model_ready_text_generic)
        }
        val notification = NotificationCompat.Builder(this, Notifier.CHANNEL_MODELS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.model_ready_title, spec.displayName))
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent(spec))
            .build()
        notifications.notify(resultId(spec), notification)
    }

    @SuppressLint("MissingPermission")
    private fun notifyFailed(spec: ModelSpec, message: String) {
        if (!notifications.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(this, Notifier.CHANNEL_MODELS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.model_failed_title, spec.displayName))
            .setContentText(getString(R.string.model_failed_text, message))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(spec))
            .build()
        notifications.notify(resultId(spec), notification)
    }

    /**
     * Tapping any of these notifications lands the user where the model is
     * used — the Tara conversation for the companion model, wherever they left
     * off otherwise.
     */
    private fun openAppIntent(spec: ModelSpec?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            spec?.returnTab?.let { putExtra(AppNavigation.EXTRA_OPEN_TAB, it) }
        }
        return PendingIntent.getActivity(
            this,
            spec?.id?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** `STOP_FOREGROUND_REMOVE` needs API 24; this app's minSdk is 26. */
    private fun stopForegroundCompat() = stopForeground(STOP_FOREGROUND_REMOVE)

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val EXTRA_MODEL_ID = "mullu.comrade.extra.MODEL_ID"

        /**
         * The foreground (progress) notification id — one download at a time
         * by design, so one id suffices. Distinct from the call/connection
         * notification ids in their respective services.
         */
        private const val PROGRESS_ID = 0xC0DE10

        /** Which model id is downloading right now, if any. */
        private val active = AtomicReference<String?>(null)

        private fun resultId(spec: ModelSpec) = PROGRESS_ID + 1 + ModelCatalog.all.indexOf(spec)

        /**
         * Start (or re-observe) the download for [modelId]. Idempotent: a
         * second tap from another screen just re-observes
         * [ModelDownloads.stateOf].
         */
        fun start(context: Context, modelId: String) {
            val intent = Intent(context, ModelDownloadService::class.java)
                .putExtra(EXTRA_MODEL_ID, modelId)
            context.startForegroundService(intent)
        }

        /** Abort an in-flight download; partial files are deleted. */
        fun cancel(modelId: String) {
            ModelDownloads.cancel(modelId)
        }
    }
}
