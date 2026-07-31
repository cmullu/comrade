package mullu.comrade.channel

import android.app.Activity
import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import mullu.comrade.ScreenSecurity

/**
 * Whether this window may be screenshotted or screen-recorded.
 *
 * ## Screenshots are allowed by default, and that is a deliberate reversal
 *
 * The Compose app set `FLAG_SECURE` on its single Activity in `onCreate` and
 * never cleared it, so **nothing** in the app could be screenshotted or shown in
 * a screen recording — not a chat, not a journal entry, not the feed, not even
 * the settings screen. The stated reason (`MainActivity.kt`: "screens can
 * display key material") does not hold up: no screen in either frontend ever
 * renders a secret key. What is on screen is an *npub*, which is public by
 * construction and printed in the UI next to the words "names repeat, keys
 * don't".
 *
 * So the flag was blocking ordinary, legitimate things people do with a
 * messenger — screenshot a plan to send to someone else, keep a copy of a
 * journal entry, file a bug report with a picture of it — to protect something
 * that was not there. It also cannot stop the threat it looks like it stops:
 * anyone holding the phone can photograph the screen.
 *
 * The default is therefore now "screenshots work", with two ways to get the old
 * behaviour back where it is genuinely wanted:
 *
 *  1. A **user setting**, stored by [ScreenSecurity] — shared with the Compose
 *     frontend so both read one key, and deliberately outside the encrypted
 *     store because it must be readable and applicable before the vault is
 *     unlocked. Re-applied on every Activity create.
 *  2. `setSecureWhileVisible` — a **screen-scoped** block, held only while a
 *     surface that really does show secret material is on screen. Dart's
 *     `SecureScreen` widget takes the hold and releases it when the secret goes
 *     away, so the flag can never be left set by a screen the user has navigated
 *     off. It ships in exactly one place — the vault passphrase field *while the
 *     user has revealed it* — and exists so that adding a seed-phrase or
 *     reveal-your-nsec screen later does not mean reaching for the app-wide flag
 *     again.
 *
 * A screen-scoped hold wins over the user preference, in the safe direction: it
 * can only ever *add* protection.
 */
internal class ScreenSecurityChannel(
    messenger: BinaryMessenger,
    appContext: Context,
) : MethodChannel.MethodCallHandler {

    private val context = appContext.applicationContext

    /** Set while a `SecureScreen` is mounted. Additive over the preference. */
    private var screenScopedHolds = 0

    /**
     * Activity, when one is attached. `FLAG_SECURE` is a window flag, so with no
     * Activity there is no window to set it on — the state is still recorded and
     * applied on the next attach.
     */
    var activity: Activity? = null
        set(value) {
            field = value
            value?.let { ScreenSecurity.apply(it, effective()) }
        }

    private val methods = MethodChannel(messenger, ChannelNames.SCREEN)
        .also { it.setMethodCallHandler(this) }

    private fun effective(): Boolean =
        screenScopedHolds > 0 || ScreenSecurity.isBlocked(context)

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isBlocked" -> result.postSuccess(ScreenSecurity.isBlocked(context))
            "setBlocked" -> {
                val blocked = call.argument<Boolean>("blocked")
                if (blocked == null) {
                    result.postError(ErrorCodes.BAD_ARGUMENT, "setBlocked needs `blocked`")
                    return
                }
                ScreenSecurity.setBlocked(context, blocked)
                applyNow()
                result.postSuccess(blocked)
            }
            "setSecureWhileVisible" -> {
                val secure = call.argument<Boolean>("secure")
                if (secure == null) {
                    result.postError(ErrorCodes.BAD_ARGUMENT, "setSecureWhileVisible needs `secure`")
                    return
                }
                // Counted, not a boolean: two secret-bearing surfaces can be on
                // screen at once (a dialog over a screen), and the second one
                // closing must not clear the first one's protection.
                screenScopedHolds = if (secure) {
                    screenScopedHolds + 1
                } else {
                    (screenScopedHolds - 1).coerceAtLeast(0)
                }
                applyNow()
                result.postSuccess(effective())
            }
            else -> result.postNotImplemented()
        }
    }

    private fun applyNow() {
        val a = activity ?: return
        val secure = effective()
        a.runOnUiThread { ScreenSecurity.apply(a, secure) }
    }

    /**
     * An engine detaching does not clear a screen-scoped hold: the Activity may
     * be mid-configuration-change with a secret still on screen. Detaching from
     * the *Activity* is what releases the window, and re-attaching re-applies
     * the state — see the `activity` setter.
     */
    fun dispose() {
        methods.setMethodCallHandler(null)
    }
}
