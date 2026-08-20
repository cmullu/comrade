/// Tara — the reflective companion (wellbeing pillar #4). A private space to
/// think out loud: reflective prompts, feeling-mirroring, brainstorming.
///
/// Two honesty gates (AUDIT §8) shape everything on this screen:
///  * Tara is NOT therapy and never presents as one — the first-open explainer
///    and the persistent footer both say so, and any message carrying distress
///    cues switches the reply into a hand-off to real crisis helplines.
///  * Everything is on-device: the reply engine is deterministic Rust code and
///    the thread lives only in the encrypted store. No network, no cloud.
///
/// Port of `ui/TaraScreen.kt`. Desktop has no Tara at all (five `tara_*` Tauri
/// commands are registered with no caller) — Android-only until now.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/models.dart';
import '../state/tara_providers.dart';
import '../util/chat_thread.dart';
import '../widgets/app_chrome.dart';
import '../widgets/glass_surface.dart';

class TaraScreen extends ConsumerWidget {
  const TaraScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final bool accepted = ref.watch(taraOptInProvider);
    return accepted ? const _TaraThread() : const _TaraExplainer();
  }
}

/// First-open explainer — the user opts in knowing exactly what Tara is not.
class _TaraExplainer extends ConsumerWidget {
  const _TaraExplainer();

  @override
  Widget build(BuildContext context, WidgetRef ref) => Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 560),
            child: SectionCard(
              elevated: true,
              padding: const EdgeInsets.all(20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: <Widget>[
                  Text('Meet Tara',
                      style: Theme.of(context).textTheme.headlineSmall),
                  const SizedBox(height: 12),
                  Text(
                    'A private space to reflect, vent, or think a decision '
                    'through. Tara listens and asks questions — she doesn\'t '
                    'judge, and nothing you say ever leaves this device.',
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                  const SizedBox(height: 12),
                  Text(
                    'Tara is not a therapist, doctor, or crisis service, and '
                    'she never gives medical advice. If you\'re in crisis, '
                    'she\'ll point you to real helplines — please use them.',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                  ),
                  const SizedBox(height: 16),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton(
                      key: const Key('tara-accept'),
                      onPressed: () =>
                          ref.read(taraOptInProvider.notifier).accept(),
                      child: const Text("I understand — let's talk"),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
}

class _TaraThread extends ConsumerStatefulWidget {
  const _TaraThread();

  @override
  ConsumerState<_TaraThread> createState() => _TaraThreadState();
}

class _TaraThreadState extends ConsumerState<_TaraThread> {
  final TextEditingController _draft = TextEditingController();
  final ScrollController _scroll = ScrollController();

  @override
  void dispose() {
    _draft.dispose();
    _scroll.dispose();
    super.dispose();
  }

  /// Follow the conversation only while the reader is already at the bottom —
  /// scrolling back to re-read something must not be yanked away (the same
  /// rule the chat thread follows).
  void _followIfAtBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scroll.hasClients) return;
      final bool atBottom = isNearBottomByOffset(
        pixels: _scroll.position.pixels,
        maxScrollExtent: _scroll.position.maxScrollExtent,
      );
      if (atBottom) _scroll.jumpTo(_scroll.position.maxScrollExtent);
    });
  }

  Future<void> _send() async {
    final String text = _draft.text;
    if (text.trim().isEmpty) return;
    _draft.clear();
    await ref.read(taraProvider.notifier).send(text);
  }

  Future<void> _confirmClear() async {
    final bool? yes = await showGlassDialog<bool>(
      context,
      builder: (BuildContext context) => AlertDialog(
        title: const Text('Clear this conversation?'),
        content: const Text(
          'Every message will be removed from this device. There is no other '
          'copy.',
        ),
        actions: <Widget>[
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Clear'),
          ),
        ],
      ),
    );
    if (yes ?? false) await ref.read(taraProvider.notifier).clearThread();
  }

  @override
  Widget build(BuildContext context) {
    final TaraState state = ref.watch(taraProvider);
    _followIfAtBottom();

    return ReadingColumn(
      child: Column(
        children: <Widget>[
          Expanded(child: _thread(context, state)),
          _composer(context, state),
        ],
      ),
    );
  }

  Widget _thread(BuildContext context, TaraState state) {
    if (state.thread == null) {
      return const Padding(
        padding: EdgeInsets.only(top: 24),
        child: Center(child: CircularProgressIndicator()),
      );
    }
    final List<Widget> children = <Widget>[];

    if (state.thread!.isEmpty && state.pendingUser == null) {
      if (state.opener != null) {
        children.add(TaraBubble(text: state.opener!, fromTara: true));
      }
    } else {
      for (final TaraMessageInfo msg in state.thread!) {
        children.add(TaraBubble(text: msg.text, fromTara: msg.fromTara));
        if (msg.crisis && msg.fromTara) {
          children.add(CrisisCard(resources: state.resources));
        }
      }
    }

    // The turn in flight.
    if (state.pendingUser != null) {
      children.add(TaraBubble(text: state.pendingUser!, fromTara: false));
    }
    if (state.thinking) children.add(const ThinkingBubble());
    final StreamingReply? streaming = state.streaming;
    if (streaming != null) {
      if (streaming.text.isNotEmpty) {
        children.add(
          TaraBubble(
            key: const Key('tara-streaming'),
            text: streaming.text,
            fromTara: true,
          ),
        );
      }
      // A crisis hand-off's card lands with the whole reply, at once.
      if (streaming.crisis) {
        children.add(CrisisCard(resources: state.resources));
      }
    }

    return ListView.separated(
      key: const Key('tara-thread'),
      controller: _scroll,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      itemCount: children.length,
      separatorBuilder: (_, __) => const SizedBox(height: 8),
      itemBuilder: (BuildContext context, int index) => children[index],
    );
  }

  Widget _composer(BuildContext context, TaraState state) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: SectionCard(
          elevated: true,
          padding: const EdgeInsets.all(10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              Row(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: <Widget>[
                  Expanded(
                    child: TextField(
                      key: const Key('tara-input'),
                      controller: _draft,
                      minLines: 1,
                      maxLines: 4,
                      textInputAction: TextInputAction.send,
                      onSubmitted: (_) => _send(),
                      decoration:
                          const InputDecoration(hintText: 'Think out loud…'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  FilledButton(
                    key: const Key('tara-send'),
                    onPressed: state.busy ? null : _send,
                    child: Text(state.busy ? '…' : 'Send'),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              Row(
                children: <Widget>[
                  Expanded(
                    child: Text(
                      'Not a therapist or crisis service. Stays on this device.',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color:
                                Theme.of(context).colorScheme.onSurfaceVariant,
                          ),
                    ),
                  ),
                  if (state.hasThread && !state.busy)
                    TextButton(
                      key: const Key('tara-clear'),
                      onPressed: _confirmClear,
                      child: const Text('Clear'),
                    ),
                ],
              ),
              ErrorText(state.error),
            ],
          ),
        ),
      );
}

/// One Tara/user bubble. Tara sits left, you sit right.
class TaraBubble extends StatelessWidget {
  const TaraBubble({required this.text, required this.fromTara, super.key});

  final String text;
  final bool fromTara;

  @override
  Widget build(BuildContext context) {
    final ColorScheme colors = Theme.of(context).colorScheme;
    return Row(
      mainAxisAlignment:
          fromTara ? MainAxisAlignment.start : MainAxisAlignment.end,
      children: <Widget>[
        Flexible(
          child: Container(
            constraints: const BoxConstraints(maxWidth: 420),
            decoration: BoxDecoration(
              color: fromTara
                  ? colors.surfaceContainerHighest
                  : colors.primaryContainer,
              borderRadius: BorderRadius.circular(16),
            ),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            child: Text(text, style: Theme.of(context).textTheme.bodyLarge),
          ),
        ),
      ],
    );
  }
}

/// The "…" that fills the gap between sending and the first streamed chunk.
class ThinkingBubble extends StatefulWidget {
  const ThinkingBubble({super.key});

  @override
  State<ThinkingBubble> createState() => _ThinkingBubbleState();
}

class _ThinkingBubbleState extends State<ThinkingBubble>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1050),
  )..repeat();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: _controller,
        builder: (BuildContext context, Widget? _) {
          final int dots = 1 + (_controller.value * 3).floor().clamp(0, 2);
          return TaraBubble(
            key: const Key('tara-thinking'),
            text: '.' * dots,
            fromTara: true,
          );
        },
      );
}

/// Real places to turn — rendered under any reply that detected distress.
class CrisisCard extends StatelessWidget {
  const CrisisCard({required this.resources, super.key});

  final List<CrisisResourceInfo> resources;

  @override
  Widget build(BuildContext context) {
    if (resources.isEmpty) return const SizedBox.shrink();
    final ColorScheme colors = Theme.of(context).colorScheme;
    return Container(
      key: const Key('tara-crisis-card'),
      width: double.infinity,
      decoration: BoxDecoration(
        color: colors.errorContainer,
        borderRadius: BorderRadius.circular(16),
      ),
      padding: const EdgeInsets.all(14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Text(
            "You don't have to carry this alone",
            style: Theme.of(context)
                .textTheme
                .titleSmall
                ?.copyWith(color: colors.onErrorContainer),
          ),
          for (final CrisisResourceInfo r in resources)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: <Widget>[
                  Text(
                    '${r.name} — ${r.contact}',
                    style: Theme.of(context)
                        .textTheme
                        .bodyMedium
                        ?.copyWith(color: colors.onErrorContainer),
                  ),
                  Text(
                    r.note,
                    style: Theme.of(context)
                        .textTheme
                        .bodySmall
                        ?.copyWith(color: colors.onErrorContainer),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}
