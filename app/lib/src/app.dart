/// The app root: theme, and the door-then-app phase switch.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'data/models.dart';
import 'screens/home_shell.dart';
import 'screens/onboarding_screen.dart';
import 'state/providers.dart';
import 'state/settings_providers.dart';
import 'theme/comrade_theme.dart';
import 'widgets/media_attachment.dart';

class ComradeApp extends ConsumerWidget {
  const ComradeApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // The workspace re-skins the whole app the way `body.theme-*` did on
    // desktop: Travel goes amber, the couple sandbox goes cyan/rose.
    final WorkspaceInfo workspace =
        ref.watch(workspaceProvider).value ?? const WorkspaceInfo.base();
    final WorkspaceSkin skin = WorkspaceSkin.fromWorkspaceKey(workspace.key);

    return MaterialApp(
      title: 'Comrade',
      debugShowCheckedModeBanner: false,
      theme: ComradeTheme.light(skin: skin),
      darkTheme: ComradeTheme.dark(skin: skin),
      // Follows the OS until Settings → Appearance says otherwise; both skins
      // exist for every workspace accent, so the override is a real choice on
      // every platform rather than a mobile-only affordance.
      themeMode: ref.watch(themeModeProvider),
      home: const _MediaPlaintextGuard(child: _PhaseGate()),
    );
  }
}

/// Drops every decrypted attachment when the app leaves the foreground or the
/// vault locks.
///
/// The Compose app did this because it wrote decrypted audio and video to
/// `cacheDir/media` and that plaintext must not outlive a foreground session
/// (AUDIT S-4). The unified app writes nothing to disk, so this is about the
/// *other* half of the same concern: the recents thumbnail, and a phone handed
/// over unlocked, should not still be holding the last photo someone sent. It
/// costs a re-fetch on return, which is what a bounded cache is for.
class _MediaPlaintextGuard extends ConsumerStatefulWidget {
  const _MediaPlaintextGuard({required this.child});

  final Widget child;

  @override
  ConsumerState<_MediaPlaintextGuard> createState() =>
      _MediaPlaintextGuardState();
}

class _MediaPlaintextGuardState extends ConsumerState<_MediaPlaintextGuard>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    switch (state) {
      case AppLifecycleState.paused:
      case AppLifecycleState.hidden:
      case AppLifecycleState.detached:
        // Nothing to await it for, and nothing to do if it fails: the cache is
        // cleared synchronously and only the platform hop is asynchronous.
        unawaited(purgeDecryptedMedia(ref));
      case AppLifecycleState.resumed:
      case AppLifecycleState.inactive:
        // `inactive` fires for a notification shade pull and for the app
        // switcher's first frame alike; purging there would re-download an
        // image every time a banner appeared.
        break;
    }
  }

  @override
  Widget build(BuildContext context) {
    // Locking the vault is the deliberate version of backgrounding: the peer
    // names, the thread and the keys all go, so the pictures go too.
    ref.listen<AsyncValue<AppPhase>>(appPhaseProvider,
        (AsyncValue<AppPhase>? previous, AsyncValue<AppPhase> next) {
      if (previous?.value is AppReady && next.value is! AppReady) {
        unawaited(purgeDecryptedMedia(ref));
      }
    });
    return widget.child;
  }
}

class _PhaseGate extends ConsumerWidget {
  const _PhaseGate();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final AsyncValue<AppPhase> phase = ref.watch(appPhaseProvider);
    return phase.when(
      loading: () => const Scaffold(
        body: Center(
          child: SizedBox(
              width: 28, height: 28, child: CircularProgressIndicator()),
        ),
      ),
      error: (Object e, StackTrace s) => Scaffold(
        body: Center(child: Text('Could not start: $e')),
      ),
      data: (AppPhase p) => switch (p) {
        AppChecking() => const Scaffold(
            body: Center(
              child: SizedBox(
                width: 28,
                height: 28,
                child: CircularProgressIndicator(),
              ),
            ),
          ),
        AppLocked(:final bool vaultExists) =>
          OnboardingScreen(vaultExists: vaultExists),
        AppReady() => const HomeShell(),
      },
    );
  }
}
