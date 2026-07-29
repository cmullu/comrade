/// Journal, Sabha feed, and call history — the read-mostly surfaces.
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/models.dart';
import '../util/display_name.dart';
import 'providers.dart';

// ── Journal (strictly local) ────────────────────────────────────────────────

class JournalController extends AsyncNotifier<List<JournalEntryInfo>> {
  @override
  Future<List<JournalEntryInfo>> build() =>
      ref.watch(comradeRepositoryProvider).journal();

  Future<void> add({required String text, String? mood}) async {
    await ref
        .read(comradeRepositoryProvider)
        .addJournalEntry(text: text, mood: mood);
    ref.invalidateSelf();
    await future;
  }

  Future<void> delete(String id) async {
    await ref.read(comradeRepositoryProvider).deleteJournalEntry(id);
    ref.invalidateSelf();
    await future;
  }
}

final AsyncNotifierProvider<JournalController, List<JournalEntryInfo>>
    journalProvider =
    AsyncNotifierProvider<JournalController, List<JournalEntryInfo>>(
        JournalController.new);

/// Journal entries bucketed under "Today" / "Yesterday" / "12 Jul 2026",
/// preserving the newest-first order the repository returns.
typedef JournalDay = ({String label, List<JournalEntryInfo> entries});

List<JournalDay> groupJournalByDay(
    List<JournalEntryInfo> entries, int nowSecs) {
  final List<JournalDay> days = <JournalDay>[];
  for (final JournalEntryInfo e in entries) {
    final String label = dayLabel(e.createdAt, nowSecs);
    if (days.isNotEmpty && days.last.label == label) {
      days.last.entries.add(e);
    } else {
      days.add((label: label, entries: <JournalEntryInfo>[e]));
    }
  }
  return days;
}

// ── Sabha (public feed) ─────────────────────────────────────────────────────

class FeedController extends AsyncNotifier<List<ChitthiInfo>> {
  @override
  Future<List<ChitthiInfo>> build() async {
    // Live public posts stream in as the relays deliver them — prepended, not
    // re-fetched, so a busy relay doesn't reset the reader's scroll.
    listenToEvents(
      ref,
      matches: (BridgeEvent e) => e is IncomingChitthi,
      onEvent: (BridgeEvent e) => prepend((e as IncomingChitthi).chitthi),
    );
    return ref.watch(comradeRepositoryProvider).sabhaTimeline();
  }

  void prepend(ChitthiInfo chitthi) {
    final List<ChitthiInfo> current = state.value ?? const <ChitthiInfo>[];
    if (current.any((ChitthiInfo c) => c.id == chitthi.id)) return;
    state = AsyncData<List<ChitthiInfo>>(<ChitthiInfo>[chitthi, ...current]);
  }

  /// Broadcast a Chitthi and show it immediately (optimistic, like both
  /// existing frontends).
  Future<void> post(String content, {String? author}) async {
    final String id =
        await ref.read(comradeRepositoryProvider).broadcastChitthi(content);
    prepend(
      ChitthiInfo(
        id: id,
        author: author ?? 'you',
        content: content,
        createdAt: DateTime.now().millisecondsSinceEpoch ~/ 1000,
      ),
    );
  }
}

final AsyncNotifierProvider<FeedController, List<ChitthiInfo>> feedProvider =
    AsyncNotifierProvider<FeedController, List<ChitthiInfo>>(
        FeedController.new);

// ── Call history ────────────────────────────────────────────────────────────

final FutureProvider<List<CallRecordInfo>> callHistoryProvider =
    FutureProvider<List<CallRecordInfo>>(
  (Ref ref) => ref.watch(comradeRepositoryProvider).callHistory(),
);
