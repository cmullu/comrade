/// Chat list, message requests, and the conversation thread.
library;

import 'dart:typed_data';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/comrade_repository.dart';
import '../data/models.dart';
import '../util/attachment_caption.dart';
import '../util/chat_thread.dart';
import 'providers.dart';

// ── Chat list ───────────────────────────────────────────────────────────────

class ConversationsController extends AsyncNotifier<List<ConversationInfo>> {
  @override
  Future<List<ConversationInfo>> build() async {
    listenToEvents(
      ref,
      matches: (BridgeEvent e) =>
          e is IncomingDirectMessage ||
          e is IncomingMedia ||
          e is PeerProfileUpdated ||
          e is MessageStatusChanged,
      onEvent: (_) => ref.invalidateSelf(),
    );
    return ref.watch(comradeRepositoryProvider).conversations();
  }

  void refresh() => ref.invalidateSelf();
}

final AsyncNotifierProvider<ConversationsController, List<ConversationInfo>>
    conversationsProvider =
    AsyncNotifierProvider<ConversationsController, List<ConversationInfo>>(
        ConversationsController.new);

// ── Message requests ────────────────────────────────────────────────────────

class MessageRequestsController
    extends AsyncNotifier<List<MessageRequestInfo>> {
  @override
  Future<List<MessageRequestInfo>> build() async {
    listenToEvents(
      ref,
      matches: (BridgeEvent e) => e is IncomingMessageRequest,
      onEvent: (_) => ref.invalidateSelf(),
    );
    return ref.watch(comradeRepositoryProvider).messageRequests();
  }

  Future<void> accept(String peer) async {
    await ref.read(comradeRepositoryProvider).acceptRequest(peer);
    ref.invalidateSelf();
    ref.read(conversationsProvider.notifier).refresh();
  }

  Future<void> block(String peer) async {
    await ref.read(comradeRepositoryProvider).blockConversation(peer);
    ref.invalidateSelf();
    ref.read(conversationsProvider.notifier).refresh();
  }
}

final AsyncNotifierProvider<MessageRequestsController, List<MessageRequestInfo>>
    messageRequestsProvider =
    AsyncNotifierProvider<MessageRequestsController, List<MessageRequestInfo>>(
        MessageRequestsController.new);

/// Just the badge count, so the chat list doesn't rebuild on request content.
final Provider<int> messageRequestCountProvider = Provider<int>(
  (Ref ref) => ref.watch(messageRequestsProvider).value?.length ?? 0,
);

// ── Comrades ────────────────────────────────────────────────────────────────

class ComradesController extends AsyncNotifier<List<ComradeInfo>> {
  @override
  Future<List<ComradeInfo>> build() =>
      ref.watch(comradeRepositoryProvider).comrades();

  /// Make [npub] a comrade, or stop being one.
  ///
  /// Refreshes the chat list too: comrade state is what decides whether a row
  /// carries a presence dot, so a stale list would show the old answer.
  Future<void> setComrade(String npub, {required bool comrade}) async {
    await ref
        .read(comradeRepositoryProvider)
        .setComrade(npub: npub, comrade: comrade);
    ref.invalidateSelf();
    ref.read(conversationsProvider.notifier).refresh();
  }
}

final AsyncNotifierProvider<ComradesController, List<ComradeInfo>>
    comradesProvider =
    AsyncNotifierProvider<ComradesController, List<ComradeInfo>>(
        ComradesController.new);

/// Whether one key is a comrade of ours.
///
/// Defaults to `false` while the list is loading or if it fails: the ⋮ menu
/// offers "Make a comrade", which is the harmless direction to guess wrong —
/// re-marking an existing comrade is a no-op, whereas guessing `true` would
/// offer to remove a relationship that may not exist.
final ProviderFamily<bool, String> isComradeProvider =
    Provider.family<bool, String>(
  (Ref ref, String npub) =>
      ref.watch(comradesProvider).value?.any(
            (ComradeInfo c) => c.npub == npub,
          ) ??
      false,
);

// ── The open conversation ───────────────────────────────────────────────────

/// Which peer the shell currently has open.
///
/// On a phone this is "the pushed screen"; on desktop it is "the right pane".
/// The router also uses it the way `ChatEventRouter.setOpenConversation` did:
/// a notification for the thread already on screen is redundant.
final StateProvider<ChatTarget?> openConversationProvider =
    StateProvider<ChatTarget?>((Ref ref) => null);

/// Everything the shell needs to title a conversation without re-fetching:
/// the key plus whatever names were known at navigation time.
class ChatTarget {
  const ChatTarget({required this.peer, this.alias, this.username});

  final String peer;

  /// User-chosen alias for the peer, when one exists.
  final String? alias;

  /// The peer's own published @handle, when known.
  final String? username;

  ChatTarget copyWith(
          {String? alias, String? username, bool clearAlias = false}) =>
      ChatTarget(
        peer: peer,
        alias: clearAlias ? null : (alias ?? this.alias),
        username: username ?? this.username,
      );

  @override
  bool operator ==(Object other) =>
      other is ChatTarget &&
      other.peer == peer &&
      other.alias == alias &&
      other.username == username;

  @override
  int get hashCode => Object.hash(peer, alias, username);
}

/// A conversation is a time-ordered merge of text messages and media
/// attachments (`ChatsScreen.kt`'s `ChatItem`).
sealed class ChatItem {
  const ChatItem();

  int get createdAt;

  /// Whether *this device* sent it — your own messages are never "unread".
  bool get outgoing;

  /// Stable list key. Media ids are namespaced so a media event id can never
  /// collide with a message id.
  String get key;

  /// The nostr event id a reply points at.
  ///
  /// **Not** [key]: the namespacing that keeps the list keys apart would make a
  /// reply address `media:abc…`, which is not an event and would never resolve
  /// on the other side. A text message and a media attachment are both ordinary
  /// events here, which is the whole reason replying to media needs no core
  /// change — `send_dm_reply` tags whatever id it is given.
  String get id;

  /// One line naming this item, for a reply chip or a quoted preview.
  String get preview;
}

class TextChatItem extends ChatItem {
  const TextChatItem(this.message);
  final MessageInfo message;

  @override
  int get createdAt => message.createdAt;

  @override
  bool get outgoing => message.outgoing;

  @override
  String get key => message.id;

  @override
  String get id => message.id;

  @override
  String get preview => message.content;
}

class MediaChatItem extends ChatItem {
  const MediaChatItem(this.media);
  final MediaMessageInfo media;

  @override
  int get createdAt => media.createdAt;

  @override
  bool get outgoing => media.outgoing;

  @override
  String get key => 'media:${media.eventId}';

  @override
  String get id => media.eventId;

  @override
  String get preview =>
      mediaQuoteLabel(mimeType: media.mimeType, caption: media.caption);
}

/// Merge text + media into one time-ordered thread, like a real chat.
///
/// Pure and exported so a test can pin the interleaving without a repository.
List<ChatItem> mergeChatItems(
  List<MessageInfo> messages,
  List<MediaMessageInfo> media,
) {
  final List<ChatItem> items = <ChatItem>[
    for (final MessageInfo m in messages) TextChatItem(m),
    for (final MediaMessageInfo m in media) MediaChatItem(m),
  ]..sort((ChatItem a, ChatItem b) => a.createdAt.compareTo(b.createdAt));
  return items;
}

class ConversationState {
  const ConversationState({
    this.messages = const <MessageInfo>[],
    this.media = const <MediaMessageInfo>[],
    this.items = const <ChatItem>[],
    this.replyingTo,
    this.sending = false,
    this.attaching = false,
    this.error,
    this.unreadBoundaryKey,
  });

  final List<MessageInfo> messages;
  final List<MediaMessageInfo> media;
  final List<ChatItem> items;

  /// [ChatItem.key] of the first message the reader had not seen when they
  /// opened this thread, or null for "nothing unread".
  ///
  /// Held by key rather than index so a backfill of older history above it
  /// cannot slide the divider onto the wrong message, and captured once per
  /// visit: Telegram leaves the line where you found it for the rest of the
  /// visit, which is what makes it useful to read down to.
  final String? unreadBoundaryKey;

  /// What the next text send will be a reply to — a message *or* an
  /// attachment. Typed as [ChatItem] rather than [MessageInfo] precisely so
  /// "reply to that photo" is expressible: the nostr `e` tag does not care
  /// which kind of event it points at.
  final ChatItem? replyingTo;
  final bool sending;
  final bool attaching;
  final String? error;

  /// Quick lookup so a bubble carrying `replyTo` can show a quoted preview.
  ///
  /// Searches media as well as text: an id that resolves to neither (history
  /// not loaded, or the original was deleted) yields null and the bubble simply
  /// renders without a quote, which is what it did before too.
  ChatItem? quoted(String? id) {
    if (id == null) return null;
    for (final ChatItem item in items) {
      if (item.id == id) return item;
    }
    return null;
  }

  ConversationState copyWith({
    List<MessageInfo>? messages,
    List<MediaMessageInfo>? media,
    ChatItem? replyingTo,
    bool clearReplyingTo = false,
    bool? sending,
    bool? attaching,
    String? error,
    bool clearError = false,
  }) {
    final List<MessageInfo> nextMessages = messages ?? this.messages;
    final List<MediaMessageInfo> nextMedia = media ?? this.media;
    return ConversationState(
      messages: nextMessages,
      media: nextMedia,
      items: (messages != null || media != null)
          ? mergeChatItems(nextMessages, nextMedia)
          : items,
      replyingTo: clearReplyingTo ? null : (replyingTo ?? this.replyingTo),
      sending: sending ?? this.sending,
      attaching: attaching ?? this.attaching,
      error: clearError ? null : (error ?? this.error),
      unreadBoundaryKey: unreadBoundaryKey,
    );
  }
}

class ConversationController
    extends FamilyAsyncNotifier<ConversationState, String> {
  String get _peer => arg;

  @override
  Future<ConversationState> build(String arg) async {
    // Only events for *this* peer reload the thread. On Android `chatTick` was
    // global — it fired for activity in any conversation and repeatedly while
    // this one was open — which is exactly what made the "don't yank the
    // reader" guard necessary. Filtering by peer here shrinks the problem but
    // does not remove it (the peer can be actively typing), so the scroll rule
    // stays.
    listenToEvents(
      ref,
      matches: (BridgeEvent e) => switch (e) {
        IncomingDirectMessage(:final MessageInfo message) =>
          message.peer == _peer,
        IncomingMedia(:final MediaMessageInfo media) => media.sender == _peer,
        MessageStatusChanged(:final String peer) => peer == _peer,
        _ => false,
      },
      onEvent: (BridgeEvent e) => _applyEvent(e),
    );

    final ComradeRepository repo = ref.watch(comradeRepositoryProvider);
    final List<MessageInfo> messages = await repo.messages(_peer);
    final List<MediaMessageInfo> media = await repo.media(_peer);
    final List<ChatItem> items = mergeChatItems(messages, media);
    // Opening the thread marks it read (sends a read receipt) *and* reports
    // where the reader had got to, which is what the thread opens at. Awaited
    // rather than fire-and-forget precisely because that answer is needed —
    // and it must be read before this same call overwrites it.
    //
    // A failure here costs the divider, not the thread: an unreadable position
    // means "open at the newest message", which is the old behaviour.
    int previousRead = 0;
    try {
      previousRead = await repo.markConversationRead(_peer);
    } on ComradeException catch (_) {
      previousRead = 0;
    }
    final int? firstUnread = firstUnreadIndex(
      createdAt: <int>[for (final ChatItem i in items) i.createdAt],
      outgoing: <bool>[for (final ChatItem i in items) i.outgoing],
      lastReadAt: previousRead,
    );
    return ConversationState(
      messages: messages,
      media: media,
      items: items,
      unreadBoundaryKey: firstUnread == null ? null : items[firstUnread].key,
    );
  }

  /// Whether the reader currently has the newest message in view.
  ///
  /// The screen owns the scroll position, so it tells the controller: messages
  /// arriving while someone is scrolled up in history have *not* been seen, and
  /// marking them read would both lie to the peer's receipt and cost the reader
  /// their divider on the next visit.
  bool _readerAtBottom = true;

  // ignore: use_setters_to_change_properties
  void setReaderAtBottom(bool atBottom) => _readerAtBottom = atBottom;

  /// Mark read, but only when the arriving message is actually on screen.
  void _markReadIfVisible() {
    if (!_readerAtBottom) return;
    markReadNow();
  }

  /// Mark read unconditionally — the caller has established that the reader can
  /// see the newest message (e.g. they just scrolled back down to it).
  ///
  /// The return value is discarded here on purpose: only the first call of a
  /// visit, in [build], is positioning the thread. The store's advance is
  /// monotonic, so repeating this is harmless.
  void markReadNow() {
    fireAndForget(
      ref.read(comradeRepositoryProvider).markConversationRead(_peer),
    );
  }

  ConversationState get _state => state.value ?? const ConversationState();

  void _applyEvent(BridgeEvent event) {
    switch (event) {
      case IncomingDirectMessage(:final MessageInfo message):
        if (_state.messages.any((MessageInfo m) => m.id == message.id)) return;
        state = AsyncData<ConversationState>(
          _state.copyWith(messages: <MessageInfo>[..._state.messages, message]),
        );
        // Receipts for messages that arrive while the thread is on screen —
        // the mark-read on open only covers backlog.
        _markReadIfVisible();
      case IncomingMedia(:final MediaMessageInfo media):
        if (_state.media.any(
          (MediaMessageInfo m) => m.eventId == media.eventId,
        )) {
          return;
        }
        state = AsyncData<ConversationState>(
          _state.copyWith(media: <MediaMessageInfo>[..._state.media, media]),
        );
        _markReadIfVisible();
      case MessageStatusChanged(
          :final List<String> messageIds,
          :final MessageStatus status
        ):
        // Never regress a status: a late "delivered" must not undo a "read".
        // (The desktop SPA's STATUS_RANK rule; Android had no equivalent —
        // it re-read the whole thread from the store instead.)
        final Set<String> ids = messageIds.toSet();
        final List<MessageInfo> next = <MessageInfo>[];
        var changed = false;
        for (final MessageInfo m in _state.messages) {
          if (m.outgoing && ids.contains(m.id) && status.outranks(m.status)) {
            next.add(m.copyWith(status: status));
            changed = true;
          } else {
            next.add(m);
          }
        }
        if (changed) {
          state = AsyncData<ConversationState>(_state.copyWith(messages: next));
        }
      default:
        break;
    }
  }

  /// Aim the composer at [item] — a text message or an attachment.
  void startReply(ChatItem item) =>
      state = AsyncData<ConversationState>(_state.copyWith(replyingTo: item));

  void cancelReply() => state =
      AsyncData<ConversationState>(_state.copyWith(clearReplyingTo: true));

  void clearError() =>
      state = AsyncData<ConversationState>(_state.copyWith(clearError: true));

  /// Send the composed text. Returns true when it left the device.
  Future<bool> send(String draft) async {
    final String text = draft.trim();
    final ConversationState current = _state;
    if (text.isEmpty || current.sending) return false;
    state = AsyncData<ConversationState>(
      current.copyWith(sending: true, clearError: true),
    );
    try {
      final MessageInfo sent = await ref.read(comradeRepositoryProvider).sendDm(
            peer: _peer,
            content: text,
            replyTo: current.replyingTo?.id,
          );
      state = AsyncData<ConversationState>(
        _state.copyWith(
          messages: <MessageInfo>[..._state.messages, sent],
          sending: false,
          clearReplyingTo: true,
        ),
      );
      ref.read(conversationsProvider.notifier).refresh();
      return true;
    } on ComradeException catch (e) {
      state = AsyncData<ConversationState>(
        _state.copyWith(sending: false, error: e.message),
      );
      return false;
    } catch (e) {
      state = AsyncData<ConversationState>(
        _state.copyWith(sending: false, error: 'Could not send.'),
      );
      return false;
    }
  }

  /// Surface a problem the screen found *before* anything was encrypted — an
  /// oversize pick, an empty capture.
  ///
  /// The same slot [attach]'s own failures land in, so there is one place to read
  /// for "why did nothing happen" rather than a toast for the early refusals and
  /// an error line for the late ones.
  void refuse(String reason) {
    state = AsyncData<ConversationState>(_state.copyWith(error: reason));
  }

  /// Encrypt + send an attachment (NIP-94 over the DM channel).
  Future<bool> attach({
    required String mimeType,
    required Uint8List bytes,
    String caption = '',
  }) async {
    if (_state.attaching) return false;
    // Also checked before the preview sheet opens, which is where a person
    // normally meets it. Kept here because this is reachable without a sheet
    // (a voice note) and because a screen that forgot the check must still not
    // hand the core something it will reject.
    final String? refusal = attachmentRejection(name: '', bytes: bytes.length);
    if (refusal != null) {
      state = AsyncData<ConversationState>(_state.copyWith(error: refusal));
      return false;
    }
    state = AsyncData<ConversationState>(
      _state.copyWith(attaching: true, clearError: true),
    );
    try {
      final MediaMessageInfo info =
          await ref.read(comradeRepositoryProvider).sendMedia(
                peer: _peer,
                mimeType: mimeType,
                caption: caption,
                bytes: bytes,
              );
      // Render the real attachment inline — not a synthetic text line.
      state = AsyncData<ConversationState>(
        _state.copyWith(
          media: <MediaMessageInfo>[..._state.media, info],
          attaching: false,
        ),
      );
      ref.read(conversationsProvider.notifier).refresh();
      return true;
    } on ComradeException catch (e) {
      state = AsyncData<ConversationState>(
        _state.copyWith(attaching: false, error: e.message),
      );
      return false;
    } catch (_) {
      state = AsyncData<ConversationState>(
        _state.copyWith(
            attaching: false, error: 'Could not send the attachment.'),
      );
      return false;
    }
  }
}

final AsyncNotifierProviderFamily<ConversationController, ConversationState,
        String> conversationProvider =
    AsyncNotifierProvider.family<ConversationController, ConversationState,
        String>(ConversationController.new);

/// Fire a background call whose failure must not crash the app or the zone.
///
/// Read receipts are the only user of this: failing to send one is invisible
/// and harmless, and there is no UI affordance that could act on the error.
void fireAndForget(Future<void> future) {
  future.then<void>((_) {}, onError: (Object _, StackTrace __) {});
}
