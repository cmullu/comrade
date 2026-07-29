/// Port of `android/app/src/test/java/mullu/comrade/ui/DisplayNameTest.kt`.
///
/// Pins the chat-title precedence: user alias → published @username → key.
/// The alias is the only name the user chose themself; the username is a
/// self-declared claim by the peer; the key is the identity.
library;

import 'package:comrade/src/util/display_name.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const String key =
      'npub1w4laefqx0av9y8gm7vk2xspwjnvyxydr0hjfpnr4x9dvw2l3jd2qtqy3gq';

  group('peerTitle precedence', () {
    test('alias outranks the published username', () {
      expect(peerTitle(key, 'Mom', 'charlie'), 'Mom');
    });

    test('published username outranks the key', () {
      expect(peerTitle(key, null, 'charlie'), '@charlie');
      expect(peerTitle(key, '   ', 'charlie'), '@charlie');
      // A handle already carrying '@' is not doubled.
      expect(peerTitle(key, null, '@charlie'), '@charlie');
    });

    test('the key is the fallback', () {
      expect(peerTitle(key, null, null), shortNpub(key));
      expect(peerTitle(key, '', ' '), shortNpub(key));
    });

    test('a blank alias does not shadow a real username', () {
      // Regression guard for the ordering: an empty-but-present alias is not
      // a name, it is the absence of one.
      expect(peerTitle(key, '', 'charlie'), '@charlie');
    });
  });

  group('shortNpub', () {
    test('keeps head and tail', () {
      expect(shortNpub(key), 'npub1w4lae…y3gq');
      expect(shortNpub('short'), 'short');
    });

    test('leaves anything 16 chars or shorter alone', () {
      expect(shortNpub('npub1abcdefghij'), 'npub1abcdefghij');
    });
  });

  group('avatar colour', () {
    test('is stable and in bounds', () {
      final int first = avatarColorIndex(key, 8);
      expect(avatarColorIndex(key, 8), first, reason: 'same key → same colour');
      expect(first, inInclusiveRange(0, 7));
    });

    test('a degenerate palette is safe', () {
      expect(avatarColorIndex(key, 0), 0);
    });

    test('different keys generally land on different hues', () {
      // Not a guarantee (8 buckets), but these two specific keys must differ
      // or the hash is doing nothing.
      expect(
        avatarColorIndex('npub1aaa', 8) == avatarColorIndex('npub1zzz', 8),
        isFalse,
      );
    });
  });

  group('formatCallDuration', () {
    test('renders minutes and seconds', () {
      expect(formatCallDuration(0), '0:00');
      expect(formatCallDuration(9), '0:09');
      expect(formatCallDuration(60), '1:00');
      expect(formatCallDuration(65), '1:05');
      expect(formatCallDuration(3660), '61:00');
    });

    test('a negative duration does not render negative', () {
      expect(formatCallDuration(-5), '0:00');
    });
  });

  group('formatCallClock (the live in-call timer)', () {
    test('adds an hours field only past an hour', () {
      expect(formatCallClock(65), '1:05');
      expect(formatCallClock(3661), '1:01:01');
    });
  });

  group('dayLabel', () {
    // 2025-07-12T07:00:00Z
    const int now = 1752303600;

    test('groups entries humanly', () {
      expect(dayLabel(now - 3600, now, utc: true), 'Today');
      expect(dayLabel(now - 86400, now, utc: true), 'Yesterday');
      expect(dayLabel(now - 2 * 86400, now, utc: true), '10 Jul 2025');
    });

    test('the boundary follows the zone, not UTC', () {
      // 23:30 UTC is already "tomorrow" at +10:00.
      const int lateEvening = 1752363000; // 2025-07-12T22:10:00Z
      expect(
        dayLabel(lateEvening, lateEvening, offset: const Duration(hours: 10)),
        'Today',
      );
      expect(
        dayLabel(
          lateEvening - 86400,
          lateEvening,
          offset: const Duration(hours: 10),
        ),
        'Yesterday',
      );
    });
  });

  group('relativeTime', () {
    final DateTime now = DateTime.utc(2026, 7, 29, 12);
    final int nowSecs = now.millisecondsSinceEpoch ~/ 1000;

    test('buckets by magnitude', () {
      expect(relativeTime(nowSecs, now: now), 'now');
      expect(relativeTime(nowSecs - 59, now: now), 'now');
      expect(relativeTime(nowSecs - 60, now: now), '1m');
      expect(relativeTime(nowSecs - 3600, now: now), '1h');
      expect(relativeTime(nowSecs - 3 * 86400, now: now), '3d');
    });
  });
}
