/// Threads and topics inside one conversation.
///
/// Two controllers, and the split is the point: [TopicsController] holds the
/// whole conversation's structure (every topic, every thread) and
/// [ThreadController] holds one open thread. Folding them together would mean
/// re-reading the entire history every time a reply lands in the sheet, because
/// the counts are derived on read by design — `comrade_ui`'s `ThreadIndex` is
/// computed per call so a stored count cannot drift when a backfill inserts an
/// old message.
///
/// **What is not here:** the slug rules, the reply graph, and which topic a
/// thread is in — all `comrade_core::topic`'s, over the bridge. Ordering and
/// filtering are `util/topic_view.dart`, shared with the other two frontends.
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/comrade_repository.dart';
import '../data/models.dart';
import 'providers.dart';

/// Everything the topic sheet draws for one conversation.
class TopicsState {
  const TopicsState({
    this.topics = const <TopicInfo>[],
    this.threads = const <ThreadInfo>[],
  });

  final List<TopicInfo> topics;
  final List<ThreadInfo> threads;

  TopicsState copyWith({
    List<TopicInfo>? topics,
    List<ThreadInfo>? threads,
  }) =>
      TopicsState(
        topics: topics ?? this.topics,
        threads: threads ?? this.threads,
      );
}

class TopicsController extends FamilyAsyncNotifier<TopicsState, String> {
  String get _peer => arg;

  @override
  Future<TopicsState> build(String arg) async {
    // The peer reorganising the conversation is the one event that changes this
    // and nothing else — no message arrived, so `conversationProvider` would
    // not hear about it. A message *also* changes it (a reply turns a message
    // into a thread), which is why both are listened for.
    listenToEvents(
      ref,
      matches: (BridgeEvent event) => switch (event) {
        TopicsChanged(:final String peer) => peer == _peer,
        // A message *also* changes this — a reply turns a message into a thread
        // — so both are listened for.
        IncomingDirectMessage(:final MessageInfo message) =>
          message.peer == _peer,
        _ => false,
      },
      onEvent: (BridgeEvent _) => reload(),
    );
    return _read();
  }

  Future<TopicsState> _read() async {
    final ComradeRepository repo = ref.read(comradeRepositoryProvider);
    final List<TopicInfo> topics = await repo.topics(_peer);
    final List<ThreadInfo> threads = await repo.threads(_peer);
    return TopicsState(topics: topics, threads: threads);
  }

  /// Re-read without dropping the sheet to a spinner.
  ///
  /// A filing is instant and local; flipping to `AsyncLoading` for it would
  /// blank the list somebody is looking at, which reads as the topic having
  /// vanished rather than as a refresh.
  Future<void> reload() async {
    try {
      state = AsyncData<TopicsState>(await _read());
    } on ComradeException catch (e, stack) {
      state = AsyncError<TopicsState>(e, stack);
    }
  }

  /// Name a topic. Idempotent — the slug is the id, so naming one that exists
  /// returns it rather than failing.
  Future<TopicInfo?> createTopic(String name) async {
    try {
      final TopicInfo topic = await ref
          .read(comradeRepositoryProvider)
          .createTopic(peer: _peer, name: name);
      await reload();
      return topic;
    } on ComradeException {
      // The caller shows the message; the sheet's list is unchanged and still
      // correct, so the state is deliberately left alone.
      rethrow;
    }
  }

  /// File the thread containing [messageId] under [topicName], creating the
  /// topic if it is new — or, with null, take it out of wherever it was.
  ///
  /// Returns the filed thread, whose `topicSlug` is what the caller reports:
  /// filing produces no chat bubble on either side (`comrade_core::topic`'s
  /// module header has the reason), so a sentence somewhere is the only trace.
  Future<ThreadInfo> assignThread({
    required String messageId,
    String? topicName,
  }) async {
    final ThreadInfo filed =
        await ref.read(comradeRepositoryProvider).assignThread(
              peer: _peer,
              messageId: messageId,
              topicName: topicName,
            );
    await reload();
    return filed;
  }

  Future<void> setTopicClosed(String slug, {required bool closed}) async {
    await ref
        .read(comradeRepositoryProvider)
        .setTopicClosed(peer: _peer, slug: slug, closed: closed);
    await reload();
  }
}

final AsyncNotifierProviderFamily<TopicsController, TopicsState, String>
    topicsProvider =
    AsyncNotifierProvider.family<TopicsController, TopicsState, String>(
        TopicsController.new);

/// Which thread is open, as (peer, rootId).
///
/// A record rather than a joined string: a peer npub and an event id are both
/// hex-ish and concatenating them would make a delimiter choice load-bearing.
typedef ThreadKey = ({String peer, String rootId});

class ThreadController extends FamilyAsyncNotifier<ThreadDetail, ThreadKey> {
  @override
  Future<ThreadDetail> build(ThreadKey arg) async {
    // A reply arriving while the sheet is open belongs in it. Filtered on the
    // peer only — whether it is *in this thread* is core's answer (the reply
    // chain), and re-reading is how we get it.
    listenToEvents(
      ref,
      matches: (BridgeEvent event) =>
          event is IncomingDirectMessage && event.message.peer == arg.peer,
      onEvent: (BridgeEvent _) => reload(),
    );
    return _read();
  }

  Future<ThreadDetail> _read() => ref
      .read(comradeRepositoryProvider)
      .thread(peer: arg.peer, rootId: arg.rootId);

  Future<void> reload() async {
    try {
      state = AsyncData<ThreadDetail>(await _read());
    } on ComradeException catch (e, stack) {
      state = AsyncError<ThreadDetail>(e, stack);
    }
  }

  /// Reply inside this thread.
  ///
  /// Addressed to the thread's *root* by core, whichever message in it the
  /// sheet happens to be keyed on — that flatness is what makes a thread a
  /// thread rather than a chain of quotes.
  Future<void> reply(String content) async {
    if (content.trim().isEmpty) return;
    final MessageInfo sent =
        await ref.read(comradeRepositoryProvider).sendThreadReply(
              peer: arg.peer,
              rootId: arg.rootId,
              content: content.trim(),
            );
    // Appended rather than re-read: the row is already stored, and a reload
    // here would race the relay.
    final ThreadDetail? current = state.value;
    if (current == null) {
      await reload();
      return;
    }
    state = AsyncData<ThreadDetail>(ThreadDetail(
      rootId: current.rootId,
      topicSlug: current.topicSlug,
      messages: <MessageInfo>[...current.messages, sent],
      media: current.media,
    ));
  }

  /// The thread's items merged by time — the interleave every frontend does
  /// over core's two lists (see `comrade_ui::ThreadDto` for why it hands up
  /// two rather than inventing a third ordering).
  static List<ThreadEntry> entries(ThreadDetail thread) {
    final List<ThreadEntry> merged = <ThreadEntry>[
      for (final MessageInfo m in thread.messages) ThreadEntry.text(m),
      for (final MediaMessageInfo m in thread.media) ThreadEntry.media(m),
    ]..sort(
        (ThreadEntry a, ThreadEntry b) => a.createdAt.compareTo(b.createdAt));
    return merged;
  }
}

final AsyncNotifierProviderFamily<ThreadController, ThreadDetail, ThreadKey>
    threadProvider =
    AsyncNotifierProvider.family<ThreadController, ThreadDetail, ThreadKey>(
        ThreadController.new);

/// One item in a thread — text or an attachment, both of which are ordinary
/// events and can be either end of a reply.
class ThreadEntry {
  const ThreadEntry.text(MessageInfo this.message) : media = null;
  const ThreadEntry.media(MediaMessageInfo this.media) : message = null;

  final MessageInfo? message;
  final MediaMessageInfo? media;

  int get createdAt => message?.createdAt ?? media!.createdAt;
  bool get outgoing => message?.outgoing ?? media!.outgoing;

  /// Stable list key. Media ids are namespaced so a media event id cannot
  /// collide with a message id — the same rule the conversation's `ChatItem`
  /// follows.
  String get key => message != null ? message!.id : 'media:${media!.eventId}';
}
