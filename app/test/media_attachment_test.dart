/// Receiving media: the bubble's per-type paths, the plaintext cache, and the
/// video fallback.
///
/// The load-bearing property here is that **decrypted bytes stay in memory and
/// are droppable**. Everything else is presentation.
library;

import 'dart:typed_data';

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

MediaMessageInfo _media(String mime, {String eventId = 'm1'}) =>
    MediaMessageInfo(
      eventId: eventId,
      url: 'https://blob.example/x',
      mimeType: mime,
      caption: '',
      sender: FakePeers.alice,
      createdAt: 1,
      size: 12,
      outgoing: false,
    );

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

    testWidgets('video with no in-app player is handed to the system player',
        (WidgetTester tester) async {
      // The regression this pins: before, a received video simply reported
      // "video playback is not wired up" forever, even though the bytes could
      // be handed to a player that exists.
      final _RecordingPlayback playback = _RecordingPlayback();
      await tester.pumpWidget(harness(
        MediaAttachmentBubble(_media('video/mp4')),
        repo: _CountingRepo(),
        extra: <Override>[
          mediaPlaybackProvider.overrideWithValue(playback),
        ],
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Tap to load video'));
      await tester.pumpAndSettle();

      expect(playback.opened, <String>['m1']);
      expect(find.textContaining('Nothing on this device'), findsNothing);
    });

    testWidgets('video with nothing at all to play it reports that',
        (WidgetTester tester) async {
      final _RecordingPlayback playback = _RecordingPlayback(opens: false);
      await tester.pumpWidget(harness(
        MediaAttachmentBubble(_media('video/mp4')),
        repo: _CountingRepo(),
        extra: <Override>[
          mediaPlaybackProvider.overrideWithValue(playback),
        ],
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Tap to load video'));
      await tester.pumpAndSettle();

      expect(find.textContaining('Nothing on this device'), findsOneWidget);
    });

    testWidgets('an in-app player is preferred when one exists',
        (WidgetTester tester) async {
      final _RecordingPlayback playback = _RecordingPlayback(
        player: const Text('inline player'),
      );
      await tester.pumpWidget(harness(
        MediaAttachmentBubble(_media('video/mp4')),
        repo: _CountingRepo(),
        extra: <Override>[
          mediaPlaybackProvider.overrideWithValue(playback),
        ],
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Tap to load video'));
      await tester.pumpAndSettle();

      expect(find.text('inline player'), findsOneWidget);
      expect(playback.opened, isEmpty, reason: 'no need to leave the app');
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
}
