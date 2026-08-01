/*!
 * comrade_ui::runtime — the async IPC bridge orchestrator.
 *
 * [`ComradeRuntime`] is the live "runtime context" the Command & Event Bridge
 * manages behind an `Arc<RwLock<…>>`. It is the single, framework-agnostic
 * aggregate that both the **Tauri desktop** shell (`#[tauri::command]` wrappers)
 * and the **Android** layer (`comrade_jni`'s uniffi-generated Kotlin bindings)
 * drive — keeping all real logic inside the workspace where it is unit-tested
 * and Send/Sync-checked. This crate itself stays uniffi-agnostic beyond
 * deriving `Record`/`Enum`/`Error` on its DTOs — `comrade_jni` is the only
 * place that wraps this type behind actual FFI scaffolding.
 *
 * It composes the sync view-model ([`UiService`] — workspace state, identity,
 * encrypted store) with the live Nostr engines (Sabha public feed, Vault E2E
 * DMs, Sakha couple ledger) and a [`tokio::sync::broadcast`] **event bus**.
 *
 * Naming: the IPC spec refers to this as the `RuntimeContext` app-state handle.
 * It is named `ComradeRuntime` here to avoid colliding with the pure, I/O-free
 * [`comrade_state::RuntimeContext`] (the workspace state machine) that it wraps.
 *
 * Design guarantees the bindings rely on:
 *  • Every method returns a typed [`UiError`] — no `.unwrap()`, no panics — so a
 *    failure becomes a `Promise.reject` (Tauri) or a thrown exception (uniffi).
 *  • Heavy work (relay connect, feed subscription, DM decryption) runs in
 *    spawned Tokio tasks via [`ComradeRuntime::spawn_event_loops`], never on the
 *    caller's UI thread.
 */

use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
use comrade_core::anon;
use comrade_core::attention::{
    self, FocusOutcome, UsageSignal, FOCUS_MAX_MINUTES, FOCUS_MIN_MINUTES,
};
use comrade_core::call::{
    derive_sas, ice_servers_for, new_call_id, parse_call_envelope, validate_turn_url, CallEnvelope,
    CallMediaKind, CallSignal, HangupReason, IceServer, IceStrategy,
};
use comrade_core::crypto::derive_media_key;
use comrade_core::dak::outbox::local_message_id;
use comrade_core::dak::{open_dm, seal_dm, MeshDm};
use comrade_core::dak::{AttemptOutcome, Outbox, OutboxSnapshot, QueueOutcome, QueuedMessage};
use comrade_core::dm::{parse_profile_share, parse_receipt, ProfileShare, Receipt, ReceiptKind};
use comrade_core::media::{
    build_file_metadata_event, encrypt_media, fetch_and_decrypt_media, FileMetadata,
    MAX_MEDIA_BYTES,
};
use comrade_core::metrics as core_metrics;
use comrade_core::metrics::Metric as CoreMetric;
use comrade_core::nudge::{is_fresh_at, nudge_expires_at, parse_nudge, DraftWatch, Nudge};
use comrade_core::presence::{
    is_online_at, parse_presence_beacon, presence_expires_at, PresenceBeacon,
    PRESENCE_HEARTBEAT_SECS, PRESENCE_SWEEP_SECS,
};
use comrade_core::saathi::SaathiEngine;
use comrade_core::sabha::{
    display_name_of, ChitthiCallback, FeedFilterSpec, FeedScope, SabhaEngine, DEFAULT_RELAYS,
};
use comrade_core::sakha::{LedgerEntry, SakhaEngine, SakhaSyncCallback};
use comrade_core::seen::{content_key, SeenSet, CONTENT_KEY_PREFIX};
use comrade_core::tara::{CompanionEngine, JournalSignal, ReflectiveCompanion};
use comrade_core::vault::{
    build_pay_regex, extract_upi_intents, VaultCallback, VaultEngine, VaultMessage,
};
use nostr_sdk::{EventId, Metadata, PublicKey, ToBech32};
use serde::{Deserialize, Serialize};
use tokio::sync::broadcast;
use tracing::warn;

use crate::{IdentityDto, UiError, UiService, UpiIntentDto, WorkspaceDto};

/// Capacity of the *critical* event bus — DMs, message requests, call
/// signals, message status, mesh status, ledger updates. Slow consumers lag
/// rather than block producers, but this channel is never intentionally
/// flooded (a peer sends a human's worth of DMs/calls, not a relay's worth of
/// public notes), so its capacity only needs to absorb a slow consumer
/// briefly falling behind, not an adversarial volume.
const EVENT_BUS_CAPACITY: usize = 256;

/// Capacity of the separate, deliberately small *feed* event bus —
/// `IncomingChitthi` only. AUDIT.md COMMS-04: a public-feed flood must be
/// able to drop old, unconsumed Chitthis under load without ever competing
/// for the same ring-buffer slots a call signal or DM needs — hence a wholly
/// separate [`broadcast::Sender`], not just "the same channel, hope the
/// consumer drains it fast enough". See [`ComradeRuntime::subscribe_feed_events`].
const FEED_EVENT_BUS_CAPACITY: usize = 64;

/// How far back the Chitthi feed subscription looks, in both the
/// authors-scoped and bootstrap cases (see [`ComradeRuntime::feed_filter_spec`]).
const FEED_SINCE_SECS: u64 = 3600;
/// Event cap for the no-contacts-yet bootstrap feed — an explicit bound so a
/// fresh identity's feed is never the unbounded relay-wide firehose either.
const FEED_BOOTSTRAP_LIMIT: usize = 200;

/// HKDF label binding the ECDH shared secret to media encryption.
const MEDIA_LABEL: &str = "comrade-media-v1";

/// What an encrypted attachment is declared as when it is uploaded.
///
/// Deliberately not the file's real type: the body is AES-GCM ciphertext, and
/// naming it `image/jpeg` would both misdescribe it and hand the media host the
/// one piece of metadata the encryption was meant to withhold — what *kind* of
/// thing was just sent. The recipient learns the true type from the encrypted
/// envelope, never from the host.
const OPAQUE_UPLOAD_MIME: &str = "application/octet-stream";
/// Encrypted-store tree mapping a NIP-94 event id → local [`MediaRef`].
const MEDIA_REFS_TREE: &str = "comrade_media_refs";
/// Encrypted-store tree caching peers' published Kind-0 profiles
/// (npub → [`PeerProfileRecord`]). This is what lets the chat UI show
/// "@charlie" instead of a raw public key.
const PEER_PROFILES_TREE: &str = "peer_profiles";
/// Re-fetch a cached peer profile with a known name after this long (seconds).
const PROFILE_TTL_SECS: u64 = 24 * 60 * 60;
/// Re-fetch a cached record with **no** name after this long (seconds).
/// Short: an offline fetch is indistinguishable from "peer has no profile",
/// and it must not freeze a peer as key-only for a whole day.
const PROFILE_NEGATIVE_TTL_SECS: u64 = 5 * 60;
/// Upper bound on network fetches per [`ComradeRuntime::refresh_peer_profiles`] call.
const PROFILE_REFRESH_CAP: usize = 16;
/// Publish attempts before giving up until the next launch (see
/// [`publish_profile_with_retry`]).
const PUBLISH_ATTEMPTS: u32 = 5;
/// Encrypted-store tree for app settings that are not per-peer (e.g. the TURN
/// relay a user has configured for calls).
const SETTINGS_TREE: &str = "app_settings";
/// Settings key holding the optional [`TurnConfig`] for WebRTC calls.
const TURN_CONFIG_KEY: &str = "turn_server";
/// Settings key holding the high-watermark (unix seconds) of the newest
/// inbox message this device has processed — see [`advance_watermark`] and
/// [`ComradeRuntime::spawn_event_loops`]'s `since_floor` computation.
const VAULT_WATERMARK_KEY: &str = "vault_last_seen_at";
/// Encrypted-store tree holding the sender-outbox snapshot — DMs that could not
/// be published, kept so an offline send survives an app kill instead of
/// evaporating (adopted from bitchat's store-and-forward, see
/// `docs/BITCHAT_ADOPTION.md`).
const OUTBOX_TREE: &str = "comrade_outbox";
const OUTBOX_KEY: &str = "queued";
/// Settings key holding the 32-byte device seed every anonymous persona is
/// derived from — see [`load_or_create_device_seed`].
const DEVICE_SEED_KEY: &str = "anonymity_device_seed";
/// Status string on a message that is queued in the outbox, not yet on a relay.
const STATUS_QUEUED: &str = "queued";
/// Status string on a message the outbox gave up on (attempt cap or TTL).
const STATUS_FAILED: &str = "failed";
/// How often the background flush loop retries queued mail. Comrade has no
/// mesh-style "peer reconnected" event to hang a flush on, so a modest fixed
/// cadence stands in for bitchat's reconnect trigger; a flush with an empty
/// outbox costs one lock and returns.
const OUTBOX_FLUSH_INTERVAL_SECS: u64 = 60;

/// Capacity of the call-signal dedup set: comfortably above one call's signal
/// count (offer/answer/several ICE candidates/hangup is a handful of events), so
/// a single call can never evict its own earlier signals mid-negotiation.
/// At-least-once relay delivery plus the 2-day inbox backfill mean the same
/// wrapper can arrive repeatedly, and a duplicate `Answer` or terminal `Hangup`
/// must not be re-applied downstream.
const CALL_SIGNAL_DEDUP_CAPACITY: usize = 512;

/// How long a message stays eligible for **cross-transport** dedup.
///
/// A DM can reach the same person twice by two different routes: sealed over
/// the local mesh now, and over a relay when the internet comes back. The two
/// copies carry different ids (the mesh copy is keyed by the sender's local id,
/// the relay copy by the event id a relay assigned), so id dedup cannot catch
/// the pair — the content can.
///
/// Deliberately narrow, and keyed by transport as well as content: two
/// identical messages that arrive over the *same* route are a person typing
/// "ok" twice and are kept. Only a copy arriving over the *other* route inside
/// this window is treated as the same message. That is the whole trade — a
/// wider window would start eating genuine repeats.
const CROSS_TRANSPORT_DEDUP_SECS: u64 = 120;
/// Recent (peer, content, transport) triples kept for the check above.
const CROSS_TRANSPORT_DEDUP_CAPACITY: usize = 512;
/// Transport labels for that key.
const TRANSPORT_RELAY: &str = "relay";
const TRANSPORT_MESH: &str = "mesh";

/// A call signal older than this is meaningless — the ring timeout has long
/// since passed on the sender's side (an `Offer`), and every other signal
/// kind (`Answer`/`Ice`/`Hangup`/…) is equally transient. Relays redeliver
/// at-least-once and the Vault inbox subscription backfills up to 2 days on
/// every launch, so without this a days-old `Offer` re-rings on every app
/// start.
const CALL_SIGNAL_MAX_AGE_SECS: u64 = 90;
/// Encrypted-store tree holding the Sakha/Sakhi pairing record (there is only
/// ever one partner per device, but a tree keeps the storage shape uniform
/// with the rest of the repository layer).
const SAKHA_TREE: &str = "sakha_pairing";
const SAKHA_PAIRING_KEY: &str = "partner";

/// Conversation gate states (persisted in `ConversationMeta.state`).
const STATE_PENDING: &str = "pending";
const STATE_ACCEPTED: &str = "accepted";
const STATE_BLOCKED: &str = "blocked";

// ── Event DTOs (serialised across the IPC / FFI boundary) ────────────────────

/// A public Chitthi (Kind-1) as the frontend sees it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct ChitthiDto {
    pub id: String,
    pub author: String,
    pub content: String,
    pub created_at: u64,
    pub reply_to: Option<String>,
}

impl ChitthiDto {
    /// Build from a live Nostr Kind-1 event captured in the Tokio feed loop.
    pub fn from_event(event: &nostr_sdk::Event) -> Self {
        let author = event
            .pubkey
            .to_bech32()
            .unwrap_or_else(|_| event.pubkey.to_hex());
        Self {
            id: event.id.to_hex(),
            author,
            content: event.content.clone(),
            created_at: event.created_at.as_secs(),
            reply_to: None,
        }
    }

    /// Build from a row of the offline encrypted Chitthi cache.
    pub fn from_cached(c: &comrade_storage::Chitthi) -> Self {
        Self {
            id: c.id.clone(),
            author: c.author_npub.clone(),
            content: c.content.clone(),
            created_at: c.created_at,
            reply_to: c.reply_to.clone(),
        }
    }
}

/// An incoming encrypted direct message (Kind-4) as the frontend sees it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct DirectMessageDto {
    pub id: String,
    pub sender: String,
    pub content: String,
    pub created_at: u64,
    pub upi_intents: Vec<UpiIntentDto>,
    /// Event id (hex) this message replies to, if any (for a quoted preview).
    pub reply_to: Option<String>,
}

impl From<VaultMessage> for DirectMessageDto {
    fn from(m: VaultMessage) -> Self {
        Self {
            id: m.event_id,
            sender: to_npub(&m.sender_pubkey),
            content: m.content,
            created_at: m.created_at,
            reply_to: m.reply_to,
            upi_intents: m
                .upi_intents
                .into_iter()
                .map(|i| UpiIntentDto {
                    amount_inr: i.amount_inr,
                    vpa: i.vpa,
                    uri: i.uri,
                })
                .collect(),
        }
    }
}

/// A WebRTC ICE server (STUN/TURN) for the frontend's `RTCConfiguration`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct IceServerDto {
    pub urls: Vec<String>,
    pub username: Option<String>,
    pub credential: Option<String>,
}

/// The support/diagnostic "is a relay configured" status — see
/// [`ComradeRuntime::turn_server_status`]. Deliberately carries the URL only,
/// never `username`/`credential`: this DTO is meant to be safe to show
/// directly in a settings screen (and safe to log), unlike the write-only
/// fields [`ComradeRuntime::set_turn_server`] takes.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct TurnServerStatusDto {
    pub configured: bool,
    pub url: Option<String>,
}

impl From<comrade_core::call::IceServer> for IceServerDto {
    fn from(s: comrade_core::call::IceServer) -> Self {
        Self {
            urls: s.urls,
            username: s.username,
            credential: s.credential,
        }
    }
}

/// Everything a frontend needs to begin negotiating a call: the call id, the
/// peer, the media kind, and the ICE servers to hand to `RTCPeerConnection`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct CallSessionDto {
    pub call_id: String,
    pub peer: String,
    pub media: String,
    pub ice_servers: Vec<IceServerDto>,
}

/// One incoming call-signaling payload (offer/answer/ICE/hangup/…) routed to
/// the frontend. `signal` is the actual [`CallSignal`] value (not a JSON blob)
/// so the WebRTC layer — and uniffi, which has no "arbitrary JSON" type — gets
/// a closed enum to `switch` on directly.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct CallSignalDto {
    pub call_id: String,
    pub peer: String,
    pub media: String,
    pub signal: CallSignal,
    /// The signal's true send time (unix seconds) — the DM's own `created_at`
    /// (already de-randomized from the gift-wrap's timestamp skew; see
    /// `comrade_core::vault::VaultMessage`), not when it was received. Lets a
    /// frontend apply its own freshness judgement in addition to the runtime's
    /// own staleness drop (see `CALL_SIGNAL_MAX_AGE_SECS` in `dispatch_incoming_dm`).
    pub created_at: u64,
}

/// A voice/video call-log entry as the frontend sees it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct CallRecordDto {
    pub id: String,
    pub peer: String,
    pub media: String,
    pub incoming: bool,
    pub outcome: String,
    pub started_at: u64,
    pub duration_secs: u64,
}

impl From<comrade_storage::CallRecord> for CallRecordDto {
    fn from(c: comrade_storage::CallRecord) -> Self {
        Self {
            id: c.id,
            peer: c.peer_npub,
            media: c.media,
            incoming: c.incoming,
            outcome: c.outcome,
            started_at: c.started_at,
            duration_secs: c.duration_secs,
        }
    }
}

/// A pending message request: a stranger's DM that is gated out of the chat
/// list until the user accepts it. Only the preview and timing are exposed —
/// the peer's chosen handle is not shared until they, in turn, accept.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct MessageRequestDto {
    pub peer: String,
    pub last_message: String,
    pub last_at: u64,
}

/// This device's Sakha/Sakhi pairing state — lets the frontend show "pair
/// with your partner" or, for a returning paired couple, "continue as
/// {role}" without asking for the partner's key again.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct SakhaStatusDto {
    pub paired: bool,
    pub partner_npub: Option<String>,
    /// `"sakha"` or `"sakhi"` — which role this device paired as, if known.
    pub role: Option<String>,
}

/// A persisted Sakha/Sakhi pairing, so a returning couple survives a
/// relaunch without re-exchanging keys. Never holds the derived symmetric
/// key — that is re-derived from the partner's pubkey plus our own secret
/// key every time [`SakhaEngine::pair_with`] runs.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct SakhaPairingRecord {
    /// Partner's public key, hex-encoded.
    partner_pubkey_hex: String,
    /// `"sakha"` or `"sakhi"`.
    role: String,
}

/// A NIP-94 encrypted-media reference as the frontend sees it (no key material).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct MediaMessageDto {
    /// NIP-94 event id — the handle passed back to `download_and_decrypt_media`.
    pub event_id: String,
    pub url: String,
    pub mime_type: String,
    pub caption: String,
    /// Bech32/hex pubkey of the counterpart (sender for incoming).
    pub sender: String,
    pub created_at: u64,
    /// Size of the encrypted blob in bytes.
    pub size: u64,
    /// Whether *this device* sent it (mirrors `MessageDto::outgoing`) — needed
    /// to tell the two apart once media from both directions is merged into
    /// one history by [`ComradeRuntime::media_with`].
    pub outgoing: bool,
}

/// Decrypted media handed back to the frontend. Bytes are base64-encoded so the
/// IPC payload stays compact (the webview rebuilds a `Blob` from it).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct MediaBytesDto {
    pub mime_type: String,
    pub base64: String,
}

/// Locally persisted pointer to an encrypted blob, keyed by NIP-94 event id.
/// Holds everything needed to *re-derive* the key — but never the key itself.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct MediaRef {
    /// NIP-94 event id (hex) — duplicates the store key this row lives under,
    /// so a full-tree scan ([`ComradeRuntime::media_with`]) can rebuild a
    /// complete [`MediaMessageDto`] without a second round trip per row.
    /// Defaulted so refs written before this field are still readable.
    #[serde(default)]
    event_id: String,
    url: String,
    /// Hex pubkey of the other party (recipient if outgoing, sender if incoming).
    peer_pubkey: String,
    mime_type: String,
    caption: String,
    size: u64,
    /// SHA-256 of the *ciphertext* blob (NIP-94 `x`), verified before decrypt.
    /// Defaulted so refs written before this field are still readable.
    #[serde(default)]
    sha256_hex: String,
    outgoing: bool,
    created_at: u64,
}

/// The private envelope carried inside the E2E DM that points at the blob.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct MediaEnvelope {
    /// Format marker / version; must equal 1.
    comrade_media: u8,
    event_id: String,
    url: String,
    mime: String,
    caption: String,
    size: u64,
    /// SHA-256 of the ciphertext (NIP-94 `x`) so the recipient can fail fast on
    /// a wrong/tampered blob. Defaulted for envelopes sent before this field.
    #[serde(default)]
    sha256_hex: String,
}

/// Detect and parse a Comrade media envelope out of a decrypted DM body.
fn parse_media_envelope(content: &str) -> Option<MediaEnvelope> {
    let env: MediaEnvelope = serde_json::from_str(content).ok()?;
    (env.comrade_media == 1).then_some(env)
}

/// Whether a queued outbox payload is a media reference rather than chat text.
///
/// The flush loop needs this: a text message that finally reaches a relay gets
/// its stored row re-keyed to the relay's event id, but a media envelope has no
/// stored row — re-keying one would *create* a text message whose body is the
/// raw envelope JSON, which would then render as a chat bubble and as the chat
/// list's preview. See [`RuntimeHandles::flush_outbox`].
fn is_media_envelope(content: &str) -> bool {
    parse_media_envelope(content).is_some()
}

/// Longest MIME type accepted from a caller or a peer. Real types are short
/// (`application/vnd.openxmlformats-officedocument.presentationml.slide` is 65);
/// this bounds what a peer can make us persist and render.
const MAX_MIME_LEN: usize = 128;

/// Longest attachment caption we send or store. A caption is free text chosen
/// by the sender and rendered verbatim by every frontend, so it is bounded on
/// both the way out and the way in.
const MAX_CAPTION_LEN: usize = 512;

/// Fallback for an attachment whose MIME type we could not use.
const DEFAULT_MIME: &str = "application/octet-stream";

/// Validate and normalise a caller-supplied MIME type.
///
/// MIME types are case-insensitive (RFC 2045 §5.1), so this lowercases them —
/// otherwise `IMAGE/PNG` would miss every `starts_with("image/")` test a
/// frontend makes and a photo would render as an unopenable file. Anything
/// blank, oversized, or carrying control characters / newlines is rejected
/// rather than quietly patched: it is a caller bug, and a header-shaped value
/// has no business reaching an HTTP `Content-Type`.
fn validate_mime_type(mime: &str) -> Result<String, UiError> {
    let trimmed = mime.trim();
    if trimmed.is_empty() {
        return Err(UiError::Engine("attachment has no MIME type".into()));
    }
    if trimmed.len() > MAX_MIME_LEN {
        return Err(UiError::Engine(format!(
            "MIME type is {} characters; the limit is {MAX_MIME_LEN}",
            trimmed.len()
        )));
    }
    if trimmed.chars().any(|c| c.is_control() || c.is_whitespace()) {
        return Err(UiError::Engine(
            "MIME type contains control characters".into(),
        ));
    }
    if !trimmed.contains('/') {
        return Err(UiError::Engine(format!("malformed MIME type: {trimmed}")));
    }
    Ok(trimmed.to_ascii_lowercase())
}

/// Make a peer-supplied string safe to persist and render: strip control
/// characters (a caption is one line of text in every frontend, and an embedded
/// newline or `\r` would let a peer forge extra UI lines) and truncate to `max`
/// on a character boundary.
fn sanitise_untrusted_text(text: &str, max: usize) -> String {
    text.chars()
        .filter(|c| !c.is_control())
        .take(max)
        .collect::<String>()
        .trim()
        .to_string()
}

/// The chat-list / message-request line for a conversation whose newest item is
/// an attachment. One helper so every surface says the same thing.
fn attachment_preview(caption: &str) -> String {
    let caption = caption.trim();
    if caption.is_empty() {
        "📎 Attachment".to_string()
    } else {
        format!("📎 {caption}")
    }
}

/// The newest attachment in one conversation, reduced to what a chat-list row
/// needs. Built by [`newest_media_by_peer`].
struct MediaSummary {
    created_at: u64,
    preview: String,
    outgoing: bool,
}

/// Newest attachment per peer (keyed by npub), for the chat list and the
/// message-request inbox.
///
/// Media references are *not* stored as messages — the envelope that carries
/// them is consumed by `dispatch_incoming_dm` and never persisted as chat text
/// (otherwise every attachment would also appear as a bubble full of JSON). So
/// any surface that answers "what is the newest thing in this thread?" has to
/// read this tree too, or a photo-only conversation is invisible and a thread
/// whose last item is a photo shows a stale text preview.
fn newest_media_by_peer(
    store: &comrade_storage::EncryptedStore,
) -> Result<std::collections::HashMap<String, MediaSummary>, UiError> {
    let mut newest: std::collections::HashMap<String, MediaSummary> =
        std::collections::HashMap::new();
    for reff in store
        .values::<MediaRef>(MEDIA_REFS_TREE)
        .map_err(|e| UiError::Storage(e.to_string()))?
    {
        let peer = to_npub(&reff.peer_pubkey);
        if newest
            .get(&peer)
            .is_some_and(|existing| existing.created_at >= reff.created_at)
        {
            continue;
        }
        newest.insert(
            peer,
            MediaSummary {
                created_at: reff.created_at,
                preview: attachment_preview(&reff.caption),
                outgoing: reff.outgoing,
            },
        );
    }
    Ok(newest)
}

/// Parse an npub (bech32) or hex public key.
fn parse_pubkey(s: &str) -> Result<PublicKey, UiError> {
    PublicKey::parse(s).map_err(|e| UiError::Engine(format!("invalid pubkey: {e}")))
}

/// Normalise a hex or bech32 public key to a canonical bech32 `npub` for the
/// frontend. Both the incoming and outgoing sides emit the same form, so the UI
/// can key conversations (and the couple panel, which is keyed by the pasted
/// npub) consistently. Falls back to the input unchanged if it cannot be parsed.
fn to_npub(pubkey: &str) -> String {
    PublicKey::parse(pubkey)
        .ok()
        .and_then(|pk| pk.to_bech32().ok())
        .unwrap_or_else(|| pubkey.to_string())
}

/// The local user's profile: the unforgeable identity (npub) plus the chosen
/// display handle. The handle is an alias, never an identifier — see
/// [`ComradeRuntime::set_username`] for the trust model.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct ProfileDto {
    pub npub: String,
    pub username: Option<String>,
}

/// A profile discovered via relay search. `npub` is the identity; `name` is a
/// self-declared, non-unique handle — the UI must always show both.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct FoundProfileDto {
    pub npub: String,
    pub name: Option<String>,
    pub about: Option<String>,
}

/// A saved contact: an npub pinned on first add (trust-on-first-use) with a
/// local alias. A different key later claiming the same handle can never
/// silently replace this entry — contacts are keyed by npub, not by name.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct ContactDto {
    pub npub: String,
    /// The *user-chosen* local alias (petname). Empty = none set.
    pub alias: String,
    /// The peer's own published @handle, from the local profile cache.
    /// Display precedence is alias → name → key; never trust name alone.
    pub name: Option<String>,
    /// Whether the user chose this contact as a comrade — see
    /// [`ComradeRuntime::set_comrade`].
    pub comrade: bool,
}

/// One entry of the chat list: a peer plus the newest message in the thread.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct ConversationDto {
    /// Peer npub (canonical bech32) — the conversation key.
    pub peer: String,
    /// Saved contact alias for the peer, when one exists (user-chosen).
    pub alias: Option<String>,
    /// The peer's own published @handle, from the local profile cache.
    pub peer_name: Option<String>,
    pub last_message: String,
    pub last_at: u64,
    pub last_outgoing: bool,
    /// Whether this peer is one of the user's comrades.
    pub comrade: bool,
    /// Whether that comrade is online *right now* — always recomputed against
    /// the clock, never a stored flag (see [`PresenceDto::online`]). Always
    /// `false` for a peer who isn't a comrade: presence only flows between
    /// comrades, so there is nothing to know.
    pub online: bool,
}

/// A comrade: a contact the user chose to exchange presence with, plus what
/// their last beacon said.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct ComradeDto {
    pub npub: String,
    /// The user-chosen local alias (petname). Empty = none set.
    pub alias: String,
    /// The peer's own published @handle, from the local profile cache.
    pub name: Option<String>,
    /// Live presence, recomputed against the current clock.
    pub online: bool,
    /// Send time (unix seconds) of their last beacon; `0` if none ever
    /// arrived — i.e. they haven't marked us back (or haven't been online
    /// since we marked them).
    pub last_seen_at: u64,
    /// Whether they have marked *us* as their comrade. `false` means we will
    /// never see them online, however long we wait — the UI must say so
    /// rather than showing a permanently grey dot with no explanation.
    pub peer_marked_us: bool,
}

/// A single peer's presence, for a chat header or a contact row.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct PresenceDto {
    pub peer: String,
    /// Whether the peer's last claim is still live at this instant. Computed
    /// on every read from `expires_at`, so a stored "online" row can never
    /// outlive the claim that produced it (a phone that dies sends no
    /// goodbye).
    pub online: bool,
    /// Send time (unix seconds) of the last beacon received from this peer.
    pub last_seen_at: u64,
    /// Whether they have marked us as their comrade (any beacon proves it).
    pub peer_marked_us: bool,
}

/// One device-local counter, as a diagnostics screen sees it.
///
/// Deliberately just a name and a number: the metrics layer has no way to
/// attach a peer, a message id, or a timestamp, so a snapshot cannot become a
/// record of who someone talked to and when. See
/// [`ComradeRuntime::metrics_snapshot`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct MetricDto {
    /// Stable dotted key, e.g. `outbox.queued`.
    pub key: String,
    pub value: u64,
}

/// Locally cached snapshot of a peer's published Kind-0 profile. `name` is a
/// self-declared, non-unique handle — a display aid, never an identifier.
/// Every field defaults so rows written by older builds keep deserialising
/// when the record grows (e.g. the planned avatar field).
#[derive(Debug, Clone, Serialize, Deserialize)]
struct PeerProfileRecord {
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    about: Option<String>,
    /// When this record was last written (unix seconds) — drives the TTL.
    #[serde(default)]
    updated_at: u64,
}

/// A private journal entry as the frontend sees it. Journal entries are
/// **strictly local**: they are never published to a relay or any network —
/// the only copy lives sealed inside the encrypted store.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct JournalEntryDto {
    pub id: String,
    pub text: String,
    /// Optional self-reported mood marker (an emoji or short tag).
    pub mood: Option<String>,
    pub created_at: u64,
}

impl From<comrade_storage::JournalEntry> for JournalEntryDto {
    fn from(e: comrade_storage::JournalEntry) -> Self {
        Self {
            id: e.id,
            text: e.text,
            mood: e.mood,
            created_at: e.created_at,
        }
    }
}

/// One turn of the Tara reflective-companion thread as the frontend sees it.
/// Like the journal, the thread is **strictly local**: no relay, no network —
/// the only copy lives sealed inside the encrypted store.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct TaraMessageDto {
    pub id: String,
    pub text: String,
    /// `true` for Tara's replies, `false` for the user's messages.
    pub from_tara: bool,
    /// Whether this turn tripped the distress detector — the frontend must
    /// surface the crisis resources alongside it (AUDIT §8 honesty gate).
    pub crisis: bool,
    pub created_at: u64,
}

impl From<comrade_storage::TaraMessage> for TaraMessageDto {
    fn from(m: comrade_storage::TaraMessage) -> Self {
        Self {
            id: m.id,
            text: m.text,
            from_tara: m.from_tara,
            crisis: m.crisis,
            created_at: m.created_at,
        }
    }
}

/// One day's usage rollup as the frontend sees it (wellbeing pillar #5).
/// **Strictly local**, exactly like [`JournalEntryDto`] — and only ever a
/// rollup: no app names, no per-app timings, no event stream. See
/// `docs/ATTENTION.md`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct AttentionDayDto {
    /// Local calendar date, `YYYY-MM-DD`.
    pub date: String,
    pub screen_minutes: u32,
    pub pickups: u32,
    /// Minutes in the apps the *user* tagged as their own scroll traps.
    pub doom_minutes: u32,
}

impl From<comrade_storage::AttentionDay> for AttentionDayDto {
    fn from(d: comrade_storage::AttentionDay) -> Self {
        Self {
            date: d.date,
            screen_minutes: d.screen_minutes,
            pickups: d.pickups,
            doom_minutes: d.doom_minutes,
        }
    }
}

/// Today's usage against the user's **own** recent medians — the only
/// comparison this app makes. Never a normative target, never another user
/// (`docs/ATTENTION.md` gate 1); `sample_days` lets the UI say how thin the
/// baseline is instead of calling one prior day "your usual".
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct AttentionSummaryDto {
    /// Today's rollup, or `None` if nothing has been recorded today.
    pub today: Option<AttentionDayDto>,
    pub median_screen_minutes: u32,
    pub median_doom_minutes: u32,
    pub median_pickups: u32,
    /// How many prior days (0–7) the medians are drawn from.
    pub sample_days: u32,
}

/// One focus session as the frontend sees it. Strictly local.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct FocusSessionDto {
    pub id: String,
    /// What the user said they'd give the time to (may be empty).
    pub intent: String,
    pub planned_minutes: u32,
    pub started_at: u64,
    pub ended_at: Option<u64>,
    /// `completed` / `abandoned` / `lapsed`; `None` while still running.
    pub outcome: Option<String>,
    /// Seconds left against the plan — 0 for a finished session.
    pub remaining_secs: u64,
}

/// The saved long-read, already split into chapter-sized chunks, plus where
/// the reader had got to. Chunking is lossless by test
/// (`comrade_core::attention::chunk_reading`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct ReadingDto {
    pub title: String,
    pub chunks: Vec<String>,
    /// Index into `chunks`, always in range (a stored position past the end
    /// after an edit is clamped rather than trusted).
    pub position: u32,
}

/// A crisis helpline surfaced when a Tara message carries distress cues.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct CrisisResourceDto {
    pub name: String,
    pub contact: String,
    pub note: String,
}

/// A single direct message in a conversation, from the offline history.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct MessageDto {
    pub id: String,
    /// Peer npub the thread is keyed by (sender if incoming, recipient if outgoing).
    pub peer: String,
    pub content: String,
    pub created_at: u64,
    pub outgoing: bool,
    /// Delivery status of an outgoing message: `sent` / `delivered` / `read`.
    /// `None` for incoming messages (no ticks shown on the receiver's side).
    pub status: Option<String>,
    /// Event id (hex) this message replies to, if any.
    pub reply_to: Option<String>,
}

/// Live connectivity status of the off-grid Saathi mesh (mDNS discovery +
/// Gossipsub), for a persistent UI indicator — the one signal that still works
/// with zero cellular or relay reachability.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct MeshStatusDto {
    /// Whether the mesh engine is running at all (the workspace is `OffGridTravel`).
    pub active: bool,
    /// Peers currently reachable over the local network via mDNS. `u64`, not
    /// `SaathiEngine::peer_count`'s native `usize` — uniffi has no FFI-safe
    /// representation for a platform-width integer.
    pub peer_count: u64,
}

/// A push event emitted by the background Tokio loops and forwarded across the
/// webview boundary (`window.emit`) or delivered to Android through a uniffi
/// callback interface (see `comrade_jni::BridgeEventListener`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Enum)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum BridgeEvent {
    /// A new public Chitthi (Kind-1) arrived on the Sabha timeline.
    IncomingChitthi(ChitthiDto),
    /// A new encrypted DM (Kind-4) was decrypted in the Vault inbox — from an
    /// already-accepted conversation.
    IncomingDirectMessage(DirectMessageDto),
    /// A new encrypted-media reference (NIP-94) arrived over the DM channel.
    IncomingMedia(MediaMessageDto),
    /// A call-signaling payload (offer/answer/ICE/hangup) arrived for the
    /// frontend's WebRTC layer.
    IncomingCallSignal(CallSignalDto),
    /// A stranger (not yet accepted) sent a DM — surfaced as a message request,
    /// not a chat. Accepting it moves the conversation into the chat list.
    IncomingMessageRequest(MessageRequestDto),
    /// A delivered/read receipt advanced the status of one or more of our
    /// outgoing messages in `peer`'s thread.
    MessageStatus {
        peer: String,
        message_ids: Vec<String>,
        status: String,
    },
    /// A peer shared (or updated) their display handle — e.g. by accepting our
    /// message request. The chat list should re-title their conversation.
    PeerProfileUpdated { peer: String, name: Option<String> },
    /// A comrade came online (or went offline / aged out). Emitted only for
    /// peers the user marked as comrades, and only on an actual transition —
    /// a heartbeat from someone already known to be online is not news. The
    /// `online == true` edge is what a frontend turns into "they're around".
    ComradePresence {
        peer: String,
        /// Display name at the time of the event (alias → published handle),
        /// so a notification can be raised without a store round-trip.
        name: Option<String>,
        online: bool,
        /// Send time of the beacon (unix seconds) for the `online` edge; the
        /// moment the claim lapsed for an aged-out `offline` edge.
        at: u64,
    },
    /// A comrade wrote something for us and did not send it — see
    /// [`comrade_core::nudge`]. Emitted once per hesitation, only for a peer
    /// the user marked as a comrade, and only while the nudge is still fresh:
    /// what a frontend turns into "they're around, and they might need you".
    ///
    /// Carries no more than [`Self::ComradePresence`] does, because the wire
    /// envelope carries no more than that either — never the draft, its
    /// length, or how the writing ended.
    ComradeNudge {
        peer: String,
        /// Display name at the time of the event (alias → published handle),
        /// so a notification can be raised without a store round-trip.
        name: Option<String>,
    },
    /// The off-grid mesh's connectivity changed: it started/stopped, or a peer
    /// joined/left via mDNS. Drives the persistent local-mesh status indicator.
    MeshStatusChanged(MeshStatusDto),
    /// The Sakha/Sakhi shared ledger changed — a partner's entry merged in
    /// over the sync channel. Carries the fresh, fully-merged ledger text.
    LedgerUpdated { ledger: String },
}

// ── Runtime ───────────────────────────────────────────────────────────────────

/// The live IPC runtime context. Wrap in `Arc<RwLock<ComradeRuntime>>` and hand
/// to Tauri's managed state / the JNI global so command handlers can reach the
/// core systems thread-safely.
pub struct ComradeRuntime {
    ui: UiService,
    sabha: Option<Arc<SabhaEngine>>,
    vault: Option<Arc<VaultEngine>>,
    sakha: Option<Arc<SakhaEngine>>,
    /// The off-grid mesh engine — running iff the active workspace is
    /// `OffGridTravel` (see [`Self::sync_saathi_lifecycle`]). Unlike the Nostr
    /// engines above, it is started and stopped on the fly rather than built
    /// once at unlock, since mDNS/Gossipsub only make sense while off-grid.
    saathi: Option<Arc<SaathiEngine>>,
    events: broadcast::Sender<BridgeEvent>,
    /// The separate, small-capacity, deliberately-lossy bus for
    /// `IncomingChitthi` only — see [`FEED_EVENT_BUS_CAPACITY`] and
    /// [`Self::subscribe_feed_events`].
    feed_events: broadcast::Sender<BridgeEvent>,
    /// Guards [`spawn_event_loops`] against re-spawning the feed/DM tasks if it
    /// is called more than once. [`spawn_event_loops`]: ComradeRuntime::spawn_event_loops
    loops_spawned: bool,
    /// Guards [`spawn_sakha_sync_loop`] the same way `loops_spawned` guards
    /// the feed/DM loops. [`spawn_sakha_sync_loop`]: ComradeRuntime::spawn_sakha_sync_loop
    sakha_sync_spawned: bool,
    /// The one live profile-publish retry task. Replaced (old one aborted)
    /// whenever the handle changes, so a stale retry loop can never republish
    /// an old name over a new one.
    publish_task: Option<tokio::task::JoinHandle<()>>,
    /// The Sabha feed-subscription task spawned by [`Self::spawn_event_loops`].
    /// Tracked (unlike a bare `tokio::spawn`) so [`Self::lock_vault`] can abort
    /// it — dropping the `Arc<SabhaEngine>` alone would not stop it, since the
    /// task itself holds its own clone.
    feed_task: Option<tokio::task::JoinHandle<()>>,
    /// The Vault inbox-subscription task, tracked for the same reason as
    /// [`Self::feed_task`].
    vault_task: Option<tokio::task::JoinHandle<()>>,
    /// The Sakha sync-subscription task, tracked for the same reason as
    /// [`Self::feed_task`].
    sakha_sync_task: Option<tokio::task::JoinHandle<()>>,
    /// The relay set new engines are built against — [`DEFAULT_RELAYS`] in
    /// production ([`Self::new`]), or an explicit override ([`Self::with_relays`])
    /// for an isolated test environment (AUDIT.md COMMS-03).
    relays: Vec<String>,
    /// Bounded LRU of call-envelope wrapper event ids already dispatched onto
    /// the event bus this session — see [`dispatch_incoming_dm`]'s call-signal
    /// branch. Behind an `Arc` so the vault callback (which outlives `&self`
    /// borrows) can hold its own clone.
    call_signal_dedup: Arc<SeenSet>,
    /// Sender outbox: DMs a relay would not accept, retried by the flush loop
    /// until a receipt clears them or the attempt cap drops them. Restored from
    /// the encrypted store on unlock, so a message survives an app kill.
    outbox: Arc<Outbox>,
    /// The periodic outbox-flush task, tracked like [`Self::feed_task`] so
    /// [`Self::lock_vault`] can abort it.
    outbox_task: Option<tokio::task::JoinHandle<()>>,
    /// The comrade-presence heartbeat/expiry loop, tracked for the same
    /// reason as [`Self::feed_task`] — [`Self::lock_vault`] must be able to
    /// stop announcing "I'm online" the moment the user locks up.
    presence_task: Option<tokio::task::JoinHandle<()>>,
    /// Recently ingested (peer, content, transport) triples, so one message
    /// delivered by two routes renders once — see
    /// [`CROSS_TRANSPORT_DEDUP_SECS`].
    transport_dedup: Arc<SeenSet>,
    /// The task that opens sealed frames seen on the local mesh and feeds the
    /// ones addressed to us through the normal DM ingress.
    mesh_dm_task: Option<tokio::task::JoinHandle<()>>,
    /// Which composers hold unsent text, for the "your comrade might need you"
    /// nudge ([`comrade_core::nudge`]). Behind an `Arc` so the presence sweep —
    /// which runs on a detached [`RuntimeHandles`], not on `&self` — watches
    /// the same map the frontends report into.
    draft_watch: Arc<DraftWatch>,
}

impl Default for ComradeRuntime {
    fn default() -> Self {
        Self::new()
    }
}

impl ComradeRuntime {
    pub fn new() -> Self {
        Self::with_relays(DEFAULT_RELAYS.iter().map(|r| r.to_string()).collect())
    }

    /// Build a runtime that connects new engines to `relays` instead of
    /// [`DEFAULT_RELAYS`] — for an isolated test relay (no public-internet
    /// dependency) or a future "selected relays" setting. Production call
    /// sites (`comrade_jni`, the Tauri desktop shell) always use [`Self::new`];
    /// this constructor exists for tests and is not itself exposed over FFI.
    pub fn with_relays(relays: Vec<String>) -> Self {
        let (events, _) = broadcast::channel(EVENT_BUS_CAPACITY);
        let (feed_events, _) = broadcast::channel(FEED_EVENT_BUS_CAPACITY);
        Self {
            ui: UiService::new(),
            sabha: None,
            vault: None,
            sakha: None,
            saathi: None,
            events,
            feed_events,
            loops_spawned: false,
            sakha_sync_spawned: false,
            publish_task: None,
            feed_task: None,
            vault_task: None,
            sakha_sync_task: None,
            relays,
            call_signal_dedup: Arc::new(SeenSet::new(CALL_SIGNAL_DEDUP_CAPACITY)),
            outbox: Arc::new(Outbox::new()),
            outbox_task: None,
            presence_task: None,
            transport_dedup: Arc::new(SeenSet::with_ttl(
                CROSS_TRANSPORT_DEDUP_CAPACITY,
                std::time::Duration::from_secs(CROSS_TRANSPORT_DEDUP_SECS),
            )),
            mesh_dm_task: None,
            draft_watch: Arc::new(DraftWatch::new()),
        }
    }

    /// Abort any in-flight profile-publish retry loop and start one for
    /// `name`. Last spawn wins — the relays only ever see the newest handle.
    fn spawn_profile_publish(&mut self, sabha: Arc<SabhaEngine>, name: String) {
        if let Some(task) = self.publish_task.take() {
            task.abort();
        }
        self.publish_task = Some(tokio::spawn(publish_profile_with_retry(sabha, name)));
    }

    // ── Event bus ──────────────────────────────────────────────────────────

    /// Subscribe to the *critical* push-event stream (everything except
    /// `IncomingChitthi` — see [`Self::subscribe_feed_events`]). The desktop
    /// layer forwards each event to the webview; the JNI layer forwards it to
    /// the registered [`comrade_jni::BridgeEventListener`].
    pub fn subscribe_events(&self) -> broadcast::Receiver<BridgeEvent> {
        self.events.subscribe()
    }

    /// Subscribe to the separate, small-capacity `IncomingChitthi` stream
    /// (AUDIT.md COMMS-04). A public-feed flood drops old, unconsumed
    /// Chitthis from *this* channel under load — it can never crowd out or
    /// delay a call signal, DM, message request, or terminal call event
    /// waiting on [`Self::subscribe_events`], because those live on a wholly
    /// separate channel. A host forwards both streams to the same listener
    /// (two loops feeding one callback, e.g. `comrade_jni::Comrade::set_event_listener`)
    /// — the split is an internal backpressure boundary, not a second API
    /// a caller needs to reason about differently.
    pub fn subscribe_feed_events(&self) -> broadcast::Receiver<BridgeEvent> {
        self.feed_events.subscribe()
    }

    /// A clonable handle to the event bus, for hosts that want to inject events
    /// (e.g. forwarding mesh/Saathi traffic) onto the same stream.
    pub fn event_sender(&self) -> broadcast::Sender<BridgeEvent> {
        self.events.clone()
    }

    // ── Milestone 1 + 4: unlock the vault & start the engines ────────────────

    /// Open the encrypted storage repository with `passphrase`, restore (or
    /// seed) the identity, and construct the core Nostr engines.
    ///
    /// Engine construction is offline — relays are registered but not dialled —
    /// so this never blocks on the network. Call [`spawn_event_loops`] afterward
    /// to connect and begin streaming.
    ///
    /// The Argon2id key stretch + sled open run on Tokio's blocking pool, so a
    /// deliberately slow KDF never stalls a reactor thread that other tasks
    /// (relay loops, other IPC commands) are scheduled on.
    ///
    /// [`spawn_event_loops`]: ComradeRuntime::spawn_event_loops
    pub async fn unlock_vault(
        &mut self,
        path: impl AsRef<std::path::Path>,
        passphrase: &str,
    ) -> Result<IdentityDto, UiError> {
        // Idempotent: a second unlock (both bridges call this at startup) must
        // not rebuild the engines — that would orphan the running ones and,
        // with spawn_event_loops, duplicate the relay connections and event
        // loops. Return the already-loaded identity instead.
        if self.is_vault_unlocked() {
            return self.ui.current_identity().ok_or(UiError::NoIdentity);
        }

        let started = std::time::Instant::now();
        let store = {
            let path = path.as_ref().to_path_buf();
            let passphrase = passphrase.to_string();
            tokio::task::spawn_blocking(move || {
                comrade_storage::EncryptedStore::open(path, &passphrase)
            })
            .await
            .map_err(|e| UiError::Storage(format!("unlock task failed: {e}")))?
            .map_err(|e| UiError::Storage(e.to_string()))?
        };
        let kdf_ms = started.elapsed().as_millis() as u64;
        self.ui.attach_store(store);

        // Mail queued before the last kill goes back into the live outbox, so
        // the flush loop picks it up on this launch.
        if let Some(store) = self.ui.store_ref() {
            if let Ok(Some(snapshot)) = store.get::<OutboxSnapshot>(OUTBOX_TREE, OUTBOX_KEY) {
                let restored = snapshot.queues.values().map(Vec::len).sum::<usize>();
                if restored > 0 {
                    tracing::info!(restored, "restored queued messages from the outbox");
                }
                self.outbox = Arc::new(Outbox::from_snapshot(snapshot));
            }
        }

        // Restore the saved identity, or seed and persist a fresh one so the
        // engines always have keys to sign with.
        let identity = match self.ui.load_identity()? {
            Some(id) => id,
            None => {
                let id = self.ui.generate_identity()?;
                self.ui.save_identity()?;
                id
            }
        };

        let keys = self.ui.identity_keys().ok_or(UiError::NoIdentity)?;
        let relays = self.relays.clone();

        self.sabha = Some(Arc::new(
            SabhaEngine::new_with_relays(&keys, relays.clone())
                .await
                .map_err(|e| UiError::Engine(e.to_string()))?,
        ));
        self.vault = Some(Arc::new(
            VaultEngine::new(&keys, relays)
                .await
                .map_err(|e| UiError::Engine(e.to_string()))?,
        ));
        self.sakha = Some(Arc::new(
            SakhaEngine::new(&keys, vec![])
                .await
                .map_err(|e| UiError::Engine(e.to_string()))?,
        ));
        self.restore_sakha_pairing().await;

        // Local-network delivery is not a mode to opt into: bring the mesh up
        // now that we have keys, so a DM can reach someone on this WiFi even
        // with no internet at all. Best-effort — see `sync_saathi_lifecycle`.
        self.sync_saathi_lifecycle().await;

        // Startup observability: the unlock is the gate every frontend waits
        // on, so record how long its two phases actually took.
        tracing::info!(
            kdf_ms,
            total_ms = started.elapsed().as_millis() as u64,
            "vault unlocked: store opened and engines built"
        );

        Ok(identity)
    }

    /// Whether the vault has been unlocked and the engines built.
    pub fn is_vault_unlocked(&self) -> bool {
        self.sabha.is_some()
    }

    /// Re-lock the vault: abort every background loop, drop the engines, the
    /// open encrypted store, and the in-memory identity — the deliberate,
    /// user-initiated counterpart to what process death does by accident (see
    /// AUDIT.md COMMS-01's security-boundary note on `RelayConnectionService`).
    /// After this, [`Self::is_vault_unlocked`] is `false` and every store/engine
    /// -backed method fails with [`UiError::VaultLocked`]/[`UiError::NoIdentity`]
    /// again, exactly as before the first unlock — calling [`Self::unlock_vault`]
    /// resumes normally (a fresh Sabha/Vault/Sakha build, fresh loops).
    ///
    /// Idempotent: locking an already-locked runtime is a harmless no-op.
    pub async fn lock_vault(&mut self) {
        // Say goodbye to the comrades before the engines go: a locked vault
        // is not online, and leaving them to age the claim out would show a
        // green dot next to a person who deliberately shut the door. The
        // send is detached and holds only the vault engine (never the store,
        // whose redb file lock the teardown below is about to reclaim), so a
        // slow or unreachable relay can never make locking hang.
        self.spawn_farewell_beacons();
        // `abort()` only *requests* cancellation at the task's next await
        // point — it does not synchronously drop the task's captured state.
        // Each of these tasks holds its own `Arc` clone of the store/engines
        // (captured once, before the task loop started, so the callbacks
        // inside it work while `self`'s own fields below are cleared) —
        // notably `EncryptedStore`, whose underlying redb file enforces a
        // single-writer lock. Awaiting the (now-erroring) handle after abort
        // blocks until that drop has actually happened, so a `unlock_vault`
        // immediately following a `lock_vault` never races the old store
        // handle for the same file lock.
        for task in [
            self.publish_task.take(),
            self.feed_task.take(),
            self.vault_task.take(),
            self.sakha_sync_task.take(),
            self.outbox_task.take(),
            self.presence_task.take(),
            self.mesh_dm_task.take(),
        ]
        .into_iter()
        .flatten()
        {
            task.abort();
            let _ = task.await;
        }
        if self.saathi.is_some() {
            self.stop_saathi().await;
        }
        self.sabha = None;
        self.vault = None;
        self.sakha = None;
        self.loops_spawned = false;
        self.sakha_sync_spawned = false;
        // A hesitation belongs to the session it happened in. Locking up is a
        // deliberate exit — the goodbye beacon above has just told the comrades
        // we are gone, and a nudge landing after it would claim the opposite.
        self.draft_watch.clear();
        self.ui.lock();
    }

    // ── Milestone 2: connect & stream into the event bus ─────────────────────

    /// Connect the engines and spawn the background Tokio loops that capture
    /// incoming Chitthis (Kind-1) and encrypted DMs (Kind-4) and push them onto
    /// the event bus. Must be called from within a Tokio runtime context.
    ///
    /// All network and decryption work happens inside these spawned tasks,
    /// keeping the UI thread free (Architecture Quality Gate).
    ///
    /// Idempotent: calling it more than once (e.g. after a repeated unlock) is
    /// a no-op, so the feed/DM loops are spawned at most once per runtime.
    pub fn spawn_event_loops(&mut self) {
        if self.loops_spawned {
            return;
        }
        self.loops_spawned = true;

        // Re-publish the saved @handle on every launch. Kind-0 is replaceable
        // (newest wins) and the publish merges into the currently published
        // profile, so this is idempotent — and it heals identities whose
        // original publish was dropped (offline onboarding, relay hiccup),
        // which otherwise stay undiscoverable forever.
        if let (Some(sabha), Some(name)) = (self.sabha.clone(), self.ui.username()) {
            self.spawn_profile_publish(sabha, name);
        }

        if let Some(sabha) = self.sabha.clone() {
            // The small, deliberately-lossy feed channel (not `self.events`,
            // the critical one) — a public-feed flood must never compete with
            // a call signal or DM for ring-buffer space (AUDIT.md COMMS-04).
            let tx = self.feed_events.clone();
            let spec = self.feed_filter_spec();
            self.feed_task = Some(tokio::spawn(async move {
                sabha.connect().await;
                let cb: ChitthiCallback = Box::new(move |event| {
                    // A send error only means no subscribers are listening yet;
                    // the relay loop must keep running regardless.
                    let _ = tx.send(BridgeEvent::IncomingChitthi(ChitthiDto::from_event(&event)));
                });
                if let Err(e) = sabha.subscribe_chitthi_feed(spec, cb).await {
                    warn!("sabha feed loop ended: {e}");
                }
            }));
        }

        if let Some(vault) = self.vault.clone() {
            let tx = self.events.clone();
            let store = self.ui.store_arc();
            // A clone of the vault handle the callback can use to send back
            // delivered receipts (the callback itself is sync; it spawns).
            let vault_cb = vault.clone();
            let dedup = self.call_signal_dedup.clone();
            let outbox = self.outbox.clone();
            let transport_dedup = self.transport_dedup.clone();
            let mesh = self.mesh_link();
            // Widen the backfill window past the standard gift-wrap skew when
            // this device was last seen longer ago than that (see
            // `VaultEngine::subscribe_inbox_with_callback`'s `since_floor`).
            let since_floor = store.as_ref().and_then(|s| read_watermark(s));
            self.vault_task = Some(tokio::spawn(async move {
                vault.connect().await;
                let cb: VaultCallback = Box::new(move |msg| {
                    let route = DmRoute {
                        label: TRANSPORT_RELAY,
                        dedup: &transport_dedup,
                        mesh: mesh.as_ref(),
                    };
                    dispatch_incoming_dm(
                        &vault_cb,
                        store.as_ref(),
                        &tx,
                        &dedup,
                        &outbox,
                        &route,
                        msg,
                    );
                });
                if let Err(e) = vault.subscribe_inbox_with_callback(cb, since_floor).await {
                    warn!("vault inbox loop ended: {e}");
                }
            }));
        }

        // Retry queued mail on a cadence (bitchat re-sends its outbox on
        // reconnect events; Comrade has no equivalent signal, so this stands in).
        // Spawned unconditionally alongside the vault loop: a flush with nothing
        // queued is one lock acquisition.
        if self.vault.is_some() {
            let handles = self.handles();
            self.outbox_task = Some(tokio::spawn(async move {
                let mut ticker = tokio::time::interval(std::time::Duration::from_secs(
                    OUTBOX_FLUSH_INTERVAL_SECS,
                ));
                // The first tick fires immediately: a launch is exactly when
                // mail queued in a previous session should go out.
                loop {
                    ticker.tick().await;
                    match handles.flush_outbox().await {
                        Ok(0) => {}
                        Ok(sent) => tracing::info!(sent, "outbox flushed"),
                        Err(e) => tracing::debug!("outbox flush skipped: {e}"),
                    }
                }
            }));
        }

        self.spawn_presence_loop();

        // Begin opening the sealed frames the local mesh sees. The engine
        // itself is started by `unlock_vault` (this method is sync); with no
        // engine running this is a no-op.
        self.spawn_mesh_dm_loop();

        // A pairing restored from a previous launch (see `restore_sakha_pairing`,
        // called from `unlock_vault`) should start syncing immediately too —
        // a fresh pairing via `pair_sakha` starts it itself.
        if self.sakha.as_ref().is_some_and(|s| s.is_paired()) {
            self.spawn_sakha_sync_loop();
        }
    }

    /// Start the comrade-presence loop: announce "I'm online" to every
    /// comrade now and every [`PRESENCE_HEARTBEAT_SECS`] after, and on the
    /// same tick age out any comrade whose own claim has lapsed (a phone
    /// that dies sends no goodbye — see [`expire_stale_presence`]).
    ///
    /// Runs only while comrades exist, in the sense that a tick with an
    /// empty comrade list does nothing at all: the feature is invisible and
    /// free until someone opts in.
    fn spawn_presence_loop(&mut self) {
        if self.vault.is_none() {
            return;
        }
        let handles = self.handles();
        let tx = self.events.clone();
        self.presence_task = Some(tokio::spawn(async move {
            let mut ticker =
                tokio::time::interval(std::time::Duration::from_secs(PRESENCE_SWEEP_SECS));
            // The default `Burst` behaviour would fire back-to-back catch-up
            // ticks after a suspended/backgrounded stretch, announcing several
            // times in a row for no benefit.
            ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            // Announcing costs a DM per comrade, so it happens on the
            // heartbeat; expiring is local work over a handful of rows, so it
            // happens on every (shorter) sweep — a dot must not stay green for
            // an extra heartbeat after the claim behind it lapsed. The first
            // tick fires immediately, so unlocking announces right away.
            let announce_every = PRESENCE_HEARTBEAT_SECS.div_ceil(PRESENCE_SWEEP_SECS).max(1);
            let mut tick: u64 = 0;
            loop {
                ticker.tick().await;
                if tick.is_multiple_of(announce_every) {
                    handles.announce_presence(true).await;
                }
                expire_stale_presence(handles.store.as_deref(), &tx);
                // Abandoned drafts ride this sweep rather than a timer of
                // their own: a nudge is a courtesy that costs one DM, and
                // arriving a sweep late only makes it more certain (the
                // writer had that much longer to come back). What it must
                // never do is arrive *early* — see `NUDGE_SETTLE_SECS`.
                handles.nudge_abandoned_drafts(now_secs()).await;
                tick = tick.wrapping_add(1);
            }
        }));
    }

    /// Open the sealed frames the local mesh sees and feed the ones addressed to
    /// us through the **same** ingress path a relay-delivered DM takes — so
    /// message-request gating, persistence, receipts, and dedup behave
    /// identically however a message arrived.
    ///
    /// Every peer on the network sees every frame; almost all of them are for
    /// someone else and are skipped after one HMAC comparison against our
    /// rotating tags. Idempotent: a second call replaces nothing, since
    /// [`Self::lock_vault`] is what tears the task down.
    fn spawn_mesh_dm_loop(&mut self) {
        if self.mesh_dm_task.is_some() {
            return;
        }
        let (Some(engine), Some(vault), Some(keys)) = (
            self.saathi.clone(),
            self.vault.clone(),
            self.ui.identity_keys(),
        ) else {
            return;
        };

        let store = self.ui.store_arc();
        let tx = self.events.clone();
        let call_dedup = self.call_signal_dedup.clone();
        let transport_dedup = self.transport_dedup.clone();
        let outbox = self.outbox.clone();
        let mesh = MeshLink {
            engine: engine.clone(),
            keys: keys.clone(),
        };
        let pay_regex = build_pay_regex().ok();

        self.mesh_dm_task = Some(tokio::spawn(async move {
            while let Some(envelope) = engine.recv_sealed().await {
                let now = now_secs();
                let opened = match open_dm(&keys, &envelope, now) {
                    // Someone else's mail — the overwhelmingly common case.
                    Ok(None) => continue,
                    Ok(Some(opened)) => opened,
                    Err(e) => {
                        tracing::debug!("mesh: a frame addressed to us failed to open: {e}");
                        continue;
                    }
                };

                let sender_hex = opened.sender.to_hex();
                let content = opened.dm.content;
                let upi_intents = pay_regex
                    .as_ref()
                    .map(|re| extract_upi_intents(&content, re))
                    .unwrap_or_default();
                let msg = VaultMessage {
                    event_id: opened.dm.id,
                    sender_pubkey: sender_hex,
                    content,
                    created_at: opened.dm.created_at,
                    upi_intents,
                    reply_to: opened.dm.reply_to,
                };
                let route = DmRoute {
                    label: TRANSPORT_MESH,
                    dedup: &transport_dedup,
                    mesh: Some(&mesh),
                };
                dispatch_incoming_dm(
                    &vault,
                    store.as_ref(),
                    &tx,
                    &call_dedup,
                    &outbox,
                    &route,
                    msg,
                );
            }
            tracing::debug!("mesh: sealed-frame stream ended");
        }));
    }

    /// The public-feed subscription policy (AUDIT.md COMMS-04): self plus
    /// every accepted contact when any are known, else a capped, time-windowed
    /// bootstrap feed for a fresh identity that hasn't added anyone yet — never
    /// the unbounded relay-wide firehose `subscribe_chitthi_feed` used to get.
    fn feed_filter_spec(&self) -> FeedFilterSpec {
        let mut authors: Vec<nostr_sdk::PublicKey> = Vec::new();
        if let Some(id) = self.ui.current_identity() {
            if let Ok(pk) = parse_pubkey(&id.npub) {
                authors.push(pk);
            }
        }
        if let Ok(contacts) = self.list_contacts() {
            authors.extend(contacts.iter().filter_map(|c| parse_pubkey(&c.npub).ok()));
        }
        // More than just self means at least one real contact is followed.
        let scope = if authors.len() > 1 {
            FeedScope::Authors(authors)
        } else {
            FeedScope::BoundedGlobal {
                limit: FEED_BOOTSTRAP_LIMIT,
            }
        };
        FeedFilterSpec {
            scope,
            since_secs: FEED_SINCE_SECS,
        }
    }

    // ── Milestone 1: timeline + broadcast ────────────────────────────────────

    /// Load the Sabha timeline from the encrypted on-disk cache (offline-first).
    pub fn fetch_sabha_timeline(&self) -> Result<Vec<ChitthiDto>, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let feed = store
            .chitthi_cache()
            .map_err(|e| UiError::Storage(e.to_string()))?;
        Ok(feed.iter().map(ChitthiDto::from_cached).collect())
    }

    /// Broadcast a Chitthi to the public relay set, optionally as a NIP-10 reply.
    /// On success the Chitthi is also cached locally for offline rendering.
    /// Returns the new event id (hex).
    ///
    /// Delegates to [`RuntimeHandles::broadcast_chitthi`] — see [`Self::send_dm`].
    pub async fn broadcast_chitthi(
        &self,
        content: &str,
        reply_to: Option<String>,
    ) -> Result<String, UiError> {
        self.handles().broadcast_chitthi(content, reply_to).await
    }

    // ── Direct messages (Telegram-like chat flow) ────────────────────────────

    /// Send an end-to-end encrypted DM to `target` (npub or hex pubkey) and
    /// persist it to the offline history. Returns the stored message DTO.
    ///
    /// Delegates to [`RuntimeHandles::send_dm`] after a cheap, synchronous
    /// handle snapshot (AUDIT P2: never hold the runtime lock across the
    /// relay round-trip inside — see [`Self::handles`]).
    pub async fn send_dm(&self, target: &str, content: &str) -> Result<MessageDto, UiError> {
        self.handles().send_dm(target, content).await
    }

    /// Send an E2E DM, optionally as a reply to a prior message (`reply_to` is
    /// the replied message's event id, hex). Sending to someone accepts the
    /// conversation on our side and shares our @handle once (so they can title
    /// the chat) — the sender-side half of "username shared once engaged".
    ///
    /// Delegates to [`RuntimeHandles::send_dm_reply`] — see [`Self::send_dm`].
    pub async fn send_dm_reply(
        &self,
        target: &str,
        content: &str,
        reply_to: Option<&str>,
    ) -> Result<MessageDto, UiError> {
        self.handles()
            .send_dm_reply(target, content, reply_to)
            .await
    }

    /// Retry queued mail now. Delegates to [`RuntimeHandles::flush_outbox`] —
    /// see [`Self::send_dm`]. Returns how many messages a relay accepted.
    ///
    /// A host that learns connectivity came back (Android's network callback,
    /// the desktop resuming from sleep) should call this instead of waiting for
    /// the periodic loop.
    pub async fn flush_outbox(&self) -> Result<usize, UiError> {
        self.handles().flush_outbox().await
    }

    /// How many messages are waiting for a relay that will take them.
    pub fn outbox_pending(&self) -> usize {
        self.outbox.len()
    }

    /// Broadcast a Chitthi under a throwaway or scoped persona instead of this
    /// device's identity. Delegates to
    /// [`RuntimeHandles::broadcast_anonymous_chitthi`] — see [`Self::send_dm`].
    pub async fn broadcast_anonymous_chitthi(
        &self,
        content: &str,
        scope: Option<String>,
    ) -> Result<String, UiError> {
        self.handles()
            .broadcast_anonymous_chitthi(content, scope.as_deref())
            .await
    }

    /// Device-local delivery counters — bare tallies with no identities, ids,
    /// content, or timestamps, so they are safe to render on a diagnostics
    /// screen or paste into a bug report.
    ///
    /// Adopted from bitchat's `StoreAndForwardMetrics`. Nothing here leaves the
    /// device: there is no exporter and no per-peer dimension to re-identify.
    pub fn metrics_snapshot(&self) -> Vec<MetricDto> {
        core_metrics::snapshot()
            .into_iter()
            .map(|(key, value)| MetricDto { key, value })
            .collect()
    }

    /// **Panic wipe** — destroy everything this device holds, then re-lock.
    ///
    /// Adopted from bitchat, whose privacy assessment names the threat plainly:
    /// for many of the people this is built for, the realistic compromise is a
    /// phone taken, often unlocked under duress. A journal of someone's worst
    /// weeks should not still be there at that point.
    ///
    /// In order: every stored value goes (identity keys included — see
    /// `comrade_storage::EncryptedStore::panic_wipe`, which enumerates the
    /// database's actual tables so a tree added later cannot be missed), then
    /// the in-memory queues, dedup sets, and counters, then the engines and
    /// identity via [`Self::lock_vault`]. Afterwards the runtime is exactly as
    /// it was before its first unlock, and a fresh unlock starts onboarding
    /// over.
    ///
    /// What it deliberately does not do is pretend to be a duress feature: it
    /// wipes, it does not hide, and it needs the app open and unlocked to run.
    pub async fn panic_wipe(&mut self) -> Result<(), UiError> {
        let store = self.ui.store_arc().ok_or(UiError::VaultLocked)?;
        // redb commits are fsync'd, so this is real I/O — keep it off the
        // reactor thread.
        tokio::task::spawn_blocking(move || store.panic_wipe())
            .await
            .map_err(|e| UiError::Storage(format!("wipe task failed: {e}")))?
            .map_err(|e| UiError::Storage(e.to_string()))?;

        self.outbox.clear();
        self.call_signal_dedup.clear();
        core_metrics::reset();
        self.lock_vault().await;
        tracing::warn!("panic wipe complete: local state destroyed and vault locked");
        Ok(())
    }

    /// The chat list: one entry per **accepted** peer, newest thread first, with
    /// saved contact aliases joined in. Pending message requests and blocked
    /// peers are excluded (see [`Self::message_requests`]).
    pub fn conversations(&self) -> Result<Vec<ConversationDto>, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let contacts = store
            .list_contacts()
            .map_err(|e| UiError::Storage(e.to_string()))?;
        let comrades: std::collections::HashSet<String> = contacts
            .iter()
            .filter(|c| c.comrade)
            .map(|c| c.npub.clone())
            .collect();
        let aliases: std::collections::HashMap<String, String> =
            contacts.into_iter().map(|c| (c.npub, c.petname)).collect();
        let now = now_secs();
        // Peers gated out of the chat list: pending requests + blocked. A peer
        // with no meta at all (e.g. history from before this feature) is treated
        // as an ordinary accepted conversation.
        let gated = self.gated_peers(store)?;

        // `(created_at, preview, outgoing)` for the newest item in each thread —
        // text or attachment, whichever is newer.
        let mut newest: std::collections::HashMap<String, (u64, String, bool)> =
            std::collections::HashMap::new();
        let mut consider = |peer: String, at: u64, preview: String, outgoing: bool| {
            if gated.contains(&peer) {
                return;
            }
            match newest.get(&peer) {
                Some((existing_at, _, _)) if *existing_at >= at => {}
                _ => {
                    newest.insert(peer, (at, preview, outgoing));
                }
            }
        };
        for msg in store
            .all_messages()
            .map_err(|e| UiError::Storage(e.to_string()))?
        {
            consider(msg.peer_npub, msg.created_at, msg.content, msg.outgoing);
        }
        // An attachment is a thread's newest item as often as a text message is,
        // and a thread that only ever exchanged photos has no messages at all.
        for (peer, media) in newest_media_by_peer(store)? {
            consider(peer, media.created_at, media.preview, media.outgoing);
        }

        let mut list: Vec<ConversationDto> = newest
            .into_iter()
            .map(|(peer, (last_at, last_message, last_outgoing))| {
                let comrade = comrades.contains(&peer);
                ConversationDto {
                    alias: aliases.get(&peer).and_then(|a| user_alias(a, &peer)),
                    peer_name: cached_peer_name(store, &peer),
                    // Presence only exists between comrades — never imply
                    // knowledge about anyone else's whereabouts.
                    online: comrade && peer_is_online(store, &peer, now),
                    comrade,
                    peer,
                    last_message,
                    last_at,
                    last_outgoing,
                }
            })
            .collect();
        list.sort_by_key(|c| std::cmp::Reverse(c.last_at));
        Ok(list)
    }

    /// Full offline message history with `peer` (npub or hex), oldest first —
    /// carrying each message's delivery status and reply target. Not gated, so
    /// a pending request's thread is viewable before it is accepted.
    pub fn messages_with(&self, peer: &str) -> Result<Vec<MessageDto>, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let peer = to_npub(peer);
        let mut msgs: Vec<MessageDto> = store
            .messages_with(&peer)
            .map_err(|e| UiError::Storage(e.to_string()))?
            .into_iter()
            .map(|m| MessageDto {
                id: m.id,
                peer: m.peer_npub,
                content: m.content,
                created_at: m.created_at,
                status: if m.outgoing {
                    Some(m.status.unwrap_or_else(|| "sent".into()))
                } else {
                    None
                },
                reply_to: m.reply_to,
                outgoing: m.outgoing,
            })
            .collect();
        msgs.sort_by_key(|m| m.created_at);
        Ok(msgs)
    }

    // ── Message requests (gate strangers; accept/block; profile on accept) ────

    /// Peers to hide from the chat list: those with a `pending` or `blocked`
    /// conversation gate. A peer with no gate record is shown (accepted).
    fn gated_peers(
        &self,
        store: &comrade_storage::EncryptedStore,
    ) -> Result<std::collections::HashSet<String>, UiError> {
        Ok(store
            .list_conversation_meta()
            .map_err(|e| UiError::Storage(e.to_string()))?
            .into_iter()
            .filter(|m| m.state == STATE_PENDING || m.state == STATE_BLOCKED)
            .map(|m| m.peer_npub)
            .collect())
    }

    /// Pending message requests — strangers' DMs awaiting accept/block, newest
    /// first, with a preview of their latest message.
    pub fn message_requests(&self) -> Result<Vec<MessageRequestDto>, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let pending: std::collections::HashSet<String> = store
            .list_conversation_meta()
            .map_err(|e| UiError::Storage(e.to_string()))?
            .into_iter()
            .filter(|m| m.state == STATE_PENDING)
            .map(|m| m.peer_npub)
            .collect();
        if pending.is_empty() {
            return Ok(vec![]);
        }
        // Newest item per pending peer: `(created_at, preview)`.
        let mut newest: std::collections::HashMap<String, (u64, String)> =
            std::collections::HashMap::new();
        let mut consider = |peer: String, at: u64, preview: String| {
            if !pending.contains(&peer) {
                return;
            }
            match newest.get(&peer) {
                Some((existing_at, _)) if *existing_at >= at => {}
                _ => {
                    newest.insert(peer, (at, preview));
                }
            }
        };
        for msg in store
            .all_messages()
            .map_err(|e| UiError::Storage(e.to_string()))?
        {
            consider(msg.peer_npub, msg.created_at, msg.content);
        }
        // A stranger whose first contact was an attachment is still a request
        // with something to preview — and, before this, a request row with an
        // empty line (the gated branch of `dispatch_incoming_dm` persists the
        // media ref but no message).
        for (peer, media) in newest_media_by_peer(store)? {
            consider(peer, media.created_at, media.preview);
        }
        let mut list: Vec<MessageRequestDto> = newest
            .into_iter()
            .map(|(peer, (last_at, last_message))| MessageRequestDto {
                peer,
                last_message,
                last_at,
            })
            .collect();
        list.sort_by_key(|r| std::cmp::Reverse(r.last_at));
        Ok(list)
    }

    /// Accept a pending message request: mark the conversation accepted, share
    /// our @handle with the peer (this is the moment "the username is shared"),
    /// and acknowledge their messages as read. The conversation now appears in
    /// the chat list. Idempotent for an already-accepted peer.
    pub fn accept_request(&self, peer: &str) -> Result<(), UiError> {
        let peer_pk = parse_pubkey(peer)?;
        let peer_npub = peer_pk
            .to_bech32()
            .map_err(|e| UiError::Engine(e.to_string()))?;
        if self.ui.store_ref().is_none() {
            return Err(UiError::VaultLocked);
        }
        self.mark_accepted_and_share_profile(&peer_npub, &peer_pk);
        // Their messages are now read — acknowledge them.
        let ids = self.incoming_ids(&peer_npub);
        self.spawn_receipt(&peer_pk, ReceiptKind::Read, ids);
        Ok(())
    }

    /// Block a peer: hide them from the chat list and drop their future DMs in
    /// the inbox loop. The message history is left intact locally.
    pub fn block_conversation(&self, peer: &str) -> Result<(), UiError> {
        let peer_pk = parse_pubkey(peer)?;
        let peer_npub = peer_pk
            .to_bech32()
            .map_err(|e| UiError::Engine(e.to_string()))?;
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        // Carry the read position across the block. Nothing reads it while the
        // thread is hidden, but throwing it away would silently restart the
        // history of a peer who is later talked to again.
        let last_read_at = store.read_position(&peer_npub).unwrap_or(0);
        let meta = comrade_storage::ConversationMeta {
            peer_npub,
            state: STATE_BLOCKED.to_string(),
            profile_shared: false,
            last_read_at,
            updated_at: now_secs(),
        };
        store
            .set_conversation_meta(&meta)
            .and_then(|()| store.flush())
            .map_err(|e| UiError::Storage(e.to_string()))
    }

    /// Mark a conversation read: send a read receipt covering the peer's
    /// incoming messages, record how far the user has now read, and return the
    /// position they had reached *before* this call.
    ///
    /// The frontend uses that previous position to open the thread at the first
    /// unread message rather than at the newest one (Telegram's behaviour), and
    /// to draw the "unread messages" divider. It is returned from this call
    /// rather than exposed as a separate getter so there is no window in which
    /// the caller's own mark-read has already overwritten the answer it is
    /// about to position on.
    ///
    /// Returns 0 when the thread has never been opened, which the UI reads as
    /// "no divider, open at the newest message" — for a first visit there is no
    /// meaningful "where I left off".
    ///
    /// Accepted conversations only: we never ack a pending request. A pending
    /// thread also keeps its read position untouched, because acking or
    /// recording it would leak that we read it before deciding to accept.
    pub fn mark_conversation_read(&self, peer: &str) -> Result<u64, UiError> {
        let peer_pk = parse_pubkey(peer)?;
        let peer_npub = peer_pk
            .to_bech32()
            .map_err(|e| UiError::Engine(e.to_string()))?;
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        // Only ack accepted conversations — acking a pending request would leak
        // that we saw it before deciding to accept.
        let accepted = store
            .get_conversation_meta(&peer_npub)
            .map_err(|e| UiError::Storage(e.to_string()))?
            .map(|m| m.state == STATE_ACCEPTED)
            .unwrap_or(true); // no gate record ⇒ ordinary conversation
        if !accepted {
            return Ok(0);
        }
        // Everything currently in the thread is what the reader is being shown,
        // so the newest stored message is the new watermark. Taken from the
        // store rather than the clock: a message that arrives one second after
        // this call must stay unread, and a clock-based mark would swallow it.
        let newest = store
            .messages_with(&peer_npub)
            .map(|msgs| msgs.iter().map(|m| m.created_at).max().unwrap_or(0))
            .unwrap_or(0);
        let previous = store
            .advance_read_position(&peer_npub, newest, now_secs(), STATE_ACCEPTED)
            .map_err(|e| UiError::Storage(e.to_string()))?;
        let ids = self.incoming_ids(&peer_npub);
        self.spawn_receipt(&peer_pk, ReceiptKind::Read, ids);
        Ok(previous)
    }

    /// Event ids of the peer's incoming (received) messages in this thread.
    fn incoming_ids(&self, peer_npub: &str) -> Vec<String> {
        self.ui
            .store_ref()
            .and_then(|s| s.messages_with(peer_npub).ok())
            .map(|msgs| {
                msgs.into_iter()
                    .filter(|m| !m.outgoing)
                    .map(|m| m.id)
                    .collect()
            })
            .unwrap_or_default()
    }

    /// Record the conversation as accepted and, once, share our @handle with the
    /// peer over the encrypted channel. See free function
    /// [`share_profile_on_accept`] for the implementation (shared with
    /// [`RuntimeHandles::send_dm_reply`]).
    fn mark_accepted_and_share_profile(&self, peer_npub: &str, peer: &PublicKey) {
        share_profile_on_accept(
            self.ui.store_arc(),
            self.vault.clone(),
            self.ui.username(),
            peer_npub,
            peer,
        );
    }

    /// Fire-and-forget a final "offline" beacon to every comrade, for a
    /// deliberate exit (vault lock). Reads the comrade list synchronously —
    /// before [`Self::lock_vault`] drops the store — and hands only the vault
    /// engine to the spawned send, so the store's file lock is free to be
    /// reclaimed immediately regardless of how long the relay takes.
    fn spawn_farewell_beacons(&self) {
        let Some(store) = self.ui.store_ref() else {
            return;
        };
        let peers = comrade_peers(store);
        spawn_presence_beacons(self.vault.clone(), peers, PresenceBeacon::offline());
    }

    /// Fire-and-forget a receipt DM (delivered/read) to `peer`.
    fn spawn_receipt(&self, peer: &PublicKey, kind: ReceiptKind, message_ids: Vec<String>) {
        if message_ids.is_empty() {
            return;
        }
        let Some(vault) = self.vault.clone() else {
            return;
        };
        let peer = *peer;
        tokio::spawn(async move {
            if let Ok(json) = Receipt::new(kind, message_ids).to_json() {
                let _ = vault.send_dm(&peer, &json).await;
            }
        });
    }

    // ── Calls (voice/video · WebRTC signalled over the DM channel) ────────────

    /// The configured TURN relay, if any (see [`Self::set_turn_server`]).
    fn configured_turn_server(&self) -> Option<IceServer> {
        let store = self.ui.store_ref()?;
        let turn = store
            .get::<TurnConfig>(SETTINGS_TREE, TURN_CONFIG_KEY)
            .ok()??;
        (!turn.url.trim().is_empty())
            .then(|| IceServer::turn(turn.url, turn.username, turn.credential))
    }

    /// The ICE servers to hand a frontend `RTCPeerConnection`: public STUN by
    /// default, plus a user-configured TURN relay if one has been set.
    ///
    /// This is the "give me everything" list; [`Self::call_ice_servers_for`]
    /// exposes the STUN-first, TURN-on-failure strategy new calls should use.
    pub fn call_ice_servers(&self) -> Vec<IceServerDto> {
        ice_servers_for(
            IceStrategy::StunAndTurn,
            self.configured_turn_server().as_ref(),
        )
        .into_iter()
        .map(IceServerDto::from)
        .collect()
    }

    /// The ICE servers for one connection attempt under `strategy`
    /// (`"stun_only"` or `"stun_and_turn"`, see [`comrade_core::call::IceStrategy`]).
    ///
    /// Every call should start with `"stun_only"` (what [`Self::place_call`]
    /// uses): STUN is free and blind to the call, unlike a TURN relay. If the
    /// frontend's `RTCPeerConnection` reports its ICE connection state never
    /// reaches `connected`/`completed` — the CGNAT case a TURN server exists
    /// for — it calls this again with `"stun_and_turn"` and restarts ICE with
    /// the widened server list, now actually routing through the configured
    /// relay.
    pub fn call_ice_servers_for(&self, strategy: &str) -> Vec<IceServerDto> {
        let strategy = IceStrategy::from_str_lenient(strategy);
        ice_servers_for(strategy, self.configured_turn_server().as_ref())
            .into_iter()
            .map(IceServerDto::from)
            .collect()
    }

    /// The 4-emoji short authentication string (SAS) for the in-progress call
    /// whose local and remote SDPs are `local_sdp`/`remote_sdp` — for the two
    /// participants to read aloud and compare, catching a man-in-the-middle
    /// that re-terminated the DTLS-SRTP media path. `None` when either side's
    /// SDP has no `a=fingerprint:` line to derive a code from — an honest
    /// "can't verify" rather than a fabricated one; the frontend should treat
    /// that the same as a user who never checked, not as a failure.
    ///
    /// Pure computation over the two SDP strings already in hand (no store,
    /// no network) — see [`comrade_core::call::derive_sas`] for the crypto.
    pub fn call_sas(&self, local_sdp: &str, remote_sdp: &str) -> Option<Vec<String>> {
        derive_sas(local_sdp, remote_sdp)
    }

    /// Configure (or, with a blank `url`, clear) the TURN relay used for calls
    /// that cannot connect over STUN alone. Persisted in the encrypted store.
    ///
    /// Rejects (without persisting or ever logging `username`/`credential`) a
    /// non-blank `url` that isn't a well-formed `turn:`/`turns:` URI — see
    /// [`validate_turn_url`] — so a copy-paste mistake fails loudly in the
    /// settings UI instead of silently producing a `RTCPeerConnection` that
    /// can never reach the relay.
    pub fn set_turn_server(
        &self,
        url: &str,
        username: &str,
        credential: &str,
    ) -> Result<(), UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        if url.trim().is_empty() {
            store
                .delete(SETTINGS_TREE, TURN_CONFIG_KEY)
                .map_err(|e| UiError::Storage(e.to_string()))?;
        } else {
            validate_turn_url(url).map_err(UiError::Engine)?;
            let cfg = TurnConfig {
                url: url.trim().to_string(),
                username: username.to_string(),
                credential: credential.to_string(),
            };
            store
                .put(SETTINGS_TREE, TURN_CONFIG_KEY, &cfg)
                .map_err(|e| UiError::Storage(e.to_string()))?;
        }
        store.flush().map_err(|e| UiError::Storage(e.to_string()))
    }

    /// An honest "is a relay configured" status for a support/diagnostic
    /// screen — the URL (not secret) only, never the username/credential, so
    /// this is safe to log or display without masking. `None` when the vault
    /// is locked (rather than erroring): a settings screen showing "no relay
    /// configured" pre-unlock is more useful than a thrown exception.
    pub fn turn_server_status(&self) -> TurnServerStatusDto {
        match self.configured_turn_server() {
            Some(turn) => TurnServerStatusDto {
                configured: true,
                url: turn.urls.first().cloned(),
            },
            None => TurnServerStatusDto {
                configured: false,
                url: None,
            },
        }
    }

    /// Begin a call to `peer`: mint a call id and return the session the
    /// frontend needs (id, peer, media kind, ICE servers). No signal is sent
    /// yet — the frontend creates the WebRTC offer, then calls
    /// [`Self::send_call_signal`]. `media` is `"audio"` or `"video"`.
    ///
    /// `ice_servers` starts STUN-only (see [`Self::call_ice_servers_for`]) —
    /// if the connection can't complete, the frontend retries with
    /// `call_ice_servers_for("stun_and_turn")` before falling back to a
    /// `HangupReason::Failed`.
    pub fn place_call(&self, peer: &str, media: &str) -> Result<CallSessionDto, UiError> {
        if self.vault.is_none() {
            return Err(UiError::VaultLocked);
        }
        let _ = parse_pubkey(peer)?; // validate the target up front
        Ok(CallSessionDto {
            call_id: new_call_id(),
            peer: to_npub(peer),
            media: CallMediaKind::from_str_lenient(media).as_str().to_string(),
            ice_servers: self.call_ice_servers_for(IceStrategy::StunOnly.as_str()),
        })
    }

    /// Send one call-signaling payload to `peer` over the encrypted DM channel.
    /// `signal_json` is a serialised [`comrade_core::call::CallSignal`], e.g.
    /// `{"kind":"offer","sdp":"…"}` or `{"kind":"ice","candidate":"…"}`.
    ///
    /// Delegates to [`RuntimeHandles::send_call_signal`] — see [`Self::send_dm`].
    pub async fn send_call_signal(
        &self,
        peer: &str,
        call_id: &str,
        media: &str,
        signal_json: &str,
    ) -> Result<(), UiError> {
        self.handles()
            .send_call_signal(peer, call_id, media, signal_json)
            .await
    }

    /// Convenience: send a `Hangup` signal with `reason` (`normal`, `declined`,
    /// `busy`, `missed`, `cancelled`, `failed`) to end/reject a call.
    ///
    /// Delegates to [`RuntimeHandles::hangup_call`] — see [`Self::send_dm`].
    pub async fn hangup_call(
        &self,
        peer: &str,
        call_id: &str,
        media: &str,
        reason: &str,
    ) -> Result<(), UiError> {
        self.handles()
            .hangup_call(peer, call_id, media, reason)
            .await
    }

    /// Persist a finished call to the call log. `outcome` is one of
    /// `connected` / `missed` / `declined` / `cancelled` / `busy` / `failed`.
    #[allow(clippy::too_many_arguments)]
    pub fn log_call(
        &self,
        peer: &str,
        call_id: &str,
        media: &str,
        incoming: bool,
        outcome: &str,
        started_at: u64,
        duration_secs: u64,
    ) -> Result<CallRecordDto, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let record = comrade_storage::CallRecord {
            id: call_id.to_string(),
            peer_npub: to_npub(peer),
            media: CallMediaKind::from_str_lenient(media).as_str().to_string(),
            incoming,
            outcome: outcome.to_string(),
            started_at: if started_at == 0 {
                now_secs()
            } else {
                started_at
            },
            duration_secs,
        };
        store
            .save_call_record(&record)
            .and_then(|()| store.flush())
            .map_err(|e| UiError::Storage(e.to_string()))?;
        Ok(record.into())
    }

    /// The call log, newest first — for a single `peer` (npub/hex) or, with
    /// `None`, across every peer.
    pub fn call_history(&self, peer: Option<&str>) -> Result<Vec<CallRecordDto>, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let calls = match peer {
            Some(p) => store
                .calls_with(&to_npub(p))
                .map_err(|e| UiError::Storage(e.to_string()))?,
            None => store
                .all_calls()
                .map_err(|e| UiError::Storage(e.to_string()))?,
        };
        Ok(calls.into_iter().map(CallRecordDto::from).collect())
    }

    // ── Profile & contacts (username = alias, identity = keypair) ────────────

    /// The local profile: npub plus the chosen @handle (if set).
    pub fn profile(&self) -> Result<ProfileDto, UiError> {
        let id = self.ui.current_identity().ok_or(UiError::NoIdentity)?;
        Ok(ProfileDto {
            npub: id.npub,
            username: self.ui.username(),
        })
    }

    /// Claim a display handle for this identity.
    ///
    /// Trust model — why this cannot be globally unique: Comrade has no central
    /// registry, so nothing can stop a second keypair from publishing the same
    /// handle. The unforgeable identifier is the keypair (npub); the handle is
    /// a discovery alias published as Kind-0 metadata. Contacts pin the npub on
    /// first use, so a later "@same_handle" under a different key shows up as a
    /// different person and can never read or receive this thread's messages.
    ///
    /// The handle is persisted locally first; relay publication happens in a
    /// background task with retries (and again on every launch), so an offline
    /// claim still succeeds and becomes discoverable once a relay is reachable.
    pub async fn set_username(&mut self, handle: &str) -> Result<ProfileDto, UiError> {
        let handle = normalize_handle(handle)?;
        self.ui.set_username(handle.clone())?;
        if let Some(sabha) = self.sabha.clone() {
            // Never block (or fail) the claim on network state — but do keep
            // trying: a single dropped publish is exactly how a fresh identity
            // ends up unfindable by everyone else. Replaces (aborts) any
            // earlier retry loop so a stale name can't win the publish race.
            self.spawn_profile_publish(sabha, handle.clone());
        }
        self.profile()
    }

    /// Canonicalise a contact key: vault must be open, key must parse. One
    /// rule for every contact method, so junk input behaves identically
    /// across add/alias/remove on every bridge.
    fn canonical_contact_npub(&self, npub: &str) -> Result<String, UiError> {
        if self.ui.store_ref().is_none() {
            return Err(UiError::VaultLocked);
        }
        parse_pubkey(npub)?
            .to_bech32()
            .map_err(|e| UiError::Engine(e.to_string()))
    }

    /// Save a contact, pinned by npub — trust on first use. An empty `alias`
    /// leaves any existing alias untouched (so opening a chat with a known
    /// contact never wipes the name the user gave them); a non-empty alias
    /// sets it. Use [`Self::set_contact_alias`] to explicitly clear one.
    pub fn add_contact(&self, npub: &str, alias: &str) -> Result<ContactDto, UiError> {
        let canonical = self.canonical_contact_npub(npub)?;
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let alias = alias.trim();
        let existing = store
            .get_contact(&canonical)
            .map_err(|e| UiError::Storage(e.to_string()))?;
        if alias.is_empty() {
            if let Some(contact) = existing {
                // Already pinned and nothing to change — don't rewrite the
                // record (this path runs on every chat open).
                return Ok(ContactDto {
                    name: cached_peer_name(store, &contact.npub),
                    alias: user_alias(&contact.petname, &contact.npub).unwrap_or_default(),
                    npub: contact.npub,
                    comrade: contact.comrade,
                });
            }
        }
        self.write_contact(canonical, alias.to_string())
    }

    /// Set (non-empty) or clear (empty) the user-chosen alias for a contact.
    /// Creates the contact if it doesn't exist yet.
    pub fn set_contact_alias(&self, npub: &str, alias: &str) -> Result<ContactDto, UiError> {
        let canonical = self.canonical_contact_npub(npub)?;
        self.write_contact(canonical, alias.trim().to_string())
    }

    fn write_contact(&self, npub: String, petname: String) -> Result<ContactDto, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        // Carry the record's existing state forward: editing an alias must
        // never silently un-choose a comrade (presence is opt-in *and*
        // opt-out — both only ever by an explicit act).
        let existing = store
            .get_contact(&npub)
            .map_err(|e| UiError::Storage(e.to_string()))?;
        let contact = comrade_storage::Contact {
            npub,
            petname,
            relays: existing
                .as_ref()
                .map(|c| c.relays.clone())
                .unwrap_or_default(),
            comrade: existing.is_some_and(|c| c.comrade),
        };
        store
            .upsert_contact(&contact)
            .and_then(|()| store.flush())
            .map_err(|e| UiError::Storage(e.to_string()))?;
        Ok(ContactDto {
            name: cached_peer_name(store, &contact.npub),
            npub: contact.npub,
            alias: contact.petname,
            comrade: contact.comrade,
        })
    }

    /// Remove a saved contact. Returns whether one existed. The message
    /// history with that peer is untouched — only the pin/alias goes.
    pub fn remove_contact(&self, npub: &str) -> Result<bool, UiError> {
        let canonical = self.canonical_contact_npub(npub)?;
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let removed = store
            .remove_contact(&canonical)
            .map_err(|e| UiError::Storage(e.to_string()))?;
        store.flush().map_err(|e| UiError::Storage(e.to_string()))?;
        Ok(removed)
    }

    /// All saved contacts, sorted by their display title (alias, else
    /// published name, else key).
    pub fn list_contacts(&self) -> Result<Vec<ContactDto>, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let mut contacts: Vec<ContactDto> = store
            .list_contacts()
            .map_err(|e| UiError::Storage(e.to_string()))?
            .into_iter()
            .map(|c| ContactDto {
                name: cached_peer_name(store, &c.npub),
                alias: user_alias(&c.petname, &c.npub).unwrap_or_default(),
                comrade: c.comrade,
                npub: c.npub,
            })
            .collect();
        contacts.sort_by_key(|c| {
            if !c.alias.is_empty() {
                c.alias.to_lowercase()
            } else {
                c.name.as_deref().unwrap_or(c.npub.as_str()).to_lowercase()
            }
        });
        Ok(contacts)
    }

    /// Best-effort people search by handle over NIP-50-capable relays. An empty
    /// result means no search relay knew the name — offer add-by-npub instead.
    ///
    /// A query that *is* a key (npub/hex) resolves that identity's profile
    /// directly instead of a name search. Every result is cached into the
    /// local profile store so the chat UI can name the peer immediately.
    ///
    /// Delegates to [`RuntimeHandles::search_profiles`] — see [`Self::send_dm`].
    pub async fn search_profiles(&self, query: &str) -> Result<Vec<FoundProfileDto>, UiError> {
        self.handles().search_profiles(query).await
    }

    /// Detach a [`ProfileRefresher`] holding only the engine/store handles.
    ///
    /// The refresh does slow network work; callers behind the shared
    /// `Arc<RwLock<ComradeRuntime>>` (JNI, Tauri) MUST take this under a
    /// briefly-held guard, **drop the guard**, and then await
    /// [`ProfileRefresher::run`] — holding the runtime lock across relay
    /// round-trips stalls every other bridge call (AUDIT P2 discipline:
    /// no guard held across network awaits).
    pub fn profile_refresher(&self) -> Result<ProfileRefresher, UiError> {
        Ok(ProfileRefresher {
            sabha: self.sabha.clone().ok_or(UiError::VaultLocked)?,
            store: self.ui.store_arc().ok_or(UiError::VaultLocked)?,
        })
    }

    /// Convenience wrapper over [`Self::profile_refresher`] for callers that
    /// own the runtime directly (tests, CLI). Bridge code should use the
    /// refresher so the shared lock is not held across the network work.
    pub async fn refresh_peer_profiles(&self) -> Result<usize, UiError> {
        self.profile_refresher()?.run().await
    }

    // ── Comrades (chosen-peer presence — see `comrade_core::presence`) ───────

    /// Choose (or un-choose) a contact as a **comrade**.
    ///
    /// What this actually does, stated plainly because it is a disclosure:
    /// from now on this device tells *that one peer* — nobody else, and no
    /// relay — when it is online, and it starts believing what they say about
    /// their own presence. Turning it off sends them a final "offline" so
    /// their view of us goes dark immediately instead of aging out.
    ///
    /// It does **not** subscribe us to their presence: nothing in a
    /// serverless design can make their device report to us. We see them
    /// online only once they have marked us too — [`ComradeDto::peer_marked_us`]
    /// is what a UI shows so that wait is explained rather than mysterious.
    ///
    /// Marking a peer we have never saved creates the contact record (you can
    /// make someone a comrade straight from their conversation). Idempotent.
    ///
    /// Must be called from within a Tokio runtime context — the beacon is
    /// sent by a spawned task so the caller is never blocked on the network.
    pub fn set_comrade(&self, npub: &str, comrade: bool) -> Result<ContactDto, UiError> {
        let canonical = self.canonical_contact_npub(npub)?;
        let peer = parse_pubkey(&canonical)?;
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let contact = store
            .set_contact_comrade(&canonical, comrade)
            .and_then(|c| store.flush().map(|()| c))
            .map_err(|e| UiError::Storage(e.to_string()))?;

        // Tell them straight away, either way: a new comrade shouldn't wait a
        // heartbeat to see us, and a dropped one shouldn't keep seeing us.
        let beacon = if comrade {
            PresenceBeacon::online()
        } else {
            PresenceBeacon::offline()
        };
        spawn_presence_beacons(self.vault.clone(), vec![peer], beacon);

        // If they had already chosen us, a live beacon of theirs may already
        // be on file — recorded silently at the time, because presence for
        // someone we hadn't chosen is not news. Choosing them is the moment
        // it becomes news, so surface it now rather than leaving the user
        // waiting on a "transition" that already happened.
        if comrade && peer_is_online(store, &contact.npub, now_secs()) {
            let at = store
                .get_peer_presence(&contact.npub)
                .ok()
                .flatten()
                .map(|p| p.last_seen_at)
                .unwrap_or_else(now_secs);
            let _ = self.events.send(BridgeEvent::ComradePresence {
                name: presence_display_name(store, &contact.npub),
                peer: contact.npub.clone(),
                online: true,
                at,
            });
        }

        Ok(ContactDto {
            name: cached_peer_name(store, &contact.npub),
            alias: user_alias(&contact.petname, &contact.npub).unwrap_or_default(),
            npub: contact.npub,
            comrade: contact.comrade,
        })
    }

    /// Every comrade with their live presence, online first, then by most
    /// recently seen. Empty until the user marks someone — the feature costs
    /// nothing (no beacons, no state) while nobody is chosen.
    pub fn comrades(&self) -> Result<Vec<ComradeDto>, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let now = now_secs();
        let mut list: Vec<ComradeDto> = store
            .list_comrades()
            .map_err(|e| UiError::Storage(e.to_string()))?
            .into_iter()
            .map(|c| {
                let presence = store.get_peer_presence(&c.npub).ok().flatten();
                ComradeDto {
                    name: cached_peer_name(store, &c.npub),
                    alias: user_alias(&c.petname, &c.npub).unwrap_or_default(),
                    online: presence
                        .as_ref()
                        .is_some_and(|p| p.online && is_online_at(p.expires_at, now)),
                    last_seen_at: presence.as_ref().map(|p| p.last_seen_at).unwrap_or(0),
                    peer_marked_us: presence.as_ref().is_some_and(|p| p.peer_marked_us),
                    npub: c.npub,
                }
            })
            .collect();
        list.sort_by(|a, b| {
            b.online
                .cmp(&a.online)
                .then_with(|| b.last_seen_at.cmp(&a.last_seen_at))
                .then_with(|| a.npub.cmp(&b.npub))
        });
        Ok(list)
    }

    /// A single peer's live presence, or `None` if no beacon has ever arrived
    /// from them (they haven't marked us, or haven't been online since).
    pub fn peer_presence(&self, npub: &str) -> Result<Option<PresenceDto>, UiError> {
        let canonical = self.canonical_contact_npub(npub)?;
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let now = now_secs();
        Ok(store
            .get_peer_presence(&canonical)
            .map_err(|e| UiError::Storage(e.to_string()))?
            .map(|p| PresenceDto {
                online: p.online && is_online_at(p.expires_at, now),
                last_seen_at: p.last_seen_at,
                peer_marked_us: p.peer_marked_us,
                peer: p.peer_npub,
            }))
    }

    /// Announce this device's presence to every comrade and return how many
    /// beacons were sent. Frontends call this on foreground/background
    /// transitions; the heartbeat in [`Self::spawn_event_loops`] keeps it
    /// fresh in between.
    ///
    /// Delegates to [`RuntimeHandles::announce_presence`] — see [`Self::send_dm`]
    /// for why the network half never runs under the runtime lock.
    pub async fn announce_presence(&self, online: bool) -> u64 {
        self.handles().announce_presence(online).await
    }

    // ── The nudge (abandoned drafts — see `comrade_core::nudge`) ─────────────

    /// There is unsent text in `peer`'s composer, as of now.
    ///
    /// Frontends call this as the user types — it is idempotent, holds a
    /// mutex for a hash lookup, touches no store and no relay, so it is safe
    /// on a keystroke. What it starts is a clock, not a disclosure: nothing
    /// leaves the device unless the draft is later abandoned *and* every rule
    /// in [`comrade_core::nudge::draft_verdict`] agrees, and nothing at all
    /// happens for a peer who was never marked as a comrade.
    ///
    /// Infallible on purpose, like [`Self::announce_presence`]: a nudge is a
    /// courtesy, and a locked vault or an unparseable key simply means there
    /// is nobody to be courteous to.
    pub fn note_draft(&self, peer: &str) {
        self.draft_watch.writing(&to_npub(peer), now_secs());
    }

    /// `peer`'s draft is gone — the box was emptied, or the thread was closed
    /// with it still unsent. Safe (and intended) to call unconditionally when a
    /// conversation closes: a composer that never held text does nothing here.
    ///
    /// This is the only trigger for the whole feature, and it is deliberately
    /// *not* the moment anything is sent: see
    /// [`RuntimeHandles::nudge_abandoned_drafts`] for the wait that follows.
    pub fn abandon_draft(&self, peer: &str) {
        self.draft_watch.abandoned(&to_npub(peer), now_secs());
    }

    // ── Journal (wellbeing pillar #1 — strictly local, never networked) ──────

    /// Save a new journal entry. `mood` is an optional self-reported marker.
    /// The entry never leaves the device: no relay, no network — only the
    /// encrypted store.
    pub fn add_journal_entry(
        &self,
        text: &str,
        mood: Option<&str>,
    ) -> Result<JournalEntryDto, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let text = text.trim();
        if text.is_empty() {
            return Err(UiError::Engine("journal entry is empty".into()));
        }
        let created_at = now_secs();
        let entry = comrade_storage::JournalEntry {
            id: timestamped_store_id(created_at),
            text: text.to_string(),
            mood: mood
                .map(str::trim)
                .filter(|m| !m.is_empty())
                .map(String::from),
            created_at,
        };
        store
            .save_journal_entry(&entry)
            .and_then(|()| store.flush())
            .map_err(|e| UiError::Storage(e.to_string()))?;
        Ok(entry.into())
    }

    /// All journal entries, newest first.
    pub fn journal_entries(&self) -> Result<Vec<JournalEntryDto>, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        Ok(store
            .journal_entries()
            .map_err(|e| UiError::Storage(e.to_string()))?
            .into_iter()
            .map(JournalEntryDto::from)
            .collect())
    }

    /// Delete a journal entry by id. Returns whether one existed.
    pub fn delete_journal_entry(&self, id: &str) -> Result<bool, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let removed = store
            .remove_journal_entry(id)
            .map_err(|e| UiError::Storage(e.to_string()))?;
        store.flush().map_err(|e| UiError::Storage(e.to_string()))?;
        Ok(removed)
    }

    // ── Tara (wellbeing pillar #4 — reflective companion, strictly local) ────
    //
    // Same locality guarantee as the journal: no relay, no network. The reply
    // engine is `comrade_core::tara::ReflectiveCompanion` — deterministic,
    // on-device templates (AUDIT §8 / OQ9: the LLM slot stays empty until the
    // owner picks an on-device runtime; a cloud backend is out of the question).

    /// Send a message to Tara: persist the user's turn, compute the companion
    /// reply, persist it too, and return it. `crisis` on the returned DTO
    /// means the frontend must show [`Self::tara_crisis_resources`].
    pub fn tara_send(&self, text: &str) -> Result<TaraMessageDto, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let text = text.trim();
        if text.is_empty() {
            return Err(UiError::Engine("tara message is empty".into()));
        }
        let existing = store
            .tara_messages()
            .map_err(|e| UiError::Storage(e.to_string()))?;
        let prior_user_turns = existing.iter().filter(|m| !m.from_tara).count() as u64;

        let reply = ReflectiveCompanion.reply(text, prior_user_turns);
        let created_at = now_secs();
        // Sequence-numbered ids, not random ones: turns sent within the same
        // second must keep their exact send order (user, reply, user, reply…)
        // under the (created_at, id) sort — random tails would interleave.
        let seq = existing.len() as u64;
        let user_turn = comrade_storage::TaraMessage {
            id: format!("{created_at:020}-{seq:010}"),
            text: text.to_string(),
            from_tara: false,
            crisis: reply.crisis,
            created_at,
        };
        let tara_turn = comrade_storage::TaraMessage {
            id: format!("{created_at:020}-{:010}", seq + 1),
            text: reply.text,
            from_tara: true,
            crisis: reply.crisis,
            created_at,
        };
        store
            .save_tara_message(&user_turn)
            .and_then(|()| store.save_tara_message(&tara_turn))
            .and_then(|()| store.flush())
            .map_err(|e| UiError::Storage(e.to_string()))?;
        Ok(tara_turn.into())
    }

    /// The whole Tara thread, oldest-first (chat order).
    pub fn tara_thread(&self) -> Result<Vec<TaraMessageDto>, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        Ok(store
            .tara_messages()
            .map_err(|e| UiError::Storage(e.to_string()))?
            .into_iter()
            .map(TaraMessageDto::from)
            .collect())
    }

    /// Delete the entire Tara thread; returns how many turns were removed.
    pub fn clear_tara_thread(&self) -> Result<u64, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let removed = store
            .clear_tara_messages()
            .map_err(|e| UiError::Storage(e.to_string()))?;
        store.flush().map_err(|e| UiError::Storage(e.to_string()))?;
        Ok(removed)
    }

    /// The opener shown when the Tara thread is empty — shaped by recent
    /// journal *mood markers* only (never entry text; data minimisation), and
    /// by yesterday's usage **rollup numbers** only (never app names).
    ///
    /// Mood outranks usage; the precedence lives in
    /// `ReflectiveCompanion::opener_with_usage` so no frontend can decide it
    /// differently. With no usage recorded this is exactly the opener it always
    /// was.
    pub fn tara_opener(&self) -> Result<String, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let now = now_secs();
        let signals: Vec<JournalSignal> = store
            .journal_entries()
            .map_err(|e| UiError::Storage(e.to_string()))?
            .into_iter()
            .map(|e| JournalSignal {
                mood: e.mood,
                age_days: now.saturating_sub(e.created_at) / 86_400,
            })
            .collect();
        // The newest recorded day is "yesterday" for nudging purposes only
        // once it is no longer today's still-growing row — commenting on a
        // day the user is still living would be both premature and preachy.
        let days = store
            .attention_days()
            .map_err(|e| UiError::Storage(e.to_string()))?;
        let today = iso_date(now);
        let mut prior = days.iter().filter(|d| d.date != today);
        let yesterday = prior.next().map(usage_signal);
        let mut medians: Vec<u32> = prior.take(7).map(|d| d.doom_minutes).collect();
        medians.sort_unstable();
        let median_doom = (!medians.is_empty()).then(|| medians[medians.len() / 2]);
        let nudge = attention::usage_opener(yesterday, median_doom);
        Ok(ReflectiveCompanion.opener_with_usage(&signals, nudge))
    }

    /// The crisis helplines Tara hands off to, for any frontend to render.
    pub fn tara_crisis_resources(&self) -> Vec<CrisisResourceDto> {
        comrade_core::tara::CRISIS_RESOURCES
            .iter()
            .map(|r| CrisisResourceDto {
                name: r.name.to_string(),
                contact: r.contact.to_string(),
                note: r.note.to_string(),
            })
            .collect()
    }

    // ── Attention (wellbeing pillar #5 — strictly local, never networked) ─────
    //
    // The same locality guarantee as the journal and Tara: no relay, no
    // network, nothing uploaded. Usage data is behavioural data of the most
    // sensitive kind, so only *rollups* reach this layer at all — the raw
    // per-app event stream is reduced on the frontend and dropped there. See
    // `docs/ATTENTION.md` for the honesty gates these commands must keep.

    /// Record (or update) one day's usage rollup. `date` is the local calendar
    /// date as `YYYY-MM-DD`; the frontend owns the calendar, because only it
    /// knows the device's timezone.
    ///
    /// Called repeatedly through the day as the numbers grow — the row is
    /// keyed by date, so this upserts rather than accumulating duplicates.
    pub fn record_attention_day(
        &self,
        date: &str,
        screen_minutes: u32,
        pickups: u32,
        doom_minutes: u32,
    ) -> Result<AttentionDayDto, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let date = date.trim();
        if !is_iso_date(date) {
            return Err(UiError::Engine(format!(
                "attention day needs a YYYY-MM-DD date, got {date:?}"
            )));
        }
        let day = comrade_storage::AttentionDay {
            date: date.to_string(),
            screen_minutes,
            pickups,
            doom_minutes,
            updated_at: now_secs(),
        };
        store
            .save_attention_day(&day)
            .and_then(|()| store.flush())
            .map_err(|e| UiError::Storage(e.to_string()))?;
        Ok(day.into())
    }

    /// Every recorded usage day, newest first — for the local trend view.
    pub fn attention_days(&self) -> Result<Vec<AttentionDayDto>, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        Ok(store
            .attention_days()
            .map_err(|e| UiError::Storage(e.to_string()))?
            .into_iter()
            .map(AttentionDayDto::from)
            .collect())
    }

    /// `today`'s rollup against the user's own medians over the previous days.
    /// `today` is passed in (rather than derived here) for the same reason
    /// [`Self::record_attention_day`] takes a date: the timezone lives in the
    /// frontend.
    pub fn attention_summary(&self, today: &str) -> Result<AttentionSummaryDto, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let days = store
            .attention_days()
            .map_err(|e| UiError::Storage(e.to_string()))?;
        let today_row = days.iter().find(|d| d.date == today).cloned();
        let prior: Vec<UsageSignal> = days
            .iter()
            .filter(|d| d.date != today)
            .take(7)
            .map(usage_signal)
            .collect();
        let signal = today_row.as_ref().map(usage_signal).unwrap_or(UsageSignal {
            screen_minutes: 0,
            doom_minutes: 0,
            pickups: 0,
        });
        let cmp = attention::compare_today(signal, &prior);
        Ok(AttentionSummaryDto {
            today: today_row.map(AttentionDayDto::from),
            median_screen_minutes: cmp.median_screen_minutes,
            median_doom_minutes: cmp.median_doom_minutes,
            median_pickups: cmp.median_pickups,
            sample_days: cmp.sample_days,
        })
    }

    /// The package names the user tagged as their own scroll traps.
    ///
    /// Comrade ships **no** built-in blacklist of apps: which apps someone is
    /// compulsive about is their judgement, not ours, and a hard-coded list
    /// would also be a claim about other products we have no standing to make.
    pub fn doom_apps(&self) -> Result<Vec<String>, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        Ok(store
            .load_attention_prefs()
            .map_err(|e| UiError::Storage(e.to_string()))?
            .doom_packages)
    }

    /// Replace the user's doom-app list. Blanks are dropped and duplicates
    /// collapsed, so the frontend can pass a raw selection.
    pub fn set_doom_apps(&self, packages: Vec<String>) -> Result<Vec<String>, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let mut cleaned: Vec<String> = Vec::new();
        for p in packages {
            let p = p.trim().to_string();
            if !p.is_empty() && !cleaned.contains(&p) {
                cleaned.push(p);
            }
        }
        cleaned.sort();
        let prefs = comrade_storage::AttentionPrefs {
            doom_packages: cleaned.clone(),
        };
        store
            .save_attention_prefs(&prefs)
            .and_then(|()| store.flush())
            .map_err(|e| UiError::Storage(e.to_string()))?;
        Ok(cleaned)
    }

    /// Start a focus session, persisted immediately so an app kill can't make
    /// the history lie about a session that really ran.
    ///
    /// At most one session runs at a time: an earlier one still open is
    /// resolved first — completed-in-spirit sessions past their grace window
    /// become `lapsed`, and one genuinely still running is `abandoned`,
    /// because the user has visibly moved on to a new intention.
    pub fn start_focus_session(
        &self,
        intent: &str,
        planned_minutes: u32,
    ) -> Result<FocusSessionDto, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        if !(FOCUS_MIN_MINUTES..=FOCUS_MAX_MINUTES).contains(&planned_minutes) {
            return Err(UiError::Engine(format!(
                "focus session must be between {FOCUS_MIN_MINUTES} and {FOCUS_MAX_MINUTES} minutes"
            )));
        }
        let now = now_secs();
        // Close out whatever was open before starting something new.
        if let Some(open) = self.open_focus_session(store, now)? {
            let outcome = attention::resolve_stale(open.planned_minutes, open.started_at, now)
                .unwrap_or(FocusOutcome::Abandoned);
            self.finish_stored_session(store, open, outcome, now)?;
        }
        let session = comrade_storage::FocusSession {
            id: timestamped_store_id(now),
            intent: intent.trim().to_string(),
            planned_minutes,
            started_at: now,
            ended_at: None,
            outcome: None,
        };
        store
            .save_focus_session(&session)
            .and_then(|()| store.flush())
            .map_err(|e| UiError::Storage(e.to_string()))?;
        Ok(focus_dto(session, now))
    }

    /// Finish the running session. `completed` is the user's own verdict —
    /// `false` records it as abandoned, deliberately without ceremony (no
    /// streak to break, `docs/ATTENTION.md` gate 3).
    ///
    /// A session whose planned end plus grace window has already passed is
    /// recorded as `lapsed` whatever the caller says: claiming a completion
    /// for a session nobody was present for would make the history a lie, and
    /// the history is the only thing the progressive-duration rule reads.
    /// Returns `None` when no session was running.
    pub fn finish_focus_session(
        &self,
        completed: bool,
    ) -> Result<Option<FocusSessionDto>, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let now = now_secs();
        let Some(open) = self.open_focus_session(store, now)? else {
            return Ok(None);
        };
        let claimed = if completed {
            FocusOutcome::Completed
        } else {
            FocusOutcome::Abandoned
        };
        let outcome =
            attention::resolve_stale(open.planned_minutes, open.started_at, now).unwrap_or(claimed);
        let finished = self.finish_stored_session(store, open, outcome, now)?;
        Ok(Some(focus_dto(finished, now)))
    }

    /// The session currently running, if any. Resolving is a *read* that can
    /// write: a session found past its grace window is recorded as `lapsed`
    /// here and reported as gone, so every caller sees one consistent answer
    /// rather than each frontend inventing its own staleness rule.
    pub fn active_focus_session(&self) -> Result<Option<FocusSessionDto>, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let now = now_secs();
        Ok(self
            .open_focus_session(store, now)?
            .map(|s| focus_dto(s, now)))
    }

    /// Focus-session history, newest first.
    pub fn focus_sessions(&self) -> Result<Vec<FocusSessionDto>, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let now = now_secs();
        Ok(store
            .focus_sessions()
            .map_err(|e| UiError::Storage(e.to_string()))?
            .into_iter()
            .map(|s| focus_dto(s, now))
            .collect())
    }

    /// The duration to suggest next, from this user's own completion history —
    /// the "rebuild the span" rule (`attention::suggest_focus_minutes`).
    pub fn suggested_focus_minutes(&self) -> Result<u32, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let history: Vec<(u32, FocusOutcome)> = store
            .focus_sessions()
            .map_err(|e| UiError::Storage(e.to_string()))?
            .into_iter()
            .filter_map(|s| {
                s.outcome
                    .as_deref()
                    .and_then(FocusOutcome::from_key)
                    .map(|o| (s.planned_minutes, o))
            })
            .collect();
        Ok(attention::suggest_focus_minutes(&history))
    }

    /// The intention nudge to show before starting a session, rotated by how
    /// many sessions came before it.
    pub fn focus_prompt(&self) -> Result<String, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let prior = store
            .focus_sessions()
            .map_err(|e| UiError::Storage(e.to_string()))?
            .len() as u64;
        Ok(attention::focus_prompt(prior).to_string())
    }

    /// The line to show when a session ends — a reflection prompt for a
    /// completion, plain acknowledgement for anything else.
    pub fn focus_reflection(&self, outcome: &str) -> Result<String, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let parsed = FocusOutcome::from_key(outcome)
            .ok_or_else(|| UiError::Engine(format!("unknown focus outcome {outcome:?}")))?;
        let prior = store
            .focus_sessions()
            .map_err(|e| UiError::Storage(e.to_string()))?
            .len() as u64;
        Ok(attention::focus_reflection(parsed, prior))
    }

    /// Save text for the distraction-free reader, replacing whatever was
    /// there, and return it chunked. The user brings the text (paste, share);
    /// nothing here fetches a URL — a reader that went to the network would
    /// put an arbitrary-fetch path into the one app that promises not to.
    pub fn save_reading(&self, title: &str, text: &str) -> Result<ReadingDto, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let text = text.trim();
        if text.is_empty() {
            return Err(UiError::Engine("nothing to read".into()));
        }
        let state = comrade_storage::ReadingState {
            title: title.trim().to_string(),
            text: text.to_string(),
            position: 0,
            updated_at: now_secs(),
        };
        store
            .save_reading_state(&state)
            .and_then(|()| store.flush())
            .map_err(|e| UiError::Storage(e.to_string()))?;
        Ok(reading_dto(state))
    }

    /// The saved long-read, chunked, or `None` if nothing is saved.
    pub fn reading(&self) -> Result<Option<ReadingDto>, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        Ok(store
            .load_reading_state()
            .map_err(|e| UiError::Storage(e.to_string()))?
            .map(reading_dto))
    }

    /// Remember which chunk the reader is on. Clamped to the real chunk count,
    /// so a stored position can never point past the end of the text.
    pub fn set_reading_position(&self, position: u32) -> Result<Option<ReadingDto>, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let Some(mut state) = store
            .load_reading_state()
            .map_err(|e| UiError::Storage(e.to_string()))?
        else {
            return Ok(None);
        };
        let chunks = attention::chunk_reading(&state.text).len() as u32;
        state.position = position.min(chunks.saturating_sub(1));
        state.updated_at = now_secs();
        store
            .save_reading_state(&state)
            .and_then(|()| store.flush())
            .map_err(|e| UiError::Storage(e.to_string()))?;
        Ok(Some(reading_dto(state)))
    }

    /// Forget the saved long-read. Returns whether one existed.
    pub fn clear_reading(&self) -> Result<bool, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let removed = store
            .clear_reading_state()
            .map_err(|e| UiError::Storage(e.to_string()))?;
        store.flush().map_err(|e| UiError::Storage(e.to_string()))?;
        Ok(removed)
    }

    /// The session still open, resolving (and persisting) a lapse first so
    /// every caller agrees on what "running" means. Shared by
    /// [`Self::active_focus_session`], [`Self::start_focus_session`] and
    /// [`Self::finish_focus_session`].
    fn open_focus_session(
        &self,
        store: &comrade_storage::EncryptedStore,
        now: u64,
    ) -> Result<Option<comrade_storage::FocusSession>, UiError> {
        let sessions = store
            .focus_sessions()
            .map_err(|e| UiError::Storage(e.to_string()))?;
        let Some(open) = sessions.into_iter().find(|s| s.outcome.is_none()) else {
            return Ok(None);
        };
        match attention::resolve_stale(open.planned_minutes, open.started_at, now) {
            Some(outcome) => {
                self.finish_stored_session(store, open, outcome, now)?;
                Ok(None)
            }
            None => Ok(Some(open)),
        }
    }

    /// Stamp `outcome` on a session and persist it.
    fn finish_stored_session(
        &self,
        store: &comrade_storage::EncryptedStore,
        session: comrade_storage::FocusSession,
        outcome: FocusOutcome,
        now: u64,
    ) -> Result<comrade_storage::FocusSession, UiError> {
        // A lapsed session ended when its plan did, not when someone finally
        // looked: stamping "now" would credit hours nobody was present for.
        let ended_at = match outcome {
            FocusOutcome::Lapsed => session.started_at + u64::from(session.planned_minutes) * 60,
            _ => now,
        };
        let finished = comrade_storage::FocusSession {
            ended_at: Some(ended_at),
            outcome: Some(outcome.as_str().to_string()),
            ..session
        };
        store
            .save_focus_session(&finished)
            .and_then(|()| store.flush())
            .map_err(|e| UiError::Storage(e.to_string()))?;
        Ok(finished)
    }

    // ── Encrypted media pipeline (NIP-94/96 · Blossom) ───────────────────────

    /// Encrypt `bytes` for `target_pubkey`, upload the opaque blob to Blossom,
    /// build a zero-knowledge NIP-94 reference, persist it locally, and deliver
    /// the reference privately over the E2E DM channel. Returns the media DTO.
    ///
    /// The AES key is derived from the ECDH shared secret, so it is never
    /// uploaded and never placed in the public event — the recipient re-derives
    /// it from their own private key and our pubkey.
    ///
    /// Delegates to [`RuntimeHandles::upload_and_send_media`] — see [`Self::send_dm`].
    pub async fn upload_and_send_media(
        &self,
        target_pubkey: &str,
        bytes: Vec<u8>,
        mime_type: &str,
        caption: &str,
    ) -> Result<MediaMessageDto, UiError> {
        self.handles()
            .upload_and_send_media(target_pubkey, bytes, mime_type, caption)
            .await
    }

    /// Resolve a NIP-94 reference by event id, fetch the encrypted blob, and
    /// decrypt it with the re-derived ECDH key. Returns base64 bytes + MIME.
    ///
    /// Delegates to [`RuntimeHandles::download_and_decrypt_media`] — see
    /// [`Self::send_dm`].
    pub async fn download_and_decrypt_media(
        &self,
        event_id: &str,
    ) -> Result<MediaBytesDto, UiError> {
        self.handles().download_and_decrypt_media(event_id).await
    }

    /// Full encrypted-media history with `peer` (npub or hex), oldest first —
    /// the media counterpart of [`Self::messages_with`]. Lets a frontend
    /// render past attachments inline after a restart, not just ones that
    /// arrived live this session (references are persisted the moment they're
    /// sent or received — see [`Self::upload_and_send_media`] and
    /// `dispatch_incoming_dm`).
    pub fn media_with(&self, peer: &str) -> Result<Vec<MediaMessageDto>, UiError> {
        let store = self.ui.store_ref().ok_or(UiError::VaultLocked)?;
        let peer_hex = parse_pubkey(peer)?.to_hex();
        let own_npub = self
            .ui
            .current_identity()
            .map(|i| i.npub)
            .unwrap_or_default();

        let mut items: Vec<MediaMessageDto> = store
            .values::<MediaRef>(MEDIA_REFS_TREE)
            .map_err(|e| UiError::Storage(e.to_string()))?
            .into_iter()
            .filter(|r| r.peer_pubkey == peer_hex)
            .map(|r| MediaMessageDto {
                event_id: r.event_id,
                url: r.url,
                mime_type: r.mime_type,
                caption: r.caption,
                sender: if r.outgoing {
                    own_npub.clone()
                } else {
                    to_npub(&r.peer_pubkey)
                },
                created_at: r.created_at,
                size: r.size,
                outgoing: r.outgoing,
            })
            .collect();
        items.sort_by_key(|m| m.created_at);
        Ok(items)
    }

    // ── Milestone 3: progressive-disclosure workspace controller ─────────────

    /// Switch the active workspace, enforcing the [`comrade_state`] transition
    /// rules. An invalid or un-paired transition returns a typed [`UiError`]
    /// (surfaced to the frontend as a rejected promise / JSON error).
    ///
    /// On success, also brings the Saathi mesh engine's lifecycle in line with
    /// the new workspace (see [`Self::sync_saathi_lifecycle`]) — entering
    /// `OffGridTravel` really starts mDNS discovery, it doesn't just flip a label.
    pub async fn toggle_workspace(&mut self, target: &str) -> Result<WorkspaceDto, UiError> {
        let dto = self.ui.switch_workspace(target)?;
        self.sync_saathi_lifecycle().await;
        Ok(dto)
    }

    /// Step back to the previous workspace, syncing the Saathi mesh lifecycle
    /// exactly as [`Self::toggle_workspace`] does.
    pub async fn back(&mut self) -> WorkspaceDto {
        let dto = self.ui.back();
        self.sync_saathi_lifecycle().await;
        dto
    }

    /// Snapshot of the off-grid mesh's live status — for seeding a UI's
    /// connectivity indicator before any [`BridgeEvent::MeshStatusChanged`]
    /// has arrived (e.g. right after a cold start or an activity recreation).
    pub fn mesh_status(&self) -> MeshStatusDto {
        match &self.saathi {
            Some(engine) => MeshStatusDto {
                active: true,
                peer_count: engine.peer_count() as u64,
            },
            None => MeshStatusDto {
                active: false,
                peer_count: 0,
            },
        }
    }

    /// Ensure the Saathi engine is running iff the current workspace is
    /// `OffGridTravel`. Centralised here (rather than duplicated in
    /// `toggle_workspace`/`back`) so every path that can change the workspace —
    /// a voice command, a future UI toggle, stepping back — drives the same
    /// real engine the persistent mesh-status indicator reads from.
    async fn sync_saathi_lifecycle(&mut self) {
        // Two independent reasons to be running, so this is an OR, not just the
        // workspace flag:
        //
        //  • the OffGridTravel workspace, where the mesh *replaces* relays, and
        //  • any unlocked vault, because "the person I'm messaging is on this
        //    WiFi" is not a mode the user should have to select — it is the
        //    fallback that makes a DM arrive when the internet is down.
        //
        // Only when neither holds does the engine stop.
        let should_run = self.ui.current_workspace().mesh_active || self.is_vault_unlocked();
        match (should_run, self.saathi.is_some()) {
            (true, false) => self.start_saathi().await,
            (false, true) => self.stop_saathi().await,
            _ => {}
        }
    }

    /// Start the Saathi mesh engine and spawn the task that forwards its live
    /// peer-count stream onto the shared event bus. Best-effort: if the swarm
    /// fails to initialise (e.g. no usable socket), the workspace switch still
    /// succeeds — the indicator just reports `active: false` rather than
    /// hanging in a perpetual "connecting" state.
    async fn start_saathi(&mut self) {
        let label = self
            .ui
            .username()
            .or_else(|| self.ui.current_identity().map(|i| i.npub))
            .unwrap_or_else(|| "comrade-mesh-peer".to_string());
        match SaathiEngine::new(label).await {
            Ok(engine) => {
                let engine = Arc::new(engine);
                self.spawn_mesh_status_forwarder(engine.clone());
                self.saathi = Some(engine);
            }
            Err(e) => {
                warn!("Saathi: failed to start mesh engine: {e}");
                let _ = self
                    .events
                    .send(BridgeEvent::MeshStatusChanged(MeshStatusDto {
                        active: false,
                        peer_count: 0,
                    }));
            }
        }
    }

    /// Shut down the Saathi mesh engine and tell the UI it is gone.
    async fn stop_saathi(&mut self) {
        if let Some(engine) = self.saathi.take() {
            engine.shutdown().await;
        }
        let _ = self
            .events
            .send(BridgeEvent::MeshStatusChanged(MeshStatusDto {
                active: false,
                peer_count: 0,
            }));
    }

    /// Forward the engine's peer-count stream onto the bridge event bus as
    /// [`BridgeEvent::MeshStatusChanged`] — once immediately (the starting
    /// snapshot) and again every time a peer joins or leaves.
    fn spawn_mesh_status_forwarder(&self, engine: Arc<SaathiEngine>) {
        let mut peer_count_rx = engine.peer_count_stream();
        let tx = self.events.clone();
        tokio::spawn(async move {
            let _ = tx.send(BridgeEvent::MeshStatusChanged(MeshStatusDto {
                active: true,
                peer_count: *peer_count_rx.borrow() as u64,
            }));
            while peer_count_rx.changed().await.is_ok() {
                let _ = tx.send(BridgeEvent::MeshStatusChanged(MeshStatusDto {
                    active: true,
                    peer_count: *peer_count_rx.borrow() as u64,
                }));
            }
        });
    }

    // ── Sakha/Sakhi CRDT ledger: pairing + entries + sync ────────────────────

    /// Restore a previously-completed pairing (and its ledger snapshot) from
    /// the encrypted store, so a returning paired couple doesn't have to
    /// re-exchange keys every launch. Called once from [`Self::unlock_vault`],
    /// right after the Sakha engine is constructed. Best-effort: a missing or
    /// unreadable record just leaves the engine unpaired, exactly as if this
    /// were the first launch.
    async fn restore_sakha_pairing(&mut self) {
        let Some(store) = self.ui.store_ref() else {
            return;
        };
        let record: Option<SakhaPairingRecord> =
            store.get(SAKHA_TREE, SAKHA_PAIRING_KEY).ok().flatten();
        if let (Some(record), Some(sakha)) = (record, self.sakha.clone()) {
            match PublicKey::parse(&record.partner_pubkey_hex) {
                Ok(partner_pk) => {
                    if let Err(e) = sakha.pair_with(partner_pk) {
                        warn!("failed to restore Sakha pairing: {e}");
                    }
                }
                Err(e) => warn!("stored Sakha partner key is invalid: {e}"),
            }
        }
        // The ledger snapshot restores independently of pairing succeeding —
        // the CRDT text itself doesn't need a partner key to read locally.
        if let Ok(Some(state)) = store.load_ledger_state() {
            if let Some(sakha) = self.sakha.clone() {
                if let Err(e) = sakha.load_snapshot(&state.snapshot).await {
                    warn!("failed to restore Sakha ledger snapshot: {e}");
                }
            }
        }
    }

    /// Start the background loop that merges the partner's incoming ledger
    /// updates: each successful merge pushes [`BridgeEvent::LedgerUpdated`]
    /// and persists a fresh snapshot. Idempotent and safe to call whether
    /// triggered by a fresh [`Self::pair_sakha`] or a pairing restored at
    /// unlock — spawned at most once per runtime.
    fn spawn_sakha_sync_loop(&mut self) {
        if self.sakha_sync_spawned {
            return;
        }
        let Some(sakha) = self.sakha.clone() else {
            return;
        };
        self.sakha_sync_spawned = true;
        let tx = self.events.clone();
        let store = self.ui.store_arc();
        let sakha_for_snapshot = sakha.clone();
        self.sakha_sync_task = Some(tokio::spawn(async move {
            sakha.connect().await;
            let cb: SakhaSyncCallback = Box::new(move |ledger| {
                let _ = tx.send(BridgeEvent::LedgerUpdated { ledger });
                let Some(store) = store.clone() else { return };
                let sakha = sakha_for_snapshot.clone();
                tokio::spawn(async move { persist_ledger_snapshot(&store, &sakha).await });
            });
            if let Err(e) = sakha.subscribe_sync(cb).await {
                warn!("sakha sync loop ended: {e}");
            }
        }));
    }

    /// Perform the Sakha/Sakhi pairing handshake with `partner_pubkey` (npub
    /// or hex) as `role` (`"sakha"`/`"sakhi"`): derives the shared ledger key,
    /// persists the pairing so it survives a restart, and starts the
    /// background sync loop that merges the partner's future ledger updates
    /// live. Returns the resulting pairing status.
    pub async fn pair_sakha(
        &mut self,
        partner_pubkey: &str,
        role: &str,
    ) -> Result<SakhaStatusDto, UiError> {
        let sakha = self.sakha.clone().ok_or(UiError::VaultLocked)?;
        let peer = parse_pubkey(partner_pubkey)?;
        sakha
            .pair_with(peer)
            .map_err(|e| UiError::Engine(e.to_string()))?;

        let role = normalize_pair_role(role);
        if let Some(store) = self.ui.store_ref() {
            let record = SakhaPairingRecord {
                partner_pubkey_hex: peer.to_hex(),
                role,
            };
            store
                .put(SAKHA_TREE, SAKHA_PAIRING_KEY, &record)
                .and_then(|()| store.flush())
                .map_err(|e| UiError::Storage(e.to_string()))?;
        }

        self.spawn_sakha_sync_loop();
        self.sakha_status()
    }

    /// This device's Sakha/Sakhi pairing state.
    pub fn sakha_status(&self) -> Result<SakhaStatusDto, UiError> {
        let sakha = self.sakha.clone().ok_or(UiError::VaultLocked)?;
        let partner_npub = sakha
            .partner_pubkey()
            .map(|pk| pk.to_bech32().unwrap_or_else(|_| pk.to_hex()));
        let role = self
            .ui
            .store_ref()
            .and_then(|s| {
                s.get::<SakhaPairingRecord>(SAKHA_TREE, SAKHA_PAIRING_KEY)
                    .ok()
                    .flatten()
            })
            .map(|r| r.role);
        Ok(SakhaStatusDto {
            paired: sakha.is_paired(),
            partner_npub,
            role,
        })
    }

    /// Append an entry to the shared Sakha/Sakhi CRDT ledger, persist a fresh
    /// local snapshot, and return the merged ledger text. Requires a
    /// completed pairing — use [`Self::pair_sakha`] first.
    ///
    /// Delegates to [`RuntimeHandles::sakha_add_entry`] — see [`Self::send_dm`].
    pub async fn sakha_add_entry(
        &self,
        description: &str,
        amount_inr: f64,
        paid_by: &str,
    ) -> Result<String, UiError> {
        self.handles()
            .sakha_add_entry(description, amount_inr, paid_by)
            .await
    }

    /// The current Sakha/Sakhi ledger text (local CRDT state — no network
    /// round trip). Empty until entries exist or a snapshot/sync restores some.
    pub async fn sakha_read_ledger(&self) -> Result<String, UiError> {
        let sakha = self.sakha.clone().ok_or(UiError::VaultLocked)?;
        Ok(sakha.read_ledger().await)
    }

    /// Publish the current Sakha/Sakhi shared CRDT ledger state to the partner.
    /// Returns the sync event id (hex). Without a completed pairing handshake the
    /// engine returns a typed error rather than panicking.
    ///
    /// Delegates to [`RuntimeHandles::sync_ledger`] — see [`Self::send_dm`].
    pub async fn sync_ledger(&self) -> Result<String, UiError> {
        self.handles().sync_ledger().await
    }

    // ── Sync view-model delegations (shared with the existing desktop UI) ────

    pub fn workspaces(&self) -> Vec<WorkspaceDto> {
        self.ui.workspaces()
    }

    pub fn current_workspace(&self) -> WorkspaceDto {
        self.ui.current_workspace()
    }

    pub fn generate_identity(&mut self) -> Result<IdentityDto, UiError> {
        self.ui.generate_identity()
    }

    pub fn current_identity(&self) -> Option<IdentityDto> {
        self.ui.current_identity()
    }

    pub fn extract_payments(&self, text: &str) -> Result<Vec<UpiIntentDto>, UiError> {
        self.ui.extract_payments(text)
    }

    /// Whether the encrypted store is unlocked (a superset state of the vault).
    pub fn is_store_unlocked(&self) -> bool {
        self.ui.is_store_unlocked()
    }

    /// A cheap, synchronous snapshot of the live engine handles + identity
    /// bits a network operation needs — see [`RuntimeHandles`]. Bridges
    /// (Tauri commands, `comrade_jni`) call this to run a network operation
    /// without holding the shared `Arc<RwLock<ComradeRuntime>>` guard across
    /// the round trip (AUDIT P2 — the same discipline [`Self::profile_refresher`]
    /// already established for profile refreshes, generalised to every other
    /// network-touching method).
    pub fn handles(&self) -> RuntimeHandles {
        RuntimeHandles {
            sabha: self.sabha.clone(),
            vault: self.vault.clone(),
            sakha: self.sakha.clone(),
            store: self.ui.store_arc(),
            keys: self.ui.identity_keys(),
            username: self.ui.username(),
            identity: self.ui.current_identity(),
            outbox: self.outbox.clone(),
            events: self.events.clone(),
            mesh: self.mesh_link(),
            draft_watch: self.draft_watch.clone(),
        }
    }

    /// A sealed-mail sender for the running mesh, if there is one and we have
    /// keys to seal with.
    fn mesh_link(&self) -> Option<MeshLink> {
        Some(MeshLink {
            engine: self.saathi.clone()?,
            keys: self.ui.identity_keys()?,
        })
    }
}

// ── Detached network operations (AUDIT P2) ──────────────────────────────────
//
// Every method below mirrors a `ComradeRuntime` method of the same name
// (which delegates to it via `ComradeRuntime::handles`), but takes owned data
// instead of `&self`. That is the whole point: `&self` methods on
// `ComradeRuntime` are called through `Arc<RwLock<ComradeRuntime>>` in every
// bridge, and Rust ties an `async fn(&self, …)`'s returned future to `&self`'s
// lifetime for its *entire* body — even the part after the method stops
// touching `self` — so a bridge calling `state.read().await.send_dm(…).await`
// holds the guard across the relay round trip no matter how the method body
// is written. `RuntimeHandles` breaks that tie: a bridge takes a snapshot
// under a briefly-held guard (`state.read().await.handles()`, cheap Arc/Option
// clones, no `.await` of its own) and the guard is dropped at the end of that
// statement — then the actual network operation runs guard-free.
#[derive(Clone)]
pub struct RuntimeHandles {
    sabha: Option<Arc<SabhaEngine>>,
    vault: Option<Arc<VaultEngine>>,
    sakha: Option<Arc<SakhaEngine>>,
    store: Option<Arc<comrade_storage::EncryptedStore>>,
    keys: Option<nostr_sdk::Keys>,
    username: Option<String>,
    identity: Option<IdentityDto>,
    /// The live sender outbox — shared with the runtime and the inbox callback,
    /// so a send failure here and a receipt there act on the same queue.
    outbox: Arc<Outbox>,
    /// Event bus, so a flush can report status changes (`sent` / `failed`)
    /// without going back through `ComradeRuntime`.
    events: broadcast::Sender<BridgeEvent>,
    /// The local-network mesh, when it is running — the transport that carries
    /// a DM when no relay will.
    mesh: Option<MeshLink>,
    /// The composer watch, shared with the runtime the frontends call into —
    /// see [`ComradeRuntime::draft_watch`] and [`Self::nudge_abandoned_drafts`].
    draft_watch: Arc<DraftWatch>,
}

impl RuntimeHandles {
    pub async fn send_dm(&self, target: &str, content: &str) -> Result<MessageDto, UiError> {
        self.send_dm_reply(target, content, None).await
    }

    pub async fn send_dm_reply(
        &self,
        target: &str,
        content: &str,
        reply_to: Option<&str>,
    ) -> Result<MessageDto, UiError> {
        if content.trim().is_empty() {
            return Err(UiError::Engine("message is empty".into()));
        }
        let vault = self.vault.clone().ok_or(UiError::VaultLocked)?;
        let peer = parse_pubkey(target)?;
        let peer_npub = to_npub(target);
        let created_at = now_secs();

        // A relay that will not take the message is not the end of the road:
        // queue it, persist it as `queued`, and let the flush loop retry
        // (bitchat whitepaper §6.1). Before this, a publish failure lost the
        // text entirely — the worst failure mode an app about staying in touch
        // can have.
        let (id, status) = match vault.send_dm_reply(&peer, content, reply_to).await {
            Ok(id) => (id.to_hex(), "sent"),
            Err(e) => {
                let local_id = local_message_id(&peer_npub, content, created_at);
                tracing::info!(error = %e, "DM could not be published — queued for retry");
                // No relay took it, but the recipient may be on this WiFi. Seal
                // it and flood the local mesh. It stays queued either way: a
                // mesh publish reaching *a* peer is not proof the recipient got
                // it, so only their receipt clears the outbox.
                if let Some(mesh) = &self.mesh {
                    mesh.send(
                        &peer,
                        &local_id,
                        content,
                        reply_to.map(str::to_string),
                        created_at,
                    )
                    .await;
                }
                let queued = QueuedMessage::new(
                    local_id.clone(),
                    peer_npub.clone(),
                    content,
                    reply_to.map(str::to_string),
                    created_at,
                );
                if let QueueOutcome::Displaced(dropped) = self.outbox.queue(queued) {
                    // The peer's queue was full; the oldest message is gone and
                    // must not keep showing as pending.
                    self.mark_status(&peer_npub, &[dropped], STATUS_FAILED);
                }
                if let Some(store) = &self.store {
                    persist_outbox(store, &self.outbox);
                }
                (local_id, STATUS_QUEUED)
            }
        };

        // The words are out of the composer and into the pipeline, so there is
        // nothing left unsaid to nudge about — including in the queued case,
        // where the outbox will deliver them without the user doing anything
        // else. No frontend has to remember this: sending is the one way to
        // leave a composer that must never look like abandoning it.
        self.draft_watch.sent(&peer_npub);

        let dto = MessageDto {
            id,
            peer: peer_npub.clone(),
            content: content.to_string(),
            created_at,
            outgoing: true,
            status: Some(status.into()),
            reply_to: reply_to.map(str::to_string),
        };
        if let Some(store) = &self.store {
            let row = comrade_storage::StoredMessage {
                id: dto.id.clone(),
                peer_npub: dto.peer.clone(),
                content: dto.content.clone(),
                created_at: dto.created_at,
                outgoing: true,
                status: Some(status.into()),
                reply_to: dto.reply_to.clone(),
            };
            if let Err(e) = store.save_message(&row).and_then(|()| store.flush()) {
                warn!("failed to persist outgoing DM: {e}");
            }
        }
        // Only a message that actually reached a relay should trigger the
        // profile share; a queued one shares on its successful flush instead.
        if status == "sent" {
            share_profile_on_accept(
                self.store.clone(),
                self.vault.clone(),
                self.username.clone(),
                &peer_npub,
                &peer,
            );
        }
        Ok(dto)
    }

    /// Retry every queued message that is still worth retrying, and reap the
    /// ones that are not. Returns how many were accepted by a relay.
    ///
    /// Called on a cadence by the loop [`ComradeRuntime::spawn_event_loops`]
    /// starts (with the first tick at launch, which is when mail queued in a
    /// previous session should go out), and callable directly by a host that
    /// knows connectivity just came back.
    pub async fn flush_outbox(&self) -> Result<usize, UiError> {
        let vault = self.vault.clone().ok_or(UiError::VaultLocked)?;
        let now = now_secs();

        // Who each queued item was for, captured *before* pruning: a media
        // reference has no stored message row, so `peer_of_stored` cannot name
        // its conversation once the queue entry is gone.
        let queued_peers: std::collections::HashMap<String, String> = self
            .outbox
            .snapshot()
            .queues
            .into_iter()
            .flat_map(|(peer, queue)| {
                queue
                    .into_iter()
                    .map(move |m| (m.message_id, peer.clone()))
                    .collect::<Vec<_>>()
            })
            .collect();

        // Expired or attempt-exhausted mail: stop lying about it in the UI.
        for dropped in self.outbox.prune(now) {
            if let Some(peer) = self
                .peer_of_stored(&dropped)
                .or_else(|| queued_peers.get(&dropped).cloned())
            {
                self.mark_status(&peer, &[dropped], STATUS_FAILED);
            }
        }

        let due = self.outbox.due(now);
        if due.is_empty() {
            return Ok(0);
        }

        let mut sent = 0usize;
        for queued in due {
            let Ok(peer_pk) = parse_pubkey(&queued.peer_npub) else {
                // An unparseable recipient can never succeed; drop it rather
                // than retry it eight times.
                self.outbox
                    .ack(&queued.peer_npub, std::slice::from_ref(&queued.message_id));
                continue;
            };
            match vault
                .send_dm_reply(&peer_pk, &queued.content, queued.reply_to.as_deref())
                .await
            {
                Ok(event_id) => {
                    self.outbox
                        .ack(&queued.peer_npub, std::slice::from_ref(&queued.message_id));
                    // A media reference has no stored text row. Re-keying it
                    // would *create* one whose body is the envelope JSON — a
                    // bubble full of machine-readable noise, and the chat
                    // list's preview. Its own NIP-94 event id is the handle the
                    // UI already holds, so that is what the status names.
                    let status_id = if is_media_envelope(&queued.content) {
                        queued.message_id.clone()
                    } else {
                        // Re-key the stored row to the relay's event id: a later
                        // delivered/read receipt names *that* id, so without
                        // this the ticks would never advance past `sent`.
                        self.rekey_stored_message(&queued, &event_id.to_hex());
                        event_id.to_hex()
                    };
                    let _ = self.events.send(BridgeEvent::MessageStatus {
                        peer: queued.peer_npub.clone(),
                        message_ids: vec![status_id],
                        status: "sent".into(),
                    });
                    share_profile_on_accept(
                        self.store.clone(),
                        self.vault.clone(),
                        self.username.clone(),
                        &queued.peer_npub,
                        &peer_pk,
                    );
                    sent += 1;
                }
                Err(e) => {
                    tracing::debug!(error = %e, "queued DM still undeliverable by relay");
                    // Still no relay — try the local network again on every
                    // flush, since a peer may have joined the WiFi since the
                    // last attempt.
                    if let Some(mesh) = &self.mesh {
                        mesh.send(
                            &peer_pk,
                            &queued.message_id,
                            &queued.content,
                            queued.reply_to.clone(),
                            queued.queued_at,
                        )
                        .await;
                    }
                    if matches!(
                        self.outbox
                            .record_attempt(&queued.peer_npub, &queued.message_id),
                        AttemptOutcome::Exhausted
                    ) {
                        self.mark_status(
                            &queued.peer_npub,
                            std::slice::from_ref(&queued.message_id),
                            STATUS_FAILED,
                        );
                    }
                }
            }
        }

        if let Some(store) = &self.store {
            persist_outbox(store, &self.outbox);
        }
        Ok(sent)
    }

    /// Broadcast a Chitthi that is **not** signed by this device's identity.
    ///
    /// `scope` picks the privacy shape (adopted from bitchat's per-geohash
    /// identities, and the missing piece `AUDIT.md` §8 flagged for the anonymous
    /// thoughts pillar):
    ///  • `None` — a throwaway key per post. Two anonymous Chitthis cannot be
    ///    linked to each other, let alone to you.
    ///  • `Some(label)` — a stable persona derived from the device seed for that
    ///    label, so replies can reach the same pseudonym while it stays
    ///    unlinkable to your identity and to your other personas.
    ///
    /// The post is cached locally under its throwaway key so it appears in your
    /// own timeline. Nothing links it to the identity on any relay; the network
    /// layer (IP, timing) is a separate exposure this cannot close — see the
    /// engine docs on [`SabhaEngine::broadcast_anonymous_chitthi`].
    pub async fn broadcast_anonymous_chitthi(
        &self,
        content: &str,
        scope: Option<&str>,
    ) -> Result<String, UiError> {
        if content.trim().is_empty() {
            return Err(UiError::Engine("chitthi is empty".into()));
        }
        let sabha = self.sabha.clone().ok_or(UiError::VaultLocked)?;
        let store = self.store.clone().ok_or(UiError::VaultLocked)?;

        let signer = match scope {
            Some(label) => {
                let seed = load_or_create_device_seed(&store)?;
                anon::derive_scoped(&seed, anon::SCOPE_CHITTHI, label)
                    .map_err(|e| UiError::Engine(e.to_string()))?
            }
            None => anon::ephemeral(),
        };

        let id = sabha
            .broadcast_anonymous_chitthi(content, &signer)
            .await
            .map_err(|e| UiError::Engine(e.to_string()))?;

        let author_npub = signer
            .public_key()
            .to_bech32()
            .unwrap_or_else(|_| signer.public_key().to_hex());
        let row = comrade_storage::Chitthi {
            id: id.to_hex(),
            author_npub,
            content: content.to_string(),
            created_at: now_secs(),
            reply_to: None,
        };
        if let Err(e) = store.cache_chitthi(&row).and_then(|()| store.flush()) {
            warn!("failed to cache anonymous chitthi: {e}");
        }
        Ok(id.to_hex())
    }

    /// Persist a status change and announce it on the event bus.
    fn mark_status(&self, peer: &str, message_ids: &[String], status: &str) {
        if let Some(store) = &self.store {
            for id in message_ids {
                let _ = store.set_message_status(id, status);
            }
            let _ = store.flush();
        }
        let _ = self.events.send(BridgeEvent::MessageStatus {
            peer: peer.to_string(),
            message_ids: message_ids.to_vec(),
            status: status.to_string(),
        });
    }

    /// Which conversation a stored message belongs to, for a status update whose
    /// only handle is the message id.
    fn peer_of_stored(&self, message_id: &str) -> Option<String> {
        self.store
            .as_ref()?
            .get_message(message_id)
            .ok()
            .flatten()
            .map(|m| m.peer_npub)
    }

    /// Replace a queued row's local id with the relay's event id, keeping the
    /// message's place in the conversation.
    fn rekey_stored_message(&self, queued: &QueuedMessage, event_id: &str) {
        let Some(store) = &self.store else { return };
        let created_at = store
            .get_message(&queued.message_id)
            .ok()
            .flatten()
            .map(|m| m.created_at)
            .unwrap_or(queued.queued_at);
        let row = comrade_storage::StoredMessage {
            id: event_id.to_string(),
            peer_npub: queued.peer_npub.clone(),
            content: queued.content.clone(),
            created_at,
            outgoing: true,
            status: Some("sent".into()),
            reply_to: queued.reply_to.clone(),
        };
        let result = store
            .save_message(&row)
            .and_then(|()| store.remove_message(&queued.message_id).map(|_| ()))
            .and_then(|()| store.flush());
        if let Err(e) = result {
            warn!("failed to re-key a flushed message: {e}");
        }
    }

    /// Announce presence to every comrade, one gift-wrapped beacon each, and
    /// return how many were accepted by a relay.
    ///
    /// Never fails loudly: presence is a courtesy, and a relay hiccup must
    /// not surface as an error in a UI that only ever calls this in the
    /// background. Sends are sequential — the comrade list is a handful of
    /// people by design, and a burst of parallel gift wraps buys nothing.
    pub async fn announce_presence(&self, online: bool) -> u64 {
        let Some(vault) = self.vault.clone() else {
            return 0;
        };
        let Some(store) = self.store.as_deref() else {
            return 0;
        };
        let peers = comrade_peers(store);
        if peers.is_empty() {
            return 0;
        }
        let beacon = if online {
            PresenceBeacon::online()
        } else {
            PresenceBeacon::offline()
        };
        let Ok(json) = beacon.to_json() else {
            return 0;
        };
        let mut sent = 0u64;
        for peer in peers {
            match vault.send_dm(&peer, &json).await {
                Ok(_) => sent += 1,
                Err(e) => tracing::debug!(%peer, "presence beacon not sent: {e}"),
            }
        }
        sent
    }

    /// Send the nudge for every draft that has now been abandoned long enough
    /// to mean it — the sending half of [`comrade_core::nudge`]. Called on the
    /// presence sweep; returns how many nudges went out (0 whenever the
    /// feature is idle, which is almost always).
    ///
    /// Three gates, in this order, and each is load-bearing:
    ///  * **[`DraftWatch::due`] decides**, so every timing rule lives in one
    ///    tested place and the cooldown is recorded at the moment we decide —
    ///    a relay that refuses the DM does not earn the writer a second nudge.
    ///  * **Only comrades are told.** Marking someone is what consents to
    ///    disclosing anything about being at your phone; without it there is
    ///    nothing to send and no one to send it to.
    ///  * **The store read happens here, not on the keystroke.** Watching a
    ///    composer must stay free, and the comrade flag can change between
    ///    typing and sending anyway — so the answer that counts is the one at
    ///    send time.
    ///
    /// Never fails: a locked vault, a peer un-chosen in the meantime, or an
    /// unreachable relay all just mean nothing is sent.
    ///
    /// `now` is passed in rather than read here so a test can stand on the far
    /// side of [`comrade_core::nudge::NUDGE_SETTLE_SECS`] without sleeping
    /// through it — the same
    /// injectable-clock treatment the presence labels get. Production has one
    /// call site, and it passes the real clock.
    pub async fn nudge_abandoned_drafts(&self, now: u64) -> u64 {
        // Take the decisions out from under the mutex *before* any await —
        // `due` hands back owned keys for exactly this reason.
        let peers = self.draft_watch.due(now);
        if peers.is_empty() {
            return 0;
        }
        let (Some(vault), Some(store)) = (self.vault.clone(), self.store.as_deref()) else {
            return 0;
        };
        let Ok(json) = Nudge::new().to_json() else {
            return 0;
        };
        // Every store read happens here, before the first await — the same
        // shape [`Self::announce_presence`] uses, so the encrypted store is
        // never being read between two relay round-trips.
        let recipients: Vec<PublicKey> = peers
            .into_iter()
            .filter(|npub| {
                store
                    .get_contact(npub)
                    .ok()
                    .flatten()
                    .is_some_and(|c| c.comrade)
            })
            .filter_map(|npub| PublicKey::parse(&npub).ok())
            .collect();

        let mut sent = 0u64;
        for peer in recipients {
            match vault.send_dm(&peer, &json).await {
                Ok(_) => sent += 1,
                Err(e) => tracing::debug!(%peer, "nudge not sent: {e}"),
            }
        }
        sent
    }

    pub async fn broadcast_chitthi(
        &self,
        content: &str,
        reply_to: Option<String>,
    ) -> Result<String, UiError> {
        let sabha = self.sabha.clone().ok_or(UiError::VaultLocked)?;

        let parent = match reply_to.as_deref() {
            Some(hex) => Some(
                EventId::from_hex(hex)
                    .map_err(|e| UiError::Engine(format!("invalid reply_to id: {e}")))?,
            ),
            None => None,
        };

        let id = sabha
            .broadcast_chitthi_reply(content, parent)
            .await
            .map_err(|e| UiError::Engine(e.to_string()))?;

        // Best-effort: persist our own Chitthi to the encrypted cache so it
        // shows up in the offline timeline immediately.
        if let Some(store) = &self.store {
            let row = comrade_storage::Chitthi {
                id: id.to_hex(),
                author_npub: self
                    .identity
                    .as_ref()
                    .map(|i| i.npub.clone())
                    .unwrap_or_default(),
                content: content.to_string(),
                created_at: now_secs(),
                reply_to,
            };
            if let Err(e) = store.cache_chitthi(&row).and_then(|()| store.flush()) {
                warn!("failed to cache outgoing chitthi: {e}");
            }
        }

        Ok(id.to_hex())
    }

    pub async fn send_call_signal(
        &self,
        peer: &str,
        call_id: &str,
        media: &str,
        signal_json: &str,
    ) -> Result<(), UiError> {
        let vault = self.vault.clone().ok_or(UiError::VaultLocked)?;
        let peer_pk = parse_pubkey(peer)?;
        let signal: CallSignal = serde_json::from_str(signal_json)
            .map_err(|e| UiError::Engine(format!("invalid call signal: {e}")))?;
        let env = CallEnvelope::new(
            call_id.to_string(),
            CallMediaKind::from_str_lenient(media),
            signal,
        );
        let json = env.to_json().map_err(|e| UiError::Engine(e.to_string()))?;
        vault
            .send_dm(&peer_pk, &json)
            .await
            .map_err(|e| UiError::Engine(e.to_string()))?;
        Ok(())
    }

    pub async fn hangup_call(
        &self,
        peer: &str,
        call_id: &str,
        media: &str,
        reason: &str,
    ) -> Result<(), UiError> {
        let signal = CallSignal::Hangup {
            reason: HangupReason::from_str_lenient(reason),
        };
        let json = serde_json::to_string(&signal).map_err(|e| UiError::Engine(e.to_string()))?;
        self.send_call_signal(peer, call_id, media, &json).await
    }

    pub async fn upload_and_send_media(
        &self,
        target_pubkey: &str,
        bytes: Vec<u8>,
        mime_type: &str,
        caption: &str,
    ) -> Result<MediaMessageDto, UiError> {
        if bytes.len() > MAX_MEDIA_BYTES {
            return Err(UiError::Engine(format!(
                "media is {} bytes; the limit is {MAX_MEDIA_BYTES}",
                bytes.len()
            )));
        }
        // Fail before the upload, not after it: a zero-byte blob is a picker or
        // permission failure on the frontend's side (a content URI that could
        // not be read yields exactly this), and sending it would cost a real
        // upload and put an undecodable bubble in both people's threads.
        if bytes.is_empty() {
            return Err(UiError::Engine("attachment is empty".into()));
        }
        let mime_type = validate_mime_type(mime_type)?;
        let mime_type = mime_type.as_str();
        let caption = sanitise_untrusted_text(caption, MAX_CAPTION_LEN);
        let caption = caption.as_str();
        // The vault is needed to deliver the reference — check before uploading
        // so a locked vault never leaves a paid-for blob on a host with nothing
        // pointing at it.
        let vault = self.vault.clone().ok_or(UiError::VaultLocked)?;
        let keys = self.keys.clone().ok_or(UiError::NoIdentity)?;
        let peer = parse_pubkey(target_pubkey)?;
        let key = derive_media_key(keys.secret_key(), &peer, MEDIA_LABEL)
            .map_err(|e| UiError::Engine(e.to_string()))?;

        let (media, _secret) =
            encrypt_media(&bytes, mime_type, &key).map_err(|e| UiError::Engine(e.to_string()))?;
        let size = media.size as u64;
        let sha256_hex = media.sha256_hex.clone();

        // Upload ciphertext only — the host sees opaque bytes, and is *told*
        // opaque bytes. This used to send the plaintext's own MIME type as the
        // upload's `Content-Type`, which contradicted the sentence above: it
        // told the media host whether the user had just sent a photo, a voice
        // note or a PDF, when the body it was describing is AES-GCM ciphertext
        // and could not be any of them. The real type travels inside the
        // encrypted DM envelope, which is where the recipient reads it.
        let url = upload_blob(media.ciphertext, OPAQUE_UPLOAD_MIME).await?;

        // Zero-knowledge NIP-94 event: URL + ciphertext hash, no key, no `ox`.
        let meta = FileMetadata {
            url: url.clone(),
            mime_type: mime_type.to_string(),
            sha256_hex,
            original_sha256_hex: None,
            size: Some(media.size),
            caption: caption.to_string(),
        };
        let event =
            build_file_metadata_event(&keys, &meta).map_err(|e| UiError::Engine(e.to_string()))?;
        let event_id = event.id.to_hex();
        let created_at = now_secs();

        // Persist a local ref so download_and_decrypt_media(event_id) resolves.
        let reff = MediaRef {
            event_id: event_id.clone(),
            url: url.clone(),
            peer_pubkey: peer.to_hex(),
            mime_type: mime_type.to_string(),
            caption: caption.to_string(),
            size,
            sha256_hex: media.sha256_hex.clone(),
            outgoing: true,
            created_at,
        };
        if let Some(store) = &self.store {
            store
                .put(MEDIA_REFS_TREE, &event_id, &reff)
                .and_then(|()| store.flush())
                .map_err(|e| UiError::Storage(e.to_string()))?;
        }

        // Privately deliver the reference over the E2E DM channel.
        let envelope = MediaEnvelope {
            comrade_media: 1,
            event_id: event_id.clone(),
            url: url.clone(),
            mime: mime_type.to_string(),
            caption: caption.to_string(),
            size,
            sha256_hex: media.sha256_hex.clone(),
        };
        let envelope_json =
            serde_json::to_string(&envelope).map_err(|e| UiError::Engine(e.to_string()))?;

        // A relay that will not take the reference must not orphan the upload.
        // Before this, a publish failure returned an error *after* the blob was
        // uploaded and the local ref persisted: the sender saw a failure, saw
        // the attachment in their own thread anyway, and the recipient never
        // learned the blob existed — with nothing left to retry from. The
        // reference is ordinary DM content, so it queues in the same outbox as
        // text (`docs/BITCHAT_ADOPTION.md` store-and-forward) keyed by its
        // NIP-94 event id, and the flush loop delivers it when a relay returns.
        let peer_npub = to_npub(target_pubkey);
        if let Err(e) = vault.send_dm(&peer, &envelope_json).await {
            tracing::info!(error = %e, "media reference could not be published — queued for retry");
            let queued = QueuedMessage::new(
                event_id.clone(),
                peer_npub.clone(),
                &envelope_json,
                None,
                created_at,
            );
            if let QueueOutcome::Displaced(dropped) = self.outbox.queue(queued) {
                self.mark_status(&peer_npub, &[dropped], STATUS_FAILED);
            }
            if let Some(store) = &self.store {
                persist_outbox(store, &self.outbox);
            }
            // Not a lie about delivery: `queued` is the same status a text DM
            // gets on the same failure, and it is keyed by the media event id so
            // the flush loop's later "sent" names the same handle the UI holds.
            self.mark_status(&peer_npub, std::slice::from_ref(&event_id), STATUS_QUEUED);
        }

        // An attachment reaches them just as a message does, so it settles the
        // same debt — see the same call in [`Self::send_dm_reply`]. Any text
        // still in the composer starts its own clock again the next time they
        // touch it.
        self.draft_watch.sent(&peer_npub);

        let sender = keys
            .public_key()
            .to_bech32()
            .unwrap_or_else(|_| keys.public_key().to_hex());
        Ok(MediaMessageDto {
            event_id,
            url,
            mime_type: mime_type.to_string(),
            caption: caption.to_string(),
            sender,
            created_at,
            size,
            outgoing: true,
        })
    }

    pub async fn download_and_decrypt_media(
        &self,
        event_id: &str,
    ) -> Result<MediaBytesDto, UiError> {
        let store = self.store.as_deref().ok_or(UiError::VaultLocked)?;
        let reff: MediaRef = store
            .get(MEDIA_REFS_TREE, event_id)
            .map_err(|e| UiError::Storage(e.to_string()))?
            .ok_or_else(|| UiError::Engine(format!("unknown media event {event_id}")))?;

        let keys = self.keys.clone().ok_or(UiError::NoIdentity)?;
        let peer = parse_pubkey(&reff.peer_pubkey)?;
        let key = derive_media_key(keys.secret_key(), &peer, MEDIA_LABEL)
            .map_err(|e| UiError::Engine(e.to_string()))?;

        // Verify the ciphertext hash when we recorded one (fail fast on a
        // wrong/tampered blob; older refs without it fall back to the AES-GCM
        // tag alone, which still rejects tampering).
        let expected = (!reff.sha256_hex.is_empty()).then_some(reff.sha256_hex.as_str());
        let bytes = fetch_and_decrypt_media(&reff.url, &key, expected)
            .await
            .map_err(|e| UiError::Engine(e.to_string()))?;

        Ok(MediaBytesDto {
            mime_type: reff.mime_type,
            base64: B64.encode(&bytes),
        })
    }

    pub async fn search_profiles(&self, query: &str) -> Result<Vec<FoundProfileDto>, UiError> {
        let sabha = self.sabha.clone().ok_or(UiError::VaultLocked)?;
        let query = query.trim().trim_start_matches('@');
        if query.is_empty() {
            return Ok(vec![]);
        }

        // Exact-key lookup: fetch that author's Kind-0 (name may be absent —
        // the key alone is still a valid, addressable result). Otherwise a
        // NIP-50 name search. Both branches share the DTO mapping and cache.
        let dtos: Vec<FoundProfileDto> = if let Ok(pk) = PublicKey::parse(query) {
            let meta = sabha
                .fetch_profile(&pk)
                .await
                .map_err(|e| UiError::Engine(e.to_string()))?;
            vec![found_profile_dto(&pk, meta.as_ref())]
        } else {
            sabha
                .search_profiles(query, 10)
                .await
                .map_err(|e| UiError::Engine(e.to_string()))?
                .into_iter()
                .map(|(pk, meta)| found_profile_dto(&pk, Some(&meta)))
                .collect()
        };
        cache_found_profiles(self.store.as_deref(), &dtos);
        Ok(dtos)
    }

    pub async fn sync_ledger(&self) -> Result<String, UiError> {
        let sakha = self.sakha.clone().ok_or(UiError::VaultLocked)?;
        let id = sakha
            .publish_sync()
            .await
            .map_err(|e| UiError::Engine(e.to_string()))?;
        Ok(id.to_hex())
    }

    pub async fn sakha_add_entry(
        &self,
        description: &str,
        amount_inr: f64,
        paid_by: &str,
    ) -> Result<String, UiError> {
        if description.trim().is_empty() {
            return Err(UiError::Engine("description is empty".into()));
        }
        let sakha = self.sakha.clone().ok_or(UiError::VaultLocked)?;
        if !sakha.is_paired() {
            return Err(UiError::Engine(
                "not paired with a partner yet — open the Partner Portal first".into(),
            ));
        }
        let entry = LedgerEntry::new(description, amount_inr, paid_by);
        sakha
            .add_entry(entry)
            .await
            .map_err(|e| UiError::Engine(e.to_string()))?;
        let ledger = sakha.read_ledger().await;
        if let Some(store) = &self.store {
            persist_ledger_snapshot(store, &sakha).await;
        }
        Ok(ledger)
    }
}

/// Upload an encrypted blob to Blossom, signed with a BUD-01 auth event.
/// Gated on the `media-http` feature; degrades to a typed error otherwise.
///
/// The BUD-01 auth event is signed with a **fresh ephemeral key**, never the
/// user's chat identity: the blob is already zero-knowledge, and signing
/// with the identity key would let the host link "npub X uploaded blob Y at
/// time T from IP Z" — a metadata leak at odds with the privacy model. Free
/// function (not a `ComradeRuntime`/`RuntimeHandles` method) since it needs
/// no engine/store state at all.
#[cfg(feature = "media-http")]
async fn upload_blob(blob: Vec<u8>, mime: &str) -> Result<String, UiError> {
    use comrade_core::media::{BlossomUploader, MediaUploader, DEFAULT_BLOSSOM_SERVERS};
    // Every default host, in order, not one: media sharing was broken outright
    // for as long as the single hard-coded host was refusing connections, and
    // no frontend could route around it.
    let uploader = BlossomUploader::with_servers(
        DEFAULT_BLOSSOM_SERVERS.iter().copied(),
        nostr_sdk::Keys::generate(),
    );
    let receipt = uploader
        .upload(&blob, mime)
        .await
        .map_err(|e| UiError::Engine(e.to_string()))?;
    Ok(receipt.url)
}

#[cfg(not(feature = "media-http"))]
async fn upload_blob(_blob: Vec<u8>, _mime: &str) -> Result<String, UiError> {
    Err(UiError::Engine(
        "media upload requires the `media-http` feature".into(),
    ))
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or_default()
}

/// Normalise a pairing-role string to exactly `"sakha"` or `"sakhi"` —
/// anything else (including case variants) falls back to `"sakha"`, mirroring
/// the lenient `from_str_lenient` pattern already used for `CallMediaKind`/
/// `HangupReason` elsewhere in this bridge.
fn normalize_pair_role(role: &str) -> String {
    if role.eq_ignore_ascii_case("sakhi") {
        "sakhi".to_string()
    } else {
        "sakha".to_string()
    }
}

/// Snapshot the Sakha CRDT doc and persist it, so the ledger survives a
/// restart without needing a fresh sync from the partner. Best-effort: a
/// write failure is logged, not propagated — losing a snapshot write is far
/// less bad than failing the ledger update that triggered it.
async fn persist_ledger_snapshot(store: &comrade_storage::EncryptedStore, sakha: &SakhaEngine) {
    let bytes = sakha.snapshot_bytes().await;
    let state = comrade_storage::LedgerState {
        snapshot: bytes,
        updated_at: now_secs(),
    };
    if let Err(e) = store.save_ledger_state(&state).and_then(|()| store.flush()) {
        warn!("failed to persist Sakha ledger snapshot: {e}");
    }
}

/// A stored usage day as the attention engine reads it (rollup numbers only).
fn usage_signal(day: &comrade_storage::AttentionDay) -> UsageSignal {
    UsageSignal {
        screen_minutes: day.screen_minutes,
        doom_minutes: day.doom_minutes,
        pickups: day.pickups,
    }
}

/// A stored focus session as a frontend sees it, with the live countdown
/// filled in (0 once it has ended).
fn focus_dto(session: comrade_storage::FocusSession, now: u64) -> FocusSessionDto {
    let remaining = if session.outcome.is_some() {
        0
    } else {
        attention::remaining_secs(session.planned_minutes, session.started_at, now)
    };
    FocusSessionDto {
        id: session.id,
        intent: session.intent,
        planned_minutes: session.planned_minutes,
        started_at: session.started_at,
        ended_at: session.ended_at,
        outcome: session.outcome,
        remaining_secs: remaining,
    }
}

/// The saved long-read, chunked, with its position clamped into range — a
/// stored position must never be able to point past the end of the text.
fn reading_dto(state: comrade_storage::ReadingState) -> ReadingDto {
    let chunks = attention::chunk_reading(&state.text);
    let last = chunks.len().saturating_sub(1) as u32;
    ReadingDto {
        title: state.title,
        chunks,
        position: state.position.min(last),
    }
}

/// Whether `s` looks like `YYYY-MM-DD`. Deliberately a shape check, not a
/// calendar check: the frontend owns the timezone and the calendar, and this
/// crate only needs keys that sort chronologically.
fn is_iso_date(s: &str) -> bool {
    let bytes = s.as_bytes();
    bytes.len() == 10
        && bytes[4] == b'-'
        && bytes[7] == b'-'
        && bytes
            .iter()
            .enumerate()
            .all(|(i, b)| matches!(i, 4 | 7) || b.is_ascii_digit())
}

/// `YYYY-MM-DD` for a unix timestamp in **UTC**.
///
/// Used only to answer "is the newest recorded row still today's?" for the
/// Tara nudge. Every stored date comes from the frontend, which knows the
/// device's real timezone; the worst this approximation can do is delay (or
/// briefly advance) one journaling nudge by hours near midnight, which is why
/// a timezone database is not pulled in for it.
fn iso_date(unix_secs: u64) -> String {
    // Days since the Unix epoch → civil date (Howard Hinnant's algorithm).
    let days = (unix_secs / 86_400) as i64;
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    format!("{y:04}-{m:02}-{d:02}")
}

/// Store key for a local-only record (journal entry, Tara turn): a zero-padded
/// timestamp prefix (so ids sort chronologically) plus a random tail (so two
/// records in the same second never collide). The randomness comes from a
/// throwaway secp256k1 key — no extra dependency, and cryptographically
/// unpredictable.
fn timestamped_store_id(created_at: u64) -> String {
    let tail = nostr_sdk::Keys::generate().public_key().to_hex();
    format!("{created_at:020}-{}", &tail[..12])
}

/// The peer's published @handle from the local profile cache, if known.
fn cached_peer_name(store: &comrade_storage::EncryptedStore, npub: &str) -> Option<String> {
    store
        .get::<PeerProfileRecord>(PEER_PROFILES_TREE, npub)
        .ok()
        .flatten()
        .and_then(|r| r.name)
}

/// Legacy builds auto-filled an empty alias with the first 12 characters of
/// the peer's npub. Normalise those placeholders (and blanks) to "no alias"
/// so the peer's published handle can title the chat — otherwise every
/// pre-existing key-added contact is stuck displaying `npub1abcdefg` forever.
const LEGACY_PLACEHOLDER_LEN: usize = 12;

fn user_alias(petname: &str, npub: &str) -> Option<String> {
    let trimmed = petname.trim();
    if trimmed.is_empty() {
        return None;
    }
    if trimmed.len() == LEGACY_PLACEHOLDER_LEN && npub.starts_with(trimmed) {
        return None;
    }
    Some(trimmed.to_string())
}

/// Map a fetched Kind-0 (or its absence) to the search-result DTO. One
/// mapping for both the direct-key branch and the name-search branch, so the
/// two can never render the same profile differently.
fn found_profile_dto(pk: &PublicKey, meta: Option<&Metadata>) -> FoundProfileDto {
    FoundProfileDto {
        npub: pk.to_bech32().unwrap_or_else(|_| pk.to_hex()),
        name: meta.and_then(display_name_of),
        about: meta.and_then(|m| m.about.clone()),
    }
}

/// Best-effort single-record write into the profile cache; returns whether
/// the write succeeded. Callers flush once per batch.
fn store_profile_record(
    store: &comrade_storage::EncryptedStore,
    npub: &str,
    record: &PeerProfileRecord,
) -> bool {
    match store.put(PEER_PROFILES_TREE, npub, record) {
        Ok(()) => true,
        Err(e) => {
            warn!("failed to cache peer profile: {e}");
            false
        }
    }
}

/// Persist discovered profiles into the local cache (best-effort) so the
/// chat list can title peers by their handle without another fetch. Free
/// function so [`RuntimeHandles::search_profiles`] can call it with no
/// runtime lock held.
fn cache_found_profiles(
    store: Option<&comrade_storage::EncryptedStore>,
    found: &[FoundProfileDto],
) {
    let Some(store) = store else {
        return;
    };
    let now = now_secs();
    let mut wrote = false;
    for profile in found {
        if profile.name.is_none() {
            continue; // nothing displayable; don't shadow a future fetch
        }
        let record = PeerProfileRecord {
            name: profile.name.clone(),
            about: profile.about.clone(),
            updated_at: now,
        };
        wrote |= store_profile_record(store, &profile.npub, &record);
    }
    if wrote {
        if let Err(e) = store.flush() {
            warn!("failed to flush profile cache: {e}");
        }
    }
}

/// A user-configured TURN relay for WebRTC calls, sealed in the encrypted store.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct TurnConfig {
    url: String,
    #[serde(default)]
    username: String,
    #[serde(default)]
    credential: String,
}

/// The conversation gate for an incoming DM's sender.
enum IncomingGate {
    /// Peer is blocked — drop everything from them silently.
    Blocked,
    /// Peer is an established conversation — deliver normally + ack.
    Accepted,
    /// Peer is a stranger (or an unaccepted request) — route to requests.
    Pending,
}

/// Classify an incoming DM's sender against the conversation gate. A peer with
/// no gate record is treated as `Pending` (a new stranger); [`send_dm`] and
/// [`accept_request`] are what flip a peer to `Accepted`.
///
/// [`send_dm`]: ComradeRuntime::send_dm
/// [`accept_request`]: ComradeRuntime::accept_request
fn conversation_gate(store: &comrade_storage::EncryptedStore, peer_npub: &str) -> IncomingGate {
    match store.get_conversation_meta(peer_npub).ok().flatten() {
        Some(m) if m.state == STATE_BLOCKED => IncomingGate::Blocked,
        Some(m) if m.state == STATE_ACCEPTED => IncomingGate::Accepted,
        _ => IncomingGate::Pending,
    }
}

/// Record a peer as a pending request if they have no gate record yet.
fn ensure_pending(store: Option<&Arc<comrade_storage::EncryptedStore>>, peer_npub: &str) {
    let Some(store) = store else { return };
    if store
        .get_conversation_meta(peer_npub)
        .ok()
        .flatten()
        .is_none()
    {
        let meta = comrade_storage::ConversationMeta {
            peer_npub: peer_npub.to_string(),
            state: STATE_PENDING.to_string(),
            profile_shared: false,
            // Only reached when no record exists, so there is nothing read yet.
            last_read_at: 0,
            updated_at: now_secs(),
        };
        if let Err(e) = store
            .set_conversation_meta(&meta)
            .and_then(|()| store.flush())
        {
            warn!("failed to record message request: {e}");
        }
    }
}

/// Cache a peer's shared display handle (from a profile-share envelope).
fn cache_pushed_peer_name(store: &comrade_storage::EncryptedStore, npub: &str, name: &str) {
    let record = PeerProfileRecord {
        name: Some(name.to_string()),
        about: None,
        updated_at: now_secs(),
    };
    if store_profile_record(store, npub, &record) {
        let _ = store.flush();
    }
}

/// Record `peer_npub`'s conversation as accepted and, once, share our
/// @handle with them over the encrypted channel. Free function (rather than
/// a `ComradeRuntime` method) so it can run from [`RuntimeHandles::send_dm_reply`]
/// with no runtime lock held, as well as from [`ComradeRuntime::accept_request`].
///
/// The share itself runs in the background so the caller is never blocked on
/// the network; the `profile_shared` flag flips only on a successful send,
/// so a failed share retries next time.
fn share_profile_on_accept(
    store: Option<Arc<comrade_storage::EncryptedStore>>,
    vault: Option<Arc<VaultEngine>>,
    username: Option<String>,
    peer_npub: &str,
    peer: &PublicKey,
) {
    let Some(store) = store else {
        return;
    };
    let existing = store.get_conversation_meta(peer_npub).ok().flatten();
    let already_shared = existing.as_ref().map(|m| m.profile_shared).unwrap_or(false);
    let meta = comrade_storage::ConversationMeta {
        peer_npub: peer_npub.to_string(),
        state: STATE_ACCEPTED.to_string(),
        profile_shared: already_shared,
        // Accepting rewrites the record, so the position has to be carried or
        // the freshly accepted thread would forget what had been read.
        last_read_at: existing.as_ref().map(|m| m.last_read_at).unwrap_or(0),
        updated_at: now_secs(),
    };
    if let Err(e) = store
        .set_conversation_meta(&meta)
        .and_then(|()| store.flush())
    {
        warn!("failed to record accepted conversation: {e}");
    }
    if already_shared {
        return;
    }
    let Some(vault) = vault else {
        return;
    };
    let peer = *peer;
    let peer_npub = peer_npub.to_string();
    tokio::spawn(async move {
        let Ok(json) = ProfileShare::new(username).to_json() else {
            return;
        };
        if vault.send_dm(&peer, &json).await.is_ok() {
            // Re-read rather than reuse the value from before the await: the
            // send is a relay round-trip, and the thread may well have been
            // opened and read in the meantime.
            let last_read_at = store.read_position(&peer_npub).unwrap_or(0);
            let meta = comrade_storage::ConversationMeta {
                peer_npub,
                state: STATE_ACCEPTED.to_string(),
                profile_shared: true,
                last_read_at,
                updated_at: now_secs(),
            };
            let _ = store
                .set_conversation_meta(&meta)
                .and_then(|()| store.flush());
        }
    });
}

// ── Comrade presence (see `comrade_core::presence` for the wire protocol) ────

/// The public keys of every contact marked as a comrade. Unparseable keys are
/// skipped rather than failing the whole fan-out — one corrupt row must not
/// silence presence for everyone else.
fn comrade_peers(store: &comrade_storage::EncryptedStore) -> Vec<PublicKey> {
    store
        .list_comrades()
        .unwrap_or_default()
        .iter()
        .filter_map(|c| PublicKey::parse(&c.npub).ok())
        .collect()
}

/// Fire-and-forget one `beacon` to each of `peers` over the encrypted DM
/// channel. Holds nothing but the vault engine, so it is safe to call
/// immediately before tearing the runtime down (see
/// [`ComradeRuntime::spawn_farewell_beacons`]).
fn spawn_presence_beacons(
    vault: Option<Arc<VaultEngine>>,
    peers: Vec<PublicKey>,
    beacon: PresenceBeacon,
) {
    let Some(vault) = vault else { return };
    if peers.is_empty() {
        return;
    }
    let Ok(json) = beacon.to_json() else { return };
    tokio::spawn(async move {
        for peer in peers {
            if let Err(e) = vault.send_dm(&peer, &json).await {
                tracing::debug!(%peer, "presence beacon not sent: {e}");
            }
        }
    });
}

/// Whether `peer`'s last beacon still claims them online at `now`. The one
/// read path for stored presence, so an expired claim can never render as a
/// green dot just because no sweep has run yet.
fn peer_is_online(store: &comrade_storage::EncryptedStore, peer_npub: &str, now: u64) -> bool {
    store
        .get_peer_presence(peer_npub)
        .ok()
        .flatten()
        .is_some_and(|p| p.online && is_online_at(p.expires_at, now))
}

/// Age out comrades whose "online" claim has lapsed, emitting one
/// [`BridgeEvent::ComradePresence`] per transition so a UI dot goes grey
/// without the peer having to send anything. This is the half of presence
/// that handles the common, silent case: a phone that runs out of battery,
/// loses signal, or is force-killed never sends a goodbye.
fn expire_stale_presence(
    store: Option<&comrade_storage::EncryptedStore>,
    tx: &broadcast::Sender<BridgeEvent>,
) {
    let Some(store) = store else { return };
    let now = now_secs();
    let mut wrote = false;
    for mut row in store.list_peer_presence().unwrap_or_default() {
        if !row.online || is_online_at(row.expires_at, now) {
            continue;
        }
        let expired_at = row.expires_at;
        row.online = false;
        if let Err(e) = store.set_peer_presence(&row) {
            warn!("failed to expire stale presence: {e}");
            continue;
        }
        wrote = true;
        // Only *our* comrades are news; a lapsed row for someone we have
        // since un-chosen is quietly cleaned up, not announced.
        if store
            .get_contact(&row.peer_npub)
            .ok()
            .flatten()
            .is_some_and(|c| c.comrade)
        {
            let _ = tx.send(BridgeEvent::ComradePresence {
                name: presence_display_name(store, &row.peer_npub),
                peer: row.peer_npub,
                online: false,
                at: expired_at,
            });
        }
    }
    if wrote {
        if let Err(e) = store.flush() {
            warn!("failed to flush expired presence: {e}");
        }
    }
}

/// How a peer should be titled in a presence notification: the alias the user
/// gave them, else the handle they published, else nothing (the frontend
/// falls back to the shortened key, exactly as the chat list does).
fn presence_display_name(
    store: &comrade_storage::EncryptedStore,
    peer_npub: &str,
) -> Option<String> {
    store
        .get_contact(peer_npub)
        .ok()
        .flatten()
        .and_then(|c| user_alias(&c.petname, peer_npub))
        .or_else(|| cached_peer_name(store, peer_npub))
        // A peer's published handle is *their* string: a whitespace-only one
        // would otherwise reach a frontend as "a name", and a notification
        // titled " is online" helps nobody. `None` lets every frontend fall
        // back to the key, which is always meaningful.
        .map(|name| name.trim().to_string())
        .filter(|name| !name.is_empty())
}

/// Apply one incoming [`PresenceBeacon`] from an accepted conversation:
/// persist what it claims, emit an event on a real transition, and answer a
/// comrade's fresh "I'm here" so they don't wait a heartbeat to see us.
///
/// Three rules keep this honest:
///  * **Expired beacons are ignored entirely.** Relays redeliver
///    at-least-once and the inbox backfills days on every launch — a replayed
///    beacon must never light a green dot (`presence_expires_at` measures
///    from the *send* time, so an old one is already spent).
///  * **Only transitions are events.** A heartbeat from a peer already known
///    to be online is state, not news, so it never re-notifies.
///  * **Only chosen comrades are announced.** A beacon from someone we
///    haven't marked is still recorded — it is the proof that *they* marked
///    us, which is what makes the mutual model discoverable — but it raises
///    nothing, and is never answered.
fn handle_presence_beacon(
    vault: &Arc<VaultEngine>,
    store: Option<&Arc<comrade_storage::EncryptedStore>>,
    tx: &broadcast::Sender<BridgeEvent>,
    peer_npub: &str,
    sender_hex: &str,
    created_at: u64,
    beacon: PresenceBeacon,
) {
    let Some(store) = store else { return };
    let now = now_secs();
    let expires_at = presence_expires_at(beacon.state, created_at, beacon.ttl_secs);
    let online = beacon.state.is_online() && is_online_at(expires_at, now);
    if beacon.state.is_online() && !online {
        tracing::debug!(peer = %peer_npub, created_at, "dropping stale presence beacon");
        return;
    }

    let previous = store.get_peer_presence(peer_npub).ok().flatten();
    // An out-of-order beacon (relays do not guarantee ordering) must not
    // rewind what a newer one already told us.
    if previous
        .as_ref()
        .is_some_and(|p| p.last_seen_at > created_at)
    {
        tracing::debug!(peer = %peer_npub, created_at, "dropping out-of-order presence beacon");
        return;
    }
    let was_online = previous
        .as_ref()
        .is_some_and(|p| p.online && is_online_at(p.expires_at, now));

    let row = comrade_storage::PeerPresence {
        peer_npub: peer_npub.to_string(),
        online,
        last_seen_at: created_at,
        expires_at,
        // Any beacon at all proves they chose us: beacons only go to comrades.
        peer_marked_us: true,
    };
    if let Err(e) = store.set_peer_presence(&row).and_then(|()| store.flush()) {
        warn!("failed to persist peer presence: {e}");
    }

    let is_our_comrade = store
        .get_contact(peer_npub)
        .ok()
        .flatten()
        .is_some_and(|c| c.comrade);
    if is_our_comrade && online != was_online {
        let _ = tx.send(BridgeEvent::ComradePresence {
            peer: peer_npub.to_string(),
            name: presence_display_name(store, peer_npub),
            online,
            at: created_at,
        });
    }

    // Answer a comrade who has just *arrived* — that is the case the reply
    // exists for, and the only one where it carries information: if we already
    // had them online, our own heartbeat is already keeping their view of us
    // fresh, and answering every heartbeat would double this feature's relay
    // traffic to say nothing new. Only our own comrades are ever answered:
    // replying to a peer we haven't chosen would disclose our presence to
    // someone we never opted into telling.
    if is_our_comrade && beacon.wants_reply() && !was_online {
        if let Ok(peer) = PublicKey::parse(sender_hex) {
            spawn_presence_beacons(
                Some(vault.clone()),
                vec![peer],
                PresenceBeacon::online_reply(),
            );
        }
    }
}

/// Apply one incoming [`Nudge`] from an accepted conversation: raise it, once,
/// if it is fresh and from someone the user chose.
///
/// Four rules, each the receiving-side mirror of something the sender promised:
///  * **Stale nudges raise nothing.** Freshness is measured from the send time
///    ([`nudge_expires_at`]), so a nudge delayed by a slow relay or replayed out
///    of the two-day backfill is already spent. Without this, every launch would
///    re-announce every hesitation of the last two days.
///  * **A redelivered wrapper raises nothing.** Relays deliver at-least-once, so
///    the same nudge can arrive twice inside its own TTL; the shared dedup set
///    (the one the call-signal branch uses) makes the second one silent.
///  * **Only our own comrades are announced.** A nudge from someone we have not
///    marked is dropped, not recorded: unlike a presence beacon — whose
///    arrival is *how* the mutual model becomes discoverable
///    (`peer_marked_us`) — a nudge writes no presence state at all, so it can
///    neither advance a "last seen" nor light a dot from outside the one path
///    that owns them.
///  * **Nothing about the draft is knowable here**, because nothing about it
///    was sent. The event carries a name for the notification and no more.
fn handle_nudge(
    store: Option<&Arc<comrade_storage::EncryptedStore>>,
    tx: &broadcast::Sender<BridgeEvent>,
    dedup: &SeenSet,
    peer_npub: &str,
    event_id: &str,
    created_at: u64,
    nudge: Nudge,
) {
    let Some(store) = store else { return };
    if !is_fresh_at(nudge_expires_at(created_at, nudge.ttl_secs), now_secs()) {
        tracing::debug!(peer = %peer_npub, created_at, "dropping stale nudge");
        return;
    }
    if dedup.already_seen(event_id) {
        tracing::debug!(%event_id, "dropping duplicate nudge");
        return;
    }
    let is_our_comrade = store
        .get_contact(peer_npub)
        .ok()
        .flatten()
        .is_some_and(|c| c.comrade);
    if !is_our_comrade {
        return;
    }
    let _ = tx.send(BridgeEvent::ComradeNudge {
        name: presence_display_name(store, peer_npub),
        peer: peer_npub.to_string(),
    });
}

/// Fire a delivered receipt back to `sender_hex` for `message_id` (best-effort;
/// only ever called for accepted conversations).
fn send_delivered_receipt(
    vault: &Arc<VaultEngine>,
    mesh: Option<&MeshLink>,
    sender_hex: &str,
    message_id: &str,
) {
    let Ok(peer) = PublicKey::parse(sender_hex) else {
        return;
    };
    let Ok(json) = Receipt::new(ReceiptKind::Delivered, vec![message_id.to_string()]).to_json()
    else {
        return;
    };
    let vault = vault.clone();
    let mesh = mesh.cloned();
    let receipt_id = format!("receipt:{message_id}");
    tokio::spawn(async move {
        if let Err(e) = vault.send_dm(&peer, &json).await {
            tracing::debug!("delivered receipt not sent over a relay: {e}");
            // Offline is exactly when the receipt matters most: it is what
            // clears the *sender's* outbox, so without this a message delivered
            // over the mesh would keep being retried until its attempt cap.
            if let Some(mesh) = mesh {
                mesh.send(&peer, &receipt_id, &json, None, now_secs()).await;
            }
        }
    });
}

/// Write the outbox snapshot into the encrypted store. Best-effort: a failure
/// here costs durability across a kill, never the live queue.
fn persist_outbox(store: &Arc<comrade_storage::EncryptedStore>, outbox: &Arc<Outbox>) {
    let snapshot = outbox.snapshot();
    if let Err(e) = store
        .put(OUTBOX_TREE, OUTBOX_KEY, &snapshot)
        .and_then(|()| store.flush())
    {
        warn!("failed to persist the outbox: {e}");
    }
}

/// Load the device anonymity seed, creating and sealing one on first use.
///
/// This is the root secret every anonymous persona is derived from
/// ([`anon::derive_scoped`]). It lives in the encrypted store next to the
/// identity and is destroyed by the panic wipe, after which past personas cannot
/// be regenerated — which is the intent.
fn load_or_create_device_seed(
    store: &Arc<comrade_storage::EncryptedStore>,
) -> Result<anon::DeviceSeed, UiError> {
    if let Some(bytes) = store
        .get_bytes(SETTINGS_TREE, DEVICE_SEED_KEY)
        .map_err(|e| UiError::Storage(e.to_string()))?
    {
        if let Ok(seed) = anon::DeviceSeed::from_slice(&bytes) {
            return Ok(seed);
        }
        // A wrong-length seed means a corrupt record; replacing it is better
        // than deriving personas from truncated key material.
        warn!("stored anonymity seed was malformed — generating a fresh one");
    }
    let seed = anon::DeviceSeed::generate();
    store
        .put_bytes(SETTINGS_TREE, DEVICE_SEED_KEY, seed.as_bytes())
        .and_then(|()| store.flush())
        .map_err(|e| UiError::Storage(e.to_string()))?;
    Ok(seed)
}

// ── Local-network delivery (see docs/OFFLINE_DELIVERY.md) ────────────────────

/// A handle for putting sealed mail onto the local network.
///
/// Wraps the running mesh engine plus our keys, which is everything needed to
/// seal a DM for a peer and flood it to whoever is on this WiFi. Cloneable and
/// cheap, so a fire-and-forget task can own one.
#[derive(Clone)]
struct MeshLink {
    engine: Arc<SaathiEngine>,
    keys: nostr_sdk::Keys,
}

impl MeshLink {
    /// Seal a DM for `peer` and publish it to the local mesh. Returns whether
    /// the mesh accepted the frame — `false` when nobody else is on the network
    /// (gossipsub has no peer to publish to), which is not an error: the
    /// message stays queued in the outbox exactly as before.
    async fn send(
        &self,
        peer: &PublicKey,
        id: &str,
        content: &str,
        reply_to: Option<String>,
        created_at: u64,
    ) -> bool {
        let dm = MeshDm::new(id, content, reply_to, created_at);
        let envelope = match seal_dm(peer, &self.keys, &dm, created_at) {
            Ok(envelope) => envelope,
            Err(e) => {
                // Oversize is the realistic case: couriered/mesh mail is capped
                // at 16 KiB, and a long message simply waits for a relay.
                tracing::debug!("mesh: could not seal a DM: {e}");
                return false;
            }
        };
        match self.engine.publish_sealed(&envelope).await {
            Ok(()) => {
                tracing::info!(peer = %peer, "DM sealed onto the local mesh");
                true
            }
            Err(e) => {
                tracing::debug!("mesh: nobody to deliver to: {e}");
                false
            }
        }
    }
}

/// The route a DM arrived by, plus what the ingress needs to answer over the
/// same route when there is no internet.
struct DmRoute<'a> {
    /// [`TRANSPORT_RELAY`] or [`TRANSPORT_MESH`].
    label: &'static str,
    /// Cross-transport dedup set — see [`CROSS_TRANSPORT_DEDUP_SECS`].
    dedup: &'a SeenSet,
    /// The local mesh, when one is running.
    mesh: Option<&'a MeshLink>,
}

impl DmRoute<'_> {
    /// Whether this message has already been ingested over the *other*
    /// transport recently, i.e. it is the second copy of one message that took
    /// two routes. Records it either way.
    fn is_cross_transport_duplicate(&self, peer_npub: &str, content: &str) -> bool {
        let key = content_key(content, CONTENT_KEY_PREFIX);
        let other = if self.label == TRANSPORT_MESH {
            TRANSPORT_RELAY
        } else {
            TRANSPORT_MESH
        };
        if self.dedup.contains(&format!("{peer_npub}|{key}|{other}")) {
            core_metrics::record(CoreMetric::DuplicateDropped);
            return true;
        }
        self.dedup
            .already_seen(&format!("{peer_npub}|{key}|{}", self.label));
        false
    }
}

/// Read the persisted inbox high-watermark (unix seconds of the newest
/// message processed so far), if any has been recorded yet.
fn read_watermark(store: &comrade_storage::EncryptedStore) -> Option<u64> {
    store.get(SETTINGS_TREE, VAULT_WATERMARK_KEY).ok().flatten()
}

/// Advance the persisted inbox high-watermark to `created_at` if it is newer
/// than what's stored (a monotonic max — out-of-order delivery must never
/// move it backwards). Read back at the next launch by
/// [`ComradeRuntime::spawn_event_loops`] to widen the backfill window past
/// the standard gift-wrap skew when this device was offline longer than that.
fn advance_watermark(store: &comrade_storage::EncryptedStore, created_at: u64) {
    let current = read_watermark(store).unwrap_or(0);
    if created_at > current {
        if let Err(e) = store
            .put(SETTINGS_TREE, VAULT_WATERMARK_KEY, &created_at)
            .and_then(|()| store.flush())
        {
            warn!("failed to advance vault watermark: {e}");
        }
    }
}

/// Route one decrypted incoming DM: block-drop, control envelopes
/// (receipt/profile-share/call), media, or plain chat — applying the message
/// -request gate throughout. Runs inside the Vault inbox Tokio task.
///
/// Idempotent end-to-end: relays redeliver at-least-once and the inbox
/// subscription backfills up to 2 days (widened further on `since_floor`) on
/// every launch, so this may see the exact same wrapper event more than
/// once. Plain-text/media DMs are deduped by checking persistence *before*
/// emitting/receipting (persist first, so a crash between persist and emit
/// self-heals as "already seen, no event" next time); call signals are
/// deduped by `dedup` and dropped once stale — see the call-envelope branch.
fn dispatch_incoming_dm(
    vault: &Arc<VaultEngine>,
    store: Option<&Arc<comrade_storage::EncryptedStore>>,
    tx: &broadcast::Sender<BridgeEvent>,
    dedup: &SeenSet,
    outbox: &Arc<Outbox>,
    route: &DmRoute<'_>,
    msg: VaultMessage,
) {
    if let Some(store) = store {
        advance_watermark(store, msg.created_at);
    }
    let peer_npub = to_npub(&msg.sender_pubkey);
    let gate = store
        .map(|s| conversation_gate(s, &peer_npub))
        .unwrap_or(IncomingGate::Pending);
    if matches!(gate, IncomingGate::Blocked) {
        return;
    }

    // 1) Receipt — advance our outgoing statuses (accepted conversations only).
    if let Some(receipt) = parse_receipt(&msg.content) {
        if matches!(gate, IncomingGate::Accepted) {
            // A receipt proves the peer holds the message, so anything still
            // queued for a retry is done — clear it before the status update so
            // a concurrent flush cannot re-send an already-delivered message.
            if !outbox.ack(&peer_npub, &receipt.message_ids).is_empty() {
                if let Some(store) = store {
                    persist_outbox(store, outbox);
                }
            }
            if let Some(store) = store {
                let status = receipt.status.as_str();
                let mut changed = Vec::new();
                for id in &receipt.message_ids {
                    if store.set_message_status(id, status).unwrap_or(false) {
                        changed.push(id.clone());
                    }
                }
                let _ = store.flush();
                if !changed.is_empty() {
                    let _ = tx.send(BridgeEvent::MessageStatus {
                        peer: peer_npub,
                        message_ids: changed,
                        status: status.to_string(),
                    });
                }
            }
        }
        return;
    }

    // 2) Profile share — cache the peer's shared @handle (any non-blocked peer;
    //    they revealed it by reaching out or accepting).
    if let Some(profile) = parse_profile_share(&msg.content) {
        if let (Some(store), Some(name)) = (store, profile.username) {
            cache_pushed_peer_name(store, &peer_npub, &name);
            let _ = tx.send(BridgeEvent::PeerProfileUpdated {
                peer: peer_npub,
                name: Some(name),
            });
        }
        return;
    }

    // 3) Presence beacon — a comrade saying "I'm here" / "I'm going". Only
    //    from an established conversation: a stranger must not be able to
    //    push presence state (or trigger a reply that discloses ours) before
    //    their message request is accepted. Returns either way, so a beacon
    //    from an unaccepted peer is dropped silently rather than surfacing as
    //    a message request full of JSON.
    if let Some(beacon) = parse_presence_beacon(&msg.content) {
        if matches!(gate, IncomingGate::Accepted) {
            handle_presence_beacon(
                vault,
                store,
                tx,
                &peer_npub,
                &msg.sender_pubkey,
                msg.created_at,
                beacon,
            );
        }
        return;
    }

    // 4) Nudge — a comrade wrote something for us and did not send it. Gated
    //    exactly like a presence beacon: a stranger must not be able to page us
    //    before their message request is accepted, and returning either way
    //    keeps a nudge from an unaccepted peer from surfacing as a message
    //    request full of JSON.
    if let Some(nudge) = parse_nudge(&msg.content) {
        if matches!(gate, IncomingGate::Accepted) {
            handle_nudge(
                store,
                tx,
                dedup,
                &peer_npub,
                &msg.event_id,
                msg.created_at,
                nudge,
            );
        }
        return;
    }

    // 5) Call signaling — only from an established conversation, so a stranger
    //    cannot ring you before their message request is accepted. Stale or
    //    already-dispatched signals are dropped: offers older than the ring
    //    timeout are meaningless, and a redelivered wrapper (relay
    //    at-least-once delivery, or the 2-day backfill re-scanning on every
    //    launch) must not re-ring or re-apply a signal already handled.
    if let Some(env) = parse_call_envelope(&msg.content) {
        if matches!(gate, IncomingGate::Accepted) {
            let age = now_secs().saturating_sub(msg.created_at);
            if age > CALL_SIGNAL_MAX_AGE_SECS {
                tracing::debug!(event_id = %msg.event_id, age, "dropping stale call signal");
            } else if dedup.already_seen(&msg.event_id) {
                tracing::debug!(event_id = %msg.event_id, "dropping duplicate call signal");
            } else {
                let _ = tx.send(BridgeEvent::IncomingCallSignal(CallSignalDto {
                    call_id: env.call_id,
                    peer: peer_npub,
                    media: env.media.as_str().to_string(),
                    signal: env.signal,
                    created_at: msg.created_at,
                }));
            }
        }
        return;
    }

    // 6) Media envelope — dedup by the NIP-94 event id (a redelivered wrapper
    //    carries the same reference), persist the ref, then surface (gated).
    if let Some(env) = parse_media_envelope(&msg.content) {
        // Everything in the envelope is chosen by the peer. The MIME type
        // decides which renderer every frontend reaches for and the caption is
        // drawn verbatim, so both are bounded and stripped of control
        // characters before they are persisted — once, here, rather than in
        // each of three UIs.
        let mime = match validate_mime_type(&env.mime) {
            Ok(mime) => mime,
            // Not a reason to drop the attachment: it downloads and opens fine,
            // it just gets the generic renderer instead of a claimed one.
            Err(e) => {
                tracing::debug!(error = %e, "incoming media has an unusable MIME type");
                DEFAULT_MIME.to_string()
            }
        };
        let caption = sanitise_untrusted_text(&env.caption, MAX_CAPTION_LEN);
        if let Some(store) = store {
            if store
                .get::<MediaRef>(MEDIA_REFS_TREE, &env.event_id)
                .ok()
                .flatten()
                .is_some()
            {
                return;
            }
            let reff = MediaRef {
                event_id: env.event_id.clone(),
                url: env.url.clone(),
                peer_pubkey: msg.sender_pubkey.clone(),
                mime_type: mime.clone(),
                caption: caption.clone(),
                size: env.size,
                sha256_hex: env.sha256_hex.clone(),
                outgoing: false,
                created_at: msg.created_at,
            };
            if let Err(e) = store
                .put(MEDIA_REFS_TREE, &env.event_id, &reff)
                .and_then(|()| store.flush())
            {
                warn!("failed to persist incoming media ref: {e}");
            }
        }
        if matches!(gate, IncomingGate::Accepted) {
            let _ = tx.send(BridgeEvent::IncomingMedia(MediaMessageDto {
                event_id: env.event_id,
                url: env.url,
                mime_type: mime,
                caption,
                sender: to_npub(&msg.sender_pubkey),
                created_at: msg.created_at,
                size: env.size,
                outgoing: false,
            }));
            send_delivered_receipt(vault, route.mesh, &msg.sender_pubkey, &msg.event_id);
        } else {
            ensure_pending(store, &peer_npub);
            let _ = tx.send(BridgeEvent::IncomingMessageRequest(MessageRequestDto {
                peer: peer_npub,
                last_message: attachment_preview(&caption),
                last_at: msg.created_at,
            }));
        }
        return;
    }

    // 7) Plain chat text — dedup by event id (a redelivered wrapper must not
    //    re-notify or re-send a delivered receipt), persist first, then
    //    deliver or gate into a request.
    // A message can reach us twice by two routes — sealed over the local mesh
    // now, over a relay when the internet returns — under two different ids, so
    // the id check below cannot catch that pair. This can.
    if route.is_cross_transport_duplicate(&peer_npub, &msg.content) {
        tracing::debug!(
            transport = route.label,
            "dropping a message already delivered by the other transport"
        );
        return;
    }
    if let Some(store) = store {
        if store.get_message(&msg.event_id).ok().flatten().is_some() {
            return;
        }
        let row = comrade_storage::StoredMessage {
            id: msg.event_id.clone(),
            peer_npub: peer_npub.clone(),
            content: msg.content.clone(),
            created_at: msg.created_at,
            outgoing: false,
            status: None,
            reply_to: msg.reply_to.clone(),
        };
        if let Err(e) = store.save_message(&row).and_then(|()| store.flush()) {
            warn!("failed to persist incoming DM: {e}");
        }
    }
    if matches!(gate, IncomingGate::Accepted) {
        send_delivered_receipt(vault, route.mesh, &msg.sender_pubkey, &msg.event_id);
        let _ = tx.send(BridgeEvent::IncomingDirectMessage(DirectMessageDto::from(
            msg,
        )));
    } else {
        ensure_pending(store, &peer_npub);
        let _ = tx.send(BridgeEvent::IncomingMessageRequest(MessageRequestDto {
            peer: peer_npub,
            last_message: msg.content,
            last_at: msg.created_at,
        }));
    }
}

/// Detached profile-refresh worker. Holds only the engine and store handles,
/// so the shared `Arc<RwLock<ComradeRuntime>>` guard can be dropped before
/// the slow network work starts (see [`ComradeRuntime::profile_refresher`]).
pub struct ProfileRefresher {
    sabha: Arc<SabhaEngine>,
    store: Arc<comrade_storage::EncryptedStore>,
}

impl ProfileRefresher {
    /// Refresh the cached Kind-0 profiles of everyone we talk to
    /// (conversation peers and saved contacts) in **one** relay round-trip,
    /// bounded by [`PROFILE_REFRESH_CAP`] and per-record freshness windows.
    /// Returns how many display names changed — the frontend reloads its
    /// chat list when > 0.
    pub async fn run(self) -> Result<usize, UiError> {
        let mut peers: Vec<String> = Vec::new();
        let mut seen = std::collections::HashSet::new();
        for msg in self
            .store
            .all_messages()
            .map_err(|e| UiError::Storage(e.to_string()))?
        {
            if seen.insert(msg.peer_npub.clone()) {
                peers.push(msg.peer_npub);
            }
        }
        for contact in self
            .store
            .list_contacts()
            .map_err(|e| UiError::Storage(e.to_string()))?
        {
            if seen.insert(contact.npub.clone()) {
                peers.push(contact.npub);
            }
        }

        // Select the stale records. A record that has a name is trusted for
        // the full TTL; a nameless record only briefly — an offline launch
        // yields Ok(no events) from the pool (indistinguishable from "peer
        // has no profile"), and that outcome must not freeze the peer as
        // key-only for a whole day.
        let now = now_secs();
        let mut stale: Vec<(String, PublicKey, Option<PeerProfileRecord>)> = Vec::new();
        for npub in peers {
            if stale.len() >= PROFILE_REFRESH_CAP {
                break;
            }
            let previous: Option<PeerProfileRecord> = self
                .store
                .get(PEER_PROFILES_TREE, &npub)
                .unwrap_or_default();
            let ttl = if previous.as_ref().is_some_and(|p| p.name.is_some()) {
                PROFILE_TTL_SECS
            } else {
                PROFILE_NEGATIVE_TTL_SECS
            };
            let fresh = previous
                .as_ref()
                .is_some_and(|r| now.saturating_sub(r.updated_at) < ttl);
            if fresh {
                continue;
            }
            let Ok(pk) = PublicKey::parse(&npub) else {
                continue;
            };
            stale.push((npub, pk, previous));
        }
        if stale.is_empty() {
            return Ok(0);
        }

        let authors: Vec<PublicKey> = stale.iter().map(|(_, pk, _)| *pk).collect();
        let found = match self.sabha.fetch_profiles(&authors).await {
            Ok(found) => found,
            Err(e) => {
                // Transport error: stamp nothing, so the next refresh retries.
                warn!("peer profile refresh failed: {e}");
                return Ok(0);
            }
        };

        let mut wrote = false;
        let mut changed = 0usize;
        for (npub, pk, previous) in stale {
            let meta = found.get(&pk);
            let record = PeerProfileRecord {
                // A silent relay set must not erase a name we already knew.
                name: meta
                    .and_then(display_name_of)
                    .or_else(|| previous.as_ref().and_then(|p| p.name.clone())),
                about: meta
                    .and_then(|m| m.about.clone())
                    .or_else(|| previous.as_ref().and_then(|p| p.about.clone())),
                updated_at: now,
            };
            let name_changed = record.name != previous.as_ref().and_then(|p| p.name.clone());
            if store_profile_record(&self.store, &npub, &record) {
                wrote = true;
                if name_changed {
                    changed += 1;
                }
            }
        }
        if wrote {
            if let Err(e) = self.store.flush() {
                warn!("failed to flush profile cache: {e}");
            }
        }
        Ok(changed)
    }
}

/// Publish the Kind-0 profile with retries and exponential backoff.
///
/// Why this exists: at onboarding the relays are still dialling when the
/// handle is claimed, and a single fire-and-forget publish that fails leaves
/// the identity permanently undiscoverable — peers searching the handle find
/// nothing. `publish_profile` itself waits (bounded) for a connection; this
/// wrapper keeps trying across transient failures. It is also spawned on
/// every launch (Kind-0 is replaceable, so republishing is idempotent).
async fn publish_profile_with_retry(sabha: Arc<SabhaEngine>, name: String) {
    // Make sure dials were at least initiated, even if the feed loop that
    // normally calls connect() hasn't run yet. Idempotent.
    sabha.connect().await;
    let mut delay = std::time::Duration::from_secs(2);
    for attempt in 1..=PUBLISH_ATTEMPTS {
        match sabha.publish_profile(&name, None).await {
            Ok(_) => {
                tracing::info!(attempt, "profile handle published to relays");
                return;
            }
            Err(e) => warn!(attempt, "profile publish failed (will retry): {e}"),
        }
        tokio::time::sleep(delay).await;
        delay = delay.saturating_mul(2);
    }
    warn!("profile publish gave up after {PUBLISH_ATTEMPTS} attempts; will retry on next launch");
}

/// Normalise and validate a chosen @handle: strip a leading '@', lowercase,
/// then require 3–24 chars of `[a-z0-9_]`. One rule shared by every bridge.
fn normalize_handle(raw: &str) -> Result<String, UiError> {
    let handle = raw.trim().trim_start_matches('@').to_lowercase();
    if handle.len() < 3 || handle.len() > 24 {
        return Err(UiError::Engine("username must be 3–24 characters".into()));
    }
    if !handle
        .chars()
        .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_')
    {
        return Err(UiError::Engine(
            "username may only contain a–z, 0–9 and _".into(),
        ));
    }
    // "primary" is the legacy no-username marker inside the store.
    if handle == "primary" {
        return Err(UiError::Engine("that username is reserved".into()));
    }
    Ok(handle)
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use comrade_core::nudge::NUDGE_SETTLE_SECS;
    use tempfile::TempDir;

    fn assert_send_sync<T: Send + Sync>() {}

    #[test]
    fn runtime_is_send_sync_for_shared_state() {
        // The Tauri managed state and JNI global both require this bound; a
        // regression here is exactly the Send/Sync compile boundary M5 guards.
        assert_send_sync::<ComradeRuntime>();
        assert_send_sync::<std::sync::Arc<tokio::sync::RwLock<ComradeRuntime>>>();
        assert_send_sync::<BridgeEvent>();
    }

    #[test]
    fn bridge_futures_are_send() {
        // Tauri's #[tauri::command] requires every command future to be Send;
        // the workspace itself never demands that, so without this
        // compile-time probe a non-Send future (e.g. a borrowed iterator held
        // across an await deep inside an engine) only surfaces in the desktop
        // CI lane — which is exactly how the search_profiles regression
        // escaped local checks once.
        fn require_send<T: Send>(_t: T) {}
        #[allow(dead_code)]
        fn probe(rt: &ComradeRuntime, wrt: &mut ComradeRuntime, urt: &mut ComradeRuntime) {
            require_send(rt.search_profiles("q"));
            require_send(rt.refresh_peer_profiles());
            require_send(rt.send_dm("npub1x", "hi"));
            require_send(rt.broadcast_chitthi("x", None));
            require_send(rt.sync_ledger());
            require_send(rt.upload_and_send_media("x", vec![], "image/png", ""));
            require_send(rt.download_and_decrypt_media("x"));
            require_send(rt.sakha_add_entry("desc", 1.0, "sakha"));
            require_send(rt.sakha_read_ledger());
            require_send(wrt.set_username("neo"));
            require_send(wrt.pair_sakha("npub1x", "sakha"));
            require_send(urt.unlock_vault("/tmp/x", "p"));
            require_send(wrt.toggle_workspace("Base"));
            require_send(urt.back());
        }
        let _ = probe;
    }

    #[tokio::test]
    async fn toggle_workspace_enforces_state_machine() {
        let mut rt = ComradeRuntime::new();
        let dto = rt.toggle_workspace("OffGridTravel").await.unwrap();
        assert_eq!(dto.key, "OffGridTravel");
        assert!(dto.mesh_active);
        // OffGridTravel -> CoupleSandbox is blocked by the transition graph.
        assert!(matches!(
            rt.toggle_workspace("CoupleSandboxSakha").await,
            Err(UiError::Transition(_))
        ));
        // Unknown keys are a distinct typed error.
        assert!(matches!(
            rt.toggle_workspace("Nope").await,
            Err(UiError::UnknownWorkspace(_))
        ));
    }

    #[tokio::test]
    async fn toggle_workspace_starts_and_stops_the_mesh_engine() {
        let mut rt = ComradeRuntime::new();
        assert_eq!(
            rt.mesh_status(),
            MeshStatusDto {
                active: false,
                peer_count: 0
            }
        );

        rt.toggle_workspace("OffGridTravel").await.unwrap();
        assert_eq!(
            rt.mesh_status(),
            MeshStatusDto {
                active: true,
                peer_count: 0
            }
        );

        rt.toggle_workspace("Base").await.unwrap();
        assert_eq!(
            rt.mesh_status(),
            MeshStatusDto {
                active: false,
                peer_count: 0
            }
        );
    }

    #[tokio::test]
    async fn back_also_stops_the_mesh_engine() {
        let mut rt = ComradeRuntime::new();
        rt.toggle_workspace("OffGridTravel").await.unwrap();
        assert!(rt.mesh_status().active);

        let dto = rt.back().await;
        assert_eq!(dto.key, "Base");
        assert!(!rt.mesh_status().active);
    }

    #[test]
    fn commands_reject_gracefully_when_vault_locked() {
        let rt = ComradeRuntime::new();
        assert!(!rt.is_vault_unlocked());
        assert!(matches!(
            rt.fetch_sabha_timeline(),
            Err(UiError::VaultLocked)
        ));
    }

    #[tokio::test]
    async fn broadcast_rejects_when_locked_without_panicking() {
        let rt = ComradeRuntime::new();
        let err = rt.broadcast_chitthi("hello sabha", None).await;
        assert!(matches!(err, Err(UiError::VaultLocked)));
        let err = rt.sync_ledger().await;
        assert!(matches!(err, Err(UiError::VaultLocked)));
    }

    #[tokio::test]
    async fn unlock_vault_seeds_identity_and_builds_engines() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        let id = rt.unlock_vault(dir.path(), "passphrase").await.unwrap();
        assert!(id.npub.starts_with("npub1"));
        assert!(rt.is_vault_unlocked());
        assert!(rt.is_store_unlocked());
        // Timeline is reachable (empty cache) once unlocked.
        assert!(rt.fetch_sabha_timeline().unwrap().is_empty());
    }

    #[tokio::test]
    async fn feed_filter_spec_is_bounded_global_with_no_contacts_then_authors_scoped_once_one_exists(
    ) {
        // AUDIT.md COMMS-04: the feed subscription must never be the
        // unbounded relay-wide firehose — a fresh identity gets an explicit
        // capped bootstrap scope, and adding a contact narrows it to authors.
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();

        let bootstrap = rt.feed_filter_spec();
        assert_eq!(
            bootstrap.scope,
            FeedScope::BoundedGlobal {
                limit: FEED_BOOTSTRAP_LIMIT
            },
            "no contacts yet must never fall back to an unbounded author-less firehose"
        );
        assert_eq!(bootstrap.since_secs, FEED_SINCE_SECS);

        let (_hex, peer) = stranger();
        rt.add_contact(&peer, "friend").unwrap();
        let scoped = rt.feed_filter_spec();
        match scoped.scope {
            FeedScope::Authors(authors) => {
                // Self + the one contact just added.
                assert_eq!(authors.len(), 2);
            }
            other => panic!("expected an authors-scoped feed once a contact exists, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn second_unlock_is_idempotent_and_keeps_the_same_identity() {
        // A repeated unlock must return the existing identity without rebuilding
        // engines (which would orphan the running ones and duplicate loops).
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        let first = rt.unlock_vault(dir.path(), "pin").await.unwrap().npub;
        let sabha_ptr = Arc::as_ptr(rt.sabha.as_ref().unwrap()) as usize;
        // Second unlock — same or different args — is a no-op that returns the
        // current identity and leaves the engine instances untouched.
        let second = rt.unlock_vault(dir.path(), "pin").await.unwrap().npub;
        assert_eq!(first, second);
        assert_eq!(
            sabha_ptr,
            Arc::as_ptr(rt.sabha.as_ref().unwrap()) as usize,
            "engines must not be rebuilt on a repeated unlock"
        );
    }

    #[tokio::test]
    async fn unlock_then_reopen_restores_same_identity() {
        let dir = TempDir::new().unwrap();
        let first = {
            let mut rt = ComradeRuntime::new();
            rt.unlock_vault(dir.path(), "pin").await.unwrap().npub
        };
        let mut rt2 = ComradeRuntime::new();
        let second = rt2.unlock_vault(dir.path(), "pin").await.unwrap().npub;
        assert_eq!(first, second);
    }

    #[tokio::test]
    async fn lock_vault_drops_engines_and_store_and_relock_works() {
        // AUDIT.md COMMS-01: locking must actually remove decrypted state, not
        // just flip a UI flag — every store/engine-backed call must reject
        // exactly as it did before the first unlock.
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        let identity = rt.unlock_vault(dir.path(), "pin").await.unwrap();
        assert!(rt.is_vault_unlocked());
        assert!(rt.is_store_unlocked());

        rt.lock_vault().await;
        assert!(!rt.is_vault_unlocked());
        assert!(!rt.is_store_unlocked());
        assert!(matches!(
            rt.fetch_sabha_timeline(),
            Err(UiError::VaultLocked)
        ));
        assert!(matches!(rt.profile(), Err(UiError::NoIdentity)));

        // Locking twice in a row must not panic (idempotent).
        rt.lock_vault().await;
        assert!(!rt.is_vault_unlocked());

        // Unlocking again resumes normally with the same on-disk identity, and
        // the feed/DM loops can be (re)spawned — the `loops_spawned` guard
        // must have been reset by the lock, not left permanently tripped.
        let relocked = rt.unlock_vault(dir.path(), "pin").await.unwrap();
        assert_eq!(relocked.npub, identity.npub);
        assert!(rt.is_vault_unlocked());
        rt.spawn_event_loops();
    }

    #[tokio::test]
    async fn sakha_status_and_ledger_reject_gracefully_before_pairing() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();

        let status = rt.sakha_status().unwrap();
        assert!(!status.paired);
        assert_eq!(status.partner_npub, None);

        assert!(matches!(
            rt.sakha_add_entry("Coffee", 150.0, "Sakha").await,
            Err(UiError::Engine(_))
        ));
        // Reading the (empty) local ledger doesn't require pairing.
        assert_eq!(rt.sakha_read_ledger().await.unwrap(), "");
    }

    #[tokio::test]
    async fn pair_sakha_rejects_an_invalid_partner_key() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        assert!(rt.pair_sakha("not-a-valid-key", "sakha").await.is_err());
        assert!(!rt.sakha_status().unwrap().paired);
    }

    #[tokio::test]
    async fn pair_sakha_add_entry_and_status_roundtrip() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();

        let partner = comrade_core::crypto::KeyProfile::generate().unwrap();
        let status = rt.pair_sakha(&partner.npub, "Sakhi").await.unwrap();
        assert!(status.paired);
        assert_eq!(status.partner_npub.as_deref(), Some(partner.npub.as_str()));
        assert_eq!(status.role.as_deref(), Some("sakhi"));

        let ledger = rt
            .sakha_add_entry("Groceries", 300.0, "Sakhi")
            .await
            .unwrap();
        assert!(ledger.contains("Groceries"), "entry must appear: {ledger}");
        assert_eq!(rt.sakha_read_ledger().await.unwrap(), ledger);
    }

    #[test]
    fn sakha_pairing_and_ledger_survive_a_relaunch() {
        // Regression guard for AUDIT A3/A8: pairing state and the local
        // ledger must not evaporate on restart just because the in-memory
        // Yrs doc and the paired-partner key live nowhere but RAM otherwise.
        //
        // This uses two independent Tokio runtimes (rather than one shared
        // `#[tokio::test]` runtime) to actually simulate a process restart:
        // `pair_sakha` spawns a detached background sync task that holds its
        // own `Arc` clone of the encrypted store, so within a single runtime
        // that task outlives the `{ }` scope below and keeps the redb file
        // open — dropping the whole `Runtime` (unlike a scope exit) forcibly
        // tears down every task it owns, exactly as a real process exit
        // would, and only then is the file lock actually released.
        let dir = TempDir::new().unwrap();
        let partner = comrade_core::crypto::KeyProfile::generate().unwrap();

        {
            let rt_tokio = tokio::runtime::Runtime::new().unwrap();
            rt_tokio.block_on(async {
                let mut rt = ComradeRuntime::new();
                rt.unlock_vault(dir.path(), "pin").await.unwrap();
                rt.pair_sakha(&partner.npub, "sakha").await.unwrap();
                rt.sakha_add_entry("Rent", 12000.0, "Sakha").await.unwrap();
            });
        }

        let rt_tokio2 = tokio::runtime::Runtime::new().unwrap();
        rt_tokio2.block_on(async {
            let mut rt2 = ComradeRuntime::new();
            rt2.unlock_vault(dir.path(), "pin").await.unwrap();
            let status = rt2.sakha_status().unwrap();
            assert!(status.paired, "pairing must survive a relaunch");
            assert_eq!(status.partner_npub.as_deref(), Some(partner.npub.as_str()));
            let ledger = rt2.sakha_read_ledger().await.unwrap();
            assert!(
                ledger.contains("Rent"),
                "ledger snapshot must survive a relaunch: {ledger}"
            );
        });
    }

    #[tokio::test]
    async fn fetch_timeline_reads_from_encrypted_cache() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();

        // Seed the encrypted cache directly (the relay loop does this in prod).
        rt.ui
            .store_ref()
            .unwrap()
            .cache_chitthi(&comrade_storage::Chitthi {
                id: "abc123".into(),
                author_npub: "npub1author".into(),
                content: "Namaste".into(),
                created_at: 42,
                reply_to: None,
            })
            .unwrap();

        let feed = rt.fetch_sabha_timeline().unwrap();
        assert_eq!(feed.len(), 1);
        assert_eq!(feed[0].id, "abc123");
        assert_eq!(feed[0].content, "Namaste");
    }

    #[tokio::test]
    async fn event_bus_delivers_serialisable_events() {
        let rt = ComradeRuntime::new();
        let mut rx = rt.subscribe_events();

        let event = BridgeEvent::IncomingChitthi(ChitthiDto {
            id: "id1".into(),
            author: "npub1x".into(),
            content: "over the wire".into(),
            created_at: 7,
            reply_to: None,
        });
        rt.event_sender().send(event.clone()).unwrap();

        let received = rx.recv().await.unwrap();
        assert_eq!(received, event);

        // It must round-trip through serde_json (the IPC payload format).
        let json = serde_json::to_string(&received).unwrap();
        assert!(json.contains("\"type\":\"incoming_chitthi\""));
        let back: BridgeEvent = serde_json::from_str(&json).unwrap();
        assert_eq!(back, event);
    }

    #[tokio::test]
    async fn feed_events_and_critical_events_are_independent_channels() {
        // AUDIT.md COMMS-04: pins the channel split itself (not just the
        // production wiring in `spawn_event_loops`) — a subscriber to one
        // channel must never see what was sent on the other, in either
        // direction, and each channel must accept a send with no subscriber
        // to the *other* one blocking or erroring.
        let rt = ComradeRuntime::new();
        let mut critical_rx = rt.subscribe_events();
        let mut feed_rx = rt.subscribe_feed_events();

        let chitthi = BridgeEvent::IncomingChitthi(ChitthiDto {
            id: "id1".into(),
            author: "npub1x".into(),
            content: "hi".into(),
            created_at: 1,
            reply_to: None,
        });
        rt.feed_events.send(chitthi.clone()).unwrap();
        assert_eq!(feed_rx.recv().await.unwrap(), chitthi);
        assert!(
            critical_rx.try_recv().is_err(),
            "IncomingChitthi sent on the feed bus must never reach a critical-bus subscriber"
        );

        let mesh = BridgeEvent::MeshStatusChanged(MeshStatusDto {
            active: true,
            peer_count: 1,
        });
        rt.events.send(mesh.clone()).unwrap();
        assert_eq!(critical_rx.recv().await.unwrap(), mesh);
        assert!(
            feed_rx.try_recv().is_err(),
            "a critical event must never reach a feed-bus subscriber"
        );
    }

    #[test]
    fn media_envelope_detection() {
        let env = MediaEnvelope {
            comrade_media: 1,
            event_id: "e1".into(),
            url: "https://blob/x".into(),
            mime: "image/png".into(),
            caption: "hi".into(),
            size: 10,
            sha256_hex: "a".repeat(64),
        };
        let json = serde_json::to_string(&env).unwrap();
        assert!(parse_media_envelope(&json).is_some());
        // A plain DM is not mistaken for a media envelope.
        assert!(parse_media_envelope("just a normal message").is_none());
        assert!(parse_media_envelope(r#"{"hello":"world"}"#).is_none());
        // An envelope written before the sha256_hex field still parses (the
        // field is #[serde(default)]) — back-compat for already-sent media.
        assert!(parse_media_envelope(
            r#"{"comrade_media":1,"event_id":"e","url":"https://b/x","mime":"image/png","caption":"","size":1}"#
        )
        .is_some());
        // The flush loop routes on this: a queued envelope must never be
        // re-keyed into a stored text message (that is how raw JSON ends up in
        // a chat bubble).
        assert!(is_media_envelope(&json));
        assert!(!is_media_envelope("just a normal message"));
    }

    #[test]
    fn mime_types_are_normalised_and_hostile_ones_refused() {
        // Case-insensitive per RFC 2045: without lowercasing, `IMAGE/PNG` fails
        // every frontend's `starts_with("image/")` and a photo renders as an
        // unopenable file.
        assert_eq!(validate_mime_type(" IMAGE/PNG ").unwrap(), "image/png");
        assert_eq!(
            validate_mime_type("application/vnd.oasis.opendocument.text").unwrap(),
            "application/vnd.oasis.opendocument.text"
        );
        // Blank, header-shaped, structureless, or oversized: refused, not patched.
        for bad in [
            "",
            "   ",
            "image/png\r\nX-Evil: 1",
            "image png",
            "notamimetype",
        ] {
            assert!(
                validate_mime_type(bad).is_err(),
                "must reject {bad:?} as a MIME type"
            );
        }
        assert!(validate_mime_type(&format!("image/{}", "x".repeat(MAX_MIME_LEN))).is_err());
    }

    #[test]
    fn captions_are_stripped_of_control_characters_and_bounded() {
        // A peer's caption is drawn verbatim in every frontend. Newlines would
        // let them forge extra UI lines; an unbounded one is a storage and
        // layout problem.
        assert_eq!(
            sanitise_untrusted_text("holiday\r\nphoto\u{0}", MAX_CAPTION_LEN),
            "holidayphoto"
        );
        assert_eq!(sanitise_untrusted_text("  spaced  ", 64), "spaced");
        let long = "é".repeat(MAX_CAPTION_LEN * 2);
        let cut = sanitise_untrusted_text(&long, MAX_CAPTION_LEN);
        // Truncation counts characters, not bytes — a multi-byte character is
        // never split in half.
        assert_eq!(cut.chars().count(), MAX_CAPTION_LEN);
        assert!(cut.chars().all(|c| c == 'é'));
    }

    #[test]
    fn attachment_previews_never_render_an_empty_line() {
        assert_eq!(attachment_preview("sunset.jpg"), "📎 sunset.jpg");
        assert_eq!(attachment_preview("   "), "📎 Attachment");
        assert_eq!(attachment_preview(""), "📎 Attachment");
    }

    #[test]
    fn to_npub_canonicalises_incoming_and_outgoing_to_the_same_key() {
        // Regression guard: incoming media/DM senders arrive as hex, outgoing
        // DTOs emit bech32. Both must normalise to the identical npub so the
        // frontend keys one conversation (and the couple panel) per peer.
        let keys = nostr_sdk::Keys::generate();
        let hex = keys.public_key().to_hex();
        let npub = keys.public_key().to_bech32().unwrap();
        assert_eq!(to_npub(&hex), npub, "hex must normalise to npub");
        assert_eq!(to_npub(&npub), npub, "npub is already canonical");
        // Unparseable input falls back unchanged rather than panicking.
        assert_eq!(to_npub("not-a-key"), "not-a-key");
    }

    #[test]
    fn incoming_media_event_serialises_with_tag() {
        let ev = BridgeEvent::IncomingMedia(MediaMessageDto {
            event_id: "e".into(),
            url: "https://blob/x".into(),
            mime_type: "image/jpeg".into(),
            caption: "pic".into(),
            sender: "npub1x".into(),
            created_at: 1,
            size: 42,
            outgoing: false,
        });
        let json = serde_json::to_string(&ev).unwrap();
        assert!(json.contains("\"type\":\"incoming_media\""));
        let back: BridgeEvent = serde_json::from_str(&json).unwrap();
        assert_eq!(back, ev);
    }

    #[test]
    fn handle_normalisation_rules() {
        assert_eq!(normalize_handle("@Abc_User").unwrap(), "abc_user");
        assert_eq!(normalize_handle("  neo42 ").unwrap(), "neo42");
        assert!(normalize_handle("ab").is_err(), "too short");
        assert!(normalize_handle(&"x".repeat(25)).is_err(), "too long");
        assert!(normalize_handle("has space").is_err());
        assert!(normalize_handle("emoji🙂").is_err());
        assert!(normalize_handle("primary").is_err(), "reserved");
    }

    #[tokio::test]
    async fn username_round_trips_through_the_encrypted_store() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        assert_eq!(rt.profile().unwrap().username, None);

        let profile = rt.set_username("@Chandra_M").await.unwrap();
        assert_eq!(profile.username.as_deref(), Some("chandra_m"));

        // Reopen: the handle must survive alongside the identity.
        drop(rt);
        let mut rt2 = ComradeRuntime::new();
        rt2.unlock_vault(dir.path(), "pin").await.unwrap();
        assert_eq!(
            rt2.profile().unwrap().username.as_deref(),
            Some("chandra_m")
        );
    }

    #[tokio::test]
    async fn contacts_are_pinned_by_npub_not_by_alias() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();

        let a = nostr_sdk::Keys::generate()
            .public_key()
            .to_bech32()
            .unwrap();
        let b = nostr_sdk::Keys::generate()
            .public_key()
            .to_bech32()
            .unwrap();

        // Two different keys may claim the same alias — both entries survive,
        // because the store is keyed by npub (TOFU), not by the display name.
        rt.add_contact(&a, "abc_user").unwrap();
        rt.add_contact(&b, "abc_user").unwrap();
        let contacts = rt.list_contacts().unwrap();
        assert_eq!(contacts.len(), 2);
        assert!(contacts.iter().any(|c| c.npub == a));
        assert!(contacts.iter().any(|c| c.npub == b));

        // Re-adding the same npub renames in place instead of duplicating.
        rt.add_contact(&a, "renamed").unwrap();
        let contacts = rt.list_contacts().unwrap();
        assert_eq!(contacts.len(), 2);
        assert_eq!(
            contacts.iter().find(|c| c.npub == a).unwrap().alias,
            "renamed"
        );

        // An empty alias on re-add (opening an existing chat) must never wipe
        // the alias the user chose.
        rt.add_contact(&a, "  ").unwrap();
        assert_eq!(
            rt.list_contacts()
                .unwrap()
                .iter()
                .find(|c| c.npub == a)
                .unwrap()
                .alias,
            "renamed"
        );

        // Junk npubs are a typed error, not a stored contact.
        assert!(rt.add_contact("not-a-key", "x").is_err());
    }

    #[tokio::test]
    async fn contact_alias_lifecycle_set_clear_remove() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let peer = nostr_sdk::Keys::generate()
            .public_key()
            .to_bech32()
            .unwrap();

        // Adding by key alone stores no alias (no fake npub-prefix names).
        let added = rt.add_contact(&peer, "").unwrap();
        assert_eq!(added.alias, "");

        // A conversation exists with this peer, so the chat list reflects the
        // alias lifecycle end to end.
        rt.ui
            .store_ref()
            .unwrap()
            .save_message(&comrade_storage::StoredMessage {
                id: "m1".into(),
                peer_npub: peer.clone(),
                content: "hello".into(),
                created_at: 1,
                outgoing: true,
                status: None,
                reply_to: None,
            })
            .unwrap();

        // The alias feature: set…
        let set = rt.set_contact_alias(&peer, "Charlie ❤").unwrap();
        assert_eq!(set.alias, "Charlie ❤");
        assert_eq!(
            rt.conversations().unwrap()[0].alias.as_deref(),
            Some("Charlie ❤")
        );

        // …and clear (empty = explicit clear, unlike add_contact).
        let cleared = rt.set_contact_alias(&peer, "").unwrap();
        assert_eq!(cleared.alias, "");
        assert_eq!(rt.conversations().unwrap()[0].alias, None);
        assert!(rt.remove_contact(&peer).unwrap());
        assert!(
            !rt.remove_contact(&peer).unwrap(),
            "second remove is a no-op"
        );
        assert_eq!(rt.messages_with(&peer).unwrap().len(), 1);
        assert!(matches!(
            rt.set_contact_alias("junk", "x"),
            Err(UiError::Engine(_))
        ));
    }

    #[tokio::test]
    async fn journal_lifecycle_add_list_delete() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();

        // Locked → typed errors, no panics.
        assert!(matches!(
            rt.add_journal_entry("hi", None),
            Err(UiError::VaultLocked)
        ));
        assert!(matches!(rt.journal_entries(), Err(UiError::VaultLocked)));
        assert!(matches!(
            rt.delete_journal_entry("x"),
            Err(UiError::VaultLocked)
        ));

        rt.unlock_vault(dir.path(), "pin").await.unwrap();

        let first = rt
            .add_journal_entry("  rough morning  ", Some("😕"))
            .unwrap();
        assert_eq!(first.text, "rough morning", "text is trimmed");
        assert_eq!(first.mood.as_deref(), Some("😕"));
        let second = rt.add_journal_entry("grateful today", Some("  ")).unwrap();
        assert_eq!(second.mood, None, "blank mood normalises to none");
        assert_ne!(first.id, second.id);

        // Whitespace-only text is rejected.
        assert!(matches!(
            rt.add_journal_entry("   ", None),
            Err(UiError::Engine(_))
        ));

        let entries = rt.journal_entries().unwrap();
        assert_eq!(entries.len(), 2);
        // Newest first; same-second entries fall back to id ordering.
        assert!(entries[0].created_at >= entries[1].created_at);

        assert!(rt.delete_journal_entry(&first.id).unwrap());
        assert!(!rt.delete_journal_entry(&first.id).unwrap());
        assert_eq!(rt.journal_entries().unwrap().len(), 1);

        // Entries survive a restart (encrypted at rest).
        drop(rt);
        let mut rt2 = ComradeRuntime::new();
        rt2.unlock_vault(dir.path(), "pin").await.unwrap();
        let entries = rt2.journal_entries().unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].text, "grateful today");
    }

    #[tokio::test]
    async fn tara_lifecycle_send_thread_clear() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();

        // Locked → typed errors, no panics.
        assert!(matches!(rt.tara_send("hi"), Err(UiError::VaultLocked)));
        assert!(matches!(rt.tara_thread(), Err(UiError::VaultLocked)));
        assert!(matches!(rt.tara_opener(), Err(UiError::VaultLocked)));
        assert!(matches!(rt.clear_tara_thread(), Err(UiError::VaultLocked)));

        rt.unlock_vault(dir.path(), "pin").await.unwrap();

        // Fresh thread → honest opener; empty sends rejected.
        assert!(rt.tara_opener().unwrap().contains("not a therapist"));
        assert!(matches!(rt.tara_send("   "), Err(UiError::Engine(_))));

        let reply = rt.tara_send("  I've been anxious all week  ").unwrap();
        assert!(reply.from_tara);
        assert!(!reply.crisis);
        assert!(reply.text.contains("anxious"));

        // Thread is chat-ordered: trimmed user turn first, reply after it.
        let thread = rt.tara_thread().unwrap();
        assert_eq!(thread.len(), 2);
        assert_eq!(thread[0].text, "I've been anxious all week");
        assert!(!thread[0].from_tara);
        assert_eq!(thread[1].text, reply.text);

        // Distress → crisis flag on both turns, and resources to render.
        let crisis = rt.tara_send("I want to end it all").unwrap();
        assert!(crisis.crisis);
        assert!(!rt.tara_crisis_resources().is_empty());
        let thread = rt.tara_thread().unwrap();
        assert_eq!(thread.len(), 4);
        assert!(thread[2].crisis && thread[3].crisis);

        // The thread survives a restart (encrypted at rest)…
        drop(rt);
        let mut rt2 = ComradeRuntime::new();
        rt2.unlock_vault(dir.path(), "pin").await.unwrap();
        assert_eq!(rt2.tara_thread().unwrap().len(), 4);

        // …and clears on request.
        assert_eq!(rt2.clear_tara_thread().unwrap(), 4);
        assert!(rt2.tara_thread().unwrap().is_empty());
        assert_eq!(rt2.clear_tara_thread().unwrap(), 0);
    }

    #[tokio::test]
    async fn tara_opener_reflects_recent_low_journal_moods() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();

        rt.add_journal_entry("rough monday", Some("😞")).unwrap();
        rt.add_journal_entry("rough tuesday", Some("😕")).unwrap();
        assert!(rt.tara_opener().unwrap().contains("felt low"));
    }

    // ── Attention (wellbeing pillar #5) ───────────────────────────────────

    #[tokio::test]
    async fn attention_commands_reject_gracefully_when_vault_locked() {
        // Every store-backed command must fail closed rather than panic or
        // silently succeed — same contract the journal and Tara hold.
        let rt = ComradeRuntime::new();
        assert!(matches!(
            rt.record_attention_day("2026-07-31", 100, 40, 20),
            Err(UiError::VaultLocked)
        ));
        assert!(matches!(rt.attention_days(), Err(UiError::VaultLocked)));
        assert!(matches!(
            rt.attention_summary("2026-07-31"),
            Err(UiError::VaultLocked)
        ));
        assert!(matches!(rt.doom_apps(), Err(UiError::VaultLocked)));
        assert!(matches!(
            rt.set_doom_apps(vec![]),
            Err(UiError::VaultLocked)
        ));
        assert!(matches!(
            rt.start_focus_session("x", 25),
            Err(UiError::VaultLocked)
        ));
        assert!(matches!(
            rt.finish_focus_session(true),
            Err(UiError::VaultLocked)
        ));
        assert!(matches!(
            rt.active_focus_session(),
            Err(UiError::VaultLocked)
        ));
        assert!(matches!(rt.focus_sessions(), Err(UiError::VaultLocked)));
        assert!(matches!(
            rt.suggested_focus_minutes(),
            Err(UiError::VaultLocked)
        ));
        assert!(matches!(rt.focus_prompt(), Err(UiError::VaultLocked)));
        assert!(matches!(
            rt.focus_reflection("completed"),
            Err(UiError::VaultLocked)
        ));
        assert!(matches!(
            rt.save_reading("t", "x"),
            Err(UiError::VaultLocked)
        ));
        assert!(matches!(rt.reading(), Err(UiError::VaultLocked)));
        assert!(matches!(
            rt.set_reading_position(0),
            Err(UiError::VaultLocked)
        ));
        assert!(matches!(rt.clear_reading(), Err(UiError::VaultLocked)));
    }

    #[tokio::test]
    async fn attention_days_upsert_and_summarise_against_the_users_own_median() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();

        // A malformed date is refused rather than stored under a key that
        // would not sort chronologically.
        assert!(matches!(
            rt.record_attention_day("31-07-2026", 10, 1, 1),
            Err(UiError::Engine(_))
        ));

        for (date, screen, doom) in [
            ("2026-07-28", 100u32, 20u32),
            ("2026-07-29", 300, 90),
            ("2026-07-30", 200, 60),
            ("2026-07-31", 90, 15),
        ] {
            rt.record_attention_day(date, screen, 40, doom).unwrap();
        }
        // Same-date re-record updates in place as today's numbers grow.
        let today = rt.record_attention_day("2026-07-31", 120, 55, 25).unwrap();
        assert_eq!(today.screen_minutes, 120);
        assert_eq!(rt.attention_days().unwrap().len(), 4);
        assert_eq!(rt.attention_days().unwrap()[0].date, "2026-07-31");

        let summary = rt.attention_summary("2026-07-31").unwrap();
        assert_eq!(summary.today.as_ref().unwrap().screen_minutes, 120);
        assert_eq!(
            summary.sample_days, 3,
            "today is excluded from its baseline"
        );
        assert_eq!(summary.median_screen_minutes, 200);
        assert_eq!(summary.median_doom_minutes, 60);

        // A day with nothing recorded reports honestly rather than zeroes
        // masquerading as a measurement.
        let empty = rt.attention_summary("2026-08-05").unwrap();
        assert!(empty.today.is_none());
        assert_eq!(empty.sample_days, 4);
    }

    #[tokio::test]
    async fn doom_apps_are_the_users_own_list_cleaned_but_never_prefilled() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();

        // Comrade ships no built-in blacklist — the list starts empty.
        assert!(rt.doom_apps().unwrap().is_empty());
        let saved = rt
            .set_doom_apps(vec![
                "com.b".into(),
                "  ".into(),
                "com.a".into(),
                "com.b".into(),
            ])
            .unwrap();
        assert_eq!(saved, vec!["com.a".to_string(), "com.b".to_string()]);
        assert_eq!(rt.doom_apps().unwrap(), saved);
        // And it can be emptied again.
        assert!(rt.set_doom_apps(vec![]).unwrap().is_empty());
    }

    #[tokio::test]
    async fn focus_session_lifecycle_start_finish_and_history() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();

        assert!(rt.active_focus_session().unwrap().is_none());
        assert_eq!(rt.suggested_focus_minutes().unwrap(), 25, "starts small");
        assert!(!rt.focus_prompt().unwrap().is_empty());

        // Out-of-range durations are refused with a message, not clamped
        // silently — a five-hour "focus session" is not the practice.
        assert!(matches!(
            rt.start_focus_session("marathon", 600),
            Err(UiError::Engine(_))
        ));
        assert!(matches!(
            rt.start_focus_session("blink", 1),
            Err(UiError::Engine(_))
        ));

        let started = rt.start_focus_session("  draft the essay  ", 25).unwrap();
        assert_eq!(started.intent, "draft the essay");
        assert_eq!(started.outcome, None);
        assert!(started.remaining_secs > 24 * 60);
        assert_eq!(
            rt.active_focus_session().unwrap().map(|s| s.id),
            Some(started.id.clone())
        );

        let finished = rt.finish_focus_session(true).unwrap().unwrap();
        assert_eq!(finished.outcome.as_deref(), Some("completed"));
        assert_eq!(
            finished.remaining_secs, 0,
            "a finished session has no clock"
        );
        assert!(finished.ended_at.is_some());
        assert!(rt.active_focus_session().unwrap().is_none());
        // Finishing again is a clean None, not an error.
        assert!(rt.finish_focus_session(true).unwrap().is_none());
        assert_eq!(rt.focus_sessions().unwrap().len(), 1);

        // Abandoning is recorded plainly.
        rt.start_focus_session("second go", 25).unwrap();
        let abandoned = rt.finish_focus_session(false).unwrap().unwrap();
        assert_eq!(abandoned.outcome.as_deref(), Some("abandoned"));
        assert!(rt
            .focus_reflection("abandoned")
            .unwrap()
            .contains("not failure"));

        // Two completions unlock the next rung; the abandon costs nothing.
        rt.start_focus_session("third", 25).unwrap();
        rt.finish_focus_session(true).unwrap();
        assert_eq!(rt.suggested_focus_minutes().unwrap(), 45);
    }

    #[tokio::test]
    async fn starting_a_new_session_closes_the_one_left_running() {
        // Only one session runs at a time: the user has visibly moved on, so
        // the old one is closed rather than left dangling forever (which would
        // block every future start).
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();

        let first = rt.start_focus_session("one", 25).unwrap();
        let second = rt.start_focus_session("two", 45).unwrap();
        assert_ne!(first.id, second.id);
        assert_eq!(
            rt.active_focus_session().unwrap().map(|s| s.id),
            Some(second.id)
        );
        let history = rt.focus_sessions().unwrap();
        assert_eq!(history.len(), 2);
        let old = history.iter().find(|s| s.id == first.id).unwrap();
        assert_eq!(old.outcome.as_deref(), Some("abandoned"));
    }

    #[tokio::test]
    async fn a_session_nobody_came_back_to_is_lapsed_not_completed() {
        // The honesty rule the progressive-duration ladder depends on: a
        // session the user was absent for must not be able to claim a
        // completion, or the "practice" it measures never happened.
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let store = rt.ui.store_ref().unwrap();

        let long_ago = now_secs() - 6 * 3600;
        store
            .save_focus_session(&comrade_storage::FocusSession {
                id: timestamped_store_id(long_ago),
                intent: "yesterday's plan".into(),
                planned_minutes: 25,
                started_at: long_ago,
                ended_at: None,
                outcome: None,
            })
            .unwrap();

        // Reading the active session resolves the stale one and reports none.
        assert!(rt.active_focus_session().unwrap().is_none());
        let history = rt.focus_sessions().unwrap();
        assert_eq!(history.len(), 1);
        assert_eq!(history[0].outcome.as_deref(), Some("lapsed"));
        // It ended when its plan did — not hours later when someone looked.
        assert_eq!(history[0].ended_at, Some(long_ago + 25 * 60));
        // And it earns no credit toward the next rung.
        assert_eq!(rt.suggested_focus_minutes().unwrap(), 25);
        assert!(rt
            .focus_reflection("lapsed")
            .unwrap()
            .contains("Nothing lost"));
    }

    #[tokio::test]
    async fn reading_roundtrips_chunked_with_a_clamped_position() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();

        assert!(rt.reading().unwrap().is_none());
        assert!(matches!(
            rt.save_reading("t", "   "),
            Err(UiError::Engine(_))
        ));

        let text = "A paragraph worth a couple of minutes of attention.\n\n".repeat(80);
        let saved = rt.save_reading("  Walden  ", &text).unwrap();
        assert_eq!(saved.title, "Walden");
        assert!(saved.chunks.len() > 1);
        assert_eq!(saved.position, 0);
        // Losslessness carries through the DTO: the reader shows exactly what
        // was saved (the trailing trim is the only edit, and it is announced).
        assert_eq!(saved.chunks.concat(), text.trim());

        let moved = rt.set_reading_position(2).unwrap().unwrap();
        assert_eq!(moved.position, 2);
        assert_eq!(rt.reading().unwrap().unwrap().position, 2);
        // A position past the end is clamped, never trusted.
        let clamped = rt.set_reading_position(9_999).unwrap().unwrap();
        assert_eq!(clamped.position as usize, clamped.chunks.len() - 1);

        assert!(rt.clear_reading().unwrap());
        assert!(!rt.clear_reading().unwrap());
        assert!(rt.reading().unwrap().is_none());
        // With nothing saved, moving the position is a clean None.
        assert!(rt.set_reading_position(0).unwrap().is_none());
    }

    #[tokio::test]
    async fn tara_opener_nudges_on_a_heavy_scroll_day_but_mood_still_wins() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();

        // Baseline days plus a heavy "yesterday". Dates are chosen relative to
        // the real clock so the today/yesterday split is what the code sees.
        let today = iso_date(now_secs());
        let yesterday = iso_date(now_secs() - 86_400);
        for (date, doom) in [
            (iso_date(now_secs() - 4 * 86_400), 30u32),
            (iso_date(now_secs() - 3 * 86_400), 30),
            (iso_date(now_secs() - 2 * 86_400), 30),
        ] {
            rt.record_attention_day(&date, 200, 40, doom).unwrap();
        }
        rt.record_attention_day(&yesterday, 400, 120, 150).unwrap();
        rt.record_attention_day(&today, 10, 2, 0).unwrap();

        let opener = rt.tara_opener().unwrap();
        assert!(opener.contains("150 minutes"), "got: {opener}");
        assert!(opener.contains("No judgement"), "got: {opener}");

        // …but two low journal days outrank it: the heavier signal wins.
        rt.add_journal_entry("rough monday", Some("😞")).unwrap();
        rt.add_journal_entry("rough tuesday", Some("😕")).unwrap();
        assert!(rt.tara_opener().unwrap().contains("felt low"));
    }

    #[tokio::test]
    async fn tara_opener_is_unchanged_when_no_usage_is_recorded() {
        // The mirror is opt-in; someone who never grants usage access must see
        // exactly the opener that shipped before this pillar existed.
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        assert!(rt.tara_opener().unwrap().contains("not a therapist"));
    }

    #[test]
    fn iso_date_matches_known_epochs_and_shape_check_agrees() {
        assert_eq!(iso_date(0), "1970-01-01");
        assert_eq!(iso_date(1_700_000_000), "2023-11-14");
        // A leap day, and the day after.
        assert_eq!(iso_date(1_709_164_800), "2024-02-29");
        assert_eq!(iso_date(1_709_251_200), "2024-03-01");
        for s in ["1970-01-01", "2026-07-31"] {
            assert!(is_iso_date(s), "{s} should be accepted");
        }
        for s in ["2026-7-31", "31-07-2026", "2026/07/31", "2026-07-3x", ""] {
            assert!(!is_iso_date(s), "{s} should be rejected");
        }
    }

    #[test]
    fn journal_ids_sort_chronologically_and_never_collide() {
        let a = timestamped_store_id(5);
        let b = timestamped_store_id(5);
        let later = timestamped_store_id(1_700_000_000);
        assert_ne!(a, b, "same-second ids must differ");
        assert!(a < later && b < later, "timestamp prefix sorts");
    }

    #[tokio::test]
    async fn legacy_placeholder_petnames_no_longer_mask_published_names() {
        // Old builds auto-filled an empty alias with the first 12 chars of
        // the npub. Those placeholders must read as "no alias" so the peer's
        // published handle can title the chat after an upgrade.
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let store = rt.ui.store_ref().unwrap();

        let peer = nostr_sdk::Keys::generate()
            .public_key()
            .to_bech32()
            .unwrap();
        let placeholder: String = peer.chars().take(12).collect();
        store
            .upsert_contact(&comrade_storage::Contact {
                npub: peer.clone(),
                petname: placeholder.clone(),
                relays: vec![],
                comrade: false,
            })
            .unwrap();
        store
            .save_message(&comrade_storage::StoredMessage {
                id: "m1".into(),
                peer_npub: peer.clone(),
                content: "hi".into(),
                created_at: 1,
                outgoing: false,
                status: None,
                reply_to: None,
            })
            .unwrap();
        store
            .put(
                PEER_PROFILES_TREE,
                &peer,
                &PeerProfileRecord {
                    name: Some("charlie".into()),
                    about: None,
                    updated_at: 1,
                },
            )
            .unwrap();

        let convo = &rt.conversations().unwrap()[0];
        assert_eq!(convo.alias, None, "placeholder is not a user alias");
        assert_eq!(convo.peer_name.as_deref(), Some("charlie"));
        assert_eq!(rt.list_contacts().unwrap()[0].alias, "");

        // A real alias — even one that looks key-ish but isn't this npub's
        // prefix — still wins.
        assert_eq!(user_alias("Mom", &peer).as_deref(), Some("Mom"));
        assert_eq!(user_alias(&placeholder, &peer), None);
        assert_eq!(user_alias("  ", &peer), None);
        assert_eq!(
            user_alias("npub1someone", &peer).as_deref(),
            Some("npub1someone"),
            "a 12-char alias that is not this peer's prefix is kept"
        );
    }

    #[tokio::test]
    async fn conversations_and_contacts_carry_cached_peer_names() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let store = rt.ui.store_ref().unwrap();

        let peer = nostr_sdk::Keys::generate()
            .public_key()
            .to_bech32()
            .unwrap();
        store
            .save_message(&comrade_storage::StoredMessage {
                id: "m1".into(),
                peer_npub: peer.clone(),
                content: "hi".into(),
                created_at: 10,
                outgoing: false,
                status: None,
                reply_to: None,
            })
            .unwrap();
        // Simulate a discovered/refreshed profile in the cache.
        store
            .put(
                PEER_PROFILES_TREE,
                &peer,
                &PeerProfileRecord {
                    name: Some("charlie".into()),
                    about: None,
                    updated_at: 1,
                },
            )
            .unwrap();

        let convos = rt.conversations().unwrap();
        assert_eq!(convos.len(), 1);
        assert_eq!(convos[0].alias, None, "no user alias was set");
        assert_eq!(
            convos[0].peer_name.as_deref(),
            Some("charlie"),
            "published handle from the profile cache titles the chat"
        );

        rt.add_contact(&peer, "").unwrap();
        let contacts = rt.list_contacts().unwrap();
        assert_eq!(contacts[0].name.as_deref(), Some("charlie"));

        // A user alias always outranks the published handle in the DTO —
        // display precedence is enforced by returning both.
        rt.set_contact_alias(&peer, "My Buddy").unwrap();
        let convos = rt.conversations().unwrap();
        assert_eq!(convos[0].alias.as_deref(), Some("My Buddy"));
        assert_eq!(convos[0].peer_name.as_deref(), Some("charlie"));
    }

    #[tokio::test]
    async fn conversations_group_history_by_peer_newest_first() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let store = rt.ui.store_ref().unwrap();

        let alice = nostr_sdk::Keys::generate()
            .public_key()
            .to_bech32()
            .unwrap();
        let bob = nostr_sdk::Keys::generate()
            .public_key()
            .to_bech32()
            .unwrap();
        for (id, peer, content, at, outgoing) in [
            ("m1", &alice, "hi alice", 10u64, true),
            ("m2", &alice, "hello back", 20, false),
            ("m3", &bob, "yo bob", 15, true),
        ] {
            store
                .save_message(&comrade_storage::StoredMessage {
                    id: id.into(),
                    peer_npub: peer.to_string(),
                    content: content.into(),
                    created_at: at,
                    outgoing,
                    status: None,
                    reply_to: None,
                })
                .unwrap();
        }
        rt.add_contact(&alice, "Alice").unwrap();

        let convos = rt.conversations().unwrap();
        assert_eq!(convos.len(), 2);
        // Alice's thread is newest (t=20) and carries her saved alias.
        assert_eq!(convos[0].peer, alice);
        assert_eq!(convos[0].alias.as_deref(), Some("Alice"));
        assert_eq!(convos[0].last_message, "hello back");
        assert!(!convos[0].last_outgoing);
        assert_eq!(convos[1].peer, bob);
        assert_eq!(convos[1].alias, None);

        // Per-thread history comes back oldest-first for rendering.
        let msgs = rt.messages_with(&alice).unwrap();
        assert_eq!(
            msgs.iter().map(|m| m.id.as_str()).collect::<Vec<_>>(),
            ["m1", "m2"]
        );
    }

    #[tokio::test]
    async fn dm_and_profile_commands_reject_when_locked() {
        let mut rt = ComradeRuntime::new();
        assert!(matches!(
            rt.send_dm("npub1x", "hi").await,
            Err(UiError::VaultLocked)
        ));
        assert!(matches!(rt.conversations(), Err(UiError::VaultLocked)));
        assert!(matches!(rt.messages_with("x"), Err(UiError::VaultLocked)));
        assert!(matches!(rt.list_contacts(), Err(UiError::VaultLocked)));
        assert!(matches!(rt.profile(), Err(UiError::NoIdentity)));
        assert!(matches!(
            rt.set_username("neo").await,
            Err(UiError::NoIdentity)
        ));
        assert!(matches!(
            rt.search_profiles("neo").await,
            Err(UiError::VaultLocked)
        ));
        assert!(matches!(
            rt.refresh_peer_profiles().await,
            Err(UiError::VaultLocked)
        ));
        assert!(matches!(
            rt.set_contact_alias("npub1x", "a"),
            Err(UiError::VaultLocked)
        ));
        assert!(matches!(
            rt.remove_contact("npub1x"),
            Err(UiError::VaultLocked)
        ));
    }

    #[tokio::test]
    async fn send_dm_rejects_empty_and_bad_recipient() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        assert!(rt.send_dm("npub1notvalid", "hello").await.is_err());
        let ok = nostr_sdk::Keys::generate()
            .public_key()
            .to_bech32()
            .unwrap();
        assert!(matches!(
            rt.send_dm(&ok, "   ").await,
            Err(UiError::Engine(_))
        ));
    }

    #[tokio::test]
    async fn media_send_rejects_when_locked() {
        // No identity/engines yet → graceful typed error, no panic.
        let rt = ComradeRuntime::new();
        let err = rt
            .upload_and_send_media("npub1xxx", vec![1, 2, 3], "image/png", "")
            .await;
        assert!(err.is_err());
        let err = rt.download_and_decrypt_media("deadbeef").await;
        assert!(matches!(err, Err(UiError::VaultLocked)));
    }

    #[tokio::test]
    async fn media_send_pipeline_to_self_without_http_feature() {
        // Exercises identity + ECDH key derivation + encrypt + local-ref logic
        // up to the network boundary. With `media-http` off, the upload step
        // returns a typed error (no panic); the run never touches a relay.
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        let id = rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let res = rt
            .upload_and_send_media(&id.npub, vec![9, 8, 7, 6], "image/png", "selfie")
            .await;
        #[cfg(not(feature = "media-http"))]
        assert!(matches!(res, Err(UiError::Engine(_))));
        // (With the feature on this would attempt a real Blossom upload.)
        let _ = res;
    }

    #[tokio::test]
    async fn download_resolves_persisted_ref() {
        // A persisted ref is resolved and key-derivation runs; only the final
        // network fetch is gated by the feature.
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        let id = rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let peer_hex = nostr_sdk::PublicKey::parse(&id.npub).unwrap().to_hex();
        let reff = MediaRef {
            event_id: "evt1".into(),
            url: "https://blob.example/abc".into(),
            peer_pubkey: peer_hex,
            mime_type: "image/png".into(),
            caption: "x".into(),
            size: 3,
            sha256_hex: String::new(),
            outgoing: false,
            created_at: 1,
        };
        rt.ui
            .store_ref()
            .unwrap()
            .put(MEDIA_REFS_TREE, "evt1", &reff)
            .unwrap();

        let out = rt.download_and_decrypt_media("evt1").await;
        // Unknown id is a clean error; known id reaches the (gated) fetch step.
        assert!(rt.download_and_decrypt_media("nope").await.is_err());
        #[cfg(not(feature = "media-http"))]
        assert!(matches!(out, Err(UiError::Engine(_))));
        let _ = out;
    }

    #[tokio::test]
    async fn media_with_lists_history_oldest_first_with_correct_direction() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        let id = rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (peer_hex, peer_npub) = stranger();

        let incoming = MediaRef {
            event_id: "evt_in".into(),
            url: "https://blob.example/in".into(),
            peer_pubkey: peer_hex.clone(),
            mime_type: "image/png".into(),
            caption: "from them".into(),
            size: 3,
            sha256_hex: String::new(),
            outgoing: false,
            created_at: 10,
        };
        let outgoing = MediaRef {
            event_id: "evt_out".into(),
            url: "https://blob.example/out".into(),
            peer_pubkey: peer_hex,
            mime_type: "audio/ogg".into(),
            caption: "from me".into(),
            size: 5,
            sha256_hex: String::new(),
            outgoing: true,
            created_at: 20,
        };
        let store = rt.ui.store_ref().unwrap();
        store.put(MEDIA_REFS_TREE, "evt_in", &incoming).unwrap();
        store.put(MEDIA_REFS_TREE, "evt_out", &outgoing).unwrap();

        let history = rt.media_with(&peer_npub).unwrap();
        assert_eq!(history.len(), 2);
        assert_eq!(history[0].event_id, "evt_in");
        assert!(!history[0].outgoing);
        assert_eq!(history[0].sender, peer_npub);
        assert_eq!(history[1].event_id, "evt_out");
        assert!(history[1].outgoing);
        assert_eq!(history[1].sender, id.npub);
    }

    #[tokio::test]
    async fn media_with_rejects_when_locked_and_is_empty_for_a_stranger() {
        let rt = ComradeRuntime::new();
        let (_, peer_npub) = stranger();
        assert!(matches!(
            rt.media_with(&peer_npub),
            Err(UiError::VaultLocked)
        ));

        let dir = TempDir::new().unwrap();
        let mut rt2 = ComradeRuntime::new();
        rt2.unlock_vault(dir.path(), "pin").await.unwrap();
        assert!(rt2.media_with(&peer_npub).unwrap().is_empty());
    }

    // ── Message requests, receipts, and calls ────────────────────────────────

    fn stranger() -> (String, String) {
        let pk = nostr_sdk::Keys::generate().public_key();
        (pk.to_hex(), pk.to_bech32().unwrap())
    }

    #[tokio::test]
    async fn strangers_are_gated_into_requests_then_accepted() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (_hex, peer) = stranger();
        let store = rt.ui.store_ref().unwrap();

        // Simulate the inbox loop recording a stranger's DM: pending + message.
        store
            .set_conversation_meta(&comrade_storage::ConversationMeta {
                peer_npub: peer.clone(),
                state: "pending".into(),
                profile_shared: false,
                last_read_at: 0,
                updated_at: 1,
            })
            .unwrap();
        store
            .save_message(&comrade_storage::StoredMessage {
                id: "in1".into(),
                peer_npub: peer.clone(),
                content: "hi, can we talk?".into(),
                created_at: 5,
                outgoing: false,
                status: None,
                reply_to: None,
            })
            .unwrap();

        // Gated out of the chat list; present as a request.
        assert!(rt.conversations().unwrap().is_empty());
        let reqs = rt.message_requests().unwrap();
        assert_eq!(reqs.len(), 1);
        assert_eq!(reqs[0].peer, peer);
        assert_eq!(reqs[0].last_message, "hi, can we talk?");

        // Accept → into the chat list, out of requests.
        rt.accept_request(&peer).unwrap();
        assert!(rt.message_requests().unwrap().is_empty());
        let convos = rt.conversations().unwrap();
        assert_eq!(convos.len(), 1);
        assert_eq!(convos[0].peer, peer);
    }

    #[tokio::test]
    async fn marking_read_reports_the_previous_position_and_watermarks_the_newest() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (_hex, peer) = stranger();
        let store = rt.ui.store_ref().unwrap();
        let save = |id: &str, at: u64| {
            store
                .save_message(&comrade_storage::StoredMessage {
                    id: id.into(),
                    peer_npub: peer.clone(),
                    content: "hi".into(),
                    created_at: at,
                    outgoing: false,
                    status: None,
                    reply_to: None,
                })
                .unwrap();
        };
        save("in1", 100);
        save("in2", 200);

        // First visit: nothing had been read, so the UI gets 0 and opens at the
        // newest message with no divider.
        assert_eq!(rt.mark_conversation_read(&peer).unwrap(), 0);
        assert_eq!(store.read_position(&peer).unwrap(), 200);

        // Two more arrive while away; re-opening reports where they had been,
        // which is what the divider is drawn from.
        save("in3", 300);
        save("in4", 400);
        assert_eq!(rt.mark_conversation_read(&peer).unwrap(), 200);
        assert_eq!(store.read_position(&peer).unwrap(), 400);

        // Re-opening with nothing new still reports the position, so a thread
        // with no unread messages simply opens at the bottom.
        assert_eq!(rt.mark_conversation_read(&peer).unwrap(), 400);
    }

    #[tokio::test]
    async fn marking_a_pending_request_read_records_nothing() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (_hex, peer) = stranger();
        let store = rt.ui.store_ref().unwrap();
        store
            .set_conversation_meta(&comrade_storage::ConversationMeta {
                peer_npub: peer.clone(),
                state: "pending".into(),
                profile_shared: false,
                last_read_at: 0,
                updated_at: 1,
            })
            .unwrap();
        store
            .save_message(&comrade_storage::StoredMessage {
                id: "in1".into(),
                peer_npub: peer.clone(),
                content: "hi, can we talk?".into(),
                created_at: 5,
                outgoing: false,
                status: None,
                reply_to: None,
            })
            .unwrap();

        // Not just "no receipt": no *stored* position either. Recording one
        // would be a local trace that we read a request before deciding on it,
        // and it would survive into the accepted thread.
        assert_eq!(rt.mark_conversation_read(&peer).unwrap(), 0);
        assert_eq!(store.read_position(&peer).unwrap(), 0);
        let meta = store.get_conversation_meta(&peer).unwrap().unwrap();
        assert_eq!(meta.state, "pending");
    }

    #[tokio::test]
    async fn blocked_peer_is_hidden_from_both_lists() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (_hex, peer) = stranger();
        rt.ui
            .store_ref()
            .unwrap()
            .save_message(&comrade_storage::StoredMessage {
                id: "in1".into(),
                peer_npub: peer.clone(),
                content: "spam".into(),
                created_at: 1,
                outgoing: false,
                status: None,
                reply_to: None,
            })
            .unwrap();
        rt.block_conversation(&peer).unwrap();
        assert!(rt.conversations().unwrap().is_empty());
        assert!(rt.message_requests().unwrap().is_empty());
    }

    /// Persist an attachment reference exactly as the send/receive paths do.
    fn save_media_ref(
        store: &comrade_storage::EncryptedStore,
        event_id: &str,
        peer_hex: &str,
        caption: &str,
        created_at: u64,
        outgoing: bool,
    ) {
        store
            .put(
                MEDIA_REFS_TREE,
                event_id,
                &MediaRef {
                    event_id: event_id.into(),
                    url: "https://blob.example/x".into(),
                    peer_pubkey: peer_hex.into(),
                    mime_type: "image/jpeg".into(),
                    caption: caption.into(),
                    size: 1234,
                    sha256_hex: "a".repeat(64),
                    outgoing,
                    created_at,
                },
            )
            .unwrap();
        store.flush().unwrap();
    }

    #[tokio::test]
    async fn a_thread_of_only_attachments_still_appears_in_the_chat_list() {
        // Media references are not stored as messages, so a chat list built from
        // messages alone made a photo-only conversation invisible — the thread
        // existed, the photo was in the store, and the list showed nothing.
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (hex, peer) = stranger();
        let store = rt.ui.store_ref().unwrap();
        accepted_peer(store, &peer, false);
        save_media_ref(store, "m1", &hex, "sunset.jpg", 100, false);

        let convos = rt.conversations().unwrap();
        assert_eq!(convos.len(), 1);
        assert_eq!(convos[0].peer, peer);
        assert_eq!(convos[0].last_message, "📎 sunset.jpg");
        assert_eq!(convos[0].last_at, 100);
        assert!(!convos[0].last_outgoing);
    }

    #[tokio::test]
    async fn the_chat_list_preview_follows_whichever_is_newer() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (hex, peer) = stranger();
        let store = rt.ui.store_ref().unwrap();
        accepted_peer(store, &peer, false);
        store
            .save_message(&comrade_storage::StoredMessage {
                id: "in1".into(),
                peer_npub: peer.clone(),
                content: "look at this".into(),
                created_at: 100,
                outgoing: false,
                status: None,
                reply_to: None,
            })
            .unwrap();

        // Older attachment: the text still wins.
        save_media_ref(store, "m1", &hex, "old.jpg", 50, false);
        assert_eq!(rt.conversations().unwrap()[0].last_message, "look at this");

        // Newer attachment, sent by us: the row becomes the attachment, and
        // `last_outgoing` follows it (the list renders a "You: " prefix from it).
        save_media_ref(store, "m2", &hex, "", 150, true);
        let convo = &rt.conversations().unwrap()[0];
        assert_eq!(convo.last_message, "📎 Attachment");
        assert_eq!(convo.last_at, 150);
        assert!(convo.last_outgoing);
    }

    #[tokio::test]
    async fn a_stranger_whose_only_contact_was_an_attachment_is_a_previewable_request() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (hex, peer) = stranger();
        let store = rt.ui.store_ref().unwrap();
        store
            .set_conversation_meta(&comrade_storage::ConversationMeta {
                peer_npub: peer.clone(),
                state: STATE_PENDING.into(),
                profile_shared: false,
                last_read_at: 0,
                updated_at: 1,
            })
            .unwrap();
        save_media_ref(store, "m1", &hex, "receipt.pdf", 42, false);

        // Gated out of the chat list...
        assert!(rt.conversations().unwrap().is_empty());
        // ...and a request with a real preview rather than an empty line.
        let reqs = rt.message_requests().unwrap();
        assert_eq!(reqs.len(), 1);
        assert_eq!(reqs[0].peer, peer);
        assert_eq!(reqs[0].last_message, "📎 receipt.pdf");
        assert_eq!(reqs[0].last_at, 42);
    }

    #[tokio::test]
    async fn sending_media_refuses_an_empty_or_unusable_payload_before_uploading() {
        // These must fail *before* the network: the alternative is paying for an
        // upload and putting an undecodable bubble in both people's threads.
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (_hex, peer) = stranger();

        let empty = rt
            .upload_and_send_media(&peer, vec![], "image/png", "")
            .await;
        assert!(matches!(empty, Err(UiError::Engine(_))), "empty payload");

        let no_mime = rt
            .upload_and_send_media(&peer, vec![1, 2, 3], "  ", "")
            .await;
        assert!(matches!(no_mime, Err(UiError::Engine(_))), "blank MIME");

        let oversized = rt
            .upload_and_send_media(&peer, vec![0u8; MAX_MEDIA_BYTES + 1], "image/png", "")
            .await;
        assert!(matches!(oversized, Err(UiError::Engine(_))), "over the cap");
    }

    #[tokio::test]
    async fn incoming_media_is_sanitised_before_it_is_stored_or_surfaced() {
        let dir = TempDir::new().unwrap();
        let store = Arc::new(comrade_storage::EncryptedStore::open(dir.path(), "pin").unwrap());
        let vault = test_vault().await;
        let (tx, mut rx) = broadcast::channel(16);
        let dedup = SeenSet::new(CALL_SIGNAL_DEDUP_CAPACITY);
        let outbox = Arc::new(Outbox::new());
        let transport_dedup = SeenSet::with_ttl(
            CROSS_TRANSPORT_DEDUP_CAPACITY,
            std::time::Duration::from_secs(CROSS_TRANSPORT_DEDUP_SECS),
        );
        let route = relay_route(&transport_dedup);
        let (hex, peer) = stranger();
        accepted_peer(&store, &peer, false);

        let envelope = serde_json::to_string(&MediaEnvelope {
            comrade_media: 1,
            event_id: "m1".into(),
            url: "https://blob.example/x".into(),
            // A peer can claim anything; the type decides which renderer every
            // frontend reaches for.
            mime: "IMAGE/JPEG".into(),
            caption: "holiday\r\nDelivered ✓✓".into(),
            size: 10,
            sha256_hex: "a".repeat(64),
        })
        .unwrap();
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            incoming(&hex, "e1", &envelope),
        );

        let stored: MediaRef = store.get(MEDIA_REFS_TREE, "m1").unwrap().unwrap();
        assert_eq!(stored.mime_type, "image/jpeg");
        assert_eq!(stored.caption, "holidayDelivered ✓✓");
        match rx.try_recv().unwrap() {
            BridgeEvent::IncomingMedia(m) => {
                assert_eq!(m.mime_type, "image/jpeg");
                assert_eq!(m.caption, "holidayDelivered ✓✓");
                assert_eq!(m.sender, peer);
                assert!(!m.outgoing);
            }
            other => panic!("expected IncomingMedia, got {other:?}"),
        }

        // An unusable MIME type downgrades the renderer; it never drops the
        // attachment (the blob still downloads and opens).
        let odd = serde_json::to_string(&MediaEnvelope {
            comrade_media: 1,
            event_id: "m2".into(),
            url: "https://blob.example/y".into(),
            mime: "not a mime type".into(),
            caption: String::new(),
            size: 10,
            sha256_hex: String::new(),
        })
        .unwrap();
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            incoming(&hex, "e2", &odd),
        );
        let stored: MediaRef = store.get(MEDIA_REFS_TREE, "m2").unwrap().unwrap();
        assert_eq!(stored.mime_type, DEFAULT_MIME);
    }

    #[tokio::test]
    async fn call_log_roundtrips_per_peer_and_globally() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (_hex, peer) = stranger();
        let rec = rt
            .log_call(&peer, "call1", "video", true, "connected", 100, 42)
            .unwrap();
        assert_eq!(rec.media, "video");
        assert_eq!(rec.outcome, "connected");
        assert_eq!(rec.duration_secs, 42);
        assert_eq!(rt.call_history(Some(&peer)).unwrap().len(), 1);
        assert_eq!(rt.call_history(None).unwrap().len(), 1);
        // Unknown media string is coerced to audio.
        let rec2 = rt
            .log_call(&peer, "call2", "hologram", false, "missed", 0, 0)
            .unwrap();
        assert_eq!(rec2.media, "audio");
    }

    #[tokio::test]
    async fn ice_servers_default_stun_and_configurable_turn() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let defaults = rt.call_ice_servers();
        assert!(!defaults.is_empty());
        assert!(defaults.iter().all(|s| s.username.is_none()));
        rt.set_turn_server("turn:turn.example.com:3478", "u", "p")
            .unwrap();
        let with_turn = rt.call_ice_servers();
        assert_eq!(with_turn.len(), defaults.len() + 1);
        assert_eq!(with_turn.last().unwrap().username.as_deref(), Some("u"));
        rt.set_turn_server("", "", "").unwrap();
        assert_eq!(rt.call_ice_servers().len(), defaults.len());
    }

    #[tokio::test]
    async fn set_turn_server_rejects_a_malformed_url_and_does_not_persist_it() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();

        let err = rt.set_turn_server("not-a-turn-url", "u", "p");
        assert!(matches!(err, Err(UiError::Engine(_))));
        assert_eq!(
            rt.turn_server_status(),
            TurnServerStatusDto {
                configured: false,
                url: None
            },
            "a rejected URL must never be persisted"
        );
    }

    #[tokio::test]
    async fn turn_server_status_reports_url_but_never_the_credential() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();

        // Pre-unlock: an honest "nothing configured", not an error.
        assert_eq!(
            rt.turn_server_status(),
            TurnServerStatusDto {
                configured: false,
                url: None
            }
        );

        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        rt.set_turn_server("turn:turn.example.com:3478", "u", "top-secret")
            .unwrap();
        let status = rt.turn_server_status();
        assert!(status.configured);
        assert_eq!(status.url.as_deref(), Some("turn:turn.example.com:3478"));
        // `TurnServerStatusDto` structurally has no credential field to leak —
        // this asserts the serialised form as a belt-and-suspenders check
        // that never regresses into adding one back.
        let json = serde_json::to_string(&status).unwrap();
        assert!(!json.contains("top-secret"));
        assert!(!json.contains("\"u\""));

        rt.set_turn_server("", "", "").unwrap();
        assert!(!rt.turn_server_status().configured);
    }

    #[tokio::test]
    async fn call_ice_servers_for_stun_only_never_leaks_turn_credentials() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        rt.set_turn_server("turn:turn.example.com:3478", "u", "p")
            .unwrap();

        // Even with a TURN relay configured, an explicit stun_only ask (what
        // place_call uses) must never include it.
        let stun_only = rt.call_ice_servers_for("stun_only");
        assert!(stun_only.iter().all(|s| s.username.is_none()));

        // The fallback a frontend calls after ICE fails to connect does
        // include it.
        let fallback = rt.call_ice_servers_for("stun_and_turn");
        assert_eq!(fallback.last().unwrap().username.as_deref(), Some("u"));
        assert_eq!(fallback.len(), stun_only.len() + 1);

        // Garbage input defaults to the private stun_only behavior.
        assert_eq!(rt.call_ice_servers_for("nonsense").len(), stun_only.len());
    }

    #[test]
    fn call_sas_delegates_to_comrade_core_and_needs_no_vault() {
        // Pure computation over the two SDP strings already in hand — unlike
        // the ICE-server methods above, it touches no store, so a bare
        // `ComradeRuntime::new()` (never unlocked) must work.
        let rt = ComradeRuntime::new();
        let sdp_a = "v=0\r\na=fingerprint:sha-256 AA:BB:CC:DD\r\n";
        let sdp_b = "v=0\r\na=fingerprint:sha-256 11:22:33:44\r\n";

        let sas = rt
            .call_sas(sdp_a, sdp_b)
            .expect("both sides have a fingerprint");
        assert_eq!(sas.len(), 4);
        // Same property `derive_sas` itself guarantees — checked again here so
        // a future refactor that swaps the delegation's argument order (the
        // one mistake that would silently break verification) fails a test.
        assert_eq!(rt.call_sas(sdp_a, sdp_b), rt.call_sas(sdp_b, sdp_a));

        let no_fingerprint = "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\n";
        assert_eq!(rt.call_sas(sdp_a, no_fingerprint), None);
    }

    #[tokio::test]
    async fn place_call_starts_stun_only_even_with_turn_configured() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        rt.set_turn_server("turn:turn.example.com:3478", "u", "p")
            .unwrap();

        let (_hex, peer) = stranger();
        let session = rt.place_call(&peer, "audio").unwrap();
        assert!(
            session.ice_servers.iter().all(|s| s.username.is_none()),
            "the initial offer must not contact the TURN relay unless STUN fails"
        );
    }

    #[tokio::test]
    async fn place_call_mints_session_and_validates() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        assert!(matches!(
            rt.place_call("npub1x", "audio"),
            Err(UiError::VaultLocked)
        ));
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (_hex, peer) = stranger();
        let session = rt.place_call(&peer, "video").unwrap();
        assert_eq!(session.peer, peer);
        assert_eq!(session.media, "video");
        assert_eq!(session.call_id.len(), 32);
        assert!(!session.ice_servers.is_empty());
        assert!(rt.place_call("not-a-key", "audio").is_err());
    }

    #[tokio::test]
    async fn send_call_signal_rejects_malformed_json() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (_hex, peer) = stranger();
        let err = rt
            .send_call_signal(&peer, "c1", "audio", "{not valid")
            .await;
        assert!(matches!(err, Err(UiError::Engine(_))));
    }

    async fn test_vault() -> Arc<VaultEngine> {
        let keys = nostr_sdk::Keys::generate();
        Arc::new(
            VaultEngine::new(&keys, vec!["wss://relay.damus.io".into()])
                .await
                .unwrap(),
        )
    }

    /// A relay-delivered route with no mesh — what most ingress tests want.
    fn relay_route(dedup: &SeenSet) -> DmRoute<'_> {
        DmRoute {
            label: TRANSPORT_RELAY,
            dedup,
            mesh: None,
        }
    }

    fn incoming(sender_hex: &str, event_id: &str, content: &str) -> VaultMessage {
        VaultMessage {
            event_id: event_id.into(),
            sender_pubkey: sender_hex.into(),
            content: content.into(),
            created_at: 3,
            upi_intents: vec![],
            reply_to: None,
        }
    }

    #[tokio::test]
    async fn dispatch_gates_unknown_sender_as_request() {
        let dir = TempDir::new().unwrap();
        let store = Arc::new(comrade_storage::EncryptedStore::open(dir.path(), "pin").unwrap());
        let vault = test_vault().await;
        let (tx, mut rx) = broadcast::channel(16);
        let dedup = SeenSet::new(CALL_SIGNAL_DEDUP_CAPACITY);
        let outbox = Arc::new(Outbox::new());
        let transport_dedup = SeenSet::with_ttl(
            CROSS_TRANSPORT_DEDUP_CAPACITY,
            std::time::Duration::from_secs(CROSS_TRANSPORT_DEDUP_SECS),
        );
        let route = relay_route(&transport_dedup);
        let (hex, peer) = stranger();

        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            incoming(&hex, "e1", "hello?"),
        );

        assert_eq!(
            store.get_conversation_meta(&peer).unwrap().unwrap().state,
            "pending"
        );
        assert_eq!(store.messages_with(&peer).unwrap().len(), 1);
        match rx.try_recv().unwrap() {
            BridgeEvent::IncomingMessageRequest(r) => {
                assert_eq!(r.peer, peer);
                assert_eq!(r.last_message, "hello?");
            }
            other => panic!("expected request, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn dispatch_delivers_accepted_and_advances_receipts() {
        let dir = TempDir::new().unwrap();
        let store = Arc::new(comrade_storage::EncryptedStore::open(dir.path(), "pin").unwrap());
        let vault = test_vault().await;
        let (tx, mut rx) = broadcast::channel(16);
        let dedup = SeenSet::new(CALL_SIGNAL_DEDUP_CAPACITY);
        let outbox = Arc::new(Outbox::new());
        let transport_dedup = SeenSet::with_ttl(
            CROSS_TRANSPORT_DEDUP_CAPACITY,
            std::time::Duration::from_secs(CROSS_TRANSPORT_DEDUP_SECS),
        );
        let route = relay_route(&transport_dedup);
        let (hex, peer) = stranger();
        store
            .set_conversation_meta(&comrade_storage::ConversationMeta {
                peer_npub: peer.clone(),
                state: "accepted".into(),
                profile_shared: true,
                last_read_at: 0,
                updated_at: 1,
            })
            .unwrap();

        // Plain text from an accepted peer is delivered (not gated).
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            incoming(&hex, "e1", "yo"),
        );
        assert!(matches!(
            rx.try_recv().unwrap(),
            BridgeEvent::IncomingDirectMessage(_)
        ));

        // A read receipt advances one of our outgoing messages.
        store
            .save_message(&comrade_storage::StoredMessage {
                id: "out1".into(),
                peer_npub: peer.clone(),
                content: "sup".into(),
                created_at: 2,
                outgoing: true,
                status: Some("sent".into()),
                reply_to: None,
            })
            .unwrap();
        let receipt = Receipt::new(ReceiptKind::Read, vec!["out1".into()])
            .to_json()
            .unwrap();
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            incoming(&hex, "e2", &receipt),
        );
        assert_eq!(
            store
                .get_message("out1")
                .unwrap()
                .unwrap()
                .status
                .as_deref(),
            Some("read")
        );
        match rx.try_recv().unwrap() {
            BridgeEvent::MessageStatus {
                message_ids,
                status,
                ..
            } => {
                assert_eq!(status, "read");
                assert_eq!(message_ids, vec!["out1".to_string()]);
            }
            other => panic!("expected MessageStatus, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn dispatch_drops_blocked_and_caches_profile_share() {
        let dir = TempDir::new().unwrap();
        let store = Arc::new(comrade_storage::EncryptedStore::open(dir.path(), "pin").unwrap());
        let vault = test_vault().await;
        let (tx, mut rx) = broadcast::channel(16);
        let dedup = SeenSet::new(CALL_SIGNAL_DEDUP_CAPACITY);
        let outbox = Arc::new(Outbox::new());
        let transport_dedup = SeenSet::with_ttl(
            CROSS_TRANSPORT_DEDUP_CAPACITY,
            std::time::Duration::from_secs(CROSS_TRANSPORT_DEDUP_SECS),
        );
        let route = relay_route(&transport_dedup);
        let (hex, peer) = stranger();
        store
            .set_conversation_meta(&comrade_storage::ConversationMeta {
                peer_npub: peer.clone(),
                state: "blocked".into(),
                profile_shared: false,
                last_read_at: 0,
                updated_at: 1,
            })
            .unwrap();
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            incoming(&hex, "e1", "let me in"),
        );
        assert!(store.messages_with(&peer).unwrap().is_empty());
        assert!(rx.try_recv().is_err(), "blocked peer emits nothing");

        // A profile share (any non-blocked peer) caches the name + emits update.
        let (other_hex, other_npub) = stranger();
        let share = ProfileShare::new(Some("charlie".into())).to_json().unwrap();
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            incoming(&other_hex, "e2", &share),
        );
        match rx.try_recv().unwrap() {
            BridgeEvent::PeerProfileUpdated { peer, name } => {
                assert_eq!(peer, other_npub);
                assert_eq!(name.as_deref(), Some("charlie"));
            }
            other => panic!("expected PeerProfileUpdated, got {other:?}"),
        }
    }

    // ── Comrade presence ─────────────────────────────────────────────────────

    /// An incoming DM stamped with a caller-chosen send time — presence is
    /// all about freshness, so unlike [`incoming`] (fixed at epoch+3, which
    /// every beacon would read as long expired) these tests set it per case.
    fn incoming_at(
        sender_hex: &str,
        event_id: &str,
        content: &str,
        created_at: u64,
    ) -> VaultMessage {
        VaultMessage {
            created_at,
            ..incoming(sender_hex, event_id, content)
        }
    }

    /// A store with `peer` as an accepted conversation, optionally marked as
    /// one of our comrades — the two axes every presence rule turns on.
    fn accepted_peer(store: &comrade_storage::EncryptedStore, peer: &str, our_comrade: bool) {
        store
            .set_conversation_meta(&comrade_storage::ConversationMeta {
                peer_npub: peer.to_string(),
                state: "accepted".into(),
                profile_shared: true,
                last_read_at: 0,
                updated_at: 1,
            })
            .unwrap();
        if our_comrade {
            store.set_contact_comrade(peer, true).unwrap();
        }
    }

    #[tokio::test]
    async fn choosing_a_comrade_is_opt_in_reversible_and_visible_everywhere() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        assert!(matches!(rt.comrades(), Err(UiError::VaultLocked)));
        assert!(matches!(
            rt.set_comrade("npub1x", true),
            Err(UiError::VaultLocked)
        ));

        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (_hex, peer) = stranger();
        assert!(
            rt.comrades().unwrap().is_empty(),
            "nobody is chosen by default"
        );

        // Marking works straight from a conversation — no prior contact row.
        let marked = rt.set_comrade(&peer, true).unwrap();
        assert!(marked.comrade);
        let comrades = rt.comrades().unwrap();
        assert_eq!(comrades.len(), 1);
        assert_eq!(comrades[0].npub, peer);
        assert!(!comrades[0].online, "no beacon yet ⇒ not online");
        assert_eq!(comrades[0].last_seen_at, 0);
        assert!(
            !comrades[0].peer_marked_us,
            "choosing someone says nothing about whether they chose us"
        );
        assert!(rt.list_contacts().unwrap()[0].comrade);

        // Editing the alias must not silently un-choose them.
        rt.set_contact_alias(&peer, "Ana").unwrap();
        assert!(rt.list_contacts().unwrap()[0].comrade);
        assert_eq!(rt.comrades().unwrap()[0].alias, "Ana");

        // …and un-choosing leaves the contact (and its alias) intact.
        let unmarked = rt.set_comrade(&peer, false).unwrap();
        assert!(!unmarked.comrade);
        assert!(rt.comrades().unwrap().is_empty());
        assert_eq!(rt.list_contacts().unwrap()[0].alias, "Ana");

        assert!(matches!(
            rt.set_comrade("junk", true),
            Err(UiError::Engine(_))
        ));
        assert!(matches!(rt.peer_presence("junk"), Err(UiError::Engine(_))));
    }

    #[tokio::test]
    async fn a_presence_event_carries_a_usable_name_or_none_at_all() {
        // The name rides the event so a frontend can title a notification
        // without a store round-trip — which makes a blank one worse than
        // useless ("  is online"). Alias wins, a published handle is next,
        // and anything whitespace-only is no name at all.
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (_hex, peer) = stranger();
        let store = rt.ui.store_arc().unwrap();

        assert_eq!(presence_display_name(&store, &peer), None, "no contact yet");

        rt.set_comrade(&peer, true).unwrap();
        assert_eq!(
            presence_display_name(&store, &peer),
            None,
            "choosing someone doesn't invent a name for them"
        );

        store.set_contact_comrade(&peer, true).unwrap();
        rt.set_contact_alias(&peer, "   ").unwrap();
        assert_eq!(
            presence_display_name(&store, &peer),
            None,
            "a whitespace alias is not a name"
        );

        rt.set_contact_alias(&peer, "  Ana  ").unwrap();
        assert_eq!(presence_display_name(&store, &peer).as_deref(), Some("Ana"));
    }

    #[tokio::test]
    async fn a_comrade_coming_online_is_announced_once_and_a_heartbeat_is_not() {
        let dir = TempDir::new().unwrap();
        let store = Arc::new(comrade_storage::EncryptedStore::open(dir.path(), "pin").unwrap());
        let vault = test_vault().await;
        let (tx, mut rx) = broadcast::channel(16);
        let dedup = SeenSet::new(CALL_SIGNAL_DEDUP_CAPACITY);
        let outbox = Arc::new(Outbox::new());
        let transport_dedup = SeenSet::with_ttl(
            CROSS_TRANSPORT_DEDUP_CAPACITY,
            std::time::Duration::from_secs(CROSS_TRANSPORT_DEDUP_SECS),
        );
        let route = relay_route(&transport_dedup);
        let (hex, peer) = stranger();
        accepted_peer(&store, &peer, true);
        let now = now_secs();

        let beacon = PresenceBeacon::online().to_json().unwrap();
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            incoming_at(&hex, "e1", &beacon, now),
        );
        match rx.try_recv().unwrap() {
            BridgeEvent::ComradePresence {
                peer: p,
                online,
                at,
                ..
            } => {
                assert_eq!(p, peer);
                assert!(online);
                assert_eq!(at, now);
            }
            other => panic!("expected ComradePresence, got {other:?}"),
        }
        // A beacon is never a chat message, a request, or a stored DM.
        assert!(store.messages_with(&peer).unwrap().is_empty());

        // The heartbeat that follows is state, not news.
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            incoming_at(&hex, "e2", &beacon, now + 1),
        );
        assert!(
            rx.try_recv().is_err(),
            "a heartbeat from someone already online must not re-notify"
        );

        // Going offline is a transition, so it is announced.
        let bye = PresenceBeacon::offline().to_json().unwrap();
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            incoming_at(&hex, "e3", &bye, now + 2),
        );
        match rx.try_recv().unwrap() {
            BridgeEvent::ComradePresence { online, .. } => assert!(!online),
            other => panic!("expected an offline ComradePresence, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn a_replayed_or_out_of_order_beacon_never_resurrects_a_green_dot() {
        let dir = TempDir::new().unwrap();
        let store = Arc::new(comrade_storage::EncryptedStore::open(dir.path(), "pin").unwrap());
        let vault = test_vault().await;
        let (tx, mut rx) = broadcast::channel(16);
        let dedup = SeenSet::new(CALL_SIGNAL_DEDUP_CAPACITY);
        let outbox = Arc::new(Outbox::new());
        let transport_dedup = SeenSet::with_ttl(
            CROSS_TRANSPORT_DEDUP_CAPACITY,
            std::time::Duration::from_secs(CROSS_TRANSPORT_DEDUP_SECS),
        );
        let route = relay_route(&transport_dedup);
        let (hex, peer) = stranger();
        accepted_peer(&store, &peer, true);
        let now = now_secs();
        let beacon = PresenceBeacon::online().to_json().unwrap();

        // The inbox backfills up to two days on every launch; that replay
        // must not claim someone is online right now.
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            incoming_at(&hex, "old", &beacon, now - 2 * 24 * 60 * 60),
        );
        assert!(rx.try_recv().is_err(), "a stale beacon emits nothing");
        assert!(store.get_peer_presence(&peer).unwrap().is_none());

        // A fresh one does, and a late-arriving *older* beacon can't undo it.
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            incoming_at(&hex, "new", &beacon, now),
        );
        assert!(matches!(
            rx.try_recv().unwrap(),
            BridgeEvent::ComradePresence { online: true, .. }
        ));
        let stale_bye = PresenceBeacon::offline().to_json().unwrap();
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            incoming_at(&hex, "late-bye", &stale_bye, now - 60),
        );
        assert!(
            rx.try_recv().is_err(),
            "an out-of-order goodbye must not rewind fresher state"
        );
        assert!(store.get_peer_presence(&peer).unwrap().unwrap().online);
    }

    #[tokio::test]
    async fn presence_from_a_stranger_is_dropped_and_from_a_non_comrade_is_silent() {
        let dir = TempDir::new().unwrap();
        let store = Arc::new(comrade_storage::EncryptedStore::open(dir.path(), "pin").unwrap());
        let vault = test_vault().await;
        let (tx, mut rx) = broadcast::channel(16);
        let dedup = SeenSet::new(CALL_SIGNAL_DEDUP_CAPACITY);
        let outbox = Arc::new(Outbox::new());
        let transport_dedup = SeenSet::with_ttl(
            CROSS_TRANSPORT_DEDUP_CAPACITY,
            std::time::Duration::from_secs(CROSS_TRANSPORT_DEDUP_SECS),
        );
        let route = relay_route(&transport_dedup);
        let now = now_secs();
        let beacon = PresenceBeacon::online().to_json().unwrap();

        // An unaccepted stranger cannot push presence state at us — and their
        // beacon must not leak into the requests bucket as raw JSON either.
        let (stranger_hex, stranger_npub) = stranger();
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            incoming_at(&stranger_hex, "s1", &beacon, now),
        );
        assert!(rx.try_recv().is_err());
        assert!(store.get_peer_presence(&stranger_npub).unwrap().is_none());
        assert!(store.messages_with(&stranger_npub).unwrap().is_empty());
        assert!(store
            .get_conversation_meta(&stranger_npub)
            .unwrap()
            .is_none());

        // An accepted peer we have *not* chosen: recorded (it proves they
        // chose us — the reciprocity hint) but never announced.
        let (hex, peer) = stranger();
        accepted_peer(&store, &peer, false);
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            incoming_at(&hex, "a1", &beacon, now),
        );
        assert!(
            rx.try_recv().is_err(),
            "presence for someone we didn't choose is not news"
        );
        let recorded = store.get_peer_presence(&peer).unwrap().unwrap();
        assert!(recorded.online);
        assert!(recorded.peer_marked_us);
    }

    /// Everything `dispatch_incoming_dm` needs, for the nudge cases below —
    /// the presence tests above spell the same set out inline, which is
    /// exactly why the fourth copy became a helper.
    struct Ingress {
        vault: Arc<VaultEngine>,
        store: Arc<comrade_storage::EncryptedStore>,
        dedup: SeenSet,
        outbox: Arc<Outbox>,
        transport_dedup: SeenSet,
        events: broadcast::Sender<BridgeEvent>,
    }

    impl Ingress {
        async fn new(dir: &TempDir) -> (Self, broadcast::Receiver<BridgeEvent>) {
            let (events, rx) = broadcast::channel(16);
            let ingress = Self {
                vault: test_vault().await,
                store: Arc::new(comrade_storage::EncryptedStore::open(dir.path(), "pin").unwrap()),
                dedup: SeenSet::new(CALL_SIGNAL_DEDUP_CAPACITY),
                outbox: Arc::new(Outbox::new()),
                transport_dedup: SeenSet::with_ttl(
                    CROSS_TRANSPORT_DEDUP_CAPACITY,
                    std::time::Duration::from_secs(CROSS_TRANSPORT_DEDUP_SECS),
                ),
                events,
            };
            (ingress, rx)
        }

        fn deliver(&self, sender_hex: &str, event_id: &str, content: &str, created_at: u64) {
            dispatch_incoming_dm(
                &self.vault,
                Some(&self.store),
                &self.events,
                &self.dedup,
                &self.outbox,
                &relay_route(&self.transport_dedup),
                incoming_at(sender_hex, event_id, content, created_at),
            );
        }
    }

    #[tokio::test]
    async fn a_comrade_who_gave_up_on_a_message_is_announced_once() {
        let dir = TempDir::new().unwrap();
        let (ingress, mut rx) = Ingress::new(&dir).await;
        let (hex, peer) = stranger();
        accepted_peer(&ingress.store, &peer, true);
        let now = now_secs();
        let nudge = Nudge::new().to_json().unwrap();

        ingress.deliver(&hex, "n1", &nudge, now);
        match rx.try_recv().unwrap() {
            BridgeEvent::ComradeNudge { peer: p, .. } => assert_eq!(p, peer),
            other => panic!("expected ComradeNudge, got {other:?}"),
        }

        // A nudge is never a chat message, a request, or a stored DM…
        assert!(ingress.store.messages_with(&peer).unwrap().is_empty());
        // …and it writes no presence state: "last seen" and the dot belong to
        // beacons alone.
        assert!(ingress.store.get_peer_presence(&peer).unwrap().is_none());

        // Relays deliver at-least-once, and the same wrapper can land twice
        // well inside the nudge's own TTL.
        ingress.deliver(&hex, "n1", &nudge, now);
        assert!(
            rx.try_recv().is_err(),
            "a redelivered nudge must not page someone twice"
        );

        // A second, genuinely new hesitation is news again — the receiver does
        // not hold the cooldown, the sender does.
        ingress.deliver(&hex, "n2", &nudge, now + 1);
        assert!(matches!(
            rx.try_recv().unwrap(),
            BridgeEvent::ComradeNudge { .. }
        ));
    }

    #[tokio::test]
    async fn a_nudge_replayed_out_of_the_backfill_raises_nothing() {
        // The vault inbox re-scans two days on every launch. Without the
        // freshness rule, every launch would re-announce every hesitation in
        // that window — and each one would read as if it had just happened.
        let dir = TempDir::new().unwrap();
        let (ingress, mut rx) = Ingress::new(&dir).await;
        let (hex, peer) = stranger();
        accepted_peer(&ingress.store, &peer, true);
        let two_days_ago = now_secs() - 2 * 24 * 60 * 60;

        ingress.deliver(&hex, "old", &Nudge::new().to_json().unwrap(), two_days_ago);
        assert!(rx.try_recv().is_err());
    }

    #[tokio::test]
    async fn a_nudge_from_a_stranger_or_someone_we_did_not_choose_raises_nothing() {
        let dir = TempDir::new().unwrap();
        let (ingress, mut rx) = Ingress::new(&dir).await;
        let now = now_secs();
        let nudge = Nudge::new().to_json().unwrap();

        // An unaccepted stranger cannot page us before we accept them — and
        // their envelope must not leak into the requests bucket as raw JSON.
        let (stranger_hex, stranger_npub) = stranger();
        ingress.deliver(&stranger_hex, "s1", &nudge, now);
        assert!(rx.try_recv().is_err());
        assert!(ingress
            .store
            .messages_with(&stranger_npub)
            .unwrap()
            .is_empty());
        assert!(ingress
            .store
            .get_conversation_meta(&stranger_npub)
            .unwrap()
            .is_none());

        // An accepted peer we never chose as a comrade: silent, and unlike a
        // presence beacon it records nothing either — a nudge is not the thing
        // that reveals a one-sided comrade relationship.
        let (hex, peer) = stranger();
        accepted_peer(&ingress.store, &peer, false);
        ingress.deliver(&hex, "a1", &nudge, now);
        assert!(
            rx.try_recv().is_err(),
            "someone we didn't choose is not our comrade to be paged about"
        );
        assert!(ingress.store.get_peer_presence(&peer).unwrap().is_none());
    }

    #[tokio::test]
    async fn watching_a_composer_is_safe_before_unlock_and_survives_junk_keys() {
        // Frontends call these on a keystroke and on every thread close, so
        // they must be harmless in every state the app can be in — a courtesy
        // feature has no business raising an error into a text field.
        let rt = ComradeRuntime::new();
        rt.note_draft("npub1definitelynotakey");
        rt.abandon_draft("npub1definitelynotakey");
        rt.note_draft("");
        rt.abandon_draft("");
        // Nothing to send to, and nothing anywhere to send it with.
        assert_eq!(rt.handles().nudge_abandoned_drafts(now_secs()).await, 0);
    }

    #[tokio::test]
    async fn a_hesitation_towards_someone_who_is_not_a_comrade_is_never_sent() {
        // The consent gate, checked at send time: presence — and this — flow
        // only to people the user deliberately chose.
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (_hex, peer) = stranger();

        let watch = rt.draft_watch.clone();
        let at = now_secs() - NUDGE_SETTLE_SECS - 60;
        watch.writing(&peer, at);
        watch.abandoned(&peer, at + 30);
        assert_eq!(
            rt.handles().nudge_abandoned_drafts(now_secs()).await,
            0,
            "no comrade flag, no disclosure"
        );

        // And the decision is spent either way: the same abandoned draft is
        // not reconsidered on the next sweep just because they were marked in
        // between.
        rt.set_comrade(&peer, true).unwrap();
        assert_eq!(rt.handles().nudge_abandoned_drafts(now_secs()).await, 0);
    }

    #[tokio::test]
    async fn locking_the_vault_drops_a_pending_nudge() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (_hex, peer) = stranger();
        rt.set_comrade(&peer, true).unwrap();

        let watch = rt.draft_watch.clone();
        let at = now_secs() - NUDGE_SETTLE_SECS - 60;
        watch.writing(&peer, at);
        watch.abandoned(&peer, at + 30);

        rt.lock_vault().await;
        assert!(
            watch.due(now_secs()).is_empty(),
            "the goodbye beacon has already said we are gone; a nudge after it \
             would claim the opposite"
        );
    }

    #[tokio::test]
    async fn an_online_claim_ages_out_on_its_own_when_the_peer_just_vanishes() {
        // The common case: no goodbye ever arrives (battery died, signal
        // lost, app force-killed). Both the sweep and every read must stop
        // claiming the peer is online once their own deadline passes.
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (_hex, peer) = stranger();
        rt.set_comrade(&peer, true).unwrap();

        let store = rt.ui.store_arc().unwrap();
        let expired_at = now_secs() - 5;
        store
            .set_peer_presence(&comrade_storage::PeerPresence {
                peer_npub: peer.clone(),
                online: true,
                last_seen_at: expired_at - 480,
                expires_at: expired_at,
                peer_marked_us: true,
            })
            .unwrap();

        // Reads are computed against the clock, so the stale row never shows
        // as online even before anything sweeps it.
        assert!(!rt.comrades().unwrap()[0].online);
        assert!(!rt.peer_presence(&peer).unwrap().unwrap().online);

        let (tx, mut rx) = broadcast::channel(16);
        expire_stale_presence(Some(&store), &tx);
        match rx.try_recv().unwrap() {
            BridgeEvent::ComradePresence {
                peer: p,
                online,
                at,
                ..
            } => {
                assert_eq!(p, peer);
                assert!(!online);
                assert_eq!(at, expired_at);
            }
            other => panic!("expected an aged-out ComradePresence, got {other:?}"),
        }
        assert!(!store.get_peer_presence(&peer).unwrap().unwrap().online);

        // Idempotent: a second sweep has nothing left to announce.
        expire_stale_presence(Some(&store), &tx);
        assert!(rx.try_recv().is_err());
    }

    #[tokio::test]
    async fn the_chat_list_shows_presence_only_for_chosen_comrades() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::new();
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (_hex, peer) = stranger();
        let store = rt.ui.store_arc().unwrap();
        store
            .save_message(&comrade_storage::StoredMessage {
                id: "m1".into(),
                peer_npub: peer.clone(),
                content: "hi".into(),
                created_at: 1,
                outgoing: false,
                status: None,
                reply_to: None,
            })
            .unwrap();
        store
            .set_peer_presence(&comrade_storage::PeerPresence {
                peer_npub: peer.clone(),
                online: true,
                last_seen_at: now_secs(),
                expires_at: now_secs() + 300,
                peer_marked_us: true,
            })
            .unwrap();

        // They've marked us, so we have live presence for them — but we
        // haven't chosen them, so the chat list claims nothing.
        let before = &rt.conversations().unwrap()[0];
        assert!(!before.comrade);
        assert!(!before.online);

        rt.set_comrade(&peer, true).unwrap();
        let after = &rt.conversations().unwrap()[0];
        assert!(after.comrade);
        assert!(after.online);
    }

    // ── T1: call-signal freshness + dedup ────────────────────────────────────

    #[tokio::test]
    async fn dispatch_drops_a_stale_call_signal_and_dedups_a_fresh_one() {
        let dir = TempDir::new().unwrap();
        let store = Arc::new(comrade_storage::EncryptedStore::open(dir.path(), "pin").unwrap());
        let vault = test_vault().await;
        let (tx, mut rx) = broadcast::channel(16);
        let dedup = SeenSet::new(CALL_SIGNAL_DEDUP_CAPACITY);
        let outbox = Arc::new(Outbox::new());
        let transport_dedup = SeenSet::with_ttl(
            CROSS_TRANSPORT_DEDUP_CAPACITY,
            std::time::Duration::from_secs(CROSS_TRANSPORT_DEDUP_SECS),
        );
        let route = relay_route(&transport_dedup);
        let (hex, peer) = stranger();
        store
            .set_conversation_meta(&comrade_storage::ConversationMeta {
                peer_npub: peer.clone(),
                state: "accepted".into(),
                profile_shared: true,
                last_read_at: 0,
                updated_at: 1,
            })
            .unwrap();

        let envelope = CallEnvelope::new(
            "call-1",
            CallMediaKind::Audio,
            CallSignal::Offer {
                sdp: "v=0\r\n".into(),
            },
        )
        .to_json()
        .unwrap();

        // A days-old backfilled offer (e.g. relaunch re-scanning the 2-day
        // window) must never ring.
        let mut stale = incoming(&hex, "e1", &envelope);
        stale.created_at = now_secs().saturating_sub(7200);
        dispatch_incoming_dm(&vault, Some(&store), &tx, &dedup, &outbox, &route, stale);
        assert!(
            rx.try_recv().is_err(),
            "a call signal older than CALL_SIGNAL_MAX_AGE_SECS must not reach the bus"
        );

        // A fresh signal reaches the bus once; the exact same wrapper event id
        // redelivered (at-least-once relay delivery) must not fire twice.
        let mut fresh = incoming(&hex, "e2", &envelope);
        fresh.created_at = now_secs();
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            fresh.clone(),
        );
        assert!(matches!(
            rx.try_recv().unwrap(),
            BridgeEvent::IncomingCallSignal(_)
        ));
        dispatch_incoming_dm(&vault, Some(&store), &tx, &dedup, &outbox, &route, fresh);
        assert!(
            rx.try_recv().is_err(),
            "the same wrapper event id must only ever dispatch one IncomingCallSignal"
        );
    }

    // ── T2: DM dedup + durable receive watermark ─────────────────────────────

    #[tokio::test]
    async fn dispatch_dedups_a_redelivered_plain_text_dm() {
        let dir = TempDir::new().unwrap();
        let store = Arc::new(comrade_storage::EncryptedStore::open(dir.path(), "pin").unwrap());
        let vault = test_vault().await;
        let (tx, mut rx) = broadcast::channel(16);
        let dedup = SeenSet::new(CALL_SIGNAL_DEDUP_CAPACITY);
        let outbox = Arc::new(Outbox::new());
        let transport_dedup = SeenSet::with_ttl(
            CROSS_TRANSPORT_DEDUP_CAPACITY,
            std::time::Duration::from_secs(CROSS_TRANSPORT_DEDUP_SECS),
        );
        let route = relay_route(&transport_dedup);
        let (hex, peer) = stranger();
        store
            .set_conversation_meta(&comrade_storage::ConversationMeta {
                peer_npub: peer.clone(),
                state: "accepted".into(),
                profile_shared: true,
                last_read_at: 0,
                updated_at: 1,
            })
            .unwrap();

        let msg = incoming(&hex, "dup1", "hello twice");
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            msg.clone(),
        );
        assert!(matches!(
            rx.try_recv().unwrap(),
            BridgeEvent::IncomingDirectMessage(_)
        ));
        assert_eq!(store.messages_with(&peer).unwrap().len(), 1);

        // Redelivered (same event id — relay at-least-once, or a backfill
        // re-scan on the next launch) must not re-notify or duplicate the row.
        dispatch_incoming_dm(&vault, Some(&store), &tx, &dedup, &outbox, &route, msg);
        assert!(
            rx.try_recv().is_err(),
            "an already-persisted event id must not re-fire IncomingDirectMessage"
        );
        assert_eq!(store.messages_with(&peer).unwrap().len(), 1);
    }

    #[tokio::test]
    async fn dispatch_dedups_a_redelivered_media_envelope() {
        let dir = TempDir::new().unwrap();
        let store = Arc::new(comrade_storage::EncryptedStore::open(dir.path(), "pin").unwrap());
        let vault = test_vault().await;
        let (tx, mut rx) = broadcast::channel(16);
        let dedup = SeenSet::new(CALL_SIGNAL_DEDUP_CAPACITY);
        let outbox = Arc::new(Outbox::new());
        let transport_dedup = SeenSet::with_ttl(
            CROSS_TRANSPORT_DEDUP_CAPACITY,
            std::time::Duration::from_secs(CROSS_TRANSPORT_DEDUP_SECS),
        );
        let route = relay_route(&transport_dedup);
        let (hex, peer) = stranger();
        store
            .set_conversation_meta(&comrade_storage::ConversationMeta {
                peer_npub: peer.clone(),
                state: "accepted".into(),
                profile_shared: true,
                last_read_at: 0,
                updated_at: 1,
            })
            .unwrap();

        let envelope = MediaEnvelope {
            comrade_media: 1,
            event_id: "media1".into(),
            url: "https://blob.example/x".into(),
            mime: "image/png".into(),
            caption: "pic".into(),
            size: 10,
            sha256_hex: "a".repeat(64),
        };
        let json = serde_json::to_string(&envelope).unwrap();

        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            incoming(&hex, "w1", &json),
        );
        assert!(matches!(
            rx.try_recv().unwrap(),
            BridgeEvent::IncomingMedia(_)
        ));

        // Redelivered wrapper (same NIP-94 event id) must not re-notify.
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            incoming(&hex, "w2", &json),
        );
        assert!(
            rx.try_recv().is_err(),
            "an already-persisted media event id must not re-fire IncomingMedia"
        );
    }

    #[test]
    fn vault_watermark_round_trips_and_only_advances() {
        let dir = TempDir::new().unwrap();
        let store = comrade_storage::EncryptedStore::open(dir.path(), "pin").unwrap();
        assert_eq!(read_watermark(&store), None);

        advance_watermark(&store, 100);
        assert_eq!(read_watermark(&store), Some(100));

        // Out-of-order delivery must never move the watermark backwards.
        advance_watermark(&store, 50);
        assert_eq!(read_watermark(&store), Some(100));

        advance_watermark(&store, 200);
        assert_eq!(read_watermark(&store), Some(200));
    }

    #[tokio::test]
    async fn dispatch_advances_the_watermark_for_every_message_kind() {
        let dir = TempDir::new().unwrap();
        let store = Arc::new(comrade_storage::EncryptedStore::open(dir.path(), "pin").unwrap());
        let vault = test_vault().await;
        let (tx, _rx) = broadcast::channel(16);
        let dedup = SeenSet::new(CALL_SIGNAL_DEDUP_CAPACITY);
        let outbox = Arc::new(Outbox::new());
        let transport_dedup = SeenSet::with_ttl(
            CROSS_TRANSPORT_DEDUP_CAPACITY,
            std::time::Duration::from_secs(CROSS_TRANSPORT_DEDUP_SECS),
        );
        let route = relay_route(&transport_dedup);
        let (hex, _peer) = stranger();

        let mut msg = incoming(&hex, "e1", "hi");
        msg.created_at = 12_345;
        dispatch_incoming_dm(&vault, Some(&store), &tx, &dedup, &outbox, &route, msg);
        assert_eq!(read_watermark(&store), Some(12_345));
    }

    // ── T4: no runtime lock held across a network await ──────────────────────

    #[tokio::test]
    async fn toggle_workspace_is_not_blocked_by_a_slow_send_dm() {
        // AUDIT P2 regression guard: send_dm must not hold the shared runtime
        // lock across its relay round-trip, or one slow/unreachable relay
        // freezes every other bridge command behind it. Points the vault
        // engine at a non-routable address (RFC 5737 TEST-NET-1) and never
        // calls `spawn_event_loops`, so the relay never connects and
        // `send_dm`'s internal `wait_for_any_relay` blocks for its full ~5s
        // bound — long enough that this test would time out if the fix
        // regressed.
        let dir = TempDir::new().unwrap();
        let rt = Arc::new(tokio::sync::RwLock::new(ComradeRuntime::with_relays(vec![
            "wss://192.0.2.1:9".to_string(),
        ])));
        rt.write()
            .await
            .unlock_vault(dir.path(), "pin")
            .await
            .unwrap();

        let (_hex, peer) = stranger();
        let send_rt = rt.clone();
        let send_task = tokio::spawn(async move {
            // The guard-scoped snapshot bridges take — see `ComradeRuntime::handles`.
            let handles = send_rt.read().await.handles();
            handles.send_dm(&peer, "hello").await
        });

        // Give the send a moment to actually start (and start blocking on the
        // relay wait) before racing the write-locking command against it.
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;

        let toggle_rt = rt.clone();
        let toggled = tokio::time::timeout(std::time::Duration::from_secs(1), async move {
            toggle_rt
                .write()
                .await
                .toggle_workspace("OffGridTravel")
                .await
        })
        .await;

        assert!(
            toggled.is_ok(),
            "toggle_workspace must not be stuck behind a slow send_dm holding the runtime lock"
        );
        assert!(toggled.unwrap().is_ok());

        send_task.abort();
    }

    // ── Store and forward (adopted from bitchat, see docs/BITCHAT_ADOPTION.md) ──

    /// A runtime with no relays at all: every publish fails, which is exactly
    /// the "you are offline" case the outbox exists for.
    async fn offline_runtime(dir: &TempDir) -> ComradeRuntime {
        let mut rt = ComradeRuntime::with_relays(vec![]);
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        rt
    }

    #[tokio::test]
    async fn a_dm_that_no_relay_accepts_is_queued_not_lost() {
        let dir = TempDir::new().unwrap();
        let rt = offline_runtime(&dir).await;
        let (_hex, peer) = stranger();

        let dto = rt.send_dm(&peer, "i had a hard day").await.unwrap();

        assert_eq!(
            dto.status.as_deref(),
            Some("queued"),
            "the user must see it pending, not see an error and lose the text"
        );
        assert!(comrade_core::dak::outbox::is_local_message_id(&dto.id));
        assert_eq!(rt.outbox_pending(), 1);

        // It is in the conversation history too, so the thread renders it.
        let history = rt.messages_with(&peer).unwrap();
        assert_eq!(history.len(), 1);
        assert_eq!(history[0].content, "i had a hard day");
        assert_eq!(history[0].status.as_deref(), Some("queued"));
    }

    #[tokio::test]
    async fn queued_mail_survives_a_lock_and_unlock() {
        let dir = TempDir::new().unwrap();
        let (_hex, peer) = stranger();
        {
            let mut rt = offline_runtime(&dir).await;
            rt.send_dm(&peer, "are you around?").await.unwrap();
            assert_eq!(rt.outbox_pending(), 1);
            rt.lock_vault().await;
        }

        let mut rt = ComradeRuntime::with_relays(vec![]);
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        assert_eq!(
            rt.outbox_pending(),
            1,
            "queued mail must survive an app kill — that is the whole point"
        );
        assert_eq!(
            rt.messages_with(&peer).unwrap()[0].content,
            "are you around?"
        );
    }

    #[tokio::test]
    async fn a_receipt_clears_queued_mail_so_a_flush_cannot_resend_it() {
        let dir = TempDir::new().unwrap();
        let store = Arc::new(comrade_storage::EncryptedStore::open(dir.path(), "pin").unwrap());
        let vault = test_vault().await;
        let (tx, _rx) = broadcast::channel(16);
        let dedup = SeenSet::new(CALL_SIGNAL_DEDUP_CAPACITY);
        let outbox = Arc::new(Outbox::new());
        let transport_dedup = SeenSet::with_ttl(
            CROSS_TRANSPORT_DEDUP_CAPACITY,
            std::time::Duration::from_secs(CROSS_TRANSPORT_DEDUP_SECS),
        );
        let route = relay_route(&transport_dedup);
        let (hex, peer) = stranger();
        store
            .set_conversation_meta(&comrade_storage::ConversationMeta {
                peer_npub: peer.clone(),
                state: "accepted".into(),
                profile_shared: true,
                last_read_at: 0,
                updated_at: 1,
            })
            .unwrap();

        outbox.queue(QueuedMessage::new("out1", &peer, "sup", None, 1));
        store
            .save_message(&comrade_storage::StoredMessage {
                id: "out1".into(),
                peer_npub: peer.clone(),
                content: "sup".into(),
                created_at: 1,
                outgoing: true,
                status: Some("sent".into()),
                reply_to: None,
            })
            .unwrap();

        let receipt = Receipt::new(ReceiptKind::Read, vec!["out1".into()])
            .to_json()
            .unwrap();
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &route,
            incoming(&hex, "e-receipt", &receipt),
        );

        assert!(
            outbox.is_empty(),
            "the peer has the message; retrying it would send a duplicate"
        );
        assert_eq!(
            store
                .get_message("out1")
                .unwrap()
                .unwrap()
                .status
                .as_deref(),
            Some("read")
        );
    }

    #[tokio::test]
    async fn flush_marks_a_message_failed_once_attempts_run_out() {
        let dir = TempDir::new().unwrap();
        let rt = offline_runtime(&dir).await;
        let (_hex, peer) = stranger();
        let dto = rt.send_dm(&peer, "still trying").await.unwrap();

        // Burn the attempt budget directly: a real flush would take one relay
        // timeout per attempt.
        for _ in 0..comrade_core::dak::outbox::MAX_ATTEMPTS {
            rt.outbox.record_attempt(&peer, &dto.id);
        }
        assert_eq!(rt.outbox_pending(), 0, "the attempt cap drops the message");

        // The flush loop's reaping pass is what makes that visible in the UI.
        rt.handles()
            .mark_status(&peer, std::slice::from_ref(&dto.id), "failed");
        assert_eq!(
            rt.messages_with(&peer).unwrap()[0].status.as_deref(),
            Some("failed"),
            "a dropped message must stop showing as in flight"
        );
    }

    #[tokio::test]
    async fn a_queued_media_reference_retries_like_text_and_never_becomes_a_chat_bubble() {
        // The reference to an uploaded blob is ordinary DM content, so it rides
        // the same store-and-forward queue as text. What it must *not* do is
        // acquire a stored message row: the flush loop's re-key step would turn
        // the envelope's JSON into a chat bubble and the chat list's preview.
        let dir = TempDir::new().unwrap();
        let rt = offline_runtime(&dir).await;
        let (_hex, peer) = stranger();
        let envelope = serde_json::to_string(&MediaEnvelope {
            comrade_media: 1,
            event_id: "m1".into(),
            url: "https://blob.example/x".into(),
            mime: "image/jpeg".into(),
            caption: "sunset.jpg".into(),
            size: 1234,
            sha256_hex: "a".repeat(64),
        })
        .unwrap();
        rt.outbox
            .queue(QueuedMessage::new("m1", &peer, &envelope, None, now_secs()));

        // No relay will take it: attempted, kept, counted — same as text.
        assert_eq!(rt.flush_outbox().await.unwrap(), 0);
        assert_eq!(
            rt.outbox_pending(),
            1,
            "a media reference no relay accepted must be retried, not dropped"
        );
        assert!(
            rt.messages_with(&peer).unwrap().is_empty(),
            "a media envelope must never appear in the thread as text"
        );
    }

    #[tokio::test]
    async fn an_expired_media_reference_is_reported_failed_against_its_conversation() {
        // The reaping pass names a failed message by id and looks its peer up
        // from the stored row. A media reference has no stored row, so before
        // this its expiry was silent — the sender was never told the photo
        // never went out.
        let dir = TempDir::new().unwrap();
        let rt = offline_runtime(&dir).await;
        let (_hex, peer) = stranger();
        let envelope = serde_json::to_string(&MediaEnvelope {
            comrade_media: 1,
            event_id: "m1".into(),
            url: "https://blob.example/x".into(),
            mime: "image/jpeg".into(),
            caption: String::new(),
            size: 1,
            sha256_hex: String::new(),
        })
        .unwrap();
        let stale = now_secs() - comrade_core::dak::outbox::TTL_SECS - 1;
        rt.outbox
            .queue(QueuedMessage::new("m1", &peer, &envelope, None, stale));

        let mut rx = rt.subscribe_events();
        rt.flush_outbox().await.unwrap();
        assert_eq!(rt.outbox_pending(), 0, "expired mail is reaped");
        match rx.try_recv().unwrap() {
            BridgeEvent::MessageStatus {
                peer: event_peer,
                message_ids,
                status,
            } => {
                assert_eq!(event_peer, peer);
                assert_eq!(message_ids, vec!["m1".to_string()]);
                assert_eq!(status, STATUS_FAILED);
            }
            other => panic!("expected a failed MessageStatus, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn flush_with_an_empty_outbox_is_a_no_op() {
        let dir = TempDir::new().unwrap();
        let rt = offline_runtime(&dir).await;
        assert_eq!(rt.flush_outbox().await.unwrap(), 0);
    }

    #[tokio::test]
    async fn flush_requires_an_unlocked_vault() {
        let rt = ComradeRuntime::with_relays(vec![]);
        assert!(matches!(rt.flush_outbox().await, Err(UiError::VaultLocked)));
    }

    // ── Local-network delivery (the "no internet, same WiFi" path) ──────────

    #[tokio::test]
    async fn the_mesh_comes_up_on_unlock_without_choosing_a_workspace() {
        // The bug this fixes: LAN delivery used to require the user to switch to
        // the OffGridTravel workspace, and even then carried no DMs.
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::with_relays(vec![]);
        assert!(!rt.mesh_status().active, "nothing running before unlock");

        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        assert_eq!(
            rt.current_workspace().key,
            "Base",
            "still the ordinary workspace"
        );
        assert!(
            rt.mesh_status().active,
            "an unlocked vault must have a local-network transport"
        );
        assert!(
            rt.handles().mesh.is_some(),
            "and the send path must be able to reach it"
        );

        // Locking takes it back down: no beacons, no frames, nothing listening.
        rt.lock_vault().await;
        assert!(!rt.mesh_status().active);
    }

    #[tokio::test]
    async fn a_dm_with_no_relay_is_sealed_onto_the_mesh_and_stays_queued() {
        let dir = TempDir::new().unwrap();
        let mut rt = ComradeRuntime::with_relays(vec![]);
        rt.unlock_vault(dir.path(), "pin").await.unwrap();
        let (_hex, peer) = stranger();

        let dto = rt.send_dm(&peer, "i'm outside, no signal").await.unwrap();

        // Queued, because a mesh publish is not proof the *recipient* got it —
        // only their receipt clears the outbox.
        assert_eq!(dto.status.as_deref(), Some("queued"));
        assert_eq!(rt.outbox_pending(), 1);
        assert_eq!(
            rt.messages_with(&peer).unwrap()[0].content,
            "i'm outside, no signal"
        );
    }

    /// The cross-transport case: the same message arrives sealed over the mesh
    /// and later over a relay, under two different ids. It must render once.
    #[tokio::test]
    async fn one_message_delivered_by_both_routes_appears_once() {
        let dir = TempDir::new().unwrap();
        let store = Arc::new(comrade_storage::EncryptedStore::open(dir.path(), "pin").unwrap());
        let vault = test_vault().await;
        let (tx, mut rx) = broadcast::channel(16);
        let dedup = SeenSet::new(CALL_SIGNAL_DEDUP_CAPACITY);
        let outbox = Arc::new(Outbox::new());
        let transport_dedup = SeenSet::with_ttl(
            CROSS_TRANSPORT_DEDUP_CAPACITY,
            std::time::Duration::from_secs(CROSS_TRANSPORT_DEDUP_SECS),
        );
        let (hex, peer) = stranger();
        store
            .set_conversation_meta(&comrade_storage::ConversationMeta {
                peer_npub: peer.clone(),
                state: "accepted".into(),
                profile_shared: true,
                updated_at: 1,
                last_read_at: 0,
            })
            .unwrap();

        // Over the mesh first, keyed by the sender's local id.
        let mesh_route = DmRoute {
            label: TRANSPORT_MESH,
            dedup: &transport_dedup,
            mesh: None,
        };
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &mesh_route,
            incoming(&hex, "queued:abc", "are you ok?"),
        );
        assert!(matches!(
            rx.try_recv().unwrap(),
            BridgeEvent::IncomingDirectMessage(_)
        ));

        // Then the relay comes back and delivers the same text under the event
        // id a relay assigned it.
        dispatch_incoming_dm(
            &vault,
            Some(&store),
            &tx,
            &dedup,
            &outbox,
            &relay_route(&transport_dedup),
            incoming(&hex, "e-relay-id", "are you ok?"),
        );
        assert!(
            rx.try_recv().is_err(),
            "the second copy must not reach the UI"
        );
        assert_eq!(
            store.messages_with(&peer).unwrap().len(),
            1,
            "and must not be stored twice"
        );
    }

    /// The flip side, and the reason the dedup key includes the transport:
    /// someone genuinely saying the same thing twice is not a duplicate.
    #[tokio::test]
    async fn the_same_text_sent_twice_over_one_route_is_two_messages() {
        let dir = TempDir::new().unwrap();
        let store = Arc::new(comrade_storage::EncryptedStore::open(dir.path(), "pin").unwrap());
        let vault = test_vault().await;
        let (tx, mut rx) = broadcast::channel(16);
        let dedup = SeenSet::new(CALL_SIGNAL_DEDUP_CAPACITY);
        let outbox = Arc::new(Outbox::new());
        let transport_dedup = SeenSet::with_ttl(
            CROSS_TRANSPORT_DEDUP_CAPACITY,
            std::time::Duration::from_secs(CROSS_TRANSPORT_DEDUP_SECS),
        );
        let (hex, peer) = stranger();
        store
            .set_conversation_meta(&comrade_storage::ConversationMeta {
                peer_npub: peer.clone(),
                state: "accepted".into(),
                profile_shared: true,
                updated_at: 1,
                last_read_at: 0,
            })
            .unwrap();

        for id in ["e1", "e2"] {
            dispatch_incoming_dm(
                &vault,
                Some(&store),
                &tx,
                &dedup,
                &outbox,
                &relay_route(&transport_dedup),
                incoming(&hex, id, "ok"),
            );
        }

        let delivered = std::iter::from_fn(|| rx.try_recv().ok())
            .filter(|e| matches!(e, BridgeEvent::IncomingDirectMessage(_)))
            .count();
        assert_eq!(
            delivered, 2,
            "\"ok\" twice is two messages, not a duplicate"
        );
        assert_eq!(store.messages_with(&peer).unwrap().len(), 2);
    }

    // ── Panic wipe ──────────────────────────────────────────────────────────

    #[tokio::test]
    async fn panic_wipe_destroys_local_state_and_relocks() {
        let dir = TempDir::new().unwrap();
        let mut rt = offline_runtime(&dir).await;
        let (_hex, peer) = stranger();
        rt.send_dm(&peer, "something private").await.unwrap();
        rt.add_journal_entry("a hard week", Some("low")).unwrap();
        assert_eq!(rt.outbox_pending(), 1);

        rt.panic_wipe().await.unwrap();

        assert!(!rt.is_vault_unlocked(), "the wipe re-locks the runtime");
        assert_eq!(rt.outbox_pending(), 0, "queued mail is destroyed too");

        // Reopening the store with the same passphrase finds nothing.
        let store = comrade_storage::EncryptedStore::open(dir.path(), "pin").unwrap();
        assert!(
            store.load_identity().unwrap().is_none(),
            "identity survived"
        );
        assert!(
            store.journal_entries().unwrap().is_empty(),
            "journal survived"
        );
        assert!(
            store.messages_with(&peer).unwrap().is_empty(),
            "DMs survived"
        );
        assert!(
            store
                .tree_names()
                .unwrap()
                .iter()
                .all(|tree| store.keys(tree).map(|k| k.is_empty()).unwrap_or(false)),
            "some tree kept its rows"
        );
    }

    #[tokio::test]
    async fn panic_wipe_needs_an_unlocked_vault() {
        let mut rt = ComradeRuntime::with_relays(vec![]);
        assert!(matches!(rt.panic_wipe().await, Err(UiError::VaultLocked)));
    }

    #[tokio::test]
    async fn a_wiped_store_can_be_onboarded_again() {
        let dir = TempDir::new().unwrap();
        let mut rt = offline_runtime(&dir).await;
        let first = rt.current_identity().unwrap().npub;
        rt.panic_wipe().await.unwrap();

        let second = rt.unlock_vault(dir.path(), "pin").await.unwrap().npub;
        assert_ne!(
            first, second,
            "a wipe must produce a new identity, not resurrect the old one"
        );
    }

    // ── Anonymous personas ──────────────────────────────────────────────────

    #[tokio::test]
    async fn scoped_personas_are_stable_per_scope_and_never_the_identity() {
        let dir = TempDir::new().unwrap();
        let rt = offline_runtime(&dir).await;
        let store = rt.ui.store_arc().unwrap();
        let identity = rt.current_identity().unwrap().npub;

        let seed = load_or_create_device_seed(&store).unwrap();
        let again = load_or_create_device_seed(&store).unwrap();
        let persona = anon::derive_scoped(&seed, anon::SCOPE_CHITTHI, "night-thoughts").unwrap();
        let persona_again =
            anon::derive_scoped(&again, anon::SCOPE_CHITTHI, "night-thoughts").unwrap();
        let other = anon::derive_scoped(&seed, anon::SCOPE_CHITTHI, "other-room").unwrap();

        assert_eq!(
            persona.public_key(),
            persona_again.public_key(),
            "the seed must be persisted, not regenerated per call"
        );
        assert_ne!(persona.public_key(), other.public_key());
        assert_ne!(
            persona.public_key().to_bech32().unwrap(),
            identity,
            "a persona must never be the identity key"
        );
    }

    #[tokio::test]
    async fn an_empty_anonymous_chitthi_is_refused_before_any_key_work() {
        let dir = TempDir::new().unwrap();
        let rt = offline_runtime(&dir).await;
        assert!(matches!(
            rt.broadcast_anonymous_chitthi("   ", None).await,
            Err(UiError::Engine(_))
        ));
    }
}
