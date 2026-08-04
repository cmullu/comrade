/// Delivery ticks and day separators — the two details a reader uses to know
/// *when* something happened and *whether it arrived*.
library;

import 'package:comrade/src/data/models.dart';
import 'package:comrade/src/util/message_reactions.dart';
import 'package:comrade/src/widgets/message_bubble.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'helpers.dart';

void main() {
  group('statusGlyph', () {
    test('a single tick means handed to a relay', () {
      expect(statusGlyph(MessageStatus.sent), '✓');
      // A missing status is still "we sent it" — a bubble with no tick at all
      // reads as "didn't send", which is worse than optimistic.
      expect(statusGlyph(null), '✓');
    });

    test('a double tick means it reached them', () {
      expect(statusGlyph(MessageStatus.delivered), '✓✓');
      expect(statusGlyph(MessageStatus.read), '✓✓');
    });
  });

  group('MessageStatus ranking', () {
    test('a later status outranks an earlier one', () {
      expect(MessageStatus.read.outranks(MessageStatus.delivered), isTrue);
      expect(MessageStatus.delivered.outranks(MessageStatus.sent), isTrue);
      expect(MessageStatus.sent.outranks(null), isTrue);
    });

    test('a late "delivered" must never undo a "read"', () {
      expect(MessageStatus.delivered.outranks(MessageStatus.read), isFalse);
    });

    test('the same status is idempotent, not a regression', () {
      expect(MessageStatus.read.outranks(MessageStatus.read), isTrue);
    });
  });

  group('StatusTicks rendering', () {
    Future<void> pumpTicks(WidgetTester tester, MessageStatus? status) async {
      final repo = await unlockedFake();
      await tester.pumpWidget(harness(StatusTicks(status), repo: repo));
      await tester.pump();
    }

    testWidgets('read is tinted with the accent, delivered is not',
        (WidgetTester tester) async {
      await pumpTicks(tester, MessageStatus.read);
      final Text read = tester.widget<Text>(find.text('✓✓'));
      final Color? readColor = read.style?.color;

      await pumpTicks(tester, MessageStatus.delivered);
      final Text delivered = tester.widget<Text>(find.text('✓✓'));

      expect(readColor, isNotNull);
      expect(delivered.style?.color, isNot(readColor),
          reason: 'read is the only status that changes colour');
    });

    testWidgets('the glyph carries a screen-reader label',
        (WidgetTester tester) async {
      await pumpTicks(tester, MessageStatus.delivered);
      expect(
        tester.widget<Text>(find.text('✓✓')).semanticsLabel,
        'Delivered',
      );
    });
  });

  group('MessageBubble', () {
    testWidgets('an outgoing bubble shows the clock time and a tick',
        (WidgetTester tester) async {
      final repo = await unlockedFake();
      await tester.pumpWidget(
        harness(
          MessageBubble(
            message: const MessageInfo(
              id: 'm1',
              peer: 'npub1x',
              content: 'Safe travels.',
              // 2025-07-12T12:00:00Z
              createdAt: 1752321600,
              outgoing: true,
              status: MessageStatus.delivered,
            ),
            onReply: () {},
          ),
          repo: repo,
        ),
      );
      await tester.pump();

      expect(find.text('Safe travels.'), findsOneWidget);
      expect(find.text('✓✓'), findsOneWidget);
    });

    testWidgets('an incoming bubble shows no ticks at all',
        (WidgetTester tester) async {
      final repo = await unlockedFake();
      await tester.pumpWidget(
        harness(
          MessageBubble(
            message: const MessageInfo(
              id: 'm1',
              peer: 'npub1x',
              content: 'Boarded',
              createdAt: 1752321600,
              outgoing: false,
              // Even if the store somehow carried one, an incoming message's
              // delivery state is not ours to display.
              status: MessageStatus.read,
            ),
            onReply: () {},
          ),
          repo: repo,
        ),
      );
      await tester.pump();

      expect(find.text('✓✓'), findsNothing);
      expect(find.text('✓'), findsNothing);
    });

    testWidgets('a reply renders the quoted preview above its own text',
        (WidgetTester tester) async {
      final repo = await unlockedFake();
      await tester.pumpWidget(
        harness(
          MessageBubble(
            message: const MessageInfo(
              id: 'm2',
              peer: 'npub1x',
              content: 'Safe travels.',
              createdAt: 1752321600,
              outgoing: true,
              replyTo: 'm1',
            ),
            quotedText: 'Boarded',
            onReply: () {},
          ),
          repo: repo,
        ),
      );
      await tester.pump();

      expect(find.text('Boarded'), findsOneWidget);
      expect(find.text('Safe travels.'), findsOneWidget);
    });

    testWidgets('swiping a bubble rightward starts a reply',
        (WidgetTester tester) async {
      // Reply moved off the long press and onto the swipe (Telegram's split), so
      // that the long press is free to open reactions. This is the test that used
      // to assert the old binding — kept pointed at the same outcome through the
      // new gesture rather than deleted, since "a swipe replies" is the contract.
      final repo = await unlockedFake();
      var replied = false;
      await tester.pumpWidget(
        harness(
          MessageBubble(
            message: const MessageInfo(
              id: 'm1',
              peer: 'npub1x',
              content: 'Boarded',
              createdAt: 1752321600,
              outgoing: false,
            ),
            onReply: () => replied = true,
          ),
          repo: repo,
        ),
      );
      await tester.pump();

      // `kDragSlopDefault` on top of the threshold: `tester.drag` spends the
      // first slop-worth of movement getting the recognizer to claim the gesture,
      // and only the remainder is reported as drag deltas.
      await tester.drag(
        find.text('Boarded'),
        const Offset(replySwipeTriggerDp + kDragSlopDefault + 8, 0),
      );
      await tester.pumpAndSettle();
      expect(replied, isTrue);
    });

    testWidgets('a short drag is a scroll wobble, not a reply',
        (WidgetTester tester) async {
      final repo = await unlockedFake();
      var replied = false;
      await tester.pumpWidget(
        harness(
          MessageBubble(
            message: const MessageInfo(
              id: 'm1',
              peer: 'npub1x',
              content: 'Boarded',
              createdAt: 1752321600,
              outgoing: false,
            ),
            onReply: () => replied = true,
          ),
          repo: repo,
        ),
      );
      await tester.pump();

      await tester.drag(find.text('Boarded'), const Offset(12, 0));
      await tester.pumpAndSettle();
      expect(replied, isFalse);
      // …and neither does a leftward one, however far it goes.
      await tester.drag(find.text('Boarded'), const Offset(-200, 0));
      await tester.pumpAndSettle();
      expect(replied, isFalse);
    });

    testWidgets('a long press opens the reaction sheet, not a reply',
        (WidgetTester tester) async {
      final repo = await unlockedFake();
      var replied = false;
      var pressed = 0;
      await tester.pumpWidget(
        harness(
          MessageBubble(
            message: const MessageInfo(
              id: 'm1',
              peer: 'npub1x',
              content: 'Boarded',
              createdAt: 1752321600,
              outgoing: false,
            ),
            onReply: () => replied = true,
            onLongPress: () => pressed++,
          ),
          repo: repo,
        ),
      );
      await tester.pump();

      await tester.longPress(find.text('Boarded'));
      await tester.pumpAndSettle();
      expect(pressed, 1);
      expect(replied, isFalse, reason: 'the two gestures must not overlap');
    });

    testWidgets('reaction chips render, count only when shared',
        (WidgetTester tester) async {
      final repo = await unlockedFake();
      final List<String> toggled = <String>[];
      await tester.pumpWidget(
        harness(
          MessageBubble(
            message: const MessageInfo(
              id: 'm1',
              peer: 'npub1x',
              content: 'Boarded',
              createdAt: 1752321600,
              outgoing: false,
            ),
            onReply: () {},
            chips: const <ReactionChip>[
              ReactionChip(emoji: '🔥', count: 2, mine: true),
              ReactionChip(emoji: '👍', count: 1, mine: false),
            ],
            onToggleReaction: toggled.add,
          ),
          repo: repo,
        ),
      );
      await tester.pump();

      expect(find.byKey(const Key('dm-reactions')), findsOneWidget);
      expect(find.text('🔥'), findsOneWidget);
      expect(find.text('2'), findsOneWidget);
      // "👍 1" is noise next to "👍" — one person is what a bare chip means.
      expect(find.text('1'), findsNothing);

      await tester.tap(find.byKey(const Key('reaction-👍')));
      expect(toggled, <String>['👍']);
    });

    testWidgets('a bubble with no reaction handler draws no chip row',
        (WidgetTester tester) async {
      // The "no fake affordances" rule: chips that cannot be tapped are a lie
      // about what the screen offers.
      final repo = await unlockedFake();
      await tester.pumpWidget(
        harness(
          MessageBubble(
            message: const MessageInfo(
              id: 'm1',
              peer: 'npub1x',
              content: 'Boarded',
              createdAt: 1752321600,
              outgoing: false,
            ),
            onReply: () {},
            chips: const <ReactionChip>[
              ReactionChip(emoji: '🔥', count: 1, mine: false),
            ],
          ),
          repo: repo,
        ),
      );
      await tester.pump();
      expect(find.byKey(const Key('dm-reactions')), findsNothing);
    });
  });

  group('DaySeparator', () {
    testWidgets('renders its label', (WidgetTester tester) async {
      final repo = await unlockedFake();
      await tester
          .pumpWidget(harness(const DaySeparator('Yesterday'), repo: repo));
      await tester.pump();
      expect(find.text('Yesterday'), findsOneWidget);
    });
  });
}
