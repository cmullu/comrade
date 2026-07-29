/// Tara's streaming-vs-crisis distinction — the single most important
/// behaviour on that screen.
///
/// An ordinary reply is *paced out* so the companion reads as thinking out
/// loud. A crisis hand-off is **never** paced: helpline numbers appear
/// complete, at once, the moment they are known. Half a phone number on screen
/// while an animation catches up is not a cosmetic bug.
library;

import 'package:comrade/src/data/fake_comrade_repository.dart';
import 'package:comrade/src/data/models.dart';
import 'package:comrade/src/screens/tara_screen.dart';
import 'package:comrade/src/state/tara_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'helpers.dart';

void main() {
  group('TaraController', () {
    late FakeComradeRepository repo;
    late ProviderContainer container;

    /// Every value `state.streaming?.text` took, in order.
    late List<String> streamedFrames;

    Future<void> boot() async {
      repo = await unlockedFake();
      container = testContainer(repo);
      streamedFrames = <String>[];
      container.listen<TaraState>(
        taraProvider,
        (TaraState? previous, TaraState next) {
          final StreamingReply? s = next.streaming;
          if (s != null) streamedFrames.add(s.text);
        },
        fireImmediately: false,
      );
      container.read(taraProvider.notifier).chunkDelay = Duration.zero;
      // Let the controller's initial load settle.
      await Future<void>.delayed(Duration.zero);
    }

    test('an ordinary reply is streamed as growing prefixes', () async {
      await boot();
      await container.read(taraProvider.notifier).send('I feel anxious today');

      // The first frame is the empty placeholder that replaces the thinking
      // indicator; the rest grow.
      final List<String> nonEmpty =
          streamedFrames.where((String f) => f.isNotEmpty).toList();
      expect(nonEmpty.length, greaterThan(1),
          reason: 'a paced reply must publish more than one frame');
      for (var i = 1; i < nonEmpty.length; i++) {
        expect(nonEmpty[i].startsWith(nonEmpty[i - 1]), isTrue,
            reason: 'each frame extends the previous one');
      }

      final List<TaraMessageInfo> thread = await repo.taraThread();
      expect(nonEmpty.last, thread.last.text,
          reason: 'the final frame is the whole reply');
      expect(thread.last.crisis, isFalse);
    });

    test('a crisis reply is NEVER streamed — one frame, complete', () async {
      await boot();
      await container
          .read(taraProvider.notifier)
          .send('honestly I want to end it all');

      final List<TaraMessageInfo> thread = await repo.taraThread();
      final TaraMessageInfo reply = thread.last;
      expect(reply.crisis, isTrue,
          reason: 'the fixture must trip the detector');

      // The controller may republish the same state more than once as the
      // turn settles (the reload re-reads the thread while `streaming` is
      // still set). What must never happen is a *different* frame — i.e. a
      // partial one. So: exactly one distinct non-empty value, and it is the
      // whole reply.
      final Set<String> distinct =
          streamedFrames.where((String f) => f.isNotEmpty).toSet();
      expect(distinct, hasLength(1),
          reason: 'a crisis hand-off must never publish a partial reply');
      expect(distinct.single, reply.text,
          reason: 'and the one frame it does publish is the complete reply');

      // Belt and braces: no frame was ever a strict prefix of the reply.
      for (final String frame in streamedFrames) {
        expect(
          frame.isEmpty || frame == reply.text,
          isTrue,
          reason: 'a partial crisis reply ($frame) must never be published',
        );
      }
    });

    test('the send is rejected while a turn is already in flight', () async {
      await boot();
      final Future<void> first =
          container.read(taraProvider.notifier).send('first message');
      // The controller is busy from the synchronous part of send().
      expect(container.read(taraProvider).busy, isTrue);
      await container.read(taraProvider.notifier).send('second message');
      await first;
      final List<TaraMessageInfo> thread = await repo.taraThread();
      expect(thread.where((TaraMessageInfo m) => !m.fromTara).length, 1);
    });
  });

  group('TaraScreen', () {
    testWidgets('shows the opt-in explainer before anything else',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      await tester.pumpWidget(harness(const TaraScreen(), repo: repo));
      await tester.pumpAndSettle();

      expect(find.text('Meet Tara'), findsOneWidget);
      expect(
        find.textContaining('not a therapist, doctor, or crisis service'),
        findsOneWidget,
      );
      expect(find.byKey(const Key('tara-input')), findsNothing);
    });

    testWidgets('accepting the explainer reveals the thread',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      await tester.pumpWidget(harness(const TaraScreen(), repo: repo));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('tara-accept')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('tara-input')), findsOneWidget);
      expect(
        find.textContaining('Not a therapist or crisis service'),
        findsOneWidget,
      );
    });

    testWidgets('a crisis turn renders the whole reply plus the crisis card',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      await tester.pumpWidget(
        harness(
          const TaraScreen(),
          repo: repo,
          prefs: <String, bool>{'tara.accepted': true},
        ),
      );
      await tester.pumpAndSettle();

      await tester.enterText(
        find.byKey(const Key('tara-input')),
        'I want to die',
      );
      await tester.tap(find.byKey(const Key('tara-send')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('tara-crisis-card')), findsOneWidget);
      expect(find.textContaining('Tele-MANAS'), findsOneWidget);
      expect(find.textContaining('14416'), findsOneWidget);

      final List<TaraMessageInfo> thread = await repo.taraThread();
      expect(find.text(thread.last.text), findsOneWidget,
          reason: 'the reply is on screen in full');
    });

    testWidgets('an ordinary turn shows no crisis card',
        (WidgetTester tester) async {
      final FakeComradeRepository repo = await unlockedFake();
      await tester.pumpWidget(
        harness(
          const TaraScreen(),
          repo: repo,
          prefs: <String, bool>{'tara.accepted': true},
        ),
      );
      await tester.pumpAndSettle();

      await tester.enterText(
        find.byKey(const Key('tara-input')),
        'thinking about a job change',
      );
      await tester.tap(find.byKey(const Key('tara-send')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('tara-crisis-card')), findsNothing);
      expect(find.byKey(const Key('tara-clear')), findsOneWidget,
          reason: 'a non-empty thread offers Clear');
    });
  });
}
