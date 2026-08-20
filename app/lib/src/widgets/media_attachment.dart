/// A chat bubble for one encrypted NIP-94/96 attachment.
///
/// Port of `ui/MediaAttachment.kt`. What is kept and what is not:
///
///  * **Kept**: images auto-load (the common, low-risk case); audio and video
///    fetch only on an explicit tap; anything else offers "open externally";
///    the bubble's tail-corner shape matches a text bubble's.
///  * **Added since the port**: a tap on a photo or a video opens it full
///    screen (`media_viewer.dart`), and a long-press on any attachment starts a
///    reply aimed at it. Both were in every messenger this is compared to and
///    in neither of the frontends this was ported from.
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
import 'media_viewer.dart';
import '../util/message_reactions.dart';
import 'message_bubble.dart';

/// Platform hooks the pure-Dart UI cannot provide by itself.
///
/// The default refuses rather than pretending: a button that silently does
/// nothing is worse than one that says it isn't wired yet.
abstract interface class MediaPlaybackDelegate {
  /// Play (or pause) decrypted audio bytes. Returns whether it is *playing*
  /// after the call, so a bubble can render play/pause from the answer.
  Future<bool> toggleAudio(String eventId, MediaBytes bytes);

  /// Hand decrypted bytes to the platform's own viewer.
  Future<bool> openExternally(String eventId, MediaBytes bytes);

  /// A widget playing decrypted video, or `null` if unsupported here.
  Widget? videoPlayer(String eventId, MediaBytes bytes);

  /// Drop every decrypted plaintext the *platform* side is holding — a playing
  /// audio buffer, anything staged for an external viewer. Called together with
  /// [DecryptedMediaCache.clear] when the app leaves the foreground or the vault
  /// locks (see [purgeDecryptedMedia]).
  Future<void> purge();
}

class UnsupportedMediaPlayback implements MediaPlaybackDelegate {
  const UnsupportedMediaPlayback();

  @override
  Future<bool> toggleAudio(String eventId, MediaBytes bytes) async => false;

  @override
  Future<bool> openExternally(String eventId, MediaBytes bytes) async => false;

  @override
  Widget? videoPlayer(String eventId, MediaBytes bytes) => null;

  @override
  Future<void> purge() async {}
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

/// Drop every decrypted attachment this app is holding, on both sides of the
/// platform boundary, and invalidate the futures that served them so the next
/// view re-fetches rather than reading a disposed cache.
///
/// Called when the app leaves the foreground and when the vault locks. Compose
/// had `purgeDecryptedMedia(context)` for the same reason (AUDIT S-4) and it had
/// files to delete; here there are none — this is memory only, which is why the
/// property holds even if the call is missed. It is still made, because a
/// screenshot of the recents thumbnail, or a phone handed over unlocked, should
/// not come with the last photo someone sent you.
Future<void> purgeDecryptedMedia(WidgetRef ref) async {
  ref.read(decryptedMediaCacheProvider).clear();
  ref.invalidate(decryptedMediaProvider);
  await ref.read(mediaPlaybackProvider).purge();
}

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
  const MediaAttachmentBubble(
    this.info, {
    this.onReply,
    this.onLongPress,
    this.chips = const <ReactionChip>[],
    this.onToggleReaction,
    this.maxWidth = 280,
    super.key,
  });

  final MediaMessageInfo info;

  /// Start a reply aimed at this attachment. Null where the caller offers no
  /// reply (the couple panel, a preview) — see [ReplyAffordance].
  final VoidCallback? onReply;

  /// Opens the reaction/action sheet — long press. Null where reacting is not
  /// offered (the couple panel, a preview), same as [onReply].
  final VoidCallback? onLongPress;

  /// The reaction chips to draw beneath this attachment, already grouped.
  final List<ReactionChip> chips;
  final void Function(String emoji)? onToggleReaction;

  final double maxWidth;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ColorScheme colors = Theme.of(context).colorScheme;
    final bool out = info.outgoing;
    final Widget bubble = Container(
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
    final void Function(String)? toggle = onToggleReaction;
    return Column(
      crossAxisAlignment:
          out ? CrossAxisAlignment.end : CrossAxisAlignment.start,
      children: <Widget>[
        ReplyAffordance(
          onReply: onReply,
          onLongPress: onLongPress,
          outgoing: out,
          child: bubble,
        ),
        if (toggle != null)
          ReactionChipRow(chips: chips, onToggle: toggle, outgoing: out),
      ],
    );
  }

  Widget _body(BuildContext context, WidgetRef ref) {
    if (info.mimeType.startsWith('image/')) return _InlineImage(info);
    if (info.mimeType.startsWith('video/')) return _VideoPoster(info);
    if (info.mimeType.startsWith('audio/')) {
      return _TapToLoad(info: info, kind: _Kind.audio);
    }
    return _TapToLoad(info: info, kind: _Kind.file);
  }
}

/// Images auto-load, like any standard messenger — no extra tap needed. The
/// tap is spent on opening the photo full screen instead, which is what a tap
/// on a photo means everywhere else.
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
        // `lg`, kept at its old value — an inline photo up to 240px square
        // reads as a card next to the bubble's own 18px rounding, not as
        // `small`'s "chip"; see `ComradeRadii.small`'s doc.
        borderRadius: BorderRadius.circular(ComradeRadii.lg),
        child: bytes.when(
          data: (MediaBytes m) => GestureDetector(
            key: const Key('media-open-fullscreen'),
            onTap: () => openMediaViewer(context, info),
            child: Image.memory(
              m.bytes,
              fit: BoxFit.contain,
              semanticLabel: info.caption.trim().isEmpty
                  ? 'Photo attachment — tap to view full screen'
                  : info.caption,
              errorBuilder: (BuildContext c, Object e, StackTrace? s) =>
                  _error(context, 'Could not decode image', () {
                ref.invalidate(decryptedMediaProvider(info.eventId));
              }),
            ),
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

/// A video is a tap target that opens the full-screen viewer — the decrypt and
/// the two-step player fallback both live there now.
///
/// Nothing is fetched until the tap, so this is still bandwidth-conscious; the
/// difference is that the bytes, once fetched, are played on a whole screen
/// rather than in a 240 px bubble.
class _VideoPoster extends StatelessWidget {
  const _VideoPoster(this.info);

  final MediaMessageInfo info;

  @override
  Widget build(BuildContext context) {
    final ColorScheme colors = Theme.of(context).colorScheme;
    // `lg`, kept at its old value on both the ink well and the fill below
    // it — they have to match, and this is a 220×124 preview, not `small`'s
    // "chip"; see `ComradeRadii.small`'s doc.
    return InkWell(
      key: const Key('media-open-fullscreen'),
      borderRadius: BorderRadius.circular(ComradeRadii.lg),
      onTap: () => openMediaViewer(context, info),
      child: Container(
        width: 220,
        height: 124,
        decoration: BoxDecoration(
          color: colors.surface.withValues(alpha: 0.4),
          borderRadius: BorderRadius.circular(ComradeRadii.lg),
        ),
        alignment: Alignment.center,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Icon(Icons.play_circle_outline, size: 42, color: colors.primary),
            const SizedBox(height: 4),
            Text(
              'Tap to play video',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      ),
    );
  }
}

enum _Kind { audio, file }

/// Audio and generic files: decrypt on an explicit tap (bandwidth-conscious),
/// then hand off to the platform delegate.
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

  /// Playing, for audio only — the glyph is a pause while it is.
  bool _playing = false;

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
          // "Not playing" after a toggle means *paused* if it was playing
          // before, and "no audio support here" if it was not. Without the
          // distinction, pausing a voice note would claim the platform cannot
          // play it.
          final bool wasPlaying = _playing;
          final bool playing =
              await delegate.toggleAudio(widget.info.eventId, bytes);
          _playing = playing;
          if (!playing && !wasPlaying) {
            _error = 'Audio playback is not wired up on this platform yet.';
          }
        case _Kind.file:
          final bool ok =
              await delegate.openExternally(widget.info.eventId, bytes);
          if (!ok) {
            _error = 'No app on this device can open this file.';
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
    final String label = switch (widget.kind) {
      _Kind.audio => _playing ? 'Playing…' : 'Voice message',
      _Kind.file => 'Open ${widget.info.mimeType.split('/').last}',
    };
    final IconData icon = switch (widget.kind) {
      _Kind.audio => _playing ? Icons.pause : Icons.play_arrow,
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

/// Capturing something new, rather than choosing something that exists.
///
/// The two `can…` getters exist so the composer can *omit* a control instead of
/// offering one that fails on tap — the settings screen's "no fake switches"
/// rule, applied to the camera button.
abstract interface class MediaCaptureDelegate {
  bool get canCapturePhoto;
  bool get canCaptureVideo;

  /// `null` when the user backed out without capturing.
  Future<PickedAttachment?> capturePhoto();

  Future<PickedAttachment?> captureVideo();
}

class NoMediaCapture implements MediaCaptureDelegate {
  const NoMediaCapture();

  @override
  bool get canCapturePhoto => false;

  @override
  bool get canCaptureVideo => false;

  @override
  Future<PickedAttachment?> capturePhoto() async => null;

  @override
  Future<PickedAttachment?> captureVideo() async => null;
}

final Provider<MediaCaptureDelegate> mediaCaptureProvider =
    Provider<MediaCaptureDelegate>((Ref ref) => const NoMediaCapture());

/// Recording a voice note — the Telegram mic button's engine.
///
/// Android's Compose composer had this as press-and-hold; the unified composer
/// makes it tap-to-start / tap-to-send instead, because press-and-hold has no
/// discoverable equivalent with a mouse (divergence D13, revisited: the
/// *feature* was the part worth keeping, the gesture was not).
abstract interface class VoiceNoteRecorder {
  bool get available;

  /// Begin capturing. False when the recorder could not be set up (mic busy, no
  /// mic, permission refused) — nothing to clean up on that path.
  Future<bool> start();

  /// Stop and return the note, or `null` when the press was too short to be one.
  Future<PickedAttachment?> stop();

  /// Abort and discard.
  Future<void> cancel();
}

class NoVoiceNoteRecorder implements VoiceNoteRecorder {
  const NoVoiceNoteRecorder();

  @override
  bool get available => false;

  @override
  Future<bool> start() async => false;

  @override
  Future<PickedAttachment?> stop() async => null;

  @override
  Future<void> cancel() async {}
}

final Provider<VoiceNoteRecorder> voiceNoteRecorderProvider =
    Provider<VoiceNoteRecorder>((Ref ref) => const NoVoiceNoteRecorder());

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
