package mullu.comrade.attention

import android.content.Context

/**
 * A nightly window in which Comrade stays quiet — phase 0 of
 * `docs/ATTENTION.md`.
 *
 * The point is sleep: a phone that buzzes at 01:00 costs an hour of it, and
 * the notification the user would have read at 08:00 just as well. So during
 * the window every notification class is suppressed **except a ringing call**,
 * which is the one thing that cannot wait and cannot be caught up on — the
 * same line [mullu.comrade.NotificationPolicy] already draws for per-chat
 * mute.
 *
 * ## Why not Android's own Do Not Disturb
 *
 * DND is a device-wide mode the *user* turns on, and reading or setting it
 * needs notification-policy access. This is Comrade's own, app-scoped, needs
 * no permission, and — more importantly — is honest: it silences only this
 * app, which is the only thing this app has any business silencing.
 *
 * The window is stored device-locally (plain SharedPreferences, like
 * [mullu.comrade.MutedChats]) for the same reason: the notification path must
 * be able to decide *before* the vault is unlocked, and a quiet hour that only
 * works once you have opened the app is not a quiet hour. What it stores is
 * two integers, which carry nothing about who anyone talks to.
 */
object QuietHours {

    private const val PREFS = "comrade_quiet_hours"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_START_MINUTE = "start_minute"
    private const val KEY_END_MINUTE = "end_minute"

    /** 22:00 — the default window, used the first time the switch is turned on. */
    const val DEFAULT_START_MINUTE = 22 * 60

    /** 07:00. */
    const val DEFAULT_END_MINUTE = 7 * 60

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Off unless the user turned it on. Deliberately opt-in, unlike the feed's
     * gentle stop: silencing notifications can make someone miss something
     * they wanted, so it is their call — whereas a calm feed costs nothing.
     */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Window start as minutes past local midnight. */
    fun startMinute(context: Context): Int =
        prefs(context).getInt(KEY_START_MINUTE, DEFAULT_START_MINUTE)

    /** Window end as minutes past local midnight. */
    fun endMinute(context: Context): Int = prefs(context).getInt(KEY_END_MINUTE, DEFAULT_END_MINUTE)

    fun setWindow(context: Context, startMinute: Int, endMinute: Int) {
        prefs(context).edit()
            .putInt(KEY_START_MINUTE, startMinute.coerceIn(0, MINUTES_PER_DAY - 1))
            .putInt(KEY_END_MINUTE, endMinute.coerceIn(0, MINUTES_PER_DAY - 1))
            .apply()
    }

    /**
     * Whether [nowMinute] (minutes past local midnight) falls inside the
     * window `[startMinute, endMinute)`.
     *
     * Handles the overnight case, which is the *normal* one here: 22:00→07:00
     * wraps past midnight, so a naive `start <= now && now < end` comparison
     * would make the common configuration match nothing at all. A window whose
     * ends are equal is treated as empty rather than as all day — "quiet from
     * 22:00 to 22:00" almost certainly means a mis-set picker, and silencing a
     * user's phone for 24 hours on that reading would be the worse failure.
     */
    fun isQuietAt(nowMinute: Int, startMinute: Int, endMinute: Int): Boolean = when {
        startMinute == endMinute -> false
        startMinute < endMinute -> nowMinute >= startMinute && nowMinute < endMinute
        else -> nowMinute >= startMinute || nowMinute < endMinute
    }

    /** [isQuietAt] against this device's stored window and the given local time. */
    fun isQuietNow(context: Context, nowMinute: Int): Boolean =
        isEnabled(context) && isQuietAt(nowMinute, startMinute(context), endMinute(context))

    /** `HH:MM`, for the settings summary. */
    fun formatMinute(minute: Int): String {
        val m = ((minute % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return "%02d:%02d".format(m / 60, m % 60)
    }

    const val MINUTES_PER_DAY = 24 * 60
}
