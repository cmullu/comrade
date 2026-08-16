/// Presentation rules for a video journal entry.
///
/// The Kotlin original is
/// `android/app/src/main/java/mullu/comrade/journal/JournalVideos.kt`, and the
/// numbers and cases here are the same ones, pinned by mirrored tests — the
/// discipline `journal_note.dart` already keeps.
///
/// **Only the presentation half is ported.** Recording, the dedicated folder
/// and the orphan sweep are Android's; this frontend shows a video entry it
/// finds in the journal but does not yet capture or play one
/// (`docs/JOURNAL.md`). What lives here is what stops such an entry drawing as
/// a blank card.
library;

/// How long a title may be, matching `JOURNAL_TITLE_MAX_CHARS`.
const int journalTitleMaxChars = 80;

/// A title as it is stored, or `null` for "no title".
///
/// Blank is `null` rather than `''`, so "has a title" is one question with one
/// answer. Newlines collapse to spaces because a heading is one line, and the
/// cut at the cap is hard, with no ellipsis written into the data.
String? journalVideoTitle(String? raw) {
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
String journalVideoHeading(String? title, String dayLabel) =>
    journalVideoTitle(title) ?? dayLabel;

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
