/*!
 * Comrade presence — "is my person online right now?", disclosed to nobody else.
 *
 * A user can mark a contact as their **comrade**: someone whose company they
 * want to know about. Presence then flows *only* between comrades, as a small
 * [`PresenceBeacon`] envelope riding inside the same NIP-44/NIP-17
 * gift-wrapped DM channel the receipts, profile shares and call signals
 * already use ([`crate::dm`], [`crate::call`]).
 *
 * ## Why not a public status event
 * Nostr's user-status convention (NIP-38, Kind-30315) is a *public,
 * replaceable* event: every relay, and everyone who cares to look, learns
 * when you are at your phone. That is a fine-grained activity log of a
 * person's day, published for free — the exact metadata this app exists to
 * not leak. A beacon inside a gift wrap leaks nothing to a relay: the outer
 * wrapper is signed by a one-time key, its timestamp is randomized, and only
 * the chosen recipient can decrypt it.
 *
 * ## The model, stated honestly
 * - Presence is **mutual by construction**. You announce to the people you
 *   marked; you see the people who marked you. Marking someone does not
 *   subscribe you to their presence, and cannot: nothing in a serverless
 *   design can compel their device to tell you anything.
 * - Presence is **soft state with a deadline**. A beacon says "online, and
 *   assume so for `ttl_secs`". A device that dies, loses signal, or is
 *   force-killed sends no farewell, so the receiver ages the claim out on its
 *   own instead of showing a permanent green dot ([`presence_expires_at`],
 *   [`is_online_at`]).
 * - A beacon is **not a location or an activity report**. It carries exactly
 *   two bits of meaning — online/offline, and how long to believe it.
 *
 * Pure and framework-free, like [`crate::dm`] and [`crate::call`]: the wire
 * shape and the freshness arithmetic live here and are unit-tested; the
 * sending schedule, storage and notifications live in `comrade_ui`.
 */

use serde::{Deserialize, Serialize};

/// Envelope marker for a presence beacon riding inside a DM body — the same
/// detection pattern [`crate::dm::ProfileShare`] and [`crate::call::CallEnvelope`]
/// use, so [`parse_presence_beacon`] accepts only its own shape and the
/// runtime can fall through to its other handlers.
pub const PRESENCE_ENVELOPE_MARKER: u8 = 1;

/// How often an online device re-announces itself to its comrades.
///
/// The cost of a beacon is one gift-wrapped DM per comrade, so this is a
/// battery/relay-traffic knob as much as a freshness one. Three minutes keeps
/// a green dot honest to within a few minutes without turning a handful of
/// comrades into a chatty background job.
pub const PRESENCE_HEARTBEAT_SECS: u64 = 180;

/// How long a received `online` beacon is believed when the sender didn't say
/// otherwise. Deliberately more than twice [`PRESENCE_HEARTBEAT_SECS`], so a
/// single dropped or slow heartbeat doesn't flap the peer to offline and back.
pub const PRESENCE_TTL_SECS: u64 = 480;

/// How often a receiver checks whether a claim it is showing has lapsed.
///
/// Separate from (and shorter than) the heartbeat because the two answer
/// different questions: the heartbeat costs a DM per comrade, while a sweep is
/// local work over a handful of rows. Sweeping on the heartbeat alone would
/// mean a dot could stay green for a whole extra heartbeat after the claim
/// behind it expired.
pub const PRESENCE_SWEEP_SECS: u64 = 60;

/// Hard ceiling on any peer-supplied TTL. A beacon is a claim by someone
/// else's device; without a clamp a buggy (or hostile) peer could claim to be
/// online for a year and pin a green dot next to their name forever.
pub const PRESENCE_MAX_TTL_SECS: u64 = 1800;

// The flap guard the three constants above exist for, enforced at compile
// time: a single dropped heartbeat must never blink a comrade offline, and the
// default TTL must itself survive the clamp every received beacon goes
// through.
const _: () = assert!(PRESENCE_TTL_SECS > 2 * PRESENCE_HEARTBEAT_SECS);
const _: () = assert!(PRESENCE_TTL_SECS <= PRESENCE_MAX_TTL_SECS);

/// Online or not — the entire semantic payload of a beacon.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PresenceState {
    /// The sender's app is running and connected right now.
    Online,
    /// The sender is going away deliberately (vault locked, app shutting
    /// down, or they just un-marked you as a comrade). Purely a courtesy:
    /// a device that vanishes sends nothing, which is why every `Online`
    /// claim also expires on its own.
    Offline,
}

impl PresenceState {
    /// Stable wire/string form, for logs and the FFI bridges.
    pub fn as_str(self) -> &'static str {
        match self {
            PresenceState::Online => "online",
            PresenceState::Offline => "offline",
        }
    }

    pub fn is_online(self) -> bool {
        matches!(self, PresenceState::Online)
    }
}

/// The JSON envelope carried inside an E2E DM that announces presence to one
/// comrade.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PresenceBeacon {
    /// Format marker / version; must equal [`PRESENCE_ENVELOPE_MARKER`].
    pub comrade_presence: u8,
    pub state: PresenceState,
    /// How many seconds this claim stays valid, clamped by the receiver to
    /// [`PRESENCE_MAX_TTL_SECS`]. Meaningless on `Offline` (the state is
    /// terminal until the next beacon), carried anyway so the shape is
    /// uniform.
    pub ttl_secs: u64,
    /// `true` when this beacon exists only to answer someone else's — a
    /// comrade *arriving* learns immediately that we are here, instead of
    /// waiting up to [`PRESENCE_HEARTBEAT_SECS`] for our next heartbeat. A
    /// reply is never itself answered, which is what stops two online devices
    /// from ping-ponging beacons forever; the receiving side additionally only
    /// answers an arrival, not every heartbeat from a peer it already knows to
    /// be online (which would say nothing new at double the traffic).
    #[serde(default)]
    pub reply: bool,
}

impl PresenceBeacon {
    /// "I'm here" — believable for [`PRESENCE_TTL_SECS`].
    pub fn online() -> Self {
        Self {
            comrade_presence: PRESENCE_ENVELOPE_MARKER,
            state: PresenceState::Online,
            ttl_secs: PRESENCE_TTL_SECS,
            reply: false,
        }
    }

    /// "I'm going away" — sent on a deliberate exit; never relied upon.
    pub fn offline() -> Self {
        Self {
            comrade_presence: PRESENCE_ENVELOPE_MARKER,
            state: PresenceState::Offline,
            ttl_secs: 0,
            reply: false,
        }
    }

    /// The same as [`Self::online`], flagged so the receiver does not answer
    /// it in turn. See [`Self::reply`].
    pub fn online_reply() -> Self {
        Self {
            reply: true,
            ..Self::online()
        }
    }

    /// Serialise to the JSON string that becomes the DM body.
    pub fn to_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }

    /// Whether this beacon deserves an immediate beacon back: a peer's fresh
    /// "I'm here" does, an answer to ours doesn't, and neither does a
    /// goodbye.
    pub fn wants_reply(&self) -> bool {
        self.state.is_online() && !self.reply
    }
}

/// Detect and parse a presence beacon out of a decrypted DM body. Returns
/// `None` for chat text, receipts, profile shares, call or media envelopes,
/// or any JSON without the presence marker.
pub fn parse_presence_beacon(content: &str) -> Option<PresenceBeacon> {
    let env: PresenceBeacon = serde_json::from_str(content).ok()?;
    (env.comrade_presence == PRESENCE_ENVELOPE_MARKER).then_some(env)
}

/// When a beacon sent at `created_at` stops being believable.
///
/// An `Online` claim expires `ttl_secs` (clamped to [`PRESENCE_MAX_TTL_SECS`])
/// after it was *sent*, not after it was received — a beacon delayed by a
/// slow relay is already partly spent, and one replayed out of a days-old
/// backfill is entirely spent, which is exactly what keeps the relay's
/// at-least-once redelivery from resurrecting a stale green dot. An `Offline`
/// beacon expires immediately: it makes no claim to age out of.
pub fn presence_expires_at(state: PresenceState, created_at: u64, ttl_secs: u64) -> u64 {
    match state {
        PresenceState::Online => created_at.saturating_add(ttl_secs.min(PRESENCE_MAX_TTL_SECS)),
        PresenceState::Offline => created_at,
    }
}

/// Whether a claim that expires at `expires_at` is still live at `now`.
/// The single rule every read goes through, so a stored "online" row can
/// never outlive its deadline just because nothing swept it.
pub fn is_online_at(expires_at: u64, now: u64) -> bool {
    expires_at > now
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn online_and_offline_beacons_round_trip() {
        for beacon in [
            PresenceBeacon::online(),
            PresenceBeacon::offline(),
            PresenceBeacon::online_reply(),
        ] {
            let json = beacon.to_json().unwrap();
            assert_eq!(parse_presence_beacon(&json).unwrap(), beacon);
        }
        assert!(PresenceBeacon::online().state.is_online());
        assert!(!PresenceBeacon::offline().state.is_online());
        assert_eq!(PresenceState::Online.as_str(), "online");
        assert_eq!(PresenceState::Offline.as_str(), "offline");
    }

    #[test]
    fn a_reply_is_never_answered_but_a_fresh_online_is() {
        assert!(PresenceBeacon::online().wants_reply());
        assert!(!PresenceBeacon::online_reply().wants_reply());
        // A goodbye needs no answer either — answering it would announce our
        // presence to someone whose app is on its way out.
        assert!(!PresenceBeacon::offline().wants_reply());
    }

    #[test]
    fn a_beacon_from_an_older_build_without_the_reply_field_still_parses() {
        // `reply` was added after the first shape; a peer running the earlier
        // build sends JSON without it and must keep working (defaulting to
        // "not a reply", i.e. the conservative "answer it" behaviour).
        let beacon =
            parse_presence_beacon(r#"{"comrade_presence":1,"state":"online","ttl_secs":480}"#)
                .expect("parses without the optional field");
        assert!(!beacon.reply);
        assert!(beacon.wants_reply());
    }

    #[test]
    fn presence_is_not_confused_with_any_other_envelope_or_chat_text() {
        // Chat text, and every other control envelope that rides the same
        // channel, must fall through this parser untouched.
        for other in [
            "just saying hi",
            "{}",
            r#"{"comrade_profile":1,"username":"neo"}"#,
            r#"{"comrade_receipt":1,"status":"read","message_ids":["m1"]}"#,
            r#"{"comrade_call":1,"call_id":"c","media":"audio","signal":{"kind":"ringing"}}"#,
            r#"{"comrade_media":1,"event_id":"e","url":"u","mime":"m","caption":"","size":1}"#,
        ] {
            assert!(
                parse_presence_beacon(other).is_none(),
                "must not parse as presence: {other}"
            );
        }
        // …and the reverse: a presence beacon is not a profile share or a
        // receipt (checked from the other side in `dm`'s own tests).
        let presence = PresenceBeacon::online().to_json().unwrap();
        assert!(crate::dm::parse_profile_share(&presence).is_none());
        assert!(crate::dm::parse_receipt(&presence).is_none());
        assert!(crate::call::parse_call_envelope(&presence).is_none());
    }

    #[test]
    fn wrong_marker_value_is_rejected() {
        assert!(
            parse_presence_beacon(r#"{"comrade_presence":2,"state":"online","ttl_secs":60}"#)
                .is_none()
        );
        assert!(
            parse_presence_beacon(r#"{"comrade_presence":0,"state":"online","ttl_secs":60}"#)
                .is_none()
        );
        // An unknown state string is not silently treated as online.
        assert!(
            parse_presence_beacon(r#"{"comrade_presence":1,"state":"away","ttl_secs":60}"#)
                .is_none()
        );
    }

    #[test]
    fn an_online_claim_expires_from_when_it_was_sent() {
        let sent_at = 1_000_000;
        let expires = presence_expires_at(PresenceState::Online, sent_at, 300);
        assert_eq!(expires, sent_at + 300);
        assert!(is_online_at(expires, sent_at + 299));
        assert!(
            !is_online_at(expires, sent_at + 300),
            "deadline is exclusive"
        );
        assert!(!is_online_at(expires, sent_at + 301));
    }

    #[test]
    fn a_peer_cannot_claim_to_be_online_indefinitely() {
        let sent_at = 1_000_000;
        let expires = presence_expires_at(PresenceState::Online, sent_at, u64::MAX);
        assert_eq!(expires, sent_at + PRESENCE_MAX_TTL_SECS);
        assert!(!is_online_at(expires, sent_at + PRESENCE_MAX_TTL_SECS + 1));
    }

    #[test]
    fn a_replayed_beacon_from_the_backfill_window_is_already_expired() {
        // Relays redeliver at-least-once and the vault inbox backfills up to
        // two days on every launch: a beacon that old must never light up a
        // green dot.
        let two_days = 2 * 24 * 60 * 60;
        let now = 5_000_000;
        let expires = presence_expires_at(PresenceState::Online, now - two_days, PRESENCE_TTL_SECS);
        assert!(!is_online_at(expires, now));
    }

    #[test]
    fn an_offline_beacon_makes_no_claim_to_age_out_of() {
        let sent_at = 1_000_000;
        let expires = presence_expires_at(PresenceState::Offline, sent_at, 9_999);
        assert_eq!(expires, sent_at);
        assert!(!is_online_at(expires, sent_at));
    }

    #[test]
    fn a_comrade_stays_online_across_a_missed_heartbeat() {
        // The behaviour the TTL/heartbeat ratio buys (the ratio itself is
        // pinned at compile time next to the constants): a device that
        // announces, then misses one heartbeat entirely, is still shown as
        // online when its next one is due.
        let sent_at = 1_000_000;
        let expires = presence_expires_at(PresenceState::Online, sent_at, PRESENCE_TTL_SECS);
        let after_a_missed_beat = sent_at + 2 * PRESENCE_HEARTBEAT_SECS;
        assert!(
            is_online_at(expires, after_a_missed_beat),
            "one dropped heartbeat must not blink a comrade offline"
        );
    }
}
