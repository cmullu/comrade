/// The call overlay's behaviour, driven through the real [CallController].
///
/// These tests exist because every one of the behaviours below is invisible in
/// a screenshot and easy to regress silently: controls that stop hiding, a
/// slash that stops animating, a camera that keeps capturing after the app is
/// backgrounded. The engine is a recording fake, so what reaches the media
/// layer is asserted rather than assumed.
library;

import 'package:comrade/src/data/comrade_repository.dart';
import 'package:comrade/src/data/models.dart';
import 'package:comrade/src/platform/pip_channel.dart';
import 'package:comrade/src/screens/call_screen.dart';
import 'package:comrade/src/state/call_providers.dart';
import 'package:comrade/src/theme/comrade_theme.dart';
import 'package:comrade/src/widgets/signal_bars.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'helpers.dart';

/// An engine that records what the UI asked of it and hands back a coloured
/// box for each track, so "is there a picture" is a real question in a test.
class RecordingEngine implements CallEngine {
  RecordingEngine({this.hasLocal = true, this.hasRemote = true});

  final bool hasLocal;
  final bool hasRemote;

  final List<bool> captureSuspensions = <bool>[];
  final List<bool> muteCalls = <bool>[];
  final List<bool> cameraCalls = <bool>[];
  int hangups = 0;

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
  Future<void> hangup() async => hangups++;

  @override
  Future<void> setMuted(bool muted) async => muteCalls.add(muted);

  @override
  Future<void> setCameraOn(bool on) async => cameraCalls.add(on);

  @override
  Future<void> switchCamera() async {}

  @override
  Future<void> setAudioRoute(AudioRoute route) async {}

  @override
  Future<void> setVideoCaptureSuspended(bool suspended) async =>
      captureSuspensions.add(suspended);

  /// Whether the platform's consent dialog is granted in this test. `false`
  /// stands in for the user dismissing it.
  bool screenShareAllowed = true;
  final List<bool> screenShareRequests = <bool>[];

  @override
  Future<bool> setScreenSharing(bool sharing) async {
    screenShareRequests.add(sharing);
    return sharing && screenShareAllowed;
  }

  @override
  Widget? videoView({required bool local, required bool mirror}) {
    if (local && !hasLocal) return null;
    if (!local && !hasRemote) return null;
    return Container(
      key: Key(local ? 'local-video' : 'remote-video'),
      color: local ? const Color(0xFF224466) : const Color(0xFF662244),
    );
  }
}

/// A PiP channel that answers the way a platform without picture-in-picture
/// does — unless [supported] says otherwise.
class FakePipChannel implements PipChannel {
  FakePipChannel({this.supported = false});

  bool supported;
  int enterCalls = 0;
  int closeCalls = 0;

  @override
  Future<bool> isSupported() async => supported;

  @override
  Future<bool> enter({double aspectWidth = 9, double aspectHeight = 16}) async {
    enterCalls++;
    return supported;
  }

  @override
  Future<void> close() async => closeCalls++;

  @override
  Stream<bool> get modeChanges => const Stream<bool>.empty();
}

const String _peer =
    'npub1alice7q0av9y8gm7vk2xspwjnvyxydr0hjfpnr4x9dvw2l3jd2qtqy3gq';

/// Pumps the overlay with an already-connected call of the requested kind.
Future<
    ({
      ProviderContainer container,
      RecordingEngine engine,
      FakePipChannel pip
    })> pumpConnectedCall(
  WidgetTester tester, {
  required bool video,
  bool pipSupported = false,
  void Function(String peer, String label)? onOpenChat,
}) async {
  final ComradeRepository repo = await unlockedFake();
  final RecordingEngine engine = RecordingEngine();
  final FakePipChannel pip = FakePipChannel(supported: pipSupported);
  late ProviderContainer container;

  await tester.pumpWidget(
    ProviderScope(
      overrides: <Override>[
        ...fakeOverrides(repo),
        callEngineProvider.overrideWithValue(engine),
        pipChannelProvider.overrideWithValue(pip),
      ],
      child: MaterialApp(
        theme: ComradeTheme.dark(),
        home: Scaffold(
          body: Consumer(
            builder: (BuildContext context, WidgetRef ref, _) {
              container = ProviderScope.containerOf(context);
              return Stack(
                children: <Widget>[
                  const SizedBox.expand(),
                  CallOverlay(onOpenChat: onOpenChat),
                ],
              );
            },
          ),
        ),
      ),
    ),
  );

  final CallController controller = container.read(callProvider.notifier);
  await controller.startOutgoing(peer: _peer, peerLabel: 'Amma', video: video);
  await tester.pump();
  await controller.accept();
  controller.onConnected(atMs: DateTime.now().millisecondsSinceEpoch);
  await tester.pump();
  return (container: container, engine: engine, pip: pip);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('signal strength', () {
    test('bars fill by quality, and nothing is invented for unknown', () {
      expect(signalBarsFor(CallQuality.good), 4);
      expect(signalBarsFor(CallQuality.medium), 2);
      expect(signalBarsFor(CallQuality.poor), 1);
      expect(signalBarsFor(CallQuality.unknown), 0);
    });

    test('only a degraded connection gets a word for it', () {
      expect(signalLabelFor(CallQuality.good), isNull);
      expect(signalLabelFor(CallQuality.unknown), isNull);
      expect(signalLabelFor(CallQuality.medium), 'Weak signal');
      expect(signalLabelFor(CallQuality.poor), 'Poor connection');
    });

    testWidgets('the in-call indicator replaces the verification emoji',
        (WidgetTester tester) async {
      final ({
        ProviderContainer container,
        RecordingEngine engine,
        FakePipChannel pip
      }) t = await pumpConnectedCall(tester, video: false);

      expect(find.byKey(const Key('call-signal')), findsOneWidget);
      // The 4-emoji SAS row is gone, label and all.
      expect(find.textContaining('Verify'), findsNothing);
      expect(find.byKey(const Key('call-sas')), findsNothing);
      expect(find.text('Weak signal'), findsNothing);

      t.container.read(callProvider.notifier).setQuality(CallQuality.medium);
      await tester.pump();
      expect(find.text('Weak signal'), findsOneWidget);

      t.container.read(callProvider.notifier).setQuality(CallQuality.poor);
      await tester.pump();
      expect(find.text('Poor connection'), findsOneWidget);
    });
  });

  group('FaceTime-style chrome', () {
    testWidgets('a video call hides its controls, and a tap brings them back',
        (WidgetTester tester) async {
      await pumpConnectedCall(tester, video: true);

      Finder chromeOpacity() => find.ancestor(
            of: find.byKey(const Key('call-hangup')),
            matching: find.byType(AnimatedOpacity),
          );
      double opacity() =>
          tester.widget<AnimatedOpacity>(chromeOpacity()).opacity;

      expect(opacity(), 1, reason: 'controls are up when the call connects');

      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();
      expect(opacity(), 0, reason: 'and fade away on their own');

      await tester.tap(find.byKey(const Key('call-stage')));
      await tester.pump();
      expect(opacity(), 1, reason: 'a tap anywhere brings them back');

      // A second tap dismisses them early rather than waiting out the timer.
      await tester.tap(find.byKey(const Key('call-stage')));
      await tester.pump();
      expect(opacity(), 0);
      await tester.pumpAndSettle();
    });

    testWidgets('an audio call keeps its controls up', (WidgetTester t) async {
      await pumpConnectedCall(t, video: false);
      await t.pump(const Duration(seconds: 10));
      await t.pumpAndSettle();
      final AnimatedOpacity chrome = t.widget<AnimatedOpacity>(
        find.ancestor(
          of: find.byKey(const Key('call-hangup')),
          matching: find.byType(AnimatedOpacity),
        ),
      );
      expect(chrome.opacity, 1);
    });
  });

  group('video paused', () {
    testWidgets('a paused peer gets a label, not a frozen frame',
        (WidgetTester tester) async {
      final ({
        ProviderContainer container,
        RecordingEngine engine,
        FakePipChannel pip
      }) t = await pumpConnectedCall(tester, video: true);

      expect(find.byKey(const Key('remote-video')), findsOneWidget);
      expect(find.byKey(const Key('video-paused')), findsNothing);

      t.container.read(callProvider.notifier).setRemoteVideoPaused(true);
      await tester.pump();

      expect(find.byKey(const Key('video-paused')), findsOneWidget);
      // The surface stays mounted under the cover. Unmounting it detaches the
      // renderer's sink from the track, so the frames that arrive when the peer
      // un-pauses would have nowhere to land until a new renderer was built —
      // which is how "they paused, then their video never came back" happened.
      expect(find.byKey(const Key('remote-video')), findsOneWidget,
          reason: 'covered, not torn down, so resuming is instant');

      t.container.read(callProvider.notifier).setRemoteVideoPaused(false);
      await tester.pump();
      expect(find.byKey(const Key('video-paused')), findsNothing);
      expect(find.byKey(const Key('remote-video')), findsOneWidget);
    });

    testWidgets('one side pausing leaves the other side alone',
        (WidgetTester tester) async {
      // The report this pins: "if one person pauses video the other should
      // continue". Each direction is independent — our camera choice must not
      // mark the peer paused, and the peer pausing must not stop our capture.
      final ({
        ProviderContainer container,
        RecordingEngine engine,
        FakePipChannel pip
      }) t = await pumpConnectedCall(tester, video: true);
      final CallController controller = t.container.read(callProvider.notifier);

      // They pause. We keep sending, and our self-view keeps its picture.
      controller.setRemoteVideoPaused(true);
      await tester.pump();
      expect(t.container.read(callProvider).localVideoPaused, isFalse);
      expect(t.engine.captureSuspensions, isEmpty,
          reason: 'their pause must not stop our camera');
      expect(find.byKey(const Key('local-video')), findsOneWidget);

      // We pause. Their state is untouched, and their picture stays up.
      controller.setRemoteVideoPaused(false);
      await controller.toggleCamera();
      await tester.pump();
      expect(t.container.read(callProvider).remoteVideoPaused, isFalse,
          reason: 'our camera being off says nothing about their video');
      expect(find.byKey(const Key('remote-video')), findsOneWidget);
    });

    testWidgets('turning the camera off pauses the self-view too',
        (WidgetTester tester) async {
      final ({
        ProviderContainer container,
        RecordingEngine engine,
        FakePipChannel pip
      }) t = await pumpConnectedCall(tester, video: true);

      expect(find.byKey(const Key('local-video')), findsOneWidget);

      await t.container.read(callProvider.notifier).toggleCamera();
      await tester.pump();

      expect(t.engine.cameraCalls, <bool>[false]);
      expect(find.byKey(const Key('video-paused')), findsOneWidget);
      expect(find.byKey(const Key('local-video')), findsOneWidget,
          reason: 'covered, not torn down — same reason as the remote surface');
    });
  });

  group('capture follows visibility', () {
    testWidgets(
        'backgrounding a video call stops the camera, resuming starts it',
        (WidgetTester tester) async {
      final ({
        ProviderContainer container,
        RecordingEngine engine,
        FakePipChannel pip
      }) t = await pumpConnectedCall(tester, video: true);
      final CallController controller = t.container.read(callProvider.notifier);

      await controller.onAppLifecycleChanged(AppLifecycleState.paused);
      await tester.pump();
      expect(t.engine.captureSuspensions, <bool>[true]);
      expect(t.container.read(callProvider).videoSuspended, isTrue);

      await controller.onAppLifecycleChanged(AppLifecycleState.resumed);
      expect(t.engine.captureSuspensions, <bool>[true, false]);
      expect(t.container.read(callProvider).videoSuspended, isFalse);
    });

    testWidgets('a glance at the notification shade does not pause the camera',
        (WidgetTester tester) async {
      // Android reports `inactive` for every transient loss of focus — the app
      // switcher, a system dialog, the shade, and the instant before a PiP
      // transition confirms. Suspending there told the peer "Video paused"
      // because someone glanced at their notifications.
      final ({
        ProviderContainer container,
        RecordingEngine engine,
        FakePipChannel pip
      }) t = await pumpConnectedCall(tester, video: true);

      await t.container
          .read(callProvider.notifier)
          .onAppLifecycleChanged(AppLifecycleState.inactive);

      expect(t.engine.captureSuspensions, isEmpty);
      expect(t.container.read(callProvider).videoSuspended, isFalse);
    });

    testWidgets('a hidden window does pause it', (WidgetTester tester) async {
      final ({
        ProviderContainer container,
        RecordingEngine engine,
        FakePipChannel pip
      }) t = await pumpConnectedCall(tester, video: true);

      await t.container
          .read(callProvider.notifier)
          .onAppLifecycleChanged(AppLifecycleState.hidden);

      expect(t.engine.captureSuspensions, <bool>[true]);
    });

    testWidgets('a picture-in-picture window counts as visible',
        (WidgetTester tester) async {
      final ({
        ProviderContainer container,
        RecordingEngine engine,
        FakePipChannel pip
      }) t = await pumpConnectedCall(tester, video: true, pipSupported: true);
      final CallController controller = t.container.read(callProvider.notifier);

      controller.onNativePipChanged(true);
      await controller.onAppLifecycleChanged(AppLifecycleState.inactive);

      expect(t.engine.captureSuspensions, isEmpty,
          reason: 'the PiP window is showing the call, so keep sending');
    });

    testWidgets('an audio call never suspends anything',
        (WidgetTester tester) async {
      final ({
        ProviderContainer container,
        RecordingEngine engine,
        FakePipChannel pip
      }) t = await pumpConnectedCall(tester, video: false);

      await t.container
          .read(callProvider.notifier)
          .onAppLifecycleChanged(AppLifecycleState.paused);

      expect(t.engine.captureSuspensions, isEmpty);
    });
  });

  group('the chat button', () {
    testWidgets('opens the conversation and shrinks the call into a tile',
        (WidgetTester tester) async {
      String? openedPeer;
      final ({
        ProviderContainer container,
        RecordingEngine engine,
        FakePipChannel pip
      }) t = await pumpConnectedCall(
        tester,
        video: true,
        onOpenChat: (String peer, String _) => openedPeer = peer,
      );

      await tester.tap(find.byKey(const Key('call-chat')));
      await tester.pumpAndSettle();

      expect(openedPeer, _peer);
      expect(t.container.read(callProvider).pip, CallPipMode.inApp);
      expect(find.byKey(const Key('call-floating-tile')), findsOneWidget);
      expect(find.byKey(const Key('call-hangup')), findsNothing,
          reason: 'the full-screen controls are gone with the full screen');

      // Tapping the tile restores the call.
      await tester.tap(find.byKey(const Key('call-floating-tile')));
      await tester.pumpAndSettle();
      expect(t.container.read(callProvider).pip, CallPipMode.none);
      expect(find.byKey(const Key('call-hangup')), findsOneWidget);
    });

    testWidgets('never hands the window to the OS — that would hide the chat',
        (WidgetTester tester) async {
      // The regression this pins: the button used to prefer native PiP, which
      // backgrounds the app. The conversation opened behind the launcher
      // instead of behind the call, so the button read as "minimise the video
      // and go nowhere". Even where native PiP is available, the chat button
      // must keep the call in *our* window.
      String? openedPeer;
      final ({
        ProviderContainer container,
        RecordingEngine engine,
        FakePipChannel pip
      }) t = await pumpConnectedCall(
        tester,
        video: true,
        pipSupported: true,
        onOpenChat: (String peer, String _) => openedPeer = peer,
      );

      await tester.tap(find.byKey(const Key('call-chat')));
      await tester.pumpAndSettle();

      expect(openedPeer, _peer, reason: 'the conversation still opens');
      expect(t.pip.enterCalls, 0,
          reason: 'no OS window: it would take the app off screen');
      expect(t.container.read(callProvider).pip, CallPipMode.inApp);
      expect(find.byKey(const Key('call-floating-tile')), findsOneWidget);
    });

    testWidgets('ending a call closes the picture-in-picture window',
        (WidgetTester tester) async {
      final ({
        ProviderContainer container,
        RecordingEngine engine,
        FakePipChannel pip
      }) t = await pumpConnectedCall(tester, video: true, pipSupported: true);
      final CallController controller = t.container.read(callProvider.notifier);

      controller.onNativePipChanged(true);
      await controller.hangup();
      await tester.pumpAndSettle();

      expect(t.pip.closeCalls, 1);
      expect(t.container.read(callProvider).pip, CallPipMode.none);
    });
  });

  group('screen sharing', () {
    testWidgets('is offered on a voice call, and grows a video stage',
        (WidgetTester tester) async {
      final ({
        ProviderContainer container,
        RecordingEngine engine,
        FakePipChannel pip
      }) t = await pumpConnectedCall(tester, video: false);

      // The button is there on a call that started without any video at all.
      expect(find.byKey(const Key('call-screen-share')), findsOneWidget);
      expect(t.container.read(callProvider).showsVideoStage, isFalse);

      await tester.tap(find.byKey(const Key('call-screen-share')));
      await tester.pumpAndSettle();

      expect(t.engine.screenShareRequests, <bool>[true]);
      expect(t.container.read(callProvider).screenSharing, isTrue);
      expect(t.container.read(callProvider).showsVideoStage, isTrue,
          reason: 'a shared screen is a picture, so the stage appears');
      expect(find.text('Stop sharing'), findsOneWidget);
    });

    testWidgets('follows the platform, not the tap, when consent is refused',
        (WidgetTester tester) async {
      final ({
        ProviderContainer container,
        RecordingEngine engine,
        FakePipChannel pip
      }) t = await pumpConnectedCall(tester, video: true);
      // The user dismisses the system dialog.
      t.engine.screenShareAllowed = false;

      await tester.tap(find.byKey(const Key('call-screen-share')));
      await tester.pumpAndSettle();

      expect(t.engine.screenShareRequests, <bool>[true]);
      expect(t.container.read(callProvider).screenSharing, isFalse,
          reason: 'a dismissed dialog must not leave the button lit');
      expect(find.text('Share screen'), findsOneWidget);
    });

    testWidgets('the platform can stop it without being asked',
        (WidgetTester tester) async {
      final ({
        ProviderContainer container,
        RecordingEngine engine,
        FakePipChannel pip
      }) t = await pumpConnectedCall(tester, video: true);
      final CallController controller = t.container.read(callProvider.notifier);

      await controller.toggleScreenShare();
      expect(t.container.read(callProvider).screenSharing, isTrue);

      // The system's own "Stop sharing", or the capture dying.
      controller.onScreenShareStopped();
      await tester.pump();
      expect(t.container.read(callProvider).screenSharing, isFalse);
    });

    testWidgets('ending the call clears it', (WidgetTester tester) async {
      final ({
        ProviderContainer container,
        RecordingEngine engine,
        FakePipChannel pip
      }) t = await pumpConnectedCall(tester, video: true);
      final CallController controller = t.container.read(callProvider.notifier);

      await controller.toggleScreenShare();
      await controller.hangup();
      await tester.pumpAndSettle();

      expect(t.container.read(callProvider).screenSharing, isFalse);
    });
  });

  group('the floating tile', () {
    testWidgets('can be dragged anywhere and snaps to the nearer edge',
        (WidgetTester tester) async {
      await pumpConnectedCall(tester, video: true);
      await tester.tap(find.byKey(const Key('call-chat')));
      await tester.pumpAndSettle();

      Offset tile() =>
          tester.getTopLeft(find.byKey(const Key('call-floating-tile')));
      final double screenWidth =
          tester.view.physicalSize.width / tester.view.devicePixelRatio;

      final Offset start = tile();
      expect(start.dx, greaterThan(screenWidth / 2),
          reason: 'defaults to the top right, clear of the send button');

      // Drag it left past the midpoint and down — it follows the finger, and
      // the snap then goes to whichever edge its *centre* ended up nearer.
      await tester.drag(
        find.byKey(const Key('call-floating-tile')),
        const Offset(-420, 220),
      );
      await tester.pumpAndSettle();

      final Offset moved = tile();
      expect(moved.dy, greaterThan(start.dy), reason: 'it moved down');
      expect(moved.dx, lessThan(screenWidth / 2),
          reason: 'and snapped to the left edge it was dragged towards');
    });

    testWidgets('never leaves the screen, however hard it is thrown',
        (WidgetTester tester) async {
      await pumpConnectedCall(tester, video: true);
      await tester.tap(find.byKey(const Key('call-chat')));
      await tester.pumpAndSettle();

      await tester.drag(
        find.byKey(const Key('call-floating-tile')),
        const Offset(-4000, 4000),
      );
      await tester.pumpAndSettle();

      final Rect tile =
          tester.getRect(find.byKey(const Key('call-floating-tile')));
      final Size screen =
          tester.view.physicalSize / tester.view.devicePixelRatio;
      expect(tile.left, greaterThanOrEqualTo(0));
      expect(tile.top, greaterThanOrEqualTo(0));
      expect(tile.right, lessThanOrEqualTo(screen.width));
      expect(tile.bottom, lessThanOrEqualTo(screen.height));
    });
  });

  group('the mute control', () {
    testWidgets('slashes the mic it is already showing',
        (WidgetTester tester) async {
      final ({
        ProviderContainer container,
        RecordingEngine engine,
        FakePipChannel pip
      }) t = await pumpConnectedCall(tester, video: false);

      Icon icon() => tester.widget<Icon>(
            find.descendant(
              of: find.byKey(const Key('call-mute')),
              matching: find.byType(Icon),
            ),
          );

      expect(icon().icon, Icons.mic);

      await tester.tap(find.byKey(const Key('call-mute')));
      await tester.pumpAndSettle();

      expect(t.engine.muteCalls, <bool>[true]);
      expect(icon().icon, Icons.mic,
          reason: 'the glyph does not swap — a slash is drawn over it');
      expect(find.text('Unmute'), findsOneWidget);
    });
  });
}
