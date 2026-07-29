/// Pure chat-thread rules — day grouping and auto-scroll — ported from
/// `android/app/src/main/java/mullu/comrade/ui/ChatThread.kt`.
///
/// Kept free of Flutter imports so plain Dart unit tests can pin them, exactly
/// as `ChatThreadTest.kt` does on Android.
library;

import 'display_name.dart' show dayLabel;

export 'display_name.dart' show dayLabel;

/// Whether a message at [epochSecs] opens a new calendar day relative to the
/// one before it at [prevEpochSecs] — i.e. whether the thread should render a
/// day separator above it. The first message of a thread (null prev) always
/// does.
bool startsNewDay(
  int? prevEpochSecs,
  int epochSecs, {
  bool utc = false,
  Duration offset = Duration.zero,
}) {
  if (prevEpochSecs == null) return true;
  return _dayKey(epochSecs, utc: utc, offset: offset) !=
      _dayKey(prevEpochSecs, utc: utc, offset: offset);
}

int _dayKey(int epochSecs, {required bool utc, required Duration offset}) {
  final DateTime raw = DateTime.fromMillisecondsSinceEpoch(epochSecs * 1000, isUtc: true);
  final DateTime t = offset != Duration.zero ? raw.add(offset) : (utc ? raw : raw.toLocal());
  return t.year * 10000 + t.month * 100 + t.day;
}

/// Whether the reader is close enough to the newest message that fresh
/// arrivals should auto-scroll into view.
///
/// Someone scrolled up reading history (further than [slack] items from the
/// end) must NOT be yanked down — they get a "new messages" affordance
/// instead. An empty or not-yet-laid-out list counts as at the bottom.
///
/// This is the index-based contract Compose's `LazyListState` exposes and the
/// Kotlin test pins. The Flutter conversation view drives it from a
/// [ScrollController] via [isNearBottomByOffset]; both live here so the rule
/// has one home.
bool isNearBottom({
  required int lastVisibleIndex,
  required int totalCount,
  int slack = 2,
}) =>
    totalCount <= 0 || lastVisibleIndex >= totalCount - 1 - slack;

/// Pixel-space equivalent of [isNearBottom] for Flutter's scrollable, which
/// reports offsets rather than item indices.
///
/// [slackPixels] stands in for the ~2 items of slack the index rule allows;
/// a list that has not been laid out yet (no extent) counts as at the bottom,
/// matching `totalCount <= 0` above.
bool isNearBottomByOffset({
  required double pixels,
  required double maxScrollExtent,
  double slackPixels = 220,
}) {
  if (maxScrollExtent <= 0) return true;
  return pixels >= maxScrollExtent - slackPixels;
}
