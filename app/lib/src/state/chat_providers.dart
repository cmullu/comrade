/// Chat list, message requests, and the conversation thread.
library;

import 'dart:typed_data';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/comrade_repository.dart';
import '../data/models.dart';
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

  /// Stable list key. Media ids are namespaced so a media event id can never
  /// collide with a message id.
  String get key;
}

class TextChatItem extends ChatItem {
  const TextChatItem(this.message);
  final MessageInfo message;

  @override
  int get createdAt => message.createdAt;

  @override
  String get key => message.id;
}

class MediaChatItem extends ChatItem {
  const MediaChatItem(this.media);
  final MediaMessageInfo media;

  @override
  int get createdAt => media.createdAt;

  @override
  String get key => 'media:${media.eventId}';
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
  });

  final List<MessageInfo> messages;
  final List<MediaMessageInfo> media;
  final List<ChatItem> items;
  final MessageInfo? replyingTo;
  final bool sending;
  final bool attaching;
  final String? error;

  /// Quick lookup so a bubble carrying `replyTo` can show a quoted preview.
  MessageInfo? quoted(String? id) {
    if (id == null) return null;
    for (final MessageInfo m in messages) {
      if (m.id == id) return m;
    }
    return null;
  }

  ConversationState copyWith({
    List<MessageInfo>? messages,
    List<MediaMessageInfo>? media,
    MessageInfo? replyingTo,
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
    // Opening the thread marks it read (sends a read receipt).
    fireAndForget(repo.markConversationRead(_peer));
    return ConversationState(
      messages: messages,
      media: media,
      items: mergeChatItems(messages, media),
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
        fireAndForget(
          ref.read(comradeRepositoryProvider).markConversationRead(_peer),
        );
      case IncomingMedia(:final MediaMessageInfo media):
        if (_state.media.any(
          (MediaMessageInfo m) => m.eventId == media.eventId,
        )) {
          return;
        }
        state = AsyncData<ConversationState>(
          _state.copyWith(media: <MediaMessageInfo>[..._state.media, media]),
        );
        fireAndForget(
          ref.read(comradeRepositoryProvider).markConversationRead(_peer),
        );
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

  void startReply(MessageInfo message) => state =
      AsyncData<ConversationState>(_state.copyWith(replyingTo: message));

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

  /// Encrypt + send an attachment (NIP-94 over the DM channel).
  Future<bool> attach({
    required String mimeType,
    required Uint8List bytes,
    String caption = '',
  }) async {
    if (_state.attaching) return false;
    if (bytes.length > 10 * 1024 * 1024) {
      state = AsyncData<ConversationState>(
        _state.copyWith(error: 'Attachments are limited to 10 MB.'),
      );
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
