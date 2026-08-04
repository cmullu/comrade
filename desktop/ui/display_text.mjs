// Peer-chosen text, on its way to a screen.
//
// Extracted from `handoff_transfer.mjs`, which had the only copy, once a second
// caller appeared: a profile page draws a peer's `name` and `about` at heading
// size, which is the same problem a transfer card's filename has and the same
// answer. One copy, because a character class that two files maintain separately
// is a character class that eventually differs — and the difference would be
// invisible until someone exploited it.
//
// Ports: `app/lib/src/util/display_text.dart`,
// `android/.../ui/DisplayText.kt`. Same cases, same answers.

/**
 * C0/C1 controls, and the bidirectional overrides.
 *
 * The controls because a newline in a "name" turns one row into two lines of
 * forged UI; the overrides because U+202E makes `holiday<RLO>gnp.exe` read as
 * `holiday.png` in every renderer that honours it. Neither is an XSS —
 * everything here goes through `textContent` — and both are lies on a screen,
 * which is enough.
 */
export const UNSAFE_TEXT = /[\u0000-\u001f\u007f-\u009f\u200e\u200f\u202a-\u202e\u2066-\u2069]/g;

/**
 * A peer's free-text field, made safe to *draw*: controls and bidi overrides
 * removed, runs of whitespace collapsed to single spaces, trimmed, and bounded.
 *
 * Whitespace is collapsed rather than preserved because every caller here draws
 * into a fixed row. A "bio" containing forty newlines is not a bio; it is a way
 * to push the rest of a profile off the screen and put whatever you like where
 * the app's own chrome used to be.
 *
 * Returns `""` for nothing usable, so callers decide between an empty row and no
 * row — this function never invents placeholder wording.
 *
 * An unsafe character becomes a **space**, not nothing. Two reasons, and the
 * first is a correctness one: `\n` is itself inside the C0 range, so deleting
 * outright turns "Bob\nSmith" into "BobSmith" and silently invents a name nobody
 * has. The second is that a space leaves the tampering *visible* —
 * `holiday<RLO>gnp.exe` reads as `holiday gnp.exe`, which looks wrong, where
 * deleting produces `holidaygnp.exe`, which looks merely unusual.
 *
 * This matches `boundedCaption`, which already replaced with a space, and
 * deliberately differs from `safeDisplayName`, which strips to nothing. That
 * divergence predates this module and is left alone rather than "tidied": both
 * behaviours are pinned by tests, and a filename is not prose.
 */
export function sanitizeDisplayText(text, maxChars) {
  const stripped = String(text ?? "")
    .replace(UNSAFE_TEXT, " ")
    .replace(/\s+/g, " ")
    .trim();
  const limit = Number(maxChars);
  if (!Number.isFinite(limit) || limit <= 0) return stripped;
  if (stripped.length <= limit) return stripped;
  return `${stripped.slice(0, limit - 1)}…`;
}
