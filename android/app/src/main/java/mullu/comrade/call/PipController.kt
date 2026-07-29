package mullu.comrade.call

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.util.Rational
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Picture-in-picture for a live video call: the floating window the call
 * shrinks into.
 *
 * There are three ways in, and only one of them is a button:
 *
 *  1. **Leaving the app during a video call.** `onUserLeaveHint` on pre-31,
 *    `setAutoEnterEnabled` from 31 up (which also covers the gesture-navigation
 *    swipe home — that never delivers `onUserLeaveHint`, and without it
 *    "swipe away mid-call" just stops the video).
 *  2. **The in-call chat button**, which wants the call out of the way of the
 *     conversation: [enter].
 *  3. Nothing else. In particular this never enters PiP for an audio call —
 *     there is no picture to float, and `CallService` already keeps an audio
 *     call alive in the background.
 *
 * ## Why the whole thing is here rather than in a UI layer
 *
 * Both frontends need identical behaviour (the Compose app calls this directly;
 * the Flutter app's `PipChannel` is a thin wrapper over it), and the tricky
 * parts — the aspect-ratio clamp that would otherwise throw, the 31-vs-pre-31
 * split, "is a video call even live" — are not UI decisions. Deliberately free
 * of any Compose or Flutter import so it compiles into both apps.
 *
 * The [Activity] is held weakly and may be null: PiP is the one call feature
 * that genuinely cannot work without a window, and the correct behaviour with
 * no Activity is to answer "not supported" rather than to queue anything.
 */
object PipController {

    private const val TAG = "PipController"

    /** Portrait phone camera — the shape a video call is in by default. */
    private const val DEFAULT_ASPECT_WIDTH = 9
    private const val DEFAULT_ASPECT_HEIGHT = 16

    /** Android's own limit on a PiP window's aspect ratio. */
    private const val MAX_ASPECT = 2.39

    private var activityRef: WeakReference<Activity>? = null

    private val _inPip = MutableStateFlow(false)

    /**
     * Whether the window is in picture-in-picture *right now*.
     *
     * The single source of truth for the UI, and it has to be: the OS gets
     * there without being asked (auto-enter), and the user leaves it without
     * asking (dragging the window back), so a flag set at request time would be
     * wrong in both directions.
     */
    val inPip: StateFlow<Boolean> = _inPip.asStateFlow()

    fun attachActivity(activity: Activity) {
        activityRef = WeakReference(activity)
        applyAutoEnter()
    }

    fun detachActivity(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityRef = null
            // The window went away, and any PiP window went with it.
            _inPip.value = false
        }
    }

    /**
     * Whether this device can show a PiP window at all: false below API 26, on
     * hardware without the feature (Android Go), and when the user has revoked
     * the app's PiP permission in Settings.
     */
    fun isSupported(): Boolean {
        val activity = activityRef?.get() ?: return false
        return activity.packageManager
            .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    /**
     * Enter PiP now; returns whether the platform accepted.
     *
     * Already being in PiP counts as success — the chat button pressed twice
     * should not read as a failure and fall back to something else.
     */
    fun enter(
        aspectWidth: Int = DEFAULT_ASPECT_WIDTH,
        aspectHeight: Int = DEFAULT_ASPECT_HEIGHT,
    ): Boolean {
        val activity = activityRef?.get() ?: return false
        if (!isSupported()) return false
        if (activity.isInPictureInPictureMode) return true
        return runCatching { activity.enterPictureInPictureMode(params(aspectWidth, aspectHeight)) }
            .onFailure { Log.w(TAG, "enterPictureInPictureMode refused", it) }
            .getOrDefault(false)
    }

    /**
     * Dismiss the PiP window at the end of a call.
     *
     * `moveTaskToBack`, not a jump back to full screen: the call is over and
     * the person may well be mid-sentence in another app. Not `finish()`
     * either — the process, and any call that starts next, has to survive.
     */
    fun close() {
        val activity = activityRef?.get() ?: return
        if (!activity.isInPictureInPictureMode) return
        runCatching { activity.moveTaskToBack(true) }
            .onFailure { Log.w(TAG, "moveTaskToBack failed", it) }
    }

    /**
     * Whether leaving the app right now should float the call: a live **video**
     * call, and nothing else.
     */
    fun shouldAutoEnter(): Boolean = when (val state = CallManager.state.value) {
        is CallUiState.Active -> state.video
        is CallUiState.Connecting -> state.video
        else -> false
    }

    /**
     * Keep the platform's auto-enter flag in step with [shouldAutoEnter]
     * (API 31+). Call it whenever the call state changes.
     */
    fun applyAutoEnter() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val activity = activityRef?.get() ?: return
        runCatching {
            activity.setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAutoEnterEnabled(shouldAutoEnter())
                    .build(),
            )
        }.onFailure { Log.w(TAG, "setPictureInPictureParams failed", it) }
    }

    /**
     * `Activity.onUserLeaveHint`. The pre-31 half of auto-enter; from 31 up
     * [applyAutoEnter] has already handled it and entering again here would be
     * a redundant transition.
     */
    fun onUserLeaving() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        if (!shouldAutoEnter()) return
        enter()
    }

    /** `Activity.onPictureInPictureModeChanged`. */
    fun onPipModeChanged(active: Boolean) {
        _inPip.value = active
    }

    /**
     * `Activity.onStop` / `onStart`: the app's video surface went away, or came
     * back.
     *
     * This is where "don't record video nobody is displaying" is actually
     * enforced, and it is deliberately here rather than in either UI layer —
     * the guarantee should not depend on which frontend is running, or on a
     * Dart engine being attached. A PiP window *is* a surface showing the call,
     * so it does not suspend.
     */
    fun onWindowVisibilityChanged(visible: Boolean) {
        val activity = activityRef?.get()
        val showing = visible || activity?.isInPictureInPictureMode == true
        CallManager.setVideoCaptureSuspended(!showing)
    }

    private fun params(aspectWidth: Int, aspectHeight: Int): PictureInPictureParams {
        val width = aspectWidth.coerceAtLeast(1)
        val height = aspectHeight.coerceAtLeast(1)
        val ratio = width.toDouble() / height.toDouble()
        // Outside Android's permitted band `enterPictureInPictureMode` throws,
        // so a frame geometry that drifts there must not take the call down.
        val clamped = when {
            ratio > MAX_ASPECT -> Rational(239, 100)
            ratio < 1.0 / MAX_ASPECT -> Rational(100, 239)
            else -> Rational(width, height)
        }
        return PictureInPictureParams.Builder().setAspectRatio(clamped).build()
    }
}
