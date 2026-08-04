/// Pure chat-thread rules — day grouping and auto-scroll — ported from
/// `android/app/src/main/java/mullu/comrade/ui/ChatThread.kt`.
///
/// Kept free of Flutter imports so plain Dart unit tests can pin them, exactly
/// as `ChatThreadTest.kt` does on Android.
library;

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
  final DateTime raw =
      DateTime.fromMillisecondsSinceEpoch(epochSecs * 1000, isUtc: true);
  final DateTime t =
      offset != Duration.zero ? raw.add(offset) : (utc ? raw : raw.toLocal());
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

/// Where to open a thread: the index of the first message the user has not
/// seen, or `null` for "open at the newest message".
///
/// Telegram's rule. [lastReadAt] is the `createdAt` of the newest message they
/// had already seen (what `markConversationRead` hands back). An item counts as
/// unread only if it is *newer* than that **and** came from the peer — your own
/// messages are read by definition, and a thread whose only new items are
/// things you sent from another device must not claim unread mail.
///
/// `null` for a first visit ([lastReadAt] of 0) on purpose: with no record of
/// where someone left off there is no honest "where you left off", and dropping
/// them at the top of a long history would be worse than the newest message.
///
/// [createdAt] must be the same time-ordered merge the thread renders, so the
/// returned index addresses the list the caller is about to scroll.
int? firstUnreadIndex({
  required List<int> createdAt,
  required List<bool> outgoing,
  required int lastReadAt,
}) {
  if (lastReadAt <= 0) return null;
  for (int i = 0; i < createdAt.length; i++) {
    final bool isOutgoing = i < outgoing.length && outgoing[i];
    if (createdAt[i] > lastReadAt && !isOutgoing) return i;
  }
  return null;
}

/// Whether an "unread messages" divider belongs above the item at [index] —
/// true only for the boundary itself, so the caller can render it inline while
/// walking the list.
bool startsUnread(int index, int? firstUnread) =>
    firstUnread != null && index == firstUnread;

/// Where the message with event id [targetId] sits in the rendered thread, or
/// `null` if it is not in it.
///
/// This is what tapping a reply's quote scrolls to. `null` is the common case,
/// not an edge case: a quote renders from whatever history is cached, and
/// replying to something older than the loaded window leaves a perfectly good
/// quote pointing at an item that is not on screen to scroll to. The caller must
/// treat that as "no destination" and leave the thread where it is — scrolling
/// somewhere arbitrary would be worse than not moving.
///
/// [eventIds] must be the same time-ordered merge the thread renders, so the
/// returned index addresses the list the caller is about to scroll.
int? indexOfEventId(List<String> eventIds, String? targetId) {
  if (targetId == null || targetId.isEmpty) return null;
  final int index = eventIds.indexOf(targetId);
  return index >= 0 ? index : null;
}

/// How long the jumped-to message stays highlighted.
///
/// A scroll on its own does not say *which* message it landed on, and a reply
/// usually quotes something surrounded by other messages — so the flash is what
/// makes the jump legible rather than disorienting. Long enough to catch the eye
/// after the scroll animation settles, short enough not to read as selection.
const Duration quoteHighlightDuration = Duration(milliseconds: 1400);

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
