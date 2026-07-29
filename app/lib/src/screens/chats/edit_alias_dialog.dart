/// The contact-alias editor: a local petname for this key, shown above their
/// self-published @handle. Clearing the field removes the alias.
///
/// Port of `MainActivity.kt`'s `EditAliasDialog`, reached from the
/// conversation header. Desktop has no alias editor at all — its
/// `displayName()` only knows the published handle — so this is Android
/// behaviour the unified app adopts wholesale.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/comrade_repository.dart';
import '../../data/models.dart';
import '../../state/providers.dart';
import '../../util/display_name.dart';
import '../../widgets/app_chrome.dart';

/// Shows the editor and returns the saved contact, or `null` if cancelled.
Future<ContactInfo?> showEditAliasDialog(
  BuildContext context, {
  required String peer,
  String? currentAlias,
}) =>
    showDialog<ContactInfo>(
      context: context,
      builder: (BuildContext context) =>
          _EditAliasDialog(peer: peer, currentAlias: currentAlias),
    );

class _EditAliasDialog extends ConsumerStatefulWidget {
  const _EditAliasDialog({required this.peer, this.currentAlias});

  final String peer;
  final String? currentAlias;

  @override
  ConsumerState<_EditAliasDialog> createState() => _EditAliasDialogState();
}

class _EditAliasDialogState extends ConsumerState<_EditAliasDialog> {
  late final TextEditingController _value =
      TextEditingController(text: widget.currentAlias ?? '');
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
      final ContactInfo saved =
          await ref.read(contactsProvider.notifier).setAlias(
                npub: widget.peer,
                alias: _value.text.trim(),
              );
      if (mounted) Navigator.of(context).pop(saved);
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
          _error = 'Could not save.';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: const Text('Alias for this contact'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            TextField(
              key: const Key('alias-input'),
              controller: _value,
              enabled: !_busy,
              autofocus: true,
              onSubmitted: (_) => _save(),
              decoration: const InputDecoration(labelText: 'Alias'),
            ),
            const SizedBox(height: 8),
            Text(
              'Only you see this name. It\'s pinned to the key '
              '${shortNpub(widget.peer)} — leave it empty to fall back to '
              'their public username.',
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
            key: const Key('alias-save'),
            onPressed: _busy ? null : _save,
            child: Text(_busy ? 'Saving…' : 'Save'),
          ),
        ],
      );
}
