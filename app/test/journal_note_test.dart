import 'package:comrade/src/util/display_name.dart';
import 'package:comrade/src/util/journal_note.dart';
import 'package:flutter_test/flutter_test.dart';

/// Mirrors `android/.../ui/JournalNoteTest.kt` and
/// `desktop/ui/journal_note.test.mjs` — same cases, same numbers.
void main() {
  group('the collapsed card', () {
    test('a short note arrives whole and offers nothing to expand', () {
      const String note = 'rough morning, but I went for the walk';
      final NotePreview preview = notePreview(note);
      expect(preview.text, note);
      expect(preview.truncated, isFalse);
    });

    test('a long note is folded at the line limit', () {
      final String note =
          List<String>.generate(10, (int i) => 'line ${i + 1}').join('\n');
      final NotePreview preview = notePreview(note);
      expect(preview.text.split('\n').length, kNotePreviewLines);
      expect(preview.truncated, isTrue);
      expect(preview.text.startsWith('line 1'), isTrue);
    });

    // The line limit alone would let this through whole: it is one line.
    test('one very long paragraph is folded too', () {
      final NotePreview preview = notePreview(('word ' * 200).trim());
      expect(preview.truncated, isTrue);
      expect(preview.text.length, lessThanOrEqualTo(kNotePreviewChars));
    });

    test('the fold falls on a word boundary', () {
      final NotePreview preview =
          notePreview(('supercalifragilistic ' * 40).trim());
      expect(preview.text.endsWith('superc'), isFalse);
      expect(preview.text.split(' ').last, isNotEmpty);
    });

    // A "show more" that expands nothing tells the reader words are being
    // withheld when none are.
    test('a note that fits exactly is not called truncated', () {
      final String note = 'x' * kNotePreviewChars;
      final NotePreview preview = notePreview(note);
      expect(preview.text, note);
      expect(preview.truncated, isFalse);
    });

    // No usable boundary: a hard cut is right, and one word must never stand in
    // for the whole note.
    test('a single unbroken word is cut rather than shown whole', () {
      final NotePreview preview = notePreview('x' * (kNotePreviewChars * 2));
      expect(preview.text.length, kNotePreviewChars);
      expect(preview.truncated, isTrue);
    });
  });

  group('who may be offered the note', () {
    ShareCandidate candidate(String npub,
            {String? alias, bool comrade = false}) =>
        (npub: npub, alias: alias, name: null, comrade: comrade);

    test('comrades lead and each group is alphabetical', () {
      final List<ShareTarget> targets = shareTargets(<ShareCandidate>[
        candidate('npub1zoe', alias: 'Zoe'),
        candidate('npub1ana', alias: 'Ana'),
        candidate('npub1bo', alias: 'Bo', comrade: true),
        candidate('npub1mira', alias: 'mira', comrade: true),
      ]);
      expect(targets.map((ShareTarget t) => t.label).toList(),
          <String>['Bo', 'mira', 'Ana', 'Zoe']);
      expect(targets.map((ShareTarget t) => t.comrade).toList(),
          <bool>[true, true, false, false]);
    });

    // Your alias first, then their self-declared handle, then the key —
    // `peerTitle`'s rule, so a note is addressed to the name the chat list
    // shows.
    test('labels follow the same trust order as every other screen', () {
      const String npub = 'npub1abcdefghijklmnopqrstuvwxyz';
      expect(
        shareTargets(<ShareCandidate>[
          (npub: npub, alias: 'Ana', name: 'anahandle', comrade: false)
        ]).single.label,
        'Ana',
      );
      expect(
        shareTargets(<ShareCandidate>[
          (npub: npub, alias: null, name: 'anahandle', comrade: false)
        ]).single.label,
        '@anahandle',
      );
      expect(
        shareTargets(<ShareCandidate>[
          (npub: npub, alias: null, name: null, comrade: false)
        ]).single.label,
        shortNpub(npub),
      );
    });

    // The picker is exactly the saved contacts — a stranger is not offered
    // here, and a contact is never quietly missing from a list you are about to
    // disclose to.
    test('nobody is invented and nobody is dropped', () {
      expect(shareTargets(const <ShareCandidate>[]), isEmpty);
      final List<ShareTarget> two = shareTargets(<ShareCandidate>[
        candidate('npub1a', alias: 'A'),
        candidate('npub1b', alias: 'B'),
      ]);
      expect(two.map((ShareTarget t) => t.npub).toList(),
          <String>['npub1a', 'npub1b']);
    });
  });

  group('the header', () {
    // The same line on both sides would be ambiguous exactly where ambiguity is
    // expensive: whose journal this came out of.
    test('says whose journal it was', () {
      expect(noteHeader(outgoing: true), 'From your journal');
      expect(noteHeader(outgoing: false), 'From their journal');
    });
  });
}
