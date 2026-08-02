import 'package:comrade/src/util/transport_precedence.dart';
import 'package:flutter_test/flutter_test.dart';

/// Pins the transport ordering behind the app-bar switch: which route leads,
/// that the other one is never dropped, and what the local glyph reports.
///
/// Mirrors `TransportPrecedenceTest.kt` — a change on one frontend that isn't
/// made on the other shows up as a failing test rather than as silent UI drift.
void main() {
  group('TransportPrecedence', () {
    test('relays lead until the user says otherwise', () {
      expect(
        TransportPrecedence.leadOf(TransportPrecedence.relayFirstWorkspace),
        TransportRoute.relay,
      );
      // An unknown key is not a reason to route strangely — the default order
      // is the safe one, because relays reach people who are not nearby.
      expect(TransportPrecedence.leadOf('SomethingNew'), TransportRoute.relay);
    });

    test('off-grid puts the local network first', () {
      expect(
        TransportPrecedence.orderOf(TransportPrecedence.localFirstWorkspace),
        <TransportRoute>[TransportRoute.localNetwork, TransportRoute.relay],
      );
    });

    test('precedence is an order, not an exclusion', () {
      // Whichever way round, both routes are still on the list: a message the
      // preferred transport cannot carry must still take the other one.
      for (final String key in <String>[
        TransportPrecedence.relayFirstWorkspace,
        TransportPrecedence.localFirstWorkspace,
      ]) {
        expect(
          TransportPrecedence.orderOf(key).toSet(),
          <TransportRoute>{TransportRoute.relay, TransportRoute.localNetwork},
        );
      }
    });

    test('switching round-trips through the workspace key', () {
      for (final TransportRoute lead in TransportRoute.values) {
        expect(
          TransportPrecedence.leadOf(TransportPrecedence.workspaceFor(lead)),
          lead,
        );
      }
    });

    test('the couple sandbox is not a transport ordering', () {
      expect(
        TransportPrecedence.isSwitchable(
            TransportPrecedence.relayFirstWorkspace),
        isTrue,
      );
      expect(
        TransportPrecedence.isSwitchable(
            TransportPrecedence.localFirstWorkspace),
        isTrue,
      );
      expect(TransportPrecedence.isSwitchable('CoupleSandboxSakha'), isFalse);
      expect(TransportPrecedence.isSwitchable('CoupleSandboxSakhi'), isFalse);
    });

    test('the local glyph separates off from looking from reaching', () {
      expect(
        TransportPrecedence.meshStateOf(active: false, peerCount: 0),
        LocalMeshState.off,
      );
      // Peers reported while the engine is down are stale, not reachable.
      expect(
        TransportPrecedence.meshStateOf(active: false, peerCount: 4),
        LocalMeshState.off,
      );
      expect(
        TransportPrecedence.meshStateOf(active: true, peerCount: 0),
        LocalMeshState.searching,
      );
      expect(
        TransportPrecedence.meshStateOf(active: true, peerCount: 1),
        LocalMeshState.reaching,
      );
    });
  });
}
