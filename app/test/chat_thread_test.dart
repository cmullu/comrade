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

  // Mirrors the "Opening position" block in `ChatThreadTest.kt`.
  group('opening position (Telegram first-unread rule)', () {
    test('opens at the first message newer than the read position', () {
      expect(
        firstUnreadIndex(
          createdAt: <int>[100, 200, 300, 400],
          outgoing: <bool>[false, false, false, false],
          lastReadAt: 200,
        ),
        2,
      );
    });

    test('a first visit opens at the newest message', () {
      // No stored position: there is no honest "where you left off", and the
      // top of a long history is a worse guess than the bottom.
      expect(
        firstUnreadIndex(
          createdAt: <int>[100, 200],
          outgoing: <bool>[false, false],
          lastReadAt: 0,
        ),
        isNull,
      );
    });

    test('a fully read thread opens at the newest message', () {
      expect(
        firstUnreadIndex(
          createdAt: <int>[100, 200],
          outgoing: <bool>[false, false],
          lastReadAt: 200,
        ),
        isNull,
      );
      // A watermark past everything (clock skew between two devices) is still
      // "nothing unread", not a bogus index.
      expect(
        firstUnreadIndex(
          createdAt: <int>[100, 200],
          outgoing: <bool>[false, false],
          lastReadAt: 9999,
        ),
        isNull,
      );
    });

    test('your own messages are never unread', () {
      // Sending from another device must not make the thread claim unread mail.
      expect(
        firstUnreadIndex(
          createdAt: <int>[100, 200, 300],
          outgoing: <bool>[false, true, true],
          lastReadAt: 100,
        ),
        isNull,
      );
    });

    test('the divider lands on the first incoming message past your own', () {
      expect(
        firstUnreadIndex(
          createdAt: <int>[100, 200, 300, 400],
          outgoing: <bool>[false, true, false, false],
          lastReadAt: 100,
        ),
        2,
      );
    });

    test('an empty thread has nothing to open at', () {
      expect(
        firstUnreadIndex(
          createdAt: <int>[],
          outgoing: <bool>[],
          lastReadAt: 100,
        ),
        isNull,
      );
    });

    test('the divider renders only at the boundary', () {
      expect(startsUnread(2, 2), isTrue);
      expect(startsUnread(1, 2), isFalse);
      expect(startsUnread(3, 2), isFalse);
      expect(startsUnread(0, null), isFalse);
    });
  });

  // Mirrors `ChatThreadTest.kt`'s "tapping a quote" block, case for case.
  group('tapping a quote to reach the original', () {
    test('finds the message it points at', () {
      const List<String> ids = <String>['a', 'b', 'c'];
      expect(indexOfEventId(ids, 'a'), 0);
      expect(indexOfEventId(ids, 'c'), 2);
    });

    // The case that decides the feature's behaviour: a reply to something older
    // than the loaded window quotes fine but has nowhere to scroll to. The
    // caller must leave the thread where it is rather than land somewhere
    // arbitrary.
    test('an original outside the loaded thread has no destination', () {
      expect(indexOfEventId(<String>['a', 'b'], 'older'), isNull);
      expect(indexOfEventId(<String>[], 'a'), isNull);
    });

    test('a message that is not a reply has nothing to go to', () {
      expect(indexOfEventId(<String>['a', 'b'], null), isNull);
      // A blank id is the bridge's "absent", and must not match a blank entry.
      expect(indexOfEventId(<String>['a', ''], ''), isNull);
    });

    test('the first match wins', () {
      // Duplicate ids should not happen — the thread is deduped upstream — but
      // if one ever slips through, the earlier item is the one quoted.
      expect(indexOfEventId(<String>['a', 'dup', 'dup'], 'dup'), 1);
    });
  });
}
