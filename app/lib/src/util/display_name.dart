/// Pure display-name helpers, ported 1:1 from
/// `android/app/src/main/java/mullu/comrade/ui/DisplayName.kt`.
///
/// Deliberately free of Flutter imports so plain Dart unit tests can pin the
/// precedence rules exactly as `DisplayNameTest.kt` does on Android.
library;

/// Short display form of an npub: `npub1abcd…wxyz`.
///
/// Matches Android's rule (head 10 + ellipsis + tail 4, only above 16 chars).
/// The desktop SPA used a different cut (11/5, above 18) — see
/// `SCREEN_INVENTORY.md`; Android's is the one the unified app keeps because
/// its unit test pins the exact output and the conversation header renders it
/// beside a 36 dp avatar where four tail characters still fit on a phone.
String shortNpub(String npub) =>
    npub.length > 16 ? '${npub.substring(0, 10)}…${npub.substring(npub.length - 4)}' : npub;

/// Display title for a peer, in trust order:
///  1. the alias *you* chose for the contact (yours, can't be spoofed),
///  2. the `@handle` *they* published — a self-declared claim, so screens
///     showing it keep the key visible alongside,
///  3. the shortened key.
String peerTitle(String peer, String? alias, String? username) {
  final String? trimmedAlias = alias?.trim();
  if (trimmedAlias != null && trimmedAlias.isNotEmpty) return alias!;
  final String? trimmedName = username?.trim();
  if (trimmedName != null && trimmedName.isNotEmpty) {
    // A handle already carrying '@' is not doubled.
    return '@${username!.startsWith('@') ? username.substring(1) : username}';
  }
  return shortNpub(peer);
}

/// Deterministic palette index for a peer avatar: the same key always gets
/// the same colour, on every device — identity-stable like the key itself.
int avatarColorIndex(String seed, int paletteSize) {
  if (paletteSize <= 0) return 0;
  var hash = 0;
  for (final int unit in seed.codeUnits) {
    hash = (hash * 31 + unit) & 0x7FFFFFFF;
  }
  return hash % paletteSize;
}

/// Rough relative timestamp for list rows ("now", "5m", "3h", "2d").
///
/// [now] is injectable so the rule is unit-testable; it defaults to the wall
/// clock exactly like the Kotlin original.
String relativeTime(int epochSecs, {DateTime? now}) {
  final int nowSecs = (now ?? DateTime.now()).millisecondsSinceEpoch ~/ 1000;
  final int d = nowSecs - epochSecs;
  if (d < 60) return 'now';
  if (d < 3600) return '${d ~/ 60}m';
  if (d < 86400) return '${d ~/ 3600}h';
  return '${d ~/ 86400}d';
}

/// Wall-clock send time for a message bubble: "14:05".
///
/// Bubbles sit under a day separator, so the clock alone is unambiguous —
/// unlike a relative "3d" that drifts while the screen is open. [utc] is
/// injectable (in place of Kotlin's `ZoneId`) so the rule is unit-testable:
/// Dart only offers "local" and "UTC", and the tests pin the UTC rendering.
String clockTime(int epochSecs, {bool utc = false, Duration offset = Duration.zero}) {
  final DateTime t =
      DateTime.fromMillisecondsSinceEpoch(epochSecs * 1000, isUtc: true).add(offset);
  final DateTime local = utc || offset != Duration.zero ? t : t.toLocal();
  return '${local.hour.toString().padLeft(2, '0')}:${local.minute.toString().padLeft(2, '0')}';
}

/// `m:ss` duration for a connected call (e.g. a call-history row).
String formatCallDuration(int totalSecs) {
  final int secs = totalSecs < 0 ? 0 : totalSecs;
  return '${secs ~/ 60}:${(secs % 60).toString().padLeft(2, '0')}';
}

/// `m:ss` or `h:mm:ss` for the live in-call timer (CallScreen.durationLabel).
String formatCallClock(int totalSecs) {
  final int secs = totalSecs < 0 ? 0 : totalSecs;
  final int hours = secs ~/ 3600;
  if (hours > 0) {
    return '$hours:${((secs % 3600) ~/ 60).toString().padLeft(2, '0')}'
        ':${(secs % 60).toString().padLeft(2, '0')}';
  }
  return '${secs ~/ 60}:${(secs % 60).toString().padLeft(2, '0')}';
}

const List<String> _months = <String>[
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
];

/// Human day header for chat/journal grouping: "Today", "Yesterday", else
/// "12 Jul 2026". [utc]/[offset] are injectable so the rule is unit-testable.
String dayLabel(
  int epochSecs,
  int nowEpochSecs, {
  bool utc = false,
  Duration offset = Duration.zero,
}) {
  final DateTime day = _zoned(epochSecs, utc: utc, offset: offset);
  final DateTime today = _zoned(nowEpochSecs, utc: utc, offset: offset);
  if (_sameDay(day, today)) return 'Today';
  final DateTime yesterday = today.subtract(const Duration(days: 1));
  if (_sameDay(day, yesterday)) return 'Yesterday';
  return '${day.day} ${_months[day.month - 1]} ${day.year}';
}

DateTime _zoned(int epochSecs, {required bool utc, required Duration offset}) {
  final DateTime t = DateTime.fromMillisecondsSinceEpoch(epochSecs * 1000, isUtc: true);
  if (offset != Duration.zero) return t.add(offset);
  return utc ? t : t.toLocal();
}

bool _sameDay(DateTime a, DateTime b) =>
    a.year == b.year && a.month == b.month && a.day == b.day;
