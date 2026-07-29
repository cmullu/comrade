/// Typed wrapper over `RelayConnectionService`'s channels.
///
/// The native side is the foreground `dataSync` service that keeps the relay
/// connection — and therefore notification delivery — alive while the vault is
/// unlocked but nothing is on screen, and that is the **sole** consumer of
/// `ComradeCore.pollEvent()`.
///
/// See `../../../android/app/src/main/kotlin/mullu/comrade/PLATFORM_CHANNELS.md`
/// §8. The load-bearing property is what this API cannot do: **there is no way
/// to drain the event queue from Dart.** `EventBus`'s three-tier priority
/// discipline (critical never-dropped, coalesced latest-per-key, feed
/// bounded-lossy) is only correct with one consumer, and that consumer is what
/// routes an incoming call signal into `CallManager` and raises the ringing
/// notification. A second drainer here would silently steal call offers from
/// the code that makes the phone ring.
///
/// What crosses instead is invalidation: [RelayState.chatTick] means "the DM
/// history changed, re-read it from Rust", not the messages themselves.
library;

import 'dart:async';

import 'package:flutter/services.dart';

import 'channels.dart';

/// Off-grid Saathi mesh connectivity.
final class MeshStatus {
  const MeshStatus({required this.active, required this.peerCount});

  static const offline = MeshStatus(active: false, peerCount: 0);

  final bool active;
  final int peerCount;
}

/// Live `EventBus` counters.
///
/// Exposed because these exist specifically to make a stuck drain loop visible:
/// a diagnostics screen watching [criticalDepth] climb is worth more than one
/// that cannot see it. [feedDrops] is expected to be non-zero on a busy relay —
/// the feed tier is bounded and lossy *by design*, so an adversarial or merely
/// chatty relay cannot grow the queue without limit.
final class EventBusStats {
  const EventBusStats({
    required this.criticalDepth,
    required this.coalescedDepth,
    required this.feedDepth,
    required this.feedDrops,
    required this.coalesceSuppressions,
    required this.lastDequeueLagMs,
  });

  static const zero = EventBusStats(
    criticalDepth: 0,
    coalescedDepth: 0,
    feedDepth: 0,
    feedDrops: 0,
    coalesceSuppressions: 0,
    lastDequeueLagMs: 0,
  );

  /// Calls, DMs, message requests. Never dropped; a rising depth means the
  /// drain loop is stuck or dead.
  final int criticalDepth;

  /// Mesh/ledger/profile/receipt status — latest-per-key only.
  final int coalescedDepth;

  /// Public feed. Bounded and lossy by design.
  final int feedDepth;

  final int feedDrops;
  final int coalesceSuppressions;
  final int lastDequeueLagMs;

  factory EventBusStats.fromMap(Map<Object?, Object?> map) => EventBusStats(
        criticalDepth: (map['criticalDepth'] as num?)?.toInt() ?? 0,
        coalescedDepth: (map['coalescedDepth'] as num?)?.toInt() ?? 0,
        feedDepth: (map['feedDepth'] as num?)?.toInt() ?? 0,
        feedDrops: (map['feedDrops'] as num?)?.toInt() ?? 0,
        coalesceSuppressions:
            (map['coalesceSuppressions'] as num?)?.toInt() ?? 0,
        lastDequeueLagMs: (map['lastDequeueLagMs'] as num?)?.toInt() ?? 0,
      );
}

/// One public-feed post.
///
/// This is the *one* place a channel carries content rather than a tick, and
/// only because the native router already keeps this list for dedup and the
/// 500-item cap — Dart's copy is a projection of it, not a second source of
/// truth. Everything else (DMs, contacts, history, profiles) comes from Rust.
final class FeedItem {
  const FeedItem({
    required this.id,
    required this.author,
    required this.content,
    required this.createdAt,
    required this.replyTo,
  });

  final String id;
  final String author;
  final String content;
  final int createdAt;
  final String? replyTo;

  factory FeedItem.fromMap(Map<Object?, Object?> map) => FeedItem(
        id: map['id'] as String? ?? '',
        author: map['author'] as String? ?? '',
        content: map['content'] as String? ?? '',
        createdAt: (map['createdAt'] as num?)?.toInt() ?? 0,
        replyTo: map['replyTo'] as String?,
      );
}

final class RelayState {
  const RelayState({
    required this.running,
    required this.chatTick,
    required this.requestTick,
    required this.mesh,
    required this.eventBus,
    required this.feed,
  });

  static const initial = RelayState(
    running: false,
    chatTick: 0,
    requestTick: 0,
    mesh: MeshStatus.offline,
    eventBus: EventBusStats.zero,
    feed: <FeedItem>[],
  );

  final bool running;

  /// Bumped whenever the DM history changed. Not a payload — re-read the
  /// conversation list and the open thread from Rust when this moves.
  final int chatTick;

  /// Bumped when a message request arrives. Reload the requests list.
  final int requestTick;

  final MeshStatus mesh;
  final EventBusStats eventBus;

  /// Newest first, capped at 500 by the native router.
  final List<FeedItem> feed;

  factory RelayState.fromMap(Map<Object?, Object?> map) {
    final mesh = map['mesh'] as Map<Object?, Object?>?;
    final bus = map['eventBus'] as Map<Object?, Object?>?;
    final feed = map['feed'] as Map<Object?, Object?>?;
    return RelayState(
      running: map['running'] as bool? ?? false,
      chatTick: (map['chatTick'] as num?)?.toInt() ?? 0,
      requestTick: (map['requestTick'] as num?)?.toInt() ?? 0,
      mesh: mesh == null
          ? MeshStatus.offline
          : MeshStatus(
              active: mesh['active'] as bool? ?? false,
              peerCount: (mesh['peerCount'] as num?)?.toInt() ?? 0,
            ),
      eventBus: bus == null ? EventBusStats.zero : EventBusStats.fromMap(bus),
      feed: (feed?['items'] as List?)
              ?.map((e) => FeedItem.fromMap(e as Map<Object?, Object?>))
              .toList(growable: false) ??
          const <FeedItem>[],
    );
  }
}

class RelayChannel {
  RelayChannel({MethodChannel? methods, EventChannel? state})
      : _methods = methods ?? const MethodChannel(Channels.relay),
        _state = state ?? const EventChannel(Channels.relayState);

  final MethodChannel _methods;
  final EventChannel _state;

  Stream<RelayState>? _stream;

  Stream<RelayState> get state => _stream ??= _state
      .receiveBroadcastStream()
      .map((e) => RelayState.fromMap(e as Map<Object?, Object?>))
      .asBroadcastStream();

  /// Start the background connection service once the vault is unlocked.
  ///
  /// Returns `false` — not an error — when the user has turned the feature off.
  /// The persistent low-priority notification and the battery cost are a real,
  /// visible tradeoff someone is entitled to decline.
  Future<bool> start() async =>
      await _methods.invokeMethod<bool>('start') ?? false;

  /// Stop it: vault lock, explicit disconnect, or logout.
  Future<void> stop() => _methods.invokeMethod<void>('stop');

  Future<bool> isEnabled() async =>
      await _methods.invokeMethod<bool>('isEnabled') ?? true;

  Future<void> setEnabled(bool enabled) =>
      _methods.invokeMethod<void>('setEnabled', {'enabled': enabled});

  /// Suppress DM notifications for the thread currently on screen.
  ///
  /// **Clear this (pass null) when the app is backgrounded**, from a
  /// `WidgetsBindingObserver`: a detached engine cannot mean "still reading
  /// this thread", and leaving it set would silence notifications for the last
  /// conversation the user happened to open.
  Future<void> setOpenConversation(String? peer) =>
      _methods.invokeMethod<void>('setOpenConversation', {'peer': peer});

  /// Force a chat-list reload when nothing native changed — e.g. a contact
  /// alias was edited locally, which changes chat titles with no relay event.
  Future<void> bumpChatTick() => _methods.invokeMethod<void>('bumpChatTick');

  /// Ask for peers' published @handles so chats are titled by name rather than
  /// key. Single-flight and rate-limited natively (and TTL-gated in Rust), and
  /// never awaited by the drain loop, so a slow relay cannot stall events.
  Future<void> refreshNames() => _methods.invokeMethod<void>('refreshNames');
}
