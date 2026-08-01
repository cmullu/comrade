/// The responsive shell, and the display-name rules the header enforces.
///
/// One codebase, two navigation idioms: bottom navigation below 840 logical
/// pixels, the desktop sidebar at or above it. These tests drag the window
/// across that line and assert the shell follows.
library;

import 'package:comrade/src/data/fake_comrade_repository.dart';
import 'package:comrade/src/data/models.dart';
import 'package:comrade/src/platform/platform.dart' show SystemChannel;
import 'package:comrade/src/screens/home_shell.dart';
import 'package:comrade/src/state/chat_providers.dart';
import 'package:comrade/src/state/settings_providers.dart';
import 'package:comrade/src/theme/breakpoints.dart';
import 'package:comrade/src/theme/comrade_theme.dart';
import 'package:comrade/src/util/display_name.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'helpers.dart';

/// Records mute calls and answers from an in-memory set — the native store
/// stands in for itself, because the real one is an Android SharedPreferences
/// read behind a channel with no handler in a widget test.
class _FakeSystemChannel extends SystemChannel {
  _FakeSystemChannel({Set<String>? muted}) : muted = muted ?? <String>{};

  final Set<String> muted;
  final List<String> calls = <String>[];

  @override
  Future<Set<String>> mutedPeers() async => muted;

  @override
  Future<bool> isMuted(String peer) async => muted.contains(peer);

  @override
  Future<void> setMuted(String peer, {required bool muted}) async {
    calls.add('setMuted:$peer:$muted');
    if (muted) {
      this.muted.add(peer);
    } else {
      this.muted.remove(peer);
    }
  }

  @override
  Future<void> unmuteAll() async => muted.clear();

  @override
  Future<bool> areNotificationsEnabled() async => true;
}

Widget _shell(
  FakeComradeRepository repo, {
  ChatTarget? open,
  SystemChannel? system,
}) =>
    ProviderScope(
      overrides: <Override>[
        ...fakeOverrides(repo),
        if (open != null)
          openConversationProvider.overrideWith((Ref ref) => open),
        if (system != null) systemChannelProvider.overrideWithValue(system),
      ],
      child: MaterialApp(
        theme: ComradeTheme.dark(),
        home: const HomeShell(),
      ),
    );

void main() {
  group('Breakpoints', () {
    test('classify follows the widths the existing CSS already uses', () {
      expect(Breakpoints.classify(390), ComradeWindowClass.compact);
      expect(Breakpoints.classify(700), ComradeWindowClass.medium);
      expect(Breakpoints.classify(840), ComradeWindowClass.expanded);
      expect(Breakpoints.classify(1440), ComradeWindowClass.expanded);
      expect(Breakpoints.classify(1600), ComradeWindowClass.ultrawide);
    });

    test('only the wide classes get a sidebar and two panes', () {
      expect(ComradeWindowClass.compact.usesBottomNav, isTrue);
      expect(ComradeWindowClass.medium.usesBottomNav, isTrue);
      expect(ComradeWindowClass.expanded.usesSidebar, isTrue);
      expect(ComradeWindowClass.ultrawide.supportsTwoPane, isTrue);
      expect(ComradeWindowClass.compact.supportsTwoPane, isFalse);
    });

    test('the sidebar track is clamped like clamp(232px, 15vw, 288px)', () {
      expect(Breakpoints.sidebarWidth(900), 232);
      expect(Breakpoints.sidebarWidth(1600), 240);
      expect(Breakpoints.sidebarWidth(3440), 288);
    });

    test(
        'the conversation list track is clamped like clamp(240px, 18vw, 320px)',
        () {
      expect(Breakpoints.conversationListWidth(1000), 240);
      expect(Breakpoints.conversationListWidth(3440), 320);
    });

    test('the feed column is clamped like clamp(720px, 66vw, 1280px)', () {
      expect(Breakpoints.feedColumnWidth(900), 720);
      expect(Breakpoints.feedColumnWidth(3440), 1280);
    });
  });

  group('navigation presentation', () {
    testWidgets('a phone-width window uses bottom navigation',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(400, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(_shell(repo));
      await tester.pumpAndSettle();

      expect(find.byType(NavigationBar), findsOneWidget);
      expect(find.byKey(const Key('nav-drawer-button')), findsOneWidget);
    });

    testWidgets('a desktop-width window uses the sidebar and no bottom bar',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(1400, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(_shell(repo));
      await tester.pumpAndSettle();

      expect(find.byType(NavigationBar), findsNothing);
      // The sidebar's own labels, plus the secondary group Android kept in a
      // drawer.
      expect(find.text('Journal'), findsOneWidget);
      expect(find.text('Call history'), findsOneWidget);
      expect(find.text('Settings'), findsOneWidget);
    });

    testWidgets('resizing across the breakpoint swaps the chrome live',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(1400, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(_shell(repo));
      await tester.pumpAndSettle();
      expect(find.byType(NavigationBar), findsNothing);

      tester.view.physicalSize = const Size(400, 900);
      await tester.pumpAndSettle();
      expect(find.byType(NavigationBar), findsOneWidget);
    });

    testWidgets('the wide layout shows list and thread side by side',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(1400, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(
        _shell(repo, open: const ChatTarget(peer: FakePeers.alice)),
      );
      await tester.pumpAndSettle();

      // The composer (detail pane) and the chat list are both mounted.
      expect(find.byKey(const Key('dm-input')), findsOneWidget);
      expect(find.text('Amma'), findsWidgets);
    });

    testWidgets('the narrow layout gives the thread the whole screen',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(400, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(
        _shell(repo, open: const ChatTarget(peer: FakePeers.alice)),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('dm-input')), findsOneWidget);
      // The conversation view owns the screen — no bottom bar beneath it.
      expect(find.byType(NavigationBar), findsNothing);
    });
  });

  group('conversation header naming', () {
    testWidgets('shows the alias, and always the npub tail beside it',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(400, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(
        _shell(
          repo,
          open: const ChatTarget(
            peer: FakePeers.alice,
            alias: 'Amma',
            username: 'alice',
          ),
        ),
      );
      await tester.pumpAndSettle();

      // Alias wins the title…
      expect(find.text('Amma'), findsWidgets);
      // …and the key stays on screen regardless, because a published @handle
      // is a self-declared claim and the key is the identity.
      expect(find.text(shortNpub(FakePeers.alice)), findsWidgets);
    });

    testWidgets('falls back to the published handle when there is no alias',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(400, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(
        _shell(
          repo,
          open: const ChatTarget(peer: FakePeers.bhaskar, username: 'bhaskar'),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('@bhaskar'), findsWidgets);
      expect(find.text(shortNpub(FakePeers.bhaskar)), findsWidgets);
    });

    testWidgets('falls back to the shortened key when neither name exists',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(400, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(
        _shell(repo, open: const ChatTarget(peer: FakePeers.stranger)),
      );
      await tester.pumpAndSettle();

      // Title and key line are both the short key — two matches, never a name.
      expect(find.text(shortNpub(FakePeers.stranger)), findsWidgets);
      expect(find.textContaining('@'), findsNothing);
    });
  });

  group('chat list', () {
    testWidgets('surfaces the message-requests banner with its count',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(400, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(_shell(repo));
      await tester.pumpAndSettle();

      expect(find.text('Message requests (1)'), findsOneWidget);
    });

    testWidgets('titles rows by alias, then handle, then key',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(400, 900));
      final FakeComradeRepository repo = await unlockedFake();

      await tester.pumpWidget(_shell(repo));
      await tester.pumpAndSettle();

      expect(find.text('Amma'), findsOneWidget);
      expect(find.text('@bhaskar'), findsOneWidget);
    });
  });

  group('transport precedence', () {
    /// Which glyph leads — the whole point of the control is that the order is
    /// readable at a glance without opening anything.
    IconData leadIcon(WidgetTester tester) =>
        tester.widget<Icon>(find.byKey(const Key('transport-lead'))).icon!;

    IconData fallbackIcon(WidgetTester tester) =>
        tester.widget<Icon>(find.byKey(const Key('transport-fallback'))).icon!;

    Future<void> pumpShell(
      WidgetTester tester,
      FakeComradeRepository repo, {
      Size size = const Size(400, 900),
    }) async {
      setWindowSize(tester, size);
      await tester.pumpWidget(_shell(repo));
      await tester.pumpAndSettle();
    }

    testWidgets('sits opposite the hamburger instead of a permanent banner',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      await pumpShell(tester, repo);

      expect(find.byKey(const Key('nav-drawer-button')), findsOneWidget);
      expect(find.byKey(const Key('transport-precedence')), findsOneWidget);
      // The row it used to occupy on every screen is gone.
      expect(find.textContaining('Local mesh'), findsNothing);
    });

    testWidgets('relays lead until the user says otherwise',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      await pumpShell(tester, repo);

      expect(leadIcon(tester), Icons.cloud_outlined);
      // The other route is drawn too — precedence is an order, not a choice of
      // one transport over none.
      expect(fallbackIcon(tester), isNot(Icons.cloud_outlined));
    });

    testWidgets('picking the local network swaps the order and the workspace',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      await pumpShell(tester, repo);

      await tester.tap(find.byKey(const Key('transport-precedence')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('precedence-local')));
      await tester.pumpAndSettle();

      expect((await repo.currentWorkspace()).meshActive, isTrue);
      expect(leadIcon(tester), isNot(Icons.cloud_outlined));
      expect(fallbackIcon(tester), Icons.cloud_outlined);
    });

    testWidgets('re-picking the order already in force changes nothing',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      await pumpShell(tester, repo);

      await tester.tap(find.byKey(const Key('transport-precedence')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('precedence-relay')));
      await tester.pumpAndSettle();

      // The core's state machine rejects a self-transition, so confirming what
      // is already set must not surface as an error the user caused.
      expect(find.byType(SnackBar), findsNothing);
      expect((await repo.currentWorkspace()).meshActive, isFalse);
      expect(leadIcon(tester), Icons.cloud_outlined);
    });

    testWidgets('nearby devices are counted on the glyph and named in the menu',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      await pumpShell(tester, repo);

      repo.emit(
        const MeshStatusChanged(MeshStatus(active: true, peerCount: 3)),
      );
      await tester.pumpAndSettle();

      // The count the banner used to spell out, without spending a row on it.
      expect(
        find.descendant(
          of: find.byKey(const Key('transport-precedence')),
          matching: find.text('3'),
        ),
        findsOneWidget,
      );
      await tester.tap(find.byKey(const Key('transport-precedence')));
      await tester.pumpAndSettle();
      expect(find.text('3 devices nearby'), findsOneWidget);
    });

    testWidgets('the wide shell puts it on the header, which has no hamburger',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      await pumpShell(tester, repo, size: const Size(1400, 900));

      expect(find.byKey(const Key('nav-drawer-button')), findsNothing);
      expect(find.byKey(const Key('transport-precedence')), findsOneWidget);
    });
  });

  group('conversation ⋮ menu', () {
    Future<void> openMenu(
      WidgetTester tester,
      FakeComradeRepository repo,
      String peer, {
      SystemChannel? system,
    }) async {
      setWindowSize(tester, const Size(400, 900));
      await tester.pumpWidget(
        _shell(repo, open: ChatTarget(peer: peer), system: system),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('chat-menu')));
      await tester.pumpAndSettle();
    }

    testWidgets('offers to mute an unmuted conversation, and then to unmute it',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      final _FakeSystemChannel system = _FakeSystemChannel();
      await openMenu(tester, repo, FakePeers.bhaskar, system: system);

      expect(find.text('Mute notifications'), findsOneWidget);
      expect(find.text('Unmute notifications'), findsNothing);

      await tester.tap(find.text('Mute notifications'));
      await tester.pumpAndSettle();
      // Native owns the mute set: it is what the notification path reads, with
      // no engine attached.
      expect(system.calls, <String>['setMuted:${FakePeers.bhaskar}:true']);

      await tester.tap(find.byKey(const Key('chat-menu')));
      await tester.pumpAndSettle();
      expect(find.text('Unmute notifications'), findsOneWidget);
      expect(find.text('Mute notifications'), findsNothing);
    });

    testWidgets('an already-muted conversation is offered the unmute',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      await openMenu(
        tester,
        repo,
        FakePeers.bhaskar,
        system: _FakeSystemChannel(muted: <String>{FakePeers.bhaskar}),
      );
      expect(find.text('Unmute notifications'), findsOneWidget);
    });

    testWidgets('replaces the alias pencil — calls stay, the rest move in',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      setWindowSize(tester, const Size(400, 900));
      await tester.pumpWidget(
        _shell(repo, open: const ChatTarget(peer: FakePeers.bhaskar)),
      );
      await tester.pumpAndSettle();

      // The pencil that used to sit in the bar is gone…
      expect(find.byKey(const Key('edit-alias')), findsNothing);
      // …calls remain one tap away…
      expect(find.byIcon(Icons.call), findsOneWidget);
      expect(find.byIcon(Icons.videocam), findsOneWidget);
      // …and everything else is behind ⋮.
      expect(find.byKey(const Key('chat-menu')), findsOneWidget);
    });

    testWidgets('offers to make a non-comrade a comrade',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      await openMenu(tester, repo, FakePeers.bhaskar);

      expect(find.text('Make a comrade'), findsOneWidget);
      expect(find.text('Remove as comrade'), findsNothing);
      // The rest of the menu is present too.
      expect(find.text('Set alias'), findsOneWidget);
      expect(find.text('Copy public key'), findsOneWidget);
      expect(find.text('Encryption details'), findsOneWidget);
      expect(find.text('Block this person'), findsOneWidget);
    });

    testWidgets('offers to drop an existing comrade',
        (WidgetTester tester) async {
      // Amma is seeded as a comrade.
      final FakeComradeRepository repo = await unlockedFake();
      await openMenu(tester, repo, FakePeers.alice);

      expect(find.text('Remove as comrade'), findsOneWidget);
      expect(find.text('Make a comrade'), findsNothing);
    });

    testWidgets('making a comrade sticks, and the menu then offers the reverse',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      await openMenu(tester, repo, FakePeers.bhaskar);

      await tester.tap(find.text('Make a comrade'));
      await tester.pumpAndSettle();

      expect(
        (await repo.comrades()).map((ComradeInfo c) => c.npub),
        contains(FakePeers.bhaskar),
      );
      await tester.tap(find.byKey(const Key('chat-menu')));
      await tester.pumpAndSettle();
      expect(find.text('Remove as comrade'), findsOneWidget);
    });

    testWidgets('encryption details shows the key in full, not shortened',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      await openMenu(tester, repo, FakePeers.bhaskar);

      await tester.tap(find.text('Encryption details'));
      await tester.pumpAndSettle();

      // Unabbreviated on purpose: a truncated key can't be compared.
      expect(find.byKey(const Key('encryption-key')), findsOneWidget);
      expect(find.text(FakePeers.bhaskar), findsOneWidget);
    });

    testWidgets('block asks first, and cancelling leaves the chat alone',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      await openMenu(tester, repo, FakePeers.bhaskar);

      await tester.tap(find.text('Block this person'));
      await tester.pumpAndSettle();
      expect(find.text('Block this person?'), findsOneWidget);

      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();

      // Still open, still there.
      expect(find.byKey(const Key('dm-input')), findsOneWidget);
      expect(
        (await repo.conversations()).map((ConversationInfo c) => c.peer),
        contains(FakePeers.bhaskar),
      );
    });

    testWidgets('confirming block drops the thread and leaves the conversation',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      await openMenu(tester, repo, FakePeers.bhaskar);

      await tester.tap(find.text('Block this person'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('block-confirm')));
      await tester.pumpAndSettle();

      expect(
        (await repo.conversations()).map((ConversationInfo c) => c.peer),
        isNot(contains(FakePeers.bhaskar)),
      );
      // Staying on a blocked thread would show a chat that no longer exists.
      expect(find.byKey(const Key('dm-input')), findsNothing);
    });
  });
}
