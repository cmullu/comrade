/// Settings: profile/@handle, background connectivity, the TURN relay card,
/// vault lock, and an honest "in the lab" note.
///
/// Port of `ui/SettingsScreen.kt`, merged with desktop's TURN modal.
///
/// **The TURN card is write-only and stays that way** (AUDIT COMMS-02). The
/// URL is readable and displayed; the username and credential go in and are
/// never read back, because nothing in the core exposes them. Re-opening the
/// editor shows those two fields blank. That is the property, not a bug: a UI
/// that could pre-fill a credential is a UI that could leak one.
///
/// Not ported: the whole voice/wake-word section. `WakeWordService`, `VoskModel`,
/// `OneShotRecognizer`, `ComradeTts` and the model-download foreground service
/// are ~1,300 lines of Android services with no cross-platform equivalent, and
/// the Android settings screen's own rule is "no fake switches". They stay
/// Android-native behind a platform channel; there is nothing here to toggle
/// yet.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/comrade_repository.dart';
import '../data/models.dart';
import '../state/providers.dart';
import '../state/settings_providers.dart';
import '../widgets/app_chrome.dart';
import '../widgets/peer_avatar.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final Profile? profile = ref.watch(profileProvider);
    if (profile == null) return const EmptyState(title: 'The vault is locked.');

    return ReadingColumn(
      child: ListView(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
        children: <Widget>[
          _ProfileCard(profile: profile),
          const SizedBox(height: 12),
          const _AppearanceCard(),
          const SizedBox(height: 12),
          const _BackgroundConnectivityCard(),
          const SizedBox(height: 12),
          const _ScreenshotCard(),
          const _TurnRelayCard(),
          const SizedBox(height: 12),
          const _VaultLockCard(),
          const SizedBox(height: 12),
          const _InTheLabCard(),
        ],
      ),
    );
  }
}

class _ProfileCard extends ConsumerStatefulWidget {
  const _ProfileCard({required this.profile});

  final Profile profile;

  @override
  ConsumerState<_ProfileCard> createState() => _ProfileCardState();
}

class _ProfileCardState extends ConsumerState<_ProfileCard> {
  bool _copied = false;

  Future<void> _editUsername() async {
    final Profile? saved = await showDialog<Profile>(
      context: context,
      builder: (BuildContext context) =>
          _EditUsernameDialog(current: widget.profile.username),
    );
    if (saved != null) ref.read(appPhaseProvider.notifier).updateProfile(saved);
  }

  @override
  Widget build(BuildContext context) {
    final Profile profile = widget.profile;
    return SectionCard(
      elevated: true,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Row(
            children: <Widget>[
              PeerAvatar(
                title: profile.username ?? profile.npub,
                seed: profile.npub,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    Text(
                      profile.username != null
                          ? '@${profile.username}'
                          : 'No username yet',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    KeyText(profile.npub),
                  ],
                ),
              ),
              TextButton(onPressed: _editUsername, child: const Text('Edit')),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            'Your identity key',
            style: Theme.of(context).textTheme.labelMedium?.copyWith(
                  color: Theme.of(context).colorScheme.primary,
                ),
          ),
          const SizedBox(height: 4),
          SelectableText(
            profile.npub,
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  fontFamily: 'monospace',
                ),
          ),
          const SizedBox(height: 8),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton(
              // Clipboard access is one of the few platform APIs Flutter gives
              // us directly, so this is the real thing, not a stub.
              onPressed: () async {
                await Clipboard.setData(ClipboardData(text: profile.npub));
                if (mounted) setState(() => _copied = true);
              },
              child: Text(_copied ? 'Copied ✓' : 'Copy key'),
            ),
          ),
          const SizedBox(height: 8),
          Text(
            'Usernames are display names and can repeat across the network. '
            'This key is what makes you *you* — share it so people can reach '
            'the real you even if someone copies your name.',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
          ),
        ],
      ),
    );
  }
}

class _EditUsernameDialog extends ConsumerStatefulWidget {
  const _EditUsernameDialog({this.current});

  final String? current;

  @override
  ConsumerState<_EditUsernameDialog> createState() =>
      _EditUsernameDialogState();
}

class _EditUsernameDialogState extends ConsumerState<_EditUsernameDialog> {
  late final TextEditingController _value =
      TextEditingController(text: widget.current ?? '');
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _value.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final Profile saved =
          await ref.read(comradeRepositoryProvider).setUsername(_value.text);
      if (mounted) Navigator.of(context).pop(saved);
    } on ComradeException catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = e.message;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: const Text('Username'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            TextField(
              controller: _value,
              enabled: !_busy,
              autofocus: true,
              onSubmitted: (_) => _save(),
              decoration: const InputDecoration(prefixText: '@'),
            ),
            const SizedBox(height: 8),
            Text(
              '3–24 characters: letters, numbers, underscore.',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
            ),
            ErrorText(_error),
          ],
        ),
        actions: <Widget>[
          TextButton(
            onPressed: _busy ? null : () => Navigator.of(context).pop(),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: _busy ? null : _save,
            child: Text(_busy ? 'Saving…' : 'Save'),
          ),
        ],
      );
}

/// Dark / light / follow-the-OS.
///
/// A segmented control rather than a single "Dark mode" switch, because a
/// switch cannot express *three* states and the third one — "whatever the OS
/// says" — is the default. See `settings_providers.dart`'s
/// [ThemeModeController] for why the override has to exist at all.
class _AppearanceCard extends ConsumerWidget {
  const _AppearanceCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ThemeMode mode = ref.watch(themeModeProvider);
    return SectionCard(
      title: 'Appearance',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          // Icons omitted on purpose: three icon+label segments overflow the
          // card on a 360 dp phone, and the labels are already unambiguous.
          SizedBox(
            width: double.infinity,
            child: SegmentedButton<ThemeMode>(
              key: const Key('theme-mode'),
              showSelectedIcon: false,
              segments: const <ButtonSegment<ThemeMode>>[
                ButtonSegment<ThemeMode>(
                  value: ThemeMode.system,
                  label: Text('System'),
                ),
                ButtonSegment<ThemeMode>(
                  value: ThemeMode.light,
                  label: Text('Light'),
                ),
                ButtonSegment<ThemeMode>(
                  value: ThemeMode.dark,
                  label: Text('Dark'),
                ),
              ],
              selected: <ThemeMode>{mode},
              onSelectionChanged: (Set<ThemeMode> selection) =>
                  ref.read(themeModeProvider.notifier).set(selection.first),
            ),
          ),
          const SizedBox(height: 8),
          Text(
            switch (mode) {
              ThemeMode.system =>
                'Following your system setting. Desktop sessions that report no '
                    'preference land on light — pick Dark here to override that.',
              ThemeMode.light => 'Light everywhere, whatever the system says.',
              ThemeMode.dark => 'Dark everywhere, whatever the system says.',
            },
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
          ),
          const SizedBox(height: 4),
          Text(
            'A call is always dark, in every mode — a bright screen held to '
            'your face at 3am is a bug, not a preference. This choice is not '
            'remembered across restarts yet: like every client preference it '
            'lives in memory until the app gets a persisted store.',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.outline,
                ),
          ),
        ],
      ),
    );
  }
}

class _BackgroundConnectivityCard extends ConsumerWidget {
  const _BackgroundConnectivityCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final bool enabled = ref.watch(backgroundConnectivityProvider);
    return SectionCard(
      child: Row(
        children: <Widget>[
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: <Widget>[
                Text(
                  'Stay connected in the background',
                  style: Theme.of(context).textTheme.titleSmall,
                ),
                const SizedBox(height: 4),
                Text(
                  'Keep receiving messages and calls while the app is unlocked '
                  'but not on screen. Shows a low-priority notification while '
                  'active; turning this off means messages and calls only '
                  'arrive while Comrade is open.',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 12),
          Switch(
            value: enabled,
            onChanged: (bool v) =>
                ref.read(backgroundConnectivityProvider.notifier).set(v),
          ),
        ],
      ),
    );
  }
}

/// Screenshots: allowed by default, blockable by the user.
///
/// The Compose app set `FLAG_SECURE` on its whole window and never cleared it,
/// so nothing in the app could be screenshotted — chats, journal, feed, settings
/// — to protect key material that is not on any of those screens (what is shown
/// is an npub, which is public). That is now the user's decision instead of a
/// blanket default, and the copy says plainly what it can and cannot do: it
/// stops the OS screenshotting, it cannot stop a camera pointed at the screen.
///
/// The whole card is absent where the platform has no equivalent — the settings
/// screen's own "no fake switches" rule.
class _ScreenshotCard extends ConsumerWidget {
  const _ScreenshotCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ScreenshotPolicy? policy = ref.watch(screenshotPolicyProvider).value;
    if (policy == null || !policy.supported) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: SectionCard(
        child: Row(
          children: <Widget>[
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: <Widget>[
                  Text(
                    'Block screenshots',
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                  const SizedBox(height: 4),
                  Text(
                    'Off by default: screenshots and screen recording work '
                    'everywhere in Comrade. Turn this on and the system refuses '
                    'both, and the app is hidden from the recents preview. It '
                    'cannot stop a photo of your screen, and it applies to your '
                    'device only — never to the person you are talking to.',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                  ),
                  const SizedBox(height: 4),
                  // Said out loud because the card above it (Appearance) has to
                  // admit the opposite: unlike every other client preference,
                  // this one survives a restart. It has to — the window flag is
                  // set before the first frame, long before there is a vault to
                  // read a setting out of — so it lives on the platform side and
                  // is shared with the Compose app.
                  Text(
                    'Remembered across restarts, and shared with the older '
                    'Android app on the same device.',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: Theme.of(context).colorScheme.outline,
                        ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 12),
            Switch(
              key: const Key('settings-block-screenshots'),
              value: policy.blocked,
              onChanged: (bool v) =>
                  ref.read(screenshotPolicyProvider.notifier).setBlocked(v),
            ),
          ],
        ),
      ),
    );
  }
}

class _TurnRelayCard extends ConsumerWidget {
  const _TurnRelayCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final AsyncValue<TurnServerStatus> status = ref.watch(turnStatusProvider);
    final TurnDiagnostic? diagnostic = ref.watch(turnDiagnosticProvider);
    final TurnServerStatus current =
        status.value ?? const TurnServerStatus.none();

    return SectionCard(
      title: 'Calls relay (TURN)',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Text(
            'Direct calls work for most pairs of devices. Some networks '
            '(notably carrier-grade NAT) need a relay server to connect at '
            'all — configure one here if calls fail to connect. Calls always '
            'try a direct connection first; the relay is only used if that '
            'fails.',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
          ),
          const SizedBox(height: 8),
          Text(
            current.configured
                ? 'Relay configured: ${current.url ?? ''}'
                : 'No relay configured — calls that can\'t connect directly '
                    'will fail instead of falling back',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          const SizedBox(height: 8),
          Row(
            children: <Widget>[
              Expanded(
                child: OutlinedButton(
                  key: const Key('turn-edit'),
                  onPressed: () async {
                    final bool saved =
                        await showTurnServerDialog(context, current: current) ??
                            false;
                    if (saved) {
                      ref.read(turnDiagnosticProvider.notifier).state = null;
                    }
                  },
                  child: Text(current.configured ? 'Edit' : 'Configure'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: OutlinedButton(
                  onPressed: current.configured
                      ? () async {
                          ref.read(turnDiagnosticProvider.notifier).state =
                              null;
                          final TurnDiagnostic result = await ref
                              .read(comradeRepositoryProvider)
                              .testTurnConnectivity();
                          ref.read(turnDiagnosticProvider.notifier).state =
                              result;
                        }
                      : null,
                  child: const Text('Test relay connectivity'),
                ),
              ),
            ],
          ),
          if (diagnostic != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                switch (diagnostic) {
                  TurnDiagnostic.noServerConfigured =>
                    'No relay configured to test',
                  TurnDiagnostic.relayAvailable =>
                    'Relay reachable — calls can fall back to it if a direct '
                        'path fails',
                  TurnDiagnostic.relayUnavailable =>
                    'Relay unreachable — check the server URL and credentials',
                },
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: diagnostic == TurnDiagnostic.relayUnavailable
                          ? Theme.of(context).colorScheme.error
                          : Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
              ),
            ),
        ],
      ),
    );
  }
}

/// Returns true when a save (or clear) happened.
Future<bool?> showTurnServerDialog(
  BuildContext context, {
  required TurnServerStatus current,
}) =>
    showDialog<bool>(
      context: context,
      builder: (BuildContext context) =>
          _EditTurnServerDialog(current: current),
    );

class _EditTurnServerDialog extends ConsumerStatefulWidget {
  const _EditTurnServerDialog({required this.current});

  final TurnServerStatus current;

  @override
  ConsumerState<_EditTurnServerDialog> createState() =>
      _EditTurnServerDialogState();
}

class _EditTurnServerDialogState extends ConsumerState<_EditTurnServerDialog> {
  late final TextEditingController _url =
      TextEditingController(text: widget.current.url ?? '');

  // Username/credential are write-only: never pre-filled from a read-back
  // value, because there isn't one.
  final TextEditingController _username = TextEditingController();
  final TextEditingController _credential = TextEditingController();

  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _url.dispose();
    _username.dispose();
    _credential.dispose();
    super.dispose();
  }

  Future<void> _save({required bool clear}) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(turnStatusProvider.notifier).save(
            url: clear ? '' : _url.text.trim(),
            username: clear ? '' : _username.text,
            credential: clear ? '' : _credential.text,
          );
      if (mounted) Navigator.of(context).pop(true);
    } on ComradeException catch (e) {
      // The Rust-side validation message only ever describes the URL's shape
      // — never the credential — so it's always safe to show directly.
      if (mounted) {
        setState(() {
          _busy = false;
          _error = e.message;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: const Text('Calls relay (TURN)'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              TextField(
                key: const Key('turn-url'),
                controller: _url,
                enabled: !_busy,
                decoration: const InputDecoration(
                  labelText: 'Server URL (turn: or turns:)',
                  hintText: 'turn:turn.example.com:3478',
                ),
              ),
              const SizedBox(height: 8),
              TextField(
                key: const Key('turn-username'),
                controller: _username,
                enabled: !_busy,
                decoration: const InputDecoration(labelText: 'Username'),
              ),
              const SizedBox(height: 8),
              TextField(
                key: const Key('turn-credential'),
                controller: _credential,
                enabled: !_busy,
                obscureText: true,
                decoration: const InputDecoration(labelText: 'Credential'),
              ),
              const SizedBox(height: 8),
              Text(
                'The username and credential are stored but never shown again '
                '— reopening this dialog leaves them blank.',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
              ),
              ErrorText(_error),
            ],
          ),
        ),
        actions: <Widget>[
          if (widget.current.configured)
            TextButton(
              onPressed: _busy ? null : () => _save(clear: true),
              child: const Text('Clear'),
            ),
          TextButton(
            onPressed: _busy ? null : () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          TextButton(
            key: const Key('turn-save'),
            onPressed: _busy || _url.text.trim().isEmpty
                ? null
                : () => _save(clear: false),
            child: Text(_busy ? 'Saving…' : 'Save'),
          ),
        ],
      );
}

class _VaultLockCard extends ConsumerWidget {
  const _VaultLockCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) => SectionCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Text('Lock vault now',
                style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 4),
            Text(
              'Drops the decrypted key from memory immediately. You\'ll need '
              'your passphrase to unlock again.',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
            ),
            const SizedBox(height: 8),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton(
                onPressed: () async {
                  final bool? yes = await showDialog<bool>(
                    context: context,
                    builder: (BuildContext context) => AlertDialog(
                      title: const Text('Lock vault now'),
                      content: const Text(
                        'Drops the decrypted key from memory immediately. '
                        'You\'ll need your passphrase to unlock again.',
                      ),
                      actions: <Widget>[
                        TextButton(
                          onPressed: () => Navigator.of(context).pop(false),
                          child: const Text('Cancel'),
                        ),
                        TextButton(
                          onPressed: () => Navigator.of(context).pop(true),
                          child: const Text('Lock vault now'),
                        ),
                      ],
                    ),
                  );
                  if (yes ?? false) {
                    await ref.read(appPhaseProvider.notifier).lock();
                  }
                },
                child: const Text('Lock vault now'),
              ),
            ),
          ],
        ),
      );
}

class _InTheLabCard extends ConsumerWidget {
  const _InTheLabCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final String version = ref.watch(coreVersionProvider).value ?? '?';
    return SectionCard(
      title: 'In the lab',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Text(
            'Off-grid mesh connectivity is real: the status bar shows nearby '
            'devices live. Actually chatting over the mesh is still built and '
            'tested only at the engine level, not usable from the app yet. '
            'Voice control and wake-word ("hey comrade") remain Android-only '
            'and are not reachable from this build. They\'ll appear here when '
            'they actually work — no fake switches.',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
          ),
          const SizedBox(height: 8),
          Text(
            'core v$version',
            style: Theme.of(context).textTheme.labelSmall?.copyWith(
                  fontFamily: 'monospace',
                  color: Theme.of(context).colorScheme.outline,
                ),
          ),
        ],
      ),
    );
  }
}
