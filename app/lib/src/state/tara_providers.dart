/// Tara — the reflective companion.
///
/// Two honesty gates (AUDIT §8) shape this controller, and both are enforced
/// here rather than in the widget so a future redesign can't lose them:
///  * Tara is **not** therapy. The opt-in explainer gates the thread, and any
///    message carrying distress cues switches the reply into a hand-off to
///    real crisis helplines.
///  * A **crisis reply is never streamed**. Helpline numbers appear complete,
///    at once, the moment they are known — see [TaraController.send].
library;

import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/app_preferences.dart';
import '../data/comrade_repository.dart';
import '../data/models.dart';
import '../util/tara_stream.dart';
import 'providers.dart';

/// Tara's reply while it is still arriving (`TaraScreen.kt`'s
/// `StreamingReply`).
class StreamingReply {
  const StreamingReply({required this.text, required this.crisis});

  final String text;
  final bool crisis;
}

class TaraState {
  const TaraState({
    this.thread,
    this.opener,
    this.resources = const <CrisisResourceInfo>[],
    this.pendingUser,
    this.thinking = false,
    this.streaming,
    this.error,
  });

  /// `null` until the first load resolves — the screen shows a spinner.
  final List<TaraMessageInfo>? thread;

  /// Tara's greeting, only fetched when the thread is empty.
  final String? opener;
  final List<CrisisResourceInfo> resources;

  /// The turn in flight: the user's message shown immediately, then a thinking
  /// indicator, then the reply streaming in. All three are local — the
  /// persisted thread is re-read only once the turn settles, so nothing
  /// renders twice.
  final String? pendingUser;
  final bool thinking;
  final StreamingReply? streaming;
  final String? error;

  bool get busy => thinking || streaming != null;
  bool get hasThread => thread != null && thread!.isNotEmpty;

  TaraState copyWith({
    List<TaraMessageInfo>? thread,
    String? opener,
    bool clearOpener = false,
    List<CrisisResourceInfo>? resources,
    String? pendingUser,
    bool clearPendingUser = false,
    bool? thinking,
    StreamingReply? streaming,
    bool clearStreaming = false,
    String? error,
    bool clearError = false,
  }) =>
      TaraState(
        thread: thread ?? this.thread,
        opener: clearOpener ? null : (opener ?? this.opener),
        resources: resources ?? this.resources,
        pendingUser:
            clearPendingUser ? null : (pendingUser ?? this.pendingUser),
        thinking: thinking ?? this.thinking,
        streaming: clearStreaming ? null : (streaming ?? this.streaming),
        error: clearError ? null : (error ?? this.error),
      );
}

/// Whether the user has read the explainer and opted in.
class TaraOptInController extends Notifier<bool> {
  @override
  bool build() =>
      ref.watch(appPreferencesProvider).getBool(PrefKeys.taraAccepted);

  Future<void> accept() async {
    await ref.read(appPreferencesProvider).setBool(PrefKeys.taraAccepted, true);
    state = true;
  }
}

final NotifierProvider<TaraOptInController, bool> taraOptInProvider =
    NotifierProvider<TaraOptInController, bool>(TaraOptInController.new);

class TaraController extends Notifier<TaraState> {
  StreamSubscription<String>? _streamSub;

  /// Injectable so tests can drive the animation to completion instantly
  /// without also removing the streaming *behaviour* they are testing.
  Duration chunkDelay = kTaraDefaultChunkDelay;

  @override
  TaraState build() {
    ref.onDispose(() {
      _streamSub?.cancel();
      _streamSub = null;
    });
    scheduleMicrotask(_load);
    return const TaraState();
  }

  Future<void> _load() async {
    final ComradeRepository repo = ref.read(comradeRepositoryProvider);
    final List<CrisisResourceInfo> resources = await repo
        .taraCrisisResources()
        .catchError((Object _) => const <CrisisResourceInfo>[]);
    state = state.copyWith(resources: resources);
    await reload();
  }

  Future<void> reload() async {
    final ComradeRepository repo = ref.read(comradeRepositoryProvider);
    final List<TaraMessageInfo> thread = await repo
        .taraThread()
        .catchError((Object _) => const <TaraMessageInfo>[]);
    String? opener;
    if (thread.isEmpty) {
      opener = await repo.taraOpener().catchError((Object _) => '');
      if (opener.isEmpty) opener = null;
    }
    state = state.copyWith(
      thread: thread,
      opener: opener,
      clearOpener: opener == null,
    );
  }

  /// One turn.
  ///
  /// The crisis branch is the load-bearing detail: a reply the engine flagged
  /// as a crisis hand-off is published **whole**, in one state update, so the
  /// helpline numbers are never half-rendered. Only non-crisis replies get
  /// paced through [streamTaraReply].
  Future<void> send(String draft) async {
    final String text = draft.trim();
    if (text.isEmpty || state.busy) return;
    state = state.copyWith(
      pendingUser: text,
      thinking: true,
      clearError: true,
      clearStreaming: true,
    );
    try {
      final TaraMessageInfo reply =
          await ref.read(comradeRepositoryProvider).taraSend(text);
      state = state.copyWith(thinking: false);
      if (reply.crisis) {
        // Never drip-feed a crisis hand-off.
        state = state.copyWith(
          streaming: StreamingReply(text: reply.text, crisis: true),
        );
      } else {
        state = state.copyWith(
          streaming: const StreamingReply(text: '', crisis: false),
        );
        await for (final String soFar
            in streamTaraReply(reply.text, perChunkDelay: chunkDelay)) {
          state = state.copyWith(
            streaming: StreamingReply(text: soFar, crisis: false),
          );
        }
      }
      // The turn is already persisted (taraSend wrote both sides); re-read it,
      // then drop the local copies in the same frame.
      await reload();
      state = state.copyWith(clearPendingUser: true, clearStreaming: true);
    } on ComradeException catch (e) {
      state = state.copyWith(
        thinking: false,
        clearPendingUser: true,
        clearStreaming: true,
        error: e.message,
      );
    } catch (_) {
      state = state.copyWith(
        thinking: false,
        clearPendingUser: true,
        clearStreaming: true,
        error: 'Could not send.',
      );
    }
  }

  Future<void> clearThread() async {
    await ref.read(comradeRepositoryProvider).clearTaraThread();
    await reload();
  }
}

final NotifierProvider<TaraController, TaraState> taraProvider =
    NotifierProvider<TaraController, TaraState>(TaraController.new);
