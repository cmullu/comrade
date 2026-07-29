/// Small shared pieces every screen reuses: cards, empty states, error text,
/// the mesh banner, and a two-pane layout primitive.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/models.dart';
import '../state/providers.dart';
import '../theme/breakpoints.dart';
import '../theme/comrade_theme.dart';

/// The bordered panel `styles.css` calls `.ledger-panel`/`.composer` and
/// Compose calls `OutlinedCard`.
class SectionCard extends StatelessWidget {
  const SectionCard({
    required this.child,
    this.title,
    this.elevated = false,
    this.padding = const EdgeInsets.all(16),
    super.key,
  });

  final Widget child;
  final String? title;
  final bool elevated;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) {
    final ComradeSurfaces surfaces = context.surfaces;
    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        color: elevated
            ? Theme.of(context).colorScheme.surfaceContainer
            : surfaces.panel,
        border: Border.all(color: surfaces.border),
        borderRadius: BorderRadius.circular(ComradeRadii.medium),
      ),
      padding: padding,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          if (title != null) ...<Widget>[
            Text(
              title!,
              style: Theme.of(context).textTheme.titleSmall?.copyWith(
                    color: Theme.of(context).colorScheme.primary,
                  ),
            ),
            const SizedBox(height: 8),
          ],
          child,
        ],
      ),
    );
  }
}

/// Inline, non-blocking error copy. Both frontends put failures next to the
/// control that caused them rather than in a modal.
class ErrorText extends StatelessWidget {
  const ErrorText(this.message, {super.key});

  final String? message;

  @override
  Widget build(BuildContext context) {
    if (message == null || message!.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Text(
        message!,
        style: Theme.of(context)
            .textTheme
            .bodySmall
            ?.copyWith(color: Theme.of(context).colorScheme.error),
      ),
    );
  }
}

/// Centred empty state with an optional call to action.
class EmptyState extends StatelessWidget {
  const EmptyState({
    required this.title,
    this.body,
    this.action,
    super.key,
  });

  final String title;
  final String? body;
  final Widget? action;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              Text(title, style: Theme.of(context).textTheme.titleMedium),
              if (body != null)
                Padding(
                  padding: const EdgeInsets.only(top: 4, bottom: 16),
                  child: Text(
                    body!,
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                  ),
                ),
              if (action != null) action!,
            ],
          ),
        ),
      );
}

/// A button that shows a spinner in place of its label while busy — the
/// `setBusy()` affordance the desktop SPA used on every submit.
class BusyButton extends StatelessWidget {
  const BusyButton({
    required this.label,
    required this.onPressed,
    this.busy = false,
    this.busyLabel,
    this.filled = true,
    this.expand = false,
    super.key,
  });

  final String label;
  final String? busyLabel;
  final VoidCallback? onPressed;
  final bool busy;
  final bool filled;
  final bool expand;

  @override
  Widget build(BuildContext context) {
    final Widget child = busy
        ? Row(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              const SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
              if (busyLabel != null) ...<Widget>[
                const SizedBox(width: 8),
                Text(busyLabel!),
              ],
            ],
          )
        : Text(label);
    final Widget button = filled
        ? FilledButton(onPressed: busy ? null : onPressed, child: child)
        : OutlinedButton(onPressed: busy ? null : onPressed, child: child);
    return expand ? SizedBox(width: double.infinity, child: button) : button;
  }
}

/// Persistent off-grid mesh indicator.
///
/// This is the one signal that still works with zero cellular or relay
/// reachability, so it stays visible rather than being a one-off toast —
/// exactly what to check when navigating somewhere with no signal at all.
/// Renders nothing when the mesh is inactive.
class MeshStatusBanner extends ConsumerWidget {
  const MeshStatusBanner({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final MeshStatus status =
        ref.watch(meshStatusProvider).value ?? const MeshStatus.idle();
    if (!status.active) return const SizedBox.shrink();
    final bool connected = status.peerCount > 0;
    final ColorScheme colors = Theme.of(context).colorScheme;
    return Material(
      color:
          connected ? colors.primaryContainer : colors.surfaceContainerHighest,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
        child: Row(
          children: <Widget>[
            Container(
              width: 8,
              height: 8,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: connected ? colors.primary : colors.onSurfaceVariant,
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                connected
                    ? 'Local mesh · ${status.peerCount} nearby'
                    : 'Local mesh · searching for nearby devices…',
                style: Theme.of(context).textTheme.labelMedium?.copyWith(
                      color: connected
                          ? colors.onPrimaryContainer
                          : colors.onSurfaceVariant,
                    ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// List beside detail above [Breakpoints.expanded]; detail replaces list
/// below it.
///
/// This is the single primitive that makes the chat tab behave like the
/// desktop `.view-vault` grid on a wide window and like Android's pushed
/// conversation on a phone, from one widget tree.
class ListDetailPane extends StatelessWidget {
  const ListDetailPane({
    required this.list,
    required this.detail,
    required this.hasSelection,
    this.placeholder,
    super.key,
  });

  final Widget list;

  /// Built only when [hasSelection]; on a narrow window it fully replaces the
  /// list, so it owns the whole screen exactly like Compose's conversation
  /// view did.
  final Widget Function() detail;
  final bool hasSelection;
  final Widget? placeholder;

  @override
  Widget build(BuildContext context) {
    final ComradeWindowClass windowClass = windowClassOf(context);
    if (!windowClass.supportsTwoPane) {
      return hasSelection ? detail() : list;
    }
    final double width =
        Breakpoints.conversationListWidth(MediaQuery.sizeOf(context).width);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        SizedBox(
          width: width,
          child: Material(color: context.surfaces.panel, child: list),
        ),
        VerticalDivider(width: 1, color: context.surfaces.border),
        Expanded(
          child: hasSelection
              ? detail()
              : (placeholder ??
                  const EmptyState(title: 'Select a conversation')),
        ),
      ],
    );
  }
}

/// A centred reading column, capped the way `.view-sabha` is
/// (`clamp(720px, 66vw, 1280px)`).
class ReadingColumn extends StatelessWidget {
  const ReadingColumn({required this.child, super.key});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    final double width = MediaQuery.sizeOf(context).width;
    if (Breakpoints.classify(width) == ComradeWindowClass.compact) return child;
    return Center(
      child: ConstrainedBox(
        constraints:
            BoxConstraints(maxWidth: Breakpoints.feedColumnWidth(width)),
        child: child,
      ),
    );
  }
}
