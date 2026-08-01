package mullu.comrade.ui

/**
 * Which transport a message tries first, as data.
 *
 * Comrade can put a DM on a Nostr relay or seal it onto the local network
 * (`docs/OFFLINE_DELIVERY.md`), and the user chooses the order from the app
 * bar. This file is that choice, kept free of Compose/Android imports so plain
 * JVM tests (`TransportPrecedenceTest`) can pin it — the ordering is the whole
 * feature, and an inverted one is invisible until a message takes the wrong
 * radio.
 *
 * Precedence is an order, **not** an exclusion: whichever route leads, a
 * message the preferred one cannot carry still takes the other. Nothing here
 * can strand a message; [orderOf] returns both routes, always.
 *
 * The Dart port is `app/lib/src/util/transport_precedence.dart`; the two must
 * agree, so a change here that isn't mirrored there fails on the other
 * frontend rather than drifting silently (see `docs/FRONTEND_STRATEGY.md`).
 * Wording stays with the caller, exactly as it does for [conversationMenu] —
 * Android string resources remain the single source of copy.
 */
enum class TransportRoute {
    /** Nostr relays: reaches anyone, anywhere, and needs the internet. */
    Relay,

    /** The Saathi mesh: reaches whoever shares this WiFi, and needs no internet. */
    LocalNetwork,
}

/** What the local network is actually doing, which is the only live signal here. */
enum class LocalMeshState {
    /** The mesh engine is not running (the vault is locked). */
    Off,

    /** Running, but nobody else has been discovered on this network yet. */
    Searching,

    /** Running, with at least one device in reach. */
    Reaching,
}

/**
 * Precedence is stored as the active workspace, because `OffGridTravel` has
 * always meant "the mesh replaces relays" — this makes that label route.
 */
object TransportPrecedence {
    const val LOCAL_FIRST_WORKSPACE: String = "OffGridTravel"
    const val RELAY_FIRST_WORKSPACE: String = "Base"

    /**
     * Both routes, in the order the router tries them for [workspaceKey].
     * Always two entries — see the exclusion note on this file.
     */
    fun orderOf(workspaceKey: String): List<TransportRoute> =
        if (workspaceKey == LOCAL_FIRST_WORKSPACE) {
            listOf(TransportRoute.LocalNetwork, TransportRoute.Relay)
        } else {
            listOf(TransportRoute.Relay, TransportRoute.LocalNetwork)
        }

    /** The route tried first — the one the app bar draws at full strength. */
    fun leadOf(workspaceKey: String): TransportRoute = orderOf(workspaceKey).first()

    /** The workspace to switch to so that [lead] goes first. */
    fun workspaceFor(lead: TransportRoute): String =
        if (lead == TransportRoute.LocalNetwork) LOCAL_FIRST_WORKSPACE else RELAY_FIRST_WORKSPACE

    /**
     * Whether the switch should be offered at all.
     *
     * The couple sandbox is a different workspace, not a transport ordering,
     * and the core's transition graph refuses to go straight from it to
     * off-grid — offering the switch there could only produce a failed toggle.
     */
    fun isSwitchable(workspaceKey: String): Boolean = !workspaceKey.startsWith("CoupleSandbox")

    /**
     * The live local-network state behind the leading glyph. The relay side has
     * no equivalent on purpose: the workspace's `relayConnected` flag is a
     * label, not a reachability probe, and drawing it as one would be a lie.
     */
    fun meshStateOf(active: Boolean, peerCount: Int): LocalMeshState = when {
        !active -> LocalMeshState.Off
        peerCount > 0 -> LocalMeshState.Reaching
        else -> LocalMeshState.Searching
    }
}
