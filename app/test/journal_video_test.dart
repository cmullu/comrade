import 'package:comrade/src/util/journal_video.dart';
import 'package:flutter_test/flutter_test.dart';

/// Mirrors the presentation half of
/// `android/.../journal/JournalVideosTest.kt` — same cases, same numbers.
///
/// The naming and sweep rules that file also pins are Android's alone: this
/// frontend shows a video entry, it does not record one (`docs/JOURNAL.md`).
void main() {
  group('titles', () {
    test('a title is trimmed and blank means none', () {
      expect(journalVideoTitle('  Sunday morning  '), 'Sunday morning');
      expect(journalVideoTitle(null), isNull);
      expect(journalVideoTitle(''), isNull);
      expect(journalVideoTitle('   \n\t '), isNull);
    });

    test('a pasted paragraph becomes one line and stops at the cap', () {
      expect(
        journalVideoTitle('the walk\nafter   the\r\nargument'),
        'the walk after the argument',
      );
      final String title =
          journalVideoTitle('x' * (journalTitleMaxChars + 40))!;
      expect(title.length, journalTitleMaxChars);
      expect(title.endsWith('…'), isFalse);
    });

    test('an unnamed recording is headed with the day it was taken', () {
      expect(journalVideoHeading(null, 'Yesterday'), 'Yesterday');
      expect(journalVideoHeading('   ', 'Yesterday'), 'Yesterday');
      expect(
          journalVideoHeading('Sunday morning', 'Yesterday'), 'Sunday morning');
    });
  });

  group('length and size', () {
    test('clip length reads as a player would show it', () {
      expect(formatClipLength(7400), '0:07');
      expect(formatClipLength(47000), '0:47');
      expect(formatClipLength(63000), '1:03');
      expect(formatClipLength(600000), '10:00');
      expect(formatClipLength(3600000), '1:00:00');
      expect(formatClipLength(7509000), '2:05:09');
    });

    test('an unknown length draws nothing rather than claiming an empty clip',
        () {
      expect(formatClipLength(0), '');
      expect(formatClipLength(-1), '');
    });

    test('size reads the way a file manager on the same phone would', () {
      expect(formatClipSize(512), '512 B');
      expect(formatClipSize(860160), '840 KB');
      expect(formatClipSize(12897485), '12.3 MB');
      expect(formatClipSize(12582912), '12 MB');
      expect(formatClipSize(1181116006), '1.1 GB');
      expect(formatClipSize(0), '');
    });
  });
}
