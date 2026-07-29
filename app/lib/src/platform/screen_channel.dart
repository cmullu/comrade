/// Whether this window may be screenshotted.
///
/// The Android half is `channel/ScreenSecurityChannel.kt`, which is also where
/// the reasoning lives. In short: the Compose app blocked screenshots and screen
/// recording for its whole Activity, unconditionally, to protect key material no
/// screen actually shows. **Screenshots now work everywhere by default**, with
/// two ways to narrow that:
///
///  * [setBlocked] — the user's own choice, persisted natively (it has to apply
///    before the vault is unlocked, so it cannot live in the encrypted store),
///    surfaced as a switch in Settings.
///  * [setSecureWhileVisible] — a screen-scoped hold, taken by `SecureScreen`
///    while a surface showing genuinely secret material is mounted. Reference
///    counted natively, so nested holds cannot leave the flag stuck on.
///
/// A platform with no native half (desktop today) reports [supported] false and
/// the Settings switch is hidden rather than shown doing nothing.
library;

import 'package:flutter/services.dart';

import 'channels.dart';

class ScreenSecurityChannel {
  ScreenSecurityChannel({MethodChannel? methods})
      : _methods = methods ?? const MethodChannel(Channels.screen);

  final MethodChannel _methods;

  bool _supported = true;

  /// False once a call has shown there is no native implementation here.
  bool get supported => _supported;

  /// The user's stored preference — false (screenshots allowed) by default.
  Future<bool> isBlocked() async {
    try {
      return await _methods.invokeMethod<bool>('isBlocked') ?? false;
    } on MissingPluginException {
      _supported = false;
      return false;
    }
  }

  /// Block or allow screenshots app-wide. Returns the value actually stored, so
  /// a caller never reports a change the platform did not make.
  Future<bool> setBlocked({required bool blocked}) async {
    try {
      return await _methods.invokeMethod<bool>(
            'setBlocked',
            <String, Object?>{'blocked': blocked},
          ) ??
          blocked;
    } on MissingPluginException {
      _supported = false;
      return false;
    }
  }

  /// Take (or release) a screen-scoped hold. Returns whether the window ends up
  /// secure — the hold plus the preference.
  Future<bool> setSecureWhileVisible({required bool secure}) async {
    try {
      return await _methods.invokeMethod<bool>(
            'setSecureWhileVisible',
            <String, Object?>{'secure': secure},
          ) ??
          false;
    } on MissingPluginException {
      _supported = false;
      return false;
    }
  }
}
