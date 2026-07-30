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

import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/models.dart';
import '../platform/call_channel.dart' show AudioRoute, CallQuality;
import '../platform/pip_channel.dart';
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

/// Where the call is being drawn: full screen, an OS picture-in-picture
/// window, or a floating tile inside this app.
///
/// [inApp] is not a lesser fallback in one respect that matters: it is the only
/// mode in which the call and *this app's* chat are on screen together, which
/// is exactly what the in-call chat button is for on a desktop window or any
/// device the OS won't give a PiP window to.
enum CallPipMode {
  /// The normal in-call layout, covering the app.
  none,

  /// The OS is showing the call in its own floating window, over other apps.
  native,

  /// A floating tile inside the app, with the rest of the app usable behind it.
  inApp,
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
    this.hasLocalVideo = false,
    this.hasRemoteVideo = false,
    this.pip = CallPipMode.none,
    this.videoSuspended = false,
    this.remoteVideoPaused = false,
    this.screenSharing = false,
  });

  final CallUiState state;
  final bool muted;
  final bool cameraOn;
  final AudioRoute audioRoute;

  /// Earpiece and speaker are always listed; Bluetooth/wired only while
  /// connected (and Bluetooth is dropped for the rest of the call if its
  /// permission is denied — AUDIT COMMS-06).
  final List<AudioRoute> availableRoutes;

  /// The live connection-quality reading behind the signal-strength bars.
  /// [CallQuality.unknown] means "nothing measured yet" and draws no filled
  /// bars — see `widgets/signal_bars.dart`.
  final CallQuality quality;

  final bool hasLocalVideo;
  final bool hasRemoteVideo;

  final CallPipMode pip;

  /// Camera capture is stopped because no surface is showing it (backgrounded
  /// with no PiP window). Independent of [cameraOn], which is the user's own
  /// choice: capture resumes on return only if [cameraOn] was true.
  final bool videoSuspended;

  /// The peer's video has stopped arriving while their track is still live —
  /// they muted their camera or their app stopped capturing. Drawn as "Video
  /// paused", never as a frozen frame.
  final bool remoteVideoPaused;

  /// We are sending our screen instead of (or as well as) a camera.
  ///
  /// Available on **voice calls too**, which is the point of the feature: a
  /// voice call that starts sharing a screen grows a video stage it did not
  /// have. See [showsVideoStage].
  final bool screenSharing;

  /// Whether the UI should draw the video stage at all.
  ///
  /// Not simply `state.video`: a voice call sharing a screen has a picture to
  /// show, and a video call keeps its stage even when both cameras are off.
  bool get showsVideoStage => state.video || screenSharing;

  /// True while our own camera is not being sent, for whichever of the two
  /// reasons. The self-view shows "Video paused" for both, because from the
  /// peer's side they are the same thing.
  ///
  /// Screen sharing is not "paused": there is a picture, it just isn't a
  /// camera. That is why this deliberately does not consult [screenSharing].
  bool get localVideoPaused => !cameraOn || videoSuspended;

  CallSession copyWith({
    CallUiState? state,
    bool? muted,
    bool? cameraOn,
    AudioRoute? audioRoute,
    List<AudioRoute>? availableRoutes,
    CallQuality? quality,
    bool? hasLocalVideo,
    bool? hasRemoteVideo,
    CallPipMode? pip,
    bool? videoSuspended,
    bool? remoteVideoPaused,
    bool? screenSharing,
  }) =>
      CallSession(
        state: state ?? this.state,
        muted: muted ?? this.muted,
        cameraOn: cameraOn ?? this.cameraOn,
        audioRoute: audioRoute ?? this.audioRoute,
        availableRoutes: availableRoutes ?? this.availableRoutes,
        quality: quality ?? this.quality,
        hasLocalVideo: hasLocalVideo ?? this.hasLocalVideo,
        hasRemoteVideo: hasRemoteVideo ?? this.hasRemoteVideo,
        pip: pip ?? this.pip,
        videoSuspended: videoSuspended ?? this.videoSuspended,
        remoteVideoPaused: remoteVideoPaused ?? this.remoteVideoPaused,
        screenSharing: screenSharing ?? this.screenSharing,
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

  /// Stop or resume capture because nothing is *displaying* the local video.
  ///
  /// Not the same call as [setCameraOn], and an engine must not collapse the
  /// two: the user's camera choice has to survive backgrounding, so capture
  /// runs only while the camera is on **and** this is false. See
  /// `CallChannel.setVideoCaptureSuspended`.
  Future<void> setVideoCaptureSuspended(bool suspended);

  /// Start or stop sending the screen, returning whether we are sharing when
  /// it settles.
  ///
  /// Returning a value rather than `void` is load-bearing: starting a share
  /// needs the user's consent through a system dialog they can dismiss, so
  /// "asked to share" and "is sharing" are different facts and the UI must
  /// follow the second one. An engine that cannot share answers `false`.
  Future<bool> setScreenSharing(bool sharing);

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
  Future<void> setVideoCaptureSuspended(bool suspended) async {}

  /// No media, so nothing to share — and it says so rather than letting the UI
  /// light up a button that does nothing.
  @override
  Future<bool> setScreenSharing(bool sharing) async => false;

  @override
  Widget? videoView({required bool local, required bool mirror}) => null;
}

final Provider<CallEngine> callEngineProvider =
    Provider<CallEngine>((Ref ref) => const NullCallEngine());

/// The window-mode channel behind [CallController.openChat]. Overridden in
/// tests; on a platform with no native picture-in-picture every method answers
/// "no" and the call falls back to [CallPipMode.inApp].
final Provider<PipChannel> pipChannelProvider =
    Provider<PipChannel>((Ref ref) => PipChannel());

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
    // The OS is the authority on whether the window is in PiP: it also gets
    // there without us (the auto-enter on leaving the app), and the user can
    // leave it by dragging the window back.
    final StreamSubscription<bool> pip =
        ref.read(pipChannelProvider).modeChanges.listen(onNativePipChanged);
    ref.onDispose(pip.cancel);
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
    // A PiP window must not outlive its call: without this it would sit there
    // showing the app's ordinary UI in a thumbnail.
    if (state.pip == CallPipMode.native) {
      unawaited(ref.read(pipChannelProvider).close());
    }
    state = state.copyWith(
      state: CallEnded(
        peerNpub: s.peer ?? '',
        label: s.peerLabel,
        isVideo: s.video,
        isIncoming: s.incoming,
        outcome: outcome,
      ),
      pip: CallPipMode.none,
      videoSuspended: false,
      remoteVideoPaused: false,
      screenSharing: false,
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

  /// Start or stop sharing the screen. Available on voice calls as well as
  /// video ones — a voice call that starts sharing grows a video stage.
  ///
  /// The UI follows the engine's answer, not the request: the platform asks
  /// the user for consent through a system dialog, and dismissing that dialog
  /// must leave the button off rather than showing a share that isn't
  /// happening.
  Future<void> toggleScreenShare() async {
    final CallUiState s = state.state;
    if (s is! CallActive && s is! CallConnecting) return;
    final bool wanted = !state.screenSharing;
    final bool actual = await _engine.setScreenSharing(wanted);
    state = state.copyWith(screenSharing: actual);
  }

  /// The platform stopped the share without us asking — the user hit the
  /// system's own "Stop sharing", or the capture died.
  void onScreenShareStopped() {
    if (state.screenSharing) state = state.copyWith(screenSharing: false);
  }

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

  void setVideoAvailability({bool? local, bool? remote}) =>
      state = state.copyWith(hasLocalVideo: local, hasRemoteVideo: remote);

  /// The peer's frames stopped arriving (or started again) — drives the
  /// "Video paused" placeholder over their avatar.
  void setRemoteVideoPaused(bool paused) =>
      state = state.copyWith(remoteVideoPaused: paused);

  // ── Picture-in-picture ────────────────────────────────────────────────────

  /// The in-call chat button: shrink the call onto the conversation.
  ///
  /// **Deliberately the in-app tile, never native PiP.** This looked like the
  /// wrong call at first and shipped as "native PiP, falling back to the tile",
  /// which broke the feature outright: an OS picture-in-picture window *leaves
  /// the app*. The conversation the shell had just opened ended up behind the
  /// launcher, so the button read as "minimise the video and go nowhere". The
  /// whole point of this button is having the call and the thread on screen
  /// together, and only one window can do that — ours.
  ///
  /// Native PiP still exists, for the case it is actually right: leaving the app
  /// during a video call (`PipController`'s auto-enter). That is the OS's job
  /// and nothing here needs to ask for it.
  Future<void> openChat() async {
    if (state.state is CallIdle || state.state is CallEnded) return;
    state = state.copyWith(pip: CallPipMode.inApp);
  }

  /// The OS entered or left picture-in-picture.
  void onNativePipChanged(bool active) {
    if (active) {
      state = state.copyWith(pip: CallPipMode.native);
      return;
    }
    // Leaving native PiP restores the full-screen call, and — because the
    // window is visible again — un-suspends capture.
    if (state.pip == CallPipMode.native) {
      state = state.copyWith(pip: CallPipMode.none);
      unawaited(_applyVideoSuspended(false));
    }
  }

  /// Tapping the in-app floating tile puts the call back to full screen.
  void restoreFromPip() {
    if (state.pip == CallPipMode.inApp) {
      state = state.copyWith(pip: CallPipMode.none);
    }
  }

  // ── Capture follows visibility ────────────────────────────────────────────

  /// Stop capturing when nothing can show the picture.
  ///
  /// Called by the call overlay's lifecycle observer.
  ///
  /// **[AppLifecycleState.inactive] does not count as hidden**, and getting that
  /// wrong is a user-visible bug rather than a nicety. Android reports
  /// `inactive` for every transient loss of focus — the app switcher, a system
  /// dialog, the notification shade, *and the moment before a PiP transition is
  /// confirmed*. Suspending there made the peer see "Video paused" because
  /// someone glanced at their notifications, and it raced the PiP callback: tap
  /// the chat button, get `inactive`, stop the camera, then have PiP arrive and
  /// start it again. Only a genuinely backgrounded window ([AppLifecycleState
  /// .paused], [AppLifecycleState.hidden], [AppLifecycleState.detached]) means
  /// nothing is displaying the video.
  ///
  /// A native PiP window *is* something displaying the call, so it stays visible
  /// even while the Activity is no longer resumed — hence the [CallSession.pip]
  /// read rather than trusting the lifecycle value alone.
  Future<void> onAppLifecycleChanged(AppLifecycleState lifecycle) async {
    final bool backgrounded = switch (lifecycle) {
      AppLifecycleState.paused ||
      AppLifecycleState.hidden ||
      AppLifecycleState.detached =>
        true,
      AppLifecycleState.resumed || AppLifecycleState.inactive => false,
    };
    final bool visible = !backgrounded || state.pip == CallPipMode.native;
    await _applyVideoSuspended(!visible);
  }

  Future<void> _applyVideoSuspended(bool suspended) async {
    if (!state.state.video || state.videoSuspended == suspended) return;
    state = state.copyWith(videoSuspended: suspended);
    await _engine.setVideoCaptureSuspended(suspended);
  }
}

final NotifierProvider<CallController, CallSession> callProvider =
    NotifierProvider<CallController, CallSession>(CallController.new);
