/// Read-once-and-delete for a recorded voice clip, behind a conditional import.
///
/// `VoiceRecorderChannel` hands back a *path*: the clip is plaintext in
/// `cacheDir`, and whoever receives it owns deleting it. Doing that needs
/// `dart:io`, which does not exist on the web — and importing it unconditionally
/// is what made `flutter build web` impossible, from a file the web build can
/// never reach anyway (there is no recorder plugin in a browser, so
/// `MediaCapabilities.recordVoice` is false and `NativeVoiceNoteRecorder` is
/// never attached).
///
/// So the platform seam is here rather than at the call site. The default is the
/// real one; the web build gets a stub that says the same thing the missing
/// plugin already says.
library;

export 'voice_clip_io.dart' if (dart.library.js_interop) 'voice_clip_web.dart';
