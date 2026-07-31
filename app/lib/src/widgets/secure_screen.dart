/// Blocks screenshots **for as long as something secret is actually on screen**,
/// and no longer.
///
/// This is the scoped counterpart to the Settings switch. Screenshots are
/// allowed across the app by default; a surface showing secret material takes a
/// hold, and releasing it does not disturb the user's app-wide preference (holds
/// are reference counted natively, so a dialog closing over a secure screen
/// cannot clear the screen's own protection either).
///
/// It ships in exactly one place, and it is worth being precise about why:
/// **no screen in either frontend renders a private key** — what onboarding and
/// settings show is an npub, which is public — so the Compose app's window-wide
/// `FLAG_SECURE` was protecting something that was not there. The one genuine
/// case is the *vault passphrase while the user has revealed it*
/// (`onboarding_screen.dart`), which is why [secure] is a parameter rather than
/// mount/unmount: toggling reveal must not rebuild the field and lose what has
/// been typed into it.
library;

import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../platform/screen_channel.dart';
import '../state/settings_providers.dart';

class SecureScreen extends ConsumerStatefulWidget {
  const SecureScreen({required this.child, this.secure = true, super.key});

  final Widget child;

  /// Whether the hold is currently wanted. Changing it acquires or releases.
  final bool secure;

  @override
  ConsumerState<SecureScreen> createState() => _SecureScreenState();
}

class _SecureScreenState extends ConsumerState<SecureScreen> {
  /// Whether this widget owns a hold right now, so a release is never sent for
  /// a hold that was never taken (which would decrement someone else's).
  bool _held = false;

  /// Resolved once, in `initState`, rather than read in `dispose`.
  ///
  /// This is the whole reason the release is reliable: by the time `dispose`
  /// runs the enclosing `ProviderScope` may already be tearing down, and
  /// `ref.read` then throws — which would silently leave the window secured
  /// forever. Holding the channel means the release is a plain method call on an
  /// object this widget already has.
  late final ScreenSecurityChannel _channel;

  @override
  void initState() {
    super.initState();
    _channel = ref.read(screenSecurityProvider);
    if (widget.secure) _acquire();
  }

  @override
  void didUpdateWidget(covariant SecureScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.secure == _held) return;
    if (widget.secure) {
      _acquire();
    } else {
      _release();
    }
  }

  void _acquire() {
    _held = true;
    unawaited(_channel.setSecureWhileVisible(secure: true));
  }

  void _release() {
    _held = false;
    unawaited(_channel.setSecureWhileVisible(secure: false));
  }

  @override
  void dispose() {
    // Deliberately not awaited: `dispose` cannot be async, and a failed release
    // leaves the window *more* protected than asked — the safe way to fail.
    if (_held) _release();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
