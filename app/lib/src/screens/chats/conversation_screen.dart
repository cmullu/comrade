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

import '../../data/comrade_repository.dart';
import '../../data/models.dart';
import '../../state/chat_providers.dart';
import '../../state/providers.dart';
import '../../util/attachment_caption.dart';
import '../../util/chat_thread.dart';
import '../../widgets/app_chrome.dart';
import '../../widgets/attachment_preview.dart';
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

  /// Whether the core has been told this composer holds unsent text. Only the
  /// edges are worth a call — the core is idempotent, but a bridge hop per
  /// keystroke is not free.
  bool _draftReported = false;

  /// Read once, so [dispose] never has to reach for a provider on its way out.
  late final ComradeRepository _repo;

  @override
  void initState() {
    super.initState();
    _repo = ref.read(comradeRepositoryProvider);
    _scroll.addListener(_onScroll);
    _draft.addListener(_onDraftChanged);
  }

  /// Report whether there is unsent text here, so the core can tell a comrade
  /// if it is later given up on (`comrade_core::nudge`). What the composer
  /// holds never leaves the device — only the fact that it holds something.
  void _onDraftChanged() {
    final bool hasText = _draft.text.trim().isNotEmpty;
    if (hasText == _draftReported) return;
    _draftReported = hasText;
    fireAndForget(
      hasText ? _repo.noteDraft(widget.peer) : _repo.abandonDraft(widget.peer),
    );
  }

  /// The draft for [peer] is gone because the screen is — leaving a thread with
  /// a half-written message in it is the same signal as clearing the box, and
  /// the one people are least likely to come back from.
  void _abandonDraft(String peer) {
    if (!_draftReported) return;
    _draftReported = false;
    fireAndForget(_repo.abandonDraft(peer));
  }

  @override
  void didUpdateWidget(covariant ConversationScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.peer != widget.peer) {
      _loadedOnce = false;
      _knownItemCount = 0;
      _newMessagesBelow = false;
      // Against the peer being left, before the clear below — by now
      // `widget.peer` is the one arriving, and the draft was never theirs.
      _abandonDraft(oldWidget.peer);
      _draft.clear();
    }
  }

  @override
  void dispose() {
    _abandonDraft(widget.peer);
    _scroll
      ..removeListener(_onScroll)
      ..dispose();
    _draft
      ..removeListener(_onDraftChanged)
      ..dispose();
    _composerFocus.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_scroll.hasClients) return;
    final bool near = isNearBottomByOffset(
      pixels: _scroll.position.pixels,
      maxScrollExtent: _scroll.position.maxScrollExtent,
    );
    // The controller can't see the viewport, so it is told: a message arriving
    // while the reader is scrolled up has not been seen and must not be marked
    // read. Reaching the bottom is also the moment anything left unread has
    // actually been read.
    ref
        .read(conversationProvider(widget.peer).notifier)
        .setReaderAtBottom(near);
    if (near != _atBottom || (near && _newMessagesBelow)) {
      if (near) _markRead();
      setState(() {
        _atBottom = near;
        if (near) _newMessagesBelow = false;
      });
    }
  }

  void _markRead() {
    ref.read(conversationProvider(widget.peer).notifier).markReadNow();
  }

  /// Jump to [index], correcting for the fact that a lazily-built list only
  /// *estimates* the extent of children it has not laid out yet.
  ///
  /// `ListView` has no scroll-to-index, and one `jumpTo` computed from
  /// `maxScrollExtent` lands short in a long thread because the estimate
  /// sharpens as items are built. So jump, let a frame settle, and jump again
  /// while the target keeps moving — bounded, because a thread whose extent
  /// never settles must not spin forever.
  Future<void> _jumpToIndex(int index, int itemCount) async {
    if (!_scroll.hasClients || itemCount <= 0) return;
    const int maxCorrections = 8;
    double previousTarget = -1;
    for (int attempt = 0; attempt < maxCorrections; attempt++) {
      if (!mounted || !_scroll.hasClients) return;
      final ScrollPosition position = _scroll.position;
      // Proportional estimate of where the index sits, clamped to the bottom
      // for the last item so "open at newest" is exact rather than approximate.
      final double target = index >= itemCount - 1
          ? position.maxScrollExtent
          : (position.maxScrollExtent * (index / itemCount))
              .clamp(0.0, position.maxScrollExtent);
      if ((target - previousTarget).abs() < 1.0) return;
      previousTarget = target;
      _scroll.jumpTo(target);
      await Future<void>.delayed(Duration.zero);
      await WidgetsBinding.instance.endOfFrame;
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
  /// with its new contents, and on the first load open where the reader left
  /// off rather than at the newest message.
  void _onItemsChanged(ConversationState state) {
    final int count = state.items.length;
    if (count == _knownItemCount) return;
    final bool grew = count > _knownItemCount;
    final bool wasNearBottom = !_scroll.hasClients ||
        isNearBottomByOffset(
          pixels: _scroll.position.pixels,
          maxScrollExtent: _scroll.position.maxScrollExtent,
        );
    _knownItemCount = count;
    if (count == 0) return;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted || !_scroll.hasClients) return;
      if (!_loadedOnce) {
        _loadedOnce = true;
        // Telegram's rule: the first unread message, else the newest one.
        final int boundary = state.unreadBoundaryKey == null
            ? count - 1
            : state.items.indexWhere(
                (ChatItem i) => i.key == state.unreadBoundaryKey,
              );
        await _jumpToIndex(boundary < 0 ? count - 1 : boundary, count);
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
  /// recorded. The composer owns *getting* it; this owns confirming and sending
  /// it.
  ///
  /// Three gates, in this order, and the order is the point:
  ///
  ///  1. **Can it be sent at all** ([attachmentRejection]). An empty capture or
  ///     an oversize file is refused here, before the sheet — every frontend used
  ///     to discover the 10 MB cap only after the upload began, which throws away
  ///     the caption the sender had just written.
  ///  2. **Is it the right file** ([showAttachmentPreview]). Backing out sends
  ///     nothing and leaves the composer exactly as it was.
  ///  3. **What is it called.** The caption is seeded from the composer per
  ///     [captionForAttachment] and then edited against the picture — never the
  ///     file's name, which is what this used to send. A device-chosen name
  ///     (`IMG_20260731_114233.jpg`) is noise in the recipient's thread at best,
  ///     and at worst the one piece of local filesystem vocabulary the sender
  ///     never chose to share.
  Future<void> _sendAttachment(
    PickedAttachment picked, {
    required bool preview,
  }) async {
    final ConversationController controller =
        ref.read(conversationProvider(widget.peer).notifier);
    final String? refusal = attachmentRejection(
      name: picked.name,
      bytes: picked.bytes.length,
    );
    if (refusal != null) {
      controller.refuse(refusal);
      return;
    }

    final bool replyPending =
        ref.read(conversationProvider(widget.peer)).value?.replyingTo != null;
    final String draft = _draft.text;
    String caption =
        captionForAttachment(draft: draft, replyPending: replyPending);
    final bool consumed =
        captionConsumesDraft(draft: draft, replyPending: replyPending);

    if (preview) {
      final String? confirmed = await showAttachmentPreview(
        context,
        attachment: picked,
        seedCaption: caption,
      );
      // Backed out. Nothing was encrypted, nothing was uploaded, and the draft
      // is still where they left it.
      if (confirmed == null || !mounted) return;
      caption = confirmed;
    }

    final bool ok = await controller.attach(
      mimeType: picked.mimeType,
      bytes: picked.bytes,
      caption: caption,
    );
    // Only clear a draft that actually went with the attachment, and only once
    // it has: a failed upload must leave the words the person typed in the box.
    // A draft the sheet then *deleted* still counts as gone — that deletion was
    // deliberate, and putting the words back would be the surprise.
    if (ok && consumed) _draft.clear();
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
        _onItemsChanged(state);
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
                  if (item.key == state.unreadBoundaryKey)
                    const UnreadSeparator(),
                  switch (item) {
                    MediaChatItem(:final MediaMessageInfo media) =>
                      MediaAttachmentBubble(
                        media,
                        onReply: () => _startReply(item),
                      ),
                    TextChatItem(:final MessageInfo message) => MessageBubble(
                        message: message,
                        quotedText: state.quoted(message.replyTo)?.preview,
                        onReply: () => _startReply(item),
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

  /// Aim the composer at [item] and put the cursor where the reply goes. The
  /// chip appearing with the keyboard still down reads as "nothing happened".
  void _startReply(ChatItem item) {
    ref.read(conversationProvider(widget.peer).notifier).startReply(item);
    _composerFocus.requestFocus();
  }

  Widget _replyChip(BuildContext context, ChatItem replying) => Padding(
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
                  '↩ ${replying.preview}',
                  key: const Key('dm-reply-chip'),
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
