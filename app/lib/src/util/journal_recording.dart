/// Presentation rules for a journal recording — a voice entry or a video entry.
///
/// The Kotlin original is
/// `android/app/src/main/java/mullu/comrade/journal/JournalRecordings.kt`, and
/// the numbers and cases here are the same ones, pinned by mirrored tests — the
/// discipline `journal_note.dart` already keeps.
///
/// **Only the presentation half is ported.** Recording, the two dedicated
/// folders and the orphan sweep are Android's; this frontend shows a recording
/// entry it finds in the journal but does not yet capture or play one
/// (`docs/JOURNAL.md`). What lives here is what stops such an entry drawing as
/// a blank card — plus [clipProgress] and [playbackLabel], which nothing here
/// calls yet and which exist so that the day this frontend grows a player, its
/// counter reads identically to Android's rather than being invented again.
library;

/// How long a title may be, matching `JOURNAL_TITLE_MAX_CHARS`.
const int journalTitleMaxChars = 80;

/// A title as it is stored, or `null` for "no title".
///
/// Blank is `null` rather than `''`, so "has a title" is one question with one
/// answer. Newlines collapse to spaces because a heading is one line, and the
/// cut at the cap is hard, with no ellipsis written into the data.
String? journalRecordingTitle(String? raw) {
  if (raw == null) {
    return null;
  }
  final String collapsed = raw.replaceAll(RegExp(r'\s+'), ' ').trim();
  if (collapsed.isEmpty) {
    return null;
  }
  return collapsed.length <= journalTitleMaxChars
      ? collapsed
      : collapsed.substring(0, journalTitleMaxChars).trimRight();
}

/// What an unnamed recording's card is headed with: the day it was taken.
///
/// A recording always has something to be called — a card headed with nothing
/// looks broken — and the day is the one fact about it that is never a guess.
String journalRecordingHeading(String? title, String dayLabel) =>
    journalRecordingTitle(title) ?? dayLabel;

/// A clip's length as `m:ss`, or `h:mm:ss` past an hour.
///
/// Empty for a non-positive duration, which is how the core spells "could not
/// read one". `0:00` would claim the recording is empty, which is a different
/// and wrong statement about someone's file.
String formatClipLength(int durationMs) {
  if (durationMs <= 0) {
    return '';
  }
  final int totalSeconds = durationMs ~/ 1000;
  final int seconds = totalSeconds % 60;
  final int minutes = (totalSeconds ~/ 60) % 60;
  final int hours = totalSeconds ~/ 3600;
  final String ss = seconds.toString().padLeft(2, '0');
  if (hours > 0) {
    return '$hours:${minutes.toString().padLeft(2, '0')}:$ss';
  }
  return '$minutes:$ss';
}

/// A file size the way a file manager on the same phone would say it —
/// `840 KB`, `12.3 MB`, `1.1 GB`. Empty for a non-positive size.
String formatClipSize(int bytes) {
  if (bytes <= 0) {
    return '';
  }
  const double kb = 1024;
  const double mb = kb * 1024;
  const double gb = mb * 1024;
  if (bytes >= gb) {
    return '${_trimDecimal(bytes / gb)} GB';
  }
  if (bytes >= mb) {
    return '${_trimDecimal(bytes / mb)} MB';
  }
  if (bytes >= kb) {
    return '${(bytes / kb).round()} KB';
  }
  return '$bytes B';
}

/// One decimal place, with a trailing `.0` dropped — `12.3`, but `12`.
String _trimDecimal(double value) {
  final double rounded = (value * 10).round() / 10;
  return rounded == rounded.roundToDouble()
      ? rounded.toInt().toString()
      : rounded.toString();
}

/// How far through a clip playback is, as `0..1` for a progress bar.
///
/// Zero for an unknown or absent duration rather than a divide by zero, and
/// clamped at both ends: a player can report a position past the duration it
/// reported earlier, and a slider handed 1.04 is a slider drawn outside its
/// track.
double clipProgress(int positionMs, int durationMs) {
  if (durationMs <= 0) {
    return 0;
  }
  return (positionMs / durationMs).clamp(0.0, 1.0);
}

/// The counter under a player: `0:07 / 0:23`, or just `0:07` when the total is
/// unknown.
///
/// Dropping the total rather than showing `0:07 / 0:00` is the same rule
/// [formatClipLength] follows — a clip whose container will not say how long it
/// is still plays, and a zero total would say it has already ended. The
/// position is clamped to the duration, because a player that reports 0:24 of a
/// 0:23 clip is a player the user reads as broken.
String playbackLabel(int positionMs, int durationMs) {
  final int safePosition = positionMs < 0 ? 0 : positionMs;
  if (durationMs <= 0) {
    final String elapsed = formatClipLength(safePosition);
    return elapsed.isEmpty ? '0:00' : elapsed;
  }
  final int shown = safePosition > durationMs ? durationMs : safePosition;
  final String elapsed = shown <= 0 ? '0:00' : formatClipLength(shown);
  return '$elapsed / ${formatClipLength(durationMs)}';
}
