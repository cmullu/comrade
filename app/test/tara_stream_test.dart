/// Port of `android/app/src/test/java/mullu/comrade/ui/TaraStreamTest.kt`.
///
/// The load-bearing property is losslessness: whatever the chunk size, the
/// pieces reassemble into the exact reply the engine produced. A streaming
/// animation that silently dropped or duplicated a character would corrupt
/// what the user is told — including a crisis hand-off's helpline numbers.
library;

import 'package:comrade/src/util/tara_stream.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const String reply = "That sounds anxious, and it makes sense you'd feel "
      'that way. What do you think is underneath that?';

  group('chunkText', () {
    test('chunks reassemble into exactly the original text', () {
      for (var size = 1; size <= 24; size++) {
        expect(
          chunkText(reply, targetChars: size).join(),
          reply,
          reason: 'lossless at chunk size $size',
        );
      }
    });

    test('newlines and unicode survive chunking', () {
      const String text =
          'Line one\nLine two — with an em dash, ellipsis…\n\nAnd 🙂 an emoji.';
      expect(chunkText(text, targetChars: 4).join(), text);
    });

    test('an emoji is never split across chunks', () {
      // The Kotlin original iterated UTF-16 chars; Dart iterates runes so a
      // surrogate pair cannot be cut in half. Every chunk must still be a
      // valid string on its own.
      const String text = '🙂 🦊 🐳 🐝 a b c d e f';
      for (final String piece in chunkText(text, targetChars: 2)) {
        expect(piece.runes.length, greaterThan(0));
        expect(String.fromCharCodes(piece.runes), piece);
      }
    });

    test('every chunk but the last ends on whitespace, so words never split',
        () {
      final List<String> chunks = chunkText(reply);
      expect(chunks.length, greaterThan(1),
          reason: 'a sentence this long must stream in pieces');
      for (final String chunk in chunks.take(chunks.length - 1)) {
        expect(chunk.trimRight().length, lessThan(chunk.length),
            reason: "chunk must end at a word boundary: '$chunk'");
        expect(chunk.length, greaterThanOrEqualTo(kTaraDefaultChunkChars),
            reason: "chunk must reach the target length: '$chunk'");
      }
    });

    test('text with no whitespace stays in one piece', () {
      expect(chunkText('supercalifragilistic', targetChars: 4),
          <String>['supercalifragilistic']);
    });

    test('empty text produces no chunks', () {
      expect(chunkText(''), isEmpty);
    });

    test('a non-positive chunk size is rejected rather than looping forever',
        () {
      expect(() => chunkText(reply, targetChars: 0), throwsArgumentError);
      expect(() => chunkText(reply, targetChars: -3), throwsArgumentError);
    });
  });

  group('streamTaraReply', () {
    test('emits growing prefixes and ends with the whole reply', () async {
      final List<String> frames =
          await streamTaraReply(reply, perChunkDelay: Duration.zero).toList();

      expect(frames.length, chunkText(reply).length,
          reason: 'one emission per chunk');
      expect(frames.last, reply,
          reason: 'the last frame is the complete reply');
      for (var i = 1; i < frames.length; i++) {
        expect(frames[i].startsWith(frames[i - 1]), isTrue,
            reason: 'each frame must extend the previous one');
        expect(frames[i].length, greaterThan(frames[i - 1].length),
            reason: 'each frame must actually grow');
      }
      // Every intermediate frame is a genuine prefix of the final text, so
      // nothing ever renders that the engine did not produce.
      for (final String frame in frames) {
        expect(reply.startsWith(frame), isTrue);
      }
    });

    test('an empty reply streams nothing', () async {
      expect(
        await streamTaraReply('', perChunkDelay: Duration.zero).toList(),
        isEmpty,
      );
    });
  });
}
