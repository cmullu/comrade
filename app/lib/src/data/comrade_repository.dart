/// The single seam between the UI and the Rust core.
///
/// ## Why this exists
///
/// Both existing frontends talk to `comrade_ui` through a thin, hand-maintained
/// adapter — `ComradeCore.kt` (60 uniffi exports) on Android, `commands.rs`
/// (54 Tauri commands) on desktop. The unified app gets exactly one, and it is
/// this interface: every screen depends on [ComradeRepository], never on the
/// generated bridge, so the UI is runnable and testable before the bridge
/// lands and stays testable afterwards.
///
/// ## Wiring the real bridge
///
/// When `package:comrade/src/rust/api.dart` exists, add a
/// `RustComradeRepository implements ComradeRepository` beside this file that
/// (a) calls the generated `Future`-returning functions, and (b) maps the
/// generated event stream into [BridgeEvent]. Nothing else changes: override
/// `comradeRepositoryProvider` in `main.dart` and the whole app switches over.
/// The generated file is deliberately **not** written here — inventing its
/// shape would create a third export surface that the real codegen would then
/// contradict (see `docs/FRONTEND_STRATEGY.md` D5).
///
/// Every method is `async` because every one of these is either genuinely
/// async in Rust or blocking I/O that must not run on the UI isolate. That is
/// a deliberate change from `ComradeCore.kt`, which wraps ~12 genuinely
/// `suspend` Rust exports in `runBlocking` to keep old call sites unchanged;
/// there are no old call sites here.
library;

import 'dart:typed_data';

import 'models.dart';

/// Thrown for any backend failure the UI is expected to show to the user.
///
/// The Rust side's validation messages are written to be user-safe (the TURN
/// validator, for instance, only ever describes the URL's shape — never the
/// credential), so [message] can be rendered directly.
class ComradeException implements Exception {
  const ComradeException(this.message, {this.cause});

  final String message;
  final Object? cause;

  @override
  String toString() => 'ComradeException: $message';
}

abstract interface class ComradeRepository {
  // ── Lifecycle ────────────────────────────────────────────────────────────

  /// Library version string (e.g. "0.1.0").
  Future<String> version();

  /// Unlock (or, on a fresh path, forge) the encrypted vault.
  ///
  /// Returns the active profile. Throws [ComradeException] on a wrong
  /// passphrase.
  Future<Profile> unlockVault(
      {required String path, required String passphrase});

  /// The active profile, or `null` when the vault is locked.
  ///
  /// Android's `currentProfileTyped()` throws when locked and the caller
  /// treats the throw as "locked"; a nullable return says the same thing
  /// without using exceptions for control flow.
  Future<Profile?> currentProfile();

  /// Drop the decrypted vault key from memory and abort the background loops.
  Future<void> lockVault();

  // ── Identity & contacts ──────────────────────────────────────────────────

  Future<Profile> setUsername(String name);

  Future<List<FoundProfile>> searchProfiles(String query);

  Future<List<ContactInfo>> contacts();

  /// Pin a key (trust-on-first-use). A blank [alias] means "no alias".
  Future<ContactInfo> addContact({required String npub, String alias = ''});

  /// Set (or, with a blank [alias], clear) the local petname for a contact.
  Future<ContactInfo> setContactAlias(
      {required String npub, required String alias});

  // ── Comrades (chosen-peer presence) ──────────────────────────────────────

  /// Choose (or un-choose) [npub] as a comrade.
  ///
  /// This is a disclosure, and the UI must present it as one: from then on
  /// this device tells *that one peer* — nobody else, and no relay — when it
  /// is online. It does **not** subscribe us to theirs; presence only arrives
  /// once they have marked us back, which [ComradeInfo.peerMarkedUs] reports
  /// so the wait can be explained rather than looking broken.
  ///
  /// Marking someone never saved creates the contact record. Idempotent.
  Future<ContactInfo> setComrade({
    required String npub,
    required bool comrade,
  });

  /// Every comrade with their live presence, online first. Empty until the
  /// user marks someone — the feature costs nothing while nobody is chosen.
  Future<List<ComradeInfo>> comrades();

  /// One peer's live presence, or `null` when no beacon has ever arrived from
  /// them. `null` is an honest "we have never heard from them", which is not
  /// the same as "offline" — do not collapse the two.
  Future<PresenceInfo?> peerPresence(String npub);

  /// Announce this device's presence to every comrade; returns how many
  /// beacons a relay accepted. Called on foreground/background transitions —
  /// the core's heartbeat covers the rest. Never throws: presence is a
  /// courtesy, so a locked vault is a quiet `0`.
  Future<int> announcePresence(bool online);

  // ── Conversations ────────────────────────────────────────────────────────

  Future<List<ConversationInfo>> conversations();

  /// Message history with [peer], oldest first.
  Future<List<MessageInfo>> messages(String peer);

  /// Send a DM, optionally as a reply to [replyTo].
  Future<MessageInfo> sendDm({
    required String peer,
    required String content,
    String? replyTo,
  });

  /// Mark the thread read (sends read receipts) and record how far the user has
  /// now read, returning the position they had reached *before* this call —
  /// `createdAt` of the newest message they had already seen, or 0 if this is
  /// the first visit.
  ///
  /// One call rather than a getter plus a setter so there is no window in which
  /// this very call has already overwritten the answer the caller is about to
  /// position the thread on. See `firstUnreadIndex`.
  Future<int> markConversationRead(String peer);

  Future<List<MessageRequestInfo>> messageRequests();

  Future<void> acceptRequest(String peer);

  Future<void> blockConversation(String peer);

  // ── Encrypted media ──────────────────────────────────────────────────────

  /// Full encrypted-media history with [peer], oldest first.
  Future<List<MediaMessageInfo>> media(String peer);

  Future<MediaMessageInfo> sendMedia({
    required String peer,
    required String mimeType,
    required String caption,
    required Uint8List bytes,
  });

  Future<MediaBytes> downloadMedia(String eventId);

  // ── Sabha (public feed) ──────────────────────────────────────────────────

  Future<List<ChitthiInfo>> sabhaTimeline();

  /// Broadcast a Chitthi, returning the new event id.
  Future<String> broadcastChitthi(String content, {String? replyTo});

  /// Extract UPI `/pay` intents from composer text (live preview).
  Future<List<UpiIntent>> extractPayments(String text);

  // ── Journal (strictly local) ─────────────────────────────────────────────

  Future<List<JournalEntryInfo>> journal();

  Future<JournalEntryInfo> addJournalEntry(
      {required String text, String? mood});

  Future<bool> deleteJournalEntry(String id);

  // ── Tara (reflective companion — strictly local, not therapy) ────────────

  Future<List<TaraMessageInfo>> taraThread();

  /// One turn. The returned message carries `crisis`, which the UI must honour
  /// by rendering the reply **complete** (never streamed) plus the crisis card.
  Future<TaraMessageInfo> taraSend(String text);

  Future<String> taraOpener();

  Future<List<CrisisResourceInfo>> taraCrisisResources();

  /// Wipe the thread. Returns how many messages were removed.
  Future<int> clearTaraThread();

  // ── Calls ────────────────────────────────────────────────────────────────

  Future<List<CallRecordInfo>> callHistory({String? peer});

  Future<CallSessionInfo> placeCall(
      {required String peer, required String media});

  Future<void> sendCallSignal({
    required String peer,
    required String callId,
    required String media,
    required String signalJson,
  });

  Future<void> hangupCall({
    required String peer,
    required String callId,
    required String media,
    required String reason,
  });

  Future<CallRecordInfo> logCall({
    required String peer,
    required String callId,
    required String media,
    required bool incoming,
    required String outcome,
    required int startedAt,
    required int durationSecs,
  });

  Future<List<IceServerInfo>> callIceServers({String strategy = 'stun_only'});

  /// The 4-emoji short authentication string, or `null` when either side's SDP
  /// carried no DTLS fingerprint. `null` is an honest "can't verify", not an
  /// error — never fabricate a code.
  Future<List<String>?> callSas(
      {required String localSdp, required String remoteSdp});

  // ── Settings ─────────────────────────────────────────────────────────────

  /// A relay's URL only — never its credential.
  Future<TurnServerStatus> turnServerStatus();

  /// Configure (or, with a blank [url], clear) the TURN relay.
  ///
  /// [username]/[credential] are **write-only**: nothing in this interface
  /// reads them back, because nothing in the core exposes them (AUDIT
  /// COMMS-02). A UI that pre-filled these fields would be lying about what it
  /// knows.
  Future<void> setTurnServer({
    required String url,
    required String username,
    required String credential,
  });

  /// Probe whether the configured relay can actually be reached.
  Future<TurnDiagnostic> testTurnConnectivity();

  // ── Workspace / mesh / couple sandbox ────────────────────────────────────

  Future<MeshStatus> meshStatus();

  Future<WorkspaceInfo> currentWorkspace();

  Future<WorkspaceInfo> toggleWorkspace(String target);

  Future<SakhaStatus> sakhaStatus();

  Future<SakhaStatus> pairSakha(
      {required String partnerNpub, required String role});

  /// Append an entry to the shared CRDT ledger; returns the rendered ledger.
  Future<String> sakhaAddEntry({
    required String description,
    required double amountInr,
    required String paidBy,
  });

  Future<String> sakhaReadLedger();

  Future<String> syncLedger();

  // ── Events ───────────────────────────────────────────────────────────────

  /// Live events pushed from the core.
  ///
  /// A broadcast stream: the chat list, the open thread, the feed and the call
  /// controller all listen. On Android this fan-out was `ChatEventRouter`
  /// (single consumer of the native queue) plus per-screen "tick" ints; here
  /// each provider subscribes to the events it cares about and nothing polls.
  Stream<BridgeEvent> get events;

  /// Release any resources (subscriptions, native handles).
  Future<void> dispose();
}
