/// Plain Dart mirrors of the DTOs `comrade_ui` already exposes to both
/// frontends (`ComradeCore.kt`'s `xxxInfo` data classes on Android, the
/// snake_case JSON the Tauri commands return on desktop).
///
/// These are hand-written on purpose: they are the app's *view* model, not the
/// generated bridge's. When `package:comrade/src/rust/api.dart` lands, the
/// repository adapter maps generated types into these, so nothing above
/// `lib/src/data/` ever depends on the bridge's shape.
library;

import 'dart:typed_data';

import 'package:meta/meta.dart';

/// The active identity: its key, and the `@handle` it published (if any).
@immutable
class Profile {
  const Profile({required this.npub, this.username});

  final String npub;
  final String? username;

  Profile copyWith(
          {String? npub, String? username, bool clearUsername = false}) =>
      Profile(
        npub: npub ?? this.npub,
        username: clearUsername ? null : (username ?? this.username),
      );

  @override
  bool operator ==(Object other) =>
      other is Profile && other.npub == npub && other.username == username;

  @override
  int get hashCode => Object.hash(npub, username);
}

/// A profile found on the public directory relays.
@immutable
class FoundProfile {
  const FoundProfile({required this.npub, this.name, this.about});

  final String npub;
  final String? name;
  final String? about;
}

/// A pinned contact: the key, the alias *you* gave it, and their published
/// handle if one is cached.
@immutable
class ContactInfo {
  const ContactInfo({
    required this.npub,
    required this.alias,
    this.name,
    this.comrade = false,
  });

  final String npub;
  final String alias;
  final String? name;

  /// Whether the user chose this contact as a comrade — i.e. agreed to tell
  /// *them alone* when this device is online. Defaults to `false` because that
  /// is what an unmarked contact is; presence is never assumed.
  final bool comrade;
}

/// One row of the chat list.
@immutable
class ConversationInfo {
  const ConversationInfo({
    required this.peer,
    required this.lastMessage,
    required this.lastAt,
    required this.lastOutgoing,
    this.alias,
    this.peerName,
    this.comrade = false,
    this.online = false,
  });

  final String peer;
  final String? alias;
  final String? peerName;
  final String lastMessage;
  final int lastAt;
  final bool lastOutgoing;

  /// Whether this peer is one of the user's comrades.
  final bool comrade;

  /// Whether that comrade is online *right now*. Recomputed by the core on
  /// every read, never a stored flag. Always `false` for a non-comrade — and
  /// `false` is also the default here, because "we do not know" and "not
  /// online" must render the same way: a grey dot, never a green one.
  final bool online;
}

/// A comrade — a contact the user chose to exchange presence with — plus what
/// their last beacon said.
@immutable
class ComradeInfo {
  const ComradeInfo({
    required this.npub,
    required this.alias,
    required this.online,
    required this.lastSeenAt,
    required this.peerMarkedUs,
    this.name,
  });

  final String npub;
  final String alias;
  final String? name;

  /// Live presence, recomputed against the current clock.
  final bool online;

  /// Send time (unix seconds) of their last beacon; `0` if none ever arrived.
  final int lastSeenAt;

  /// Whether they have marked *us* as their comrade. `false` means we will
  /// never see them online however long we wait — the UI must say so rather
  /// than showing a permanently grey dot with no explanation.
  final bool peerMarkedUs;
}

/// One peer's presence, for a chat header or a contact row.
@immutable
class PresenceInfo {
  const PresenceInfo({
    required this.peer,
    required this.online,
    required this.lastSeenAt,
    required this.peerMarkedUs,
  });

  final String peer;
  final bool online;
  final int lastSeenAt;
  final bool peerMarkedUs;
}

/// Delivery states an outgoing message moves through, newest-wins.
///
/// The desktop SPA carried this as a `STATUS_RANK` map so a late "delivered"
/// could never undo a "read"; Android only rendered the glyph. The unified app
/// keeps the ranking — see [MessageStatus.outranks].
enum MessageStatus {
  sent('sent', 1),
  delivered('delivered', 2),
  read('read', 3);

  const MessageStatus(this.wire, this.rank);

  final String wire;
  final int rank;

  static MessageStatus? fromWire(String? wire) {
    if (wire == null) return null;
    for (final MessageStatus s in MessageStatus.values) {
      if (s.wire == wire) return s;
    }
    return null;
  }

  /// Whether this status may replace [other] (never regress).
  bool outranks(MessageStatus? other) => rank >= (other?.rank ?? 0);
}

/// One text message in a conversation.
@immutable
class MessageInfo {
  const MessageInfo({
    required this.id,
    required this.peer,
    required this.content,
    required this.createdAt,
    required this.outgoing,
    this.fromTara = false,
    this.status,
    this.replyTo,
    this.sharedNote,
  });

  final String id;
  final String peer;
  final String content;
  final int createdAt;
  final bool outgoing;

  /// True when core read Tara's marker off the wire form — see
  /// `comrade_ui::MessageAuthor`. A claim by whichever Comrade sent it, never an
  /// authenticated one, so it may style a bubble and must not gate anything.
  /// Flattened to a bool for the same reason Android's `MessageInfo` does: the
  /// question a bubble asks is "is this hers".
  final bool fromTara;
  final MessageStatus? status;
  final String? replyTo;

  /// Set when this message is a journal note its sender chose to share — see
  /// `comrade_core::note`. [content] already carries the same text without the
  /// marker line, so a bubble ignoring this still shows the words; what this
  /// adds is the card and the mood.
  ///
  /// A label, not an attestation, exactly like [fromTara]: the marker is text
  /// anybody can type, so it may style a bubble and must never gate anything.
  final SharedNoteInfo? sharedNote;

  MessageInfo copyWith({MessageStatus? status}) => MessageInfo(
        id: id,
        peer: peer,
        content: content,
        createdAt: createdAt,
        outgoing: outgoing,
        fromTara: fromTara,
        status: status ?? this.status,
        replyTo: replyTo,
        sharedNote: sharedNote,
      );
}

/// The journal note a [MessageInfo] carries, as the card draws it.
@immutable
class SharedNoteInfo {
  const SharedNoteInfo({required this.text, this.mood});

  final String text;

  /// The self-reported mood marker the entry carried, if it had one.
  final String? mood;
}

// ── Threads and topics (see `comrade_core::topic`) ───────────────────────────

/// One topic in one conversation — a heading threads are filed under.
///
/// The counts are computed on read by core rather than stored, so they cannot
/// drift when a backfill inserts an old message into the middle of a thread.
@immutable
class TopicInfo {
  const TopicInfo({
    required this.slug,
    required this.name,
    required this.closed,
    required this.threadCount,
    required this.messageCount,
    required this.lastActivityAt,
    this.mine = true,
  });

  /// Canonical slug — the id. See `comrade_core::topic::slugify` for why the
  /// slug *is* the id and what that costs (a topic cannot be renamed into a
  /// different word).
  final String slug;

  /// Display name, as first spelled.
  final String name;

  /// Archived: readable, out of the picker. Nothing deletes a topic.
  final bool closed;
  final int threadCount;
  final int messageCount;
  final int lastActivityAt;

  /// Whether *this device* named it.
  final bool mine;
}

/// One thread as a list row: what it is about, how big it got, where it is filed.
@immutable
class ThreadInfo {
  const ThreadInfo({
    required this.rootId,
    required this.preview,
    required this.rootIsMedia,
    required this.rootMissing,
    required this.replyCount,
    required this.lastAt,
    required this.unread,
    this.topicSlug,
  });

  /// Event id of the root — the thread's id.
  final String rootId;

  /// Slug of the topic it is filed under, or null for unfiled.
  final String? topicSlug;

  /// The root's own text. Empty for an uncaptioned attachment and for a root
  /// that is not on this device — [rootIsMedia] and [rootMissing] say which, so
  /// the *word* stays here and can be translated rather than coming up from
  /// core in English.
  final String preview;
  final bool rootIsMedia;

  /// Whether the root message is missing from this device's history. Not an
  /// error and not rare: a thread can be filed before its root is cached.
  final bool rootMissing;

  /// Messages *below* the root.
  final int replyCount;
  final int lastAt;
  final bool unread;
}

/// One thread in full — the root and everything that replied into it.
///
/// Two lists rather than one merged one, because a merge needs a total order
/// and every frontend already has its own interleave by `createdAt`.
@immutable
class ThreadDetail {
  const ThreadDetail({
    required this.rootId,
    required this.messages,
    required this.media,
    this.topicSlug,
  });

  final String rootId;
  final String? topicSlug;
  final List<MessageInfo> messages;

  /// In practice the root or nothing: an attachment carries no `replyTo`, so it
  /// can start a thread but not join one (`AUDIT.md` TOPIC-2).
  final List<MediaMessageInfo> media;
}

/// A stranger's first DM, gated out of the chat list until accepted.
@immutable
class MessageRequestInfo {
  const MessageRequestInfo({
    required this.peer,
    required this.lastMessage,
    required this.lastAt,
  });

  final String peer;
  final String lastMessage;
  final int lastAt;
}

/// An encrypted NIP-94/96 attachment referenced from the DM channel.
@immutable
class MediaMessageInfo {
  const MediaMessageInfo({
    required this.eventId,
    required this.url,
    required this.mimeType,
    required this.caption,
    required this.sender,
    required this.createdAt,
    required this.size,
    required this.outgoing,
  });

  final String eventId;
  final String url;
  final String mimeType;
  final String caption;
  final String sender;
  final int createdAt;
  final int size;
  final bool outgoing;
}

/// One person's emoji reaction to one message.
///
/// Flat rather than "a message plus its reactions" because reactions arrive
/// independently of the message they are about — a reaction can outrun the
/// backfill of its target — so the UI joins them by [targetId], the same way it
/// resolves a `replyTo` into a quoted preview.
class ReactionInfo {
  const ReactionInfo({
    required this.targetId,
    required this.peer,
    required this.reactor,
    required this.emoji,
    required this.createdAt,
    required this.outgoing,
  });

  /// Event id of the message reacted to — a text message or an attachment.
  final String targetId;
  final String peer;
  final String reactor;

  /// Empty on a withdrawal, which is how the wire says "I take mine back".
  final String emoji;
  final int createdAt;

  /// Whether *this device* sent it, so the UI can highlight your own.
  final bool outgoing;
}

/// Decrypted attachment bytes.
///
/// The Kotlin/JS bridges both carried base64; the repository interface takes
/// raw bytes because `flutter_rust_bridge` passes `Vec<u8>` as a
/// [Uint8List] natively, and a base64 round-trip of a 10 MB attachment is
/// pure waste.
@immutable
class MediaBytes {
  const MediaBytes({required this.mimeType, required this.bytes});

  final String mimeType;
  final Uint8List bytes;
}

/// A public post on the Sabha feed.
@immutable
class ChitthiInfo {
  const ChitthiInfo({
    required this.id,
    required this.author,
    required this.content,
    required this.createdAt,
    this.replyTo,
  });

  final String id;
  final String author;
  final String content;
  final int createdAt;
  final String? replyTo;
}

/// A journal entry — strictly local. Nothing about the journal is synchronised,
/// published or uploaded; the one way an entry's words reach anybody is the
/// user handing one to one person (`ComradeRepository.shareJournalEntry`),
/// which sends a copy and leaves the entry alone.
@immutable
class JournalEntryInfo {
  const JournalEntryInfo({
    required this.id,
    required this.text,
    required this.createdAt,
    this.mood,
  });

  final String id;
  final String text;
  final String? mood;
  final int createdAt;
}

/// One turn of the Tara thread.
@immutable
class TaraMessageInfo {
  const TaraMessageInfo({
    required this.id,
    required this.text,
    required this.fromTara,
    required this.crisis,
    required this.createdAt,
  });

  final String id;
  final String text;

  /// True for Tara's replies, false for the user's messages.
  final bool fromTara;

  /// True when the turn tripped the distress detector — the UI must show the
  /// crisis card, and must NOT stream the reply.
  final bool crisis;
  final int createdAt;
}

/// A real place to turn, rendered under any reply that detected distress.
@immutable
class CrisisResourceInfo {
  const CrisisResourceInfo({
    required this.name,
    required this.contact,
    required this.note,
  });

  final String name;
  final String contact;
  final String note;
}

/// A logged voice/video call.
@immutable
class CallRecordInfo {
  const CallRecordInfo({
    required this.id,
    required this.peer,
    required this.media,
    required this.incoming,
    required this.outcome,
    required this.startedAt,
    required this.durationSecs,
  });

  final String id;
  final String peer;

  /// `"audio"` or `"video"` — the wire value the core stores.
  final String media;
  final bool incoming;

  /// `connected | missed | declined | busy | cancelled | failed`.
  final String outcome;
  final int startedAt;
  final int durationSecs;

  bool get isVideo => media == 'video';
}

/// A relay's URL only — **never** the username/credential.
///
/// `set_turn_server` is write-only on both existing frontends (AUDIT COMMS-02):
/// nothing reads a saved credential back, so nothing can leak it. The unified
/// app keeps that discipline — this class has no credential field to populate.
@immutable
class TurnServerStatus {
  const TurnServerStatus({required this.configured, this.url});

  const TurnServerStatus.none()
      : configured = false,
        url = null;

  final bool configured;
  final String? url;
}

/// The honest outcome of "can a relay actually be reached?".
enum TurnDiagnostic { noServerConfigured, relayAvailable, relayUnavailable }

/// Off-grid Saathi mesh status.
@immutable
class MeshStatus {
  const MeshStatus({required this.active, required this.peerCount});

  const MeshStatus.idle()
      : active = false,
        peerCount = 0;

  final bool active;
  final int peerCount;
}

/// The active workspace (Base / Off-Grid Travel / Couple Sandbox).
@immutable
class WorkspaceInfo {
  const WorkspaceInfo({
    required this.key,
    required this.label,
    required this.relayConnected,
    required this.meshActive,
    required this.coupleSandbox,
  });

  const WorkspaceInfo.base()
      : key = 'Base',
        label = 'Base',
        relayConnected = true,
        meshActive = false,
        coupleSandbox = false;

  final String key;
  final String label;
  final bool relayConnected;
  final bool meshActive;
  final bool coupleSandbox;

  /// `Sakhi` when the key ends in it, otherwise `Sakha`.
  String get coupleRole => key.endsWith('Sakhi') ? 'Sakhi' : 'Sakha';
}

/// Sakha/Sakhi pairing state for the couple sandbox.
@immutable
class SakhaStatus {
  const SakhaStatus({required this.paired, this.partnerNpub, this.role});

  const SakhaStatus.unpaired()
      : paired = false,
        partnerNpub = null,
        role = null;

  final bool paired;
  final String? partnerNpub;

  /// `sakha` | `sakhi`.
  final String? role;
}

/// An ICE server the call engine should use.
@immutable
class IceServerInfo {
  const IceServerInfo({required this.urls, this.username, this.credential});

  final List<String> urls;
  final String? username;
  final String? credential;
}

/// The result of `place_call`: the id both sides key their signals by.
@immutable
class CallSessionInfo {
  const CallSessionInfo({
    required this.callId,
    required this.peer,
    required this.media,
    required this.iceServers,
  });

  final String callId;
  final String peer;
  final String media;
  final List<IceServerInfo> iceServers;
}

/// A UPI payment intent extracted from message text (`/pay 250 to a@upi`).
@immutable
class UpiIntent {
  const UpiIntent(
      {required this.amountInr, required this.vpa, required this.uri});

  final double amountInr;
  final String vpa;
  final String uri;
}

// ── Bridge events ───────────────────────────────────────────────────────────

/// Events pushed from the Rust core.
///
/// Mirrors `uniffi.comrade_ui.BridgeEvent` / the desktop `comrade://event`
/// payloads. A sealed hierarchy so `switch` over it is exhaustive — the
/// Kotlin side got that from `sealed interface`, the JS side got nothing.
sealed class BridgeEvent {
  const BridgeEvent();
}

class IncomingChitthi extends BridgeEvent {
  const IncomingChitthi(this.chitthi);
  final ChitthiInfo chitthi;
}

class IncomingDirectMessage extends BridgeEvent {
  const IncomingDirectMessage(this.message);
  final MessageInfo message;
}

class IncomingMedia extends BridgeEvent {
  const IncomingMedia(this.media);
  final MediaMessageInfo media;
}

/// A peer reacted to a message, changed their reaction, or took it back — an
/// empty [ReactionInfo.emoji] is the withdrawal.
///
/// Emitted only when the visible state actually changed, so a replay off the
/// two-day gift-wrap backfill redraws nothing.
class IncomingReaction extends BridgeEvent {
  const IncomingReaction(this.reaction);
  final ReactionInfo reaction;
}

class IncomingMessageRequest extends BridgeEvent {
  const IncomingMessageRequest(this.request);
  final MessageRequestInfo request;
}

/// Delivery/read receipts for a batch of our own outgoing messages.
class MessageStatusChanged extends BridgeEvent {
  const MessageStatusChanged({
    required this.peer,
    required this.messageIds,
    required this.status,
  });

  final String peer;
  final List<String> messageIds;
  final MessageStatus status;
}

class PeerProfileUpdated extends BridgeEvent {
  const PeerProfileUpdated({required this.peer, this.name});
  final String peer;
  final String? name;
}

/// A comrade came online, went offline, or aged out.
///
/// Emitted only for peers the user marked as comrades, and only on an actual
/// transition — a heartbeat from someone already known to be online is not
/// news.
class ComradePresenceChanged extends BridgeEvent {
  const ComradePresenceChanged({
    required this.peer,
    required this.online,
    required this.at,
    this.name,
  });

  final String peer;

  /// Display name at the time of the event (alias → published handle), so a
  /// notification can be raised without a store round-trip.
  final String? name;
  final bool online;

  /// Send time of the beacon (unix seconds) for the `online` edge; the moment
  /// the claim lapsed for an aged-out `offline` edge.
  final int at;
}

/// A comrade wrote something for us and never sent it — see
/// `comrade_core::nudge`.
///
/// One event per hesitation, only for a peer the user marked as a comrade, and
/// only while it is still fresh. It carries a name for the notification and
/// nothing else: not the draft, not its length, not whether they cleared it or
/// walked away. It is also not presence — it moves no dot and advances no "last
/// seen", both of which belong to beacons alone.
class IncomingComradeNudge extends BridgeEvent {
  const IncomingComradeNudge({required this.peer, this.name});
  final String peer;

  /// Display name at the time of the event (alias → published handle), so a
  /// notification can be raised without a store round-trip.
  final String? name;
}

class MeshStatusChanged extends BridgeEvent {
  const MeshStatusChanged(this.status);
  final MeshStatus status;
}

class LedgerUpdated extends BridgeEvent {
  const LedgerUpdated(this.ledger);
  final String ledger;
}

/// The peer named a topic, filed a thread under one, or archived one — the
/// structure of [peer]'s conversation moved.
///
/// Carries the conversation and nothing else. The sheets re-read the whole
/// conversation anyway (the counts are derived, not stored — see [TopicInfo]),
/// so a finer payload would be read and thrown away. Core only emits this when
/// the visible state actually changed, so a replay off the two-day gift-wrap
/// backfill redraws nothing.
class TopicsChanged extends BridgeEvent {
  const TopicsChanged(this.peer);
  final String peer;
}

/// A call signal (offer/answer/ice/ringing/busy/hangup) from a peer.
class IncomingCallSignal extends BridgeEvent {
  const IncomingCallSignal({
    required this.peer,
    required this.callId,
    required this.media,
    required this.kind,
    this.sdp,
    this.candidate,
    this.reason,
  });

  final String peer;
  final String callId;
  final String media;

  /// `offer | answer | ice | ringing | busy | hangup`.
  final String kind;
  final String? sdp;
  final String? candidate;
  final String? reason;
}
