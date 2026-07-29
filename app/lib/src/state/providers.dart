/// Root providers: the backend seam, the event stream, and app-level state.
///
/// Everything the UI reads flows from [comradeRepositoryProvider]. Nothing
/// below this file knows whether it is talking to Rust or to the fake.
library;

import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/app_preferences.dart';
import '../data/comrade_repository.dart';
import '../data/models.dart';

/// The backend. **Must** be overridden at the composition root — there is no
/// sensible default, and silently defaulting to the fake in production would
/// be the worst possible failure mode (a demo that looks like it works).
final Provider<ComradeRepository> comradeRepositoryProvider =
    Provider<ComradeRepository>((Ref ref) {
  throw UnimplementedError(
    'comradeRepositoryProvider must be overridden with a ComradeRepository '
    '(FakeComradeRepository for tests/preview, the flutter_rust_bridge-backed '
    'implementation in the shipping app).',
  );
});

/// Client-side preferences. Overridden at the root alongside the repository.
final Provider<AppPreferences> appPreferencesProvider =
    Provider<AppPreferences>((Ref ref) => InMemoryPreferences());

/// Live events from the core, as a broadcast stream every feature can watch.
///
/// On Android a single `ChatEventRouter` drained the native queue and
/// published integer "ticks" that screens re-read on; here each provider
/// listens for the events it cares about. Same single-consumer property (the
/// repository owns the drain), without the polling.
final StreamProvider<BridgeEvent> bridgeEventsProvider =
    StreamProvider<BridgeEvent>(
        (Ref ref) => ref.watch(comradeRepositoryProvider).events);

/// Subscribe to the subset of [BridgeEvent]s [matches] accepts, running
/// [onEvent] for each. Returns nothing; wire it inside a provider's `build`.
void listenToEvents(
  Ref ref, {
  required bool Function(BridgeEvent event) matches,
  required void Function(BridgeEvent event) onEvent,
}) {
  final StreamSubscription<BridgeEvent> sub =
      ref.watch(comradeRepositoryProvider).events.listen((BridgeEvent e) {
    if (matches(e)) onEvent(e);
  });
  ref.onDispose(sub.cancel);
}

// ── App phase (the door, then the app) ──────────────────────────────────────

/// Startup phases: resolve what's on disk, then either the door or the app.
/// Mirrors `MainActivity.kt`'s `AppPhase`.
sealed class AppPhase {
  const AppPhase();
}

class AppChecking extends AppPhase {
  const AppChecking();
}

class AppLocked extends AppPhase {
  const AppLocked({required this.vaultExists});
  final bool vaultExists;
}

class AppReady extends AppPhase {
  const AppReady(this.profile);
  final Profile profile;
}

class AppPhaseController extends AsyncNotifier<AppPhase> {
  @override
  Future<AppPhase> build() async {
    final ComradeRepository repo = ref.watch(comradeRepositoryProvider);
    final Profile? profile = await repo.currentProfile();
    // An already-unlocked runtime (a hot restart, or an Activity recreation on
    // Android) skips the door entirely — same rule as `ComradeApp`.
    return profile != null
        ? AppReady(profile)
        : const AppLocked(vaultExists: true);
  }

  /// Called by onboarding once a passphrase has produced a profile.
  void markReady(Profile profile) =>
      state = AsyncData<AppPhase>(AppReady(profile));

  /// Reflect a profile edit (a claimed/changed @handle) without re-reading.
  void updateProfile(Profile profile) {
    if (state.value is AppReady) state = AsyncData<AppPhase>(AppReady(profile));
  }

  /// Drop the decrypted key and return to the door.
  Future<void> lock() async {
    await ref.read(comradeRepositoryProvider).lockVault();
    state = const AsyncData<AppPhase>(AppLocked(vaultExists: true));
  }
}

final AsyncNotifierProvider<AppPhaseController, AppPhase> appPhaseProvider =
    AsyncNotifierProvider<AppPhaseController, AppPhase>(AppPhaseController.new);

/// The unlocked profile, or `null` while the vault is closed.
final Provider<Profile?> profileProvider = Provider<Profile?>((Ref ref) {
  final AppPhase? phase = ref.watch(appPhaseProvider).value;
  return phase is AppReady ? phase.profile : null;
});

// ── Mesh status ─────────────────────────────────────────────────────────────

/// Live off-grid mesh status, seeded from a snapshot then driven by events.
///
/// This is the one signal that still works with zero cellular or relay
/// reachability, so the banner stays visible rather than being a one-off
/// toast — exactly what to check when navigating somewhere with no signal.
class MeshStatusController extends AsyncNotifier<MeshStatus> {
  @override
  Future<MeshStatus> build() async {
    listenToEvents(
      ref,
      matches: (BridgeEvent e) => e is MeshStatusChanged,
      onEvent: (BridgeEvent e) =>
          state = AsyncData<MeshStatus>((e as MeshStatusChanged).status),
    );
    return ref.watch(comradeRepositoryProvider).meshStatus();
  }
}

final AsyncNotifierProvider<MeshStatusController, MeshStatus>
    meshStatusProvider =
    AsyncNotifierProvider<MeshStatusController, MeshStatus>(
        MeshStatusController.new);

// ── Workspace (Base / Off-Grid Travel / Couple Sandbox) ─────────────────────

class WorkspaceController extends AsyncNotifier<WorkspaceInfo> {
  @override
  Future<WorkspaceInfo> build() =>
      ref.watch(comradeRepositoryProvider).currentWorkspace();

  Future<void> toggle(String target) async {
    state = const AsyncLoading<WorkspaceInfo>().copyWithPrevious(state);
    state = await AsyncValue.guard<WorkspaceInfo>(
      () => ref.read(comradeRepositoryProvider).toggleWorkspace(target),
    );
  }
}

final AsyncNotifierProvider<WorkspaceController, WorkspaceInfo>
    workspaceProvider =
    AsyncNotifierProvider<WorkspaceController, WorkspaceInfo>(
        WorkspaceController.new);

// ── Contacts (used for display-name resolution across screens) ──────────────

class ContactsController extends AsyncNotifier<List<ContactInfo>> {
  @override
  Future<List<ContactInfo>> build() async {
    listenToEvents(
      ref,
      matches: (BridgeEvent e) => e is PeerProfileUpdated,
      onEvent: (_) => ref.invalidateSelf(),
    );
    return ref.watch(comradeRepositoryProvider).contacts();
  }

  Future<ContactInfo> setAlias(
      {required String npub, required String alias}) async {
    final ContactInfo saved = await ref
        .read(comradeRepositoryProvider)
        .setContactAlias(npub: npub, alias: alias);
    ref.invalidateSelf();
    return saved;
  }
}

final AsyncNotifierProvider<ContactsController, List<ContactInfo>>
    contactsProvider =
    AsyncNotifierProvider<ContactsController, List<ContactInfo>>(
        ContactsController.new);

/// Contacts indexed by key, for the O(1) lookups call history and the chat
/// list do per row.
final Provider<Map<String, ContactInfo>> contactsByNpubProvider =
    Provider<Map<String, ContactInfo>>((Ref ref) {
  final List<ContactInfo> list =
      ref.watch(contactsProvider).value ?? const <ContactInfo>[];
  return <String, ContactInfo>{
    for (final ContactInfo c in list) c.npub: c,
  };
});

// ── Core version (settings footer) ──────────────────────────────────────────

final FutureProvider<String> coreVersionProvider = FutureProvider<String>(
  (Ref ref) => ref.watch(comradeRepositoryProvider).version(),
);
