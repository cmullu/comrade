/// Settings: profile/@handle, background connectivity, notifications, app
/// updates, the TURN relay card, vault lock, and an honest "in the lab" note.
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
// `show`-listed: `platform.dart` also re-exports the call channel's own
// `TurnServerStatus`/`TurnDiagnostic`, which would collide with `models.dart`.
import '../platform/platform.dart'
    show
        DownloadFailed,
        DownloadIdle,
        DownloadInstalling,
        DownloadReady,
        DownloadRunning,
        DownloadVerifying,
        UpdateAvailable,
        UpdateChecking,
        UpdateDownload,
        UpdateFailed,
        UpdateSettings,
        UpdateStatus,
        UpdateUnknown,
        UpdateUpToDate;
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
          const SizedBox(height: 12),
          const _NotificationsCard(),
          const SizedBox(height: 12),
          const _UpdatesCard(),
          const SizedBox(height: 12),
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
            'your face at 3am is a bug, not a preference.',
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
                  // This line used to have to say that *this* setting survives a
                  // restart, because the Appearance card above it admitted that
                  // it did not. Every client preference persists now, so the
                  // only thing still worth saying is the part that is still only
                  // true here: the value lives on the platform side (the window
                  // flag has to be applied before the first frame, earlier than
                  // even the preference store is open), which is why the older
                  // Compose app reads the very same setting.
                  Text(
                    'Shared with the older Android app on the same device — '
                    'this one is stored by the system, not by Comrade.',
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

/// What Comrade may notify about, and what it has been told to stay quiet about.
///
/// The per-channel detail lives in the OS's own screen (Android owns that UI and
/// duplicating it would drift), so this card's job is the three things that
/// screen cannot say: that notifications are off entirely, how many
/// conversations *this app* is muting, and that muting is device-local.
class _NotificationsCard extends ConsumerWidget {
  const _NotificationsCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ThemeData theme = Theme.of(context);
    final bool enabled = ref.watch(notificationsEnabledProvider).value ?? true;
    final Set<String> muted =
        ref.watch(mutedChatsProvider).value ?? const <String>{};
    return SectionCard(
      title: 'Notifications',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(
            'Messages, message requests, calls, comrades coming online and app '
            'updates each have their own channel, so you can silence one '
            'without silencing the rest.',
            style: theme.textTheme.bodySmall
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
          if (!enabled) ...<Widget>[
            const SizedBox(height: 8),
            Text(
              'Notifications are turned off for Comrade — nothing will reach '
              'you while the app is in the background, including calls.',
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: theme.colorScheme.error),
            ),
          ],
          const SizedBox(height: 8),
          Text(
            muted.isEmpty
                ? 'No conversations are muted.'
                : '${muted.length} '
                    '${muted.length == 1 ? "conversation is" : "conversations are"} '
                    'muted (on this device).',
            style: theme.textTheme.bodySmall,
          ),
          const SizedBox(height: 4),
          Text(
            'Muting is stored on this device only — Comrade has no server to '
            'sync it through — and a muted chat still shows its unread count '
            'and still rings for calls.',
            style: theme.textTheme.bodySmall
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
          if (muted.isNotEmpty) ...<Widget>[
            const SizedBox(height: 8),
            OutlinedButton(
              onPressed: () =>
                  ref.read(mutedChatsProvider.notifier).unmuteAll(),
              child: const Text('Unmute all'),
            ),
          ],
          const SizedBox(height: 8),
          OutlinedButton(
            onPressed: () =>
                ref.read(mutedChatsProvider.notifier).openSystemSettings(),
            child: const Text('Notification settings'),
          ),
        ],
      ),
    );
  }
}

/// Whether a newer Comrade exists, and how to get it.
///
/// Comrade is installed by sideload, so nothing otherwise tells anyone that a
/// release — including a security fix — has shipped. The APK is downloaded by a
/// native foreground service: leaving this screen, or the app, does not stop it,
/// the shade carries the progress, and it finishes with a tappable "ready to
/// install". The release page stays as a fallback for a release with no single
/// APK, and for anyone who would rather not let Comrade install apps.
class _UpdatesCard extends ConsumerStatefulWidget {
  const _UpdatesCard();

  @override
  ConsumerState<_UpdatesCard> createState() => _UpdatesCardState();
}

class _UpdatesCardState extends ConsumerState<_UpdatesCard>
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

  /// The install permission is granted on a *system* screen, and a downloaded
  /// APK can be reaped with the cache — both happen while this app is away, so
  /// coming back has to re-ask rather than trust what was true when we left.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      ref.read(updateSettingsProvider.notifier).refresh();
    }
  }

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final UpdateSettings settings = ref.watch(updateSettingsProvider).value ??
        const UpdateSettings.unknown();
    final UpdateStatus status = ref.watch(updateStatusProvider);
    final UpdateDownload download =
        ref.watch(updateStateProvider).value?.download ?? const DownloadIdle();

    return SectionCard(
      title: 'App updates',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(
            "You're running ${settings.currentVersion}",
            style: theme.textTheme.bodySmall,
          ),
          const SizedBox(height: 8),
          ..._statusRows(context, status, settings, download),
          if (settings.skippedVersion != null) ...<Widget>[
            const SizedBox(height: 8),
            Text(
              "Skipped ${settings.skippedVersion} — you'll be told about "
              'anything newer.',
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
            TextButton(
              onPressed: () =>
                  ref.read(updateSettingsProvider.notifier).unskip(),
              child: const Text('Stop skipping'),
            ),
          ],
          const SizedBox(height: 8),
          OutlinedButton(
            onPressed: status is UpdateChecking
                ? null
                : () => ref.read(updateSettingsProvider.notifier).checkNow(),
            child: const Text('Check now'),
          ),
          const SizedBox(height: 8),
          Row(
            children: <Widget>[
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    Text(
                      'Check for updates automatically',
                      style: theme.textTheme.bodyMedium,
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Asks github.com once a day whether a newer Comrade has '
                      'been published, and notifies you if there is — '
                      'including while Comrade is closed. That tells GitHub '
                      'your IP address and nothing else — no account, no '
                      'identifier, no message data. With this off, nothing is '
                      'checked until you tap “Check now”.',
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              Switch(
                value: settings.autoCheck,
                onChanged: (bool v) =>
                    ref.read(updateSettingsProvider.notifier).setAutoCheck(v),
              ),
            ],
          ),
        ],
      ),
    );
  }

  /// The download → verify → install run of controls, driven by the native
  /// download state. One control at a time, always naming what the next tap
  /// does: **Download update** → a progress bar with **Cancel download** →
  /// **Install 0.0.51**.
  List<Widget> _downloadControls(
    BuildContext context,
    UpdateDownload download,
    UpdateSettings settings,
  ) {
    final ThemeData theme = Theme.of(context);
    final UpdateSettingsController controller =
        ref.read(updateSettingsProvider.notifier);

    List<Widget> permissionNote() => settings.canInstall
        ? const <Widget>[]
        : <Widget>[
            const SizedBox(height: 8),
            Text(
              'Allow Comrade to install updates',
              style: theme.textTheme.bodyMedium,
            ),
            const SizedBox(height: 4),
            Text(
              'Android asks once, per app, before an app may install another. '
              'Without it the update can still be downloaded here and installed '
              'from the release page in your browser.',
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
            const SizedBox(height: 4),
            OutlinedButton(
              onPressed: controller.openInstallPermissionSettings,
              child: const Text('Allow installing'),
            ),
          ];

    switch (download) {
      case DownloadRunning(:final int bytesRead, :final int? percent):
        return <Widget>[
          Text(
            percent != null
                ? 'Downloading… $percent%'
                : 'Downloading… ${(bytesRead / 1024 / 1024).toStringAsFixed(1)} MB',
            style: theme.textTheme.bodySmall,
          ),
          const SizedBox(height: 4),
          LinearProgressIndicator(
            value: percent == null ? null : percent / 100,
          ),
          const SizedBox(height: 8),
          OutlinedButton(
            onPressed: controller.cancelDownload,
            child: const Text('Cancel download'),
          ),
        ];
      case DownloadVerifying():
        return <Widget>[
          Text('Checking the download…', style: theme.textTheme.bodySmall),
          const SizedBox(height: 4),
          const LinearProgressIndicator(),
        ];
      case DownloadReady(:final String version):
        return <Widget>[
          FilledButton(
            onPressed: settings.canInstall
                ? controller.install
                : controller.openInstallPermissionSettings,
            child: Text('Install $version'),
          ),
          ...permissionNote(),
        ];
      // Two phases. While the APK is still being handed over there is real
      // progress to show, and showing it is the difference between "installing"
      // and the several silent seconds this used to be.
      case DownloadInstalling(:final int? percent) when percent != null:
        return <Widget>[
          Text(
            'Handing it to Android… $percent%',
            style: theme.textTheme.bodySmall
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
          const SizedBox(height: 4),
          LinearProgressIndicator(value: percent / 100),
        ];
      case DownloadInstalling():
        return <Widget>[
          Text(
            'Waiting for Android to install it…',
            style: theme.textTheme.bodySmall
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
          const SizedBox(height: 8),
          // Android reports nothing when it declines to show an app's install
          // confirmation, so "waiting" and "never going to happen" look
          // identical from here. This hands the install back to the user
          // instead of stranding them on that line. Deliberately not offered
          // during the hand-off above, where "nothing is happening" is visibly
          // false.
          OutlinedButton(
            onPressed: controller.retryInstall,
            child: const Text('Nothing happened? Try again'),
          ),
        ];
      case DownloadFailed(:final String message):
        return <Widget>[
          Text(
            message,
            style: theme.textTheme.bodySmall
                ?.copyWith(color: theme.colorScheme.error),
          ),
          const SizedBox(height: 4),
          FilledButton(
            onPressed: controller.download,
            child: const Text('Download update'),
          ),
          ...permissionNote(),
        ];
      case DownloadIdle():
        return <Widget>[
          FilledButton(
            onPressed: controller.download,
            child: const Text('Download update'),
          ),
          ...permissionNote(),
        ];
    }
  }

  /// The status half of the card: one of checking / available / up to date /
  /// failed / never looked.
  List<Widget> _statusRows(
    BuildContext context,
    UpdateStatus status,
    UpdateSettings settings,
    UpdateDownload download,
  ) {
    final ThemeData theme = Theme.of(context);
    switch (status) {
      case UpdateChecking():
        return <Widget>[
          Text(
            'Checking…',
            style: theme.textTheme.bodySmall
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ];
      case UpdateAvailable(:final String version, :final String notes):
        return <Widget>[
          Text(
            'Comrade $version is available',
            style: theme.textTheme.titleSmall
                ?.copyWith(color: theme.colorScheme.primary),
          ),
          if (notes.isNotEmpty) ...<Widget>[
            const SizedBox(height: 4),
            Text(
              // Bounded: release notes in this repo run long, and a settings
              // card is not a changelog viewer — the full text is one tap away.
              notes.split('\n').take(12).join('\n'),
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          ],
          const SizedBox(height: 8),
          ..._downloadControls(context, download, settings),
          const SizedBox(height: 4),
          OutlinedButton(
            onPressed: () =>
                ref.read(updateSettingsProvider.notifier).skip(version),
            child: const Text('Skip this version'),
          ),
          const SizedBox(height: 4),
          // A text button, not a filled one: the release page is the fallback
          // now (a release with no single APK, or someone who would rather not
          // let Comrade install apps), not the recommended path.
          TextButton(
            onPressed: () =>
                ref.read(updateSettingsProvider.notifier).openRelease(),
            child: const Text('Open the release page instead'),
          ),
          const SizedBox(height: 4),
          Text(
            'The download keeps going if you leave this screen, and the shade '
            'tells you when it is ready to install. On Android 12 and newer it '
            'usually installs without a confirmation dialog — Comrade closes, '
            'the new version goes in, and a notification offers to reopen it. '
            'Some devices still ask you to confirm; either way Android refuses '
            'an APK signed with a different key than the copy you already have.',
            style: theme.textTheme.bodySmall
                ?.copyWith(color: theme.colorScheme.outline),
          ),
        ];
      case UpdateFailed(:final String message):
        return <Widget>[
          Text(
            "Couldn't check: $message",
            style: theme.textTheme.bodySmall
                ?.copyWith(color: theme.colorScheme.error),
          ),
        ];
      case UpdateUpToDate():
        return <Widget>[
          Text(
            'Up to date',
            style: theme.textTheme.bodySmall
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ];
      case UpdateUnknown():
        return <Widget>[
          Text(
            settings.lastCheckedAt > 0 ? 'Up to date' : 'Not checked yet',
            style: theme.textTheme.bodySmall
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ];
    }
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
