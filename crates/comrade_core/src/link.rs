/*!
 * link — sign a keyless client (a browser tab) in to a device that holds the vault.
 *
 * Comrade's identity *is* a secret key, and every frontend so far has held one:
 * Android unlocks a vault on the phone, desktop unlocks one on the laptop. A web
 * build cannot. `comrade_core` does not compile for `wasm32-unknown-unknown` —
 * `mio`, reached through tokio's net stack, fails outright — and even if it did,
 * `comrade_storage` opens the vault with `redb::Database::create`, which wants a
 * real file and an exclusive lock on it. So the browser cannot be a peer.
 *
 * It can be a **screen**. This module is the protocol that makes one device's
 * unlocked vault reachable from another device that has no key of its own, on
 * terms the key-holder sets and can withdraw:
 *
 * | | what it is |
 * |---|---|
 * | [`LinkOffer`] | what the browser puts in the QR: a fresh session pubkey, a nonce, relay hints, and a timestamp |
 * | [`pairing_fingerprint`] | 4 emoji shown on *both* screens, so the human confirms they scanned their own tab |
 * | [`LinkScopes`] | what the session may ask for — and what it may never ask for, at any scope |
 * | [`LinkGrant`] | the host's answer: scoped, labelled, expiring, revocable by id |
 * | [`LinkSessionStore`] | the host's registry of live sessions, with quotas, sweeps and a panic-wipe hook |
 * | [`LinkFrame`] / [`chunk`] / [`Reassembler`] | request/response framing, split to fit the courier seal |
 *
 * ## The trust story, stated plainly
 *
 * The browser generates its own keypair and **never sees the identity nsec**.
 * What it receives is a [`LinkGrant`]: a capability, not a credential. Losing a
 * laptop therefore loses a revocable session, not an identity — which is the
 * whole reason to do it this way rather than typing a passphrase into a web page.
 *
 * Frames travel inside [`crate::dak::courier::seal`], which was built for
 * couriered mail and happens to be exactly the right shape here: the wire
 * carries no sender identity, the inner MAC is keyed by the static-static shared
 * secret so [`crate::dak::courier::open`] returns an *authenticated* sender, and
 * the payload is length-padded. That authenticated sender is what lets the
 * browser learn which npub answered its offer without the grant needing a
 * signature scheme of its own.
 *
 * ## What the fingerprint defends against, and what it does not
 *
 * The offer travels optically, so nothing can sit in the middle of it. The
 * attack that remains is an **evil twin**: someone shows you *their* browser's
 * QR and you scan it, granting them a session on your vault.
 *
 * **The fingerprint does not prevent that, and an earlier version of this
 * comment claimed it did.** [`pairing_fingerprint`] needs the host pubkey, and a
 * [`LinkOffer`] deliberately carries nothing about the host — the browser learns
 * which npub adopted it *from the grant*. So the honest sequence is:
 *
 * 1. Attacker shows their tab's QR. You scan it. Your phone can compute a
 *    fingerprint (it knows both keys); **your** tab cannot, because it has
 *    received no grant.
 * 2. If you approve, the attacker's tab gets the grant and displays a
 *    fingerprint that matches your phone's exactly. There was never a
 *    disagreement to notice.
 *
 * What the fingerprint actually gives is **detection after the fact**: your own
 * tab, once paired, shows four emoji, and a row in the host's linked-devices list
 * whose emoji you cannot match against a tab in front of you is a session to
 * revoke. Prevention rests on the approval sheet naming what is being granted,
 * on [`MAX_PAIRING_ATTEMPTS`], and on the grant being short-lived and revocable —
 * not on the emoji. Closing the gap properly needs the offer to commit to a host
 * key, which would mean the browser knowing which vault it is asking for before
 * it asks; that is a protocol change, and it is recorded as one in
 * `docs/FLUTTER_WEB_MIGRATION.md` rather than papered over here.
 *
 * 4 emoji from a 32-symbol alphabet is 20 bits, which is right for a
 * user-compared code and is *not* load-bearing against a determined attacker for
 * the reason above. The alphabet is shared with the call SAS deliberately — one
 * comparison ritual for the user to learn, not two — and
 * `the_shared_alphabet_is_exactly_32_symbols` pins the property the bit count
 * depends on, which `call.rs`'s own test does not.
 *
 * ## No forward secrecy, and no I/O
 *
 * Same caveat as [`crate::dak::courier`], for the same reason: sealing is
 * static-static ECDH, so compromise of the host identity key exposes recorded
 * link traffic. Sessions expire and are revocable, which bounds the window; it
 * does not close it.
 *
 * Like [`crate::dak`], this module is policy and framing with **no I/O** — no
 * relay, no socket, no clock. Every function that needs the time takes it as an
 * argument, which is what makes expiry and rate limiting testable. Transport
 * lives above, in `comrade_ui`.
 *
 * Written against `nostr` rather than `nostr_sdk` on purpose. `nostr_sdk` pulls
 * tokio's net stack, which does not compile for `wasm32-unknown-unknown`, and the
 * point of keeping this file's imports narrow is that a browser can one day run
 * this exact code rather than a second implementation of it.
 *
 * Its non-test dependencies are precisely `nostr`, `serde`, `serde_json`, `sha2`,
 * `hex`, `base64`, and — inside the crate — [`crate::error`] and
 * [`crate::call::EMOJI_ALPHABET`]. Note what is *not* in that list:
 * [`crate::crypto`] and [`crate::pad`] are used only by the test module, through
 * [`crate::dak::courier`]. So the wasm-reachable subset is this file plus
 * `error.rs` plus that one `const` — and the alphabet has to travel with it,
 * which is why `docs/FLUTTER_WEB_MIGRATION.md` Phase W1 moves it rather than
 * leaving `call.rs` behind as an edge the leaf crate cannot satisfy.
 */

use std::collections::{BTreeMap, BTreeSet};

use nostr::key::PublicKey;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::{call::EMOJI_ALPHABET, error::LinkError};

/// Link wire version. Bumped only for a breaking change to the offer URI, the
/// grant shape, or the frame envelope — a browser and a phone on different
/// versions must refuse each other rather than guess.
pub const WIRE_VERSION: u8 = 1;

/// URI scheme and host the QR encodes.
pub const URI_SCHEME: &str = "comrade";
pub const URI_HOST: &str = "link";

/// Offer nonce length. Its only job is to make the fingerprint unpredictable
/// for a *given* pair of keys, so 128 bits is generous.
pub const NONCE_LEN: usize = 16;
/// Grant id length — the revocation handle, and all the host needs to show a
/// session in a list and take it away.
pub const GRANT_ID_LEN: usize = 16;

/// Relay hints an offer may carry. The browser cannot discover relays on its
/// own, so it names the ones it can reach; more than a handful is a fingerprint,
/// not a hint.
pub const MAX_RELAY_HINTS: usize = 4;
/// Longest a single relay URL may be.
pub const MAX_RELAY_URL_CHARS: usize = 200;

/// How long a displayed QR stays valid. Short on purpose: an offer left on a
/// screen in a café is a standing invitation, and the browser can always mint
/// another.
pub const OFFER_TTL_SECS: u64 = 120;
/// Tolerance for the two devices' clocks disagreeing about *now*.
pub const CLOCK_SKEW_SECS: u64 = 60;

/// Longest device label a grant may carry. Shown in the host's session list, so
/// it is attacker-controlled text and is length- and content-checked.
pub const MAX_LABEL_CHARS: usize = 48;

/// Longest method name a request may name.
///
/// The bound exists because the name travels back out in
/// [`LinkError::NotPermitted`], whose message is documented as text shown to
/// somebody holding a phone. Without it, the only limit is
/// [`MAX_FRAME_BYTES`] — so a refused request could put a 1 MiB string full of
/// newlines into a log line or a toast. Every real method name is well under 40
/// characters; the longest in the table is `broadcast_anonymous_chitthi` at 27.
pub const MAX_METHOD_CHARS: usize = 64;

/// Grant lifetimes. A linked tab is a convenience, not a second home: it
/// expires on its own even if the user never revokes it.
pub const DEFAULT_GRANT_TTL_SECS: u64 = 14 * 24 * 60 * 60;
pub const MAX_GRANT_TTL_SECS: u64 = 90 * 24 * 60 * 60;

/// Live sessions one vault will keep. Each is a standing hole in the vault's
/// perimeter; a cap means a user who pairs and forgets does not accumulate
/// dozens of them.
pub const MAX_ACTIVE_SESSIONS: usize = 5;

/// Pairing attempts allowed inside [`PAIRING_WINDOW_SECS`], counted whether they
/// were approved or refused. Bounds how many times an evil twin gets to roll the
/// 1-in-a-million fingerprint collision, and keeps a nagging prompt loop from
/// wearing the user down into tapping approve.
///
/// Deliberately **above** [`MAX_ACTIVE_SESSIONS`], and the gap is the point: at
/// five each, someone legitimately filling all five session slots in one sitting
/// would spend their whole prompt budget doing it, and the session cap could
/// never be the thing that spoke. Room for the five plus a few mis-scans.
pub const MAX_PAIRING_ATTEMPTS: usize = 8;
pub const PAIRING_WINDOW_SECS: u64 = 5 * 60;

/// Payload bytes per chunk. Under [`crate::dak::courier::MAX_CIPHERTEXT_BYTES`]
/// (16 KiB) with room for the seal's ephemeral key, MAC, padding and the chunk
/// header, so a chunk that fits this always fits a seal.
pub const MAX_CHUNK_BYTES: usize = 8 * 1024;
/// Chunks one logical frame may span.
pub const MAX_CHUNKS: u16 = 128;
/// Therefore the largest frame the link can carry: 1 MiB. A thread of messages
/// fits; a photo does not, and should not — media has its own pipeline and the
/// browser should fetch a blob by reference rather than through the RPC channel.
pub const MAX_FRAME_BYTES: usize = MAX_CHUNK_BYTES * MAX_CHUNKS as usize;
/// Partially-received frames held at once, per session — see [`Reassembler`],
/// which is bound to one session so this cap cannot be shared across peers.
pub const MAX_PENDING_FRAMES: usize = 8;
/// How long an incomplete frame waits for its missing chunks.
pub const REASSEMBLY_TTL_SECS: u64 = 60;

/// Emoji in a pairing fingerprint. Matches the call SAS, so the user learns one
/// "compare these four" ritual rather than two.
pub const FINGERPRINT_LEN: usize = 4;

/// Domain separator for the fingerprint hash, so the digest cannot be confused
/// with the call SAS's even though both draw from the same alphabet.
const FINGERPRINT_CONTEXT: &[u8] = b"comrade-link-fingerprint-v1";

// ── Scopes ───────────────────────────────────────────────────────────────────

/// What a linked session is allowed to reach.
///
/// Coarse on purpose. A finer lattice would be more expressive and less
/// explicable, and a permission the user cannot summarise is a permission they
/// cannot consent to — the host's approval sheet has to render this as a short
/// list of sentences.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum LinkScope {
    /// Read the chat list, threads, contacts and the profile.
    ReadThreads,
    /// Send DMs and mark threads read.
    SendMessages,
    /// Read and download encrypted attachments; send new ones.
    Media,
    /// Read the Sabha timeline and broadcast a Chitthi.
    Feed,
    /// Read call history and place calls.
    Calls,
    /// See comrades' presence and announce our own.
    Presence,
}

impl LinkScope {
    /// Every scope, for a host UI that has to render the full list.
    pub const ALL: [LinkScope; 6] = [
        LinkScope::ReadThreads,
        LinkScope::SendMessages,
        LinkScope::Media,
        LinkScope::Feed,
        LinkScope::Calls,
        LinkScope::Presence,
    ];

    /// One sentence, in the second person, for the approval sheet. The host must
    /// show these rather than the variant names: "SendMessages" is a symbol,
    /// "Send messages as you" is a decision.
    ///
    /// Each sentence has to cover everything its `scope_for` arm actually
    /// permits, which is a real constraint and not a stylistic one.
    /// `SendMessages` reads the way it does because its arm is not only
    /// `send_dm`: it also grants `set_username`, `add_contact`,
    /// `set_contact_alias` and `remove_contact`. A sheet that said only "send
    /// messages" while the tab could rename the user's published handle and
    /// delete their contacts would be a permission prompt that misinforms, which
    /// is worse than no prompt at all.
    pub fn describe(self) -> &'static str {
        match self {
            Self::ReadThreads => "Read your conversations, contacts and profile",
            Self::SendMessages => "Send messages as you, and edit your display name and contacts",
            Self::Media => "Open and send photos and files",
            Self::Feed => "Read the public feed and post as you",
            Self::Calls => "See your call history and place calls",
            Self::Presence => "See who is online, and tell them you are",
        }
    }
}

/// A set of [`LinkScope`]s.
///
/// A `BTreeSet` rather than a bitfield: the wire form stays readable, an unknown
/// variant from a newer peer fails deserialisation instead of silently landing
/// as a set bit, and the ordering makes [`LinkGrant`] comparable in tests.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct LinkScopes(BTreeSet<LinkScope>);

impl FromIterator<LinkScope> for LinkScopes {
    fn from_iter<I: IntoIterator<Item = LinkScope>>(scopes: I) -> Self {
        Self(scopes.into_iter().collect())
    }
}

impl LinkScopes {
    pub fn empty() -> Self {
        Self(BTreeSet::new())
    }

    /// What a browser tab is offered by default: everything except nothing.
    ///
    /// Every scope *is* in here, and that is not an oversight — a web client
    /// that cannot read a thread is not a web client. The privacy line in this
    /// design is not drawn by withholding scopes; it is drawn by
    /// [`Self::permits`], which refuses the local-only surfaces at **every**
    /// scope, and by the grant being revocable and short-lived.
    pub fn web_default() -> Self {
        Self::from_iter(LinkScope::ALL)
    }

    pub fn contains(&self, scope: LinkScope) -> bool {
        self.0.contains(&scope)
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    pub fn iter(&self) -> impl Iterator<Item = LinkScope> + '_ {
        self.0.iter().copied()
    }

    /// May a session holding these scopes call `method`?
    ///
    /// Fails closed twice over: an unknown method is refused, and the
    /// never-grantable list below is refused regardless of scope.
    pub fn permits(&self, method: &str) -> bool {
        if is_never_grantable(method) {
            return false;
        }
        match scope_for(method) {
            Some(required) => self.contains(required),
            // Not in the table. Either it is new and nobody taught this function
            // about it, or it does not exist. Both answers are "no": a linked
            // session gaining reach because someone added an export and forgot
            // this file is exactly the failure this returns `false` to prevent.
            None => false,
        }
    }
}

/// Methods no [`LinkGrant`] can ever reach, at any scope.
///
/// Two kinds, for two different reasons:
///
/// * **Strictly local surfaces.** The journal and Tara are documented as never
///   leaving the device — `ComradeRepository` says "strictly local" of both, and
///   Tara's thread is the most sensitive text in the product. A linked browser
///   is another device by definition, so it does not get them. Not "not yet":
///   there is no scope that turns these on, so no host UI can offer one and no
///   user can be talked into it.
/// * **Key material and destructive lifecycle.** Unlocking, locking, minting an
///   identity and the panic wipe stay with the key-holder. A revoked session
///   must not have been able to wipe the vault, and a session that could
///   `panic_wipe` would hand an attacker with a stolen laptop the one
///   irreversible button in the product.
fn is_never_grantable(method: &str) -> bool {
    if method.starts_with("journal_")
        || method.starts_with("tara_")
        || method.starts_with("add_journal")
        || method.starts_with("delete_journal")
        || method.starts_with("clear_tara")
    {
        return true;
    }
    matches!(
        method,
        "unlock_vault"
            | "lock_vault"
            | "is_vault_unlocked"
            | "is_store_unlocked"
            | "generate_identity"
            | "generate_keypair"
            | "npub_from_nsec"
            | "panic_wipe"
            | "set_turn_server"
            // Process lifecycle. `scope_for` would refuse these anyway by not
            // knowing them, but `init_runtime_with_relays` takes a **relay list**
            // — a linked session that could call it would choose which relays the
            // host talks to, which is a traffic-redirection primitive and not the
            // kind of thing to leave resting on a default. Named explicitly so it
            // stays refused even if someone later adds it to the scope table.
            | "init_app"
            | "init_runtime"
            | "init_runtime_with_relays"
            // Radio control, and it is not a label. `toggle_workspace` was in
            // `SendMessages`' arm until a review pointed out what it does:
            // `comrade_ui`'s runtime notes that entering `OffGridTravel` "really
            // starts mDNS discovery, it doesn't just flip a label". A browser tab
            // that could turn on the host phone's local-network broadcast is
            // deciding what that phone radiates to the room it is sitting in,
            // which no approval sentence about sending messages describes and
            // which a web client has no reason to need. Reading the workspace
            // (`current_workspace`, `workspaces`) stays permitted.
            | "toggle_workspace"
            | "back"
            // The link surface itself. Deny-listed before it exists (Phase W2 of
            // docs/FLUTTER_WEB_MIGRATION.md adds these exports), because the
            // order matters: a session that could mint or revoke grants could
            // promote itself and lock the key-holder out of their own vault.
            | "link_grant"
            | "link_revoke"
            | "link_sessions"
    )
}

/// The scope a method needs, or `None` if this file has never heard of it.
///
/// Exhaustive rather than prefix-matched wherever a prefix would be a guess.
/// `send_*` is not a family — `send_dm` writes a message and `send_call_signal`
/// negotiates a call — so they are listed apart.
fn scope_for(method: &str) -> Option<LinkScope> {
    Some(match method {
        // Reading the address book and the threads in it.
        "version" | "profile" | "conversations" | "messages_with" | "message_requests"
        | "list_contacts" | "search_profiles" | "current_workspace" | "workspaces"
        | "mesh_status" | "extract_payments" => LinkScope::ReadThreads,

        // Writing to them.
        "send_dm"
        | "send_dm_reply"
        | "mark_conversation_read"
        | "accept_request"
        | "block_conversation"
        | "set_username"
        | "add_contact"
        | "set_contact_alias"
        | "remove_contact"
        | "note_draft"
        | "abandon_draft" => LinkScope::SendMessages,

        // `download_and_decrypt_media`, not `download_media`: these are
        // `comrade_jni::api` export names, and an approximation of one fails
        // closed — which is the safe direction and still a bug, because the
        // browser silently cannot open an attachment. An earlier draft of this
        // table had the shorter name, from `ComradeRepository.downloadMedia`.
        // Phase W3's method table is where the two can finally be diffed
        // mechanically; see docs/FLUTTER_WEB_MIGRATION.md.
        "media_with" | "upload_and_send_media" | "download_and_decrypt_media" => LinkScope::Media,

        "fetch_sabha_timeline" | "broadcast_chitthi" | "broadcast_anonymous_chitthi" => {
            LinkScope::Feed
        }

        "call_history" | "place_call" | "send_call_signal" | "hangup_call" | "log_call"
        | "call_ice_servers" | "call_sas" | "turn_server_status" => LinkScope::Calls,

        "comrades" | "peer_presence" | "set_comrade" | "announce_presence" | "nudge_comrades" => {
            LinkScope::Presence
        }

        _ => return None,
    })
}

// ── The offer (what the QR carries) ──────────────────────────────────────────

/// What the browser publishes for the phone's camera.
///
/// Note what is *not* here: no relay credential, no vault path, nothing about
/// the host at all. An offer is a request to be adopted, and it is safe to
/// photograph — the worst a stranger with a picture of it can do is offer their
/// own vault to a browser tab that is not theirs, which fails at the
/// fingerprint.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LinkOffer {
    pub version: u8,
    /// x-only pubkey of the keypair the browser generated for this session.
    pub session: PublicKey,
    pub nonce: [u8; NONCE_LEN],
    /// Unix seconds the offer was minted, per the browser's clock.
    pub created_at: u64,
    /// Relays the browser can reach, in preference order.
    pub relays: Vec<String>,
}

impl LinkOffer {
    /// Validate and build. Rejects the offer rather than trimming it: a browser
    /// that sent six relay hints has a bug, and silently dropping two would hide
    /// it.
    pub fn new(
        session: PublicKey,
        nonce: [u8; NONCE_LEN],
        created_at: u64,
        relays: Vec<String>,
    ) -> Result<Self, LinkError> {
        if relays.len() > MAX_RELAY_HINTS {
            return Err(LinkError::TooManyRelays {
                count: relays.len(),
                max: MAX_RELAY_HINTS,
            });
        }
        for relay in &relays {
            validate_relay(relay)?;
        }
        Ok(Self {
            version: WIRE_VERSION,
            session,
            nonce,
            created_at,
            relays,
        })
    }

    /// The string the QR encodes.
    ///
    /// Percent-encoded query rather than JSON-in-base64 so that a human looking
    /// at a decoded QR can see what they are about to approve. Field order is
    /// fixed, which keeps the encoding a function of the value — a property the
    /// round-trip test relies on.
    pub fn to_uri(&self) -> String {
        let mut uri = format!(
            "{}://{}?v={}&s={}&n={}&t={}",
            URI_SCHEME,
            URI_HOST,
            self.version,
            hex::encode(self.session.to_bytes()),
            hex::encode(self.nonce),
            self.created_at,
        );
        for relay in &self.relays {
            uri.push_str("&r=");
            uri.push_str(&percent_encode(relay));
        }
        uri
    }

    /// Parse a scanned URI.
    ///
    /// Strict everywhere: unknown version, unknown query key, missing field,
    /// repeated field and malformed hex are all errors. A scanner is reading
    /// bytes off a stranger's screen, so "be liberal in what you accept" is
    /// precisely the wrong instinct.
    pub fn parse(uri: &str) -> Result<Self, LinkError> {
        let prefix = format!("{URI_SCHEME}://{URI_HOST}?");
        let query = uri
            .strip_prefix(&prefix)
            .ok_or_else(|| LinkError::Malformed("not a comrade://link URI".into()))?;

        let mut version: Option<u8> = None;
        let mut session: Option<PublicKey> = None;
        let mut nonce: Option<[u8; NONCE_LEN]> = None;
        let mut created_at: Option<u64> = None;
        let mut relays: Vec<String> = Vec::new();

        for pair in query.split('&') {
            let (key, value) = pair
                .split_once('=')
                .ok_or_else(|| LinkError::Malformed(format!("query part {pair:?} has no value")))?;
            match key {
                "v" => set_once(
                    &mut version,
                    value
                        .parse::<u8>()
                        .map_err(|_| LinkError::Malformed(format!("bad version {value:?}")))?,
                    "v",
                )?,
                "s" => set_once(&mut session, parse_xonly(value)?, "s")?,
                "n" => set_once(&mut nonce, parse_nonce(value)?, "n")?,
                "t" => set_once(
                    &mut created_at,
                    value
                        .parse::<u64>()
                        .map_err(|_| LinkError::Malformed(format!("bad timestamp {value:?}")))?,
                    "t",
                )?,
                "r" => {
                    if relays.len() == MAX_RELAY_HINTS {
                        return Err(LinkError::TooManyRelays {
                            count: relays.len() + 1,
                            max: MAX_RELAY_HINTS,
                        });
                    }
                    let relay = percent_decode(value)?;
                    validate_relay(&relay)?;
                    relays.push(relay);
                }
                other => return Err(LinkError::Malformed(format!("unknown query key {other:?}"))),
            }
        }

        let version = version.ok_or_else(|| LinkError::Malformed("missing v".into()))?;
        if version != WIRE_VERSION {
            return Err(LinkError::UnsupportedVersion(version));
        }

        Ok(Self {
            version,
            session: session.ok_or_else(|| LinkError::Malformed("missing s".into()))?,
            nonce: nonce.ok_or_else(|| LinkError::Malformed("missing n".into()))?,
            created_at: created_at.ok_or_else(|| LinkError::Malformed("missing t".into()))?,
            relays,
        })
    }

    /// Is this offer too old to honour?
    ///
    /// Skew-tolerant in both directions: the browser's clock may be a minute
    /// ahead of the phone's, and refusing a fresh offer because of that would be
    /// an unexplainable failure at the moment the user is watching.
    pub fn is_expired(&self, now: u64) -> bool {
        if self.created_at > now.saturating_add(CLOCK_SKEW_SECS) {
            return true;
        }
        now.saturating_sub(self.created_at) > OFFER_TTL_SECS + CLOCK_SKEW_SECS
    }
}

fn set_once<T>(slot: &mut Option<T>, value: T, key: &str) -> Result<(), LinkError> {
    if slot.is_some() {
        return Err(LinkError::Malformed(format!("{key} appears twice")));
    }
    *slot = Some(value);
    Ok(())
}

fn parse_xonly(value: &str) -> Result<PublicKey, LinkError> {
    let bytes =
        hex::decode(value).map_err(|e| LinkError::Malformed(format!("session key hex: {e}")))?;
    PublicKey::from_slice(&bytes).map_err(|e| LinkError::Malformed(format!("session key: {e}")))
}

fn parse_nonce(value: &str) -> Result<[u8; NONCE_LEN], LinkError> {
    let bytes = hex::decode(value).map_err(|e| LinkError::Malformed(format!("nonce hex: {e}")))?;
    bytes
        .try_into()
        .map_err(|_| LinkError::Malformed(format!("nonce must be {NONCE_LEN} bytes")))
}

/// `wss://` only, no credentials, and nothing that can lie in a list.
///
/// `ws://` is refused rather than upgraded: a browser served over HTTPS cannot
/// open a plaintext socket anyway, so accepting it here would mean storing a
/// hint that can only ever fail. Userinfo is refused because a relay hint gets
/// persisted in a grant and shown in a session list, and neither is a place for
/// a password.
///
/// And because it *is* shown in that list, a relay URL is untrusted display text
/// exactly like a device label — so it gets the same treatment
/// ([`sanitise_label`]), which an earlier version of this function did not do.
/// Rejecting only `char::is_whitespace` let `wss://a\u{0}b` and
/// `wss://a\u{202E}evil.example` through: NUL is not `White_Space`, and U+202E
/// RIGHT-TO-LEFT OVERRIDE is not either — it silently reverses the rendering of
/// everything after it, which is how a hostile hint renders as a familiar one.
/// Refused rather than stripped, because unlike a label there is no legitimate
/// URL that needs cleaning up, and a silently-rewritten URL is a hint that
/// connects somewhere other than what was checked.
fn validate_relay(url: &str) -> Result<(), LinkError> {
    if url.chars().count() > MAX_RELAY_URL_CHARS {
        return Err(LinkError::Malformed(format!(
            "relay URL longer than {MAX_RELAY_URL_CHARS} characters"
        )));
    }
    let Some(rest) = url.strip_prefix("wss://") else {
        return Err(LinkError::InsecureRelay(url.to_string()));
    };
    if rest.is_empty() {
        return Err(LinkError::Malformed("relay URL has no host".into()));
    }
    let authority = rest.split(['/', '?', '#']).next().unwrap_or(rest);
    if authority.contains('@') {
        return Err(LinkError::Malformed(
            "relay URL must not carry credentials".into(),
        ));
    }
    if url.chars().any(|c| {
        c.is_whitespace() || c.is_control() || BIDI_OVERRIDES.contains(&c) || c == '\u{FEFF}'
    }) {
        return Err(LinkError::Malformed(
            "relay URL contains a control, whitespace or text-direction character".into(),
        ));
    }
    Ok(())
}

/// Unicode bidirectional formatting characters, which reorder the glyphs after
/// them without being visible themselves.
///
/// Not covered by `char::is_control` — they are `Cf` (format), not `Cc`
/// (control) — so a check that only asks about control characters lets every one
/// of these through. They are refused wherever text this module persists gets
/// shown back to a human: a linked-devices list is a security decision surface,
/// and a row that renders differently from the bytes it holds is exactly the
/// thing that makes such a list untrustworthy.
const BIDI_OVERRIDES: &[char] = &[
    '\u{200E}', '\u{200F}', // LRM, RLM
    '\u{202A}', '\u{202B}', '\u{202C}', '\u{202D}', '\u{202E}', // embedding/override
    '\u{2066}', '\u{2067}', '\u{2068}', '\u{2069}', // isolates
];

/// Minimal percent-encoding: keep the unreserved set, escape everything else.
///
/// Hand-rolled rather than pulling a URL crate in, because the only thing that
/// ever goes through it is a relay URL that [`validate_relay`] has already
/// constrained, and because this file has to stay compilable for wasm with a
/// dependency set small enough to audit. Deliberately escapes more than it must
/// — `/` and `:` would both survive a query string unescaped — since
/// over-escaping round-trips correctly and under-escaping does not.
fn percent_encode(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.as_bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                out.push(*byte as char)
            }
            other => out.push_str(&format!("%{other:02X}")),
        }
    }
    out
}

fn percent_decode(value: &str) -> Result<String, LinkError> {
    let bytes = value.as_bytes();
    let mut out: Vec<u8> = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let hex = bytes
                .get(i + 1..i + 3)
                .ok_or_else(|| LinkError::Malformed("truncated percent escape".into()))?;
            let text = std::str::from_utf8(hex)
                .map_err(|_| LinkError::Malformed("percent escape is not ASCII".into()))?;
            let byte = u8::from_str_radix(text, 16)
                .map_err(|_| LinkError::Malformed(format!("bad percent escape %{text}")))?;
            out.push(byte);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).map_err(|_| LinkError::Malformed("relay URL is not UTF-8".into()))
}

// ── The fingerprint ──────────────────────────────────────────────────────────

/// Four emoji for the human to compare between the phone and the browser.
///
/// Deliberately **not** symmetric in the way the call SAS is: there the two
/// sides are peers and either could be "local", so the inputs are sorted. Here
/// the roles are fixed — one side offered, the other granted — so the session
/// key and the host key go in in a fixed order, and a swapped pair yields a
/// different fingerprint. That is a property, not an oversight: it means the
/// fingerprint also pins *which way round* the pairing went.
pub fn pairing_fingerprint(
    session: &PublicKey,
    host: &PublicKey,
    nonce: &[u8; NONCE_LEN],
) -> Vec<String> {
    let mut hasher = Sha256::new();
    hasher.update(FINGERPRINT_CONTEXT);
    hasher.update(session.to_bytes());
    hasher.update(host.to_bytes());
    hasher.update(nonce);
    let digest = hasher.finalize();

    (0..FINGERPRINT_LEN)
        .map(|i| EMOJI_ALPHABET[(digest[i] % EMOJI_ALPHABET.len() as u8) as usize].to_string())
        .collect()
}

// ── The grant ────────────────────────────────────────────────────────────────

/// The host's answer to an offer: a capability the browser presents on every
/// call, and the host can take away.
///
/// Carries no key material. It is not secret from the *user* — the host lists it
/// back to them — but it is bearer-ish on the wire, which is why it only ever
/// travels sealed to the session key it names.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LinkGrant {
    pub version: u8,
    /// Revocation handle. Random, not derived, so that revoking one session
    /// discloses nothing about the others.
    #[serde(with = "id_hex")]
    pub id: [u8; GRANT_ID_LEN],
    #[serde(with = "xonly_hex")]
    pub session: PublicKey,
    /// The npub that granted this. The browser learns its own user's identity
    /// from here — and cross-checks it against the authenticated sender that
    /// [`crate::dak::courier::open`] reports, which is what makes it trustworthy.
    #[serde(with = "xonly_hex")]
    pub host: PublicKey,
    pub scopes: LinkScopes,
    /// Human label for the host's session list, e.g. "Firefox on Linux".
    pub label: String,
    pub issued_at: u64,
    pub expires_at: u64,
    /// Relays both sides will use, copied from the offer so the browser does not
    /// have to re-derive them and the host has a record of what it agreed to.
    pub relays: Vec<String>,
}

impl LinkGrant {
    pub fn is_expired(&self, now: u64) -> bool {
        now >= self.expires_at
    }

    /// Does this grant permit `method`, right now?
    ///
    /// One call rather than a scope getter plus an expiry getter, because every
    /// caller needs both and a caller that checked only one would be a
    /// vulnerability rather than a bug.
    pub fn permits(&self, method: &str, now: u64) -> bool {
        !self.is_expired(now) && self.scopes.permits(method)
    }

    pub fn id_hex(&self) -> String {
        hex::encode(self.id)
    }

    /// The browser's check on a grant it just unsealed. Call this before storing
    /// one; a grant that does not pass is not a session.
    ///
    /// `sealed_by` is the **authenticated** sender from
    /// [`crate::dak::courier::open`], not anything the frame claimed. Three
    /// things have to line up, and each closes something specific:
    ///
    /// * `version` — this is the reader [`WIRE_VERSION`]'s "a browser and a phone
    ///   on different versions must refuse each other rather than guess" needs in
    ///   order to be true of grants at all. Without it the field was written at
    ///   mint time and consulted by nothing, so the promise covered only the
    ///   offer URI.
    /// * `session` — a grant addressed to a *different* browser tab, replayed
    ///   into this one. It could not be unsealed by us, but checking costs
    ///   nothing and does not depend on that reasoning holding.
    /// * `host` — the npub the grant claims must be the npub that sealed it.
    ///   This is the whole reason no signature scheme was needed.
    pub fn accept(
        &self,
        expected_session: &PublicKey,
        sealed_by: &PublicKey,
        now: u64,
    ) -> Result<(), LinkError> {
        if self.version != WIRE_VERSION {
            return Err(LinkError::UnsupportedVersion(self.version));
        }
        if &self.session != expected_session {
            return Err(LinkError::Malformed(
                "grant was issued to a different session".into(),
            ));
        }
        if &self.host != sealed_by {
            return Err(LinkError::Malformed(
                "grant names a different host than the one that sealed it".into(),
            ));
        }
        if self.is_expired(now) {
            return Err(LinkError::SessionExpired);
        }
        if self.scopes.is_empty() {
            return Err(LinkError::NoScopes);
        }
        Ok(())
    }
}

mod id_hex {
    use serde::{Deserialize, Deserializer, Serializer};

    pub fn serialize<S: Serializer>(
        id: &[u8; super::GRANT_ID_LEN],
        s: S,
    ) -> Result<S::Ok, S::Error> {
        s.serialize_str(&hex::encode(id))
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(
        d: D,
    ) -> Result<[u8; super::GRANT_ID_LEN], D::Error> {
        let text = String::deserialize(d)?;
        let bytes = hex::decode(&text).map_err(serde::de::Error::custom)?;
        bytes.try_into().map_err(|_| {
            serde::de::Error::custom(format!("grant id must be {} bytes", super::GRANT_ID_LEN))
        })
    }
}

mod xonly_hex {
    use nostr::key::PublicKey;
    use serde::{Deserialize, Deserializer, Serializer};

    pub fn serialize<S: Serializer>(key: &PublicKey, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&hex::encode(key.to_bytes()))
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<PublicKey, D::Error> {
        let text = String::deserialize(d)?;
        let bytes = hex::decode(&text).map_err(serde::de::Error::custom)?;
        PublicKey::from_slice(&bytes).map_err(serde::de::Error::custom)
    }
}

// ── The host's session registry ──────────────────────────────────────────────

/// Persisted form, so linked sessions survive a restart inside the existing
/// Argon2id + AES-256-GCM store rather than needing a second at-rest scheme —
/// same reasoning as [`crate::dak::courier::CourierSnapshot`].
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct LinkSessionSnapshot {
    pub grants: Vec<LinkGrant>,
    /// Unix seconds of recent pairing attempts, so the rate limit is not reset
    /// by force-quitting the app.
    #[serde(default)]
    pub attempts: Vec<u64>,
}

/// Proof that the user was prompted about one specific link code.
///
/// Not constructible outside this module and not `Clone`, so it can be produced
/// only by [`LinkSessionStore::note_pairing_attempt`] and spent only once, on the
/// offer it was issued for. That is the entire point: it turns "remember to rate
/// limit before prompting" from a comment into a compile error.
#[derive(Debug)]
pub struct PairingAttempt {
    session: PublicKey,
}

/// Every browser tab this vault has adopted.
///
/// Not interior-mutable, for the same reason [`crate::dak::courier::CourierStore`]
/// is not: granting, revoking and sweeping are policy decisions the caller has to
/// sequence, so `&mut self` is the honest signature.
#[derive(Debug, Default)]
pub struct LinkSessionStore {
    grants: Vec<LinkGrant>,
    attempts: Vec<u64>,
}

impl LinkSessionStore {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn from_snapshot(snapshot: LinkSessionSnapshot) -> Self {
        Self {
            grants: snapshot.grants,
            attempts: snapshot.attempts,
        }
    }

    pub fn snapshot(&self) -> LinkSessionSnapshot {
        LinkSessionSnapshot {
            grants: self.grants.clone(),
            attempts: self.attempts.clone(),
        }
    }

    /// Record that the user is *about to be asked* to approve a pairing, and
    /// return the receipt [`Self::grant`] needs.
    ///
    /// Called before the prompt is shown, and counted whether they say yes or
    /// no — the thing being limited is how often a stranger can put that prompt
    /// in front of them, not how often they agree.
    ///
    /// It returns a [`PairingAttempt`] rather than `()` because this rate limit is
    /// the *only* thing bounding how many times an evil twin gets to roll the
    /// 20-bit fingerprint collision this module's header describes, and an
    /// obligation stated in a doc comment is one a second caller can forget.
    /// `grant` consumes the receipt, so the ordering is enforced by the type
    /// rather than by whoever reads this paragraph.
    pub fn note_pairing_attempt(
        &mut self,
        offer: &LinkOffer,
        now: u64,
    ) -> Result<PairingAttempt, LinkError> {
        self.attempts
            .retain(|at| now.saturating_sub(*at) < PAIRING_WINDOW_SECS);
        if self.attempts.len() >= MAX_PAIRING_ATTEMPTS {
            return Err(LinkError::TooManyAttempts {
                max: MAX_PAIRING_ATTEMPTS,
                window_secs: PAIRING_WINDOW_SECS,
            });
        }
        self.attempts.push(now);
        Ok(PairingAttempt {
            session: offer.session,
        })
    }

    /// Mint a grant for an approved offer.
    ///
    /// Everything checkable is checked here rather than at the call site, so a
    /// second caller (the desktop host, later) cannot forget one: the user was
    /// prompted (`attempt`, which cannot be forged or reused for another offer),
    /// the offer must be fresh, the label sane, the TTL bounded, and there must be
    /// room. Expired sessions are swept first, so "full" means five *live* tabs,
    /// not five ever.
    #[allow(clippy::too_many_arguments)]
    pub fn grant(
        &mut self,
        attempt: PairingAttempt,
        offer: &LinkOffer,
        host: PublicKey,
        scopes: LinkScopes,
        label: &str,
        id: [u8; GRANT_ID_LEN],
        ttl_secs: u64,
        now: u64,
    ) -> Result<LinkGrant, LinkError> {
        // The receipt has to be for *this* offer. Otherwise one prompt could be
        // spent granting a different session — which is the same hole as not
        // prompting at all, reached by holding on to an old receipt.
        if attempt.session != offer.session {
            return Err(LinkError::Malformed(
                "pairing receipt belongs to a different link code".into(),
            ));
        }
        if offer.version != WIRE_VERSION {
            return Err(LinkError::UnsupportedVersion(offer.version));
        }
        if offer.is_expired(now) {
            return Err(LinkError::OfferExpired);
        }
        if scopes.is_empty() {
            return Err(LinkError::NoScopes);
        }
        if ttl_secs == 0 || ttl_secs > MAX_GRANT_TTL_SECS {
            return Err(LinkError::BadLifetime {
                requested: ttl_secs,
                max: MAX_GRANT_TTL_SECS,
            });
        }
        let label = sanitise_label(label)?;

        self.sweep(now);

        // Re-pairing the same session key replaces its grant rather than adding
        // a second one. Otherwise a browser that reloads mid-pairing would burn
        // a session slot per attempt, and the host's list would fill with
        // duplicates of one tab.
        self.grants.retain(|g| g.session != offer.session);

        if self.grants.len() >= MAX_ACTIVE_SESSIONS {
            return Err(LinkError::TooManySessions {
                max: MAX_ACTIVE_SESSIONS,
            });
        }

        let grant = LinkGrant {
            version: WIRE_VERSION,
            id,
            session: offer.session,
            host,
            scopes,
            label,
            issued_at: now,
            expires_at: now.saturating_add(ttl_secs),
            relays: offer.relays.clone(),
        };
        self.grants.push(grant.clone());
        Ok(grant)
    }

    /// Authorise one incoming call from a linked session.
    ///
    /// The single gate every request goes through, which is why it returns the
    /// grant rather than a bool: the caller needs the label and scopes for
    /// logging, and making it awkward to check authorisation without holding the
    /// grant makes it harder to forget to check at all.
    pub fn authorize(
        &self,
        session: &PublicKey,
        method: &str,
        now: u64,
    ) -> Result<&LinkGrant, LinkError> {
        let grant = self
            .grants
            .iter()
            .find(|g| &g.session == session)
            .ok_or(LinkError::UnknownSession)?;
        if grant.is_expired(now) {
            return Err(LinkError::SessionExpired);
        }
        if !grant.scopes.permits(method) {
            return Err(LinkError::NotPermitted {
                method: describable_method(method),
            });
        }
        Ok(grant)
    }

    /// Drop one session by id. `false` when there was nothing to drop, which the
    /// caller should treat as success — revoking twice is not an error.
    pub fn revoke(&mut self, id: &[u8; GRANT_ID_LEN]) -> bool {
        let before = self.grants.len();
        self.grants.retain(|g| &g.id != id);
        self.grants.len() != before
    }

    /// Drop every session, returning how many went. Wired to the panic wipe:
    /// a wipe that left a live browser session attached to the vault it just
    /// destroyed would be a wipe in name only.
    pub fn revoke_all(&mut self) -> usize {
        let count = self.grants.len();
        self.grants.clear();
        count
    }

    /// Forget expired sessions and stale attempt records.
    pub fn sweep(&mut self, now: u64) -> usize {
        let before = self.grants.len();
        self.grants.retain(|g| !g.is_expired(now));
        self.attempts
            .retain(|at| now.saturating_sub(*at) < PAIRING_WINDOW_SECS);
        before - self.grants.len()
    }

    /// Live sessions, newest first — the order a "linked devices" screen wants.
    pub fn active(&self, now: u64) -> Vec<&LinkGrant> {
        let mut live: Vec<&LinkGrant> = self.grants.iter().filter(|g| !g.is_expired(now)).collect();
        live.sort_by(|a, b| b.issued_at.cmp(&a.issued_at).then(a.id.cmp(&b.id)));
        live
    }

    pub fn len(&self) -> usize {
        self.grants.len()
    }

    pub fn is_empty(&self) -> bool {
        self.grants.is_empty()
    }

    /// What the panic wipe calls, matching [`crate::dak`]'s stores.
    pub fn clear(&mut self) {
        self.grants.clear();
        self.attempts.clear();
    }
}

/// Trim and bound a device label, and refuse control characters.
///
/// The label comes from the browser and lands in the host's session list, so it
/// is untrusted display text. Newlines and control bytes are stripped rather
/// than escaped because there is no legitimate label containing them, and a
/// label that can inject a line break can forge a second row in that list.
///
/// [`BIDI_OVERRIDES`] go too, and they are the reason this is not simply
/// `is_control`: they are category `Cf`, so `char::is_control` answers `false`
/// for every one of them, and a label of `"Firefox\u{202E}…"` reverses the
/// rendering of the row it lands in. Stripped rather than rejected, unlike a
/// relay URL — a label is only ever shown, so removing the character loses
/// nothing, whereas rewriting a URL would change where it points.
/// Make a rejected method name safe to put in a message a human reads.
///
/// [`LinkError::NotPermitted`] interpolates this, and the name came off the wire
/// bounded only by [`MAX_FRAME_BYTES`]. Truncating rather than refusing, because
/// the caller is already on the error path: the useful outcome is a legible
/// "not permitted to call X", not a second, different error about the shape of
/// the first one.
fn describable_method(method: &str) -> String {
    let cleaned: String = method
        .chars()
        .filter(|c| !c.is_control() && !BIDI_OVERRIDES.contains(c) && *c != '\u{FEFF}')
        .take(MAX_METHOD_CHARS)
        .collect();
    if cleaned.is_empty() {
        return "(unnamed)".to_string();
    }
    cleaned
}

fn sanitise_label(label: &str) -> Result<String, LinkError> {
    let cleaned: String = label
        .chars()
        .filter(|c| !c.is_control() && !BIDI_OVERRIDES.contains(c) && *c != '\u{FEFF}')
        .collect::<String>()
        .trim()
        .to_string();
    if cleaned.is_empty() {
        return Err(LinkError::Malformed("device label is empty".into()));
    }
    if cleaned.chars().count() > MAX_LABEL_CHARS {
        return Err(LinkError::Malformed(format!(
            "device label longer than {MAX_LABEL_CHARS} characters"
        )));
    }
    Ok(cleaned)
}

// ── Frames ───────────────────────────────────────────────────────────────────

/// What travels, once sealed, between a host and a linked session.
///
/// JSON-tagged rather than a compact binary encoding: this channel carries a
/// few dozen small calls a minute, the seal already pads away length
/// differences, and being able to read a captured frame in a debugger is worth
/// more here than the bytes.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum LinkFrame {
    /// Session → host. `params` and the response `body` are JSON, opaque to this
    /// module: the method table lives in `comrade_ui`, and teaching this file
    /// the shape of 80 DTOs would put the same knowledge in two places.
    Request {
        id: u64,
        method: String,
        params: String,
    },
    /// Host → session. `ok == false` means `body` is a user-safe message.
    Response { id: u64, ok: bool, body: String },
    /// Host → session, unsolicited: the `BridgeEvent` bus, forwarded.
    Event { seq: u64, body: String },
    /// Host → session, once, in answer to a scanned offer.
    Granted { grant: LinkGrant },
    /// Host → session: this session is over. Sent best-effort — a revoked
    /// session must stop working whether or not this arrives, which it does,
    /// because [`LinkSessionStore::authorize`] no longer finds it.
    Revoked {
        #[serde(with = "id_hex")]
        id: [u8; GRANT_ID_LEN],
        reason: String,
    },
}

impl LinkFrame {
    /// Serialise, refusing anything too large to chunk.
    pub fn encode(&self) -> Result<Vec<u8>, LinkError> {
        let bytes = serde_json::to_vec(self)
            .map_err(|e| LinkError::Malformed(format!("frame encode: {e}")))?;
        if bytes.len() > MAX_FRAME_BYTES {
            return Err(LinkError::FrameTooLarge {
                size: bytes.len(),
                max: MAX_FRAME_BYTES,
            });
        }
        Ok(bytes)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, LinkError> {
        serde_json::from_slice(bytes)
            .map_err(|e| LinkError::Malformed(format!("frame decode: {e}")))
    }
}

/// One piece of a frame, sized to fit inside a courier seal.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Chunk {
    /// Groups the pieces of one frame. Only has to be unique among a sender's
    /// in-flight frames.
    pub frame: u64,
    pub seq: u16,
    pub total: u16,
    #[serde(with = "chunk_bytes")]
    pub data: Vec<u8>,
}

mod chunk_bytes {
    use base64::{engine::general_purpose::STANDARD, Engine};
    use serde::{Deserialize, Deserializer, Serializer};

    pub fn serialize<S: Serializer>(data: &[u8], s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&STANDARD.encode(data))
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Vec<u8>, D::Error> {
        let text = String::deserialize(d)?;
        STANDARD.decode(&text).map_err(serde::de::Error::custom)
    }
}

/// Split an encoded frame into sealable chunks.
///
/// An empty payload still yields one chunk. That looks like a special case and
/// is the opposite: without it, a zero-length frame would produce no chunks, the
/// receiver would never see it, and the sender would sit waiting for a reply to
/// something it never sent.
pub fn chunk(frame_id: u64, payload: &[u8]) -> Result<Vec<Chunk>, LinkError> {
    if payload.len() > MAX_FRAME_BYTES {
        return Err(LinkError::FrameTooLarge {
            size: payload.len(),
            max: MAX_FRAME_BYTES,
        });
    }
    let pieces: Vec<&[u8]> = if payload.is_empty() {
        vec![&[]]
    } else {
        payload.chunks(MAX_CHUNK_BYTES).collect()
    };
    let total = u16::try_from(pieces.len()).map_err(|_| LinkError::FrameTooLarge {
        size: payload.len(),
        max: MAX_FRAME_BYTES,
    })?;
    Ok(pieces
        .into_iter()
        .enumerate()
        .map(|(i, data)| Chunk {
            frame: frame_id,
            seq: i as u16,
            total,
            data: data.to_vec(),
        })
        .collect())
}

/// Rebuilds one session's frames from chunks that may arrive out of order,
/// twice, or not at all.
///
/// Bounded in every direction a remote peer controls: chunk count, frame size,
/// how many frames may be part-assembled at once, and how long an incomplete one
/// waits. A reassembly buffer with none of those is a memory-exhaustion primitive
/// handed to whoever can reach the channel. Worst case per instance is
/// [`MAX_PENDING_FRAMES`] × [`MAX_FRAME_BYTES`] = 8 MiB.
///
/// **One instance per session, and the type enforces it.** A `Chunk`'s `frame`
/// id is an arbitrary `u64` chosen by whoever sent it, so a single `Reassembler`
/// shared across linked sessions would let one session reach into another's
/// half-built frames: send `frame: 1, seq: 1` and complete a frame session A
/// started, and the caller would attribute B's bytes to A's grant. Cheaper
/// still, contradict A's `seq: 0` and the frame is dropped, or fill all eight
/// slots and starve A entirely. None of that is reachable if the buffer cannot
/// be shared, so [`Reassembler::for_session`] takes the session key and
/// [`Reassembler::session`] hands it back — there is no `Default` and no way to
/// build one that is not already bound to a peer.
#[derive(Debug)]
pub struct Reassembler {
    session: PublicKey,
    pending: BTreeMap<u64, Partial>,
}

#[derive(Debug)]
struct Partial {
    total: u16,
    pieces: BTreeMap<u16, Vec<u8>>,
    bytes: usize,
    first_seen: u64,
}

impl Reassembler {
    /// Bind a fresh buffer to one linked session. See the type's doc for why
    /// this is the only constructor.
    pub fn for_session(session: PublicKey) -> Self {
        Self {
            session,
            pending: BTreeMap::new(),
        }
    }

    /// Whose chunks this buffer accepts. Lets a caller holding a map of
    /// reassemblers assert it is feeding the right one.
    pub fn session(&self) -> &PublicKey {
        &self.session
    }

    /// Feed one chunk. `Ok(Some(frame))` when it completed one.
    ///
    /// A duplicate chunk is accepted and ignored rather than rejected — retries
    /// are normal on a relay — but a chunk that *contradicts* one already held is
    /// an error, because that is not a retry, it is someone rewriting a frame
    /// mid-assembly.
    pub fn accept(&mut self, chunk: Chunk, now: u64) -> Result<Option<Vec<u8>>, LinkError> {
        self.expire(now);

        if chunk.total == 0 || chunk.total > MAX_CHUNKS {
            return Err(LinkError::Malformed(format!(
                "chunk claims {} of {} pieces",
                chunk.seq, chunk.total
            )));
        }
        if chunk.seq >= chunk.total {
            return Err(LinkError::Malformed(format!(
                "chunk {} out of range for a {}-piece frame",
                chunk.seq, chunk.total
            )));
        }
        if chunk.data.len() > MAX_CHUNK_BYTES {
            return Err(LinkError::FrameTooLarge {
                size: chunk.data.len(),
                max: MAX_CHUNK_BYTES,
            });
        }

        if !self.pending.contains_key(&chunk.frame) && self.pending.len() >= MAX_PENDING_FRAMES {
            return Err(LinkError::TooManyPartialFrames {
                max: MAX_PENDING_FRAMES,
            });
        }

        let partial = self.pending.entry(chunk.frame).or_insert_with(|| Partial {
            total: chunk.total,
            pieces: BTreeMap::new(),
            bytes: 0,
            first_seen: now,
        });

        if partial.total != chunk.total {
            self.pending.remove(&chunk.frame);
            return Err(LinkError::Malformed(
                "chunk disagrees with the frame's piece count".into(),
            ));
        }
        match partial.pieces.get(&chunk.seq) {
            Some(existing) if existing == &chunk.data => return Ok(None),
            Some(_) => {
                self.pending.remove(&chunk.frame);
                return Err(LinkError::Malformed(
                    "chunk contradicts one already received".into(),
                ));
            }
            None => {}
        }

        let grown = partial.bytes + chunk.data.len();
        if grown > MAX_FRAME_BYTES {
            self.pending.remove(&chunk.frame);
            return Err(LinkError::FrameTooLarge {
                size: grown,
                max: MAX_FRAME_BYTES,
            });
        }
        partial.bytes = grown;
        partial.pieces.insert(chunk.seq, chunk.data);

        if partial.pieces.len() as u16 != partial.total {
            return Ok(None);
        }
        let done = self
            .pending
            .remove(&chunk.frame)
            .expect("just looked it up");
        Ok(Some(done.pieces.into_values().flatten().collect()))
    }

    /// Drop frames whose missing chunks never arrived.
    pub fn expire(&mut self, now: u64) -> usize {
        let before = self.pending.len();
        self.pending
            .retain(|_, p| now.saturating_sub(p.first_seen) < REASSEMBLY_TTL_SECS);
        before - self.pending.len()
    }

    pub fn pending_frames(&self) -> usize {
        self.pending.len()
    }
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use nostr::prelude::Keys;

    fn key(seed: u8) -> PublicKey {
        // Deterministic keys so failures are reproducible. `Keys::generate`
        // would make a flake impossible to re-run.
        let mut secret = [seed; 32];
        secret[31] = seed.wrapping_add(1).max(1);
        Keys::new(nostr::prelude::SecretKey::from_slice(&secret).expect("valid scalar"))
            .public_key()
    }

    fn offer(now: u64) -> LinkOffer {
        LinkOffer::new(
            key(7),
            [0xAB; NONCE_LEN],
            now,
            vec!["wss://relay.example/one".to_string()],
        )
        .expect("valid offer")
    }

    // ── Offer URI ────────────────────────────────────────────────────────────

    #[test]
    fn offer_uri_round_trips() {
        let original = LinkOffer::new(
            key(3),
            [0x11; NONCE_LEN],
            1_700_000_000,
            vec![
                "wss://relay.damus.io".to_string(),
                "wss://relay.example.org/nostr?x=1".to_string(),
            ],
        )
        .expect("valid");
        let parsed = LinkOffer::parse(&original.to_uri()).expect("parses");
        assert_eq!(parsed, original);
    }

    #[test]
    fn offer_uri_is_a_function_of_the_value() {
        let a = offer(1_700_000_000);
        let b = offer(1_700_000_000);
        assert_eq!(a.to_uri(), b.to_uri(), "encoding must be deterministic");
    }

    #[test]
    fn offer_uri_shape_is_pinned() {
        // Pins the wire format itself. A Dart or WASM re-implementation is
        // checked against this exact string, so changing it is a protocol
        // change and has to be a deliberate one.
        let uri = LinkOffer::new(
            key(3),
            [0x11; NONCE_LEN],
            1_700_000_000,
            vec!["wss://relay.damus.io".to_string()],
        )
        .expect("valid")
        .to_uri();
        assert_eq!(
            uri,
            format!(
                "comrade://link?v=1&s={}&n=11111111111111111111111111111111&t=1700000000\
                 &r=wss%3A%2F%2Frelay.damus.io",
                hex::encode(key(3).to_bytes())
            )
        );
    }

    #[test]
    fn parsing_rejects_a_future_wire_version() {
        let uri = offer(1_000).to_uri().replace("v=1", "v=2");
        assert!(matches!(
            LinkOffer::parse(&uri),
            Err(LinkError::UnsupportedVersion(2))
        ));
    }

    #[test]
    fn parsing_rejects_unknown_keys_rather_than_ignoring_them() {
        let uri = format!("{}&extra=1", offer(1_000).to_uri());
        assert!(matches!(
            LinkOffer::parse(&uri),
            Err(LinkError::Malformed(_))
        ));
    }

    #[test]
    fn parsing_rejects_a_repeated_field() {
        let uri = format!("{}&t=99", offer(1_000).to_uri());
        assert!(matches!(
            LinkOffer::parse(&uri),
            Err(LinkError::Malformed(_))
        ));
    }

    #[test]
    fn parsing_rejects_each_missing_field_in_turn() {
        let full = offer(1_000).to_uri();
        for drop_key in ["v=", "s=", "n=", "t="] {
            let stripped: Vec<&str> = full
                .strip_prefix("comrade://link?")
                .expect("prefix")
                .split('&')
                .filter(|part| !part.starts_with(drop_key))
                .collect();
            let uri = format!("comrade://link?{}", stripped.join("&"));
            assert!(
                LinkOffer::parse(&uri).is_err(),
                "an offer missing {drop_key:?} must not parse"
            );
        }
    }

    #[test]
    fn parsing_rejects_a_non_link_uri() {
        for uri in [
            "https://example.com",
            "comrade://pay?v=1",
            "comrade://link",
            "",
        ] {
            assert!(LinkOffer::parse(uri).is_err(), "{uri:?} must not parse");
        }
    }

    #[test]
    fn plaintext_and_credentialed_relays_are_refused() {
        assert!(matches!(
            validate_relay("ws://relay.example"),
            Err(LinkError::InsecureRelay(_))
        ));
        assert!(matches!(
            validate_relay("https://relay.example"),
            Err(LinkError::InsecureRelay(_))
        ));
        assert!(matches!(
            validate_relay("wss://user:pass@relay.example"),
            Err(LinkError::Malformed(_))
        ));
        assert!(matches!(
            validate_relay("wss://"),
            Err(LinkError::Malformed(_))
        ));
        assert!(validate_relay("wss://relay.example/path?q=1").is_ok());
    }

    #[test]
    fn relays_cannot_carry_invisible_or_direction_reversing_characters() {
        // Regression. `validate_relay` rejected only `char::is_whitespace`, and
        // neither NUL nor U+202E RIGHT-TO-LEFT OVERRIDE is `White_Space` — so both
        // reached `LinkGrant.relays` and the linked-devices list, where U+202E
        // reverses the rendering of everything after it.
        for hostile in [
            "wss://a\u{0}b.example",
            "wss://a\u{202E}elpmaxe.live",
            "wss://a\u{200F}b.example",
            "wss://a\u{2066}b.example",
            "wss://a\u{FEFF}b.example",
            "wss://a\u{1}b.example",
        ] {
            assert!(
                validate_relay(hostile).is_err(),
                "{hostile:?} must not reach a session list"
            );
        }
        assert!(validate_relay("wss://relay.example/nostr").is_ok());
    }

    #[test]
    fn a_label_cannot_reverse_the_row_it_lands_in() {
        // Same class as above, opposite treatment: a label is only ever shown, so
        // the character is stripped rather than the whole label refused.
        assert_eq!(
            sanitise_label("Firefox\u{202E}xunil no").expect("ok"),
            "Firefoxxunil no"
        );
        assert!(sanitise_label("\u{202E}\u{200F}").is_err());
    }

    #[test]
    fn a_refused_method_name_cannot_flood_the_message_shown_to_the_user() {
        // `LinkError::NotPermitted` interpolates this, and the name arrives off
        // the wire bounded only by MAX_FRAME_BYTES (1 MiB).
        let flood = "x".repeat(4096);
        let described = describable_method(&flood);
        assert_eq!(described.chars().count(), MAX_METHOD_CHARS);

        assert_eq!(describable_method("send\n\ndm"), "senddm");
        assert_eq!(describable_method("\u{202E}dm"), "dm");
        assert_eq!(describable_method("\u{0}"), "(unnamed)");
        assert_eq!(describable_method(""), "(unnamed)");

        let now = 1_000;
        let mut store = LinkSessionStore::new();
        let offer = offer(now);
        grant_on(&mut store, &offer, now);
        match store.authorize(&offer.session, &flood, now) {
            Err(LinkError::NotPermitted { method }) => {
                assert_eq!(method.chars().count(), MAX_METHOD_CHARS)
            }
            other => panic!("expected NotPermitted, got {other:?}"),
        }
    }

    #[test]
    fn a_linked_browser_cannot_turn_on_the_hosts_local_radio() {
        // `toggle_workspace` was under SendMessages until a review pointed out
        // that entering OffGridTravel really starts mDNS discovery rather than
        // flipping a label. Reading the workspace stays fine.
        let all = LinkScopes::web_default();
        assert!(!all.permits("toggle_workspace"));
        assert!(!all.permits("back"));
        assert!(all.permits("current_workspace"));
        assert!(all.permits("workspaces"));
    }

    #[test]
    fn relay_hints_are_capped_at_both_ends() {
        let six: Vec<String> = (0..6).map(|i| format!("wss://r{i}.example")).collect();
        assert!(matches!(
            LinkOffer::new(key(1), [0; NONCE_LEN], 0, six.clone()),
            Err(LinkError::TooManyRelays { .. })
        ));

        // And the parser must not be a way around the constructor's cap.
        let mut uri = LinkOffer::new(key(1), [0; NONCE_LEN], 0, six[..4].to_vec())
            .expect("four is fine")
            .to_uri();
        uri.push_str("&r=wss%3A%2F%2Fr9.example");
        assert!(matches!(
            LinkOffer::parse(&uri),
            Err(LinkError::TooManyRelays { .. })
        ));
    }

    #[test]
    fn percent_coding_round_trips_every_byte() {
        for byte in 0u8..=255 {
            let text = format!("wss://a{}", byte as char);
            let coded = percent_encode(&text);
            assert!(
                !coded.contains('&') && !coded.contains('='),
                "{coded:?} would break the query string"
            );
            assert_eq!(percent_decode(&coded).expect("decodes"), text);
        }
    }

    #[test]
    fn percent_decoding_rejects_a_truncated_escape() {
        assert!(percent_decode("wss%3").is_err());
        assert!(percent_decode("wss%zz").is_err());
    }

    // ── Offer freshness ──────────────────────────────────────────────────────

    #[test]
    fn an_offer_goes_stale_but_tolerates_clock_skew() {
        let minted = 10_000;
        let offer = offer(minted);
        assert!(!offer.is_expired(minted));
        assert!(!offer.is_expired(minted + OFFER_TTL_SECS));
        // Inside the skew allowance, still good.
        assert!(!offer.is_expired(minted + OFFER_TTL_SECS + CLOCK_SKEW_SECS));
        assert!(offer.is_expired(minted + OFFER_TTL_SECS + CLOCK_SKEW_SECS + 1));
        // A phone whose clock is behind the browser's still pairs...
        assert!(!offer.is_expired(minted - CLOCK_SKEW_SECS));
        // ...but an offer from the far future is refused, not honoured for two
        // extra minutes from whenever we notice it.
        assert!(offer.is_expired(minted - CLOCK_SKEW_SECS - 1));
    }

    // ── Scopes ───────────────────────────────────────────────────────────────

    #[test]
    fn no_scope_set_can_ever_reach_the_journal_or_tara() {
        let all = LinkScopes::web_default();
        for method in [
            "journal_entries",
            "add_journal_entry",
            "delete_journal_entry",
            "tara_send",
            "tara_thread",
            "tara_opener",
            "tara_crisis_resources",
            "clear_tara_thread",
        ] {
            assert!(
                !all.permits(method),
                "{method} is strictly local and must not be reachable from a linked session"
            );
        }
    }

    #[test]
    fn no_scope_set_can_reach_key_material_or_the_panic_wipe() {
        let all = LinkScopes::web_default();
        for method in [
            "unlock_vault",
            "lock_vault",
            "generate_identity",
            "generate_keypair",
            "npub_from_nsec",
            "panic_wipe",
            "set_turn_server",
            "is_vault_unlocked",
            "is_store_unlocked",
            "link_grant",
            "link_revoke",
            "link_sessions",
        ] {
            assert!(
                !all.permits(method),
                "{method} must stay with the key-holder"
            );
        }
    }

    #[test]
    fn no_scope_set_can_choose_which_relays_the_host_talks_to() {
        // `init_runtime_with_relays` takes a relay list. A linked session that
        // could call it would redirect the host's traffic, so it is refused by
        // name rather than only by `scope_for` happening not to list it.
        let all = LinkScopes::web_default();
        for method in ["init_app", "init_runtime", "init_runtime_with_relays"] {
            assert!(!all.permits(method), "{method} is process lifecycle");
        }
    }

    #[test]
    fn media_download_is_named_after_the_real_export() {
        // Regression. The table once said "download_media", which is not an
        // export — `comrade_jni::api` spells it `download_and_decrypt_media`, and
        // the short name came from `ComradeRepository.downloadMedia`. It failed
        // closed, so nothing was exposed; a linked browser just silently could
        // not open an attachment.
        let media: LinkScopes = [LinkScope::Media].into_iter().collect();
        assert!(media.permits("download_and_decrypt_media"));
        assert!(!media.permits("download_media"));
    }

    #[test]
    fn an_unknown_method_is_refused_rather_than_allowed() {
        let all = LinkScopes::web_default();
        assert!(!all.permits("some_export_added_next_month"));
        assert!(!all.permits(""));
    }

    #[test]
    fn every_scope_is_actually_reachable() {
        // Guards against a scope that exists in the enum, is offered in the
        // approval sheet, and gates nothing — which would be a lie told to the
        // user in a permission prompt.
        for scope in LinkScope::ALL {
            let only: LinkScopes = [scope].into_iter().collect();
            assert!(
                METHOD_SAMPLES.iter().any(|m| only.permits(m)),
                "{scope:?} gates no method in the table"
            );
        }
    }

    #[test]
    fn reading_does_not_imply_writing() {
        let read_only: LinkScopes = [LinkScope::ReadThreads].into_iter().collect();
        assert!(read_only.permits("messages_with"));
        assert!(!read_only.permits("send_dm"));
        assert!(!read_only.permits("broadcast_chitthi"));
        assert!(!read_only.permits("place_call"));
        assert!(!read_only.permits("upload_and_send_media"));
    }

    #[test]
    fn every_scope_describes_itself_for_the_approval_sheet() {
        let mut seen = BTreeSet::new();
        for scope in LinkScope::ALL {
            let text = scope.describe();
            assert!(!text.is_empty());
            assert!(seen.insert(text), "{text:?} describes two scopes");
        }
    }

    const METHOD_SAMPLES: &[&str] = &[
        "conversations",
        "send_dm",
        "media_with",
        "fetch_sabha_timeline",
        "call_history",
        "comrades",
    ];

    // ── Fingerprint ──────────────────────────────────────────────────────────

    #[test]
    fn fingerprint_is_four_emoji_from_the_call_sas_alphabet() {
        let fp = pairing_fingerprint(&key(1), &key(2), &[0; NONCE_LEN]);
        assert_eq!(fp.len(), FINGERPRINT_LEN);
        for emoji in &fp {
            assert!(
                EMOJI_ALPHABET.contains(&emoji.as_str()),
                "{emoji:?} is not in the shared alphabet"
            );
        }
    }

    #[test]
    fn a_reassembler_is_bound_to_one_session() {
        // The invariant behind `MAX_PENDING_FRAMES` being "per session": a
        // `Chunk`'s frame id is attacker-chosen, so a buffer shared between two
        // linked sessions would let one complete, poison or starve the other's
        // frames. There is no `Default` and no `new()` — the only constructor
        // takes the session key, so the sharing bug is unwritable rather than
        // merely undocumented.
        let alice = key(11);
        let bob = key(12);
        let mut for_alice = Reassembler::for_session(alice);
        let mut for_bob = Reassembler::for_session(bob);
        assert_eq!(for_alice.session(), &alice);
        assert_eq!(for_bob.session(), &bob);

        // Same frame id from both peers. Alice's half-frame must be untouched by
        // Bob's chunk, and neither must complete on the other's bytes.
        let half = Chunk {
            frame: 1,
            seq: 0,
            total: 2,
            data: b"alice".to_vec(),
        };
        let rest = Chunk {
            frame: 1,
            seq: 1,
            total: 2,
            data: b"bob".to_vec(),
        };
        assert_eq!(for_alice.accept(half, 0).expect("accepts"), None);
        assert_eq!(for_bob.accept(rest, 0).expect("accepts"), None);
        assert_eq!(for_alice.pending_frames(), 1);
        assert_eq!(for_bob.pending_frames(), 1);
    }

    #[test]
    fn the_shared_alphabet_is_exactly_32_symbols() {
        // `pairing_fingerprint` reduces one digest byte per emoji with `%`, so
        // the "4 emoji is 20 bits" claim in this module's header is only true
        // while the alphabet is a power of two that divides 256. `call.rs`'s own
        // test asserts the alphabet is *at least* 32, which would let a 33rd
        // entry silently bias this fingerprint and falsify that comment.
        assert_eq!(EMOJI_ALPHABET.len(), 32);
        assert_eq!(256 % EMOJI_ALPHABET.len(), 0);
    }

    #[test]
    fn fingerprint_is_deterministic() {
        assert_eq!(
            pairing_fingerprint(&key(1), &key(2), &[9; NONCE_LEN]),
            pairing_fingerprint(&key(1), &key(2), &[9; NONCE_LEN])
        );
    }

    #[test]
    fn fingerprint_changes_with_every_input() {
        let base = pairing_fingerprint(&key(1), &key(2), &[9; NONCE_LEN]);
        assert_ne!(base, pairing_fingerprint(&key(5), &key(2), &[9; NONCE_LEN]));
        assert_ne!(base, pairing_fingerprint(&key(1), &key(6), &[9; NONCE_LEN]));
        assert_ne!(base, pairing_fingerprint(&key(1), &key(2), &[8; NONCE_LEN]));
    }

    #[test]
    fn fingerprint_is_not_symmetric() {
        // The evil-twin defence needs the fingerprint to pin the *direction* of
        // the pairing, unlike the call SAS which deliberately sorts its inputs.
        assert_ne!(
            pairing_fingerprint(&key(1), &key(2), &[9; NONCE_LEN]),
            pairing_fingerprint(&key(2), &key(1), &[9; NONCE_LEN])
        );
    }

    // ── Granting ─────────────────────────────────────────────────────────────

    /// The prompt-then-grant pair every host performs, for the tests that are
    /// about what happens after the user has said yes.
    #[allow(clippy::too_many_arguments)]
    fn issue(
        store: &mut LinkSessionStore,
        offer: &LinkOffer,
        scopes: LinkScopes,
        label: &str,
        id: [u8; GRANT_ID_LEN],
        ttl_secs: u64,
        now: u64,
    ) -> Result<LinkGrant, LinkError> {
        let attempt = store.note_pairing_attempt(offer, now)?;
        store.grant(attempt, offer, key(99), scopes, label, id, ttl_secs, now)
    }

    fn grant_on(store: &mut LinkSessionStore, offer: &LinkOffer, now: u64) -> LinkGrant {
        issue(
            store,
            offer,
            LinkScopes::web_default(),
            "Firefox on Linux",
            [1; GRANT_ID_LEN],
            DEFAULT_GRANT_TTL_SECS,
            now,
        )
        .expect("grant")
    }

    #[test]
    fn a_grant_requires_a_receipt_for_that_exact_link_code() {
        // The receipt is the enforcement point for the pairing rate limit, which
        // is the only bound on the fingerprint grind. It must not be spendable on
        // a different offer than the one the user was shown.
        let now = 1_000;
        let mut store = LinkSessionStore::new();
        let shown = offer(now);
        let other = LinkOffer::new(key(123), [1; NONCE_LEN], now, vec![]).expect("valid");

        let receipt = store
            .note_pairing_attempt(&shown, now)
            .expect("first prompt");
        assert!(matches!(
            store.grant(
                receipt,
                &other,
                key(99),
                LinkScopes::web_default(),
                "tab",
                [1; GRANT_ID_LEN],
                DEFAULT_GRANT_TTL_SECS,
                now,
            ),
            Err(LinkError::Malformed(_))
        ));
        assert!(store.is_empty());
    }

    #[test]
    fn a_granted_session_can_read_and_expires_on_its_own() {
        let now = 1_000_000;
        let mut store = LinkSessionStore::new();
        let offer = offer(now);
        let grant = grant_on(&mut store, &offer, now);

        assert_eq!(grant.expires_at, now + DEFAULT_GRANT_TTL_SECS);
        assert!(store
            .authorize(&offer.session, "conversations", now)
            .is_ok());

        let after = grant.expires_at;
        assert!(matches!(
            store.authorize(&offer.session, "conversations", after),
            Err(LinkError::SessionExpired)
        ));
        assert!(store.active(after).is_empty());
    }

    #[test]
    fn a_stale_offer_cannot_be_granted() {
        let minted = 1_000_000;
        let mut store = LinkSessionStore::new();
        let offer = offer(minted);
        let late = minted + OFFER_TTL_SECS + CLOCK_SKEW_SECS + 1;
        assert!(matches!(
            issue(
                &mut store,
                &offer,
                LinkScopes::web_default(),
                "Firefox",
                [1; GRANT_ID_LEN],
                DEFAULT_GRANT_TTL_SECS,
                late,
            ),
            Err(LinkError::OfferExpired)
        ));
    }

    #[test]
    fn granting_refuses_an_empty_scope_set_and_a_silly_lifetime() {
        let now = 1_000;
        let mut store = LinkSessionStore::new();
        let offer = offer(now);
        assert!(matches!(
            issue(
                &mut store,
                &offer,
                LinkScopes::empty(),
                "Firefox",
                [1; GRANT_ID_LEN],
                DEFAULT_GRANT_TTL_SECS,
                now
            ),
            Err(LinkError::NoScopes)
        ));
        for ttl in [0, MAX_GRANT_TTL_SECS + 1] {
            assert!(matches!(
                issue(
                    &mut store,
                    &offer,
                    LinkScopes::web_default(),
                    "Firefox",
                    [1; GRANT_ID_LEN],
                    ttl,
                    now
                ),
                Err(LinkError::BadLifetime { .. })
            ));
        }
    }

    #[test]
    fn a_label_is_trimmed_bounded_and_stripped_of_control_characters() {
        assert_eq!(sanitise_label("  Chrome  ").expect("ok"), "Chrome");
        assert_eq!(
            sanitise_label("Chrome\non Linux").expect("ok"),
            "Chromeon Linux",
            "a newline must not be able to forge a second row in the session list"
        );
        assert!(sanitise_label("   ").is_err());
        assert!(sanitise_label("\u{0}\u{1}").is_err());
        assert!(sanitise_label(&"x".repeat(MAX_LABEL_CHARS)).is_ok());
        assert!(sanitise_label(&"x".repeat(MAX_LABEL_CHARS + 1)).is_err());
    }

    #[test]
    fn re_pairing_the_same_tab_replaces_its_session_instead_of_burning_a_slot() {
        let now = 1_000;
        let mut store = LinkSessionStore::new();
        let offer = offer(now);
        grant_on(&mut store, &offer, now);

        let second = issue(
            &mut store,
            &offer,
            [LinkScope::ReadThreads].into_iter().collect(),
            "Firefox on Linux",
            [2; GRANT_ID_LEN],
            DEFAULT_GRANT_TTL_SECS,
            now + 1,
        )
        .expect("re-pair");

        assert_eq!(store.len(), 1);
        assert_eq!(store.active(now + 1), vec![&second]);
        // And the replacement's narrower scopes are what now apply.
        assert!(store.authorize(&offer.session, "send_dm", now + 1).is_err());
    }

    #[test]
    fn live_sessions_are_capped_but_expired_ones_do_not_count() {
        let now = 1_000;
        let mut store = LinkSessionStore::new();

        for i in 0..MAX_ACTIVE_SESSIONS {
            let offer = LinkOffer::new(key(i as u8 + 10), [0; NONCE_LEN], now, vec![])
                .expect("valid offer");
            issue(
                &mut store,
                &offer,
                LinkScopes::web_default(),
                "tab",
                [i as u8; GRANT_ID_LEN],
                DEFAULT_GRANT_TTL_SECS,
                now,
            )
            .expect("within cap");
        }

        let extra = LinkOffer::new(key(200), [0; NONCE_LEN], now, vec![]).expect("valid");
        assert!(matches!(
            issue(
                &mut store,
                &extra,
                LinkScopes::web_default(),
                "tab",
                [77; GRANT_ID_LEN],
                DEFAULT_GRANT_TTL_SECS,
                now
            ),
            Err(LinkError::TooManySessions { .. })
        ));

        // Once the first five have aged out, there is room again — "full" means
        // five live tabs, not five ever.
        let later = now + DEFAULT_GRANT_TTL_SECS;
        let fresh = LinkOffer::new(key(200), [0; NONCE_LEN], later, vec![]).expect("valid");
        assert!(issue(
            &mut store,
            &fresh,
            LinkScopes::web_default(),
            "tab",
            [77; GRANT_ID_LEN],
            DEFAULT_GRANT_TTL_SECS,
            later
        )
        .is_ok());
    }

    #[test]
    fn pairing_prompts_are_rate_limited_whether_or_not_the_user_agrees() {
        let now = 1_000;
        let mut store = LinkSessionStore::new();
        for _ in 0..MAX_PAIRING_ATTEMPTS {
            store
                .note_pairing_attempt(&offer(now), now)
                .expect("within the window");
        }
        assert!(matches!(
            store.note_pairing_attempt(&offer(now), now),
            Err(LinkError::TooManyAttempts { .. })
        ));
        // The window slides rather than resetting on a fixed boundary.
        assert!(store
            .note_pairing_attempt(&offer(now), now + PAIRING_WINDOW_SECS)
            .is_ok());
    }

    #[test]
    fn the_rate_limit_survives_a_restart() {
        let now = 1_000;
        let mut store = LinkSessionStore::new();
        for _ in 0..MAX_PAIRING_ATTEMPTS {
            store
                .note_pairing_attempt(&offer(now), now)
                .expect("within the window");
        }
        let mut restarted = LinkSessionStore::from_snapshot(store.snapshot());
        assert!(
            matches!(
                restarted.note_pairing_attempt(&offer(now), now),
                Err(LinkError::TooManyAttempts { .. })
            ),
            "force-quitting the app must not reset the pairing rate limit"
        );
    }

    // ── Authorising, revoking ────────────────────────────────────────────────

    #[test]
    fn an_unknown_session_is_refused() {
        let store = LinkSessionStore::new();
        assert!(matches!(
            store.authorize(&key(7), "conversations", 0),
            Err(LinkError::UnknownSession)
        ));
    }

    #[test]
    fn authorising_reports_the_method_it_refused() {
        let now = 1_000;
        let mut store = LinkSessionStore::new();
        let offer = offer(now);
        issue(
            &mut store,
            &offer,
            [LinkScope::ReadThreads].into_iter().collect(),
            "tab",
            [1; GRANT_ID_LEN],
            DEFAULT_GRANT_TTL_SECS,
            now,
        )
        .expect("grant");
        match store.authorize(&offer.session, "send_dm", now) {
            Err(LinkError::NotPermitted { method }) => assert_eq!(method, "send_dm"),
            other => panic!("expected NotPermitted, got {other:?}"),
        }
    }

    #[test]
    fn revoking_stops_a_session_immediately_and_is_idempotent() {
        let now = 1_000;
        let mut store = LinkSessionStore::new();
        let offer = offer(now);
        let grant = grant_on(&mut store, &offer, now);

        assert!(store.revoke(&grant.id));
        assert!(matches!(
            store.authorize(&offer.session, "conversations", now),
            Err(LinkError::UnknownSession)
        ));
        assert!(!store.revoke(&grant.id), "revoking twice is not an error");
    }

    #[test]
    fn a_panic_wipe_takes_every_linked_session_with_it() {
        let now = 1_000;
        let mut store = LinkSessionStore::new();
        for i in 0..3u8 {
            let offer = LinkOffer::new(key(i + 20), [0; NONCE_LEN], now, vec![]).expect("valid");
            issue(
                &mut store,
                &offer,
                LinkScopes::web_default(),
                "tab",
                [i; GRANT_ID_LEN],
                DEFAULT_GRANT_TTL_SECS,
                now,
            )
            .expect("grant");
        }
        assert_eq!(store.revoke_all(), 3);
        assert!(store.is_empty());

        store.clear();
        assert!(store.snapshot() == LinkSessionSnapshot::default());
    }

    #[test]
    fn sessions_survive_a_snapshot_round_trip() {
        let now = 1_000;
        let mut store = LinkSessionStore::new();
        let offer = offer(now);
        let grant = grant_on(&mut store, &offer, now);

        let json = serde_json::to_string(&store.snapshot()).expect("serialises");
        let restored: LinkSessionSnapshot = serde_json::from_str(&json).expect("deserialises");
        let store = LinkSessionStore::from_snapshot(restored);

        assert_eq!(store.active(now), vec![&grant]);
        assert!(store
            .authorize(&offer.session, "conversations", now)
            .is_ok());
    }

    #[test]
    fn active_sessions_come_back_newest_first() {
        let now = 1_000;
        let mut store = LinkSessionStore::new();
        for i in 0..3u8 {
            let offer =
                LinkOffer::new(key(i + 30), [0; NONCE_LEN], now + i as u64, vec![]).expect("valid");
            issue(
                &mut store,
                &offer,
                LinkScopes::web_default(),
                "tab",
                [i; GRANT_ID_LEN],
                DEFAULT_GRANT_TTL_SECS,
                now + i as u64,
            )
            .expect("grant");
        }
        let issued: Vec<u64> = store.active(now + 3).iter().map(|g| g.issued_at).collect();
        assert_eq!(issued, vec![now + 2, now + 1, now]);
    }

    #[test]
    fn sweeping_reports_what_it_dropped() {
        let now = 1_000;
        let mut store = LinkSessionStore::new();
        let offer = offer(now);
        grant_on(&mut store, &offer, now);
        assert_eq!(store.sweep(now), 0);
        assert_eq!(store.sweep(now + DEFAULT_GRANT_TTL_SECS), 1);
        assert_eq!(store.sweep(now + DEFAULT_GRANT_TTL_SECS), 0);
    }

    // ── Grants on the wire ───────────────────────────────────────────────────

    #[test]
    fn a_grant_round_trips_through_json() {
        let now = 1_000;
        let mut store = LinkSessionStore::new();
        let grant = grant_on(&mut store, &offer(now), now);
        let json = serde_json::to_string(&grant).expect("serialises");
        assert_eq!(
            serde_json::from_str::<LinkGrant>(&json).expect("deserialises"),
            grant
        );
    }

    #[test]
    fn a_grant_with_an_unknown_scope_is_refused_rather_than_partly_understood() {
        // A newer host offering a scope this build has never heard of must fail
        // the whole grant. Silently dropping the unknown entry would leave the
        // browser believing it has less access than the host recorded, and the
        // two session lists would disagree.
        let json = r#"{
            "version": 1,
            "id": "0102030405060708090a0b0c0d0e0f10",
            "session": "0000000000000000000000000000000000000000000000000000000000000001",
            "host": "0000000000000000000000000000000000000000000000000000000000000002",
            "scopes": ["ReadThreads", "TimeTravel"],
            "label": "tab",
            "issued_at": 1,
            "expires_at": 2,
            "relays": []
        }"#;
        assert!(serde_json::from_str::<LinkGrant>(json).is_err());
    }

    #[test]
    fn permits_checks_expiry_and_scope_together() {
        let now = 1_000;
        let mut store = LinkSessionStore::new();
        let grant = grant_on(&mut store, &offer(now), now);
        assert!(grant.permits("conversations", now));
        assert!(!grant.permits("conversations", grant.expires_at));
        assert!(!grant.permits("tara_send", now));
    }

    #[test]
    fn the_browser_refuses_a_grant_that_does_not_check_out() {
        // `LinkGrant::accept` is the reader that makes WIRE_VERSION's promise —
        // "a browser and a phone on different versions must refuse each other
        // rather than guess" — true of grants and not only of the offer URI. The
        // version field was previously written at mint time and read by nothing.
        let now = 1_000;
        let mut store = LinkSessionStore::new();
        let offer = offer(now);
        let host = key(99);
        let grant = grant_on(&mut store, &offer, now);

        assert!(grant.accept(&offer.session, &host, now).is_ok());

        // A grant from a future protocol version is refused, not guessed at.
        let mut newer = grant.clone();
        newer.version = WIRE_VERSION + 1;
        assert!(matches!(
            newer.accept(&offer.session, &host, now),
            Err(LinkError::UnsupportedVersion(_))
        ));

        // Addressed to another tab.
        assert!(matches!(
            grant.accept(&key(77), &host, now),
            Err(LinkError::Malformed(_))
        ));

        // Claims a host other than the key that actually sealed it — the check
        // that stands in for a signature.
        assert!(matches!(
            grant.accept(&offer.session, &key(78), now),
            Err(LinkError::Malformed(_))
        ));

        // Already expired on arrival.
        assert!(matches!(
            grant.accept(&offer.session, &host, grant.expires_at),
            Err(LinkError::SessionExpired)
        ));

        // Scopeless: a session that can do nothing is not a session.
        let mut empty = grant.clone();
        empty.scopes = LinkScopes::empty();
        assert!(matches!(
            empty.accept(&offer.session, &host, now),
            Err(LinkError::NoScopes)
        ));
    }

    // ── Frames and chunking ──────────────────────────────────────────────────

    #[test]
    fn frames_round_trip() {
        let frames = vec![
            LinkFrame::Request {
                id: 1,
                method: "conversations".into(),
                params: "{}".into(),
            },
            LinkFrame::Response {
                id: 1,
                ok: true,
                body: "[]".into(),
            },
            LinkFrame::Event {
                seq: 4,
                body: r#"{"kind":"dm"}"#.into(),
            },
            LinkFrame::Revoked {
                id: [3; GRANT_ID_LEN],
                reason: "you revoked this tab".into(),
            },
        ];
        for frame in frames {
            let bytes = frame.encode().expect("encodes");
            assert_eq!(LinkFrame::decode(&bytes).expect("decodes"), frame);
        }
    }

    #[test]
    fn chunking_round_trips_across_the_boundaries() {
        for len in [
            0,
            1,
            MAX_CHUNK_BYTES - 1,
            MAX_CHUNK_BYTES,
            MAX_CHUNK_BYTES + 1,
            MAX_CHUNK_BYTES * 3,
        ] {
            let payload: Vec<u8> = (0..len).map(|i| (i % 251) as u8).collect();
            let chunks = chunk(1, &payload).expect("chunks");
            assert!(!chunks.is_empty(), "{len} bytes produced no chunks");

            let mut reassembler = Reassembler::for_session(key(42));
            let mut done = None;
            for c in chunks {
                assert!(c.data.len() <= MAX_CHUNK_BYTES);
                if let Some(frame) = reassembler.accept(c, 0).expect("accepts") {
                    done = Some(frame);
                }
            }
            assert_eq!(done.expect("completed"), payload, "{len} bytes");
            assert_eq!(reassembler.pending_frames(), 0);
        }
    }

    #[test]
    fn chunks_may_arrive_out_of_order_or_twice() {
        let payload: Vec<u8> = (0..MAX_CHUNK_BYTES * 3).map(|i| (i % 251) as u8).collect();
        let mut chunks = chunk(7, &payload).expect("chunks");
        chunks.reverse();

        let mut reassembler = Reassembler::for_session(key(42));
        let mut done = None;
        for c in chunks {
            // Feed each one twice: a relay retry must not corrupt the frame, and
            // must not complete it a second time either.
            for _ in 0..2 {
                if let Some(frame) = reassembler.accept(c.clone(), 0).expect("accepts") {
                    assert!(done.is_none(), "the frame completed more than once");
                    done = Some(frame);
                }
            }
        }
        assert_eq!(done.expect("completed"), payload);

        // The replay of the *last* chunk arrived after the frame was already
        // delivered, so it opened a fresh partial rather than being recognised as
        // a duplicate. That is the deliberate trade: remembering completed frame
        // ids to suppress this would be unbounded state fed by a remote peer,
        // whereas a stray partial is capped at MAX_PENDING_FRAMES and expires.
        assert_eq!(reassembler.pending_frames(), 1);
        assert_eq!(reassembler.expire(REASSEMBLY_TTL_SECS), 1);
    }

    #[test]
    fn a_chunk_that_contradicts_one_already_held_drops_the_frame() {
        let mut reassembler = Reassembler::for_session(key(42));
        let first = Chunk {
            frame: 1,
            seq: 0,
            total: 2,
            data: vec![1, 2, 3],
        };
        let forged = Chunk {
            frame: 1,
            seq: 0,
            total: 2,
            data: vec![9, 9, 9],
        };
        assert_eq!(reassembler.accept(first, 0).expect("first"), None);
        assert!(reassembler.accept(forged, 0).is_err());
        assert_eq!(
            reassembler.pending_frames(),
            0,
            "a contradicted frame must be abandoned, not left half-built"
        );
    }

    #[test]
    fn malformed_chunk_headers_are_refused() {
        let mut reassembler = Reassembler::for_session(key(42));
        for bad in [
            Chunk {
                frame: 1,
                seq: 0,
                total: 0,
                data: vec![],
            },
            Chunk {
                frame: 1,
                seq: 2,
                total: 2,
                data: vec![],
            },
            Chunk {
                frame: 1,
                seq: 0,
                total: MAX_CHUNKS + 1,
                data: vec![],
            },
            Chunk {
                frame: 1,
                seq: 0,
                total: 1,
                data: vec![0; MAX_CHUNK_BYTES + 1],
            },
        ] {
            assert!(reassembler.accept(bad, 0).is_err());
        }
    }

    #[test]
    fn a_frame_cannot_grow_past_the_cap_one_chunk_at_a_time() {
        // Every chunk is individually legal and the header claims a legal piece
        // count; only the running total is over. Without the byte accounting
        // this is a memory-exhaustion primitive.
        let mut reassembler = Reassembler::for_session(key(42));
        let mut result = Ok(None);
        for seq in 0..MAX_CHUNKS {
            result = reassembler.accept(
                Chunk {
                    frame: 1,
                    seq,
                    total: MAX_CHUNKS,
                    data: vec![0; MAX_CHUNK_BYTES],
                },
                0,
            );
            if result.is_err() {
                break;
            }
        }
        // Exactly MAX_CHUNKS × MAX_CHUNK_BYTES is the cap, so this completes.
        assert!(matches!(result, Ok(Some(_))), "got {result:?}");

        // One byte more, and it is refused.
        let mut reassembler = Reassembler::for_session(key(42));
        for seq in 0..MAX_CHUNKS - 1 {
            reassembler
                .accept(
                    Chunk {
                        frame: 2,
                        seq,
                        total: MAX_CHUNKS,
                        data: vec![0; MAX_CHUNK_BYTES],
                    },
                    0,
                )
                .expect("under the cap");
        }
        assert!(matches!(
            reassembler
                .accept(
                    Chunk {
                        frame: 2,
                        seq: MAX_CHUNKS - 1,
                        total: MAX_CHUNKS,
                        data: vec![0; MAX_CHUNK_BYTES],
                    },
                    0,
                )
                .and_then(|_| reassembler.accept(
                    Chunk {
                        frame: 3,
                        seq: 0,
                        total: 1,
                        data: vec![0; MAX_CHUNK_BYTES + 1],
                    },
                    0,
                )),
            Err(LinkError::FrameTooLarge { .. })
        ));
    }

    #[test]
    fn part_built_frames_are_capped_and_expire() {
        let mut reassembler = Reassembler::for_session(key(42));
        for frame in 0..MAX_PENDING_FRAMES as u64 {
            reassembler
                .accept(
                    Chunk {
                        frame,
                        seq: 0,
                        total: 2,
                        data: vec![1],
                    },
                    0,
                )
                .expect("within cap");
        }
        assert!(matches!(
            reassembler.accept(
                Chunk {
                    frame: 999,
                    seq: 0,
                    total: 2,
                    data: vec![1],
                },
                0,
            ),
            Err(LinkError::TooManyPartialFrames { .. })
        ));

        // Once they age out there is room again, and the abandoned halves are
        // not still holding memory.
        assert_eq!(reassembler.expire(REASSEMBLY_TTL_SECS), MAX_PENDING_FRAMES);
        assert_eq!(reassembler.pending_frames(), 0);
        assert!(reassembler
            .accept(
                Chunk {
                    frame: 999,
                    seq: 0,
                    total: 2,
                    data: vec![1],
                },
                REASSEMBLY_TTL_SECS,
            )
            .is_ok());
    }

    #[test]
    fn an_oversized_frame_is_refused_before_it_is_chunked() {
        assert!(matches!(
            chunk(1, &vec![0u8; MAX_FRAME_BYTES + 1]),
            Err(LinkError::FrameTooLarge { .. })
        ));
    }

    #[test]
    fn a_chunk_fits_inside_a_courier_seal() {
        // The reason MAX_CHUNK_BYTES is 8 KiB and not 16: the seal adds an
        // ephemeral key, a MAC and padding on top of the payload, and a chunk
        // that only fits before sealing is a chunk that cannot be sent.
        use crate::dak::courier::{open, seal, MAX_CIPHERTEXT_BYTES};

        let host = Keys::generate();
        let session = Keys::generate();
        let payload = vec![0x5A; MAX_CHUNK_BYTES];
        let chunks = chunk(1, &payload).expect("chunks");
        assert_eq!(chunks.len(), 1);

        let wire = serde_json::to_vec(&chunks[0]).expect("serialises");
        let sealed = seal(&session.public_key(), &host, &wire).expect("seals");
        assert!(
            sealed.len() <= MAX_CIPHERTEXT_BYTES,
            "a full chunk sealed to {} bytes, over the {MAX_CIPHERTEXT_BYTES}-byte courier cap",
            sealed.len()
        );

        let opened = open(&session, &sealed).expect("opens");
        assert_eq!(opened.sender, host.public_key());
        assert_eq!(
            serde_json::from_slice::<Chunk>(&opened.payload).expect("decodes"),
            chunks[0]
        );
    }

    #[test]
    fn the_seal_authenticates_which_host_answered_an_offer() {
        // This is the property that lets a browser trust a grant without the
        // grant carrying a signature: `open` reports the sender's identity key,
        // and only the holder of that key could have produced the inner MAC.
        use crate::dak::courier::{open, seal};

        let host = Keys::generate();
        let impostor = Keys::generate();
        let session = Keys::generate();

        let now = 1_000;
        let mut store = LinkSessionStore::new();
        let offer =
            LinkOffer::new(session.public_key(), [4; NONCE_LEN], now, vec![]).expect("valid offer");
        let receipt = store.note_pairing_attempt(&offer, now).expect("prompted");
        let grant = store
            .grant(
                receipt,
                &offer,
                host.public_key(),
                LinkScopes::web_default(),
                "tab",
                [1; GRANT_ID_LEN],
                DEFAULT_GRANT_TTL_SECS,
                now,
            )
            .expect("grant");

        let frame = LinkFrame::Granted {
            grant: grant.clone(),
        };
        let sealed = seal(
            &session.public_key(),
            &host,
            &frame.encode().expect("encodes"),
        )
        .expect("seals");
        let opened = open(&session, &sealed).expect("opens");
        assert_eq!(opened.sender, host.public_key());

        // The browser's check: the npub the grant claims must be the npub that
        // actually sealed it. A grant naming someone else's host is refused.
        match LinkFrame::decode(&opened.payload).expect("decodes") {
            LinkFrame::Granted { grant } => {
                // The browser's real check, in one call, against the
                // *authenticated* sender rather than anything the frame claimed.
                grant
                    .accept(&session.public_key(), &opened.sender, now)
                    .expect("grant checks out");
                assert!(grant
                    .accept(&session.public_key(), &impostor.public_key(), now)
                    .is_err());
                assert_eq!(grant.host, opened.sender);
                assert_ne!(grant.host, impostor.public_key());
                assert_eq!(
                    pairing_fingerprint(&grant.session, &grant.host, &offer.nonce),
                    pairing_fingerprint(&session.public_key(), &host.public_key(), &offer.nonce),
                    "both screens must derive the same four emoji"
                );
            }
            other => panic!("expected a grant, got {other:?}"),
        }
    }
}
