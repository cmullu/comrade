package mullu.comrade.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.security.MessageDigest
import mullu.comrade.Notifier
import mullu.comrade.R

/**
 * Hands a downloaded update APK to the system installer.
 *
 * `PackageInstaller` rather than an `ACTION_VIEW` on a `content://` URI: the
 * session API reports what happened (`STATUS_*` back to
 * [UpdateInstallReceiver]), so a refusal or a failure can be said out loud
 * instead of the app assuming an install it never got. The user still confirms —
 * `STATUS_PENDING_USER_ACTION` is the OS's own dialog and cannot be skipped by
 * an app, which is exactly as it should be.
 *
 * ## Two gates before the session opens
 *
 * 1. **`REQUEST_INSTALL_PACKAGES`, granted per source.** Declaring the
 *    permission is not enough; from Android 8 the user grants "install unknown
 *    apps" to *this app*, once. [canInstall] reports it and
 *    [installPermissionSettingsIntent] takes them to the screen that grants it.
 *    There is no way to ask for it in-app, and no way around it — this is the
 *    same consent a browser gets when it installs a sideloaded APK, moved to the
 *    app that actually knows what the file is.
 * 2. **[UpdateInstall.verify].** Package name, version code and signing
 *    certificate are checked against the running app *before* the session is
 *    created, so a substituted file is deleted with a reason rather than handed
 *    to a system dialog that says only "app not installed".
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"
    private const val READY_ID = 0xC0DE22
    private const val ACTION_INSTALL_STATUS = "mullu.comrade.action.INSTALL_STATUS"
    private const val EXTRA_VERSION = "mullu.comrade.extra.UPDATE_VERSION"
    private const val EXTRA_PATH = "mullu.comrade.extra.UPDATE_PATH"

    /** Whether this app may install APKs at all (the per-source user grant). */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * The system screen that grants "install unknown apps" to Comrade.
     * Scoped to this package, so it opens on our own row rather than on a list.
     */
    fun installPermissionSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    // ── Verification ─────────────────────────────────────────────────────────

    /**
     * Read what [apk] claims to be, as [UpdateInstall.ArchiveFacts]. A
     * `signerSha256` of null means the platform did not surface the archive's
     * certificates — see that field's doc for why that is not treated as "no
     * signers".
     */
    @Suppress("DEPRECATION") // GET_SIGNATURES: the only archive-signer read below API 28
    fun readArchive(context: Context, apk: File): UpdateInstall.ArchiveFacts? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = runCatching { context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags) }
            .getOrNull() ?: return null
        return UpdateInstall.ArchiveFacts(
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = versionCodeOf(info),
            signerSha256 = signersOf(info),
        )
    }

    /** The same facts about the running app. */
    fun readInstalled(context: Context): UpdateInstall.InstalledFacts {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        return UpdateInstall.InstalledFacts(
            packageName = context.packageName,
            versionCode = versionCodeOf(info),
            signerSha256 = signersOf(info) ?: emptySet(),
        )
    }

    @Suppress("DEPRECATION") // versionCode below API 28
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION") // signatures below API 28
    private fun signersOf(info: PackageInfo): Set<String>? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return null
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            info.signatures
        } ?: return null
        if (raw.isEmpty()) return null
        return raw.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    // ── Installing ───────────────────────────────────────────────────────────

    /**
     * Open a session, stream [apk] into it and commit. The OS then asks the user
     * to confirm; [UpdateInstallReceiver] reports the outcome.
     *
     * Verification has already happened by the time this runs (in
     * [UpdateDownloadService]); it is repeated here because "install" can also
     * be tapped days later, from a notification, against a file that has been
     * sitting in the cache in the meantime.
     */
    fun install(context: Context, apk: File, version: String) {
        if (!canInstall(context)) {
            UpdateDownloads.update(
                UpdateDownloadState.Failed(context.getString(R.string.update_install_permission_needed)),
            )
            return
        }
        val verdict = UpdateInstall.verify(readArchive(context, apk), readInstalled(context))
        if (verdict is UpdateInstall.Verdict.Refused) {
            apk.delete()
            UpdateDownloads.update(UpdateDownloadState.Failed(reasonText(context, verdict.reason)))
            return
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("comrade-update", 0, apk.length()).use { out ->
                    apk.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
                UpdateDownloads.update(UpdateDownloadState.Installing(version))
                session.commit(statusSender(context, version, apk))
            }
        } catch (failure: Exception) {
            Log.w(TAG, "install session failed", failure)
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            UpdateDownloads.update(
                UpdateDownloadState.Failed(failure.message ?: context.getString(R.string.update_install_failed_generic)),
            )
        }
    }

    private fun statusSender(context: Context, version: String, apk: File): IntentSender {
        val intent = Intent(context, UpdateInstallReceiver::class.java)
            .setAction(ACTION_INSTALL_STATUS)
            .putExtra(EXTRA_VERSION, version)
            .putExtra(EXTRA_PATH, apk.absolutePath)
        // Mutable by requirement: the platform fills in EXTRA_STATUS and, for
        // the pending-user-action case, the confirmation Intent itself.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, version.hashCode(), intent, flags).intentSender
    }

    internal fun reasonText(context: Context, reason: UpdateInstall.Reason): String = context.getString(
        when (reason) {
            UpdateInstall.Reason.WRONG_PACKAGE -> R.string.update_refused_wrong_package
            UpdateInstall.Reason.NOT_NEWER -> R.string.update_refused_not_newer
            UpdateInstall.Reason.SIGNER_MISMATCH -> R.string.update_refused_signer
            UpdateInstall.Reason.UNREADABLE -> R.string.update_refused_unreadable
        },
    )

    // ── The "ready to install" notification ──────────────────────────────────

    /**
     * The whole point of downloading in a service: the user can be somewhere
     * else entirely when the transfer finishes, and tapping this installs it —
     * no browser, no file manager, no hunting through Downloads.
     */
    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled()
    fun notifyReady(context: Context, version: String, apk: File) {
        val mgr = NotificationManagerCompat.from(context)
        if (!mgr.areNotificationsEnabled()) return
        val intent = Intent(context, UpdateInstallReceiver::class.java)
            .setAction(UpdateInstallReceiver.ACTION_INSTALL_NOW)
            .putExtra(EXTRA_VERSION, version)
            .putExtra(EXTRA_PATH, apk.absolutePath)
        val tap = PendingIntent.getBroadcast(
            context,
            "install:$version".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, Notifier.CHANNEL_UPDATES)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_ready_title, version))
            .setContentText(context.getString(R.string.update_ready_text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(tap)
            .build()
        mgr.notify(READY_ID, n)
    }

    fun clearReady(context: Context) {
        NotificationManagerCompat.from(context).cancel(READY_ID)
    }

    internal fun versionOf(intent: Intent): String = intent.getStringExtra(EXTRA_VERSION).orEmpty()

    internal fun pathOf(intent: Intent): String? = intent.getStringExtra(EXTRA_PATH)
}

/**
 * Receives both halves of the install: the user tapping "ready to install", and
 * the `PackageInstaller` session's own status callbacks.
 *
 * A receiver rather than an Activity so a tap works with no UI alive — the
 * download exists precisely so the app can be closed while it runs.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val version = UpdateInstaller.versionOf(intent)
        val path = UpdateInstaller.pathOf(intent)
        when (intent.action) {
            ACTION_INSTALL_NOW -> {
                UpdateInstaller.clearReady(context)
                val apk = path?.let(::File)
                if (apk == null || !apk.exists()) {
                    UpdateDownloads.update(
                        UpdateDownloadState.Failed(context.getString(R.string.update_gone_before_install)),
                    )
                    return
                }
                UpdateInstaller.install(context, apk, version)
            }
            else -> onSessionStatus(context, intent, path)
        }
    }

    private fun onSessionStatus(context: Context, intent: Intent, path: String?) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The OS's confirmation dialog. It arrives as an Intent we are
                // expected to start; NEW_TASK because a receiver has no task of
                // its own to start it in.
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    UpdateDownloads.update(
                        UpdateDownloadState.Failed(context.getString(R.string.update_install_failed_generic)),
                    )
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure {
                        Log.w(TAG, "could not show the install confirmation", it)
                        UpdateDownloads.update(
                            UpdateDownloadState.Failed(context.getString(R.string.update_install_failed_generic)),
                        )
                    }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // The process is usually replaced moments after this, so treat
                // it as bookkeeping only: drop the APK and the state, so a
                // restarted app does not offer to install what it is running.
                path?.let { File(it).delete() }
                UpdateDownloads.update(UpdateDownloadState.Idle)
                UpdateInstaller.clearReady(context)
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "install failed: status=$status message=$message")
                // A user who declines the confirmation dialog lands here too
                // (STATUS_FAILURE_ABORTED). The APK is kept: they may well tap
                // Install again, and re-downloading it would be rude.
                UpdateDownloads.update(
                    UpdateDownloadState.Failed(
                        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                            context.getString(R.string.update_install_cancelled)
                        } else {
                            message ?: context.getString(R.string.update_install_failed_generic)
                        },
                    ),
                )
            }
        }
    }

    companion object {
        private const val TAG = "UpdateInstallReceiver"
        const val ACTION_INSTALL_NOW = "mullu.comrade.action.INSTALL_UPDATE"
    }
}
