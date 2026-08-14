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
 * Whether the reader is close enough to the newest message that a rebuild of
 * the thread should land them at the bottom again.
 *
 * Someone scrolled up reading history must NOT be yanked down. On desktop that
 * is not only about new mail: the thread is rebuilt for a delivery tick or a
 * peer rename too, so without this rule an event that added nothing to read
 * still drags the reader to the newest line.
 *
 * The pixel equivalent of `isNearBottom` on Android and `isNearBottomByOffset`
 * in the Flutter app — a scrollable's travel is `scrollHeight - clientHeight`,
 * which is what Flutter calls `maxScrollExtent`, and [slackPixels] is the same
 * 220 those carry. A log with nothing to scroll (not yet laid out, or shorter
 * than its box) counts as at the bottom, matching their `totalCount <= 0`.
 */
export function isNearBottom({
  scrollTop,
  scrollHeight,
  clientHeight,
  slackPixels = 220,
}) {
  const travel = (scrollHeight || 0) - (clientHeight || 0);
  if (travel <= 0) return true;
  return (scrollTop || 0) >= travel - slackPixels;
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
