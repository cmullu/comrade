/// Typed wrapper over the wake-word service's channels.
///
/// The native side is `WakeWordService` — an always-on foreground service
/// running the offline Vosk recogniser — plus `VoskModel` (the refcounted
/// shared model) and `OneShotRecognizer`.
///
/// See `../../../android/app/src/main/kotlin/mullu/comrade/PLATFORM_CHANNELS.md`
/// §7. Two things are deliberately missing from this API and their absence is
/// the invariant:
///
///  * **No pause/resume.** The mic handoff between a call and a voice-note
///    recording is refcounted natively through `MicHolderSet`, and both holders
///    are native. If Dart could call `resume`, an unbalanced call from some
///    widget's `dispose` would restart the recogniser mid-call — the exact bug
///    AUDIT's 2026-07-15 review found reachable through the app's real
///    navigation (hold record → answer a call → release record).
///  * **No model acquire/release.** The 30 s idle-close that reclaims the
///    model's RAM only works if every reference is returned by an owner with a
///    real teardown. A Dart-held reference is one no `onDestroy` can guarantee
///    returning.
library;

import 'dart:async';

import 'package:flutter/services.dart';

import 'channels.dart';

/// What the recogniser is doing right now. An enum key, not a localised
/// string — the wording lives in Dart's `.arb` files now. (The service still
/// sets its own *notification* text from `R.string`, because it owns that
/// notification.)
enum WakeWordStatus {
  /// Service not running.
  idle,

  /// Listening for "Hey Comrade".
  listening,

  /// Wake phrase heard; the next utterance is a command.
  goAhead,

  /// No speech model installed — offer the download instead of an error.
  modelMissing,

  /// The recogniser could not open the mic.
  micError,
}

final class WakeWordState {
  const WakeWordState({
    required this.running,
    required this.status,
    required this.modelAvailable,
  });

  static const unknown = WakeWordState(
    running: false,
    status: WakeWordStatus.idle,
    modelAvailable: false,
  );

  final bool running;
  final WakeWordStatus status;

  /// Whether a model can be loaded with no user interaction — bundled in the
  /// APK, previously downloaded, or already resident. Voice entry points check
  /// this up front and offer the on-demand download when false.
  final bool modelAvailable;

  factory WakeWordState.fromMap(Map<Object?, Object?> map) => WakeWordState(
        running: map['running'] as bool? ?? false,
        status: switch (map['status'] as String?) {
          'listening' => WakeWordStatus.listening,
          'goAhead' => WakeWordStatus.goAhead,
          'modelMissing' => WakeWordStatus.modelMissing,
          'micError' => WakeWordStatus.micError,
          _ => WakeWordStatus.idle,
        },
        modelAvailable: map['modelAvailable'] as bool? ?? false,
      );
}

class WakeWordChannel {
  WakeWordChannel({MethodChannel? methods, EventChannel? state})
      : _methods = methods ?? const MethodChannel(Channels.wakeWord),
        _state = state ?? const EventChannel(Channels.wakeWordState);

  final MethodChannel _methods;
  final EventChannel _state;

  Stream<WakeWordState>? _stream;

  Stream<WakeWordState> get state => _stream ??= _state
      .receiveBroadcastStream()
      .map((e) => WakeWordState.fromMap(e as Map<Object?, Object?>))
      .asBroadcastStream();

  /// Start the always-listening service. Requests `RECORD_AUDIO` first; throws
  /// [PlatformErrorCodes.permissionDenied] if refused.
  Future<void> start() => _methods.invokeMethod<void>('start');

  Future<void> stop() => _methods.invokeMethod<void>('stop');

  /// Read from the *service's* own flag, not from remembered widget state — the
  /// voice screen is disposed on every tab switch, so a toggle must re-seed
  /// from whether the service is actually alive.
  Future<bool> isRunning() async =>
      await _methods.invokeMethod<bool>('isRunning') ?? false;

  Future<bool> isModelAvailable() async =>
      await _methods.invokeMethod<bool>('isModelAvailable') ?? false;

  /// Capture a single utterance offline (tap-to-talk, dictation) and return the
  /// recognised text — possibly empty if nothing was heard.
  ///
  /// Deliberately returns *text* and does not run a command: dispatching from
  /// the recogniser's callback is precisely what AUDIT's 2026-07-15 pass moved
  /// off the main looper. What to do with the words is Dart's decision.
  Future<String> listenOnce(
          {Duration timeout = const Duration(seconds: 7)}) async =>
      await _methods.invokeMethod<String>(
          'listenOnce', {'timeoutMs': timeout.inMilliseconds}) ??
      '';
}
