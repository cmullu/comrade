/// The web stand-in for [readAndDeleteClip]. See `voice_clip.dart`.
library;

import 'dart:typed_data';

/// Always `null`: a browser has no `cacheDir` and no recorder plugin to have
/// written to one.
///
/// Unreachable in practice rather than merely unimplemented —
/// `MediaCapabilities.recordVoice` is false on the web, so
/// `NativeVoiceNoteRecorder` is never attached and nothing calls this. It exists
/// to make the web build *compile*, and returning `null` keeps it honest if that
/// assumption ever stops holding: the composer already renders "not available
/// here" for a null clip, which is true.
Future<Uint8List?> readAndDeleteClip(String path) async => null;
