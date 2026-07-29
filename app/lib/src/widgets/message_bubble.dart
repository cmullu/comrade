import 'package:flutter/material.dart';

import '../data/models.dart';
import '../theme/comrade_theme.dart';
import '../util/display_name.dart';

/// Delivery-status glyph shown on outgoing bubbles: ✓ sent, ✓✓
/// delivered/read.
///
/// Ported from `ChatsScreen.kt`'s `statusGlyph`, which treats a *missing*
/// status as "sent" — an outgoing message the store has no receipt for yet is
/// still one we handed to a relay, and a bubble with no tick at all reads as
/// "didn't send".
String statusGlyph(MessageStatus? status) => switch (status) {
      MessageStatus.read || MessageStatus.delivered => '✓✓',
      MessageStatus.sent || null => '✓',
    };

/// Delivery ticks. Only "read" is tinted with the accent — the same signal
/// both existing frontends used (`bubble-status.read` on desktop, the primary
/// colour on Android).
class StatusTicks extends StatelessWidget {
  const StatusTicks(this.status, {super.key});

  final MessageStatus? status;

  @override
  Widget build(BuildContext context) {
    final ColorScheme colors = Theme.of(context).colorScheme;
    return Text(
      statusGlyph(status),
      semanticsLabel: switch (status) {
        MessageStatus.read => 'Read',
        MessageStatus.delivered => 'Delivered',
        MessageStatus.sent || null => 'Sent',
      },
      style: Theme.of(context).textTheme.labelSmall?.copyWith(
            color:
                status == MessageStatus.read ? colors.primary : colors.outline,
          ),
    );
  }
}

/// Centred "Today" / "Yesterday" / "12 Jul 2026" pill between days.
class DaySeparator extends StatelessWidget {
  const DaySeparator(this.label, {super.key});

  final String label;

  @override
  Widget build(BuildContext context) {
    final ColorScheme colors = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Center(
        child: Container(
          decoration: BoxDecoration(
            color: colors.surfaceContainerHighest.withValues(alpha: 0.7),
            borderRadius: BorderRadius.circular(ComradeRadii.small),
          ),
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
          child: Text(
            label,
            style: Theme.of(context)
                .textTheme
                .labelSmall
                ?.copyWith(color: colors.onSurfaceVariant),
          ),
        ),
      ),
    );
  }
}

/// A small quoted line rendered above a reply's own text.
class QuotedPreview extends StatelessWidget {
  const QuotedPreview(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    final ColorScheme colors = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(bottom: 4),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: colors.surface.withValues(alpha: 0.6),
        borderRadius: BorderRadius.circular(ComradeRadii.extraSmall),
      ),
      child: Text(
        text,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: Theme.of(context)
            .textTheme
            .bodySmall
            ?.copyWith(color: colors.onSurfaceVariant),
      ),
    );
  }
}

/// One text message.
///
/// Long-press (touch) or the hover reply affordance (pointer) starts a reply —
/// Android used `combinedClickable`'s long-press, desktop used a button that
/// appeared on `:hover`. Both are wired here: a phone gets the long-press, a
/// mouse gets a visible target, and neither platform loses its idiom.
class MessageBubble extends StatefulWidget {
  const MessageBubble({
    required this.message,
    required this.onReply,
    this.quotedText,
    this.maxWidth = 300,
    super.key,
  });

  final MessageInfo message;
  final String? quotedText;
  final VoidCallback onReply;
  final double maxWidth;

  @override
  State<MessageBubble> createState() => _MessageBubbleState();
}

class _MessageBubbleState extends State<MessageBubble> {
  bool _hovering = false;

  @override
  Widget build(BuildContext context) {
    final MessageInfo msg = widget.message;
    final ColorScheme colors = Theme.of(context).colorScheme;
    final bool out = msg.outgoing;

    final Widget bubble = Container(
      constraints: BoxConstraints(maxWidth: widget.maxWidth),
      decoration: BoxDecoration(
        color: out ? colors.primaryContainer : colors.surfaceContainerHighest,
        borderRadius: BorderRadius.only(
          topLeft: const Radius.circular(ComradeRadii.bubble),
          topRight: const Radius.circular(ComradeRadii.bubble),
          bottomLeft: Radius.circular(
            out ? ComradeRadii.bubble : ComradeRadii.bubbleTail,
          ),
          bottomRight: Radius.circular(
            out ? ComradeRadii.bubbleTail : ComradeRadii.bubble,
          ),
        ),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          if (widget.quotedText != null) QuotedPreview(widget.quotedText!),
          Text(msg.content, style: Theme.of(context).textTheme.bodyLarge),
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              mainAxisAlignment: MainAxisAlignment.end,
              children: <Widget>[
                Text(
                  clockTime(msg.createdAt),
                  style: Theme.of(context)
                      .textTheme
                      .labelSmall
                      ?.copyWith(color: colors.outline),
                ),
                if (out) ...<Widget>[
                  const SizedBox(width: 4),
                  StatusTicks(msg.status),
                ],
              ],
            ),
          ),
        ],
      ),
    );

    return MouseRegion(
      onEnter: (_) => setState(() => _hovering = true),
      onExit: (_) => setState(() => _hovering = false),
      child: GestureDetector(
        onLongPress: widget.onReply,
        child: Row(
          mainAxisAlignment:
              out ? MainAxisAlignment.end : MainAxisAlignment.start,
          crossAxisAlignment: CrossAxisAlignment.center,
          children: <Widget>[
            if (out && _hovering) _replyButton(context),
            Flexible(child: bubble),
            if (!out && _hovering) _replyButton(context),
          ],
        ),
      ),
    );
  }

  Widget _replyButton(BuildContext context) => IconButton(
        onPressed: widget.onReply,
        iconSize: 18,
        visualDensity: VisualDensity.compact,
        tooltip: 'Reply',
        icon: const Icon(Icons.reply),
      );
}
