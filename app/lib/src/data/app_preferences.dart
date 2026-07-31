/// Small key/value settings that belong to the *client*, not the vault.
///
/// Android kept these in `SharedPreferences` (Tara's opt-in flag,
/// `BackgroundConnectivityPreference`); desktop had no equivalent and simply
/// re-asked every launch. The unified app needs one seam so screens don't care
/// which platform they're on.
///
/// Two implementations. [InMemoryPreferences] is the default and what every
/// test injects; [PersistentPreferences] in `persistent_preferences.dart` is
/// what the shipping app installs at the composition root, and it is the one
/// that makes these settings survive a relaunch.
///
/// **Not the encrypted store**, which this file used to point at as the better
/// home. Every setting here has to be readable *before* the vault is unlocked —
/// the theme has to be right for the first frame of the onboarding screen, and
/// background connectivity decides whether to connect at all — so a store that
/// needs a passphrase cannot serve them. That is a reason, not a compromise:
/// nothing here is secret. The one client setting that is deliberately *not*
/// here is the screenshot policy, which lives on the platform side because
/// `FLAG_SECURE` has to be applied earlier still and is shared with the Compose
/// app.
///
/// The getters are synchronous by design, so a `build()` can read one without
/// becoming a `FutureProvider`. That is what forces the async work — opening the
/// store and warming its cache — into the composition root.
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

  /// Every key the app stores, and the allowlist the persisted store is opened
  /// with. A key that is not declared here throws at the call site instead of
  /// quietly writing an entry nothing will ever read back — which is the whole
  /// reason `shared_preferences` recommends an allowlist, and the reason this
  /// set is not merely documentation.
  static const Set<String> all = <String>{
    taraAccepted,
    backgroundConnectivity,
    themeMode,
  };
}
