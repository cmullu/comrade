/// The conversation thread: day separators between calendar days, text and
/// media interleaved in time order, and receipts that never regress.
library;

import 'dart:typed_data';

import 'package:comrade/src/data/fake_comrade_repository.dart';
import 'package:comrade/src/data/models.dart';
import 'package:comrade/src/screens/chats/conversation_screen.dart';
import 'package:comrade/src/state/chat_providers.dart';
import 'package:comrade/src/util/attachment_caption.dart';
import 'package:comrade/src/util/message_reactions.dart';
import 'package:comrade/src/widgets/media_attachment.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'helpers.dart';

/// Yields one small attachment, so a test can drive the paper clip without a
/// platform picker.
class _OnePicker implements AttachmentPicker {
  @override
  Future<PickedAttachment?> pick() async => PickedAttachment(
        name: 'IMG_20260731_114233.png',
        mimeType: 'image/png',
        bytes: Uint8List.fromList(<int>[1, 2, 3]),
      );
}

/// Yields something over the 10 MB cap. Allocated, not faked: the refusal has to
/// be decided from the bytes the picker actually handed over.
class _HugePicker implements AttachmentPicker {
  @override
  Future<PickedAttachment?> pick() async => PickedAttachment(
        name: 'holiday.mov',
        mimeType: 'video/quicktime',
        bytes: Uint8List(maxAttachmentBytes + 1),
      );
}

/// What a cancelled camera hands back.
class _EmptyPicker implements AttachmentPicker {
  @override
  Future<PickedAttachment?> pick() async => PickedAttachment(
        name: 'cap.jpg',
        mimeType: 'image/jpeg',
        bytes: Uint8List(0),
      );
}

/// The text currently in the preview sheet's caption box.
String captionInSheet(WidgetTester tester) =>
    tester
        .widget<TextField>(find.byKey(const Key('attachment-preview-caption')))
        .controller
        ?.text ??
    '';

/// Drag a bubble past the reply threshold and let go.
///
/// Reply is a rightward swipe now — the long press belongs to reactions. The
/// distance comes from `replySwipeTriggerDp` rather than a literal so this cannot
/// drift out from under the gesture it is exercising, plus `kDragSlopDefault`:
/// `tester.drag` spends the first slop-worth of movement getting the recognizer
/// to claim the gesture, and only the remainder is reported as drag deltas. Omit
/// it and a drag that looks past the threshold arrives just short of it.
Future<void> _swipeToReply(WidgetTester tester, Finder bubble) async {
  await tester.drag(
    bubble,
    const Offset(replySwipeTriggerDp + kDragSlopDefault + 8, 0),
  );
  await tester.pumpAndSettle();
}

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
      // A frame between typing and tapping, because Send is disabled while
      // there is nothing to send: the composer's action button follows the
      // draft (`MessageComposer._actionButton`), so the state it is in is a
      // rendered fact, not an assumption.
      await tester.pump();
      await tester.tap(find.byKey(const Key('dm-action')));
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

    testWidgets('a half-written message reports itself, then reports going',
        (WidgetTester tester) async {
      // The composer's half of `comrade_core::nudge`: the core cannot tell a
      // comrade that a message was written and abandoned unless the screen
      // says both things happened. Only the edges are reported — a call per
      // keystroke would cross the bridge for nothing.
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(
        harness(const ConversationScreen(peer: FakePeers.bhaskar), repo: repo),
      );
      await tester.pumpAndSettle();
      expect(repo.draftReports, isEmpty);

      await tester.enterText(find.byKey(const Key('dm-input')), 'I wanted to');
      await tester.pumpAndSettle();
      expect(repo.draftReports, <(String, String)>[
        ('note', FakePeers.bhaskar),
      ]);

      await tester.enterText(
          find.byKey(const Key('dm-input')), 'I wanted to s');
      await tester.pumpAndSettle();
      expect(
        repo.draftReports,
        hasLength(1),
        reason: 'still the same unsent draft — nothing new to say',
      );

      // Clearing the box is giving up on it.
      await tester.enterText(find.byKey(const Key('dm-input')), '');
      await tester.pumpAndSettle();
      expect(repo.draftReports, <(String, String)>[
        ('note', FakePeers.bhaskar),
        ('abandon', FakePeers.bhaskar),
      ]);
    });

    testWidgets('walking away from a draft reports it against that peer',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(
        harness(const ConversationScreen(peer: FakePeers.bhaskar), repo: repo),
      );
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('dm-input')), 'are you ok');
      await tester.pumpAndSettle();

      // The screen goes with the text still in it — the case a person is least
      // likely to come back from, and the one this feature exists for.
      await tester.pumpWidget(harness(const SizedBox(), repo: repo));
      await tester.pumpAndSettle();

      expect(repo.draftReports, <(String, String)>[
        ('note', FakePeers.bhaskar),
        ('abandon', FakePeers.bhaskar),
      ]);
    });

    testWidgets('a whitespace-only composer is not a half-written message',
        (WidgetTester tester) async {
      // `_send` would not send it either, so it must not look like something
      // that was nearly sent.
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(
        harness(const ConversationScreen(peer: FakePeers.bhaskar), repo: repo),
      );
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('dm-input')), '   ');
      await tester.pumpAndSettle();

      expect(repo.draftReports, isEmpty);
    });
  });

  group('reacting to a message', () {
    testWidgets('a long press reacts, and tapping the chip takes it back',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(
        harness(const ConversationScreen(peer: FakePeers.alice), repo: repo),
      );
      await tester.pumpAndSettle();

      await tester.longPress(find.byType(MediaAttachmentBubble));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('message-actions')), findsOneWidget);

      await tester.tap(find.byKey(const Key('react-🔥')));
      await tester.pumpAndSettle();

      // The chip is drawn, marked as ours, and the store holds one row.
      expect(find.byKey(const Key('reaction-🔥')), findsOneWidget);
      final List<ReactionInfo> stored = await repo.reactions(FakePeers.alice);
      expect(stored.length, 1);
      expect(stored.single.emoji, '🔥');
      expect(stored.single.outgoing, isTrue);

      // Tapping the chip you are already in withdraws yours — the toggle, which
      // the core decides and this screen only reflects.
      await tester.tap(find.byKey(const Key('reaction-🔥')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('reaction-🔥')), findsNothing);
      expect(await repo.reactions(FakePeers.alice), isEmpty);
    });

    testWidgets('the sheet can reply instead, and the sheet closes first',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(
        harness(const ConversationScreen(peer: FakePeers.alice), repo: repo),
      );
      await tester.pumpAndSettle();

      await tester.longPress(find.byType(MediaAttachmentBubble));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('action-reply')));
      await tester.pumpAndSettle();

      // Gone, not merely covered: a reply focuses the composer, which cannot
      // happen underneath a sheet.
      expect(find.byKey(const Key('message-actions')), findsNothing);
      expect(find.byKey(const Key('dm-reply-chip')), findsOneWidget);
    });

    testWidgets('a peer reacting to our message shows up live',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(
        harness(const ConversationScreen(peer: FakePeers.alice), repo: repo),
      );
      await tester.pumpAndSettle();

      final String target = (await repo.messages(FakePeers.alice)).first.id;
      repo.emit(IncomingReaction(ReactionInfo(
        targetId: target,
        peer: FakePeers.alice,
        reactor: FakePeers.alice,
        emoji: '👍',
        createdAt: 1752321600,
        outgoing: false,
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('reaction-👍')), findsOneWidget);

      // …and withdrawing it takes the chip away again. An empty emoji is the
      // withdrawal on the wire.
      repo.emit(IncomingReaction(ReactionInfo(
        targetId: target,
        peer: FakePeers.alice,
        reactor: FakePeers.alice,
        emoji: '',
        createdAt: 1752321700,
        outgoing: false,
      )));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('reaction-👍')), findsNothing);
    });
  });

  group('replying to an attachment', () {
    testWidgets('a rightward swipe on media aims the composer at it',
        (WidgetTester tester) async {
      // The gap this closes: the reply target used to be typed `MessageInfo`,
      // so a thread's attachments were simply unrepliable. Nothing in the core
      // required that — an `e` tag does not care what kind of event it names.
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(
        harness(const ConversationScreen(peer: FakePeers.alice), repo: repo),
      );
      await tester.pumpAndSettle();

      await _swipeToReply(tester, find.byType(MediaAttachmentBubble));

      // The seeded attachment is a voice note with no caption: the chip has to
      // say *what* is being replied to, which is why the kind is always named.
      expect(
        find.byKey(const Key('dm-reply-chip')),
        findsOneWidget,
      );
      expect(find.textContaining('Voice message'), findsWidgets);
    });

    testWidgets('the reply is sent against the attachment event id',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(
        harness(const ConversationScreen(peer: FakePeers.alice), repo: repo),
      );
      await tester.pumpAndSettle();

      await _swipeToReply(tester, find.byType(MediaAttachmentBubble));
      await tester.enterText(find.byKey(const Key('dm-input')), 'heard it');
      await tester.pump();
      await tester.tap(find.byKey(const Key('dm-action')));
      await tester.pumpAndSettle();

      final MessageInfo sent = (await repo.messages(FakePeers.alice)).last;
      expect(sent.content, 'heard it');
      expect(sent.replyTo, 'media-1');
      // And the thread can now quote it, which is the other half: a reply whose
      // target resolves to nothing renders as a bare message.
      expect(find.textContaining('🎤 Voice message'), findsWidgets);
      expect(find.byKey(const Key('dm-reply-chip')), findsNothing,
          reason: 'sending clears the reply');
    });
  });

  group('previewing an attachment before sending it', () {
    // The gap this closes: a pick went straight to encrypt-and-upload, so the
    // first time the sender saw what they had chosen was in their own thread,
    // already delivered.

    testWidgets('picking shows the file, its name and its size',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(harness(
        const ConversationScreen(peer: FakePeers.bhaskar),
        repo: repo,
        extra: <Override>[
          attachmentPickerProvider.overrideWithValue(_OnePicker()),
        ],
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('dm-attach')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('attachment-preview')), findsOneWidget);
      // The device-chosen filename belongs here and nowhere else: it is what
      // answers "is this the file I meant to pick".
      expect(find.text('IMG_20260731_114233.png · 3 B'), findsOneWidget);
      // Nothing has been sent yet.
      expect(await repo.media(FakePeers.bhaskar), hasLength(0));
    });

    testWidgets('backing out sends nothing and keeps the draft',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(harness(
        const ConversationScreen(peer: FakePeers.bhaskar),
        repo: repo,
        extra: <Override>[
          attachmentPickerProvider.overrideWithValue(_OnePicker()),
        ],
      ));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('dm-input')), 'at the gate');
      await tester.pump();
      await tester.tap(find.byKey(const Key('dm-attach')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('attachment-preview-cancel')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('attachment-preview')), findsNothing);
      expect(await repo.media(FakePeers.bhaskar), hasLength(0));
      expect(
        tester
            .widget<TextField>(find.byKey(const Key('dm-input')))
            .controller
            ?.text,
        'at the gate',
        reason: 'nothing was sent, so nothing was consumed',
      );
    });

    testWidgets('an oversize pick is refused before any preview',
        (WidgetTester tester) async {
      // The cap used to be met only once the upload had begun — after the
      // caption was written, which is the only work the sender actually did.
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(harness(
        const ConversationScreen(peer: FakePeers.bhaskar),
        repo: repo,
        extra: <Override>[
          attachmentPickerProvider.overrideWithValue(_HugePicker()),
        ],
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('dm-attach')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('attachment-preview')), findsNothing);
      expect(
        find.textContaining('attachments are limited to 10 MB'),
        findsOneWidget,
      );
      expect(await repo.media(FakePeers.bhaskar), hasLength(0));
    });

    testWidgets('an empty capture is refused too', (WidgetTester tester) async {
      // A cancelled camera hands back a zero-byte placeholder.
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(harness(
        const ConversationScreen(peer: FakePeers.bhaskar),
        repo: repo,
        extra: <Override>[
          attachmentPickerProvider.overrideWithValue(_EmptyPicker()),
        ],
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('dm-attach')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('attachment-preview')), findsNothing);
      expect(find.textContaining('is empty'), findsOneWidget);
    });
  });

  group('tagging an attachment', () {
    testWidgets('the composer text seeds the caption, and the box empties',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(harness(
        const ConversationScreen(peer: FakePeers.bhaskar),
        repo: repo,
        extra: <Override>[
          attachmentPickerProvider.overrideWithValue(_OnePicker()),
        ],
      ));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('dm-input')), 'at the gate');
      await tester.pump();
      await tester.tap(find.byKey(const Key('dm-attach')));
      await tester.pumpAndSettle();

      // Seeded, so the habit of typing-then-attaching keeps working.
      expect(captionInSheet(tester), 'at the gate');
      await tester.tap(find.byKey(const Key('attachment-preview-send')));
      await tester.pumpAndSettle();

      final MediaMessageInfo sent = (await repo.media(FakePeers.bhaskar)).last;
      expect(sent.caption, 'at the gate');
      // Not the file's name, which is what this used to send: a device-chosen
      // `IMG_20260731_114233.jpg` is noise the sender never chose to share.
      expect(sent.caption, isNot(contains('.png')));
      expect(
        tester
            .widget<TextField>(find.byKey(const Key('dm-input')))
            .controller
            ?.text,
        isEmpty,
      );
    });

    testWidgets('a caption written in the sheet is what gets sent',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(harness(
        const ConversationScreen(peer: FakePeers.bhaskar),
        repo: repo,
        extra: <Override>[
          attachmentPickerProvider.overrideWithValue(_OnePicker()),
        ],
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('dm-attach')));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const Key('attachment-preview-caption')),
        '  composed against the picture  ',
      );
      await tester.tap(find.byKey(const Key('attachment-preview-send')));
      await tester.pumpAndSettle();

      // Trimmed on the way out, so the sender's thread and the recipient's show
      // the same string.
      expect(
        (await repo.media(FakePeers.bhaskar)).last.caption,
        'composed against the picture',
      );
    });

    testWidgets('a seeded draft the sheet deletes is still consumed',
        (WidgetTester tester) async {
      // The deletion was deliberate; putting the words back would be the
      // surprise.
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(harness(
        const ConversationScreen(peer: FakePeers.bhaskar),
        repo: repo,
        extra: <Override>[
          attachmentPickerProvider.overrideWithValue(_OnePicker()),
        ],
      ));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('dm-input')), 'never mind');
      await tester.pump();
      await tester.tap(find.byKey(const Key('dm-attach')));
      await tester.pumpAndSettle();
      await tester.enterText(
          find.byKey(const Key('attachment-preview-caption')), '');
      await tester.tap(find.byKey(const Key('attachment-preview-send')));
      await tester.pumpAndSettle();

      expect((await repo.media(FakePeers.bhaskar)).last.caption, isEmpty);
      expect(
        tester
            .widget<TextField>(find.byKey(const Key('dm-input')))
            .controller
            ?.text,
        isEmpty,
      );
    });

    testWidgets('a pending reply keeps its draft', (WidgetTester tester) async {
      // An attachment cannot carry `reply_to`, so it is not that reply — and
      // taking the words typed for the reply would lose them to a caption.
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(harness(
        const ConversationScreen(peer: FakePeers.alice),
        repo: repo,
        extra: <Override>[
          attachmentPickerProvider.overrideWithValue(_OnePicker()),
        ],
      ));
      await tester.pumpAndSettle();

      await _swipeToReply(tester, find.byType(MediaAttachmentBubble));
      await tester.enterText(find.byKey(const Key('dm-input')), 'this one');
      await tester.pump();
      await tester.tap(find.byKey(const Key('dm-attach')));
      await tester.pumpAndSettle();

      // The sheet opens with an empty caption rather than the reply's words.
      expect(captionInSheet(tester), isEmpty);
      await tester.tap(find.byKey(const Key('attachment-preview-send')));
      await tester.pumpAndSettle();

      expect((await repo.media(FakePeers.alice)).last.caption, isEmpty);
      expect(
        tester
            .widget<TextField>(find.byKey(const Key('dm-input')))
            .controller
            ?.text,
        'this one',
      );
      expect(find.byKey(const Key('dm-reply-chip')), findsOneWidget);
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

  group('opening position', () {
    testWidgets('a thread read up to date opens with no unread divider',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();
      // Seed the watermark past everything in Bhaskar's thread.
      repo.seedReadPosition(FakePeers.bhaskar, 1 << 40);

      await tester.pumpWidget(
        harness(const ConversationScreen(peer: FakePeers.bhaskar), repo: repo),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('unread-divider')), findsNothing);
    });

    testWidgets('a first visit opens with no divider', (WidgetTester t) async {
      setWindowSize(t, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();
      // No seeded position at all — nothing is known about where they left off,
      // so claiming an unread boundary would be an invention.
      await t.pumpWidget(
        harness(const ConversationScreen(peer: FakePeers.bhaskar), repo: repo),
      );
      await t.pumpAndSettle();

      expect(find.byKey(const Key('unread-divider')), findsNothing);
    });

    testWidgets('unread incoming messages get a divider',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();
      final List<MessageInfo> seeded = await repo.messages(FakePeers.bhaskar);
      // Read up to the first message; the rest are new. m5 is incoming, m6 is
      // ours, so the boundary must be the incoming one.
      repo.seedReadPosition(FakePeers.bhaskar, seeded.first.createdAt - 1);

      await tester.pumpWidget(
        harness(const ConversationScreen(peer: FakePeers.bhaskar), repo: repo),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('unread-divider')), findsOneWidget);
    });

    testWidgets('a thread whose only new messages are your own has no divider',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();
      final List<MessageInfo> seeded = await repo.messages(FakePeers.bhaskar);
      final MessageInfo mine = seeded.lastWhere((MessageInfo m) => m.outgoing);
      // Everything after the watermark is outgoing → nothing unread.
      repo.seedReadPosition(FakePeers.bhaskar, mine.createdAt - 1);
      // Guard the premise: the tail really is only ours.
      expect(
        seeded.where((MessageInfo m) => m.createdAt >= mine.createdAt).every(
              (MessageInfo m) => m.outgoing,
            ),
        isTrue,
      );

      await tester.pumpWidget(
        harness(const ConversationScreen(peer: FakePeers.bhaskar), repo: repo),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('unread-divider')), findsNothing);
    });

    testWidgets('opening advances the watermark, so a re-visit is clean',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      final FakeComradeRepository repo = await unlockedFake();
      final List<MessageInfo> seeded = await repo.messages(FakePeers.bhaskar);
      repo.seedReadPosition(FakePeers.bhaskar, seeded.first.createdAt - 1);

      await tester.pumpWidget(
        harness(const ConversationScreen(peer: FakePeers.bhaskar), repo: repo),
      );
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('unread-divider')), findsOneWidget);

      // Second visit: the first one marked everything read, so the line is gone
      // rather than stuck where it was.
      final int previous = await repo.markConversationRead(FakePeers.bhaskar);
      expect(previous, seeded.last.createdAt);
    });
  });
}
