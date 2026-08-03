package mullu.comrade.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.io.File
import mullu.comrade.R

/**
 * The one place an update install is started from, and the one place the
 * installer's answer arrives.
 *
 * ## Why an Activity, when nothing is drawn
 *
 * `PackageInstaller` answers `STATUS_PENDING_USER_ACTION` by handing back an
 * Intent for its own confirmation dialog and expecting the app to start it.
 * Starting an activity is exactly the thing Android has spent several releases
 * restricting, and both restrictions applied to the `BroadcastReceiver` this
 * replaces:
 *
 * - **Notification trampolines** (Android 12, API 31). A notification whose
 *   `contentIntent` targets a receiver or service which then calls
 *   `startActivity()` is blocked. That was the "ready to install" tap.
 * - **Background activity starts** (Android 10, API 29, tightened in 14). A
 *   receiver has no window of its own, so the confirmation start depended on
 *   whatever else the app happened to have on screen.
 *
 * Neither throws. `startActivity` returns normally and the dialog simply never
 * appears — which is how the app came to sit on "Waiting for Android to install
 * it…" with no error to show. An Activity has none of this problem: it is
 * resumed and on top when it starts the dialog, which is the one context the
 * platform has never restricted.
 *
 * It draws nothing (`Theme.Translucent.NoTitleBar`, no `setContentView`) and
 * lives in its own excluded-from-recents task, so from the user's side there is
 * a tap and then the system's install dialog.
 *
 * ## Lifecycle
 *
 * The work is dispatched from [onResume], never from [onCreate] or
 * [onNewIntent], because a relaunched instance runs `onNewIntent` *before* it is
 * resumed and starting the dialog there would put us back in exactly the
 * background-start case this exists to avoid.
 *
 * The instance stays alive while the copy runs, so the session's callback lands
 * on a resumed activity. If it is destroyed anyway — the user backs out, or the
 * OS reclaims it — the callback's `PendingIntent` launches a fresh one and the
 * work carries on in [UpdateInstaller]'s worker either way.
 */
class UpdateInstallActivity : Activity() {

    /**
     * Whether the intent currently held by [getIntent] has been acted on.
     * Guards against re-running the same install when the activity is merely
     * resumed again (returning from the system dialog, for instance).
     */
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Deliberately no setContentView: this activity is a context to start
        // the system's dialog from, not a screen.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handled = false
    }

    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent == null) {
            finish()
            return
        }
        if (intent.hasExtra(PackageInstaller.EXTRA_STATUS)) {
            onSessionStatus(intent)
        } else {
            startInstall(intent)
        }
    }

    // ── Starting ─────────────────────────────────────────────────────────────

    private fun startInstall(intent: Intent) {
        UpdateInstaller.clearReady(this)
        val version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
        val apk = intent.getStringExtra(EXTRA_PATH)?.let(::File)
        if (apk == null || !apk.exists()) {
            fail(getString(R.string.update_gone_before_install))
            return
        }
        if (!UpdateInstaller.canInstall(this)) {
            // Not a dead end: the card shows the reason and the button that
            // opens the system screen granting it.
            fail(getString(R.string.update_install_permission_needed))
            return
        }
        // Returns immediately; the copy runs on UpdateInstaller's worker and the
        // session reports back through statusIntent() below. This instance stays
        // alive so that report lands on a resumed activity — unless the work
        // ends without committing a session, in which case no report is coming
        // and staying would strand an invisible activity on top of the app.
        UpdateInstaller.install(this, apk, version, onFailedToCommit = ::finish)
    }

    // ── The installer's answer ───────────────────────────────────────────────

    private fun onSessionStatus(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val path = intent.getStringExtra(EXTRA_PATH)
        when (val outcome = UpdateInstall.outcomeOf(status, message)) {
            UpdateInstall.Outcome.NeedsConfirmation -> showConfirmation(intent)

            UpdateInstall.Outcome.Installed -> {
                // The process is usually replaced moments after this, so treat
                // it as bookkeeping only: drop the APK and the state so a
                // restarted app does not offer to install what it is running.
                path?.let { File(it).delete() }
                UpdateDownloads.update(UpdateDownloadState.Idle)
                UpdateInstaller.clearReady(this)
                finish()
            }

            UpdateInstall.Outcome.Cancelled -> {
                // The APK is kept: they may well tap Install again, and making
                // them re-download it would be rude.
                fail(getString(R.string.update_install_cancelled))
            }

            UpdateInstall.Outcome.NoSpace -> fail(getString(R.string.update_install_no_space))

            is UpdateInstall.Outcome.Failed -> {
                Log.w(TAG, "install failed: status=$status message=$message")
                fail(outcome.message ?: getString(R.string.update_install_failed_generic))
            }
        }
    }

    /**
     * Start the OS's own confirmation dialog. This is the whole reason this
     * class exists — see the class doc.
     */
    private fun showConfirmation(intent: Intent) {
        val confirm = confirmationIntent(intent)
        if (confirm == null) {
            Log.w(TAG, "pending-user-action status carried no confirmation Intent")
            fail(getString(R.string.update_install_failed_generic))
            return
        }
        // No NEW_TASK: started from this resumed activity, the dialog belongs on
        // top of it. Adding NEW_TASK here would hand it to a separate task and
        // reintroduce the background-start question this class exists to settle.
        val started = runCatching { startActivity(confirm) }
            .onFailure { Log.w(TAG, "could not show the install confirmation", it) }
            .isSuccess
        if (!started) {
            fail(getString(R.string.update_install_failed_generic))
            return
        }
        // Left in Installing: the dialog is up and the next status decides. This
        // instance finishes because the dialog is its own activity now, and the
        // session's next callback relaunches us.
        finish()
    }

    @Suppress("DEPRECATION") // getParcelableExtra(String, Class) is API 33+
    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun fail(message: String) {
        UpdateDownloads.update(UpdateDownloadState.Failed(message))
        finish()
    }

    companion object {
        private const val TAG = "UpdateInstallActivity"
        private const val EXTRA_VERSION = "mullu.comrade.extra.UPDATE_VERSION"
        private const val EXTRA_PATH = "mullu.comrade.extra.UPDATE_PATH"

        /**
         * "Install this APK." Used by the settings card and by the "ready to
         * install" notification's tap — the same entry point either way.
         */
        fun installIntent(context: Context, version: String, path: String): Intent =
            Intent(context, UpdateInstallActivity::class.java)
                .putExtra(EXTRA_VERSION, version)
                .putExtra(EXTRA_PATH, path)
                // Started from a PendingIntent, from a notification, and from a
                // non-Activity context, so it always needs a task of its own.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * Where a committed session reports back. Distinguished from
         * [installIntent] by the platform's own `EXTRA_STATUS`, which it fills
         * in at send time — hence the mutable `PendingIntent` wrapping it.
         */
        fun statusIntent(context: Context, version: String, path: String): Intent =
            installIntent(context, version, path)

        /** Start an install with no UI of the caller's own in the way. */
        fun start(context: Context, version: String, path: String) {
            runCatching { context.startActivity(installIntent(context, version, path)) }
                .onFailure {
                    Log.w(TAG, "could not start the installer activity", it)
                    UpdateDownloads.update(
                        UpdateDownloadState.Failed(
                            context.getString(R.string.update_install_failed_generic),
                        ),
                    )
                }
        }
    }
}
