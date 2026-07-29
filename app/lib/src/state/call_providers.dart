/// Call state for the UI, plus the seam where a real media engine plugs in.
///
/// **Scope, stated plainly.** `docs/FRONTEND_STRATEGY.md` D3 is the open
/// defect in this migration: `CallScreen` renders `org.webrtc.VideoTrack`
/// handles that cannot cross a platform channel, so a Flutter call UI needs
/// either a `flutter_webrtc` rewrite of `CallManager` (2,039 lines, the
/// most-repaired file in the repo) or a hand-written PlatformView. Neither is
/// in scope for a UI reconstruction, and neither can be faked convincingly.
///
/// What this file does instead: model every state the existing UI renders
/// (`call/CallUiState.kt`, plus the observable sub-states `CallManager`
/// exposes as `StateFlow`s), and put the media engine behind [CallEngine] so
/// the screen is complete, testable and driveable today, and needs no change
/// when the engine lands. [NullCallEngine] is the default — it does nothing and
/// says so.
library;

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/models.dart';
import '../platform/call_channel.dart' show AudioRoute, CallQuality;
import 'providers.dart';

// `AudioRoute` and `CallQuality` are declared ONCE, in the platform layer
// (`platform/call_channel.dart`), because that is the wire contract with
// `CallManager` — a second copy here would be a shape that can silently drift
// from what the channel actually decodes. They are re-exported so screens keep
// importing their call types from one place.
export '../platform/call_channel.dart' show AudioRoute, CallQuality;

/// Display text for an audio route.
///
/// Presentation-only, so it lives here rather than on the enum itself: the
/// channel layer's job is to agree with Kotlin about the wire values, not to
/// carry UI strings. Exhaustive `switch` — adding a route to the platform enum
/// is a compile error here until it gets a label.
extension AudioRouteLabel on AudioRoute {
  String get label => switch (this) {
        AudioRoute.earpiece => 'Earpiece',
        AudioRoute.speaker => 'Speaker',
        AudioRoute.bluetooth => 'Bluetooth',
        AudioRoute.wired => 'Wired headset',
      };
}

/// The four call phases the UI renders, plus [CallIdle].
/// Port of `call/CallUiState.kt`.
sealed class CallUiState {
  const CallUiState();

  String? get peer => null;
  String get peerLabel => '';
  bool get video => false;
  bool get incoming => false;
}

class CallIdle extends CallUiState {
  const CallIdle();
}

/// Incoming call ringing (callee), or outgoing call placed (caller).
class CallRinging extends CallUiState {
  const CallRinging({
    required this.peerNpub,
    required this.label,
    required this.isVideo,
    required this.isIncoming,
    this.remoteRinging = false,
  });

  final String peerNpub;
  final String label;
  final bool isVideo;
  final bool isIncoming;

  /// Caller side: the callee's device has acked the ring — "Ringing…" rather
  /// than "Calling…".
  final bool remoteRinging;

  @override
  String get peer => peerNpub;
  @override
  String get peerLabel => label;
  @override
  bool get video => isVideo;
  @override
  bool get incoming => isIncoming;
}

/// Negotiating: offer/answer exchanged, waiting for the media path.
class CallConnecting extends CallUiState {
  const CallConnecting({
    required this.peerNpub,
    required this.label,
    required this.isVideo,
    required this.isIncoming,
  });

  final String peerNpub;
  final String label;
  final bool isVideo;
  final bool isIncoming;

  @override
  String get peer => peerNpub;
  @override
  String get peerLabel => label;
  @override
  bool get video => isVideo;
  @override
  bool get incoming => isIncoming;
}

/// Connected — media flowing. [connectedAtMs] seeds the duration timer.
class CallActive extends CallUiState {
  const CallActive({
    required this.peerNpub,
    required this.label,
    required this.isVideo,
    required this.isIncoming,
    required this.connectedAtMs,
  });

  final String peerNpub;
  final String label;
  final bool isVideo;
  final bool isIncoming;
  final int connectedAtMs;

  @override
  String get peer => peerNpub;
  @override
  String get peerLabel => label;
  @override
  bool get video => isVideo;
  @override
  bool get incoming => isIncoming;
}

/// Terminal card shown briefly before returning to [CallIdle].
class CallEnded extends CallUiState {
  const CallEnded({
    required this.peerNpub,
    required this.label,
    required this.isVideo,
    required this.isIncoming,
    required this.outcome,
  });

  final String peerNpub;
  final String label;
  final bool isVideo;
  final bool isIncoming;

  /// `connected | missed | declined | busy | cancelled | failed`.
  final String outcome;

  @override
  String get peer => peerNpub;
  @override
  String get peerLabel => label;
  @override
  bool get video => isVideo;
  @override
  bool get incoming => isIncoming;
}

/// Everything the call overlay renders beyond the phase itself — the
/// `StateFlow`s `CallManager` exposes one by one.
class CallSession {
  const CallSession({
    this.state = const CallIdle(),
    this.muted = false,
    this.cameraOn = true,
    this.audioRoute = AudioRoute.earpiece,
    this.availableRoutes = const <AudioRoute>[
      AudioRoute.earpiece,
      AudioRoute.speaker
    ],
    this.quality = CallQuality.unknown,
    this.sasEmojis,
    this.hasLocalVideo = false,
    this.hasRemoteVideo = false,
  });

  final CallUiState state;
  final bool muted;
  final bool cameraOn;
  final AudioRoute audioRoute;

  /// Earpiece and speaker are always listed; Bluetooth/wired only while
  /// connected (and Bluetooth is dropped for the rest of the call if its
  /// permission is denied — AUDIT COMMS-06).
  final List<AudioRoute> availableRoutes;
  final CallQuality quality;

  /// The 4-emoji short authentication string, or `null` for the honest
  /// "can't verify" state. Never fabricated.
  final List<String>? sasEmojis;

  final bool hasLocalVideo;
  final bool hasRemoteVideo;

  CallSession copyWith({
    CallUiState? state,
    bool? muted,
    bool? cameraOn,
    AudioRoute? audioRoute,
    List<AudioRoute>? availableRoutes,
    CallQuality? quality,
    List<String>? sasEmojis,
    bool clearSas = false,
    bool? hasLocalVideo,
    bool? hasRemoteVideo,
  }) =>
      CallSession(
        state: state ?? this.state,
        muted: muted ?? this.muted,
        cameraOn: cameraOn ?? this.cameraOn,
        audioRoute: audioRoute ?? this.audioRoute,
        availableRoutes: availableRoutes ?? this.availableRoutes,
        quality: quality ?? this.quality,
        sasEmojis: clearSas ? null : (sasEmojis ?? this.sasEmojis),
        hasLocalVideo: hasLocalVideo ?? this.hasLocalVideo,
        hasRemoteVideo: hasRemoteVideo ?? this.hasRemoteVideo,
      );
}

/// The media half of a call: capture, peer connection, renderers.
///
/// Implemented by whichever engine wins D3. Everything above this line is
/// pure UI state and works without it.
abstract interface class CallEngine {
  Future<void> startOutgoing({
    required String peer,
    required String callId,
    required bool video,
    required List<IceServerInfo> iceServers,
  });

  Future<void> accept();
  Future<void> hangup();
  Future<void> setMuted(bool muted);
  Future<void> setCameraOn(bool on);
  Future<void> switchCamera();
  Future<void> setAudioRoute(AudioRoute route);

  /// A widget rendering the named track, or `null` when the engine has no
  /// frames for it yet. `local` mirrors (it is the front-camera preview);
  /// remote never does.
  Widget? videoView({required bool local, required bool mirror});
}

/// The default engine: does nothing, and is honest that it does nothing.
///
/// Every call control still updates the UI state (so the screen is fully
/// exercisable in a preview or a widget test), but no media is captured and no
/// signal is sent.
class NullCallEngine implements CallEngine {
  const NullCallEngine();

  @override
  Future<void> startOutgoing({
    required String peer,
    required String callId,
    required bool video,
    required List<IceServerInfo> iceServers,
  }) async {}

  @override
  Future<void> accept() async {}

  @override
  Future<void> hangup() async {}

  @override
  Future<void> setMuted(bool muted) async {}

  @override
  Future<void> setCameraOn(bool on) async {}

  @override
  Future<void> switchCamera() async {}

  @override
  Future<void> setAudioRoute(AudioRoute route) async {}

  @override
  Widget? videoView({required bool local, required bool mirror}) => null;
}

final Provider<CallEngine> callEngineProvider =
    Provider<CallEngine>((Ref ref) => const NullCallEngine());

/// Drives the call overlay.
///
/// Signalling (offer/answer/ICE, glare resolution, the STUN→TURN fallback, the
/// connect timeout) deliberately lives *below* this, in the engine and in the
/// shared Rust decision layer `docs/COMMS_ARCHITECTURE.md` ADR-2/WP15 plans.
/// This controller only owns what the screen shows.
class CallController extends Notifier<CallSession> {
  @override
  CallSession build() {
    listenToEvents(
      ref,
      matches: (BridgeEvent e) => e is IncomingCallSignal,
      onEvent: (BridgeEvent e) => _onSignal(e as IncomingCallSignal),
    );
    return const CallSession();
  }

  CallEngine get _engine => ref.read(callEngineProvider);

  void _onSignal(IncomingCallSignal signal) {
    switch (signal.kind) {
      case 'offer':
        if (state.state is! CallIdle) return; // busy; the engine answers busy
        state = state.copyWith(
          state: CallRinging(
            peerNpub: signal.peer,
            label: signal.peer,
            isVideo: signal.media == 'video',
            isIncoming: true,
          ),
          clearSas: true,
        );
      case 'ringing':
        final CallUiState s = state.state;
        if (s is CallRinging && !s.isIncoming) {
          state = state.copyWith(
            state: CallRinging(
              peerNpub: s.peerNpub,
              label: s.label,
              isVideo: s.isVideo,
              isIncoming: false,
              remoteRinging: true,
            ),
          );
        }
      case 'busy':
        _end('busy');
      case 'hangup':
        _end(state.state is CallActive ? 'connected' : 'missed');
      default:
        break;
    }
  }

  /// Place an outgoing call. [peerLabel] is the already-resolved display title
  /// (alias → @handle → key), because the call UI has no contact list to
  /// consult mid-ring.
  Future<void> startOutgoing({
    required String peer,
    required String peerLabel,
    required bool video,
  }) async {
    if (state.state is! CallIdle) return;
    state = CallSession(
      state: CallRinging(
        peerNpub: peer,
        label: peerLabel,
        isVideo: video,
        isIncoming: false,
      ),
      cameraOn: video,
    );
    final CallSessionInfo session = await ref
        .read(comradeRepositoryProvider)
        .placeCall(peer: peer, media: video ? 'video' : 'audio');
    await _engine.startOutgoing(
      peer: peer,
      callId: session.callId,
      video: video,
      iceServers: session.iceServers,
    );
  }

  Future<void> accept() async {
    final CallUiState s = state.state;
    if (s is! CallRinging || !s.isIncoming) return;
    state = state.copyWith(
      state: CallConnecting(
        peerNpub: s.peerNpub,
        label: s.label,
        isVideo: s.isVideo,
        isIncoming: true,
      ),
    );
    await _engine.accept();
  }

  Future<void> reject() async {
    await _engine.hangup();
    _end('declined');
  }

  Future<void> hangup() async {
    final bool wasConnected = state.state is CallActive;
    await _engine.hangup();
    _end(wasConnected
        ? 'connected'
        : (state.state.incoming ? 'declined' : 'cancelled'));
  }

  /// The engine reports the media path is up.
  void onConnected({int? atMs}) {
    final CallUiState s = state.state;
    if (s is CallActive) return;
    state = state.copyWith(
      state: CallActive(
        peerNpub: s.peer ?? '',
        label: s.peerLabel,
        isVideo: s.video,
        isIncoming: s.incoming,
        connectedAtMs: atMs ?? DateTime.now().millisecondsSinceEpoch,
      ),
    );
  }

  void _end(String outcome) {
    final CallUiState s = state.state;
    if (s is CallIdle) return;
    state = state.copyWith(
      state: CallEnded(
        peerNpub: s.peer ?? '',
        label: s.peerLabel,
        isVideo: s.video,
        isIncoming: s.incoming,
        outcome: outcome,
      ),
      clearSas: true,
    );
  }

  /// Dismiss the terminal card and return to [CallIdle].
  void dismissEnded() {
    if (state.state is CallEnded) state = const CallSession();
  }

  Future<void> toggleMute() async {
    final bool next = !state.muted;
    state = state.copyWith(muted: next);
    await _engine.setMuted(next);
  }

  Future<void> toggleCamera() async {
    final bool next = !state.cameraOn;
    state = state.copyWith(cameraOn: next);
    await _engine.setCameraOn(next);
  }

  Future<void> switchCamera() => _engine.switchCamera();

  Future<void> setAudioRoute(AudioRoute route) async {
    state = state.copyWith(audioRoute: route);
    await _engine.setAudioRoute(route);
  }

  /// AUDIT COMMS-06: a denied Bluetooth permission drops the route for the
  /// rest of the call rather than leaving a tap silently do nothing.
  void onBluetoothPermissionDenied() {
    state = state.copyWith(
      availableRoutes: <AudioRoute>[
        for (final AudioRoute r in state.availableRoutes)
          if (r != AudioRoute.bluetooth) r,
      ],
      audioRoute:
          state.audioRoute == AudioRoute.bluetooth ? AudioRoute.speaker : null,
    );
  }

  void setQuality(CallQuality quality) =>
      state = state.copyWith(quality: quality);

  /// Publish a derived SAS. `null` means "can't verify" and is rendered as
  /// nothing — never as a fabricated code.
  void setSas(List<String>? emojis) =>
      state = (emojis == null || emojis.isEmpty)
          ? state.copyWith(clearSas: true)
          : state.copyWith(sasEmojis: emojis);

  void setVideoAvailability({bool? local, bool? remote}) =>
      state = state.copyWith(hasLocalVideo: local, hasRemoteVideo: remote);
}

final NotifierProvider<CallController, CallSession> callProvider =
    NotifierProvider<CallController, CallSession>(CallController.new);
