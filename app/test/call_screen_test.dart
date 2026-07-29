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

      expect(find.byKey(const Key('remote-video')), findsNothing,
          reason: 'the stale surface is dropped, not left frozen');
      expect(find.byKey(const Key('video-paused')), findsOneWidget);
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
      expect(find.byKey(const Key('local-video')), findsNothing);
      expect(find.byKey(const Key('video-paused')), findsOneWidget);
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
      expect(t.pip.enterCalls, 1, reason: 'native PiP is asked for first');
      expect(t.container.read(callProvider).pip, CallPipMode.inApp,
          reason: 'and this platform has none, so the in-app tile takes over');
      expect(find.byKey(const Key('call-floating-tile')), findsOneWidget);
      expect(find.byKey(const Key('call-hangup')), findsNothing,
          reason: 'the full-screen controls are gone with the full screen');

      // Tapping the tile restores the call.
      await tester.tap(find.byKey(const Key('call-floating-tile')));
      await tester.pumpAndSettle();
      expect(t.container.read(callProvider).pip, CallPipMode.none);
      expect(find.byKey(const Key('call-hangup')), findsOneWidget);
    });

    testWidgets('a platform with native PiP waits for the OS to confirm',
        (WidgetTester tester) async {
      final ({
        ProviderContainer container,
        RecordingEngine engine,
        FakePipChannel pip
      }) t = await pumpConnectedCall(tester, video: true, pipSupported: true);

      await tester.tap(find.byKey(const Key('call-chat')));
      await tester.pumpAndSettle();

      expect(t.pip.enterCalls, 1);
      expect(t.container.read(callProvider).pip, CallPipMode.none,
          reason: 'the mode changes when the OS says so, not when we ask');

      t.container.read(callProvider.notifier).onNativePipChanged(true);
      await tester.pumpAndSettle();
      expect(t.container.read(callProvider).pip, CallPipMode.native);
      // In the OS thumbnail there is room for the picture and nothing else.
      expect(find.byKey(const Key('call-hangup')), findsNothing);
      expect(find.byKey(const Key('remote-video')), findsOneWidget);
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
