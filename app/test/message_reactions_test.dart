import 'package:comrade/src/util/message_reactions.dart';
import 'package:flutter_test/flutter_test.dart';

/// Mirror of `MessageReactionsTest.kt`, case for case.
///
/// Both frontends implement the same two gestures, and the numbers behind them —
/// which emoji the quick row offers, how far a drag must go, how ties in the chip
/// order break — have to match, or one message behaves differently depending on
/// which app you opened. Keeping the suites parallel is what makes drift fail a
/// build instead of shipping.
void main() {
  const String me = 'npub1me';
  const String ana = 'npub1ana';
  const String bo = 'npub1bo';

  ReactionRow row(String reactor, String emoji, {String target = 'm1'}) =>
      ReactionRow(targetId: target, reactor: reactor, emoji: emoji);

  group('reaction chips', () {
    test('the quick row is six emoji and matches Android', () {
      expect(quickReactions.length, 6);
      expect(quickReactions, <String>['👍', '❤️', '🔥', '😂', '😮', '🙏']);
      expect(quickReactions.toSet().length, quickReactions.length,
          reason: 'no duplicates');
    });

    test('nothing in, nothing out', () {
      expect(summariseReactions(const <ReactionRow>[], me), isEmpty);
    });

    test('the same emoji from several people is one chip with a count', () {
      final List<ReactionChip> chips =
          summariseReactions(<ReactionRow>[row(ana, '🔥'), row(bo, '🔥')], me);
      expect(chips, <ReactionChip>[
        const ReactionChip(emoji: '🔥', count: 2, mine: false),
      ]);
    });

    test('your own reaction is marked so tapping the chip can take it back',
        () {
      final List<ReactionChip> chips =
          summariseReactions(<ReactionRow>[row(ana, '🔥'), row(me, '🔥')], me);
      expect(
          chips.single, const ReactionChip(emoji: '🔥', count: 2, mine: true));
      expect(summariseReactions(<ReactionRow>[row(ana, '👍')], me).single.mine,
          isFalse);
    });

    test('chips order by count, then by first appearance', () {
      // The tie-break is what stops the row reshuffling between reloads. Two
      // emoji on one each must come back in the order they arrived, not in
      // codepoint order.
      final List<ReactionChip> chips = summariseReactions(
        <ReactionRow>[row(ana, '😂'), row(bo, '🔥'), row(me, '🔥')],
        me,
      );
      expect(chips.map((ReactionChip c) => c.emoji), <String>['🔥', '😂']);
      expect(chips.map((ReactionChip c) => c.count), <int>[2, 1]);

      final List<ReactionChip> tied =
          summariseReactions(<ReactionRow>[row(ana, '🙏'), row(bo, '😮')], me);
      expect(tied.map((ReactionChip c) => c.emoji), <String>['🙏', '😮'],
          reason: 'first seen wins a tie');
    });

    test('one person counted once even if handed to us twice', () {
      // Merging a live IncomingReaction into an already-loaded list can double a
      // row for a frame. A chip reading "2" when one person reacted would be a
      // lie the next reload silently corrects.
      final List<ReactionChip> chips =
          summariseReactions(<ReactionRow>[row(ana, '🔥'), row(ana, '🔥')], me);
      expect(chips.single.count, 1);
    });

    test('a withdrawn reaction draws no chip', () {
      // An empty emoji is the withdrawal on the wire; if it ever reaches the UI
      // list, it must not render as a blank chip.
      final List<ReactionChip> chips =
          summariseReactions(<ReactionRow>[row(ana, ''), row(bo, '🔥')], me);
      expect(chips.map((ReactionChip c) => c.emoji), <String>['🔥']);
    });
  });

  group('swipe to reply', () {
    test('a leftward drag moves nothing and replies to nothing', () {
      expect(replySwipeOffsetDp(-40), 0);
      expect(replySwipeTriggered(-100), isFalse);
      expect(replySwipeOffsetDp(0), 0);
    });

    test('the bubble follows the finger up to a cap', () {
      expect(replySwipeOffsetDp(20), 20);
      expect(replySwipeOffsetDp(replySwipeMaxDp), replySwipeMaxDp);
      expect(replySwipeOffsetDp(1000), replySwipeMaxDp,
          reason: 'a long drag must not push the bubble off screen');
    });

    test('a short wobble does not open a reply but a real flick does', () {
      // A vertical scroll carries some horizontal noise; the threshold is what
      // keeps that from opening a reply nobody asked for.
      expect(replySwipeTriggered(8), isFalse);
      expect(replySwipeTriggered(replySwipeTriggerDp - 0.5), isFalse);
      expect(replySwipeTriggered(replySwipeTriggerDp), isTrue);
      expect(replySwipeTriggered(200), isTrue);
    });

    test('the bubble can always be dragged as far as the trigger', () {
      // If the cap were below the trigger the gesture would be unreachable — the
      // bubble would stop moving before the drag ever counted.
      expect(replySwipeMaxDp, greaterThanOrEqualTo(replySwipeTriggerDp));
      expect(replySwipeTriggered(replySwipeOffsetDp(1000)), isTrue);
    });
  });
}
