import 'package:comrade/src/util/chat_menu.dart';
import 'package:flutter_test/flutter_test.dart';

/// Pins the conversation ⋮ menu: the comrade row names the action rather than
/// the state, destructive entries stay last, and nothing is offered twice.
///
/// Mirrors `ChatMenuTest.kt` — a change on one frontend that isn't made on the
/// other shows up as a failing test rather than as silent UI drift.
void main() {
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

    test('order is stable', () {
      expect(conversationMenu(isComrade: false), <ChatMenuAction>[
        ChatMenuAction.setAlias,
        ChatMenuAction.addComrade,
        ChatMenuAction.copyKey,
        ChatMenuAction.encryptionInfo,
        ChatMenuAction.block,
      ]);
    });

    test('toggling comrade swaps one row in place, it does not reorder', () {
      final List<ChatMenuAction> off = conversationMenu(isComrade: false);
      final List<ChatMenuAction> on = conversationMenu(isComrade: true);
      // Otherwise a tap lands on a different action than the one reached for.
      expect(on.length, off.length);
      expect(
        on.indexOf(ChatMenuAction.removeComrade),
        off.indexOf(ChatMenuAction.addComrade),
      );
    });

    test('no action appears twice', () {
      for (final bool isComrade in <bool>[true, false]) {
        final List<ChatMenuAction> menu =
            conversationMenu(isComrade: isComrade);
        expect(menu.toSet().length, menu.length);
      }
    });

    test('block is the only destructive entry and it is last', () {
      for (final bool isComrade in <bool>[true, false]) {
        final List<ChatMenuAction> menu =
            conversationMenu(isComrade: isComrade);
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
        <ChatMenuAction>{
          ...conversationMenu(isComrade: true),
          ...conversationMenu(isComrade: false),
        },
        ChatMenuAction.values.toSet(),
      );
    });
  });
}
