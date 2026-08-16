import 'package:comrade/src/util/journal_recording.dart';
import 'package:flutter_test/flutter_test.dart';

/// Mirrors the presentation half of
/// `android/.../journal/JournalRecordingsTest.kt` — same cases, same numbers.
///
/// The naming and sweep rules that file also pins are Android's alone: this
/// frontend shows a recording entry, it does not record one
/// (`docs/JOURNAL.md`).
void main() {
  group('titles', () {
    test('a title is trimmed and blank means none', () {
      expect(journalRecordingTitle('  Sunday morning  '), 'Sunday morning');
      expect(journalRecordingTitle(null), isNull);
      expect(journalRecordingTitle(''), isNull);
      expect(journalRecordingTitle('   \n\t '), isNull);
    });

    test('a pasted paragraph becomes one line and stops at the cap', () {
      expect(
        journalRecordingTitle('the walk\nafter   the\r\nargument'),
        'the walk after the argument',
      );
      final String title =
          journalRecordingTitle('x' * (journalTitleMaxChars + 40))!;
      expect(title.length, journalTitleMaxChars);
      expect(title.endsWith('…'), isFalse);
    });

    test('an unnamed recording is headed with the day it was taken', () {
      expect(journalRecordingHeading(null, 'Yesterday'), 'Yesterday');
      expect(journalRecordingHeading('   ', 'Yesterday'), 'Yesterday');
      expect(journalRecordingHeading('Sunday morning', 'Yesterday'),
          'Sunday morning');
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

  group('playback', () {
    test('the counter under the player reads elapsed over total', () {
      expect(playbackLabel(0, 23000), '0:00 / 0:23');
      expect(playbackLabel(7200, 23000), '0:07 / 0:23');
      expect(playbackLabel(23000, 23000), '0:23 / 0:23');
    });

    test('the player never claims to be past the end', () {
      expect(playbackLabel(24000, 23000), '0:23 / 0:23');
      expect(playbackLabel(-500, 23000), '0:00 / 0:23');
    });

    test('an unknown total shows the elapsed time alone', () {
      expect(playbackLabel(7200, 0), '0:07');
      expect(playbackLabel(0, 0), '0:00');
      expect(playbackLabel(-1, -1), '0:00');
    });

    test('progress is a fraction and never out of range', () {
      expect(clipProgress(0, 23000), closeTo(0, 0.0001));
      expect(clipProgress(11500, 23000), closeTo(0.5, 0.0001));
      expect(clipProgress(23000, 23000), closeTo(1, 0.0001));
      expect(clipProgress(24000, 23000), closeTo(1, 0.0001));
      expect(clipProgress(-500, 23000), closeTo(0, 0.0001));
      expect(clipProgress(7000, 0), closeTo(0, 0.0001));
      expect(clipProgress(7000, -1), closeTo(0, 0.0001));
    });
  });
}
