/// Pure thread-and-topic rules — ported from
/// `android/app/src/main/java/mullu/comrade/topic/TopicDecisions.kt` and
/// `desktop/ui/topics.mjs`, with the same cases and the same answers, pinned by
/// a third copy of one test.
///
/// Kept free of Flutter imports so plain Dart unit tests can pin them, exactly
/// as `chat_thread.dart` does.
///
/// **What is not here:** the slug rules, the reply graph, and which topic a
/// thread is in. Those are `comrade_core::topic`'s answers, reached over the
/// bridge. Nothing in this file re-derives a slug or walks a reply chain — a
/// second implementation of either is the drift `/pay` already suffered across
/// four frontends.
library;

import '../data/models.dart';

/// Topics in the order a list should show them: liveliest first, archived last.
///
/// Not creation order, which is what the store returns. A conversation
/// accumulates topics and the one that just moved is the one being worked on;
/// sorting by name would pin `#a-thing-from-march` above it forever. Archived
/// topics sink rather than vanish when [includeClosed] is set, so the archive
/// reads as an archive rather than as an empty list.
List<TopicInfo> visibleTopics(
  List<TopicInfo> topics, {
  bool includeClosed = false,
}) {
  final List<TopicInfo> rows =
      topics.where((TopicInfo t) => includeClosed || !t.closed).toList()
        ..sort((TopicInfo a, TopicInfo b) {
          if (a.closed != b.closed) return a.closed ? 1 : -1;
          return b.lastActivityAt.compareTo(a.lastActivityAt);
        });
  return rows;
}

/// Which slice of threads a sheet is showing.
///
/// [ThreadFilter.unfiled] is a real destination rather than the absence of a
/// filter: "what have I not put anywhere" is a question people ask of a filing
/// system, and expressing it as a null slug would make it indistinguishable
/// from "everything".
enum ThreadFilterKind { all, unfiled, inTopic }

class ThreadFilter {
  const ThreadFilter._(this.kind, this.slug);

  const ThreadFilter.all() : this._(ThreadFilterKind.all, null);
  const ThreadFilter.unfiled() : this._(ThreadFilterKind.unfiled, null);
  const ThreadFilter.inTopic(String slug)
      : this._(ThreadFilterKind.inTopic, slug);

  final ThreadFilterKind kind;
  final String? slug;

  @override
  bool operator ==(Object other) =>
      other is ThreadFilter && other.kind == kind && other.slug == slug;

  @override
  int get hashCode => Object.hash(kind, slug);
}

/// The threads a sheet should list, most recently active first.
///
/// [includeSingletons] defaults to false because every message is technically a
/// thread of one, and listing them would make the sheet a second copy of the
/// conversation — the same screen with worse ordering. Slack draws the same
/// line. It is a parameter rather than a constant because a thread has to be
/// *startable*, and a thread that does not exist yet has no replies.
List<ThreadInfo> threadsFor(
  List<ThreadInfo> threads, {
  ThreadFilter filter = const ThreadFilter.all(),
  bool includeSingletons = false,
}) {
  final List<ThreadInfo> rows = threads
      .where((ThreadInfo t) {
        switch (filter.kind) {
          case ThreadFilterKind.all:
            return true;
          case ThreadFilterKind.unfiled:
            return t.topicSlug == null;
          case ThreadFilterKind.inTopic:
            return t.topicSlug == filter.slug;
        }
      })
      .where((ThreadInfo t) => includeSingletons || t.replyCount > 0)
      .toList()
    ..sort((ThreadInfo a, ThreadInfo b) {
      final int byTime = b.lastAt.compareTo(a.lastAt);
      return byTime != 0 ? byTime : a.rootId.compareTo(b.rootId);
    });
  return rows;
}

/// How many of a topic's threads have something unread in them.
///
/// Reads the threads rather than the topic row because unread is per thread and
/// a topic's own counts are about size, not attention — forty read messages and
/// one new reply is a 1.
int unreadThreadCount(List<ThreadInfo> threads, [String? topicSlug]) => threads
    .where((ThreadInfo t) =>
        t.unread && (topicSlug == null || t.topicSlug == topicSlug))
    .length;

/// Which of five things a thread row has to say, so the *words* stay in the
/// widget where they can be localised.
///
/// Core hands up an empty preview for an uncaptioned attachment and for a root
/// that is not in this device's history, plus two booleans saying which.
/// Returning a sentence from here would push English out of a decision
/// function — the same split the offer envelopes make between the wire and the
/// sentence.
enum PreviewKind {
  /// Show [ThreadInfo.preview] as it is.
  text,

  /// An attachment with a caption: the caption, marked as an attachment.
  captionedMedia,

  /// An attachment with no caption: the frontend's own word for one.
  bareMedia,

  /// The root is not on this device. A normal state, not an error: a thread can
  /// be filed before its root arrives, and a reply can quote something older
  /// than the loaded window.
  rootMissing,

  /// A text message with nothing renderable in it.
  empty,
}

/// [PreviewKind.rootMissing] wins over an empty preview, because both are true
/// at once for a filing that outran its thread — and "not on this device" is a
/// fact where "(no text)" reads as a bug.
PreviewKind previewKind(ThreadInfo row) {
  if (row.rootMissing) return PreviewKind.rootMissing;
  if (row.preview.trim().isEmpty) {
    return row.rootIsMedia ? PreviewKind.bareMedia : PreviewKind.empty;
  }
  return row.rootIsMedia ? PreviewKind.captionedMedia : PreviewKind.text;
}

/// What `/assign #topic` should do, given what the composer has selected.
///
/// Three answers, and the middle one is why this is a function:
///
/// - No topic named → open the picker. A bare `/assign` is the discoverable way
///   in, not a refusal.
/// - A topic named but nothing selected → say what is missing and keep the
///   text. The grammar cannot know which thread was meant, and guessing would
///   file the wrong conversation.
/// - Both → file it.
///
/// [replyTarget] is the event id of whatever the composer is replying to. Any
/// message in the thread will do — core walks up to the root.
enum AssignPlanKind { openPicker, needsTarget, file }

class AssignPlan {
  const AssignPlan(this.kind, {this.slug, this.messageId});

  final AssignPlanKind kind;
  final String? slug;
  final String? messageId;

  @override
  bool operator ==(Object other) =>
      other is AssignPlan &&
      other.kind == kind &&
      other.slug == slug &&
      other.messageId == messageId;

  @override
  int get hashCode => Object.hash(kind, slug, messageId);
}

AssignPlan planAssign(List<String> slugs, String? replyTarget) {
  final String slug = slugs.firstWhere(
    (String s) => s.trim().isNotEmpty,
    orElse: () => '',
  );
  if (slug.isEmpty) return const AssignPlan(AssignPlanKind.openPicker);
  if (replyTarget == null || replyTarget.isEmpty) {
    return AssignPlan(AssignPlanKind.needsTarget, slug: slug);
  }
  return AssignPlan(AssignPlanKind.file, slug: slug, messageId: replyTarget);
}
