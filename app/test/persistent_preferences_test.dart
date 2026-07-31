/// The settings seam, against a real `shared_preferences` store.
///
/// The property under test is the one a unit test normally cannot reach: a
/// value written now is still there **after the app is relaunched**. The
/// platform's in-memory backend stands in for the device's store, so "relaunch"
/// is modelled the way it actually behaves — a brand-new [PersistentPreferences]
/// opened over a store that outlives it, exactly as a second launch would find
/// the file the first one wrote. What is *not* mocked is anything in
/// `PersistentPreferences` itself, nor the cache-warming in `open()`, which is
/// the part that makes the synchronous getters legal.
///
/// This is also the only test that can catch the allowlist being out of step
/// with [PrefKeys]: a key missing from `PrefKeys.all` throws on read and write,
/// so the last test walks every declared key rather than trusting the set.
library;

import 'package:comrade/src/data/app_preferences.dart';
import 'package:comrade/src/data/persistent_preferences.dart';
import 'package:comrade/src/state/providers.dart';
import 'package:comrade/src/state/settings_providers.dart';
import 'package:flutter/material.dart' show ThemeMode;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences_platform_interface/in_memory_shared_preferences_async.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_async_platform_interface.dart';

void main() {
  /// The device's store: outlives the `PersistentPreferences` instances opened
  /// over it, which is what makes the relaunch test meaningful.
  late InMemorySharedPreferencesAsync device;

  setUp(() {
    device = InMemorySharedPreferencesAsync.empty();
    SharedPreferencesAsyncPlatform.instance = device;
  });

  test('an unset key reads as its default, not as an error', () async {
    final PersistentPreferences prefs = await PersistentPreferences.open();
    expect(prefs.getString(PrefKeys.themeMode), isNull);
    expect(prefs.getBool(PrefKeys.taraAccepted), isFalse);
    expect(
      prefs.getBool(PrefKeys.backgroundConnectivity, defaultValue: true),
      isTrue,
    );
  });

  test('a write is readable through the same instance, synchronously',
      () async {
    final PersistentPreferences prefs = await PersistentPreferences.open();
    await prefs.setString(PrefKeys.themeMode, 'dark');
    await prefs.setBool(PrefKeys.taraAccepted, true);
    // No await on the read: the cache is what makes this legal, and the
    // synchronous getters are why `AppPreferences` looks the way it does.
    expect(prefs.getString(PrefKeys.themeMode), 'dark');
    expect(prefs.getBool(PrefKeys.taraAccepted), isTrue);
  });

  test('a write survives a relaunch', () async {
    final PersistentPreferences first = await PersistentPreferences.open();
    await first.setString(PrefKeys.themeMode, 'dark');
    await first.setBool(PrefKeys.backgroundConnectivity, false);

    // A second launch: a new instance, warmed from the store the first wrote.
    final PersistentPreferences second = await PersistentPreferences.open();
    expect(second.getString(PrefKeys.themeMode), 'dark');
    expect(
      second.getBool(PrefKeys.backgroundConnectivity, defaultValue: true),
      isFalse,
    );
  });

  test('the theme survives a relaunch end to end', () async {
    // The whole path the user sees: pick Dark, kill the app, come back to dark.
    final PersistentPreferences before = await PersistentPreferences.open();
    final ProviderContainer first = ProviderContainer(
      overrides: <Override>[appPreferencesProvider.overrideWithValue(before)],
    );
    addTearDown(first.dispose);
    expect(first.read(themeModeProvider), ThemeMode.system);
    await first.read(themeModeProvider.notifier).set(ThemeMode.dark);

    final PersistentPreferences after = await PersistentPreferences.open();
    final ProviderContainer second = ProviderContainer(
      overrides: <Override>[appPreferencesProvider.overrideWithValue(after)],
    );
    addTearDown(second.dispose);
    expect(second.read(themeModeProvider), ThemeMode.dark);
  });

  test('every declared key is inside the store allowlist', () async {
    // `PrefKeys.all` is what the store is opened with, so a key declared in
    // `PrefKeys` but forgotten there throws an ArgumentError on first touch —
    // in release, at whatever moment the user first flips that setting.
    final PersistentPreferences prefs = await PersistentPreferences.open();
    for (final String key in <String>[
      PrefKeys.taraAccepted,
      PrefKeys.backgroundConnectivity,
      PrefKeys.themeMode,
    ]) {
      expect(PrefKeys.all, contains(key), reason: '$key is not allowlisted');
      expect(() => prefs.getString(key), returnsNormally, reason: key);
    }
  });

  test('a key outside the allowlist is refused, not silently dropped',
      () async {
    final PersistentPreferences prefs = await PersistentPreferences.open();
    expect(PrefKeys.all, isNot(contains('appearance.undeclared')));
    expect(
      () => prefs.getString('appearance.undeclared'),
      throwsArgumentError,
    );
  });
}
