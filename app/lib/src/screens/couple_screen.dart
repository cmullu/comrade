/// The Couple Sandbox (Sakha/Sakhi): a Diffie-Hellman pairing handshake and a
/// shared, end-to-end encrypted CRDT ledger — Hisab-Kitab.
///
/// **This is desktop-only today, and that is the divergence being resolved.**
/// `desktop/ui` ships the pairing modal, the ledger form and live sync against
/// real backend commands (`pair_sakha`, `sakha_add_entry`, `sakha_read_ledger`,
/// `sync_ledger`); Android's settings screen says the couples space is "built
/// and tested only at the engine level, not usable from the app yet". Both
/// statements were true of their own platform. The unified app carries the
/// feature, because the engine work is done and the desktop UI proves it —
/// which means the Android "in the lab" copy is now the stale half, and
/// `settings_screen.dart` has been updated accordingly.
///
/// Above [Breakpoints.ultrawide] the two panels sit side by side, mirroring
/// `styles.css`'s `@media (min-width: 1600px)` rule for `.couple-body` — the
/// one place the existing desktop UI already spends spare ultrawide width.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/comrade_repository.dart';
import '../data/models.dart';
import '../state/settings_providers.dart';
import '../theme/breakpoints.dart';
import '../widgets/app_chrome.dart';
import '../widgets/peer_avatar.dart';

class CoupleScreen extends ConsumerWidget {
  const CoupleScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final AsyncValue<SakhaStatus> status = ref.watch(sakhaProvider);
    return status.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (Object e, StackTrace s) =>
          EmptyState(title: 'Could not read pairing status', body: '$e'),
      data: (SakhaStatus s) => s.paired ? const _Sandbox() : const _PairForm(),
    );
  }
}

class _PairForm extends ConsumerStatefulWidget {
  const _PairForm();

  @override
  ConsumerState<_PairForm> createState() => _PairFormState();
}

class _PairFormState extends ConsumerState<_PairForm> {
  final TextEditingController _payload = TextEditingController();
  String _role = 'sakha';
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _payload.dispose();
    super.dispose();
  }

  Future<void> _pair() async {
    final String payload = _payload.text.trim();
    if (!RegExp(r'^npub1[0-9a-z]+$', caseSensitive: false).hasMatch(payload)) {
      setState(() => _error = "Enter your partner's npub public key.");
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref
          .read(sakhaProvider.notifier)
          .pair(partnerNpub: payload, role: _role);
      if (mounted) setState(() => _busy = false);
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
  Widget build(BuildContext context) => Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 520),
            child: SectionCard(
              title: 'Open the Partner Portal',
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: <Widget>[
                  Text(
                    "Paste your partner's Nostr public key (npub1…) to perform "
                    'a Diffie-Hellman key exchange and open your shared, '
                    'end-to-end encrypted Sakha/Sakhi ledger. Relays only ever '
                    'see opaque ciphertext.',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    key: const Key('pair-payload'),
                    controller: _payload,
                    minLines: 2,
                    maxLines: 3,
                    enabled: !_busy,
                    decoration: const InputDecoration(
                      labelText: "Partner's public key",
                      hintText: 'npub1…',
                    ),
                  ),
                  const SizedBox(height: 12),
                  Text('Enter as',
                      style: Theme.of(context).textTheme.labelMedium),
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 8,
                    children: <Widget>[
                      for (final String role in <String>['sakha', 'sakhi'])
                        ChoiceChip(
                          label: Text(role == 'sakha' ? 'Sakha' : 'Sakhi'),
                          selected: _role == role,
                          onSelected: _busy
                              ? null
                              : (_) => setState(() => _role = role),
                        ),
                    ],
                  ),
                  ErrorText(_error),
                  const SizedBox(height: 8),
                  BusyButton(
                    key: const Key('pair-submit'),
                    label: 'Pair & enter',
                    busyLabel: 'Pairing…',
                    busy: _busy,
                    expand: true,
                    onPressed: _pair,
                  ),
                ],
              ),
            ),
          ),
        ),
      );
}

class _Sandbox extends ConsumerWidget {
  const _Sandbox();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final SakhaStatus status =
        ref.watch(sakhaProvider).value ?? const SakhaStatus.unpaired();
    final ComradeWindowClass windowClass = windowClassOf(context);

    final List<Widget> panels = <Widget>[
      _LedgerPanel(status: status),
      const _CoupleMediaPanel(),
    ];

    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          Row(
            children: <Widget>[
              PeerAvatar(
                title: status.role == 'sakhi' ? 'Sakhi' : 'Sakha',
                seed: status.partnerNpub ?? 'partner',
                size: 36,
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    Text(
                      'Couple Sandbox · ${status.role == 'sakhi' ? 'Sakhi' : 'Sakha'}',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    if (status.partnerNpub != null)
                      KeyText(status.partnerNpub!),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          if (windowClass == ComradeWindowClass.ultrawide)
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                for (final Widget panel in panels) ...<Widget>[
                  Flexible(
                      child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 620),
                    child: panel,
                  )),
                  const SizedBox(width: 16),
                ],
              ],
            )
          else
            for (final Widget panel in panels) ...<Widget>[
              panel,
              const SizedBox(height: 16),
            ],
        ],
      ),
    );
  }
}

class _LedgerPanel extends ConsumerStatefulWidget {
  const _LedgerPanel({required this.status});

  final SakhaStatus status;

  @override
  ConsumerState<_LedgerPanel> createState() => _LedgerPanelState();
}

class _LedgerPanelState extends ConsumerState<_LedgerPanel> {
  final TextEditingController _description = TextEditingController();
  final TextEditingController _amount = TextEditingController();
  final TextEditingController _paidBy = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _description.dispose();
    _amount.dispose();
    _paidBy.dispose();
    super.dispose();
  }

  Future<void> _add() async {
    final double? amount = double.tryParse(_amount.text.trim());
    if (_description.text.trim().isEmpty ||
        _paidBy.text.trim().isEmpty ||
        amount == null ||
        amount < 0) {
      setState(
          () => _error = 'Fill in what it was for, the amount, and who paid.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    await ref.read(ledgerProvider.notifier).addEntry(
          description: _description.text.trim(),
          amountInr: amount,
          paidBy: _paidBy.text.trim(),
        );
    if (!mounted) return;
    _description.clear();
    _amount.clear();
    _paidBy.clear();
    setState(() => _busy = false);
  }

  @override
  Widget build(BuildContext context) {
    final AsyncValue<String> ledger = ref.watch(ledgerProvider);
    return SectionCard(
      title: 'Hisab-Kitab — shared ledger',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Text(
            'A cryptographically isolated CRDT ledger shared only between '
            'paired partners. Encrypted end-to-end; relays see opaque '
            'ciphertext.',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _description,
            enabled: !_busy,
            decoration: const InputDecoration(labelText: 'What was it for?'),
          ),
          const SizedBox(height: 8),
          Row(
            children: <Widget>[
              Expanded(
                child: TextField(
                  controller: _amount,
                  enabled: !_busy,
                  keyboardType:
                      const TextInputType.numberWithOptions(decimal: true),
                  decoration: const InputDecoration(labelText: '₹ amount'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: TextField(
                  controller: _paidBy,
                  enabled: !_busy,
                  decoration: const InputDecoration(labelText: 'Paid by'),
                ),
              ),
            ],
          ),
          ErrorText(_error),
          const SizedBox(height: 8),
          Row(
            children: <Widget>[
              BusyButton(label: 'Add entry', busy: _busy, onPressed: _add),
              const SizedBox(width: 8),
              OutlinedButton(
                onPressed: () => ref.read(ledgerProvider.notifier).sync(),
                child: const Text('Sync ledger'),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surfaceContainerLowest,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Text(
              switch (ledger) {
                AsyncData<String>(:final String value)
                    when value.trim().isNotEmpty =>
                  value,
                AsyncError<String>(:final Object error) => '$error',
                AsyncLoading<String>() => 'Loading…',
                _ => 'No entries yet — add the first one above.',
              },
              style: Theme.of(context)
                  .textTheme
                  .bodySmall
                  ?.copyWith(fontFamily: 'monospace'),
            ),
          ),
        ],
      ),
    );
  }
}

class _CoupleMediaPanel extends StatelessWidget {
  const _CoupleMediaPanel();

  @override
  Widget build(BuildContext context) => SectionCard(
        title: 'Encrypted media',
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Text(
              'Photos and audio notes shared with your partner appear in your '
              'ordinary conversation with them, encrypted with the couple\'s '
              'Diffie-Hellman secret. The desktop shell kept a second copy of '
              'that thread here; the unified app does not duplicate it — open '
              'the conversation instead.',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
            ),
          ],
        ),
      );
}
