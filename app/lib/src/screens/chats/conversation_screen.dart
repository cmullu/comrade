/// One conversation: a time-ordered merge of text and media, a composer, and
/// the scroll discipline the Compose version fought for.
///
/// **The scroll rule is a deliberate fix, not an accident** (commit
/// `a76bacf`, "android/chat: stop yanking readers to the bottom"). A reload
/// must not drag a reader who scrolled up in history back to the newest
/// message: auto-scroll happens only on first load, or when they were already
/// near the bottom. Otherwise the jump-to-latest button lights up instead. The
/// rule itself lives in `util/chat_thread.dart` and is unit-tested.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/models.dart';
import '../../state/chat_providers.dart';
import '../../util/chat_thread.dart';
import '../../widgets/app_chrome.dart';
import '../../widgets/composer.dart';
import '../../widgets/media_attachment.dart';
import '../../widgets/message_bubble.dart';

class ConversationScreen extends ConsumerStatefulWidget {
  const ConversationScreen({required this.peer, super.key});

  final String peer;

  @override
  ConsumerState<ConversationScreen> createState() => _ConversationScreenState();
}

class _ConversationScreenState extends ConsumerState<ConversationScreen> {
  final ScrollController _scroll = ScrollController();
  final TextEditingController _draft = TextEditingController();
  final FocusNode _composerFocus = FocusNode();

  /// Keyed on the peer so switching conversations resets the bookkeeping.
  bool _loadedOnce = false;
  int _knownItemCount = 0;
  bool _newMessagesBelow = false;
  bool _atBottom = true;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
  }

  @override
  void didUpdateWidget(covariant ConversationScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.peer != widget.peer) {
      _loadedOnce = false;
      _knownItemCount = 0;
      _newMessagesBelow = false;
      _draft.clear();
    }
  }

  @override
  void dispose() {
    _scroll
      ..removeListener(_onScroll)
      ..dispose();
    _draft.dispose();
    _composerFocus.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_scroll.hasClients) return;
    final bool near = isNearBottomByOffset(
      pixels: _scroll.position.pixels,
      maxScrollExtent: _scroll.position.maxScrollExtent,
    );
    if (near != _atBottom || (near && _newMessagesBelow)) {
      setState(() {
        _atBottom = near;
        if (near) _newMessagesBelow = false;
      });
    }
  }

  void _jumpToLatest({bool animate = true}) {
    if (!_scroll.hasClients) return;
    final double target = _scroll.position.maxScrollExtent;
    if (animate) {
      _scroll.animateTo(
        target,
        duration: const Duration(milliseconds: 180),
        curve: Curves.easeOut,
      );
    } else {
      _scroll.jumpTo(target);
    }
    setState(() => _newMessagesBelow = false);
  }

  /// Apply the "don't yank the reader" rule after the list has been laid out
  /// with its new contents.
  void _onItemsChanged(int count) {
    if (count == _knownItemCount) return;
    final bool grew = count > _knownItemCount;
    final bool wasNearBottom = !_scroll.hasClients ||
        isNearBottomByOffset(
          pixels: _scroll.position.pixels,
          maxScrollExtent: _scroll.position.maxScrollExtent,
        );
    _knownItemCount = count;
    if (count == 0) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scroll.hasClients) return;
      if (!_loadedOnce) {
        _loadedOnce = true;
        _scroll.jumpTo(_scroll.position.maxScrollExtent);
      } else if (grew && wasNearBottom) {
        _jumpToLatest();
      } else if (grew) {
        setState(() => _newMessagesBelow = true);
      }
    });
  }

  Future<void> _send() async {
    final String text = _draft.text;
    if (text.trim().isEmpty) return;
    final bool ok =
        await ref.read(conversationProvider(widget.peer).notifier).send(text);
    if (ok) {
      _draft.clear();
      // Sending is an explicit act — the sender always follows their own
      // message down, whatever they were reading.
      WidgetsBinding.instance.addPostFrameCallback((_) => _jumpToLatest());
    }
    if (mounted) _composerFocus.requestFocus();
  }

  /// Send one attachment, however it was obtained — picked, photographed, or
  /// recorded. The composer owns *getting* it; this owns sending it.
  Future<void> _sendAttachment(PickedAttachment picked) async {
    await ref.read(conversationProvider(widget.peer).notifier).attach(
          mimeType: picked.mimeType,
          bytes: picked.bytes,
          caption: picked.name,
        );
    if (mounted) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _jumpToLatest());
    }
  }

  @override
  Widget build(BuildContext context) {
    final AsyncValue<ConversationState> async =
        ref.watch(conversationProvider(widget.peer));

    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (Object e, StackTrace s) => EmptyState(
        title: 'Could not open this conversation',
        body: '$e',
      ),
      data: (ConversationState state) {
        _onItemsChanged(state.items.length);
        return Column(
          children: <Widget>[
            Expanded(child: _thread(context, state)),
            if (state.error != null)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: ErrorText(state.error),
              ),
            if (state.replyingTo != null)
              _replyChip(context, state.replyingTo!),
            _composer(context, state),
          ],
        );
      },
    );
  }

  Widget _thread(BuildContext context, ConversationState state) {
    if (state.items.isEmpty) {
      return Padding(
        padding: const EdgeInsets.only(top: 24, left: 24, right: 24),
        child: Text(
          'Messages are end-to-end encrypted with your keys. Say hi!',
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
        ),
      );
    }
    final int nowSecs = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    return Stack(
      children: <Widget>[
        ListView.builder(
          key: const Key('dm-thread'),
          controller: _scroll,
          padding: const EdgeInsets.all(12),
          itemCount: state.items.length,
          itemBuilder: (BuildContext context, int index) {
            final ChatItem item = state.items[index];
            final int? prevAt =
                index == 0 ? null : state.items[index - 1].createdAt;
            // The separator lives inside the message item (not as its own
            // list entry) so item indices keep matching the merged list and
            // the scroll arithmetic stays honest.
            return Padding(
              key: ValueKey<String>(item.key),
              padding: const EdgeInsets.only(bottom: 6),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: <Widget>[
                  if (startsNewDay(prevAt, item.createdAt))
                    DaySeparator(dayLabel(item.createdAt, nowSecs)),
                  switch (item) {
                    MediaChatItem(:final MediaMessageInfo media) => Row(
                        mainAxisAlignment: media.outgoing
                            ? MainAxisAlignment.end
                            : MainAxisAlignment.start,
                        children: <Widget>[MediaAttachmentBubble(media)],
                      ),
                    TextChatItem(:final MessageInfo message) => MessageBubble(
                        message: message,
                        quotedText: state.quoted(message.replyTo)?.content,
                        onReply: () => ref
                            .read(conversationProvider(widget.peer).notifier)
                            .startReply(message),
                      ),
                  },
                ],
              ),
            );
          },
        ),
        if (!_atBottom)
          Positioned(
            right: 12,
            bottom: 12,
            child: FloatingActionButton.small(
              key: const Key('dm-jump-latest'),
              heroTag: 'dm-jump-latest',
              backgroundColor: _newMessagesBelow
                  ? Theme.of(context).colorScheme.primary
                  : Theme.of(context).colorScheme.surfaceContainerHighest,
              foregroundColor: _newMessagesBelow
                  ? Theme.of(context).colorScheme.onPrimary
                  : Theme.of(context).colorScheme.onSurfaceVariant,
              tooltip: _newMessagesBelow
                  ? 'New messages — jump to latest'
                  : 'Jump to latest',
              onPressed: _jumpToLatest,
              child: const Icon(Icons.keyboard_arrow_down),
            ),
          ),
      ],
    );
  }

  Widget _replyChip(BuildContext context, MessageInfo replying) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12),
        child: Row(
          children: <Widget>[
            Expanded(
              child: Container(
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(10),
                ),
                padding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                child: Text(
                  '↩ ${replying.content}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
            ),
            IconButton(
              tooltip: 'Cancel reply',
              onPressed: () => ref
                  .read(conversationProvider(widget.peer).notifier)
                  .cancelReply(),
              icon: const Icon(Icons.close),
            ),
          ],
        ),
      );

  /// Emoji · text · paper clip, then one round button that is Send or the
  /// swappable mic/camera — see [MessageComposer], which owns the layout and the
  /// capability gating.
  Widget _composer(BuildContext context, ConversationState state) =>
      MessageComposer(
        controller: _draft,
        focusNode: _composerFocus,
        sending: state.sending,
        attaching: state.attaching,
        onSend: _send,
        onAttachment: _sendAttachment,
      );
}
