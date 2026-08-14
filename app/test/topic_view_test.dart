/// The thread-and-topic decision vectors.
///
/// Deliberately the same cases as
/// `android/app/src/test/java/mullu/comrade/topic/TopicDecisionsTest.kt` and
/// `desktop/ui/topics.test.mjs`, with the same inputs, so the three frontends
/// drifting apart is a red test rather than a field bug — the remedy
/// `docs/COMMS_ARCHITECTURE.md` ADR-2 prescribes.
library;

import 'package:comrade/src/data/models.dart';
import 'package:comrade/src/util/topic_view.dart';
import 'package:flutter_test/flutter_test.dart';

TopicInfo topic(
  String slug, {
  bool closed = false,
  int lastActivityAt = 100,
}) =>
    TopicInfo(
      slug: slug,
      name: slug,
      closed: closed,
      threadCount: 1,
      messageCount: 3,
      lastActivityAt: lastActivityAt,
    );

ThreadInfo thread(
  String rootId, {
  String? topicSlug,
  String preview = "the deposit still hasn't come back",
  bool rootIsMedia = false,
  bool rootMissing = false,
  int replyCount = 2,
  int lastAt = 200,
  bool unread = false,
}) =>
    ThreadInfo(
      rootId: rootId,
      topicSlug: topicSlug,
      preview: preview,
      rootIsMedia: rootIsMedia,
      rootMissing: rootMissing,
      replyCount: replyCount,
      lastAt: lastAt,
      unread: unread,
    );

void main() {
  test('topics list liveliest first, archived last', () {
    final List<TopicInfo> rows = visibleTopics(
      <TopicInfo>[
        topic('old', lastActivityAt: 10),
        topic('archived', closed: true, lastActivityAt: 999),
        topic('live', lastActivityAt: 500),
      ],
      includeClosed: true,
    );
    expect(
      rows.map((TopicInfo t) => t.slug),
      <String>['live', 'old', 'archived'],
      reason: 'an archive that outranks live topics is not an archive',
    );
  });

  test('archived topics are out of the picker by default', () {
    final List<TopicInfo> rows = visibleTopics(
      <TopicInfo>[topic('live'), topic('gone', closed: true)],
    );
    expect(rows.map((TopicInfo t) => t.slug), <String>['live']);
  });

  test('threads with no replies are hidden unless asked for', () {
    final List<ThreadInfo> rows = <ThreadInfo>[
      thread('a'),
      thread('b', replyCount: 0),
    ];
    expect(
      threadsFor(rows).map((ThreadInfo t) => t.rootId),
      <String>['a'],
      reason:
          'listing every message would make the sheet a worse copy of the chat',
    );
    expect(
      threadsFor(rows, includeSingletons: true).length,
      2,
      reason: 'a thread has to be startable from the sheet',
    );
  });

  test('a topic filter is exact, and unfiled is asked for by name', () {
    final List<ThreadInfo> rows = <ThreadInfo>[
      thread('a', topicSlug: 'deposit'),
      thread('b', topicSlug: 'repairs'),
      thread('c'),
    ];
    expect(
      threadsFor(rows, filter: const ThreadFilter.inTopic('deposit'))
          .map((ThreadInfo t) => t.rootId),
      <String>['a'],
    );
    expect(
      threadsFor(rows, filter: const ThreadFilter.unfiled())
          .map((ThreadInfo t) => t.rootId),
      <String>['c'],
    );
    expect(threadsFor(rows, filter: const ThreadFilter.all()).length, 3);
  });

  test('threads sort by last activity, newest first', () {
    final List<ThreadInfo> rows = threadsFor(<ThreadInfo>[
      thread('a', lastAt: 100),
      thread('c', lastAt: 300),
      thread('b', lastAt: 200),
    ]);
    expect(rows.map((ThreadInfo t) => t.rootId), <String>['c', 'b', 'a']);
  });

  test('the preview names what core deliberately left unworded', () {
    expect(previewKind(thread('a')), PreviewKind.text);
    expect(
      previewKind(thread('a', preview: '', rootIsMedia: true)),
      PreviewKind.bareMedia,
    );
    expect(
      previewKind(thread('a', preview: 'sunset.jpg', rootIsMedia: true)),
      PreviewKind.captionedMedia,
    );
    expect(
        previewKind(thread('a', rootMissing: true)), PreviewKind.rootMissing);
    expect(previewKind(thread('a', preview: '  ')), PreviewKind.empty);
  });

  test('a missing root wins over an empty preview', () {
    // Both are true at once for a filing that arrived before its thread, and
    // "(no text)" would read as a bug where "not on this device" is a fact.
    expect(
      previewKind(thread('a', preview: '', rootMissing: true)),
      PreviewKind.rootMissing,
    );
  });

  test('unread counts threads, not messages', () {
    final List<ThreadInfo> rows = <ThreadInfo>[
      thread('a', topicSlug: 'deposit', unread: true),
      thread('b', topicSlug: 'deposit'),
      thread('c', topicSlug: 'repairs', unread: true),
    ];
    expect(unreadThreadCount(rows, 'deposit'), 1);
    expect(unreadThreadCount(rows), 2);
  });

  test('a bare /assign opens the picker rather than refusing', () {
    expect(
      planAssign(const <String>[], 'msg1'),
      const AssignPlan(AssignPlanKind.openPicker),
    );
    expect(
      planAssign(const <String>[], null),
      const AssignPlan(AssignPlanKind.openPicker),
    );
  });

  test('/assign with a topic and nothing selected says what is missing', () {
    final AssignPlan plan = planAssign(const <String>['deposit'], null);
    expect(plan.kind, AssignPlanKind.needsTarget);
    expect(
      plan.slug,
      'deposit',
      reason: 'the sentence has to be able to name the topic they typed',
    );
  });

  test('/assign with a topic and a selection files that thread', () {
    expect(
      planAssign(const <String>['deposit', 'second'], 'msg1'),
      const AssignPlan(AssignPlanKind.file, slug: 'deposit', messageId: 'msg1'),
    );
  });
}
