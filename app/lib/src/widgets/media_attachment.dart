/// A chat bubble for one encrypted NIP-94/96 attachment.
///
/// Port of `ui/MediaAttachment.kt`. What is kept and what is not:
///
///  * **Kept**: images auto-load (the common, low-risk case); audio and video
///    need an explicit tap; anything else offers "open externally"; the
///    bubble's tail-corner shape matches a text bubble's.
///  * **Kept, and load-bearing**: decrypted plaintext lives in a bounded
///    in-memory cache and **never touches disk** (AUDIT S-4). Android had to
///    write audio/video to `cacheDir/media` because `MediaPlayer`/`VideoView`
///    need a path, and paid for it with an explicit purge on backgrounding.
///    Flutter's own image decoding takes bytes directly, so the image path
///    here has no disk step at all — one whole class of leak simply does not
///    exist. Audio/video playback is stubbed rather than reintroducing it: see
///    [MediaPlaybackDelegate].
///  * **Not kept**: `FileProvider` URIs and `Intent.ACTION_VIEW`. Handing a
///    decrypted file to another app is a platform decision, so it goes behind
///    the same delegate.
library;

import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/comrade_repository.dart';
import '../data/models.dart';
import '../state/providers.dart';
import '../theme/comrade_theme.dart';
import '../util/display_name.dart';

/// Platform hooks the pure-Dart UI cannot provide by itself.
///
/// The default refuses rather than pretending: a button that silently does
/// nothing is worse than one that says it isn't wired yet.
abstract interface class MediaPlaybackDelegate {
  /// Play (or pause) decrypted audio bytes. Returns whether playback started.
  Future<bool> toggleAudio(String eventId, MediaBytes bytes);

  /// Hand decrypted bytes to the platform's own viewer.
  Future<bool> openExternally(String eventId, MediaBytes bytes);

  /// A widget playing decrypted video, or `null` if unsupported here.
  Widget? videoPlayer(String eventId, MediaBytes bytes);
}

class UnsupportedMediaPlayback implements MediaPlaybackDelegate {
  const UnsupportedMediaPlayback();

  @override
  Future<bool> toggleAudio(String eventId, MediaBytes bytes) async => false;

  @override
  Future<bool> openExternally(String eventId, MediaBytes bytes) async => false;

  @override
  Widget? videoPlayer(String eventId, MediaBytes bytes) => null;
}

final Provider<MediaPlaybackDelegate> mediaPlaybackProvider =
    Provider<MediaPlaybackDelegate>(
        (Ref ref) => const UnsupportedMediaPlayback());

/// Bounded in-memory LRU of decrypted attachment bytes, keyed by event id.
///
/// Re-viewing (or scrolling past and back to) an attachment never re-decrypts
/// or re-downloads it, and nothing is written to disk, so there is nothing to
/// purge on backgrounding.
class DecryptedMediaCache {
  DecryptedMediaCache({this.capacity = 24});

  final int capacity;
  final Map<String, MediaBytes> _entries = <String, MediaBytes>{};

  MediaBytes? get(String eventId) {
    final MediaBytes? hit = _entries.remove(eventId);
    if (hit != null) _entries[eventId] = hit; // move to most-recent
    return hit;
  }

  void put(String eventId, MediaBytes bytes) {
    _entries
      ..remove(eventId)
      ..[eventId] = bytes;
    while (_entries.length > capacity) {
      _entries.remove(_entries.keys.first);
    }
  }

  /// Drop every plaintext this cache holds — on vault lock, or when the app
  /// leaves the foreground.
  void clear() => _entries.clear();

  int get length => _entries.length;
}

final Provider<DecryptedMediaCache> decryptedMediaCacheProvider =
    Provider<DecryptedMediaCache>((Ref ref) {
  final DecryptedMediaCache cache = DecryptedMediaCache();
  ref.onDispose(cache.clear);
  return cache;
});

/// Fetch + decrypt an attachment once, then serve it from the cache.
final FutureProviderFamily<MediaBytes, String> decryptedMediaProvider =
    FutureProvider.family<MediaBytes, String>((Ref ref, String eventId) async {
  final DecryptedMediaCache cache = ref.watch(decryptedMediaCacheProvider);
  final MediaBytes? hit = cache.get(eventId);
  if (hit != null) return hit;
  final MediaBytes bytes =
      await ref.watch(comradeRepositoryProvider).downloadMedia(eventId);
  cache.put(eventId, bytes);
  return bytes;
});

class MediaAttachmentBubble extends ConsumerWidget {
  const MediaAttachmentBubble(this.info, {this.maxWidth = 280, super.key});

  final MediaMessageInfo info;
  final double maxWidth;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ColorScheme colors = Theme.of(context).colorScheme;
    final bool out = info.outgoing;
    return Container(
      constraints: BoxConstraints(maxWidth: maxWidth),
      decoration: BoxDecoration(
        color: out ? colors.primaryContainer : colors.surfaceContainerHighest,
        borderRadius: BorderRadius.only(
          topLeft: const Radius.circular(ComradeRadii.bubble),
          topRight: const Radius.circular(ComradeRadii.bubble),
          bottomLeft: Radius.circular(
            out ? ComradeRadii.bubble : ComradeRadii.bubbleTail,
          ),
          bottomRight: Radius.circular(
            out ? ComradeRadii.bubbleTail : ComradeRadii.bubble,
          ),
        ),
      ),
      padding: const EdgeInsets.all(10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          if (info.caption.trim().isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: Text(info.caption,
                  style: Theme.of(context).textTheme.bodySmall),
            ),
          _body(context, ref),
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(
              relativeTime(info.createdAt),
              style: Theme.of(context)
                  .textTheme
                  .labelSmall
                  ?.copyWith(color: colors.outline),
            ),
          ),
        ],
      ),
    );
  }

  Widget _body(BuildContext context, WidgetRef ref) {
    if (info.mimeType.startsWith('image/')) return _InlineImage(info);
    if (info.mimeType.startsWith('audio/')) {
      return _TapToLoad(info: info, kind: _Kind.audio);
    }
    if (info.mimeType.startsWith('video/')) {
      return _TapToLoad(info: info, kind: _Kind.video);
    }
    return _TapToLoad(info: info, kind: _Kind.file);
  }
}

/// Images auto-load, like any standard messenger — no extra tap needed.
class _InlineImage extends ConsumerWidget {
  const _InlineImage(this.info);

  final MediaMessageInfo info;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final AsyncValue<MediaBytes> bytes =
        ref.watch(decryptedMediaProvider(info.eventId));
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 240, maxHeight: 240),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(ComradeRadii.small),
        child: bytes.when(
          data: (MediaBytes m) => Image.memory(
            m.bytes,
            fit: BoxFit.contain,
            semanticLabel:
                info.caption.trim().isEmpty ? 'Image attachment' : info.caption,
            errorBuilder: (BuildContext c, Object e, StackTrace? s) =>
                _error(context, 'Could not decode image', () {
              ref.invalidate(decryptedMediaProvider(info.eventId));
            }),
          ),
          loading: () => const Padding(
            padding: EdgeInsets.all(24),
            child: SizedBox(
              width: 28,
              height: 28,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
          ),
          error: (Object e, StackTrace s) => _error(
            context,
            e is ComradeException ? e.message : 'Could not load image',
            () => ref.invalidate(decryptedMediaProvider(info.eventId)),
          ),
        ),
      ),
    );
  }

  Widget _error(BuildContext context, String message, VoidCallback retry) =>
      InkWell(
        onTap: retry,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Text('⚠ $message · tap to retry',
              style: Theme.of(context).textTheme.bodySmall),
        ),
      );
}

enum _Kind { audio, video, file }

/// Audio, video, and generic files: decrypt on an explicit tap
/// (bandwidth-conscious), then hand off to the platform delegate.
class _TapToLoad extends ConsumerStatefulWidget {
  const _TapToLoad({required this.info, required this.kind});

  final MediaMessageInfo info;
  final _Kind kind;

  @override
  ConsumerState<_TapToLoad> createState() => _TapToLoadState();
}

class _TapToLoadState extends ConsumerState<_TapToLoad> {
  bool _loading = false;
  String? _error;
  Widget? _player;

  Future<void> _activate() async {
    if (_loading) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final MediaBytes bytes =
          await ref.read(decryptedMediaProvider(widget.info.eventId).future);
      final MediaPlaybackDelegate delegate = ref.read(mediaPlaybackProvider);
      switch (widget.kind) {
        case _Kind.audio:
          final bool ok =
              await delegate.toggleAudio(widget.info.eventId, bytes);
          if (!ok) {
            _error = 'Audio playback is not wired up on this platform yet.';
          }
        case _Kind.video:
          final Widget? player =
              delegate.videoPlayer(widget.info.eventId, bytes);
          if (player == null) {
            _error = 'Video playback is not wired up on this platform yet.';
          } else {
            _player = player;
          }
        case _Kind.file:
          final bool ok =
              await delegate.openExternally(widget.info.eventId, bytes);
          if (!ok) {
            _error = 'No handler for this file type on this platform yet.';
          }
      }
    } on ComradeException catch (e) {
      _error = e.message;
    } catch (_) {
      _error = 'Could not load the attachment.';
    }
    if (mounted) setState(() => _loading = false);
  }

  @override
  Widget build(BuildContext context) {
    if (_player != null) {
      return AspectRatio(aspectRatio: 16 / 9, child: _player);
    }
    final String label = switch (widget.kind) {
      _Kind.audio => 'Voice message',
      _Kind.video => 'Tap to load video',
      _Kind.file => 'Open ${widget.info.mimeType.split('/').last}',
    };
    final IconData icon = switch (widget.kind) {
      _Kind.audio => Icons.play_arrow,
      _Kind.video => Icons.movie_outlined,
      _Kind.file => Icons.download_outlined,
    };
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        OutlinedButton.icon(
          onPressed: _loading ? null : _activate,
          icon: _loading
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2))
              : Icon(icon, size: 18),
          label: Text(label),
        ),
        if (_error != null)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(
              _error!,
              style: Theme.of(context)
                  .textTheme
                  .bodySmall
                  ?.copyWith(color: Theme.of(context).colorScheme.error),
            ),
          ),
      ],
    );
  }
}

/// Picking a file is platform work too; the composer asks for this and gets
/// `null` until a picker is wired in.
abstract interface class AttachmentPicker {
  Future<PickedAttachment?> pick();
}

class PickedAttachment {
  const PickedAttachment({
    required this.name,
    required this.mimeType,
    required this.bytes,
  });

  final String name;
  final String mimeType;
  final Uint8List bytes;
}

class NoAttachmentPicker implements AttachmentPicker {
  const NoAttachmentPicker();

  @override
  Future<PickedAttachment?> pick() async => null;
}

final Provider<AttachmentPicker> attachmentPickerProvider =
    Provider<AttachmentPicker>((Ref ref) => const NoAttachmentPicker());
