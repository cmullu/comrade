/// First-run and unlock flow.
///
/// New device: pick a @username + passcode → a keypair identity is created on
/// device and sealed into the encrypted vault. Returning: passcode unlock.
/// Legacy vaults (created before usernames) are asked to claim a handle after
/// unlocking.
///
/// Identity model: the username is a *display alias*. The real identity — what
/// peers actually message — is the keypair created here (shown as `npub…`).
/// Handles are not globally unique and cannot be, without a central registry;
/// contacts therefore pin the key on first use, so a stranger reusing your
/// handle can never receive messages meant for you.
///
/// Port of `ui/OnboardingScreen.kt`, with one behaviour taken from the desktop
/// vault door instead: the reveal toggle on the passphrase field
/// (`index.html#toggle-reveal`). Typing a long passphrase blind on a desktop
/// keyboard is worse than on a phone keypad, and the field is not otherwise
/// verifiable.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/comrade_repository.dart';
import '../data/models.dart';
import '../state/providers.dart';
import '../widgets/app_chrome.dart';

class OnboardingScreen extends ConsumerStatefulWidget {
  const OnboardingScreen({required this.vaultExists, super.key});

  final bool vaultExists;

  @override
  ConsumerState<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends ConsumerState<OnboardingScreen> {
  final TextEditingController _username = TextEditingController();
  final TextEditingController _passcode = TextEditingController();
  final TextEditingController _confirm = TextEditingController();

  bool _busy = false;
  bool _reveal = false;
  String? _error;

  /// A legacy vault unlocked without a username falls into the claim step.
  bool _claimOnly = false;

  bool get _creating => !widget.vaultExists && !_claimOnly;

  @override
  void dispose() {
    _username.dispose();
    _passcode.dispose();
    _confirm.dispose();
    super.dispose();
  }

  /// Client-side validation, identical to the Kotlin original. The core
  /// validates again; this only avoids a pointless round-trip.
  String? _validate() {
    if (_claimOnly || _creating) {
      final String handle =
          _username.text.trim().replaceFirst(RegExp('^@'), '');
      if (handle.length < 3 || handle.length > 24) {
        return 'Username must be 3–24 characters.';
      }
      if (!RegExp(r'^\w+$').hasMatch(handle)) {
        return 'Only letters, numbers and _ are allowed.';
      }
      if (!_claimOnly && _passcode.text.length < 6) {
        return 'Passcode must be at least 6 characters.';
      }
      if (!_claimOnly && _passcode.text != _confirm.text) {
        return "Passcodes don't match.";
      }
      return null;
    }
    return _passcode.text.isEmpty ? 'Enter your passcode.' : null;
  }

  Future<void> _submit() async {
    final String? problem = _validate();
    if (problem != null) {
      setState(() => _error = problem);
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    final ComradeRepository repo = ref.read(comradeRepositoryProvider);
    try {
      Profile profile;
      if (_claimOnly) {
        profile = await repo.setUsername(_username.text);
      } else if (_creating) {
        await repo.unlockVault(path: kVaultPath, passphrase: _passcode.text);
        profile = await repo.setUsername(_username.text);
      } else {
        profile = await repo.unlockVault(
          path: kVaultPath,
          passphrase: _passcode.text,
        );
      }
      if (!mounted) return;
      if (profile.username == null) {
        // Unlocked a pre-username vault: ask for a handle now.
        setState(() {
          _busy = false;
          _claimOnly = true;
        });
      } else {
        setState(() => _busy = false);
        ref.read(appPhaseProvider.notifier).markReady(profile);
      }
    } on ComradeException catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = e.message;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = 'Something went wrong.';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final String subtitle = _claimOnly
        ? 'Pick a username for your existing identity.'
        : _creating
            ? 'Private messaging without middlemen.'
            : 'Welcome back.';

    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 48),
          child: ConstrainedBox(
            // The desktop `.vault-card` is `min(380px, 90vw)`; on a phone the
            // same constraint simply never binds.
            constraints: const BoxConstraints(maxWidth: 380),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: <Widget>[
                Text(
                  '⬢',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.displaySmall
                      ?.copyWith(color: theme.colorScheme.primary),
                ),
                Text(
                  'Comrade',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.headlineMedium
                      ?.copyWith(fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 4),
                Text(
                  subtitle,
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodyMedium
                      ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                ),
                const SizedBox(height: 24),
                if (_creating || _claimOnly) ...<Widget>[
                  TextField(
                    key: const Key('onboarding-username'),
                    controller: _username,
                    enabled: !_busy,
                    decoration: const InputDecoration(
                      labelText: 'Username',
                      prefixText: '@',
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Your public name, so people can find you. Your real '
                    'identity is a cryptographic key created on this device — '
                    'contacts are always pinned to the key, so a name-alike '
                    'can never impersonate you.',
                    style: theme.textTheme.bodySmall
                        ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                  ),
                  const SizedBox(height: 12),
                ],
                if (!_claimOnly) ...<Widget>[
                  TextField(
                    key: const Key('onboarding-passcode'),
                    controller: _passcode,
                    enabled: !_busy,
                    obscureText: !_reveal,
                    onSubmitted: (_) => _submit(),
                    decoration: InputDecoration(
                      labelText: _creating ? 'Passcode' : 'Your passcode',
                      suffixIcon: IconButton(
                        onPressed: () => setState(() => _reveal = !_reveal),
                        tooltip: 'Show or hide passphrase',
                        icon: Icon(
                          _reveal ? Icons.visibility_off : Icons.visibility,
                        ),
                      ),
                    ),
                  ),
                  if (_creating) ...<Widget>[
                    const SizedBox(height: 12),
                    TextField(
                      key: const Key('onboarding-confirm'),
                      controller: _confirm,
                      enabled: !_busy,
                      obscureText: !_reveal,
                      onSubmitted: (_) => _submit(),
                      decoration:
                          const InputDecoration(labelText: 'Confirm passcode'),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'The passcode encrypts everything stored on this device. '
                      'It never leaves the device and cannot be recovered — '
                      'pick something strong and memorable.',
                      style: theme.textTheme.bodySmall
                          ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                    ),
                  ],
                ],
                if (_error != null)
                  Padding(
                    key: const Key('onboarding-error'),
                    padding: const EdgeInsets.only(top: 8),
                    child: ErrorText(_error),
                  ),
                const SizedBox(height: 20),
                SizedBox(
                  height: 52,
                  child: BusyButton(
                    key: const Key('onboarding-submit'),
                    busy: _busy,
                    expand: true,
                    onPressed: _submit,
                    label: _claimOnly
                        ? 'Claim username'
                        : _creating
                            ? 'Create my identity'
                            : 'Unlock',
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Where the encrypted vault lives.
///
/// Android resolved this as `filesDir/comrade-vault`; desktop resolved
/// `appDataDir()/comrade-vault` with a cwd-relative fallback. Both end up at
/// "the platform's private app-data directory", which the bridge is the right
/// place to resolve — the UI should not be doing path arithmetic. Until it
/// does, this constant is what the fake accepts.
const String kVaultPath = 'comrade-vault';
