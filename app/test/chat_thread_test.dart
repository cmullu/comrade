/// Port of `android/app/src/test/java/mullu/comrade/ui/ChatThreadTest.kt`.
///
/// Pins the chat-thread UX rules: when a day separator is due, when fresh
/// messages may auto-scroll the reader, and how bubble times render.
library;

import 'package:comrade/src/util/chat_thread.dart';
import 'package:comrade/src/util/display_name.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const int noon = 1752321600; // 2025-07-12T12:00:00Z

  group('day separators', () {
    test('the first message always opens a day', () {
      expect(startsNewDay(null, noon, utc: true), isTrue);
    });

    test('the same day does not repeat the header', () {
      expect(startsNewDay(noon, noon + 3600, utc: true), isFalse);
    });

    test('crossing midnight opens a new day', () {
      // 23:30 → 00:30 the next day: only a one-hour gap, but a new date.
      const int lateEvening = noon + 11 * 3600 + 1800;
      expect(startsNewDay(lateEvening, lateEvening + 3600, utc: true), isTrue);
    });

    test('the boundary follows the zone, not UTC', () {
      // 23:30 UTC and 00:30 UTC straddle midnight in UTC, but both are
      // afternoon of the same day at +10:00.
      const int lateEvening = noon + 11 * 3600 + 1800;
      expect(
        startsNewDay(
          lateEvening,
          lateEvening + 3600,
          offset: const Duration(hours: 10),
        ),
        isFalse,
      );
    });
  });

  group('auto-scroll on new messages (index rule)', () {
    test('a reader at the newest message auto-scrolls', () {
      expect(isNearBottom(lastVisibleIndex: 9, totalCount: 10), isTrue);
    });

    test('a reader within slack of the bottom auto-scrolls', () {
      expect(
        isNearBottom(lastVisibleIndex: 7, totalCount: 10, slack: 2),
        isTrue,
      );
    });

    test('a reader scrolled up in history is NOT yanked', () {
      expect(
        isNearBottom(lastVisibleIndex: 6, totalCount: 10, slack: 2),
        isFalse,
      );
      expect(isNearBottom(lastVisibleIndex: 0, totalCount: 100), isFalse);
    });

    test('an empty or unmeasured list counts as bottom', () {
      expect(isNearBottom(lastVisibleIndex: -1, totalCount: 0), isTrue);
    });
  });

  group('auto-scroll (pixel rule the Flutter list actually uses)', () {
    test('at the very bottom counts as near', () {
      expect(
        isNearBottomByOffset(pixels: 1000, maxScrollExtent: 1000),
        isTrue,
      );
    });

    test('within the slack window counts as near', () {
      expect(
        isNearBottomByOffset(
          pixels: 900,
          maxScrollExtent: 1000,
          slackPixels: 220,
        ),
        isTrue,
      );
    });

    test('scrolled up beyond the slack window does not', () {
      expect(
        isNearBottomByOffset(
          pixels: 400,
          maxScrollExtent: 1000,
          slackPixels: 220,
        ),
        isFalse,
      );
    });

    test('a list with nothing to scroll counts as bottom', () {
      expect(isNearBottomByOffset(pixels: 0, maxScrollExtent: 0), isTrue);
    });
  });

  group('bubble timestamps', () {
    test('clockTime renders a zero-padded 24-hour wall clock', () {
      expect(clockTime(noon, utc: true), '12:00');
      expect(clockTime(noon + 300, utc: true), '12:05');
      expect(clockTime(noon, offset: const Duration(hours: 10)), '22:00');
      // 00:xx just after midnight.
      expect(clockTime(noon + 12 * 3600 + 1800, utc: true), '00:30');
    });
  });
}
