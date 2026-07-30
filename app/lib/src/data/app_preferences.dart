/// Small key/value settings that belong to the *client*, not the vault.
///
/// Android kept these in `SharedPreferences` (Tara's opt-in flag,
/// `BackgroundConnectivityPreference`); desktop had no equivalent and simply
/// re-asked every launch. The unified app needs one seam so screens don't care
/// which platform they're on.
///
/// The default implementation is in-memory: nothing here is secret, but
/// nothing here is persisted yet either, and pretending otherwise would be a
/// lie the first relaunch exposes. Swap in a `shared_preferences`-backed
/// implementation (or, better, the encrypted store) at the composition root.
/// Every setting that reads through this seam — Tara's opt-in, background
/// connectivity, the theme choice — shares that one gap, and no screen claims
/// otherwise in its copy.
library;

abstract interface class AppPreferences {
  bool getBool(String key, {bool defaultValue = false});
  Future<void> setBool(String key, bool value);

  /// `null` when never set. Deliberately nullable rather than defaulted: an
  /// absent choice and a stored one are different facts, and the theme setting
  /// needs to tell them apart (absent means "follow the system", which is not
  /// the same as having been *chosen*).
  String? getString(String key);
  Future<void> setString(String key, String value);
}

class InMemoryPreferences implements AppPreferences {
  InMemoryPreferences([Map<String, Object>? seed])
      : _values = <String, Object>{...?seed};

  final Map<String, Object> _values;

  // Casting rather than type-testing: a key seeded with the wrong type is a
  // programming error, and a TypeError at the read site names it. Silently
  // handing back the default would hide it until the UI looked wrong.
  @override
  bool getBool(String key, {bool defaultValue = false}) =>
      _values[key] as bool? ?? defaultValue;

  @override
  Future<void> setBool(String key, bool value) async {
    _values[key] = value;
  }

  @override
  String? getString(String key) => _values[key] as String?;

  @override
  Future<void> setString(String key, String value) async {
    _values[key] = value;
  }
}

abstract final class PrefKeys {
  /// The user has read Tara's "this is not therapy" explainer and opted in.
  static const String taraAccepted = 'tara.accepted';

  /// Keep the relay connection alive while backgrounded (AUDIT COMMS-01).
  /// Default **on**: it is what makes an accepted DM or an incoming call
  /// notify you at all while the app isn't on screen.
  static const String backgroundConnectivity = 'connectivity.background';

  /// `system` (default) | `light` | `dark` — see `themeModeFromKey`.
  static const String themeMode = 'appearance.themeMode';
}
