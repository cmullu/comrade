/**
 * Pure chat-thread rules the desktop UI shares with the other two frontends.
 *
 * Mirrors `android/app/src/main/java/mullu/comrade/ui/ChatThread.kt` and
 * `app/lib/src/util/chat_thread.dart` — same cases, same answers, pinned by
 * three copies of one test.
 */

/**
 * The id of the message a quote should scroll to, or null when there is nothing
 * to scroll to.
 *
 * `null` is the common case, not an edge case: a quote renders from whatever
 * history is loaded in the open thread, and replying to something older leaves a
 * perfectly good quote pointing at a message that is not on screen. The caller
 * must treat that as "no destination" and leave the thread where it is —
 * scrolling somewhere arbitrary would be worse than not moving.
 *
 * Takes the thread as rendered so the answer addresses the DOM the caller is
 * about to search. A message with no id (not yet confirmed by a relay) can
 * neither be quoted nor jumped to, and is skipped rather than matched on
 * undefined.
 */
export function quoteScrollTargetId(msgs, replyToId) {
  if (!replyToId) return null;
  const found = (msgs || []).find((m) => m && m.id && m.id === replyToId);
  return found ? found.id : null;
}

/**
 * How long the jumped-to message stays highlighted, in milliseconds.
 *
 * A scroll on its own does not say *which* message it landed on, and a reply
 * usually quotes something surrounded by other messages — so the flash is what
 * makes the jump legible rather than disorienting. Long enough to catch the eye
 * after the scroll settles, short enough not to read as selection.
 *
 * Kept equal to `QUOTE_HIGHLIGHT_MS` on Android and `quoteHighlightDuration` in
 * the Flutter app: the same gesture must not feel different per frontend.
 */
export const QUOTE_HIGHLIGHT_MS = 1400;
