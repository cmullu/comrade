/// Typed wrapper over the voice-note recorder.
///
/// The native side is `VoiceRecorder`: a push-to-talk `MediaRecorder` writing
/// speech-tuned mono AAC into the app's cache dir. It pauses the wake-word
/// recogniser for the duration through the refcounted `MicHolderSet`, so a
/// recording and a call can overlap without either stealing the mic — that
/// bookkeeping is native and not reachable from here (see [WakeWordChannel]).
///
/// See `../../../android/app/src/main/kotlin/mullu/comrade/PLATFORM_CHANNELS.md`
/// §9.
library;

import 'dart:async';

import 'package:flutter/services.dart';

import 'channels.dart';

/// A finished voice note on disk.
///
/// **A path, deliberately — not bytes.** The file is a plaintext recording in
/// the cache dir and must be deleted the moment the encrypted send resolves
/// (the plaintext must not outlive the send; AUDIT S-4). Handing base64 across
/// the channel would put a second plaintext copy in the Dart heap with no
/// deletion discipline attached to it at all. Whoever receives this owns the
/// file and owns deleting it.
final class VoiceNote {
  const VoiceNote({required this.path, required this.mimeType});

  final String path;
  final String mimeType;
}

class VoiceRecorderChannel {
  VoiceRecorderChannel({MethodChannel? methods})
      : _methods = methods ?? const MethodChannel(Channels.recorder);

  final MethodChannel _methods;

  /// Begin capturing.
  ///
  /// Returns `false` when the recorder could not be set up — mic busy, no mic,
  /// encoder unavailable. Nothing to clean up on that path: no file is created
  /// and no partial recorder is left running. Requests `RECORD_AUDIO` first and
  /// throws [PlatformErrorCodes.permissionDenied] if refused.
  Future<bool> start() async =>
      await _methods.invokeMethod<bool>('start') ?? false;

  /// Stop and return the clip, or `null` if the press was too short (< 500 ms)
  /// to be a real note — an accidental tap, not an error. The file is already
  /// deleted in that case.
  Future<VoiceNote?> stop() async {
    final map = await _methods.invokeMapMethod<String, Object?>('stop');
    if (map == null) return null;
    return VoiceNote(
      path: map['path'] as String? ?? '',
      mimeType: map['mimeType'] as String? ?? 'audio/aac',
    );
  }

  /// Abort and delete the partial file.
  Future<void> cancel() => _methods.invokeMethod<void>('cancel');

  Future<bool> isRecording() async =>
      await _methods.invokeMethod<bool>('isRecording') ?? false;
}
