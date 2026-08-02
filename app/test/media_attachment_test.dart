/// Receiving media: the bubble's per-type paths, the plaintext cache, and the
/// video fallback.
///
/// The load-bearing property here is that **decrypted bytes stay in memory and
/// are droppable**. Everything else is presentation.
library;

import 'dart:typed_data';

import 'package:comrade/src/data/comrade_repository.dart';
import 'package:comrade/src/data/fake_comrade_repository.dart';
import 'package:comrade/src/data/models.dart';
import 'package:comrade/src/widgets/media_attachment.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'helpers.dart';

/// Records what the bubble asked the platform to do.
class _RecordingPlayback implements MediaPlaybackDelegate {
  _RecordingPlayback({
    this.audioPlays = true,
    this.opens = true,
    this.player,
  });

  /// What `toggleAudio` answers — i.e. "is playing now".
  final bool audioPlays;
  final bool opens;
  final Widget? player;

  final List<String> toggled = <String>[];
  final List<String> opened = <String>[];
  int purges = 0;

  @override
  Future<bool> toggleAudio(String eventId, MediaBytes bytes) async {
    toggled.add(eventId);
    return audioPlays;
  }

  @override
  Future<bool> openExternally(String eventId, MediaBytes bytes) async {
    opened.add(eventId);
    return opens;
  }

  @override
  Widget? videoPlayer(String eventId, MediaBytes bytes) => player;

  @override
  Future<void> purge() async => purges++;
}

MediaMessageInfo _media(
  String mime, {
  String eventId = 'm1',
  String caption = '',
}) =>
    MediaMessageInfo(
      eventId: eventId,
      url: 'https://blob.example/x',
      mimeType: mime,
      caption: caption,
      sender: FakePeers.alice,
      createdAt: 1,
      size: 12,
      outgoing: false,
    );

/// A 64×64 PNG. Big enough to be a *tap target* once decoded, which a 1×1
/// fixture is not: a zero-or-one-pixel image leaves the bubble with nothing to
/// hit and every gesture lands on the empty row beside it.
final Uint8List _png = Uint8List.fromList(<int>[
  137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, //
  0, 0, 0, 64, 0, 0, 0, 64, 8, 2, 0, 0, 0, 37, 11, 230, //
  137, 0, 0, 0, 78, 73, 68, 65, 84, 120, 218, 237, 207, 65, 9, 0, //
  0, 8, 4, 176, 11, 118, 253, 49, 150, 17, 124, 11, 131, 21, 88, 166, //
  125, 45, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, //
  2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, //
  2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, //
  2, 151, 5, 55, 235, 1, 45, 10, 150, 103, 129, 0, 0, 0, 0, 73, //
  69, 78, 68, 174, 66, 96, 130, //
]);

/// Serves a decodable image instead of the counting repo's audio bytes.
class _ImageRepo extends FakeComradeRepository {
  _ImageRepo() : super(latency: Duration.zero, seed: false);

  int downloads = 0;

  @override
  Future<MediaBytes> downloadMedia(String eventId) async {
    downloads++;
    return MediaBytes(mimeType: 'image/png', bytes: _png);
  }
}

/// Pump a bubble whose image has to *decode*, not merely be handed over.
///
/// Image decoding runs on the real event loop, which a widget test's fake clock
/// never advances — so the first frames happen inside [WidgetTester.runAsync].
/// Skip this and the image renders at zero size, the bubble collapses, and
/// every tap in the test lands on empty row beside it while the production
/// widget is fine. Nothing about the widget under test changes; only the test's
/// clock does.
Future<void> pumpDecoded(
  WidgetTester tester,
  Widget widget, {
  required ComradeRepository repo,
  List<Override> extra = const <Override>[],
}) async {
  await tester.runAsync(() async {
    await tester.pumpWidget(harness(widget, repo: repo, extra: extra));
    await tester.pump();
    await Future<void>.delayed(const Duration(milliseconds: 120));
  });
  await tester.pumpAndSettle();
}

/// Serves fixed bytes and counts the downloads, so a test can tell a cache hit
/// from a re-fetch.
class _CountingRepo extends FakeComradeRepository {
  _CountingRepo() : super(latency: Duration.zero, seed: false);

  int downloads = 0;

  @override
  Future<MediaBytes> downloadMedia(String eventId) async {
    downloads++;
    return MediaBytes(
      mimeType: 'audio/aac',
      bytes: Uint8List.fromList(<int>[1, 2, 3, 4]),
    );
  }
}

void main() {
  group('the decrypted-media cache', () {
    test('is bounded and evicts the least recently used', () {
      final DecryptedMediaCache cache = DecryptedMediaCache(capacity: 2);
      MediaBytes bytes(int b) => MediaBytes(
            mimeType: 'image/png',
            bytes: Uint8List.fromList(<int>[b]),
          );

      cache
        ..put('a', bytes(1))
        ..put('b', bytes(2));
      // Touching 'a' makes 'b' the oldest.
      expect(cache.get('a'), isNotNull);
      cache.put('c', bytes(3));

      expect(cache.length, 2);
      expect(cache.get('b'), isNull, reason: 'least recently used evicted');
      expect(cache.get('a'), isNotNull);
      expect(cache.get('c'), isNotNull);
    });

    test('clear drops every plaintext it holds', () {
      final DecryptedMediaCache cache = DecryptedMediaCache();
      cache.put(
        'a',
        MediaBytes(mimeType: 'image/png', bytes: Uint8List(4)),
      );
      cache.clear();
      expect(cache.length, 0);
      expect(cache.get('a'), isNull);
    });
  });

  group('the bubble', () {
    testWidgets('audio plays through the platform and shows it',
        (WidgetTester tester) async {
      final _RecordingPlayback playback = _RecordingPlayback();
      final _CountingRepo repo = _CountingRepo();
      await tester.pumpWidget(harness(
        MediaAttachmentBubble(_media('audio/aac')),
        repo: repo,
        extra: <Override>[
          mediaPlaybackProvider.overrideWithValue(playback),
        ],
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Voice message'));
      await tester.pumpAndSettle();

      expect(playback.toggled, <String>['m1']);
      expect(find.text('Playing…'), findsOneWidget);
      expect(repo.downloads, 1);
    });

    testWidgets('a platform with no audio support says so',
        (WidgetTester tester) async {
      final _RecordingPlayback playback = _RecordingPlayback(audioPlays: false);
      await tester.pumpWidget(harness(
        MediaAttachmentBubble(_media('audio/aac')),
        repo: _CountingRepo(),
        extra: <Override>[
          mediaPlaybackProvider.overrideWithValue(playback),
        ],
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Voice message'));
      await tester.pumpAndSettle();

      expect(find.textContaining('not wired up'), findsOneWidget);
    });

    testWidgets('a video is not fetched until it is opened',
        (WidgetTester tester) async {
      // Bandwidth-conscious, still: the poster costs nothing, and the decrypt
      // happens when someone actually asks to watch.
      final _CountingRepo repo = _CountingRepo();
      await tester.pumpWidget(harness(
        MediaAttachmentBubble(_media('video/mp4')),
        repo: repo,
        extra: <Override>[
          mediaPlaybackProvider.overrideWithValue(_RecordingPlayback()),
        ],
      ));
      await tester.pumpAndSettle();

      expect(find.text('Tap to play video'), findsOneWidget);
      expect(repo.downloads, 0);
    });

    testWidgets('an unknown type offers to open it, and names the failure',
        (WidgetTester tester) async {
      final _RecordingPlayback playback = _RecordingPlayback(opens: false);
      await tester.pumpWidget(harness(
        MediaAttachmentBubble(_media('application/pdf')),
        repo: _CountingRepo(),
        extra: <Override>[
          mediaPlaybackProvider.overrideWithValue(playback),
        ],
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Open pdf'));
      await tester.pumpAndSettle();

      expect(find.textContaining('No app on this device'), findsOneWidget);
    });
  });

  group('replying to an attachment', () {
    testWidgets('a long press aims the composer at it',
        (WidgetTester tester) async {
      var replies = 0;
      await pumpDecoded(
        tester,
        MediaAttachmentBubble(_media('image/png'), onReply: () => replies++),
        repo: _ImageRepo(),
      );

      await tester.longPress(find.byKey(const Key('media-open-fullscreen')));
      await tester.pumpAndSettle();

      expect(replies, 1);
    });

    testWidgets('a bubble with no reply handler has no long-press to miss',
        (WidgetTester tester) async {
      // The "no fake affordances" rule: a caller that offers no reply must not
      // leave a gesture that swallows the press and does nothing.
      await pumpDecoded(
        tester,
        MediaAttachmentBubble(_media('image/png')),
        repo: _ImageRepo(),
      );

      final Iterable<GestureDetector> detectors =
          tester.widgetList<GestureDetector>(find.descendant(
        of: find.byType(MediaAttachmentBubble),
        matching: find.byType(GestureDetector),
      ));
      expect(
        detectors.where((GestureDetector g) => g.onLongPress != null),
        isEmpty,
      );
    });
  });

  group('full screen', () {
    testWidgets('tapping a photo opens the viewer, caption and all',
        (WidgetTester tester) async {
      await pumpDecoded(
        tester,
        MediaAttachmentBubble(_media('image/png', caption: 'the platform')),
        repo: _ImageRepo(),
      );

      await tester.tap(find.byKey(const Key('media-open-fullscreen')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('media-viewer-image')), findsOneWidget);
      expect(find.byKey(const Key('media-viewer-caption')), findsOneWidget);
      expect(find.text('Photo'), findsOneWidget);
    });

    testWidgets('the viewer costs no second fetch', (WidgetTester t) async {
      // The bubble already decrypted these bytes; the viewer reads the same
      // provider, so opening one is free and purging still drops one copy.
      final _ImageRepo repo = _ImageRepo();
      await pumpDecoded(
        t,
        MediaAttachmentBubble(_media('image/png')),
        repo: repo,
      );
      expect(repo.downloads, 1);

      await t.tap(find.byKey(const Key('media-open-fullscreen')));
      await t.pumpAndSettle();

      expect(repo.downloads, 1);
    });

    testWidgets('closing the viewer returns to the thread',
        (WidgetTester tester) async {
      await pumpDecoded(
        tester,
        MediaAttachmentBubble(_media('image/png')),
        repo: _ImageRepo(),
      );

      await tester.tap(find.byKey(const Key('media-open-fullscreen')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('media-viewer-close')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('media-viewer-bar')), findsNothing);
      expect(find.byType(MediaAttachmentBubble), findsOneWidget);
    });

    testWidgets('a video plays in-app full screen when the platform can',
        (WidgetTester tester) async {
      final _RecordingPlayback playback = _RecordingPlayback(
        player: const Text('inline player'),
      );
      await tester.pumpWidget(harness(
        MediaAttachmentBubble(_media('video/mp4')),
        repo: _CountingRepo(),
        extra: <Override>[mediaPlaybackProvider.overrideWithValue(playback)],
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('media-open-fullscreen')));
      await tester.pumpAndSettle();

      expect(find.text('inline player'), findsOneWidget);
      expect(playback.opened, isEmpty, reason: 'no need to leave the app');
    });

    testWidgets('a video with no in-app player is handed to the system one',
        (WidgetTester tester) async {
      // The regression this pins: before, a received video simply reported
      // "video playback is not wired up" forever, even though the bytes could
      // be handed to a player that exists.
      final _RecordingPlayback playback = _RecordingPlayback();
      await tester.pumpWidget(harness(
        MediaAttachmentBubble(_media('video/mp4')),
        repo: _CountingRepo(),
        extra: <Override>[mediaPlaybackProvider.overrideWithValue(playback)],
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('media-open-fullscreen')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('media-viewer-play-externally')));
      await tester.pumpAndSettle();

      expect(playback.opened, <String>['m1']);
      expect(find.textContaining('Nothing on this device'), findsNothing);
    });

    testWidgets('a video with nothing at all to play it says so',
        (WidgetTester tester) async {
      final _RecordingPlayback playback = _RecordingPlayback(opens: false);
      await tester.pumpWidget(harness(
        MediaAttachmentBubble(_media('video/mp4')),
        repo: _CountingRepo(),
        extra: <Override>[mediaPlaybackProvider.overrideWithValue(playback)],
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('media-open-fullscreen')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('media-viewer-play-externally')));
      await tester.pumpAndSettle();

      expect(find.textContaining('Nothing on this device'), findsOneWidget);
    });

    testWidgets('a voice note has no full screen to open',
        (WidgetTester tester) async {
      await tester.pumpWidget(harness(
        MediaAttachmentBubble(_media('audio/aac')),
        repo: _CountingRepo(),
        extra: <Override>[
          mediaPlaybackProvider.overrideWithValue(_RecordingPlayback()),
        ],
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('media-open-fullscreen')), findsNothing);
    });
  });
}
