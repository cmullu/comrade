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

  /// Tab requests from a notification the user tapped.
  ///
  /// The only Kotlin → Dart direction in the whole contract, and fire-and-forget
  /// by construction: nothing native waits on it, so a detached engine costs
  /// nothing. A request that arrived while no engine was attached — a cold start
  /// from a notification is exactly that — is stashed natively; call
  /// [consumePendingTab] once at startup to collect it.
  Stream<String> get openTabRequests => _tabs.stream;

  Future<Object?> _handle(MethodCall call) async {
    if (call.method == 'openTab') {
      final tab = (call.arguments as Map?)?['tab'] as String?;
      if (tab != null && tab.isNotEmpty) _tabs.add(tab);
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

  void dispose() {
    _methods.setMethodCallHandler(null);
    _tabs.close();
  }
}
