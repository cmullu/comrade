/// Notification channels, notification clearing, the POST_NOTIFICATIONS grant,
/// and the notification → tab navigation hop.
///
/// See `../../../android/app/src/main/kotlin/mullu/comrade/PLATFORM_CHANNELS.md`
/// §9.
///
/// **Notification channel ids are frozen and unreachable from here.** The calls
/// channel is `comrade_calls_v2`: the `_v2` exists because channel settings are
/// sticky once created, so silencing the original id would never have taken
/// effect for upgrading installs, and it carries `setSound(null, null)` so the
/// native `Ringer` is the only thing that rings. Changing the id, or giving it
/// a sound, double-rings every incoming call on every existing install. This
/// API can only ensure the known set exists — it cannot create or alter one.
library;

import 'dart:async';

import 'package:flutter/services.dart';

import 'channels.dart';

class SystemChannel {
  SystemChannel({MethodChannel? methods})
      : _methods = methods ?? MethodChannel(Channels.system) {
    _methods.setMethodCallHandler(_handle);
  }

  final MethodChannel _methods;
  final _tabs = StreamController<String>.broadcast();
  final _peers = StreamController<String>.broadcast();

  /// Tab requests from a notification the user tapped.
  ///
  /// The only Kotlin → Dart direction in the whole contract, and fire-and-forget
  /// by construction: nothing native waits on it, so a detached engine costs
  /// nothing. A request that arrived while no engine was attached — a cold start
  /// from a notification is exactly that — is stashed natively; call
  /// [consumePendingTab] once at startup to collect it.
  Stream<String> get openTabRequests => _tabs.stream;

  /// Conversation requests from a tapped **message** notification — the npub of
  /// the thread to open (WP11). Same stash-and-collect rule as
  /// [openTabRequests]; see [consumePendingPeer].
  Stream<String> get openConversationRequests => _peers.stream;

  Future<Object?> _handle(MethodCall call) async {
    switch (call.method) {
      case 'openTab':
        final tab = (call.arguments as Map?)?['tab'] as String?;
        if (tab != null && tab.isNotEmpty) _tabs.add(tab);
      case 'openConversation':
        final peer = (call.arguments as Map?)?['peer'] as String?;
        if (peer != null && peer.isNotEmpty) _peers.add(peer);
    }
    return null;
  }

  /// Register the app's notification channels. Idempotent; safe at every start.
  Future<void> ensureNotificationChannels() =>
      _methods.invokeMethod<void>('ensureNotificationChannels');

  /// Whether POST_NOTIFICATIONS is granted (always true below Android 13).
  Future<bool> hasNotificationPermission() async =>
      await _methods.invokeMethod<bool>('hasNotificationPermission') ?? false;

  /// Ask for POST_NOTIFICATIONS. Returns whether it is now granted; does not
  /// throw on refusal, because a refused notification permission is a normal
  /// state the app keeps working in (with quieter delivery).
  Future<bool> requestNotificationPermission() async =>
      await _methods.invokeMethod<bool>('requestNotificationPermission') ??
      false;

  /// Clear every notification posted for this peer — e.g. on opening the chat.
  Future<void> clearForPeer(String peer) =>
      _methods.invokeMethod<void>('clearForPeer', {'peer': peer});

  /// Clear only the incoming-call notification for this peer.
  ///
  /// Rarely needed from Dart: `CallStateReactor` already clears it natively on
  /// every call transition, precisely so it works with no UI attached.
  Future<void> clearCall(String peer) =>
      _methods.invokeMethod<void>('clearCall', {'peer': peer});

  /// Collect a tab request that arrived before the engine was listening.
  /// Call once during startup; returns null when there is none.
  Future<String?> consumePendingTab() =>
      _methods.invokeMethod<String?>('consumePendingTab');

  /// The same, for a conversation named by a tapped message notification.
  Future<String?> consumePendingPeer() =>
      _methods.invokeMethod<String?>('consumePendingPeer');

  /// Whether the OS will deliver *any* notification from Comrade. False means
  /// nothing reaches the user in the background — including calls.
  Future<bool> areNotificationsEnabled() async =>
      await _methods.invokeMethod<bool>('areNotificationsEnabled') ?? false;

  /// Open the OS's own per-app notification screen, where the channels live.
  Future<void> openNotificationSettings() =>
      _methods.invokeMethod<void>('openNotificationSettings');

  // ── Per-conversation mute ──────────────────────────────────────────────────
  //
  // Native state, not Dart state, and deliberately: the notification path that
  // consults it runs with no engine attached. Device-local by construction —
  // Comrade has no server to sync a preference through, and the settings card
  // says so.

  /// Every muted conversation's npub.
  Future<Set<String>> mutedPeers() async {
    final List<Object?>? peers =
        await _methods.invokeMethod<List<Object?>>('mutedPeers');
    return (peers ?? const <Object?>[]).whereType<String>().toSet();
  }

  Future<bool> isMuted(String peer) async =>
      await _methods
          .invokeMethod<bool>('isMuted', <String, Object?>{'peer': peer}) ??
      false;

  /// Mute or unmute one conversation. Muting also clears anything already in
  /// the shade for that peer — otherwise the buzz it was meant to stop sits
  /// there afterwards.
  Future<void> setMuted(String peer, {required bool muted}) =>
      _methods.invokeMethod<void>(
          'setMuted', <String, Object?>{'peer': peer, 'muted': muted});

  Future<void> unmuteAll() => _methods.invokeMethod<void>('unmuteAll');

  void dispose() {
    _methods.setMethodCallHandler(null);
    _tabs.close();
    _peers.close();
  }
}
