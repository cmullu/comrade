/// The [AppPreferences] implementation that actually remembers.
///
/// Kept in its own file so `app_preferences.dart` — which every test imports —
/// stays free of the plugin, and so the composition root remains the only place
/// that knows a plugin is involved at all. Same rule `main.dart` already follows
/// for the Rust bridge.
///
/// ## Why `SharedPreferencesWithCache`
///
/// [AppPreferences]' getters are synchronous, because a Riverpod `build()` reads
/// one and turning those into `FutureProvider`s would make the theme and the
/// Tara gate flicker through a loading state on every launch. `SharedPreferences
/// WithCache` is the variant built for exactly that shape: one async open that
/// warms an in-memory cache, then synchronous reads. The newer
/// `SharedPreferencesAsync` is async on every get, and the legacy
/// `SharedPreferences.getInstance()` is the API this one replaces.
///
/// ## Why it may throw, and why that is not caught
///
/// [open] propagates a platform failure rather than falling back to
/// [InMemoryPreferences]. A store that cannot open means the plugin is not
/// registered — a build problem, on a platform where it cannot happen once the
/// build is right. Falling back would leave the app running with settings that
/// silently do not stick while the Appearance card claims they do, which is the
/// one thing this seam's history says not to do. `main.dart` treats a missing
/// Rust bridge the same way.
library;

import 'package:shared_preferences/shared_preferences.dart';

import 'app_preferences.dart';

class PersistentPreferences implements AppPreferences {
  const PersistentPreferences(this._store);

  /// Opens the platform store and warms its cache. Call once, at startup.
  ///
  /// Scoped to [PrefKeys.all]: the store will neither read nor write anything
  /// outside that set, so a stray key is an error at the call site rather than
  /// an orphaned entry on disk.
  static Future<PersistentPreferences> open() async =>
      PersistentPreferences(await SharedPreferencesWithCache.create(
        cacheOptions: const SharedPreferencesWithCacheOptions(
          allowList: PrefKeys.all,
        ),
      ));

  final SharedPreferencesWithCache _store;

  @override
  bool getBool(String key, {bool defaultValue = false}) =>
      _store.getBool(key) ?? defaultValue;

  @override
  Future<void> setBool(String key, bool value) => _store.setBool(key, value);

  @override
  String? getString(String key) => _store.getString(key);

  @override
  Future<void> setString(String key, String value) =>
      _store.setString(key, value);
}
