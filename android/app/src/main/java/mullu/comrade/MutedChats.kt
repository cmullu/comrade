package mullu.comrade

import android.content.Context

/**
 * Which conversations the user asked not to be notified about.
 *
 * **Device-local, and the UI says so.** Comrade has no server and no
 * cross-device settings sync, so a mute set on a phone cannot follow the user
 * to a desktop — pretending otherwise would be the kind of switch that implies
 * more than it does. It also lives *outside* the encrypted vault, in plain
 * SharedPreferences, for one specific reason: the router must be able to decide
 * whether to notify before or without an unlock, and a mute that only works
 * once you have opened the app is not a mute. The cost is that an npub set is
 * readable by anything that can already read this app's private storage — the
 * same trade `mullu.comrade.ScreenSecurity` makes, and the same reasoning: it
 * is a list of keys, all of which are public by construction.
 *
 * Read on the notification path, so it is a plain synchronous
 * SharedPreferences read (backed by the in-memory map after first load) rather
 * than anything that could stall event draining.
 */
object MutedChats {

    private const val PREFS = "mullu.comrade.Notifications"
    private const val KEY_MUTED = "muted_peers"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every muted peer. A copy: the returned set from prefs must not be mutated. */
    fun muted(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_MUTED, emptySet())?.toSet() ?: emptySet()

    fun isMuted(context: Context, peer: String): Boolean = peer in muted(context)

    fun setMuted(context: Context, peer: String, muted: Boolean) {
        val updated = muted(context).toMutableSet().apply {
            if (muted) add(peer) else remove(peer)
        }
        prefs(context).edit().putStringSet(KEY_MUTED, updated).apply()
    }

    /** Unmute everything — the escape hatch in Settings for "why is it quiet?". */
    fun unmuteAll(context: Context) {
        prefs(context).edit().remove(KEY_MUTED).apply()
    }
}
