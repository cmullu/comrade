/// The [ComradeRepository] that actually talks to Rust.
///
/// Everything here is a translation layer and nothing more: the generated
/// bindings in `lib/src/rust/` are the only thing that knows about the FFI
/// wire, and the view models in `models.dart` are the only thing the UI knows
/// about. This file is the single place the two shapes meet, which is why the
/// screens never had to change when the bridge landed.
///
/// ## Three shapes that do not line up, and what is done about them
///
/// * **`BigInt` timestamps.** Rust's `u64` crosses as [BigInt], because a
///   `u64` does not fit a Dart `int` in general. Unix seconds and byte counts
///   do, so they are narrowed with [BigInt.toInt] at the boundary rather than
///   leaking `BigInt` into every widget.
/// * **Call signals.** [ComradeRepository.sendCallSignal] takes the JSON the
///   WebRTC layer already produces, but the bridge takes a typed `CallSignal`
///   enum. The JSON is parsed here, against the same internally-tagged shape
///   (`{"kind":"offer","sdp":…}`) that `comrade_core::call::CallSignal`
///   serialises to — this is a re-encode, not a new format.
/// * **Errors.** The generated code throws `UiError`, a freezed union. Screens
///   catch [ComradeException]. Every call is funnelled through [_guard], which
///   renders the union into the user-safe message the core already wrote.
///
/// ## What this bridge cannot do yet
///
/// Five [ComradeRepository] methods have **no counterpart in
/// `crates/comrade_jni/src/api.rs`**: [sakhaStatus], [pairSakha],
/// [sakhaAddEntry], [sakhaReadLedger] and [testTurnConnectivity]. The first
/// four exist on `comrade_ui::ComradeRuntime` but are exported by neither
/// foreign ABI; the last has no implementation anywhere in `crates/` — there
/// is no TURN probe to call. They therefore throw a [ComradeException] that
/// names the missing export. Returning a plausible-looking value instead
/// (`unpaired`, `relayAvailable`) would be inventing an answer the core never
/// gave, which is the one failure mode a privacy tool cannot afford.
///
/// ## What the bridge carries that this interface does not
///
/// The reverse gap, listed so it is a decision rather than an oversight.
/// `api.rs` exports these to Dart — reaching parity with the uniffi/Kotlin
/// ABI, so the two frontends can no longer drift — but [ComradeRepository]
/// models no product surface for them yet, and inventing one here would be
/// guessing at a screen nobody has designed:
///
/// * `broadcastAnonymousChitthi` — posting under a throwaway persona.
/// * `flushOutbox` / `outboxPending` — the queued-mail retry and its counter.
/// * `metricsSnapshot` — device-local delivery counters for a diagnostics
///   screen.
/// * `panicWipe` — the irreversible local-state destroy.
/// * `nudgeComrades` — the deliberate "I might need you", which on Android is
///   sent by the breathing screen. This app has no breathing screen yet (the
///   attention pillar is Android-first, see `docs/ATTENTION.md`), and a
///   disclosure with no UI to explain it is the one thing this feature must
///   not be. It is one line here the day that screen exists.
///
/// And a family that `api.rs` deliberately does *not* export yet: the in-chat
/// commands (`/task`, `@tara`, `/comrade-breathe`, `/play` — see
/// `docs/CHAT_ACTIONS.md`). They are on the uniffi surface for Kotlin and on
/// the Tauri surface for desktop, and they are kept off the FRB surface for one
/// reason only:
///
/// * The composer here has no picker, no mention chips and no aside styling,
///   and an aside that looks like a message is precisely the failure that
///   feature must not have — `@tara` reaching `sendDmReply` would send somebody
///   their own private thought.
///
/// **A second reason used to be given here and it is no longer true.** It said
/// adding them would mean regenerating `frb_generated.rs` and that the change
/// introducing them could not, because `flutter_rust_bridge_codegen` was
/// unavailable. The threads-and-topics work of 2026-08-14 installed the pinned
/// 2.12.0 codegen and regenerated, so codegen is a step somebody can take, not
/// a blocker — see `.claude/rules/flutter.md` for the exact command and why
/// `--no-web` is not optional. What is left is the UI gap above, and that one is
/// real: it is a composer, not a bridge.
///
/// The Rust side is complete and frontend-agnostic.
///
/// Each is a one-line addition to the interface plus a fake implementation
/// when a screen wants it. Nothing here is half-wired: every method the
/// interface declares is implemented by both this class and
/// `FakeComradeRepository`.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_rust_bridge/flutter_rust_bridge.dart' show FrbException;
import 'package:flutter_rust_bridge/flutter_rust_bridge_for_generated.dart'
    show ExternalLibrary;
import 'package:meta/meta.dart';

import '../rust/api.dart' as rust;
import '../rust/frb_generated.dart';
import 'comrade_repository.dart';
import 'models.dart';

class RustComradeRepository implements ComradeRepository {
  /// Wraps an **already-initialised** [RustLib].
  ///
  /// Subscribing to the event stream is done here, in the constructor, and
  /// not lazily on the first `events` read: `bridge_event_stream` is fed by
  /// `tokio::sync::broadcast` channels, whose receivers are not retroactive.
  /// A subscription created after `unlock_vault` would silently miss every
  /// event emitted while the engines were starting up.
  RustComradeRepository() {
    _rustEvents = rust.bridgeEventStream().listen(
      (rust.BridgeEvent event) {
        final BridgeEvent? mapped = mapBridgeEvent(event);
        if (mapped != null && !_events.isClosed) _events.add(mapped);
      },
      onError: (Object error, StackTrace stack) {
        if (!_events.isClosed) _events.addError(_translate(error), stack);
      },
    );
  }

  /// Load the native library, start the bridge, and materialise the
  /// process-global `ComradeRuntime`.
  ///
  /// [externalLibrary] is for tests and for hosts that place the cdylib
  /// somewhere the default loader would not look; production passes nothing
  /// and lets `flutter_rust_bridge` find `libcomrade_jni.so` the usual way.
  static Future<RustComradeRepository> connect({
    ExternalLibrary? externalLibrary,
  }) async {
    await RustLib.init(externalLibrary: externalLibrary);
    // Idempotent, and safe when Kotlin's background services got there first:
    // both ABIs resolve the same `OnceLock`.
    await rust.initRuntime();
    return RustComradeRepository();
  }

  final StreamController<BridgeEvent> _events =
      StreamController<BridgeEvent>.broadcast();
  late final StreamSubscription<rust.BridgeEvent> _rustEvents;

  @override
  Stream<BridgeEvent> get events => _events.stream;

  @override
  Future<void> dispose() async {
    await _rustEvents.cancel();
    await _events.close();
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  // `version()` is `#[frb(sync)]`, so there is genuinely nothing to await; the
  // interface is uniformly async so callers never have to know which is which.
  @override
  Future<String> version() async => _guardSync(rust.version);

  @override
  Future<Profile> unlockVault({
    required String path,
    required String passphrase,
  }) async {
    final rust.IdentityDto identity = await _guard(
      () => rust.unlockVault(path: path, passphrase: passphrase),
    );
    // `unlock_vault` returns the *identity*; the published @handle lives on
    // the profile record, which is only readable once the store is open.
    return Profile(npub: identity.npub, username: await _username());
  }

  @override
  Future<Profile?> currentProfile() async {
    final rust.IdentityDto? identity = await _guard(rust.currentIdentity);
    if (identity == null) return null;
    return Profile(npub: identity.npub, username: await _username());
  }

  @override
  Future<void> lockVault() => _guard(rust.lockVault);

  /// The published `@handle`, or `null` when there is none *or* the store
  /// cannot be read. Never throws: a missing handle must not turn a
  /// successful unlock into a failed one.
  Future<String?> _username() async {
    try {
      return (await rust.profile()).username;
    } on FrbException {
      return null;
    }
  }

  // ── Identity & contacts ────────────────────────────────────────────────────

  @override
  Future<Profile> setUsername(String name) async =>
      _profile(await _guard(() => rust.setUsername(name: name)));

  @override
  Future<List<FoundProfile>> searchProfiles(String query) async =>
      (await _guard(() => rust.searchProfiles(query: query)))
          .map(_foundProfile)
          .toList();

  @override
  Future<List<ContactInfo>> contacts() async =>
      (await _guard(rust.listContacts)).map(_contact).toList();

  @override
  Future<ContactInfo> addContact({
    required String npub,
    String alias = '',
  }) async =>
      _contact(await _guard(() => rust.addContact(npub: npub, alias: alias)));

  @override
  Future<ContactInfo> setContactAlias({
    required String npub,
    required String alias,
  }) async =>
      _contact(
        await _guard(() => rust.setContactAlias(npub: npub, alias: alias)),
      );

  // ── Comrades (chosen-peer presence) ────────────────────────────────────────

  @override
  Future<ContactInfo> setComrade({
    required String npub,
    required bool comrade,
  }) async =>
      _contact(
        await _guard(() => rust.setComrade(npub: npub, comrade: comrade)),
      );

  @override
  Future<List<ComradeInfo>> comrades() async =>
      (await _guard(rust.comrades)).map(_comrade).toList();

  @override
  Future<PresenceInfo?> peerPresence(String npub) async {
    final rust.PresenceDto? dto =
        await _guard(() => rust.peerPresence(npub: npub));
    return dto == null ? null : _presence(dto);
  }

  @override
  Future<int> announcePresence(bool online) async =>
      (await _guard(() => rust.announcePresence(online: online))).toInt();

  @override
  Future<void> noteDraft(String npub) =>
      _guard(() => rust.noteDraft(peer: npub));

  @override
  Future<void> abandonDraft(String npub) =>
      _guard(() => rust.abandonDraft(peer: npub));

  // ── Conversations ──────────────────────────────────────────────────────────

  @override
  Future<List<ConversationInfo>> conversations() async =>
      (await _guard(rust.conversations)).map(_conversation).toList();

  @override
  Future<List<MessageInfo>> messages(String peer) async =>
      (await _guard(() => rust.messagesWith(peer: peer)))
          .map(_message)
          .toList();

  @override
  Future<MessageInfo> sendDm({
    required String peer,
    required String content,
    String? replyTo,
  }) async =>
      // `send_dm_reply` with a null `replyTo` is exactly `send_dm`, so there is
      // no reason to branch.
      _message(await _guard(
        () => rust.sendDmReply(
          target: peer,
          content: content,
          replyTo: replyTo,
        ),
      ));

  @override
  Future<int> markConversationRead(String peer) async =>
      (await _guard(() => rust.markConversationRead(peer: peer))).toInt();

  // ── Threads and topics (see `comrade_core::topic`) ───────────────────────

  @override
  Future<List<TopicInfo>> topics(String peer) async =>
      (await _guard(() => rust.topics(peer: peer))).map(_topic).toList();

  @override
  Future<List<ThreadInfo>> threads(String peer, {String? topicSlug}) async =>
      (await _guard(() => rust.threads(peer: peer, topicSlug: topicSlug)))
          .map(_thread)
          .toList();

  @override
  Future<ThreadDetail> thread({
    required String peer,
    required String rootId,
  }) async {
    final rust.ThreadDto dto =
        await _guard(() => rust.thread(peer: peer, rootId: rootId));
    return ThreadDetail(
      rootId: dto.rootId,
      topicSlug: dto.topicSlug,
      messages: dto.messages.map(_message).toList(),
      media: dto.media.map(_media).toList(),
    );
  }

  @override
  Future<TopicInfo> createTopic({
    required String peer,
    required String name,
  }) async =>
      _topic(await _guard(() => rust.createTopic(peer: peer, name: name)));

  @override
  Future<ThreadInfo> assignThread({
    required String peer,
    required String messageId,
    String? topicName,
  }) async =>
      _thread(await _guard(
        () => rust.assignThread(
          peer: peer,
          messageId: messageId,
          topicName: topicName,
        ),
      ));

  @override
  Future<TopicInfo> setTopicClosed({
    required String peer,
    required String slug,
    required bool closed,
  }) async =>
      _topic(await _guard(
        () => rust.setTopicClosed(peer: peer, slug: slug, closed: closed),
      ));

  @override
  Future<MessageInfo> sendThreadReply({
    required String peer,
    required String rootId,
    required String content,
  }) async =>
      _message(await _guard(
        () =>
            rust.sendThreadReply(peer: peer, rootId: rootId, content: content),
      ));

  @override
  Future<List<MessageRequestInfo>> messageRequests() async =>
      (await _guard(rust.messageRequests)).map(_messageRequest).toList();

  @override
  Future<void> acceptRequest(String peer) =>
      _guard(() => rust.acceptRequest(peer: peer));

  @override
  Future<void> blockConversation(String peer) =>
      _guard(() => rust.blockConversation(peer: peer));

  // ── Encrypted media ────────────────────────────────────────────────────────

  @override
  Future<List<MediaMessageInfo>> media(String peer) async =>
      (await _guard(() => rust.mediaWith(peer: peer))).map(_media).toList();

  @override
  Future<List<ReactionInfo>> reactions(String peer) async =>
      (await _guard(() => rust.reactions(peer: peer))).map(_reaction).toList();

  @override
  Future<ReactionInfo?> toggleReaction({
    required String peer,
    required String targetId,
    required String emoji,
  }) async {
    final rust.ReactionDto? dto = await _guard(
      () => rust.toggleReaction(peer: peer, targetId: targetId, emoji: emoji),
    );
    return dto == null ? null : _reaction(dto);
  }

  @override
  Future<MediaMessageInfo> sendMedia({
    required String peer,
    required String mimeType,
    required String caption,
    required Uint8List bytes,
  }) async =>
      _media(await _guard(
        () => rust.uploadAndSendMedia(
          targetPubkey: peer,
          bytes: bytes,
          mimeType: mimeType,
          caption: caption,
        ),
      ));

  @override
  Future<MediaBytes> downloadMedia(String eventId) async {
    final rust.MediaBytesDto dto =
        await _guard(() => rust.downloadAndDecryptMedia(eventId: eventId));
    return MediaBytes(
      mimeType: dto.mimeType,
      bytes: base64Decode(dto.base64),
    );
  }

  // ── Sabha (public feed) ────────────────────────────────────────────────────

  @override
  Future<List<ChitthiInfo>> sabhaTimeline() async =>
      (await _guard(rust.fetchSabhaTimeline)).map(_chitthi).toList();

  @override
  Future<String> broadcastChitthi(String content, {String? replyTo}) =>
      _guard(() => rust.broadcastChitthi(content: content, replyTo: replyTo));

  @override
  Future<List<UpiIntent>> extractPayments(String text) async =>
      (await _guard(() => rust.extractPayments(text: text)))
          .map(_upiIntent)
          .toList();

  // ── Journal ────────────────────────────────────────────────────────────────

  @override
  Future<List<JournalEntryInfo>> journal() async =>
      (await _guard(rust.journalEntries)).map(_journalEntry).toList();

  @override
  Future<JournalEntryInfo> addJournalEntry({
    required String text,
    String? mood,
  }) async =>
      _journalEntry(
        await _guard(() => rust.addJournalEntry(text: text, mood: mood)),
      );

  @override
  Future<bool> deleteJournalEntry(String id) =>
      _guard(() => rust.deleteJournalEntry(id: id));

  @override
  Future<MessageInfo> shareJournalEntry({
    required String peer,
    required String entryId,
  }) async =>
      _message(
        await _guard(
          () => rust.shareJournalEntry(peer: peer, entryId: entryId),
        ),
      );

  // ── Tara ───────────────────────────────────────────────────────────────────

  @override
  Future<List<TaraMessageInfo>> taraThread() async =>
      (await _guard(rust.taraThread)).map(_taraMessage).toList();

  @override
  Future<TaraMessageInfo> taraSend(String text) async =>
      _taraMessage(await _guard(() => rust.taraSend(text: text)));

  @override
  Future<String> taraOpener() => _guard(rust.taraOpener);

  @override
  Future<List<CrisisResourceInfo>> taraCrisisResources() async =>
      (await _guard(rust.taraCrisisResources)).map(_crisisResource).toList();

  @override
  Future<int> clearTaraThread() async =>
      (await _guard(rust.clearTaraThread)).toInt();

  // ── Calls ──────────────────────────────────────────────────────────────────

  @override
  Future<List<CallRecordInfo>> callHistory({String? peer}) async =>
      (await _guard(() => rust.callHistory(peer: peer)))
          .map(_callRecord)
          .toList();

  @override
  Future<CallSessionInfo> placeCall({
    required String peer,
    required String media,
  }) async {
    final rust.CallSessionDto dto = await _guard(
      () => rust.placeCall(peer: peer, media: parseCallMediaKind(media)),
    );
    return CallSessionInfo(
      callId: dto.callId,
      peer: dto.peer,
      media: dto.media,
      iceServers: dto.iceServers.map(_iceServer).toList(),
    );
  }

  @override
  Future<void> sendCallSignal({
    required String peer,
    required String callId,
    required String media,
    required String signalJson,
  }) =>
      _guard(() => rust.sendCallSignal(
            peer: peer,
            callId: callId,
            media: parseCallMediaKind(media),
            signal: parseCallSignal(signalJson),
          ));

  @override
  Future<void> hangupCall({
    required String peer,
    required String callId,
    required String media,
    required String reason,
  }) =>
      _guard(() => rust.hangupCall(
            peer: peer,
            callId: callId,
            media: parseCallMediaKind(media),
            reason: parseHangupReason(reason),
          ));

  @override
  Future<CallRecordInfo> logCall({
    required String peer,
    required String callId,
    required String media,
    required bool incoming,
    required String outcome,
    required int startedAt,
    required int durationSecs,
  }) async =>
      _callRecord(await _guard(
        () => rust.logCall(
          peer: peer,
          callId: callId,
          media: parseCallMediaKind(media),
          incoming: incoming,
          outcome: outcome,
          startedAt: BigInt.from(startedAt),
          durationSecs: BigInt.from(durationSecs),
        ),
      ));

  @override
  Future<List<IceServerInfo>> callIceServers({
    String strategy = 'stun_only',
  }) async =>
      (await _guard(
        () => rust.callIceServersFor(strategy: parseIceStrategy(strategy)),
      ))
          .map(_iceServer)
          .toList();

  @override
  Future<List<String>?> callSas({
    required String localSdp,
    required String remoteSdp,
  }) =>
      _guard(() => rust.callSas(localSdp: localSdp, remoteSdp: remoteSdp));

  // ── Settings ───────────────────────────────────────────────────────────────

  @override
  Future<TurnServerStatus> turnServerStatus() async {
    final rust.TurnServerStatusDto dto = await _guard(rust.turnServerStatus);
    return TurnServerStatus(configured: dto.configured, url: dto.url);
  }

  @override
  Future<void> setTurnServer({
    required String url,
    required String username,
    required String credential,
  }) =>
      _guard(() => rust.setTurnServer(
            url: url,
            username: username,
            credential: credential,
          ));

  @override
  Future<TurnDiagnostic> testTurnConnectivity() async =>
      throw const ComradeException(
        'Relay connectivity cannot be tested from this build: the core '
        'exposes no TURN probe.',
      );

  // ── Workspace / mesh / couple sandbox ──────────────────────────────────────

  @override
  Future<MeshStatus> meshStatus() async => _meshStatus(
        await _guard(rust.meshStatus),
      );

  @override
  Future<WorkspaceInfo> currentWorkspace() async =>
      _workspace(await _guard(rust.currentWorkspace));

  @override
  Future<WorkspaceInfo> toggleWorkspace(String target) async =>
      _workspace(await _guard(() => rust.toggleWorkspace(target: target)));

  @override
  Future<SakhaStatus> sakhaStatus() async => throw _notBridged('sakha_status');

  @override
  Future<SakhaStatus> pairSakha({
    required String partnerNpub,
    required String role,
  }) async =>
      throw _notBridged('pair_sakha');

  @override
  Future<String> sakhaAddEntry({
    required String description,
    required double amountInr,
    required String paidBy,
  }) async =>
      throw _notBridged('sakha_add_entry');

  @override
  Future<String> sakhaReadLedger() async =>
      throw _notBridged('sakha_read_ledger');

  @override
  Future<String> syncLedger() => _guard(rust.syncLedger);

  // ── Error handling ─────────────────────────────────────────────────────────

  static ComradeException _notBridged(String rustFn) => ComradeException(
        'The couple sandbox is unavailable in this build: '
        'crates/comrade_jni/src/api.rs exports no `$rustFn`.',
      );

  /// Run [body] and render any bridge failure as a [ComradeException].
  ///
  /// `UiError` is a value the core deliberately returned, and its message is
  /// written to be shown to a user. Any other [FrbException] — a panic that
  /// crossed as `PanicException`, a codec mismatch — is a bug, so it is
  /// wrapped rather than rendered, keeping the original as [
  /// ComradeException.cause].
  static Future<T> _guard<T>(Future<T> Function() body) async {
    try {
      return await body();
    } on FrbException catch (e, stack) {
      Error.throwWithStackTrace(_translate(e), stack);
    }
  }

  static T _guardSync<T>(T Function() body) {
    try {
      return body();
    } on FrbException catch (e, stack) {
      Error.throwWithStackTrace(_translate(e), stack);
    }
  }

  static Object _translate(Object error) => switch (error) {
        final rust.UiError e => ComradeException(describeUiError(e), cause: e),
        final FrbException e =>
          ComradeException('The core failed unexpectedly.', cause: e),
        _ => error,
      };
}

// ── Pure translations ────────────────────────────────────────────────────────
//
// Top-level and `@visibleForTesting` rather than private methods, because they
// are the part of this file with real logic in it and they need no native
// library to exercise — see `test/rust_comrade_repository_test.dart`.

/// The user-facing rendering of a `UiError`.
///
/// The core's own messages (the TURN validator's, the transition machine's)
/// are written to be user-safe and are passed through verbatim; the unit
/// variants get a sentence here because Rust only gave them a name.
@visibleForTesting
String describeUiError(rust.UiError error) => switch (error) {
      rust.UiError_UnknownWorkspace(:final String field0) =>
        'Unknown workspace: $field0',
      rust.UiError_Transition(:final String field0) => field0,
      rust.UiError_NoIdentity() =>
        'No identity yet — unlock the vault to create one.',
      rust.UiError_StoreLocked() ||
      rust.UiError_VaultLocked() =>
        'The vault is locked.',
      rust.UiError_Crypto(:final String field0) => field0,
      rust.UiError_Storage(:final String field0) => field0,
      rust.UiError_Engine(:final String field0) => field0,
      rust.UiError_Catalogue(:final String field0) => field0,
      rust.UiError_Download(:final String field0) => field0,
      // Deliberately not "nothing found". The distinction is the whole reason
      // this variant exists rather than an empty result list: the build cannot
      // search at all, and telling somebody their song does not exist because a
      // Cargo feature is off is a wrong answer delivered confidently.
      rust.UiError_CatalogueUnavailable() =>
        'This build cannot search for music.',
    };

/// `"audio"`/`"video"` — the wire strings the rest of the app already speaks.
///
/// Unlike the core's `from_str_lenient`, an unrecognised value is rejected
/// rather than defaulting: a typo silently placing a video call as audio is a
/// privacy surprise, not a convenience.
@visibleForTesting
rust.CallMediaKind parseCallMediaKind(String media) =>
    switch (media.trim().toLowerCase()) {
      'audio' => rust.CallMediaKind.audio,
      'video' => rust.CallMediaKind.video,
      _ => throw ComradeException('Unknown call media kind: "$media".'),
    };

/// Mirrors `comrade_core::call::HangupReason`'s `from_str_lenient`: anything
/// unrecognised is `unknown`, which is exactly what that variant is for.
@visibleForTesting
rust.HangupReason parseHangupReason(String reason) =>
    switch (reason.trim().toLowerCase()) {
      'normal' => rust.HangupReason.normal,
      'declined' => rust.HangupReason.declined,
      'busy' => rust.HangupReason.busy,
      'missed' => rust.HangupReason.missed,
      'cancelled' || 'canceled' => rust.HangupReason.cancelled,
      'failed' => rust.HangupReason.failed,
      _ => rust.HangupReason.unknown,
    };

/// Mirrors `comrade_core::call::IceStrategy`'s `from_str_lenient`, including
/// its deliberate bias: anything unrecognised is STUN-only, so a malformed
/// value can never route media through a TURN relay by accident.
@visibleForTesting
rust.IceStrategy parseIceStrategy(String strategy) =>
    switch (strategy.trim().toLowerCase()) {
      'stun_and_turn' => rust.IceStrategy.stunAndTurn,
      _ => rust.IceStrategy.stunOnly,
    };

/// Parse the internally-tagged JSON `comrade_core::call::CallSignal`
/// serialises to (`#[serde(tag = "kind", rename_all = "snake_case")]`).
@visibleForTesting
rust.CallSignal parseCallSignal(String signalJson) {
  final Object? decoded = jsonDecode(signalJson);
  if (decoded is! Map<String, dynamic>) {
    throw ComradeException('Malformed call signal: expected a JSON object.');
  }
  final Object? kind = decoded['kind'];
  return switch (kind) {
    'offer' => rust.CallSignal.offer(sdp: _str(decoded, 'sdp')),
    'answer' => rust.CallSignal.answer(sdp: _str(decoded, 'sdp')),
    'ice' => rust.CallSignal.ice(
        candidate: _str(decoded, 'candidate'),
        sdpMid: decoded['sdpMid'] as String? ?? decoded['sdp_mid'] as String?,
        sdpMLineIndex:
            (decoded['sdpMLineIndex'] ?? decoded['sdp_m_line_index']) as int?,
      ),
    'ringing' => const rust.CallSignal.ringing(),
    'busy' => const rust.CallSignal.busy(),
    'hangup' => rust.CallSignal.hangup(
        // `#[serde(default = "hangup_default")]` on the Rust side.
        reason: parseHangupReason(decoded['reason'] as String? ?? 'normal'),
      ),
    _ => throw ComradeException('Unknown call signal kind: "$kind".'),
  };
}

String _str(Map<String, dynamic> json, String key) {
  final Object? value = json[key];
  if (value is String) return value;
  throw ComradeException('Call signal is missing a string "$key".');
}

/// Translate one pushed event, or `null` when it cannot be represented.
///
/// Two `null` cases, and they are different in kind.
///
/// A delivery status this build does not know: a newer core adding a fourth
/// receipt state must not crash an older UI, and there is nothing useful to
/// show for a status whose rank is unknown — so it is dropped, which is what
/// "newest-wins ranking" already does to anything that does not outrank what is
/// on screen.
///
/// And the watch-together events, which this app has no surface for. Dropped
/// deliberately rather than half-handled, exactly as `RelayConnectionService`
/// does on Android: a notification about a session nobody can see or leave
/// would be worse than silence. They are listed one by one rather than caught
/// by a wildcard, so that adding a together screen here is a compile error
/// pointing at this spot instead of a silent no-op. See `docs/TOGETHER.md` §9.
@visibleForTesting
BridgeEvent? mapBridgeEvent(rust.BridgeEvent event) => switch (event) {
      rust.BridgeEvent_IncomingChitthi(:final rust.ChitthiDto field0) =>
        IncomingChitthi(_chitthi(field0)),
      rust.BridgeEvent_IncomingDirectMessage(
        :final rust.DirectMessageDto field0
      ) =>
        IncomingDirectMessage(MessageInfo(
          id: field0.id,
          peer: field0.sender,
          // The note's own text when this arrival is a shared journal note, so
          // a bubble appended live reads exactly as the one `messages()` gives
          // after a reload. Core parses the marker on both paths — see
          // `DirectMessageDto::shared_note`.
          content: field0.sharedNote?.text ?? field0.content,
          createdAt: field0.createdAt.toInt(),
          outgoing: false,
          replyTo: field0.replyTo,
          sharedNote: field0.sharedNote == null
              ? null
              : SharedNoteInfo(
                  text: field0.sharedNote!.text,
                  mood: field0.sharedNote!.mood,
                ),
        )),
      rust.BridgeEvent_IncomingMedia(:final rust.MediaMessageDto field0) =>
        IncomingMedia(_media(field0)),
      rust.BridgeEvent_IncomingReaction(:final rust.ReactionDto field0) =>
        IncomingReaction(_reaction(field0)),
      rust.BridgeEvent_IncomingCallSignal(:final rust.CallSignalDto field0) =>
        _incomingCallSignal(field0),
      rust.BridgeEvent_IncomingMessageRequest(
        :final rust.MessageRequestDto field0
      ) =>
        IncomingMessageRequest(_messageRequest(field0)),
      rust.BridgeEvent_MessageStatus(
        :final String peer,
        :final List<String> messageIds,
        :final String status
      ) =>
        switch (MessageStatus.fromWire(status)) {
          final MessageStatus known => MessageStatusChanged(
              peer: peer,
              messageIds: messageIds,
              status: known,
            ),
          null => null,
        },
      rust.BridgeEvent_PeerProfileUpdated(
        :final String peer,
        :final String? name
      ) =>
        PeerProfileUpdated(peer: peer, name: name),
      rust.BridgeEvent_ComradePresence(
        :final String peer,
        :final String? name,
        :final bool online,
        :final BigInt at
      ) =>
        ComradePresenceChanged(
          peer: peer,
          name: name,
          online: online,
          at: at.toInt(),
        ),
      rust.BridgeEvent_ComradeNudge(:final String peer, :final String? name) =>
        IncomingComradeNudge(peer: peer, name: name),
      // Ride mode — no Flutter surface yet; see `docs/RIDE.md` §8. Null rather
      // than a stub for the same reason the Together arms below are: a glance
      // card nobody can see, spoken by nothing, is worse than silence — and a
      // phrase that never reaches a screen must not raise a notification
      // claiming it did. Android is the priority frontend and has it.
      rust.BridgeEvent_RideSignal() => null,
      rust.BridgeEvent_MeshStatusChanged(:final rust.MeshStatusDto field0) =>
        MeshStatusChanged(_meshStatus(field0)),
      rust.BridgeEvent_LedgerUpdated(:final String ledger) =>
        LedgerUpdated(ledger),
      // Watch/listen together — no Flutter surface yet; see the doc comment.
      rust.BridgeEvent_TogetherInvited() => null,
      rust.BridgeEvent_TogetherJoined() => null,
      rust.BridgeEvent_TogetherCommand() => null,
      rust.BridgeEvent_TogetherCorrection() => null,
      rust.BridgeEvent_TogetherEnded() => null,
      rust.BridgeEvent_TogetherShare() => null,
      // Asks the frontend to carry a signal over a direct peer channel. This
      // frontend has no WebRTC at all (divergence D34), so there is nothing to
      // carry it on — and dropping it is correct rather than a stub, because
      // core only ever emits one after a frontend has reported a live channel,
      // which this one never does.
      rust.BridgeEvent_TogetherOutbound() => null,
      // Handing a large attachment over needs a WebRTC data channel, which this
      // frontend has none of — see divergence D34 in `SCREEN_INVENTORY.md`. Null
      // rather than a stub so nothing here pretends the transfer is possible.
      rust.BridgeEvent_AttachmentHandoff() => null,
      // The peer reorganised the conversation. Structure, not a message — so no
      // notification and no chat-list change, only the thread sheets reload.
      rust.BridgeEvent_TopicsChanged(:final String peer) => TopicsChanged(peer),
    };

/// Flatten the typed `CallSignal` union back into the flat shape the call UI
/// reads (`kind` plus whichever payload that kind carries).
IncomingCallSignal _incomingCallSignal(rust.CallSignalDto dto) {
  final rust.CallSignal signal = dto.signal;
  return IncomingCallSignal(
    peer: dto.peer,
    callId: dto.callId,
    media: dto.media,
    kind: switch (signal) {
      rust.CallSignal_Offer() => 'offer',
      rust.CallSignal_Answer() => 'answer',
      rust.CallSignal_Ice() => 'ice',
      rust.CallSignal_Ringing() => 'ringing',
      rust.CallSignal_Busy() => 'busy',
      rust.CallSignal_Hangup() => 'hangup',
    },
    sdp: switch (signal) {
      rust.CallSignal_Offer(:final String sdp) => sdp,
      rust.CallSignal_Answer(:final String sdp) => sdp,
      _ => null,
    },
    candidate: signal is rust.CallSignal_Ice ? signal.candidate : null,
    reason: signal is rust.CallSignal_Hangup ? signal.reason.name : null,
  );
}

// ── DTO → view model ─────────────────────────────────────────────────────────

Profile _profile(rust.ProfileDto dto) =>
    Profile(npub: dto.npub, username: dto.username);

FoundProfile _foundProfile(rust.FoundProfileDto dto) =>
    FoundProfile(npub: dto.npub, name: dto.name, about: dto.about);

ContactInfo _contact(rust.ContactDto dto) => ContactInfo(
      npub: dto.npub,
      alias: dto.alias,
      name: dto.name,
      comrade: dto.comrade,
    );

ComradeInfo _comrade(rust.ComradeDto dto) => ComradeInfo(
      npub: dto.npub,
      alias: dto.alias,
      name: dto.name,
      online: dto.online,
      lastSeenAt: dto.lastSeenAt.toInt(),
      peerMarkedUs: dto.peerMarkedUs,
    );

PresenceInfo _presence(rust.PresenceDto dto) => PresenceInfo(
      peer: dto.peer,
      online: dto.online,
      lastSeenAt: dto.lastSeenAt.toInt(),
      peerMarkedUs: dto.peerMarkedUs,
    );

ConversationInfo _conversation(rust.ConversationDto dto) => ConversationInfo(
      peer: dto.peer,
      alias: dto.alias,
      peerName: dto.peerName,
      lastMessage: dto.lastMessage,
      lastAt: dto.lastAt.toInt(),
      lastOutgoing: dto.lastOutgoing,
      comrade: dto.comrade,
      online: dto.online,
    );

MessageInfo _message(rust.MessageDto dto) => MessageInfo(
      id: dto.id,
      peer: dto.peer,
      content: dto.content,
      createdAt: dto.createdAt.toInt(),
      outgoing: dto.outgoing,
      fromTara: dto.author == rust.MessageAuthor.tara,
      status: MessageStatus.fromWire(dto.status),
      replyTo: dto.replyTo,
      sharedNote: dto.sharedNote == null
          ? null
          : SharedNoteInfo(
              text: dto.sharedNote!.text,
              mood: dto.sharedNote!.mood,
            ),
    );

MessageRequestInfo _messageRequest(rust.MessageRequestDto dto) =>
    MessageRequestInfo(
      peer: dto.peer,
      lastMessage: dto.lastMessage,
      lastAt: dto.lastAt.toInt(),
    );

TopicInfo _topic(rust.TopicDto dto) => TopicInfo(
      slug: dto.slug,
      name: dto.name,
      closed: dto.closed,
      threadCount: dto.threadCount,
      messageCount: dto.messageCount,
      lastActivityAt: dto.lastActivityAt.toInt(),
      mine: dto.mine,
    );

ThreadInfo _thread(rust.ThreadSummaryDto dto) => ThreadInfo(
      rootId: dto.rootId,
      topicSlug: dto.topicSlug,
      preview: dto.preview,
      rootIsMedia: dto.rootIsMedia,
      rootMissing: dto.rootMissing,
      replyCount: dto.replyCount,
      lastAt: dto.lastAt.toInt(),
      unread: dto.unread,
    );

MediaMessageInfo _media(rust.MediaMessageDto dto) => MediaMessageInfo(
      eventId: dto.eventId,
      url: dto.url,
      mimeType: dto.mimeType,
      caption: dto.caption,
      sender: dto.sender,
      createdAt: dto.createdAt.toInt(),
      size: dto.size.toInt(),
      outgoing: dto.outgoing,
    );

ReactionInfo _reaction(rust.ReactionDto dto) => ReactionInfo(
      targetId: dto.targetId,
      peer: dto.peer,
      reactor: dto.reactor,
      emoji: dto.emoji,
      createdAt: dto.createdAt.toInt(),
      outgoing: dto.outgoing,
    );

ChitthiInfo _chitthi(rust.ChitthiDto dto) => ChitthiInfo(
      id: dto.id,
      author: dto.author,
      content: dto.content,
      createdAt: dto.createdAt.toInt(),
      replyTo: dto.replyTo,
    );

JournalEntryInfo _journalEntry(rust.JournalEntryDto dto) => JournalEntryInfo(
      id: dto.id,
      text: dto.text,
      mood: dto.mood,
      createdAt: dto.createdAt.toInt(),
    );

TaraMessageInfo _taraMessage(rust.TaraMessageDto dto) => TaraMessageInfo(
      id: dto.id,
      text: dto.text,
      fromTara: dto.fromTara,
      crisis: dto.crisis,
      createdAt: dto.createdAt.toInt(),
    );

CrisisResourceInfo _crisisResource(rust.CrisisResourceDto dto) =>
    CrisisResourceInfo(name: dto.name, contact: dto.contact, note: dto.note);

CallRecordInfo _callRecord(rust.CallRecordDto dto) => CallRecordInfo(
      id: dto.id,
      peer: dto.peer,
      media: dto.media,
      incoming: dto.incoming,
      outcome: dto.outcome,
      startedAt: dto.startedAt.toInt(),
      durationSecs: dto.durationSecs.toInt(),
    );

IceServerInfo _iceServer(rust.IceServerDto dto) => IceServerInfo(
      urls: dto.urls,
      username: dto.username,
      credential: dto.credential,
    );

UpiIntent _upiIntent(rust.UpiIntentDto dto) =>
    UpiIntent(amountInr: dto.amountInr, vpa: dto.vpa, uri: dto.uri);

MeshStatus _meshStatus(rust.MeshStatusDto dto) =>
    MeshStatus(active: dto.active, peerCount: dto.peerCount.toInt());

WorkspaceInfo _workspace(rust.WorkspaceDto dto) => WorkspaceInfo(
      key: dto.key,
      label: dto.label,
      relayConnected: dto.relayConnected,
      meshActive: dto.meshActive,
      coupleSandbox: dto.coupleSandbox,
    );
