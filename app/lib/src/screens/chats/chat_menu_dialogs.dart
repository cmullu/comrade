/// The two confirmations the conversation ⋮ menu opens: what protects the
/// thread, and blocking the person on the other end.
///
/// Ports of `MainActivity.kt`'s `EncryptionDetailsDialog` and `BlockPeerDialog`.
library;

import 'package:flutter/material.dart';

import '../../widgets/glass_surface.dart';

/// Explains what protects this thread and shows the peer's key in full.
///
/// The key is unabbreviated and selectable on purpose: comparing it out of band
/// is the only way to be sure who you are talking to, and a truncated key
/// cannot be compared.
Future<void> showEncryptionDetailsDialog(
  BuildContext context, {
  required String peer,
}) =>
    showGlassDialog<void>(
      context,
      builder: (BuildContext context) {
        final ThemeData theme = Theme.of(context);
        return AlertDialog(
          icon: const Icon(Icons.lock),
          title: const Text('Encryption details'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                const Text(
                  'Messages in this chat are sealed with your key and theirs '
                  '(NIP-44) and gift-wrapped, so relays carry neither the text '
                  'nor who is talking to whom. Nobody without one of the two '
                  'private keys can read it — not a relay, not us.',
                ),
                const SizedBox(height: 12),
                Text(
                  'Their public key',
                  style: theme.textTheme.labelMedium
                      ?.copyWith(color: theme.colorScheme.primary),
                ),
                const SizedBox(height: 4),
                Container(
                  width: double.infinity,
                  padding:
                      const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: SelectableText(
                    peer,
                    key: const Key('encryption-key'),
                    style: theme.textTheme.bodySmall
                        ?.copyWith(fontFamily: 'monospace'),
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  'Names are claims; this key is the identity. Compare it with '
                  'them over a channel you already trust to be certain who '
                  "you're talking to.",
                  style: theme.textTheme.bodySmall
                      ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                ),
              ],
            ),
          ),
          actions: <Widget>[
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Close'),
            ),
          ],
        );
      },
    );

/// Asks before blocking. Returns `true` only if the user confirmed.
///
/// Blocking is silent to the other person and drops their future messages, so
/// the copy says both of those things before it happens.
Future<bool> confirmBlockPeer(BuildContext context) async {
  final ThemeData theme = Theme.of(context);
  final bool? yes = await showGlassDialog<bool>(
    context,
    builder: (BuildContext context) => AlertDialog(
      icon: Icon(Icons.warning, color: theme.colorScheme.error),
      title: const Text('Block this person?'),
      content: const Text(
        'Their future messages will be dropped before they reach you, and this '
        'chat leaves your list. They are not told. You can start a new chat '
        'with their key later.',
      ),
      actions: <Widget>[
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: const Text('Cancel'),
        ),
        TextButton(
          key: const Key('block-confirm'),
          onPressed: () => Navigator.of(context).pop(true),
          child: Text(
            'Block',
            style: TextStyle(color: theme.colorScheme.error),
          ),
        ),
      ],
    ),
  );
  return yes ?? false;
}
