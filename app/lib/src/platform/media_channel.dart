/// Attachments, natively: choosing one, capturing one, playing one, handing one
/// to another app.
///
/// The Android half is `channel/MediaChannel.kt`. This file is the three seams
/// the media UI declares — [AttachmentPicker], [MediaCaptureDelegate] and
/// [MediaPlaybackDelegate] — implemented over that channel, plus the capability
/// probe the composer uses so it never shows a button this device cannot honour.
///
/// **Bytes cross this channel, and that is deliberate.** The alternative (paths)
/// would mean plaintext on disk with a deletion discipline attached, which is
/// exactly what the unified app set out not to have: decrypted attachments live
/// in `DecryptedMediaCache` and are served to other apps out of memory. The one
/// place a file is unavoidable is camera capture, and the native side deletes it
/// in a `finally` — see `MediaChannel.kt`.
///
/// On any platform without the plugin (desktop today), every call raises
/// [MissingPluginException]; each wrapper turns that into the same "not
/// available here" answer the do-nothing defaults gave, so a desktop build keeps
/// working with the honest message rather than crashing.
library;

import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import '../data/models.dart';
import '../widgets/media_attachment.dart';
import 'channels.dart';
import 'voice_recorder_channel.dart';

/// What this device can actually do with attachments.
///
/// Absent (all false) on a platform with no native half, so the composer
/// degrades to "attach + send" instead of offering a camera that cannot open.
@immutable
class MediaCapabilities {
  const MediaCapabilities({
    this.pick = false,
    this.capturePhoto = false,
    this.captureVideo = false,
    this.recordVoice = false,
    this.playAudio = false,
    this.openExternally = false,
  });

  static const MediaCapabilities none = MediaCapabilities();

  final bool pick;
  final bool capturePhoto;
  final bool captureVideo;
  final bool recordVoice;
  final bool playAudio;
  final bool openExternally;

  bool get canCapture => capturePhoto || captureVideo;

  factory MediaCapabilities.fromMap(Map<String, Object?> map) =>
      MediaCapabilities(
        pick: map['pick'] == true,
        capturePhoto: map['capturePhoto'] == true,
        captureVideo: map['captureVideo'] == true,
        recordVoice: map['recordVoice'] == true,
        playAudio: map['playAudio'] == true,
        openExternally: map['openExternally'] == true,
      );

  @override
  bool operator ==(Object other) =>
      other is MediaCapabilities &&
      other.pick == pick &&
      other.capturePhoto == capturePhoto &&
      other.captureVideo == captureVideo &&
      other.recordVoice == recordVoice &&
      other.playAudio == playAudio &&
      other.openExternally == openExternally;

  @override
  int get hashCode => Object.hash(
      pick, capturePhoto, captureVideo, recordVoice, playAudio, openExternally);
}

/// Raw wrapper. The typed seams below are what the UI uses.
class MediaChannel {
  MediaChannel({MethodChannel? methods})
      : _methods = methods ?? const MethodChannel(Channels.media);

  final MethodChannel _methods;

  /// Probe the device. Answers [MediaCapabilities.none] where there is no native
  /// half rather than throwing: this runs at startup, before `runApp`, and a
  /// desktop build must not fail to launch because it has no camera channel.
  Future<MediaCapabilities> capabilities() async {
    try {
      final Map<String, Object?>? map =
          await _methods.invokeMapMethod<String, Object?>('capabilities');
      return map == null
          ? MediaCapabilities.none
          : MediaCapabilities.fromMap(map);
    } on MissingPluginException {
      return MediaCapabilities.none;
    }
  }

  /// The document picker. `null` when the user cancelled.
  Future<PickedAttachment?> pick() => _picked('pick');

  /// The camera. `null` when the user backed out without capturing.
  Future<PickedAttachment?> capturePhoto() => _picked('capturePhoto');

  Future<PickedAttachment?> captureVideo() => _picked('captureVideo');

  Future<PickedAttachment?> _picked(String method) async {
    final Map<String, Object?>? map =
        await _methods.invokeMapMethod<String, Object?>(method);
    if (map == null) return null;
    final Uint8List? bytes = map['bytes'] as Uint8List?;
    if (bytes == null || bytes.isEmpty) return null;
    return PickedAttachment(
      name: map['name'] as String? ?? 'attachment',
      mimeType: map['mimeType'] as String? ?? 'application/octet-stream',
      bytes: bytes,
    );
  }

  /// Play/pause one attachment. Returns whether it is playing afterwards.
  Future<bool> toggleAudio(String eventId, Uint8List bytes) async =>
      await _methods.invokeMethod<bool>('toggleAudio', <String, Object?>{
        'eventId': eventId,
        'bytes': bytes,
      }) ??
      false;

  Future<void> stopAudio() => _methods.invokeMethod<void>('stopAudio');

  /// Hand the bytes to whatever app the user has for this type. `false` when
  /// nothing on the device can open it.
  Future<bool> openExternally({
    required String name,
    required String mimeType,
    required Uint8List bytes,
  }) async =>
      await _methods.invokeMethod<bool>('openExternally', <String, Object?>{
        'name': name,
        'mimeType': mimeType,
        'bytes': bytes,
      }) ??
      false;

  /// Drop every decrypted plaintext the native layer holds — the audio buffer
  /// and anything staged for an external viewer. Paired with the Dart-side
  /// `DecryptedMediaCache.clear()`; both run when the app backgrounds or the
  /// vault locks.
  Future<void> purge() => _methods.invokeMethod<void>('purge');
}

/// Runs [call], answering [fallback] when there is no native half on this
/// platform. Any *other* platform error is left to the caller: "the picker
/// failed" and "there is no picker" are different facts and the UI says
/// different things about them.
Future<T> _orUnsupported<T>(Future<T> Function() call, T fallback) async {
  try {
    return await call();
  } on MissingPluginException {
    return fallback;
  }
}

class NativeAttachmentPicker implements AttachmentPicker {
  NativeAttachmentPicker(this._channel);

  final MediaChannel _channel;

  @override
  Future<PickedAttachment?> pick() =>
      _orUnsupported<PickedAttachment?>(_channel.pick, null);
}

class NativeMediaCapture implements MediaCaptureDelegate {
  NativeMediaCapture(this._channel, this._capabilities);

  final MediaChannel _channel;
  final MediaCapabilities _capabilities;

  @override
  bool get canCapturePhoto => _capabilities.capturePhoto;

  @override
  bool get canCaptureVideo => _capabilities.captureVideo;

  @override
  Future<PickedAttachment?> capturePhoto() =>
      _orUnsupported<PickedAttachment?>(_channel.capturePhoto, null);

  @override
  Future<PickedAttachment?> captureVideo() =>
      _orUnsupported<PickedAttachment?>(_channel.captureVideo, null);
}

class NativeMediaPlayback implements MediaPlaybackDelegate {
  NativeMediaPlayback(this._channel);

  final MediaChannel _channel;

  @override
  Future<bool> toggleAudio(String eventId, MediaBytes bytes) =>
      _orUnsupported<bool>(
          () => _channel.toggleAudio(eventId, bytes.bytes), false);

  @override
  Future<bool> openExternally(String eventId, MediaBytes bytes) =>
      _orUnsupported<bool>(
        () => _channel.openExternally(
          // The event id makes a usable filename for a share sheet and cannot
          // carry anything the peer chose — a caption could.
          name: 'comrade-$eventId',
          mimeType: bytes.mimeType,
          bytes: bytes.bytes,
        ),
        false,
      );

  /// No in-app video player: a `SurfaceView` + player PlatformView is a
  /// workstream of its own, and a received video is watchable *today* because
  /// the bubble falls back to [openExternally] when this returns null (which
  /// hands the bytes to the system player from memory). Honest null rather than
  /// a widget that shows a black rectangle.
  @override
  Widget? videoPlayer(String eventId, MediaBytes bytes) => null;

  @override
  Future<void> purge() async {
    try {
      await _channel.purge();
    } on MissingPluginException {
      // Nothing native to purge on this platform.
    }
  }
}

/// Voice notes over the existing [VoiceRecorderChannel].
///
/// That channel hands back a *path* on purpose (the clip is plaintext in
/// `cacheDir`, and whoever receives it owns deleting it — `VoiceRecorderChannel`
/// says so). This is that owner: it reads the clip once, deletes it, and passes
/// bytes on to the encrypted send. The plaintext therefore lives for one read
/// rather than until something remembers to purge — the same discipline
/// `MediaChannel` applies to a camera capture.
class NativeVoiceNoteRecorder implements VoiceNoteRecorder {
  NativeVoiceNoteRecorder(this._recorder, {required bool available})
      : _available = available;

  final VoiceRecorderChannel _recorder;
  final bool _available;

  @override
  bool get available => _available;

  @override
  Future<bool> start() async {
    try {
      return await _recorder.start();
    } on PlatformException {
      // Permission refused, mic busy, no encoder. All "could not start".
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  @override
  Future<PickedAttachment?> stop() async {
    final VoiceNote? note = await _stopQuietly();
    if (note == null) return null;
    final File clip = File(note.path);
    try {
      final Uint8List bytes = await clip.readAsBytes();
      if (bytes.isEmpty) return null;
      return PickedAttachment(
        name: 'Voice note',
        mimeType: note.mimeType,
        bytes: bytes,
      );
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

  Future<VoiceNote?> _stopQuietly() async {
    try {
      return await _recorder.stop();
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
  }

  @override
  Future<void> cancel() async {
    try {
      await _recorder.cancel();
    } on PlatformException {
      // Nothing to cancel.
    } on MissingPluginException {
      // No recorder here.
    }
  }
}
