/**
 * What the composer has to tell the core about an unsent draft — the desktop
 * half of `comrade_core::nudge`.
 *
 * Pure and DOM-free so it can be tested (`draft_reports.test.mjs`), following
 * the same rule `call_decisions.mjs` and `media_cache.mjs` follow: the decision
 * lives here, and `main.js` only performs it. Two of the three cases below are
 * exactly the kind of "couple of lines in the event handler" that used to go
 * untested — and one of them is a re-attribution that is easy to get backwards.
 *
 * What crosses the bridge is only ever *which* of the two things happened.
 * Never the text.
 */

/** There is unsent text in this composer, as of now. */
export const NOTE = "note_draft";

/** It is gone — emptied, or left behind. */
export const ABANDON = "abandon_draft";

/**
 * What an edit to `peer`'s composer should report, given what the box now
 * holds. Whitespace-only counts as empty, because it could not be sent either.
 *
 * Returns a list (rather than one command) so every caller performs reports the
 * same way, including [switchReports]'s two.
 */
export function editReports(peer, text) {
  if (!peer) return [];
  return [{ command: String(text ?? "").trim() ? NOTE : ABANDON, peer }];
}

/**
 * What switching the open conversation from `from` to `to` should report.
 *
 * Two things happen at once, and the order matters. Leaving a conversation
 * abandons its draft **whatever the box currently holds** — that is not the
 * same question `editReports` asks, and using `editReports` here would report
 * "still writing" to the peer being walked away from. Meanwhile this composer
 * keeps its text across a switch, so whatever is left in it is now the *new*
 * peer's draft and is re-attributed.
 */
export function switchReports(from, to, text) {
  const reports = [];
  if (from && from !== to) reports.push({ command: ABANDON, peer: from });
  reports.push(...editReports(to, text));
  return reports;
}
