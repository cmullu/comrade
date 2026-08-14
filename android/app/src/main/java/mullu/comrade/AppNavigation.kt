package mullu.comrade

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One-shot "open this" requests coming from outside the composition —
 * today, a tapped notification.
 *
 * [StateFlow]s rather than Activity fields so a request survives the gap
 * between the intent arriving and [MainActivity]'s shell existing: tapping
 * "the companion model is ready" while the vault is locked parks the request
 * here, and the user lands on Tara right after unlocking instead of on the
 * chat list. The consumer clears each one ([consume]/[consumePeer]) so a
 * configuration change doesn't re-navigate.
 */
object AppNavigation {

    /** Intent extra carrying a [MainTab]-ish key, e.g. [mullu.comrade.model.ModelCatalog.TAB_TARA]. */
    const val EXTRA_OPEN_TAB = "mullu.comrade.extra.OPEN_TAB"

    /**
     * Intent extra carrying the npub of a conversation to open — a tapped
     * message notification (WP11 in `docs/COMMS_ARCHITECTURE.md`).
     */
    const val EXTRA_OPEN_PEER = "mullu.comrade.extra.OPEN_PEER"

    /**
     * Destinations that aren't bottom-nav tabs, carried in [EXTRA_OPEN_TAB]
     * because they answer the same question ("where should this tap land?").
     * Settings is a pushed screen reached from the drawer; requests is a
     * sub-screen of Chats.
     */
    const val SCREEN_SETTINGS = "settings"
    const val SCREEN_REQUESTS = "requests"

    /** The reading shelf, a sub-screen of Focus — where a share lands. */
    const val SCREEN_SHELF = "shelf"

    private val _requestedTab = MutableStateFlow<String?>(null)
    val requestedTab: StateFlow<String?> = _requestedTab

    private val _requestedPeer = MutableStateFlow<String?>(null)
    val requestedPeer: StateFlow<String?> = _requestedPeer

    /**
     * A payload shared into Comrade from another app, waiting to be saved.
     *
     * Parked here rather than saved in `onCreate` for the reason the tab requests
     * are: the shelf lives inside the encrypted vault, so a share that arrives
     * while Comrade is locked cannot be written yet. The one-bit alternative —
     * dropping it and hoping the user shares again — is the worst outcome
     * available, because the whole point of the share sheet is that it catches
     * something in the moment it was noticed.
     *
     * Held in memory only: a shared article sitting in a file or a preference
     * outside the vault would be exactly the plaintext-at-rest leak the store
     * exists to prevent.
     */
    private val _sharedText = MutableStateFlow<SharedPayload?>(null)
    val sharedText: StateFlow<SharedPayload?> = _sharedText

    /** `Intent.EXTRA_SUBJECT` and `EXTRA_TEXT`, as the sending app supplied them. */
    data class SharedPayload(val subject: String?, val body: String)

    /** Record a navigation request; blank/absent keys are ignored. */
    fun request(tab: String?) {
        if (!tab.isNullOrBlank()) _requestedTab.value = tab
    }

    /** Record an "open this conversation" request; blank/absent keys are ignored. */
    fun requestPeer(peer: String?) {
        if (!peer.isNullOrBlank()) _requestedPeer.value = peer
    }

    /** Called once the tab/screen request has been honoured. */
    fun consume() {
        _requestedTab.value = null
    }

    /** Called once the conversation request has been honoured. */
    fun consumePeer() {
        _requestedPeer.value = null
    }

    /**
     * Record something shared into the app. A blank body is ignored — several
     * apps send an `ACTION_SEND` with only a MIME type when a share is cancelled.
     */
    fun requestShare(subject: String?, body: String?) {
        if (body.isNullOrBlank()) return
        _sharedText.value = SharedPayload(subject?.takeIf { it.isNotBlank() }, body)
    }

    /** Called once the shared payload has been saved (or refused). */
    fun consumeShare() {
        _sharedText.value = null
    }
}
