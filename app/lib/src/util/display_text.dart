/// Peer-chosen text, on its way to a screen.
///
/// The Dart port of `desktop/ui/display_text.mjs`; the Kotlin mirror is
/// `android/.../ui/DisplayText.kt`. All three are pinned by mirrored tests, so a
/// change here is a change in three places.
library;

/// C0/C1 controls, and the bidirectional overrides.
///
/// The controls because a newline in a "name" turns one row into two lines of
/// forged UI; the overrides because U+202E makes `holiday<RLO>gnp.exe` read as
/// `holiday.png` in every renderer that honours it. Neither is an injection —
/// Flutter draws text, it does not interpret it — and both are lies on a screen,
/// which is enough.
bool _isUnsafeDisplayChar(int n) =>
    n <= 0x1F ||
    (n >= 0x7F && n <= 0x9F) ||
    n == 0x200E ||
    n == 0x200F ||
    (n >= 0x202A && n <= 0x202E) ||
    (n >= 0x2066 && n <= 0x2069);

final RegExp _whitespaceRun = RegExp(r'\s+');

/// A peer's free-text field, made safe to *draw*: controls and bidi overrides
/// removed, runs of whitespace collapsed to single spaces, trimmed, and bounded.
///
/// Whitespace is collapsed rather than preserved because every caller here draws
/// into a fixed row. A "bio" containing forty newlines is not a bio; it is a way
/// to push the rest of a profile off the screen and put whatever you like where
/// the app's own chrome used to be.
///
/// An unsafe character becomes a **space**, not nothing. Two reasons, and the
/// first is a correctness one: `\n` is itself a control, so deleting outright
/// turns "Bob\nSmith" into "BobSmith" and silently invents a name nobody has. The
/// second is that a space leaves the tampering *visible* —
/// `holiday<RLO>gnp.exe` reads as `holiday gnp.exe`, which looks wrong, where
/// deleting produces `holidaygnp.exe`, which looks merely unusual.
///
/// Returns `''` for nothing usable, so callers decide between an empty row and no
/// row — this function never invents placeholder wording.
///
/// [maxChars] zero or negative means no bound.
String sanitizeDisplayText(String? text, int maxChars) {
  final stripped = String.fromCharCodes(
    (text ?? '').runes.map((r) => _isUnsafeDisplayChar(r) ? 0x20 : r),
  ).replaceAll(_whitespaceRun, ' ').trim();
  if (maxChars <= 0 || stripped.length <= maxChars) return stripped;
  return '${stripped.substring(0, maxChars - 1)}…';
}
