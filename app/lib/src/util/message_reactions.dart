/// Emoji reactions and the swipe-to-reply gesture — the pure half.
///
/// Mirrors `android/app/src/main/java/mullu/comrade/ui/MessageReactions.kt`
/// exactly. Both sides carry the same numbers and the same ordering rule, and
/// both are pinned by tests, so a change made to one and not the other fails a
/// build instead of shipping two apps that behave differently on the same
/// gesture.
library;

/// The emoji offered on a long press, in order.
///
/// Six, because the row has to fit one line on a small phone without scrolling —
/// a reaction the user has to hunt for is slower than typing the word. Anything
/// else is a tap away in the full picker, which the same sheet opens.
const List<String> quickReactions = <String>[
  '👍',
  '❤️',
  '🔥',
  '😂',
  '😮',
  '🙏'
];

/// One emoji's worth of reaction on a message: which emoji, how many people, and
/// whether you are one of them.
class ReactionChip {
  const ReactionChip({
    required this.emoji,
    required this.count,
    required this.mine,
  });

  final String emoji;
  final int count;
  final bool mine;

  @override
  bool operator ==(Object other) =>
      other is ReactionChip &&
      other.emoji == emoji &&
      other.count == count &&
      other.mine == mine;

  @override
  int get hashCode => Object.hash(emoji, count, mine);

  @override
  String toString() => 'ReactionChip($emoji x$count${mine ? ' mine' : ''})';
}

/// The subset of a reaction the UI needs, so [summariseReactions] stays pure and
/// has no bridge type in its signature.
class ReactionRow {
  const ReactionRow({
    required this.targetId,
    required this.reactor,
    required this.emoji,
  });

  final String targetId;
  final String reactor;
  final String emoji;
}

/// Group a message's reactions into the chips drawn under its bubble.
///
/// Ordered by count descending, then by first appearance, so the row is stable
/// across reloads: sorting by emoji codepoint would reshuffle chips whenever a
/// tie broke differently, and a row that moves under your finger is a row you
/// mis-tap.
///
/// [selfReactor] is the id standing for this device — see [mineReactor]. It is the
/// marker for "you reacted", which is what makes tapping a chip a toggle rather
/// than adding a duplicate.
List<ReactionChip> summariseReactions(
  List<ReactionRow> reactions,
  String selfReactor,
) {
  if (reactions.isEmpty) return const <ReactionChip>[];
  // A LinkedHashMap by insertion order, so "first appearance" is recoverable as
  // the tie-break below.
  final Map<String, List<ReactionRow>> grouped = <String, List<ReactionRow>>{};
  for (final ReactionRow r in reactions) {
    if (r.emoji.isEmpty) continue;
    grouped.putIfAbsent(r.emoji, () => <ReactionRow>[]).add(r);
  }
  final List<({int order, ReactionChip chip})> chips =
      <({int order, ReactionChip chip})>[];
  int index = 0;
  grouped.forEach((String emoji, List<ReactionRow> rows) {
    chips.add((
      order: index++,
      chip: ReactionChip(
        emoji: emoji,
        // Distinct reactors, not rows: the store holds one row per person per
        // message, but a caller merging a live event into a loaded list can hand
        // us the same person twice for a moment.
        count: rows.map((ReactionRow r) => r.reactor).toSet().length,
        mine: rows.any((ReactionRow r) => r.reactor == selfReactor),
      ),
    ));
  });
  chips.sort(
      (({int order, ReactionChip chip}) a, ({int order, ReactionChip chip}) b) {
    final int byCount = b.chip.count.compareTo(a.chip.count);
    return byCount != 0 ? byCount : a.order.compareTo(b.order);
  });
  return chips.map((({int order, ReactionChip chip}) e) => e.chip).toList();
}

// ── Swipe to reply ───────────────────────────────────────────────────────────

/// How far a bubble must be dragged before letting go replies to it, in logical
/// pixels.
///
/// Far enough that a horizontal wobble during a vertical scroll does not open a
/// reply the user did not ask for; short enough to be one flick of a thumb.
const double replySwipeTriggerDp = 56;

/// How far a bubble will actually move, however hard it is dragged. The bubble
/// stops well before the trigger distance becomes a drag across the screen — the
/// gesture is a nudge with a spring, not a pane that slides away.
const double replySwipeMaxDp = 72;

/// Where to draw a bubble mid-drag, given the raw accumulated drag.
///
/// Leftward drags return 0: reply is a one-directional gesture (Telegram's, and
/// the one that leaves a left-to-right swipe free for anything a future screen
/// wants). Rightward is clamped to [replySwipeMaxDp], so a long drag does not
/// push the bubble off screen.
double replySwipeOffsetDp(double dragDp) => dragDp.clamp(0, replySwipeMaxDp);

/// Whether releasing at [dragDp] should open a reply to this message.
bool replySwipeTriggered(double dragDp) => dragDp >= replySwipeTriggerDp;

/// The synthetic reactor id standing for "this device".
///
/// The core already marks each stored reaction with `outgoing`, so the UI maps
/// every outgoing row to this id and passes it as `mineReactor`. That means no
/// screen has to hold the local npub just to draw a highlight — and it cannot get
/// the comparison wrong, because there is nothing to compare.
///
/// Not a valid npub, deliberately: a real key could theoretically collide with it
/// and quietly mark a peer's reaction as your own. Matches `MINE_REACTOR` in
/// `MessageReactions.kt`.
const String mineReactor = ' self';
