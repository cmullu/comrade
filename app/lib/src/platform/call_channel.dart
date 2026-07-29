/// Typed wrapper over the call service's channels.
///
/// The native side is `CallManager` — 2,039 lines of `org.webrtc` state machine
/// that Phase 2 deliberately does not touch — plus `CallService` (the
/// foreground service that keeps a call's process alive) and `Ringer`. This
/// file is a facade; it holds no call state of its own beyond the last snapshot
/// the platform sent.
///
/// See `../../../android/app/src/main/kotlin/mullu/comrade/PLATFORM_CHANNELS.md`
/// §5.
library;

import 'dart:async';

import 'package:flutter/services.dart';

import 'channels.dart';

/// Where a call is in its lifecycle.
///
/// Mirrors `CallUiState` (`android/…/call/CallUiState.kt`) as a Dart 3 sealed
/// class, so a `switch` over it is exhaustively checked the same way the Kotlin
/// `when` is. `incoming` distinguishes the callee (Accept/Decline) from the
/// caller (Cancel); `peerLabel` is the *already-resolved* display title —
/// resolved natively so the ringing screen and the notification cannot
/// disagree.
sealed class CallPhase {
  const CallPhase();
}

/// No call in flight.
final class CallIdle extends CallPhase {
  const CallIdle();
}

/// Incoming call ringing (callee), or outgoing call placed (caller).
final class CallRinging extends CallPhase {
  const CallRinging({
    required this.peer,
    required this.peerLabel,
    required this.video,
    required this.incoming,
    required this.remoteRinging,
  });

  final String peer;
  final String peerLabel;
  final bool video;
  final bool incoming;

  /// Caller side: the callee's device acked the ring — "Ringing…" vs "Calling…".
  final bool remoteRinging;
}

/// Offer/answer exchanged, waiting for the media path.
final class CallConnecting extends CallPhase {
  const CallConnecting({
    required this.peer,
    required this.peerLabel,
    required this.video,
    required this.incoming,
  });

  final String peer;
  final String peerLabel;
  final bool video;
  final bool incoming;
}

/// Media flowing. [connectedAtMs] seeds the duration timer.
final class CallActive extends CallPhase {
  const CallActive({
    required this.peer,
    required this.peerLabel,
    required this.video,
    required this.incoming,
    required this.connectedAtMs,
  });

  final String peer;
  final String peerLabel;
  final bool video;
  final bool incoming;
  final int connectedAtMs;
}

/// Terminal card shown briefly before returning to [CallIdle].
final class CallEnded extends CallPhase {
  const CallEnded({
    required this.peer,
    required this.peerLabel,
    required this.video,
    required this.incoming,
    required this.outcome,
  });

  final String peer;
  final String peerLabel;
  final bool video;
  final bool incoming;
  final String outcome;
}

/// Where in-call audio is playing. `bluetooth`/`wired` only appear in
/// [CallSnapshot.availableRoutes] while a matching device is connected — and
/// `bluetooth` disappears for the rest of the call if the user refuses
/// `BLUETOOTH_CONNECT` (AUDIT COMMS-06).
enum AudioRoute { earpiece, speaker, bluetooth, wired }

/// A coarse heuristic read on media quality, sampled every 2 s from
/// `PeerConnection.getStats`. `unknown` covers both "no reading yet" and "stats
/// present but unparseable" — in both cases the only correct UI is to show
/// nothing.
enum CallQuality { good, medium, poor, unknown }

/// One coherent snapshot of everything `CallManager` publishes.
///
/// Delivered as a unit — the native `StateFlow`s are `combine`d before
/// crossing — so the UI can never render a muted `idle` call from two
/// half-applied updates.
final class CallSnapshot {
  const CallSnapshot({
    required this.phase,
    required this.muted,
    required this.cameraOn,
    required this.quality,
    required this.audioRoute,
    required this.availableRoutes,
    this.videoSuspended = false,
    this.remoteVideoPaused = false,
  });

  static const idle = CallSnapshot(
    phase: CallIdle(),
    muted: false,
    cameraOn: true,
    quality: CallQuality.unknown,
    audioRoute: AudioRoute.earpiece,
    availableRoutes: [AudioRoute.earpiece, AudioRoute.speaker],
  );

  final CallPhase phase;
  final bool muted;
  final bool cameraOn;
  final CallQuality quality;
  final AudioRoute audioRoute;
  final List<AudioRoute> availableRoutes;

  /// Capture is stopped because nothing is displaying it — the app is
  /// backgrounded without a picture-in-picture window. Distinct from
  /// `!cameraOn`, which is the user's own decision and survives it: coming back
  /// to the foreground resumes capture only if [cameraOn] was true all along.
  final bool videoSuspended;

  /// No decoded frames are arriving from the peer although their video track is
  /// live — they turned their camera off, or their app stopped capturing
  /// because it went to the background. Rendered as "Video paused" over their
  /// avatar rather than as a frozen last frame.
  final bool remoteVideoPaused;

  factory CallSnapshot.fromMap(Map<Object?, Object?> map) {
    String str(String k) => map[k] as String? ?? '';
    bool flag(String k, {bool or = false}) => map[k] as bool? ?? or;

    final phase = switch (map['phase'] as String?) {
      'ringing' => CallRinging(
          peer: str('peer'),
          peerLabel: str('peerLabel'),
          video: flag('video'),
          incoming: flag('incoming'),
          remoteRinging: flag('remoteRinging'),
        ),
      'connecting' => CallConnecting(
          peer: str('peer'),
          peerLabel: str('peerLabel'),
          video: flag('video'),
          incoming: flag('incoming'),
        ),
      'active' => CallActive(
          peer: str('peer'),
          peerLabel: str('peerLabel'),
          video: flag('video'),
          incoming: flag('incoming'),
          connectedAtMs: (map['connectedAtMs'] as num?)?.toInt() ?? 0,
        ),
      'ended' => CallEnded(
          peer: str('peer'),
          peerLabel: str('peerLabel'),
          video: flag('video'),
          incoming: flag('incoming'),
          outcome: str('outcome'),
        ),
      _ => const CallIdle(),
    };

    return CallSnapshot(
      phase: phase,
      muted: flag('muted'),
      cameraOn: flag('cameraOn', or: true),
      quality: _quality(map['quality'] as String?),
      audioRoute: _route(map['audioRoute'] as String?) ?? AudioRoute.earpiece,
      availableRoutes: (map['availableRoutes'] as List?)
              ?.map((e) => _route(e as String?))
              .whereType<AudioRoute>()
              .toList(growable: false) ??
          const [AudioRoute.earpiece, AudioRoute.speaker],
      videoSuspended: flag('videoSuspended'),
      remoteVideoPaused: flag('remoteVideoPaused'),
    );
  }

  static CallQuality _quality(String? name) => switch (name) {
        'good' => CallQuality.good,
        'medium' => CallQuality.medium,
        'poor' => CallQuality.poor,
        _ => CallQuality.unknown,
      };

  static AudioRoute? _route(String? name) => switch (name) {
        'earpiece' => AudioRoute.earpiece,
        'speaker' => AudioRoute.speaker,
        'bluetooth' => AudioRoute.bluetooth,
        'wired' => AudioRoute.wired,
        _ => null,
      };
}

/// Result of the TURN relay reachability diagnostic.
enum TurnDiagnostic {
  /// No TURN server configured — nothing to test.
  noServer,

  /// A `relay`-typed local candidate came back: the relay is reachable.
  relayAvailable,

  /// A server is configured but no relay candidate arrived in time.
  relayUnavailable,
}

/// Whether a TURN relay is configured, and its URL. **Never the credential** —
/// credentials are write-only through [CallChannel.setTurnServer], matching the
/// app's existing nsec-masking discipline.
final class TurnServerStatus {
  const TurnServerStatus({required this.configured, required this.url});

  final bool configured;
  final String? url;
}

/// Control + state for calls.
class CallChannel {
  CallChannel({
    MethodChannel? methods,
    EventChannel? state,
  })  : _methods = methods ?? const MethodChannel(Channels.call),
        _state = state ?? const EventChannel(Channels.callState);

  final MethodChannel _methods;
  final EventChannel _state;

  Stream<CallSnapshot>? _stream;

  /// Snapshot-then-delta stream of the whole call state.
  ///
  /// The platform emits the current value immediately on subscribe, so a widget
  /// that attaches mid-call is correct on its first frame. `broadcast` because
  /// several widgets legitimately watch it; the underlying `EventChannel`
  /// subscription is created once and shared.
  ///
  /// **Detach is lossless and not buffered.** If the engine is detached while a
  /// call rings, connects and ends, the reattached UI sees `idle` — which is
  /// correct, because the ringtone, the notification, the call-history row and
  /// the foreground service all happened natively in the meantime
  /// (`CallStateReactor`). There is deliberately no replay queue: one would let
  /// a stale "Ringing" arrive after the call was already over.
  Stream<CallSnapshot> get state => _stream ??= _state
      .receiveBroadcastStream()
      .map((e) => CallSnapshot.fromMap(e as Map<Object?, Object?>))
      .asBroadcastStream();

  /// Place an outgoing call. Gates mic (and camera, for video) natively, in the
  /// same gesture — see `PLATFORM_CHANNELS.md` §5.3.
  ///
  /// Throws [PlatformException] with [PlatformErrorCodes.permissionDenied] if
  /// the user refuses.
  Future<void> placeCall({
    required String peer,
    required String peerLabel,
    required bool video,
  }) =>
      _methods.invokeMethod<void>('placeCall', {
        'peer': peer,
        'peerLabel': peerLabel,
        'video': video,
      });

  /// Accept the ringing call. There is no explicit accept signal on the wire —
  /// the callee's Answer *is* the accept.
  ///
  /// A no-op (not an error) if the call ended between the tap and the handler.
  Future<void> accept() => _methods.invokeMethod<void>('accept');

  Future<void> reject() => _methods.invokeMethod<void>('reject');

  Future<void> hangup() => _methods.invokeMethod<void>('hangup');

  // These return as soon as the request is handed over; the resulting state
  // arrives on [state]. A method that returned the new value would give the UI
  // two clocks to disagree about.
  Future<void> toggleMute() => _methods.invokeMethod<void>('toggleMute');

  Future<void> toggleCamera() => _methods.invokeMethod<void>('toggleCamera');

  Future<void> switchCamera() => _methods.invokeMethod<void>('switchCamera');

  /// Stop (or resume) camera capture because nothing is displaying it.
  ///
  /// Separate from [toggleCamera] on purpose: this is the app saying "no
  /// surface is showing this video", not the user saying "don't send my
  /// camera". The native side keeps both facts and captures only when *both*
  /// allow it, so returning to the foreground with the camera deliberately off
  /// does not switch it back on.
  ///
  /// The privacy half of this is the point: with the call backgrounded and no
  /// picture-in-picture window, the camera hardware is released rather than
  /// left running against a surface nobody can see.
  Future<void> setVideoCaptureSuspended(bool suspended) => _methods
      .invokeMethod<void>('setVideoCaptureSuspended', {'suspended': suspended});

  Future<void> cycleAudioRoute() =>
      _methods.invokeMethod<void>('cycleAudioRoute');

  /// Select an audio route.
  ///
  /// Picking [AudioRoute.bluetooth] on API 31+ requests `BLUETOOTH_CONNECT` at
  /// that moment. On refusal this throws
  /// [PlatformErrorCodes.permissionDenied] **and** the native side has already
  /// dropped Bluetooth from `availableRoutes` for the rest of the call
  /// (AUDIT COMMS-06) — so the correct UI response is to explain the speaker
  /// fallback, not to retry. The next [state] event will show the shortened
  /// list.
  Future<void> setAudioRoute(AudioRoute route) =>
      _methods.invokeMethod<void>('setAudioRoute', {'route': route.name});

  /// Best-effort "is the configured TURN relay reachable" check.
  ///
  /// Safe at any time including mid-call — it allocates a throwaway RELAY-only
  /// `PeerConnection` and never touches the live session. Can legitimately take
  /// the full [timeout].
  Future<TurnDiagnostic> testTurnConnectivity({
    Duration timeout = const Duration(seconds: 8),
  }) async {
    final raw = await _methods.invokeMethod<String>(
      'testTurnConnectivity',
      {'timeoutMs': timeout.inMilliseconds},
    );
    return switch (raw) {
      'relayAvailable' => TurnDiagnostic.relayAvailable,
      'relayUnavailable' => TurnDiagnostic.relayUnavailable,
      _ => TurnDiagnostic.noServer,
    };
  }

  Future<TurnServerStatus> turnServerStatus() async {
    final map =
        await _methods.invokeMapMethod<String, Object?>('turnServerStatus');
    return TurnServerStatus(
      configured: map?['configured'] as bool? ?? false,
      url: map?['url'] as String?,
    );
  }

  /// Configure (or, with a blank [url], clear) the TURN relay.
  ///
  /// [username]/[credential] are write-only from here on: nothing reads them
  /// back and nothing logs them. Throws [PlatformErrorCodes.illegalTurnUrl]
  /// with a user-safe message — never the credential — if the URI is malformed.
  Future<void> setTurnServer({
    required String url,
    String username = '',
    String credential = '',
  }) =>
      _methods.invokeMethod<void>('setTurnServer', {
        'url': url,
        'username': username,
        'credential': credential,
      });
}
