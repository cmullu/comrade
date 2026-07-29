/// Find people: search the public directory relays, or paste a key.
///
/// Port of `ChatsScreen.kt`'s `NewChatScreen`. The honesty copy about search
/// is kept verbatim — names are not unique, and the safest connection is a
/// key swapped directly.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/comrade_repository.dart';
import '../../data/models.dart';
import '../../state/chat_providers.dart';
import '../../state/providers.dart';
import '../../util/display_name.dart';
import '../../widgets/app_chrome.dart';
import '../../widgets/peer_avatar.dart';

class NewChatScreen extends ConsumerStatefulWidget {
  const NewChatScreen({required this.onOpen, super.key});

  final void Function(ChatTarget target) onOpen;

  @override
  ConsumerState<NewChatScreen> createState() => _NewChatScreenState();
}

class _NewChatScreenState extends ConsumerState<NewChatScreen> {
  final TextEditingController _query = TextEditingController();

  bool _searching = false;
  bool _searched = false;
  List<FoundProfile> _results = const <FoundProfile>[];
  String? _error;

  @override
  void dispose() {
    _query.dispose();
    super.dispose();
  }

  String get _trimmed => _query.text.trim();

  bool get _isKey => _trimmed.startsWith('npub1') && _trimmed.length > 20;

  Future<void> _search() async {
    if (_trimmed.isEmpty || _isKey) return;
    setState(() {
      _searching = true;
      _error = null;
    });
    try {
      final List<FoundProfile> found =
          await ref.read(comradeRepositoryProvider).searchProfiles(_trimmed);
      if (!mounted) return;
      setState(() {
        _results = found;
        _searched = true;
        _searching = false;
      });
    } on ComradeException catch (e) {
      if (mounted) {
        setState(() {
          _error = e.message;
          _searching = false;
        });
      }
    }
  }

  Future<void> _startChat(String npub, String? username) async {
    try {
      // Pin the key only (trust-on-first-use). The published @handle is cached
      // by the search itself; an alias stays the user's to set.
      final ContactInfo saved =
          await ref.read(comradeRepositoryProvider).addContact(npub: npub);
      if (!mounted) return;
      widget.onOpen(
        ChatTarget(
          peer: saved.npub,
          alias: saved.alias.isEmpty ? null : saved.alias,
          username: username ?? saved.name,
        ),
      );
    } on ComradeException catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final List<ContactInfo> contacts =
        ref.watch(contactsProvider).value ?? const <ContactInfo>[];

    return ReadingColumn(
      child: ListView(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        children: <Widget>[
          TextField(
            key: const Key('newchat-query'),
            controller: _query,
            onChanged: (_) => setState(() => _searched = false),
            onSubmitted: (_) => _isKey ? _startChat(_trimmed, null) : _search(),
            decoration:
                const InputDecoration(labelText: '@username or npub1… key'),
          ),
          const SizedBox(height: 10),
          if (_isKey)
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: () => _startChat(_trimmed, null),
                child: Text('Start chat with ${shortNpub(_trimmed)}'),
              ),
            )
          else
            BusyButton(
              label: 'Search',
              busyLabel: 'Searching…',
              busy: _searching,
              filled: false,
              expand: true,
              onPressed: _trimmed.isEmpty ? null : _search,
            ),
          const SizedBox(height: 10),
          Text(
            'Search asks public directory relays, so it only finds people who '
            'published their username. Names are not unique — always glance at '
            'the key. The safest way to connect is swapping npub keys directly.',
            style: theme.textTheme.bodySmall
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
          ErrorText(_error),
          if (_searched && _results.isEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 10),
              child: Text(
                'No one found under that name. They may not have published it '
                '— ask them for their npub key instead.',
                style: theme.textTheme.bodySmall
                    ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
              ),
            ),
          for (final FoundProfile found in _results)
            _PersonRow(
              title:
                  found.name != null ? '@${found.name}' : shortNpub(found.npub),
              seed: found.npub,
              subtitle: shortNpub(found.npub) +
                  (found.about != null ? ' · ${found.about}' : ''),
              onTap: () => _startChat(found.npub, found.name),
            ),
          if (contacts.isNotEmpty) ...<Widget>[
            Padding(
              padding: const EdgeInsets.only(top: 16, bottom: 4),
              child: Text(
                'Contacts',
                style: theme.textTheme.titleSmall
                    ?.copyWith(color: theme.colorScheme.primary),
              ),
            ),
            for (final ContactInfo contact in contacts)
              _PersonRow(
                title: peerTitle(contact.npub, contact.alias, contact.name),
                seed: contact.npub,
                subtitle: shortNpub(contact.npub),
                onTap: () => widget.onOpen(
                  ChatTarget(
                    peer: contact.npub,
                    alias: contact.alias.isEmpty ? null : contact.alias,
                    username: contact.name,
                  ),
                ),
              ),
          ],
        ],
      ),
    );
  }
}

class _PersonRow extends StatelessWidget {
  const _PersonRow({
    required this.title,
    required this.seed,
    required this.subtitle,
    required this.onTap,
  });

  final String title;
  final String seed;
  final String subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: Row(
            children: <Widget>[
              PeerAvatar(title: title, seed: seed, size: 40),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    Text(title, style: Theme.of(context).textTheme.titleSmall),
                    KeyText(subtitle, short: false),
                  ],
                ),
              ),
            ],
          ),
        ),
      );
}
