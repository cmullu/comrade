package mullu.comrade

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import mullu.comrade.call.CallManager
import mullu.comrade.call.PipController

/**
 * The single Activity hosting the Flutter UI.
 *
 * Deliberately thin. Everything that used to live in the Compose
 * `MainActivity` — the call-state side effects, notification routing,
 * permission gating — has moved either into Dart (via the channels) or into
 * engine-independent native code ([CallStateReactor], [ComradePlugin]).
 * What is left here is what genuinely needs an *Activity*:
 *
 *  1. registering this Activity's window with [CallStateReactor] so an
 *     incoming call can light the screen over the keyguard;
 *  2. forwarding a notification's `AppNavigation.EXTRA_OPEN_TAB` to Dart;
 *  3. the picture-in-picture callbacks — `onUserLeaveHint` and
 *     `onPictureInPictureModeChanged` are delivered to the Activity and
 *     nowhere else.
 */
class MainActivity : FlutterActivity() {

    private val plugin = ComradePlugin()

    /**
     * Activity-scoped, and only for keeping the OS's auto-enter-PiP setting in
     * step with whether a video call is live. Cancelled in [onDestroy].
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        flutterEngine.plugins.add(plugin)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Screenshots are allowed unless the user asked for them to be blocked.
        // Applied here, before the first frame, because the preference has to
        // hold before any Flutter engine exists — a window that starts
        // unprotected and is secured a frame later has already been captured by
        // the recents thumbnail. See ScreenSecurityChannel for why the
        // Compose app's unconditional FLAG_SECURE is gone.
        ScreenSecurity.applyPreference(this)
        CallStateReactor.attachActivity(this)
        forwardRequestedTab(intent)
        // A video call that starts (or ends) has to update the platform's
        // auto-enter flag, or swiping home lands on the wrong behaviour.
        scope.launch {
            CallManager.state.collectLatest { PipController.applyAutoEnter() }
        }
    }

    /**
     * Pressing home (or the recents gesture on pre-31) during a video call
     * floats the call instead of stopping it. Below API 31 this is the *only*
     * hook for that; from 31 up `setAutoEnterEnabled` has already handled it
     * and [PipController.onUserLeaving] returns without acting.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PipController.onUserLeaving()
    }

    /**
     * The single source of truth Dart listens to for "are we in PiP".
     *
     * It fires for the OS's own transitions too — the user dragging the window
     * back to full screen, or expanding it — which is exactly why the Dart side
     * never assumes a mode change happened just because it asked for one.
     */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipController.onPipModeChanged(isInPictureInPictureMode)
        // Entering PiP keeps the camera running (the window is showing the
        // call); leaving a *stopped* state resumes it. onStop/onStart below
        // handle the ordinary background case.
        PipController.onWindowVisibilityChanged(isInPictureInPictureMode)
    }

    /**
     * Nothing is displaying the local video any more, so stop capturing it.
     *
     * Enforced here — natively, in the Activity — rather than only in Dart, so
     * the guarantee holds whatever the Flutter engine is doing. `CallManager`
     * makes it idempotent, so Dart's own lifecycle handling on top of this is
     * belt-and-braces rather than a second, conflicting decision.
     */
    override fun onStop() {
        super.onStop()
        PipController.onWindowVisibilityChanged(visible = false)
    }

    override fun onStart() {
        super.onStart()
        PipController.onWindowVisibilityChanged(visible = true)
    }

    /**
     * `launchMode="singleTop"` (see the manifest) means a second tap on a
     * notification re-delivers here rather than creating a new Activity, so the
     * tab extra has to be read from both entry points.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forwardRequestedTab(intent)
    }

    override fun onDestroy() {
        CallStateReactor.detachActivity(this)
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Fire-and-forget. If the engine is not attached yet (a cold start from a
     * notification is exactly that case), `SystemChannel` stashes the request
     * and Dart picks it up with `consumePendingTab` once it is running.
     */
    private fun forwardRequestedTab(intent: Intent?) {
        val tab = intent?.getStringExtra(AppNavigation.EXTRA_OPEN_TAB) ?: return
        plugin.systemChannel?.requestTab(tab)
    }
}
