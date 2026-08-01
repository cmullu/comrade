/// Which transport a message tries first, as data.
///
/// Comrade can put a DM on a Nostr relay or seal it onto the local network
/// (`docs/OFFLINE_DELIVERY.md`), and the user chooses the order from the app
/// bar. This file is that choice, kept free of Flutter imports so a plain unit
/// test can pin it — the ordering is the whole feature, and an inverted one is
/// invisible until a message takes the wrong radio.
///
/// Precedence is an order, **not** an exclusion: whichever route leads, a
/// message the preferred one cannot carry still takes the other. Nothing here
/// can strand a message; [orderOf] returns both routes, always.
///
/// The Kotlin original is
/// `android/app/src/main/java/mullu/comrade/ui/TransportPrecedence.kt`; the two
/// must agree, so a change there that isn't mirrored here fails on this
/// frontend rather than drifting silently (see `docs/FRONTEND_STRATEGY.md`).
library;

/// The two routes a DM can take.
enum TransportRoute {
  /// Nostr relays: reaches anyone, anywhere, and needs the internet.
  relay,

  /// The Saathi mesh: reaches whoever shares this WiFi, and needs no internet.
  localNetwork,
}

/// What the local network is actually doing, which is the only live signal
/// here.
enum LocalMeshState {
  /// The mesh engine is not running (the vault is locked).
  off,

  /// Running, but nobody else has been discovered on this network yet.
  searching,

  /// Running, with at least one device in reach.
  reaching,
}

/// Precedence is stored as the active workspace, because `OffGridTravel` has
/// always meant "the mesh replaces relays" — this makes that label route.
abstract final class TransportPrecedence {
  static const String localFirstWorkspace = 'OffGridTravel';
  static const String relayFirstWorkspace = 'Base';

  /// Both routes, in the order the router tries them for [workspaceKey].
  /// Always two entries — see the exclusion note on this library.
  static List<TransportRoute> orderOf(String workspaceKey) =>
      workspaceKey == localFirstWorkspace
          ? const <TransportRoute>[
              TransportRoute.localNetwork,
              TransportRoute.relay
            ]
          : const <TransportRoute>[
              TransportRoute.relay,
              TransportRoute.localNetwork
            ];

  /// The route tried first — the one the app bar draws at full strength.
  static TransportRoute leadOf(String workspaceKey) =>
      orderOf(workspaceKey).first;

  /// The workspace to switch to so that [lead] goes first.
  static String workspaceFor(TransportRoute lead) =>
      lead == TransportRoute.localNetwork
          ? localFirstWorkspace
          : relayFirstWorkspace;

  /// Whether the switch should be offered at all.
  ///
  /// The couple sandbox is a different workspace, not a transport ordering, and
  /// the core's transition graph refuses to go straight from it to off-grid —
  /// offering the switch there could only produce a failed toggle.
  static bool isSwitchable(String workspaceKey) =>
      !workspaceKey.startsWith('CoupleSandbox');

  /// The live local-network state behind the leading glyph. The relay side has
  /// no equivalent on purpose: the workspace's `relayConnected` flag is a
  /// label, not a reachability probe, and drawing it as one would be a lie.
  static LocalMeshState meshStateOf({
    required bool active,
    required int peerCount,
  }) {
    if (!active) return LocalMeshState.off;
    return peerCount > 0 ? LocalMeshState.reaching : LocalMeshState.searching;
  }
}
