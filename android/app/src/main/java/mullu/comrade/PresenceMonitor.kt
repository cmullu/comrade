package mullu.comrade

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live presence of the user's comrades, as a [StateFlow] any screen can
 * collect for a dot next to a name.
 *
 * Same shape as [MeshStatusMonitor]: this object holds no polling logic of its
 * own — [RelayConnectionService]'s [ChatEventRouter] pushes updates in as
 * `comrade_presence` events arrive, and screens seed it from
 * [ComradeCore.comrades] when they open.
 *
 * The presence rules themselves live in the Rust core (see
 * `comrade_core::presence`): what reaches here is already "this peer is
 * online / is not", with expiry, replay and mutual-consent handling applied.
 * What is decided *here* is only whether a given change is worth a
 * notification — see [shouldNotify], kept pure so a plain JVM test can pin it.
 */
object PresenceMonitor {

    /** peer npub → online right now. Absent means "no beacon on file". */
    private val _online = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val online: StateFlow<Map<String, Boolean>> = _online.asStateFlow()

    fun isOnline(peer: String): Boolean = _online.value[peer] == true

    /**
     * Record a peer's presence and report whether this was a change *into*
     * being online — the edge a notification belongs on. A repeated "still
     * online" (the native side suppresses most of these, but a screen seeding
     * itself replays them) returns false.
     */
    fun update(peer: String, online: Boolean): Boolean {
        val was = _online.value[peer] == true
        _online.update { it + (peer to online) }
        return shouldNotify(wasOnline = was, isOnline = online)
    }

    /** Seed from a full [ComradeCore.comrades] snapshot, notifying about nothing. */
    fun seed(comrades: List<ComradeCore.ComradeInfo>) {
        _online.value = comrades.associate { it.npub to it.online }
    }

    /** Forget everything — used when the vault locks, so a stale dot can't outlive it. */
    fun clear() {
        _online.value = emptyMap()
    }

    /**
     * Whether a presence change deserves a notification: only the transition
     * into online does. Going offline updates the dot silently (nobody wants
     * to be told their friend closed an app), and a repeat of a state we
     * already showed is not news.
     */
    fun shouldNotify(wasOnline: Boolean, isOnline: Boolean): Boolean = isOnline && !wasOnline
}
