/// A small, dependency-free emoji picker for the composer's left-hand button.
///
/// Deliberately hand-rolled rather than a package. Every emoji-keyboard plugin
/// on pub either ships its own asset font (megabytes, and a second glyph set
/// that disagrees with the platform's) or pulls in `shared_preferences` and a
/// permissions plugin for a "recents" row. What the composer needs is a grid of
/// characters that inserts one at the cursor, which is 200 lines and no new
/// dependency — and it renders in the platform's own emoji font, so what you
/// pick is what the person you send it to sees.
///
/// The set is curated, not exhaustive: the emoji people actually reach for in a
/// messenger, grouped the way keyboards group them. A search field over 3,600
/// Unicode names would need that name table, which is exactly the asset this
/// avoids.
library;

import 'package:flutter/material.dart';

/// One labelled group in the picker.
@immutable
class EmojiGroup {
  const EmojiGroup(this.label, this.icon, this.emoji);

  final String label;
  final IconData icon;
  final List<String> emoji;
}

/// The groups, in keyboard order.
///
/// Written as space-separated strings and split at startup: a `const` list of
/// 400 single-character literals is one emoji per line once the formatter has
/// had it, which is unreadable and unreviewable. No emoji here contains a
/// space, so the split is lossless — including the ZWJ sequences.
final List<EmojiGroup> kEmojiGroups = <EmojiGroup>[
  EmojiGroup(
      'Smileys',
      Icons.emoji_emotions_outlined,
      _split(
          '😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤗 🤭 🤫 🤔 🤐 😐 😑 😶 😏 😒 🙄 '
          '😬 😮‍💨 🤥 😌 😔 😪 😴 😷 🤒 🤕 🥱 😵 🤯 🥳 😎 🤓 🧐 😕 😟 🙁 😯 😦 😧 😢 😥 😭 😱 😖 😣 😞 😓 😩 🥺 😤 😠 '
          '😡 🤬 💀 💩 🤡 👻 👽 🤖 😺')),
  EmojiGroup(
      'People',
      Icons.front_hand_outlined,
      _split(
          '👋 🤚 ✋ 🖖 👌 🤌 ✌️ 🤞 🤟 🤘 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💪 🦾 👀 👁️ 👄 🧠 '
          '🫀 👶 🧑 👩 👨 🧓 🙋 🤷 🤦 💁 🙆 🙅 🧏 🚶 🏃 🧘 👫 👬 👭 🧑‍🤝‍🧑 👪 🫂 💃 🕺 👑 🎓')),
  EmojiGroup(
      'Hearts',
      Icons.favorite_outline,
      _split(
          '❤️ 🧡 💛 💚 💙 💜 🤎 🖤 🤍 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ♥️ ✨ ⭐ 🌟 💫 💥 🔥 🎉 🎊 🎈 🎁')),
  EmojiGroup(
      'Nature',
      Icons.pets_outlined,
      _split(
          '🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🦆 🦉 🦄 🐝 🦋 🐌 🐞 🐢 🐍 🐙 🐳 🐬 🌵 🌲 🌳 🌴 🌱 🌿 '
          '☘️ 🍀 🍁 🍂 🌸 💐 🌹 🌻 🌼 🌷 🌙 ☀️ ⛅ 🌈 ❄️ ⚡ 🌊 🏔️ 🌍 🪐 🌞 🌝 🍄 🐚')),
  EmojiGroup(
      'Food',
      Icons.restaurant_outlined,
      _split(
          '🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍒 🥭 🍍 🥥 🥝 🍅 🥑 🍆 🥕 🌽 🌶️ 🍞 🥐 🥖 🧇 🧀 🍳 🥘 🍲 🍛 🍜 🍝 🍕 🍔 🌮 🌯 '
          '🥗 🍿 🧂 🍦 🍩 🍪 🎂 🍰 🧁 🍫 🍬 ☕ 🍵 🧋 🥤')),
  EmojiGroup(
      'Activity',
      Icons.sports_soccer_outlined,
      _split(
          '⚽ 🏀 🏈 ⚾ 🎾 🏐 🏓 🏸 🥊 ⛳ 🎯 🎮 🕹️ 🎲 ♟️ 🎸 🎹 🎺 🎻 🥁 🎤 🎧 🎬 🎨 📷 📚 ✏️ 🧵 🧩 🏆 🚴 🏊 🧗 ⛺ '
          '🎿 🛹 🎣 🪄 🎺 🪘')),
  EmojiGroup(
      'Travel',
      Icons.flight_outlined,
      _split(
          '🚗 🚕 🚌 🏎️ 🚓 🚑 🚒 🛵 🏍️ 🚲 🛺 🚂 ✈️ 🚀 🛸 🚁 ⛵ 🚢 🗺️ 🧭 🏠 🏢 🏥 🏦 🏨 🏫 🕌 ⛩️ 🛕 🌃 🌉 🎡 🎢 '
          '🏝️ 🏖️ ⛱️ 🧳 🎫 🛎️ 🚏')),
  EmojiGroup(
      'Objects',
      Icons.lightbulb_outline,
      _split(
          '📱 💻 ⌨️ 🖥️ 🖨️ 🔌 🔋 💡 🔍 🔒 🔓 🔑 🗝️ 🔨 🪛 ⚙️ 🧰 🧲 🪜 🧹 📎 📌 📏 ✂️ 🗒️ 📅 📆 🗓️ 📊 📈 💰 💳 '
          '🧾 ✉️ 📤 📥 📦 📮 🗳️ 📢 ⌛ ⏰ ⏳ 🔔 🔕 🎙️ 🩺 💊 🧪 🔭')),
  EmojiGroup(
      'Symbols',
      Icons.tag,
      _split(
          '✅ ❌ ❗ ❓ ‼️ ⁉️ 💯 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟥 🟧 🟨 🟩 🟦 ➕ ➖ ✖️ ➗ ♻️ ⚠️ 🚫 🔞 📵 🔇 🔊 🎵 🎶 '
          '➡️ ⬅️ ⬆️ ⬇️ 🔄 🔝 🆗 ☮️ ☯️ 🕉️ 🔱 ⚛️ 🈶 🅰️ 🆒 🆕 🆓')),
];

/// Space-separated emoji to a list. Kept private: the format is an internal
/// convenience, not part of the picker's contract.
List<String> _split(String emoji) => emoji.split(' ');

/// Open the picker as a bottom sheet, reporting each pick through [onPick]. The
/// returned future completes when the sheet closes.
///
/// It deliberately does *not* close on a pick: choosing three emoji in a row is
/// the common case, so the sheet stays open and reports each one. It closes on
/// the scrim, the drag handle, or the ✓ button.
Future<void> showEmojiPicker(
  BuildContext context, {
  required ValueChanged<String> onPick,
}) =>
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      useSafeArea: true,
      builder: (BuildContext context) => EmojiPickerSheet(onPick: onPick),
    );

class EmojiPickerSheet extends StatefulWidget {
  const EmojiPickerSheet({required this.onPick, super.key});

  final ValueChanged<String> onPick;

  @override
  State<EmojiPickerSheet> createState() => _EmojiPickerSheetState();
}

class _EmojiPickerSheetState extends State<EmojiPickerSheet> {
  int _group = 0;

  @override
  Widget build(BuildContext context) {
    final ColorScheme colors = Theme.of(context).colorScheme;
    final EmojiGroup group = kEmojiGroups[_group];
    return SizedBox(
      height: 340,
      child: Column(
        children: <Widget>[
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Row(
              children: <Widget>[
                Expanded(
                  child: Text(
                    group.label,
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                ),
                IconButton(
                  key: const Key('emoji-close'),
                  tooltip: 'Done',
                  onPressed: () => Navigator.of(context).maybePop(),
                  icon: const Icon(Icons.check),
                ),
              ],
            ),
          ),
          Expanded(
            child: GridView.builder(
              key: ValueKey<int>(_group),
              padding: const EdgeInsets.symmetric(horizontal: 8),
              gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                maxCrossAxisExtent: 44,
              ),
              itemCount: group.emoji.length,
              itemBuilder: (BuildContext context, int i) {
                final String emoji = group.emoji[i];
                return InkWell(
                  key: Key('emoji-$emoji'),
                  borderRadius: BorderRadius.circular(8),
                  onTap: () => widget.onPick(emoji),
                  child: Center(
                    child: Text(emoji, style: const TextStyle(fontSize: 22)),
                  ),
                );
              },
            ),
          ),
          const Divider(height: 1),
          SizedBox(
            height: 52,
            child: ListView.builder(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 4),
              itemCount: kEmojiGroups.length,
              itemBuilder: (BuildContext context, int i) => IconButton(
                key: Key('emoji-group-$i'),
                tooltip: kEmojiGroups[i].label,
                onPressed: () => setState(() => _group = i),
                icon: Icon(
                  kEmojiGroups[i].icon,
                  color: i == _group ? colors.primary : colors.onSurfaceVariant,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Insert [emoji] into [controller] at the cursor (replacing any selection) and
/// leave the caret after it.
///
/// Pulled out and tested because getting it wrong is silently annoying: naive
/// `text += emoji` sends the caret to the end of the message every time, and
/// slicing by UTF-16 offsets is what splits a flag or a skin-toned hand in half.
void insertEmoji(TextEditingController controller, String emoji) {
  final TextEditingValue value = controller.value;
  final TextSelection selection = value.selection;
  if (!selection.isValid) {
    controller.value = TextEditingValue(
      text: value.text + emoji,
      selection: TextSelection.collapsed(offset: (value.text + emoji).length),
    );
    return;
  }
  final String next = value.text.replaceRange(
    selection.start,
    selection.end,
    emoji,
  );
  controller.value = TextEditingValue(
    text: next,
    selection: TextSelection.collapsed(offset: selection.start + emoji.length),
  );
}
