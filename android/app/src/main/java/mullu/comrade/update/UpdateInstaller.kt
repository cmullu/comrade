package mullu.comrade.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import mullu.comrade.MainActivity
import mullu.comrade.Notifier
import mullu.comrade.R

/**
 * Hands a downloaded update APK to the system installer.
 *
 * `PackageInstaller` rather than an `ACTION_VIEW` on a `content://` URI: the
 * session API reports what happened (`STATUS_*` back to
 * [UpdateInstallActivity]), so a refusal or a failure can be said out loud
 * instead of the app assuming an install it never got.
 *
 * ## The confirmation dialog is asked to stand aside, not relied on
 *
 * From API 31 the session asks for `USER_ACTION_NOT_REQUIRED`, so on a device
 * that allows it the update applies with no dialog: Comrade's process is
 * replaced and [notifyInstalled] is the only thing the user sees — a
 * notification saying the new version is in and offering to reopen it. That is
 * an owner decision, taken after the dialog turned out to be the part that
 * failed.
 *
 * **Whether it is allowed is entirely the platform's call**, and it can differ
 * between devices and over time — another installer holding update ownership
 * (Android 14+), an OEM policy, or simply an older release all end with the
 * platform answering `STATUS_PENDING_USER_ACTION` instead. So the dialog path
 * below is not legacy code: it is the fallback, and on many devices it will be
 * the normal path. Nothing here may assume which one happened.
 *
 * **What is *not* given up.** Android's refusal to install an APK signed with a
 * different key than the installed copy is unconditional enforcement, not a
 * dialog — it does not weaken when the dialog is skipped. Neither does
 * [UpdateInstall.verify], which still runs first. What the user gives up is
 * *seeing* the install as it happens, which is what the notification is for.
 *
 * ## Two things this must never do again
 *
 * The first shipped version of this got both wrong, and the result was an ANR
 * followed by a card stuck on "Waiting for Android to install it…":
 *
 * 1. **The APK is copied into the session on a worker thread.** A release APK
 *    is tens of megabytes; copying it on the caller's thread froze the UI, and
 *    when the caller was a `BroadcastReceiver` it also blew the ten-second
 *    receiver deadline. Every entry point here is asynchronous now — [install]
 *    returns immediately and reports through [UpdateDownloads].
 * 2. **The confirmation dialog is started from an Activity.**
 *    `STATUS_PENDING_USER_ACTION` hands back an Intent the app has to start, and
 *    a `BroadcastReceiver` is the one context that cannot reliably start it: a
 *    notification whose `contentIntent` is a broadcast that starts an activity
 *    is a *notification trampoline*, blocked outright since Android 12, and a
 *    background activity start from a receiver is blocked since Android 10.
 *    Both are dropped **silently** — nothing throws, no status arrives — which
 *    is why the app sat there claiming to be waiting. [UpdateInstallActivity]
 *    receives the callback instead and starts the dialog while resumed.
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

    /** Distinct from [READY_ID]: "ready" is cancelled the moment an install starts. */
    private const val INSTALLED_ID = 0xC0DE24

    /**
     * One at a time, off the main thread. A single thread rather than a pool
     * because two concurrent sessions for the same package are never wanted, and
     * it is created lazily so a process that never installs never spawns it.
     */
    private val worker by lazy {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "comrade-update-install") }
    }

    private val working = AtomicBoolean(false)

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
     * Verify [apk], stream it into a session and commit — **on a worker
     * thread**. Returns as soon as the work is queued; the OS then asks the user
     * to confirm and [UpdateInstallActivity] reports the outcome.
     *
     * Verification has already happened by the time this runs (in
     * [UpdateDownloadService]); it is repeated here because "install" can also
     * be tapped days later, from a notification, against a file that has been
     * sitting in the cache in the meantime. Both halves — parsing the archive
     * and copying it — are far too slow for the main thread, which is the whole
     * reason this hands off to [worker].
     */
    /**
     * @param onFailedToCommit run on the main thread when the work ends
     *   *without* a committed session — the only case in which no `STATUS_*`
     *   callback will ever arrive, and therefore the only case in which the
     *   caller has to clean up after itself rather than wait.
     */
    fun install(context: Context, apk: File, version: String, onFailedToCommit: () -> Unit = {}) {
        val app = context.applicationContext
        if (!canInstall(app)) {
            UpdateDownloads.update(
                UpdateDownloadState.Failed(app.getString(R.string.update_install_permission_needed)),
            )
            onFailedToCommit()
            return
        }
        // Claimed here, on the caller's thread, so a double tap cannot open two
        // sessions for the same APK. Released by every exit below.
        if (!working.compareAndSet(false, true)) return
        UpdateDownloads.update(UpdateDownloadState.Installing(version, apk.absolutePath))
        worker.execute {
            var committed = false
            try {
                committed = installBlocking(app, apk, version)
            } finally {
                working.set(false)
                if (!committed) Handler(Looper.getMainLooper()).post(onFailedToCommit)
            }
        }
    }

    /** Whether a session write is in flight. Read by the "try again" path. */
    fun isInstalling(): Boolean = working.get()

    /** @return whether a session was committed, and so whether a status is coming. */
    private fun installBlocking(context: Context, apk: File, version: String): Boolean {
        val verdict = UpdateInstall.verify(readArchive(context, apk), readInstalled(context))
        if (verdict is UpdateInstall.Verdict.Refused) {
            apk.delete()
            UpdateDownloads.update(UpdateDownloadState.Failed(reasonText(context, verdict.reason)))
            return false
        }
        val installer = context.packageManager.packageInstaller
        // A previous attempt that was killed mid-write (or an ANR the user chose
        // to close) leaves a sealed-but-uncommitted session behind, and those
        // count against a per-app cap. This install supersedes all of them.
        runCatching {
            installer.mySessions.forEach { info ->
                runCatching { installer.abandonSession(info.sessionId) }
            }
        }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            // Both advisory, both worth setting: the package name is what the
            // system shows in its own confirmation dialog, and the size lets it
            // fail fast on a full device instead of part-way through the copy.
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Ask to skip the confirmation dialog — see this object's doc for
            // what that does and does not buy. A request, not an instruction:
            // when the platform says no it answers STATUS_PENDING_USER_ACTION
            // exactly as before and UpdateInstallActivity shows the dialog.
            //
            // setRequestUpdateOwnership (API 34) is deliberately *not* set:
            // ownership can only be claimed on a first install, so on an update
            // it is a no-op, and writing it here would read as if it helped.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("comrade-update", 0, apk.length()).use { out ->
                    apk.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(statusSender(context, version, apk))
            }
            return true
        } catch (failure: Exception) {
            Log.w(TAG, "install session failed", failure)
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            UpdateDownloads.update(
                UpdateDownloadState.Failed(failure.message ?: context.getString(R.string.update_install_failed_generic)),
            )
            return false
        }
    }

    /**
     * Where the session reports back: an **Activity**, not a receiver. See this
     * object's doc comment — a receiver cannot reliably start the confirmation
     * dialog the platform hands it, and fails at it silently.
     */
    private fun statusSender(context: Context, version: String, apk: File): IntentSender {
        val intent = UpdateInstallActivity.statusIntent(context, version, apk.absolutePath)
        // Mutable by requirement: the platform fills in EXTRA_STATUS and, for
        // the pending-user-action case, the confirmation Intent itself.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(context, "status:$version".hashCode(), intent, flags).intentSender
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
     *
     * The tap opens [UpdateInstallActivity] directly. It used to send a
     * broadcast that started an activity, which is the textbook shape of a
     * notification trampoline — blocked since Android 12, and blocked without a
     * word, so the notification simply did nothing.
     */
    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled()
    fun notifyReady(context: Context, version: String, apk: File) {
        val mgr = NotificationManagerCompat.from(context)
        if (!mgr.areNotificationsEnabled()) return
        val tap = PendingIntent.getActivity(
            context,
            "install:$version".hashCode(),
            UpdateInstallActivity.installIntent(context, version, apk.absolutePath),
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

    // ── The "installed, reopen it" notification ──────────────────────────────

    /**
     * Say that the update went in, and offer to reopen Comrade.
     *
     * This is the whole of the user-visible story for a dialog-less install.
     * Applying an update replaces the process, so the app the user was looking
     * at simply disappears — without this they would be left staring at a
     * launcher wondering what happened.
     *
     * Posted from whichever process receives `STATUS_SUCCESS`, which for a
     * self-update is usually a fresh one running the **new** code: the
     * `PendingIntent` resolves against whatever is installed by the time the
     * platform sends it. Either way the id is fixed, so a duplicate replaces
     * rather than stacks.
     */
    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled()
    fun notifyInstalled(context: Context, version: String) {
        // Not redundant: this can be the first thing a brand-new process does,
        // launched by the session callback alone with no service having started,
        // and posting to a channel that does not exist yet is dropped in silence
        // from Android 8 — which would lose the only notice of the install.
        Notifier.ensureChannels(context)
        val mgr = NotificationManagerCompat.from(context)
        if (!mgr.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context,
            "installed:$version".hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, Notifier.CHANNEL_UPDATES)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(
                if (version.isNotBlank()) {
                    context.getString(R.string.update_installed_title, version)
                } else {
                    context.getString(R.string.update_installed_title_generic)
                },
            )
            .setContentText(context.getString(R.string.update_installed_text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open)
            .build()
        mgr.notify(INSTALLED_ID, n)
    }
}
