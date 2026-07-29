/// The app root: theme, and the door-then-app phase switch.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'data/models.dart';
import 'screens/home_shell.dart';
import 'screens/onboarding_screen.dart';
import 'state/providers.dart';
import 'theme/comrade_theme.dart';

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
      // Dark-mode-first, like the desktop shell — but the system preference
      // wins where the platform has one.
      themeMode: ThemeMode.system,
      home: const _PhaseGate(),
    );
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
