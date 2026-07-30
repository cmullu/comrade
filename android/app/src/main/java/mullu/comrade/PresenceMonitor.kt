package mullu.comrade

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live presence of the user's comrades, as a [StateFlow] any screen can
 * collect for a dot and a "last seen" line next to a name.
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

    /**
     * What we know about one comrade right now. [lastSeenAt] is the send time
     * of the last beacon that had them *online* (0 if we have never caught
     * them), so it can be shown as a wall clock without ever overstating.
     */
    data class Presence(
        val online: Boolean,
        val lastSeenAt: Long = 0L,
        val peerMarkedUs: Boolean = false,
    )

    /** peer npub → what we know. Absent means "no beacon on file". */
    private val _presence = MutableStateFlow<Map<String, Presence>>(emptyMap())
    val presence: StateFlow<Map<String, Presence>> = _presence.asStateFlow()

    fun isOnline(peer: String): Boolean = _presence.value[peer]?.online == true

    fun presenceOf(peer: String): Presence? = _presence.value[peer]

    /**
     * Record a peer's presence and report whether this was a change *into*
     * being online — the edge a notification belongs on. A repeated "still
     * online" (the native side suppresses most of these, but a screen seeding
     * itself replays them) returns false.
     *
     * [at] is the beacon's send time. It only advances [Presence.lastSeenAt]
     * on the online edge: the offline edge's timestamp is either a goodbye
     * (fine) or the moment a claim lapsed (up to a TTL later than the peer was
     * actually seen), and overstating "last seen" is the one direction that
     * would lie to the user. The store keeps the authoritative value, which
     * screens re-read when the chat tick fires.
     */
    fun update(peer: String, online: Boolean, at: Long = 0L): Boolean {
        val previous = _presence.value[peer]
        val wasOnline = previous?.online == true
        _presence.update { current ->
            current + (
                peer to Presence(
                    online = online,
                    lastSeenAt = if (online) maxOf(at, previous?.lastSeenAt ?: 0L) else previous?.lastSeenAt ?: 0L,
                    peerMarkedUs = true, // any beacon at all proves they chose us
                )
                )
        }
        return shouldNotify(wasOnline = wasOnline, isOnline = online)
    }

    /** Seed from a full [ComradeCore.comrades] snapshot, notifying about nothing. */
    fun seed(comrades: List<ComradeCore.ComradeInfo>) {
        _presence.value = comrades.associate {
            it.npub to Presence(
                online = it.online,
                lastSeenAt = it.lastSeenAt,
                peerMarkedUs = it.peerMarkedUs,
            )
        }
    }

    /** Forget everything — used when the vault locks, so a stale dot can't outlive it. */
    fun clear() {
        _presence.value = emptyMap()
    }

    /**
     * Whether a presence change deserves a notification: only the transition
     * into online does. Going offline updates the dot silently (nobody wants
     * to be told their friend closed an app), and a repeat of a state we
     * already showed is not news.
     */
    fun shouldNotify(wasOnline: Boolean, isOnline: Boolean): Boolean = isOnline && !wasOnline
}
