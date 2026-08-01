/// Full-screen photo and video viewing.
///
/// Tapping a photo or video in a thread opens it here: black background,
/// pinch-and-drag zoom, the caption if there is one, and a way out. Every
/// messenger does this and its absence is felt immediately — a 240 px bubble is
/// a thumbnail, not a look at the picture.
///
/// Two properties this shares with the bubble it was opened from, and must not
/// lose:
///
///  * **The plaintext still never touches disk.** The viewer reads the same
///    [decryptedMediaProvider] the bubble does, so opening one costs no extra
///    fetch and no extra decrypt, and [purgeDecryptedMedia] still drops
///    everything in one call.
///  * **Video is delegated, not reimplemented.** An in-app player needs a
///    PlatformView; where there is none, the bytes go to the system player
///    exactly as they do inline.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/comrade_repository.dart';
import '../data/models.dart';
import '../util/attachment_caption.dart';
import 'media_attachment.dart';

/// Open [info] full screen. A no-op for types that have nothing to show
/// full screen (audio, documents) so callers can wire it to any bubble.
Future<void> openMediaViewer(
    BuildContext context, MediaMessageInfo info) async {
  if (!opensFullScreen(info.mimeType)) return;
  await Navigator.of(context).push<void>(
    MaterialPageRoute<void>(
      fullscreenDialog: true,
      builder: (BuildContext _) => MediaViewerPage(info),
    ),
  );
}

class MediaViewerPage extends ConsumerWidget {
  const MediaViewerPage(this.info, {super.key});

  final MediaMessageInfo info;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final AsyncValue<MediaBytes> bytes =
        ref.watch(decryptedMediaProvider(info.eventId));
    final String caption = info.caption.trim();
    // Deliberately not the app theme: a photo is judged against black, and the
    // surrounding chrome should not tint it. Both frontends agree on this.
    return Theme(
      data: ThemeData.dark(useMaterial3: true),
      child: Scaffold(
        backgroundColor: Colors.black,
        appBar: AppBar(
          key: const Key('media-viewer-bar'),
          backgroundColor: Colors.black.withValues(alpha: 0.6),
          foregroundColor: Colors.white,
          elevation: 0,
          leading: IconButton(
            key: const Key('media-viewer-close'),
            tooltip: 'Close',
            onPressed: () => Navigator.of(context).maybePop(),
            icon: const Icon(Icons.close),
          ),
          title: Text(
            mediaKindLabel(info.mimeType),
            style: const TextStyle(fontSize: 16),
          ),
          actions: <Widget>[
            IconButton(
              key: const Key('media-viewer-open-externally'),
              tooltip: 'Open with another app',
              onPressed: bytes.hasValue
                  ? () => _openExternally(context, ref, bytes.requireValue)
                  : null,
              icon: const Icon(Icons.open_in_new),
            ),
          ],
        ),
        body: Column(
          children: <Widget>[
            Expanded(
              child: Center(
                child: bytes.when(
                  data: (MediaBytes m) => _content(context, ref, m),
                  loading: () => const CircularProgressIndicator(),
                  error: (Object e, StackTrace s) => _Failed(
                    message: e is ComradeException
                        ? e.message
                        : 'Could not load this attachment.',
                    onRetry: () =>
                        ref.invalidate(decryptedMediaProvider(info.eventId)),
                  ),
                ),
              ),
            ),
            if (caption.isNotEmpty)
              SafeArea(
                top: false,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                  child: Text(
                    caption,
                    key: const Key('media-viewer-caption'),
                    style: const TextStyle(color: Colors.white),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _content(BuildContext context, WidgetRef ref, MediaBytes m) {
    if (info.mimeType.startsWith('image/')) {
      return InteractiveViewer(
        key: const Key('media-viewer-image'),
        minScale: 1,
        maxScale: 6,
        child: Image.memory(
          m.bytes,
          fit: BoxFit.contain,
          semanticLabel: info.caption.trim().isEmpty
              ? 'Photo attachment'
              : info.caption.trim(),
          errorBuilder: (BuildContext c, Object e, StackTrace? s) => _Failed(
            message: 'Could not decode this image.',
            onRetry: () => ref.invalidate(decryptedMediaProvider(info.eventId)),
          ),
        ),
      );
    }
    return _FullScreenVideo(info: info, bytes: m);
  }

  Future<void> _openExternally(
    BuildContext context,
    WidgetRef ref,
    MediaBytes m,
  ) async {
    final ScaffoldMessengerState? messenger =
        ScaffoldMessenger.maybeOf(context);
    final bool ok =
        await ref.read(mediaPlaybackProvider).openExternally(info.eventId, m);
    if (!ok) {
      messenger?.showSnackBar(
        const SnackBar(content: Text('No app on this device can open this.')),
      );
    }
  }
}

/// Video, full screen: the in-app player when the platform has one, the system
/// player when it does not, and an honest line when it has neither.
class _FullScreenVideo extends ConsumerStatefulWidget {
  const _FullScreenVideo({required this.info, required this.bytes});

  final MediaMessageInfo info;
  final MediaBytes bytes;

  @override
  ConsumerState<_FullScreenVideo> createState() => _FullScreenVideoState();
}

class _FullScreenVideoState extends ConsumerState<_FullScreenVideo> {
  bool _handedOff = false;
  String? _error;

  @override
  Widget build(BuildContext context) {
    final Widget? player = ref
        .read(mediaPlaybackProvider)
        .videoPlayer(widget.info.eventId, widget.bytes);
    if (player != null) {
      return AspectRatio(
        key: const Key('media-viewer-video'),
        aspectRatio: 16 / 9,
        child: player,
      );
    }
    if (_error != null) {
      return _Failed(message: _error!, onRetry: null);
    }
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        const Icon(Icons.movie_outlined, size: 56, color: Colors.white70),
        const SizedBox(height: 12),
        FilledButton.icon(
          key: const Key('media-viewer-play-externally'),
          onPressed: _handedOff ? null : _handOff,
          icon: const Icon(Icons.play_arrow),
          label: const Text('Play with another app'),
        ),
      ],
    );
  }

  Future<void> _handOff() async {
    setState(() => _handedOff = true);
    final bool ok = await ref
        .read(mediaPlaybackProvider)
        .openExternally(widget.info.eventId, widget.bytes);
    if (!mounted) return;
    setState(() {
      _handedOff = false;
      _error = ok ? null : 'Nothing on this device can play this video.';
    });
  }
}

class _Failed extends StatelessWidget {
  const _Failed({required this.message, required this.onRetry});

  final String message;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Text(
              '⚠ $message',
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.white),
            ),
            if (onRetry != null) ...<Widget>[
              const SizedBox(height: 12),
              TextButton(
                key: const Key('media-viewer-retry'),
                onPressed: onRetry,
                child: const Text('Try again'),
              ),
            ],
          ],
        ),
      );
}
