import 'package:flutter/material.dart';

import '../theme/comrade_theme.dart';
import '../util/display_name.dart';

/// Identity-stable avatar: the same key renders the same colour on every
/// device (Telegram-style), so people become recognisable at a glance.
///
/// Port of `ChatsScreen.kt`'s `PeerAvatar`. The seed is the *key*, never the
/// title — renaming a contact must not change their colour.
class PeerAvatar extends StatelessWidget {
  const PeerAvatar({
    required this.title,
    required this.seed,
    this.size = 46,
    super.key,
  });

  final String title;
  final String seed;
  final double size;

  @override
  Widget build(BuildContext context) {
    final Color base =
        kAvatarPalette[avatarColorIndex(seed, kAvatarPalette.length)];
    final String stripped = title.replaceFirst(RegExp('^@+'), '');
    final String initial =
        stripped.isEmpty ? '?' : stripped.characters.first.toUpperCase();
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: <Color>[base.withValues(alpha: 0.82), base],
        ),
      ),
      alignment: Alignment.center,
      child: Text(
        initial,
        style: Theme.of(context).textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.bold,
              color: Colors.white,
              fontSize: size * 0.38,
            ),
      ),
    );
  }
}

/// A key rendered the way both frontends render keys: monospace, dimmed, and
/// never abbreviated away entirely.
class KeyText extends StatelessWidget {
  const KeyText(this.npub, {this.short = true, this.style, super.key});

  final String npub;
  final bool short;
  final TextStyle? style;

  @override
  Widget build(BuildContext context) {
    final TextStyle? base = style ?? Theme.of(context).textTheme.bodySmall;
    return Text(
      short ? shortNpub(npub) : npub,
      style: monoStyle(base)?.copyWith(
        color: base?.color ?? Theme.of(context).colorScheme.onSurfaceVariant,
      ),
      maxLines: short ? 1 : null,
      overflow: short ? TextOverflow.ellipsis : null,
    );
  }
}
