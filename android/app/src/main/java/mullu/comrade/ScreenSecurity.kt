package mullu.comrade

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.WindowManager

/**
 * Whether the app's window may be screenshotted or screen-recorded.
 *
 * ## Screenshots are allowed by default, and that is a deliberate reversal
 *
 * This Activity used to set `FLAG_SECURE` in `onCreate` and never clear it, so
 * **nothing** in the app could be screenshotted or appear in a screen recording
 * — not a chat, not a journal entry, not the feed, not the settings screen. The
 * stated reason ("screens can display key material", AUDIT S5 / M1-6) does not
 * survive checking: no screen in this app, or in the Flutter one, renders a
 * secret key. What is displayed is an **npub**, which is public by construction
 * and shown next to the words "names repeat, keys don't".
 *
 * So the flag was blocking ordinary, legitimate uses — screenshot a plan to send
 * to someone, keep a copy of a journal entry, attach a picture of a bug to a
 * report — to protect something that was not on screen. Nor can it stop the
 * threat it resembles: anyone holding the phone can photograph the display.
 *
 * Blocking is therefore now the **user's** choice, off by default, and the
 * preference lives here rather than in the encrypted vault for two reasons: it
 * has to be readable before the vault is unlocked (the window flag must be right
 * for the very first frame), and it is a property of this device rather than of
 * the identity.
 *
 * Both frontends read this one object — the Compose settings screen writes it
 * directly, and the Flutter app's `ScreenSecurityChannel` delegates to it — so
 * the two cannot disagree about a preference stored under one key. (This file
 * lives in the legacy tree because that tree is staged into the Flutter module's
 * build as well; see `app/android/app/build.gradle.kts`.)
 *
 * What it deliberately does not claim: blocking screenshots stops the *operating
 * system* capturing the screen. It cannot stop a second phone pointed at it, and
 * it says nothing about the person you are talking to — their device is theirs.
 * The settings copy states both.
 */
object ScreenSecurity {
    /**
     * Its own file rather than `comrade_prefs`: this is read on the UI thread in
     * `onCreate` before anything else is touched, and a small dedicated file
     * keeps that read cheap.
     */
    private const val PREFS_NAME = "comrade_screen_security"
    private const val KEY_BLOCKED = "block_screenshots"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** False — screenshots work — unless the user has turned blocking on. */
    fun isBlocked(context: Context): Boolean = prefs(context).getBoolean(KEY_BLOCKED, false)

    fun setBlocked(context: Context, blocked: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLOCKED, blocked).apply()
    }

    /** Apply [secure] to [activity]'s window. Main thread only. */
    fun apply(activity: Activity, secure: Boolean) {
        if (secure) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /** Read the stored preference and apply it. */
    fun applyPreference(activity: Activity) = apply(activity, isBlocked(activity))
}
