/// Shared test scaffolding: a container/app wired to the in-memory fake.
library;

import 'package:comrade/src/data/app_preferences.dart';
import 'package:comrade/src/data/comrade_repository.dart';
import 'package:comrade/src/data/fake_comrade_repository.dart';
import 'package:comrade/src/state/providers.dart';
import 'package:comrade/src/theme/comrade_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

/// A repository with no simulated latency, already unlocked.
Future<FakeComradeRepository> unlockedFake({bool seed = true}) async {
  final FakeComradeRepository repo =
      FakeComradeRepository(latency: Duration.zero, seed: seed);
  await repo.unlockVault(path: 'test-vault', passphrase: 'passphrase');
  return repo;
}

List<Override> fakeOverrides(
  ComradeRepository repo, {
  Map<String, Object>? prefs,
  List<Override> extra = const <Override>[],
}) =>
    <Override>[
      comradeRepositoryProvider.overrideWithValue(repo),
      appPreferencesProvider.overrideWithValue(InMemoryPreferences(prefs)),
      // Platform seams (pickers, capture, playback, window security) come last
      // so a test can replace one the defaults above would otherwise pin.
      ...extra,
    ];

ProviderContainer testContainer(
  ComradeRepository repo, {
  Map<String, Object>? prefs,
  List<Override> extra = const <Override>[],
}) {
  final ProviderContainer container = ProviderContainer(
    overrides: fakeOverrides(repo, prefs: prefs, extra: extra),
  );
  addTearDown(container.dispose);
  return container;
}

/// Wrap [child] in the app's theme plus a `ProviderScope` bound to [repo].
Widget harness(
  Widget child, {
  required ComradeRepository repo,
  Map<String, Object>? prefs,
  List<Override> extra = const <Override>[],
  Brightness brightness = Brightness.dark,
}) =>
    ProviderScope(
      overrides: fakeOverrides(repo, prefs: prefs, extra: extra),
      child: MaterialApp(
        theme: brightness == Brightness.dark
            ? ComradeTheme.dark()
            : ComradeTheme.light(),
        home: Scaffold(body: child),
      ),
    );

/// Force a specific logical window size for a responsive test, and restore it
/// afterwards.
void setWindowSize(WidgetTester tester, Size logical) {
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = logical;
  addTearDown(tester.view.reset);
}

/// The seeded peer keys the fake uses, so tests don't re-declare them.
abstract final class FakePeers {
  /// Has an alias ("Amma") *and* a published handle ("alice").
  static const String alice =
      'npub1alice7q0av9y8gm7vk2xspwjnvyxydr0hjfpnr4x9dvw2l3jd2qtqy3gq';

  /// Has a published handle ("bhaskar") but no alias.
  static const String bhaskar =
      'npub1bob4laefqx0av9y8gm7vk2xspwjnvyxydr0hjfpnr4x9dvw2l3jd2qwx9m';

  /// A stranger in the requests bucket: no alias, no handle.
  static const String stranger =
      'npub1stranger0av9y8gm7vk2xspwjnvyxydr0hjfpnr4x9dvw2l3jd2qk4h7p';
}
