import 'package:flutter/material.dart';

import '../data/models.dart';
import '../theme/comrade_theme.dart';
import '../util/display_name.dart';
import '../util/message_reactions.dart';

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

/// "Unread messages" line marking where the reader left off.
///
/// A full-width rule rather than a day-style pill: it is a boundary through the
/// thread, not a label on one message, and it has to be findable at a glance
/// after scrolling away from it. Mirrors `ChatsScreen.kt`'s `UnreadSeparator`.
class UnreadSeparator extends StatelessWidget {
  const UnreadSeparator({super.key});

  @override
  Widget build(BuildContext context) {
    final ColorScheme colors = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: <Widget>[
          Expanded(
            child: Divider(color: colors.primary.withValues(alpha: 0.5)),
          ),
          const SizedBox(width: 8),
          Text(
            'Unread messages',
            key: const Key('unread-divider'),
            style: Theme.of(context)
                .textTheme
                .labelSmall
                ?.copyWith(color: colors.primary),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Divider(color: colors.primary.withValues(alpha: 0.5)),
          ),
        ],
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

/// The reaction chips under one bubble.
///
/// Tapping a chip toggles *your* reaction in it — adding yours if you were not in
/// it, taking yours back if you were — which is why [ReactionChip.mine] has to be
/// drawn: without the highlight, tapping is a coin flip between the two.
///
/// Renders nothing for an empty list, so a message with no reactions costs no
/// vertical space. Mirrors `ChatsScreen.kt`'s `ReactionChips`.
class ReactionChipRow extends StatelessWidget {
  const ReactionChipRow({
    required this.chips,
    required this.onToggle,
    this.outgoing = false,
    super.key,
  });

  final List<ReactionChip> chips;
  final void Function(String emoji) onToggle;
  final bool outgoing;

  @override
  Widget build(BuildContext context) {
    if (chips.isEmpty) return const SizedBox.shrink();
    final ColorScheme colors = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(top: 3),
      child: Row(
        key: const Key('dm-reactions'),
        mainAxisSize: MainAxisSize.min,
        mainAxisAlignment:
            outgoing ? MainAxisAlignment.end : MainAxisAlignment.start,
        children: <Widget>[
          for (final ReactionChip chip in chips)
            Padding(
              padding: const EdgeInsets.only(right: 4),
              child: InkWell(
                key: Key('reaction-${chip.emoji}'),
                borderRadius: BorderRadius.circular(ComradeRadii.small),
                onTap: () => onToggle(chip.emoji),
                child: Container(
                  decoration: BoxDecoration(
                    color: chip.mine
                        ? colors.primary.withValues(alpha: 0.18)
                        : colors.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(ComradeRadii.small),
                  ),
                  padding:
                      const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: <Widget>[
                      Text(chip.emoji,
                          style: Theme.of(context).textTheme.labelLarge),
                      // The count is only informative once more than one person
                      // is in it; "🔥 1" is noise next to "🔥".
                      if (chip.count > 1) ...<Widget>[
                        const SizedBox(width: 3),
                        Text(
                          '${chip.count}',
                          style:
                              Theme.of(context).textTheme.labelSmall?.copyWith(
                                    color: chip.mine
                                        ? colors.primary
                                        : colors.onSurfaceVariant,
                                  ),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// Makes [child] repliable and reactable: a rightward drag replies, a long press
/// opens the reaction/action sheet, and a reply button appears on hover (pointer).
///
/// **Long press used to be reply.** It is react now, and reply moved to the
/// swipe — Telegram's split, and the one that gives reactions a gesture to live
/// on instead of leaving the one discoverable interaction on a message pointed at
/// the less common of the two. The hover button stays as it was, because a mouse
/// has neither gesture.
///
/// The numbers behind the drag live in `util/message_reactions.dart` so a unit
/// test can pin them and the Kotlin side can carry the same ones.
///
/// Shared by text and media bubbles so "reply to that photo" behaves exactly
/// like "reply to that message" — including which side of the bubble the button
/// appears on, which is the outside edge in both directions.
class ReplyAffordance extends StatefulWidget {
  const ReplyAffordance({
    required this.child,
    required this.onReply,
    required this.outgoing,
    this.onLongPress,
    super.key,
  });

  final Widget child;

  /// Null where replying is not offered (a not-yet-sent item, a test harness
  /// with no controller): the swipe and the hover button both disappear
  /// rather than becoming gestures that do nothing.
  final VoidCallback? onReply;

  /// Opens the reaction/action sheet. Null where reacting is not offered, in
  /// which case a long press does nothing at all rather than falling back to
  /// reply — two gestures doing one thing is how a user learns the wrong one.
  final VoidCallback? onLongPress;

  final bool outgoing;

  @override
  State<ReplyAffordance> createState() => _ReplyAffordanceState();
}

class _ReplyAffordanceState extends State<ReplyAffordance> {
  bool _hovering = false;

  /// Accumulated horizontal drag, in logical pixels. Reset on release whether or
  /// not the gesture fired: the bubble is a nudge with a spring, not a pane that
  /// stays open — the reply chip above the composer is the result.
  double _drag = 0;

  @override
  Widget build(BuildContext context) {
    final bool out = widget.outgoing;
    final ColorScheme colors = Theme.of(context).colorScheme;
    final double offset = replySwipeOffsetDp(_drag);
    final bool armed = replySwipeTriggered(_drag);
    final Widget row = Row(
      mainAxisAlignment: out ? MainAxisAlignment.end : MainAxisAlignment.start,
      children: <Widget>[
        if (out && _hovering && widget.onReply != null) _button(),
        Flexible(child: widget.child),
        if (!out && _hovering && widget.onReply != null) _button(),
      ],
    );
    if (widget.onReply == null && widget.onLongPress == null) return row;

    // The arrow fades in behind the bubble as the drag approaches the trigger, so
    // the gesture teaches itself — there is no other way to discover it.
    final Widget dragged = Stack(
      children: <Widget>[
        if (offset > 1)
          Positioned(
            left: 4,
            top: 0,
            bottom: 0,
            child: Center(
              child: Opacity(
                opacity: (offset / replySwipeTriggerDp).clamp(0.0, 1.0),
                child: Icon(
                  Icons.reply,
                  size: 20,
                  color: armed ? colors.primary : colors.outline,
                ),
              ),
            ),
          ),
        Transform.translate(offset: Offset(offset, 0), child: row),
      ],
    );

    return MouseRegion(
      onEnter: (_) => setState(() => _hovering = true),
      onExit: (_) => setState(() => _hovering = false),
      child: GestureDetector(
        onLongPress: widget.onLongPress,
        onHorizontalDragUpdate: widget.onReply == null
            ? null
            : (DragUpdateDetails d) => setState(() => _drag += d.delta.dx),
        onHorizontalDragEnd: widget.onReply == null
            ? null
            : (DragEndDetails _) {
                final bool fire = replySwipeTriggered(_drag);
                setState(() => _drag = 0);
                if (fire) widget.onReply!.call();
              },
        // A drag cancelled by the scroll view winning the arena must not fire.
        onHorizontalDragCancel: () => setState(() => _drag = 0),
        child: dragged,
      ),
    );
  }

  Widget _button() => IconButton(
        onPressed: widget.onReply,
        iconSize: 18,
        visualDensity: VisualDensity.compact,
        tooltip: 'Reply',
        icon: const Icon(Icons.reply),
      );
}

/// What a long press on a message opens: the quick reaction row, then the
/// actions.
///
/// A bottom sheet rather than the floating row Telegram anchors over the bubble.
/// The reachability argument decides it: an anchored popup has to be positioned
/// against a bubble that may be at the very top or bottom of the list, and the
/// failure mode is a row half off screen. A sheet is always in the same place and
/// always within a thumb's reach. Mirrors `ChatsScreen.kt`'s `MessageActionSheet`.
///
/// [myReaction] is the emoji this device has already sent on this message, if any
/// — highlighted, so it is visible that tapping it again removes it.
class MessageActionSheet extends StatelessWidget {
  const MessageActionSheet({
    required this.onReact,
    required this.onMoreEmoji,
    required this.onReply,
    required this.onCopy,
    this.myReaction,
    super.key,
  });

  final String? myReaction;
  final void Function(String emoji) onReact;
  final VoidCallback onMoreEmoji;
  final VoidCallback onReply;
  final VoidCallback onCopy;

  @override
  Widget build(BuildContext context) {
    final ColorScheme colors = Theme.of(context).colorScheme;
    return SafeArea(
      child: Column(
        key: const Key('message-actions'),
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: <Widget>[
                for (final String emoji in quickReactions)
                  InkWell(
                    key: Key('react-$emoji'),
                    customBorder: const CircleBorder(),
                    onTap: () => onReact(emoji),
                    child: Container(
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: emoji == myReaction
                            ? colors.primary.withValues(alpha: 0.18)
                            : Colors.transparent,
                      ),
                      padding: const EdgeInsets.all(8),
                      child: Text(
                        emoji,
                        style: Theme.of(context).textTheme.headlineSmall,
                      ),
                    ),
                  ),
                // Anything outside the six. Same picker the composer's emoji
                // button opens, so there is one picker in the app.
                IconButton(
                  key: const Key('react-more'),
                  onPressed: onMoreEmoji,
                  tooltip: 'More emoji',
                  icon: const Icon(Icons.add),
                ),
              ],
            ),
          ),
          const Divider(height: 1),
          ListTile(
            key: const Key('action-reply'),
            leading: const Icon(Icons.reply),
            title: const Text('Reply'),
            onTap: onReply,
          ),
          ListTile(
            key: const Key('action-copy'),
            leading: const Icon(Icons.content_copy),
            title: const Text('Copy text'),
            onTap: onCopy,
          ),
        ],
      ),
    );
  }
}

/// One text message.
class MessageBubble extends StatelessWidget {
  const MessageBubble({
    required this.message,
    required this.onReply,
    this.onLongPress,
    this.chips = const <ReactionChip>[],
    this.onToggleReaction,
    this.quotedText,
    this.maxWidth = 300,
    super.key,
  });

  final MessageInfo message;
  final String? quotedText;
  final VoidCallback onReply;

  /// Opens the reaction/action sheet. Optional so existing callers and tests that
  /// only care about rendering keep working with no sheet wired up.
  final VoidCallback? onLongPress;

  /// The reaction chips to draw beneath this bubble, already grouped.
  final List<ReactionChip> chips;
  final void Function(String emoji)? onToggleReaction;
  final double maxWidth;

  @override
  Widget build(BuildContext context) {
    final MessageInfo msg = message;
    final ColorScheme colors = Theme.of(context).colorScheme;
    final bool out = msg.outgoing;

    final Widget bubble = Container(
      constraints: BoxConstraints(maxWidth: maxWidth),
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
          if (quotedText != null) QuotedPreview(quotedText!),
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

    final void Function(String)? toggle = onToggleReaction;
    return Column(
      crossAxisAlignment:
          out ? CrossAxisAlignment.end : CrossAxisAlignment.start,
      children: <Widget>[
        ReplyAffordance(
          onReply: onReply,
          onLongPress: onLongPress,
          outgoing: out,
          child: bubble,
        ),
        if (toggle != null)
          ReactionChipRow(chips: chips, onToggle: toggle, outgoing: out),
      ],
    );
  }
}
