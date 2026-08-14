/// Pure rules for handing a journal note to somebody, and for drawing one in a
/// chat — ported 1:1 from
/// `android/app/src/main/java/mullu/comrade/ui/JournalNote.kt`.
///
/// The wire form and the parse are `comrade_core::note`'s, reached through
/// `MessageInfo.sharedNote`; nothing here re-reads the marker. What is here is
/// the part that is genuinely the frontend's: who may be offered the note, and
/// how much of it a bubble shows before it stops being a bubble.
///
/// Deliberately free of Flutter imports so plain Dart unit tests can pin the
/// rules, exactly as `JournalNoteTest.kt` and `journal_note.test.mjs` do.
library;

import 'display_name.dart';

/// How many lines of a shared note a bubble draws before offering "show more".
///
/// A journal entry is written to be read alone, at whatever length the day
/// needed; a chat bubble is read in a scroll of other people's messages. Six
/// lines lets the short entries that are most of them arrive whole, and keeps a
/// long one from pushing the rest of the conversation off screen.
///
/// Identical to Android's `NOTE_PREVIEW_LINES` and desktop's: the same note
/// must not fold differently per frontend.
const int kNotePreviewLines = 6;

/// …and how many characters, for the entry that is one very long paragraph.
/// Without this, [kNotePreviewLines] alone would let a single unbroken line of
/// a thousand characters through.
const int kNotePreviewChars = 280;

/// What a collapsed note card shows, and whether anything is being withheld.
typedef NotePreview = ({String text, bool truncated});

/// The collapsed form of a shared note.
///
/// Cuts on a word boundary when one is near the limit, because a note ending
/// mid-word reads as data loss rather than as a fold. `truncated` is what
/// drives the "show more" control — a note that fits must not offer one, since
/// a control that expands nothing says the sender wrote more than they did.
NotePreview notePreview(
  String text, {
  int maxLines = kNotePreviewLines,
  int maxChars = kNotePreviewChars,
}) {
  final List<String> lines = text.split('\n');
  String cut = lines.length > maxLines ? lines.take(maxLines).join('\n') : text;
  bool truncated = cut.length < text.length;
  if (cut.length > maxChars) {
    final String hard = cut.substring(0, maxChars);
    final int space = hard.lastIndexOf(' ');
    // Only honour a word boundary in the last quarter; a space at character 3
    // would leave one word standing in for a paragraph.
    cut = space > maxChars * 3 ~/ 4 ? hard.substring(0, space) : hard;
    truncated = true;
  }
  return (text: cut.trimRight(), truncated: truncated);
}

/// The header over a note card: whose journal it came out of.
///
/// Two wordings rather than one, because the same line on both sides would be
/// ambiguous exactly where ambiguity is expensive. Attribution, not proof —
/// core reads the marker off text any client could write, so this says what the
/// sending Comrade claims, the same standing Tara's label has.
String noteHeader({required bool outgoing}) =>
    outgoing ? 'From your journal' : 'From their journal';

/// Someone a note may be handed to, as the picker lists them.
typedef ShareTarget = ({String npub, String label, bool comrade});

/// A saved contact, in the shape [shareTargets] needs — no FFI types here.
typedef ShareCandidate = ({
  String npub,
  String? alias,
  String? name,
  bool comrade,
});

/// Who the journal's share sheet offers, comrades first and each group ordered
/// by the name on screen.
///
/// **Saved contacts only, and that is the point.** Every other send path in the
/// app can reach whoever's thread is open, including a stranger whose request
/// was never accepted. A journal note is the most private thing this app holds,
/// so the list is drawn from people the user deliberately added — the accident
/// this design refuses is the one where the note goes to the wrong name in a
/// long list of strangers.
///
/// Comrades lead because marking someone a comrade is already the "this person
/// may reach me" disclosure, so they are the people most likely to be meant —
/// not because a comrade is entitled to anything here. Labels follow
/// [peerTitle]'s trust order, so a note is addressed to the same name the chat
/// list shows.
List<ShareTarget> shareTargets(List<ShareCandidate> candidates) {
  final List<ShareTarget> targets = candidates
      .map((ShareCandidate c) => (
            npub: c.npub,
            label: peerTitle(c.npub, c.alias, c.name),
            comrade: c.comrade,
          ))
      .toList();
  targets.sort((ShareTarget a, ShareTarget b) {
    if (a.comrade != b.comrade) return a.comrade ? -1 : 1;
    return a.label.toLowerCase().compareTo(b.label.toLowerCase());
  });
  return targets;
}
