/// The real implementation of [readAndDeleteClip]. See `voice_clip.dart`.
library;

import 'dart:io';
import 'dart:typed_data';

/// Read the clip at [path] once, then delete it, whatever happened.
///
/// `null` for "there is nothing usable here" — missing, unreadable, or empty.
/// An empty file is a recording that never captured anything, and passing zero
/// bytes on to the encrypted send would put an unplayable attachment in a
/// thread.
///
/// The delete is in a `finally` on purpose: the plaintext then lives for one
/// read rather than until something remembers to purge it, which is the same
/// discipline `MediaChannel` applies to a camera capture.
Future<Uint8List?> readAndDeleteClip(String path) async {
  final File clip = File(path);
  try {
    final Uint8List bytes = await clip.readAsBytes();
    return bytes.isEmpty ? null : bytes;
  } on FileSystemException {
    return null;
  } finally {
    try {
      await clip.delete();
    } on FileSystemException {
      // Already gone, or never written. Nothing to do and nothing to say.
    }
  }
}
