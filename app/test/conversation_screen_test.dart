/// The conversation thread: day separators between calendar days, text and
/// media interleaved in time order, and receipts that never regress.
library;

import 'package:comrade/src/data/fake_comrade_repository.dart';
import 'package:comrade/src/data/models.dart';
import 'package:comrade/src/screens/chats/conversation_screen.dart';
import 'package:comrade/src/state/chat_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'helpers.dart';

void main() {
  group('mergeChatItems', () {
    test('interleaves text and media in time order', () {
      const MessageInfo older = MessageInfo(
        id: 'm1',
        peer: 'p',
        content: 'first',
        createdAt: 100,
        outgoing: false,
      );
      const MessageInfo newer = MessageInfo(
        id: 'm2',
        peer: 'p',
        content: 'third',
        createdAt: 300,
        outgoing: true,
      );
      const MediaMessageInfo middle = MediaMessageInfo(
        eventId: 'e1',
        url: '',
        mimeType: 'image/png',
        caption: '',
        sender: 'p',
        createdAt: 200,
        size: 1,
        outgoing: false,
      );

      final List<ChatItem> items = mergeChatItems(
        <MessageInfo>[older, newer],
        <MediaMessageInfo>[middle],
      );

      expect(items.map((ChatItem i) => i.createdAt), <int>[100, 200, 300]);
      expect(items[1], isA<MediaChatItem>());
    });

    test('media keys are namespaced so they cannot collide with message ids',
        () {
      final List<ChatItem> items = mergeChatItems(
        const <MessageInfo>[
          MessageInfo(
            id: 'shared-id',
            peer: 'p',
            content: 'x',
            createdAt: 1,
            outgoing: false,
          ),
        ],
        const <MediaMessageInfo>[
          MediaMessageInfo(
            eventId: 'shared-id',
            url: '',
            mimeType: 'image/png',
            caption: '',
            sender: 'p',
            createdAt: 2,
            size: 1,
            outgoing: false,
          ),
        ],
      );
      expect(items.map((ChatItem i) => i.key).toSet(), hasLength(2));
    });
  });

  group('ConversationScreen', () {
    testWidgets('renders one day separator per calendar day',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(
        harness(const ConversationScreen(peer: FakePeers.alice), repo: repo),
      );
      await tester.pumpAndSettle();

      // The seeded thread spans yesterday and today.
      expect(find.text('Today'), findsOneWidget);
      expect(find.text('Yesterday'), findsOneWidget);
    });

    testWidgets('a single-day thread gets exactly one separator',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(
        harness(const ConversationScreen(peer: FakePeers.bhaskar), repo: repo),
      );
      await tester.pumpAndSettle();

      expect(find.text('Today'), findsOneWidget);
      expect(find.text('Yesterday'), findsNothing);
    });

    testWidgets('an empty thread shows the encryption reassurance',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo =
          FakeComradeRepository(latency: Duration.zero, seed: false);
      await repo.unlockVault(path: 't', passphrase: 'p');

      await tester.pumpWidget(
        harness(const ConversationScreen(peer: 'npub1nobody'), repo: repo),
      );
      await tester.pumpAndSettle();

      expect(
        find.textContaining('end-to-end encrypted with your keys'),
        findsOneWidget,
      );
    });

    testWidgets('sending appends the message and clears the composer',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(
        harness(const ConversationScreen(peer: FakePeers.bhaskar), repo: repo),
      );
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('dm-input')), 'On my way');
      await tester.tap(find.byKey(const Key('dm-send')));
      await tester.pumpAndSettle();

      expect(find.text('On my way'), findsOneWidget);
      expect(
        tester
            .widget<TextField>(find.byKey(const Key('dm-input')))
            .controller
            ?.text,
        isEmpty,
      );
    });
  });

  group('receipt handling', () {
    test('a status event upgrades a matching outgoing message', () async {
      final FakeComradeRepository repo = await unlockedFake();
      final ProviderContainer container = testContainer(repo);

      await container.read(conversationProvider(FakePeers.bhaskar).future);
      repo.emit(
        const MessageStatusChanged(
          peer: FakePeers.bhaskar,
          messageIds: <String>['m6'],
          status: MessageStatus.read,
        ),
      );
      await Future<void>.delayed(Duration.zero);

      final ConversationState state =
          container.read(conversationProvider(FakePeers.bhaskar)).requireValue;
      final MessageInfo m6 =
          state.messages.firstWhere((MessageInfo m) => m.id == 'm6');
      expect(m6.status, MessageStatus.read);
    });

    test('a late "delivered" does not undo a "read"', () async {
      final FakeComradeRepository repo = await unlockedFake();
      final ProviderContainer container = testContainer(repo);

      await container.read(conversationProvider(FakePeers.alice).future);
      // m2 is seeded as already read.
      repo.emit(
        const MessageStatusChanged(
          peer: FakePeers.alice,
          messageIds: <String>['m2'],
          status: MessageStatus.delivered,
        ),
      );
      await Future<void>.delayed(Duration.zero);

      final ConversationState state =
          container.read(conversationProvider(FakePeers.alice)).requireValue;
      final MessageInfo m2 =
          state.messages.firstWhere((MessageInfo m) => m.id == 'm2');
      expect(m2.status, MessageStatus.read);
    });

    test('a receipt for another peer is ignored', () async {
      final FakeComradeRepository repo = await unlockedFake();
      final ProviderContainer container = testContainer(repo);

      await container.read(conversationProvider(FakePeers.bhaskar).future);
      repo.emit(
        const MessageStatusChanged(
          peer: FakePeers.alice,
          messageIds: <String>['m6'],
          status: MessageStatus.read,
        ),
      );
      await Future<void>.delayed(Duration.zero);

      final ConversationState state =
          container.read(conversationProvider(FakePeers.bhaskar)).requireValue;
      expect(
        state.messages.firstWhere((MessageInfo m) => m.id == 'm6').status,
        MessageStatus.sent,
      );
    });
  });
}
