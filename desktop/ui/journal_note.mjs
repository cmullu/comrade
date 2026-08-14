/**
 * Drawing a shared journal note in a chat bubble — the desktop half of
 * `comrade_core::note`.
 *
 * ## Why this is its own module
 *
 * `main.js` is a classic script full of DOM glue and cannot be unit tested, so
 * the part that *decides* something lives here and `node --test` covers it —
 * the split `chat_thread.mjs`, `focus_view.mjs` and `chat_commands.mjs` already
 * use.
 *
 * ## What is *not* here
 *
 * **The marker.** Which messages are notes, and how the mood is split from the
 * text, are `comrade_core::note`'s answers, reached over the Tauri bridge:
 * `MessageDto` *and* `DirectMessageDto` both arrive with `shared_note` already
 * parsed, so the live event and the reloaded thread agree without this file
 * knowing what a marker looks like. A second copy of the grammar is precisely
 * the failure `/pay` demonstrated across four implementations — and the reason
 * `chat_commands.mjs` still carries one for Tara is that her DTO does not do
 * this yet.
 *
 * Mirrors `android/app/src/main/java/mullu/comrade/ui/JournalNote.kt` and
 * `app/lib/src/util/journal_note.dart`: same numbers, same cases, three copies
 * of one test.
 *
 * ## What this window cannot do yet
 *
 * **Send** one. The desktop UI has no journal screen at all
 * (`docs/ATTENTION.md` OQ14), so there is nothing here to share *from*; the
 * `share_journal_entry` command is registered and waiting. Receiving is the
 * half that matters today — a note written on a phone has to be readable on the
 * laptop it is read on, and a bubble that showed the raw marker line would be
 * the whole feature failing at its last step.
 */

/**
 * How many lines of a shared note a bubble draws before offering "show more".
 *
 * A journal entry is written to be read alone, at whatever length the day
 * needed; a chat bubble is read in a scroll of other people's messages. Six
 * lines lets the short entries that are most of them arrive whole, and keeps a
 * long one from pushing the rest of the conversation off screen.
 *
 * Kept equal to `NOTE_PREVIEW_LINES` on Android and in the Flutter app: the
 * same note must not fold differently per frontend.
 */
export const NOTE_PREVIEW_LINES = 6;

/**
 * …and how many characters, for the entry that is one very long paragraph.
 * Without this, {@link NOTE_PREVIEW_LINES} alone would let a single unbroken
 * line of a thousand characters through.
 */
export const NOTE_PREVIEW_CHARS = 280;

/**
 * The collapsed form of a shared note: `{ text, truncated }`.
 *
 * Cuts on a word boundary when one is near the limit, because a note ending
 * mid-word reads as data loss rather than as a fold. `truncated` is what drives
 * the "show more" control — a note that fits must not offer one, since a
 * control that expands nothing says the sender wrote more than they did.
 */
export function notePreview(
  text,
  maxLines = NOTE_PREVIEW_LINES,
  maxChars = NOTE_PREVIEW_CHARS,
) {
  const full = text || "";
  const lines = full.split("\n");
  let cut = lines.length > maxLines ? lines.slice(0, maxLines).join("\n") : full;
  let truncated = cut.length < full.length;
  if (cut.length > maxChars) {
    const hard = cut.slice(0, maxChars);
    const space = hard.lastIndexOf(" ");
    // Only honour a word boundary in the last quarter; a space at character 3
    // would leave one word standing in for a paragraph.
    cut = space > (maxChars * 3) / 4 ? hard.slice(0, space) : hard;
    truncated = true;
  }
  return { text: cut.replace(/\s+$/, ""), truncated };
}

/**
 * The header line over a note card: whose journal it came out of.
 *
 * Two wordings rather than one, because the same line on both sides would be
 * ambiguous exactly where ambiguity is expensive. Attribution, not proof — core
 * reads the marker off text any client could write, so this says what the
 * sending Comrade claims, the same standing Tara's label has.
 */
export function noteHeader(outgoing) {
  return outgoing ? "From your journal" : "From their journal";
}
