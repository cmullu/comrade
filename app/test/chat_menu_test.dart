import 'package:comrade/src/util/chat_menu.dart';
import 'package:flutter_test/flutter_test.dart';

/// Pins the conversation ⋮ menu: the comrade and mute rows name the action
/// rather than the state, destructive entries stay last, and nothing is offered
/// twice.
///
/// Mirrors `ChatMenuTest.kt` — a change on one frontend that isn't made on the
/// other shows up as a failing test rather than as silent UI drift.
void main() {
  /// Every combination of the two toggles the menu is built from.
  List<List<ChatMenuAction>> allMenus() => <List<ChatMenuAction>>[
        for (final bool isComrade in <bool>[true, false])
          for (final bool isMuted in <bool>[true, false])
            conversationMenu(isComrade: isComrade, isMuted: isMuted),
      ];

  bool isComradeRow(ChatMenuAction a) =>
      a == ChatMenuAction.addComrade || a == ChatMenuAction.removeComrade;
  bool isMuteRow(ChatMenuAction a) =>
      a == ChatMenuAction.mute || a == ChatMenuAction.unmute;

  group('conversationMenu', () {
    test('a non-comrade is offered the add', () {
      final List<ChatMenuAction> menu = conversationMenu(isComrade: false);
      expect(menu, contains(ChatMenuAction.addComrade));
      expect(menu, isNot(contains(ChatMenuAction.removeComrade)));
    });

    test('a comrade is offered the removal', () {
      final List<ChatMenuAction> menu = conversationMenu(isComrade: true);
      expect(menu, contains(ChatMenuAction.removeComrade));
      expect(menu, isNot(contains(ChatMenuAction.addComrade)));
    });

    test('an unmuted chat is offered mute, and vice versa', () {
      final List<ChatMenuAction> loud =
          conversationMenu(isComrade: false, isMuted: false);
      expect(loud, contains(ChatMenuAction.mute));
      expect(loud, isNot(contains(ChatMenuAction.unmute)));

      final List<ChatMenuAction> quiet =
          conversationMenu(isComrade: false, isMuted: true);
      expect(quiet, contains(ChatMenuAction.unmute));
      expect(quiet, isNot(contains(ChatMenuAction.mute)));
    });

    test('order is stable', () {
      expect(
          conversationMenu(isComrade: false, isMuted: false), <ChatMenuAction>[
        ChatMenuAction.setAlias,
        ChatMenuAction.addComrade,
        ChatMenuAction.mute,
        ChatMenuAction.copyKey,
        ChatMenuAction.encryptionInfo,
        ChatMenuAction.block,
      ]);
    });

    test('toggling comrade or mute swaps one row in place, never reorders', () {
      // Otherwise a tap lands on a different action than the one reached for.
      expect(
          allMenus().map((List<ChatMenuAction> m) => m.length).toSet().length,
          1);
      for (final List<ChatMenuAction> menu in allMenus()) {
        expect(menu.indexWhere(isComradeRow), 1);
        expect(menu.indexWhere(isMuteRow), 2);
      }
    });

    test('no action appears twice', () {
      for (final List<ChatMenuAction> menu in allMenus()) {
        expect(menu.toSet().length, menu.length);
      }
    });

    test('block is the only destructive entry and it is last', () {
      for (final List<ChatMenuAction> menu in allMenus()) {
        expect(menu.last, ChatMenuAction.block);
        expect(
          menu.where((ChatMenuAction a) => a.destructive),
          <ChatMenuAction>[ChatMenuAction.block],
        );
      }
    });

    test('every action is reachable from some menu', () {
      // A menu entry nobody can reach is a dead code path; an action missing
      // from the enum is a non-exhaustive switch at the call site.
      expect(
        allMenus().expand((List<ChatMenuAction> m) => m).toSet(),
        ChatMenuAction.values.toSet(),
      );
    });
  });
}
