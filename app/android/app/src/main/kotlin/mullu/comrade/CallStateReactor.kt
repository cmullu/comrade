package mullu.comrade

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mullu.comrade.call.CallUiState
import mullu.comrade.call.CallManager
import mullu.comrade.call.Ringer

/**
 * The user-perceptible side effects of a call phase change — ringtone,
 * vibration, notification clearing, missed-call notice, lock-screen bypass —
 * moved out of the UI and made engine-independent.
 *
 * ## Why this file exists
 *
 * Under Compose these lived in `MainActivity`'s `LaunchedEffect(callState)`
 * (`android/…/MainActivity.kt:373-411`), i.e. they ran only while an Activity
 * was composed. That was already the weaker of two guarantees; under Flutter it
 * would be strictly worse, because the Flutter engine can be detached while the
 * process and every service keep running — which is the entire point of Phase 2
 * (see `channel/../PLATFORM_CHANNELS.md` §4.3).
 *
 * **An incoming call must ring with the screen off.** So the `when` below runs
 * on an application-scoped coroutine started from `Application.onCreate`, not
 * from any UI. Three of the four effects need nothing but a `Context`. The
 * fourth (`setShowOverLockScreen`) genuinely needs an Activity window, so it
 * goes through a [WeakReference] that `MainActivity` registers and clears; when
 * it is null the effect is simply skipped, exactly as it was skipped before
 * whenever no Activity happened to be composed.
 *
 * ## What was preserved
 *
 * This is a **behaviour-preserving relocation, not a rewrite**. The arms are
 * copied verbatim, including the subtle one: a call counts as *missed* only
 * when `outcome == "missed" && incoming`, because the caller's own unanswered
 * outgoing call is not missed on this device.
 *
 * Ringing audio remains `Ringer`'s exclusive job, which is why the
 * `comrade_calls_v2` notification channel is silent (`Notifier.kt:31-40`) — if
 * anything here ever also played a sound, every incoming call would double-ring.
 */
object CallStateReactor {

    private val started = AtomicBoolean(false)
    private var activityRef: WeakReference<Activity>? = null

    /**
     * Begin observing. Idempotent — `compareAndSet` picks one winner, so a
     * second call (e.g. a test harness, or an Application subclass that also
     * starts it) is a no-op rather than a second collector double-ringing.
     *
     * [scope] should be the process-lifetime scope
     * ([ComradeApplication.appScope]), not an Activity's or a Service's.
     */
    fun start(context: Context, scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch(Dispatchers.Main) {
            var lastCallPeer: String? = null
            CallManager.state.collect { st ->
                when (st) {
                    is CallUiState.Ringing -> {
                        lastCallPeer = st.peer
                        if (st.incoming) {
                            Ringer.start(appContext)
                            showOverLockScreen(true)
                        } else {
                            Ringer.stop()
                        }
                    }
                    is CallUiState.Connecting -> {
                        lastCallPeer = st.peer
                        Notifier.clearCall(appContext, st.peer)
                        Ringer.stop()
                    }
                    is CallUiState.Active -> {
                        lastCallPeer = st.peer
                        Notifier.clearCall(appContext, st.peer)
                        Ringer.stop()
                    }
                    is CallUiState.Ended -> {
                        // Missed from *this* device's perspective only when this
                        // device was the callee and the ring timed out
                        // unanswered — the caller's own unanswered outgoing call
                        // is not "missed" here.
                        if (st.outcome == "missed" && st.incoming) {
                            Notifier.notifyMissedCall(appContext, st.peer, st.peerLabel)
                        }
                        lastCallPeer?.let { Notifier.clearCall(appContext, it) }
                        Ringer.stop()
                        showOverLockScreen(false)
                    }
                    CallUiState.Idle -> {
                        lastCallPeer?.let { Notifier.clearCall(appContext, it) }
                        Ringer.stop()
                        showOverLockScreen(false)
                    }
                }
            }
        }
    }

    /** Registered by `MainActivity.onCreate`; cleared in `onDestroy`. */
    fun attachActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun detachActivity(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    /**
     * Light the screen and show over the keyguard for an incoming call, so it
     * behaves like a real phone call on a locked device. Skipped with no
     * Activity — there is no window to flag, and the ringtone plus the
     * full-screen-intent notification (`Notifier.notifyIncomingCall`, which is
     * why `USE_FULL_SCREEN_INTENT` is declared) still surface the call.
     */
    private fun showOverLockScreen(show: Boolean) {
        val activity = activityRef?.get() ?: return
        val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        if (show) activity.window.addFlags(flags) else activity.window.clearFlags(flags)
    }
}
