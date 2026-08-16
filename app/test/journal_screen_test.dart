/// How a video journal entry draws in a frontend that cannot play one.
///
/// Recording, the dedicated folder that keeps a clip out of the gallery, and
/// the orphan sweep are Android's (`docs/JOURNAL.md`). What has to be right
/// here is that an entry made over there is still *legible* over here: a title,
/// a clip line, and — because sharing sends words and a recording has none —
/// no share control offering a send the core would refuse.
library;

import 'package:comrade/src/data/fake_comrade_repository.dart';
import 'package:comrade/src/screens/journal_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'helpers.dart';

void main() {
  late FakeComradeRepository repo;

  Future<void> openJournal(WidgetTester tester) async {
    // Tall enough for every seeded entry to be built: the list is lazy, and
    // the video entry is the oldest, so a default-sized window would leave it
    // unbuilt and every assertion below would pass or fail for the wrong
    // reason.
    setWindowSize(tester, const Size(800, 2400));
    repo = await unlockedFake();
    await tester.pumpWidget(harness(const JournalScreen(), repo: repo));
    await tester.pumpAndSettle();
  }

  testWidgets('a video entry shows its title and how long it runs',
      (WidgetTester tester) async {
    await openJournal(tester);

    expect(find.text('The walk after the argument'), findsOneWidget);
    // Length and size, formatted by the same rules Android uses.
    expect(
      find.textContaining('0:47'),
      findsOneWidget,
      reason: 'the clip length should be on the card',
    );
    expect(find.textContaining('12.3 MB'), findsOneWidget);
    // And it says where the footage actually is, rather than offering a play
    // control this frontend cannot honour.
    expect(
        find.textContaining('on the phone that recorded it'), findsOneWidget);
    expect(find.byKey(const Key('journal-video-line')), findsOneWidget);
  });

  testWidgets('an entry with no words offers no way to share it',
      (WidgetTester tester) async {
    await openJournal(tester);

    // Two seeded entries have text; the video entry has none. A share control
    // on that third card would open a picker for a send the core refuses.
    expect(find.byKey(const Key('journal-share')), findsNWidgets(2));
  });

  testWidgets('a typed entry is unaffected — no heading, still shareable',
      (WidgetTester tester) async {
    await openJournal(tester);

    expect(
        find.text('Slept badly, but the morning walk helped.'), findsOneWidget);
    // Titles are opt-in: the entries that never had one draw no heading at
    // all rather than a placeholder.
    expect(find.byKey(const Key('journal-entry-title')), findsOneWidget);
  });
}
