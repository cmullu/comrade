/// Picture-in-picture: shrinking a live video call into a floating window so
/// the rest of the phone (or the rest of this app) stays usable.
///
/// Two things drive it, and only one of them is Dart's:
///
///  * **Leaving the app** — pressing home, or gesturing away, during a video
///    call. That is handled entirely natively (`MainActivity.onUserLeaveHint`
///    plus `setAutoEnterEnabled` on API 31+), because the window must enter PiP
///    *before* the Activity stops. A round trip to Dart cannot reliably fit in
///    that window, and the native side can already see whether a video call is
///    live — `CallManager` is a process-scoped singleton, so this works even
///    with the Flutter engine detached.
///  * **The in-call chat button** — an explicit request while the app is in
///    the foreground, which is [enter].
///
/// [modeChanges] is the single source of truth for "are we in PiP right now":
/// both paths land there, as does the user dragging the PiP window back to
/// full screen or the OS refusing the request.
///
/// **Every method degrades to "no native PiP" rather than throwing.** Desktop,
/// tests, Android Go devices and a user who revoked the PiP permission all take
/// the same path, and the caller's job is then to fall back to the in-app
/// floating tile (see `CallPipMode.inApp`). A missing plugin is a normal state
/// here, not an error.
library;

import 'dart:async';

import 'package:flutter/services.dart';

import 'channels.dart';

class PipChannel {
  PipChannel({MethodChannel? methods, EventChannel? state})
      : _methods = methods ?? const MethodChannel(Channels.pip),
        _state = state ?? const EventChannel(Channels.pipState);

  final MethodChannel _methods;
  final EventChannel _state;

  Stream<bool>? _stream;

  /// Whether this platform/device can show a native PiP window at all.
  ///
  /// Answers `false` — never throws — on a platform with no handler.
  Future<bool> isSupported() async {
    try {
      return await _methods.invokeMethod<bool>('isSupported') ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  /// Ask the platform to enter PiP now. Returns whether it actually did.
  ///
  /// [aspectWidth]/[aspectHeight] shape the floating window; the default is the
  /// 9:16 portrait a phone camera produces. Android clamps anything outside
  /// roughly 1:2.39–2.39:1, so passing a live frame's own ratio is safe.
  Future<bool> enter({double aspectWidth = 9, double aspectHeight = 16}) async {
    try {
      return await _methods.invokeMethod<bool>('enter', <String, Object?>{
            'aspectWidth': aspectWidth,
            'aspectHeight': aspectHeight,
          }) ??
          false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  /// Dismiss the PiP window, leaving the user wherever they were.
  ///
  /// Called when the call ends: a PiP window that outlives its call would sit
  /// there showing the app's ordinary UI in a thumbnail. Deliberately *not* a
  /// jump back to full screen — the call is over, and the person may well be
  /// mid-sentence in another app.
  Future<void> close() async {
    try {
      await _methods.invokeMethod<void>('close');
    } on PlatformException {
      // Nothing to close, or the platform refused: not an error worth
      // surfacing at the end of a call.
    } on MissingPluginException {
      // No native PiP here at all.
    }
  }

  /// `true` when the window entered PiP, `false` when it left.
  ///
  /// Emits the current value on subscribe, so a widget that attaches while
  /// already in PiP renders correctly on its first frame. An unimplemented
  /// channel yields an empty stream rather than an error.
  Stream<bool> get modeChanges => _stream ??= _state
      .receiveBroadcastStream()
      .map((Object? e) => e as bool? ?? false)
      // Swallowed rather than surfaced: every error this stream can produce
      // means "this platform has no PiP", which the caller already handles by
      // staying full screen. An unhandled error here would take down the zone
      // in a test that never asked about PiP at all.
      .handleError((Object _) {})
      .asBroadcastStream();
}
