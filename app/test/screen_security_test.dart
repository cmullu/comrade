/// Screenshots: allowed by default, blockable by the user, and holdable by a
/// screen that shows something secret.
///
/// These drive the real [MethodChannel] through a mock handler rather than a
/// hand-written fake, because the thing most likely to break is the *contract* —
/// a renamed method or a changed argument key is a silent no-op at runtime, and
/// a fake would happily agree with the wrong one.
library;

import 'package:comrade/src/platform/channels.dart';
import 'package:comrade/src/platform/screen_channel.dart';
import 'package:comrade/src/state/settings_providers.dart';
import 'package:comrade/src/widgets/secure_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'helpers.dart';

void main() {
  // The non-widget cases here reach the test binary messenger too, and it only
  // exists once the binding is up.
  TestWidgetsFlutterBinding.ensureInitialized();

  const MethodChannel channel = MethodChannel(Channels.screen);
  final List<MethodCall> calls = <MethodCall>[];
  bool blocked = false;
  int holds = 0;

  /// Install a stand-in for the native side. [present] false models a platform
  /// with no implementation at all (desktop today), where every call raises
  /// `MissingPluginException`.
  void mockNative({bool present = true}) {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
            channel,
            present
                ? (MethodCall call) async {
                    calls.add(call);
                    switch (call.method) {
                      case 'isBlocked':
                        return blocked;
                      case 'setBlocked':
                        blocked = call.arguments['blocked'] as bool;
                        return blocked;
                      case 'setSecureWhileVisible':
                        holds += (call.arguments['secure'] as bool) ? 1 : -1;
                        return holds > 0 || blocked;
                      default:
                        return null;
                    }
                  }
                : null);
  }

  setUp(() {
    calls.clear();
    blocked = false;
    holds = 0;
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  group('the channel', () {
    test('reports not-blocked by default, and stores a change', () async {
      mockNative();
      final ScreenSecurityChannel screen = ScreenSecurityChannel();

      expect(await screen.isBlocked(), isFalse,
          reason: 'screenshots work unless the user says otherwise');
      expect(await screen.setBlocked(blocked: true), isTrue);
      expect(await screen.isBlocked(), isTrue);
      expect(screen.supported, isTrue);
      expect(
        calls.map((MethodCall c) => c.method).toList(),
        <String>['isBlocked', 'setBlocked', 'isBlocked'],
      );
    });

    test('a platform with no implementation reports unsupported, not an error',
        () async {
      mockNative(present: false);
      final ScreenSecurityChannel screen = ScreenSecurityChannel();

      expect(await screen.isBlocked(), isFalse);
      expect(screen.supported, isFalse);
      // And asking to block does not claim to have done it.
      expect(await screen.setBlocked(blocked: true), isFalse);
    });
  });

  group('the settings policy', () {
    test('reads as supported-and-allowed, and follows a toggle', () async {
      mockNative();
      final ProviderContainer container = testContainer(await unlockedFake());

      final ScreenshotPolicy initial =
          await container.read(screenshotPolicyProvider.future);
      expect(initial.supported, isTrue);
      expect(initial.blocked, isFalse, reason: 'screenshots work by default');

      await container.read(screenshotPolicyProvider.notifier).setBlocked(true);

      expect(blocked, isTrue, reason: 'the platform stored it');
      expect(container.read(screenshotPolicyProvider).value?.blocked, isTrue);
    });

    test('reports unsupported where there is no native half, so the card hides',
        () async {
      mockNative(present: false);
      final ProviderContainer container = testContainer(await unlockedFake());

      final ScreenshotPolicy policy =
          await container.read(screenshotPolicyProvider.future);
      expect(policy.supported, isFalse);
      expect(policy.blocked, isFalse);
    });
  });

  group('SecureScreen', () {
    testWidgets('holds only while mounted', (WidgetTester tester) async {
      mockNative();
      await tester.pumpWidget(harness(
        const SecureScreen(child: Text('nsec goes here')),
        repo: await unlockedFake(),
      ));
      await tester.pumpAndSettle();
      expect(holds, 1, reason: 'acquired on mount');

      // Replace it with something that is not secret.
      await tester.pumpWidget(harness(
        const Text('ordinary screen'),
        repo: await unlockedFake(),
      ));
      await tester.pumpAndSettle();
      expect(holds, 0, reason: 'released on dispose — never left set');
    });
  });
}
