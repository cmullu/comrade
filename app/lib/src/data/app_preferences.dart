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
library;

abstract interface class AppPreferences {
  bool getBool(String key, {bool defaultValue = false});
  Future<void> setBool(String key, bool value);
}

class InMemoryPreferences implements AppPreferences {
  InMemoryPreferences([Map<String, bool>? seed])
      : _values = <String, bool>{...?seed};

  final Map<String, bool> _values;

  @override
  bool getBool(String key, {bool defaultValue = false}) =>
      _values[key] ?? defaultValue;

  @override
  Future<void> setBool(String key, bool value) async {
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
}
