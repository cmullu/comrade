/// Settings state: appearance, the TURN relay card, background connectivity,
/// and the couple-sandbox pairing the desktop shell owned.
library;

import 'package:flutter/material.dart' show ThemeMode;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/app_preferences.dart';
import '../data/models.dart';
import '../platform/screen_channel.dart';
import 'providers.dart';

// ── Appearance (SCREEN_INVENTORY D26) ───────────────────────────────────────

/// The stored spelling of a [ThemeMode]. Only these three strings are ever
/// written; anything else in the store is treated as "never chosen".
String themeModeKey(ThemeMode mode) => switch (mode) {
      ThemeMode.system => 'system',
      ThemeMode.light => 'light',
      ThemeMode.dark => 'dark',
    };

/// Decode of [themeModeKey]. Unknown and absent both mean [ThemeMode.system]:
/// a value this build doesn't recognise (an older/newer spelling, a corrupted
/// store) must not leave the app themeless, and "follow the OS" is the only
/// answer that is right on every platform.
ThemeMode themeModeFromKey(String? key) => switch (key) {
      'light' => ThemeMode.light,
      'dark' => ThemeMode.dark,
      _ => ThemeMode.system,
    };

/// Dark, light, or whatever the OS says.
///
/// Default **system**, per SCREEN_INVENTORY D26 — a phone in daylight is a
/// real use case and so is a phone at 3am. But following the OS cannot be the
/// *only* option: the desktop shell this replaces was dark-only by design, and
/// a Linux/Windows session that reports no preference resolves to light, which
/// is how a dark-first product ends up bright with no way back. So the choice
/// is explicit and overridable, and the override — not the OS — wins once set.
///
/// The call overlay is exempt in either direction: it is full-bleed dark
/// always (`CallPalette`), because a bright call screen held to a face is a
/// bug, not a theme.
class ThemeModeController extends Notifier<ThemeMode> {
  @override
  ThemeMode build() => themeModeFromKey(
        ref.watch(appPreferencesProvider).getString(PrefKeys.themeMode),
      );

  Future<void> set(ThemeMode mode) async {
    await ref
        .read(appPreferencesProvider)
        .setString(PrefKeys.themeMode, themeModeKey(mode));
    state = mode;
  }
}

final NotifierProvider<ThemeModeController, ThemeMode> themeModeProvider =
    NotifierProvider<ThemeModeController, ThemeMode>(ThemeModeController.new);

// ── TURN relay (AUDIT COMMS-02) ─────────────────────────────────────────────

class TurnStatusController extends AsyncNotifier<TurnServerStatus> {
  @override
  Future<TurnServerStatus> build() =>
      ref.watch(comradeRepositoryProvider).turnServerStatus();

  /// Save (or, with a blank [url], clear) the relay.
  ///
  /// [username] and [credential] go **in** and are never read back — there is
  /// no getter for them anywhere in the stack, so a subsequent "Edit" opens
  /// with those fields blank. That is not an oversight to fix later; it is the
  /// property that makes a saved credential unleakable through the UI.
  Future<void> save({
    required String url,
    required String username,
    required String credential,
  }) async {
    await ref.read(comradeRepositoryProvider).setTurnServer(
          url: url,
          username: username,
          credential: credential,
        );
    ref.invalidateSelf();
    await future;
  }

  Future<void> clear() => save(url: '', username: '', credential: '');
}

final AsyncNotifierProvider<TurnStatusController, TurnServerStatus>
    turnStatusProvider =
    AsyncNotifierProvider<TurnStatusController, TurnServerStatus>(
        TurnStatusController.new);

/// The last "Test relay connectivity" result, or `null` when never run /
/// invalidated by an edit.
final StateProvider<TurnDiagnostic?> turnDiagnosticProvider =
    StateProvider<TurnDiagnostic?>((Ref ref) => null);

// ── Background connectivity (AUDIT COMMS-01) ────────────────────────────────

/// On by default: it is what makes an accepted DM or an incoming call notify
/// you while the app is backgrounded but unlocked. The persistent
/// low-priority notification and the battery cost are a real, visible tradeoff
/// the user is allowed to decline.
class BackgroundConnectivityController extends Notifier<bool> {
  @override
  bool build() => ref
      .watch(appPreferencesProvider)
      .getBool(PrefKeys.backgroundConnectivity, defaultValue: true);

  Future<void> set(bool enabled) async {
    await ref
        .read(appPreferencesProvider)
        .setBool(PrefKeys.backgroundConnectivity, enabled);
    state = enabled;
  }
}

final NotifierProvider<BackgroundConnectivityController, bool>
    backgroundConnectivityProvider =
    NotifierProvider<BackgroundConnectivityController, bool>(
        BackgroundConnectivityController.new);

// ── Screenshots (FLAG_SECURE) ───────────────────────────────────────────────

/// The native window-security channel. Overridden at the composition root so
/// tests (and any platform without a native half) can substitute a fake.
final Provider<ScreenSecurityChannel> screenSecurityProvider =
    Provider<ScreenSecurityChannel>((Ref ref) => ScreenSecurityChannel());

/// Screenshot policy: whether this platform can block them at all, and whether
/// the user has asked it to.
class ScreenshotPolicy {
  const ScreenshotPolicy({required this.supported, required this.blocked});

  /// False on a platform with no `FLAG_SECURE` equivalent — the Settings card
  /// hides itself rather than showing a switch that does nothing.
  final bool supported;

  /// True when screenshots and screen recording are blocked app-wide.
  final bool blocked;

  @override
  bool operator ==(Object other) =>
      other is ScreenshotPolicy &&
      other.supported == supported &&
      other.blocked == blocked;

  @override
  int get hashCode => Object.hash(supported, blocked);
}

/// **Screenshots are allowed by default**, which is a deliberate reversal of the
/// Compose app's unconditional `FLAG_SECURE` (see `ScreenSecurityChannel.kt` for
/// the reasoning: it blocked every screen in the app to protect key material no
/// screen shows, and could never stop someone photographing the display).
///
/// Blocking them is now the user's call, stored natively — it has to be readable
/// and applicable before the vault is unlocked, so it cannot live in the
/// encrypted store.
class ScreenshotPolicyController extends AsyncNotifier<ScreenshotPolicy> {
  @override
  Future<ScreenshotPolicy> build() async {
    final ScreenSecurityChannel channel = ref.watch(screenSecurityProvider);
    final bool blocked = await channel.isBlocked();
    return ScreenshotPolicy(supported: channel.supported, blocked: blocked);
  }

  Future<void> setBlocked(bool blocked) async {
    final ScreenSecurityChannel channel = ref.read(screenSecurityProvider);
    // Report what the platform actually stored, never what was asked for.
    final bool stored = await channel.setBlocked(blocked: blocked);
    state = AsyncData<ScreenshotPolicy>(
      ScreenshotPolicy(supported: channel.supported, blocked: stored),
    );
  }
}

final AsyncNotifierProvider<ScreenshotPolicyController, ScreenshotPolicy>
    screenshotPolicyProvider =
    AsyncNotifierProvider<ScreenshotPolicyController, ScreenshotPolicy>(
        ScreenshotPolicyController.new);

// ── Couple sandbox (Sakha / Sakhi) ──────────────────────────────────────────

class SakhaController extends AsyncNotifier<SakhaStatus> {
  @override
  Future<SakhaStatus> build() =>
      ref.watch(comradeRepositoryProvider).sakhaStatus();

  Future<void> pair({required String partnerNpub, required String role}) async {
    state = await AsyncValue.guard<SakhaStatus>(
      () => ref
          .read(comradeRepositoryProvider)
          .pairSakha(partnerNpub: partnerNpub, role: role),
    );
  }
}

final AsyncNotifierProvider<SakhaController, SakhaStatus> sakhaProvider =
    AsyncNotifierProvider<SakhaController, SakhaStatus>(SakhaController.new);

class LedgerController extends AsyncNotifier<String> {
  @override
  Future<String> build() async {
    // The partner pushing an update over the sync channel refreshes it live.
    listenToEvents(
      ref,
      matches: (BridgeEvent e) => e is LedgerUpdated,
      onEvent: (BridgeEvent e) =>
          state = AsyncData<String>((e as LedgerUpdated).ledger),
    );
    final SakhaStatus status = await ref.watch(sakhaProvider.future);
    if (!status.paired) return '';
    return ref.watch(comradeRepositoryProvider).sakhaReadLedger();
  }

  Future<void> addEntry({
    required String description,
    required double amountInr,
    required String paidBy,
  }) async {
    state = await AsyncValue.guard<String>(
      () => ref.read(comradeRepositoryProvider).sakhaAddEntry(
            description: description,
            amountInr: amountInr,
            paidBy: paidBy,
          ),
    );
  }

  Future<void> sync() async {
    await ref.read(comradeRepositoryProvider).syncLedger();
    ref.invalidateSelf();
    await future;
  }
}

final AsyncNotifierProvider<LedgerController, String> ledgerProvider =
    AsyncNotifierProvider<LedgerController, String>(LedgerController.new);
