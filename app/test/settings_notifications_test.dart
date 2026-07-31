/// The two settings cards this feature adds: notifications (per-conversation
/// mute) and app updates.
///
/// Both are driven through fake channels, because both talk to Android services
/// a widget test has no engine for — which is also the state a Linux desktop
/// build is in, so these tests double as the proof that the cards render there
/// instead of erroring.
library;

import 'package:comrade/src/data/fake_comrade_repository.dart';
import 'package:comrade/src/platform/platform.dart'
    show
        SystemChannel,
        UpdateAvailable,
        UpdateChannel,
        UpdateSettings,
        UpdateStatus,
        UpdateUnknown;
import 'package:comrade/src/screens/settings_screen.dart';
import 'package:comrade/src/state/settings_providers.dart';
import 'package:comrade/src/theme/comrade_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'helpers.dart';

/// Records what the card asked for, and answers with whatever the test set.
class _FakeUpdateChannel extends UpdateChannel {
  _FakeUpdateChannel({
    UpdateStatus status = const UpdateUnknown(),
    this.settingsValue = const UpdateSettings.unknown(),
  }) : _status = status;

  final UpdateStatus _status;
  UpdateSettings settingsValue;

  final List<String> calls = <String>[];
  String? skipped;
  bool? autoCheckSetTo;

  @override
  Stream<UpdateStatus> get status => Stream<UpdateStatus>.value(_status);

  @override
  Future<UpdateSettings> settings() async => settingsValue;

  @override
  Future<void> check({bool force = false}) async => calls.add('check:$force');

  @override
  Future<void> setAutoCheck(bool enabled) async {
    autoCheckSetTo = enabled;
    calls.add('setAutoCheck:$enabled');
    settingsValue = UpdateSettings(
      currentVersion: settingsValue.currentVersion,
      autoCheck: enabled,
      lastCheckedAt: settingsValue.lastCheckedAt,
      skippedVersion: settingsValue.skippedVersion,
    );
  }

  @override
  Future<void> skip(String version) async {
    skipped = version;
    calls.add('skip:$version');
  }

  @override
  Future<void> unskip() async => calls.add('unskip');

  @override
  Future<void> openRelease() async => calls.add('openRelease');
}

class _FakeSystemChannel extends SystemChannel {
  _FakeSystemChannel({Set<String>? muted, this.notificationsEnabled = true})
      : muted = muted ?? <String>{};

  final Set<String> muted;
  final bool notificationsEnabled;
  final List<String> calls = <String>[];

  @override
  Future<Set<String>> mutedPeers() async => muted;

  @override
  Future<bool> isMuted(String peer) async => muted.contains(peer);

  @override
  Future<void> setMuted(String peer, {required bool muted}) async {
    calls.add('setMuted:$peer:$muted');
    if (muted) {
      this.muted.add(peer);
    } else {
      this.muted.remove(peer);
    }
  }

  @override
  Future<void> unmuteAll() async {
    calls.add('unmuteAll');
    muted.clear();
  }

  @override
  Future<bool> areNotificationsEnabled() async => notificationsEnabled;

  @override
  Future<void> openNotificationSettings() async => calls.add('openSettings');
}

Widget _settings(
  FakeComradeRepository repo, {
  UpdateChannel? updates,
  SystemChannel? system,
}) =>
    ProviderScope(
      overrides: <Override>[
        ...fakeOverrides(repo),
        if (updates != null) updateChannelProvider.overrideWithValue(updates),
        if (system != null) systemChannelProvider.overrideWithValue(system),
      ],
      child: MaterialApp(
        theme: ComradeTheme.dark(),
        home: const Scaffold(body: SettingsScreen()),
      ),
    );

/// Pump the settings screen with room for every card to build.
///
/// `ListView` builds lazily, so on a phone-sized surface the update card sits
/// below the fold and simply does not exist for a finder — a tall window is the
/// least fragile way to make the whole list real.
Future<void> pumpSettings(
  WidgetTester tester,
  FakeComradeRepository repo, {
  UpdateChannel? updates,
  SystemChannel? system,
}) async {
  setWindowSize(tester, const Size(1000, 4000));
  await tester.pumpWidget(_settings(repo, updates: updates, system: system));
  await tester.pumpAndSettle();
}

void main() {
  group('the updates card', () {
    testWidgets('names the running version and offers a manual check',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      final _FakeUpdateChannel updates = _FakeUpdateChannel(
        settingsValue: const UpdateSettings(
          currentVersion: '0.0.8',
          autoCheck: true,
          lastCheckedAt: 0,
        ),
      );
      await pumpSettings(tester, repo, updates: updates);

      expect(find.text("You're running 0.0.8"), findsOneWidget);
      expect(find.text('Not checked yet'), findsOneWidget);

      await tester.tap(find.text('Check now'));
      await tester.pumpAndSettle();
      // Forced: the user is looking at the answer, so the native side is told
      // not to also raise a notification about it.
      expect(updates.calls, contains('check:true'));
    });

    testWidgets('offers the release and the skip when one is available',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      final _FakeUpdateChannel updates = _FakeUpdateChannel(
        status: const UpdateAvailable(
          version: '0.0.9',
          tag: 'v0.0.9',
          notes: 'Fixed the thing',
          pageUrl: 'https://github.test/releases/tag/v0.0.9',
          apkBytes: 42,
          checkedAt: 1,
        ),
        settingsValue: const UpdateSettings(
          currentVersion: '0.0.8',
          autoCheck: true,
          lastCheckedAt: 1,
        ),
      );
      await pumpSettings(tester, repo, updates: updates);

      expect(find.text('Comrade 0.0.9 is available'), findsOneWidget);
      expect(find.text('Fixed the thing'), findsOneWidget);

      await tester.tap(find.text('Get the update'));
      await tester.pumpAndSettle();
      expect(updates.calls, contains('openRelease'));

      await tester.tap(find.text('Skip this version'));
      await tester.pumpAndSettle();
      expect(updates.skipped, '0.0.9');
    });

    testWidgets('the automatic-check switch reaches the native preference',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      final _FakeUpdateChannel updates = _FakeUpdateChannel(
        settingsValue: const UpdateSettings(
          currentVersion: '0.0.8',
          autoCheck: true,
          lastCheckedAt: 0,
        ),
      );
      await pumpSettings(tester, repo, updates: updates);

      await tester.tap(find.text('Check for updates automatically'));
      // The label is not the switch; drive the switch itself.
      final Finder autoSwitch = find.descendant(
        of: find.ancestor(
          of: find.text('Check for updates automatically'),
          matching: find.byType(Row),
        ),
        matching: find.byType(Switch),
      );
      await tester.tap(autoSwitch.first);
      await tester.pumpAndSettle();
      expect(updates.autoCheckSetTo, isFalse);
    });

    testWidgets('a skipped version is stated, with a way back',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      final _FakeUpdateChannel updates = _FakeUpdateChannel(
        settingsValue: const UpdateSettings(
          currentVersion: '0.0.8',
          autoCheck: true,
          lastCheckedAt: 1,
          skippedVersion: '0.0.9',
        ),
      );
      await pumpSettings(tester, repo, updates: updates);

      expect(
        find.textContaining(
            "Skipped 0.0.9 — you'll be told about anything newer"),
        findsOneWidget,
      );
      await tester.tap(find.text('Stop skipping'));
      await tester.pumpAndSettle();
      expect(updates.calls, contains('unskip'));
    });

    testWidgets('renders with no native side at all (desktop, tests)',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      // No channel overrides: the real ones throw MissingPluginException here,
      // which is exactly the Linux-desktop case.
      await pumpSettings(tester, repo);
      expect(find.text('App updates'), findsOneWidget);
      expect(find.text('Notifications'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  });

  group('the notifications card', () {
    testWidgets('says nothing is muted, and offers the system screen',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      final _FakeSystemChannel system = _FakeSystemChannel();
      await pumpSettings(tester, repo, system: system);

      expect(find.text('No conversations are muted.'), findsOneWidget);
      await tester.tap(find.text('Notification settings'));
      await tester.pumpAndSettle();
      expect(system.calls, contains('openSettings'));
    });

    testWidgets('counts muted conversations and can unmute them all',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      final _FakeSystemChannel system =
          _FakeSystemChannel(muted: <String>{'npub1ana', 'npub1bo'});
      await pumpSettings(tester, repo, system: system);

      expect(find.text('2 conversations are muted (on this device).'),
          findsOneWidget);
      await tester.tap(find.text('Unmute all'));
      await tester.pumpAndSettle();
      expect(system.calls, contains('unmuteAll'));
      expect(find.text('No conversations are muted.'), findsOneWidget);
    });

    testWidgets('one muted conversation reads in the singular',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      await pumpSettings(tester, repo,
          system: _FakeSystemChannel(muted: <String>{'npub1ana'}));
      expect(find.text('1 conversation is muted (on this device).'),
          findsOneWidget);
    });

    testWidgets('notifications switched off is said out loud, calls included',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      await pumpSettings(tester, repo,
          system: _FakeSystemChannel(notificationsEnabled: false));
      expect(
        find.textContaining('Notifications are turned off for Comrade'),
        findsOneWidget,
      );
      expect(find.textContaining('including calls'), findsOneWidget);
    });
  });
}
