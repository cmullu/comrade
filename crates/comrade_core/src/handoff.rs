/*!
 * Handing a large attachment straight to the other device.
 *
 * The media pipeline caps an attachment at [`MAX_MEDIA_BYTES`] — 10 MB — and the
 * cap is not arbitrary. It encrypts and buffers the whole file in memory, and it
 * uploads to a third-party Blossom host whose own per-upload limit we do not
 * control and cannot raise (`blossom.band` publishes 20 MiB free / 100 MiB paid;
 * `nostr.download` advertises no limit at all, which is worse — it means the
 * ceiling is whatever that operator configured today).
 *
 * So a bigger file takes a different road. [`super::share`] already moves bulk
 * between two devices for `together`: receiver-driven, chunked, resumable,
 * integrity-checked, over a WebRTC data channel with nobody in the middle. This
 * module is that machinery pointed at an attachment instead of a music track.
 *
 * ## What is genuinely new here, and what is reused
 * Reused wholesale, with no changes: [`ShareOffer`] (the shape of the bytes),
 * [`ShareTracker`] (what has arrived), the chunk framing, and every line of
 * [`transport`](super::share::transport)'s relay policy. Nothing about moving
 * bytes is duplicated.
 *
 * New, and only this:
 *
 *  * **An envelope of its own.** `together`'s share signals ride inside
 *    [`TogetherEnvelope`](super::together::TogetherEnvelope), which scopes them to
 *    a session, and an attachment has no session. A handoff is scoped by a
 *    transfer id instead.
 *  * **A shorter sequence.** `together` needs an
 *    [`Ask`](super::share::ShareSignal::Ask) because the side *lacking* the file
 *    discovers that first. Here the sender initiates — they picked the file — so
 *    it is offer → accept → transport, with no guessing about who wants what.
 *  * **What the file is.** [`ShareOffer`] describes bytes and a duration, which is
 *    everything a track needs. An attachment also has a name, a MIME type and a
 *    caption, and the recipient has to see all three *before* agreeing to a
 *    400 MB transfer.
 *
 * ## The honest cost, stated before anyone builds on this
 * Two things the hosted path gives you that this one cannot:
 *
 *  * **The recipient must be online, now.** A Blossom upload sits on a host until
 *    they next open the app; a data channel needs both devices present. There is
 *    no store-and-forward here and there should not be — the whole point is that
 *    nothing else holds a copy.
 *  * **A direct path must exist.** [`RelayPolicy::DirectOnly`] is the default and
 *    `AUDIT.md` §8.1 measures the consequence: 30-40% of remote pairs (CGNAT is
 *    common on Indian mobile carriers) get no direct path, and for them a large
 *    handoff simply does not happen. The fallback is to send something under the
 *    hosted cap, and the UI must say so rather than spinning.
 *
 * That is why [`route_for_bytes`] exists as its own decision: the question is not
 * "how large may an attachment be" but "which road does *this* file take", and
 * the two roads have different failure modes that a person has to be told about.
 */

use rand::RngCore;
use serde::{Deserialize, Serialize};

use crate::media::MAX_MEDIA_BYTES;
use crate::share::{transport::RefusalReason, ShareOffer, TransferSignal};

/// Format marker / version for [`HandoffEnvelope`], so a body that happens to
/// parse as one is still not treated as one.
pub const HANDOFF_ENVELOPE_MARKER: u8 = 1;

/// Which road an attachment of a given size takes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "snake_case")]
pub enum AttachmentRoute {
    /// Encrypt, upload to a Blossom host, publish a NIP-94 reference. Reaches
    /// someone who is not online, and works even when the only path between the
    /// two devices is a relay — because there is no path between the two
    /// devices. Capped at [`MAX_MEDIA_BYTES`].
    Hosted,
    /// Hand the bytes to the other device over a WebRTC data channel. No size
    /// cap and no third party, at the cost of needing them present and needing a
    /// direct path.
    PeerToPeer,
}

/// The road [`total_bytes`](ShareOffer::total_bytes) has to take.
///
/// A single threshold, and it is the *hosted* ceiling rather than a number of its
/// own: below it the hosted path is strictly better (offline delivery, no ICE, no
/// relay question), and above it the hosted path is not available at all. There
/// is no band where both work and one has to be preferred, so there is nothing
/// here to tune.
pub fn route_for_bytes(total_bytes: u64) -> AttachmentRoute {
    if total_bytes <= MAX_MEDIA_BYTES as u64 {
        AttachmentRoute::Hosted
    } else {
        AttachmentRoute::PeerToPeer
    }
}

/// What the file is, on top of the shape of its bytes.
///
/// The shape is a plain [`ShareOffer`] rather than fields copied out of it, so
/// the chunk arithmetic, the tracker and the framing all take this apart the
/// same way they take a `together` handover apart — there is one implementation
/// of "which bytes are chunk 41", not two.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct AttachmentHandoff {
    /// Total size, chunk size, whole-file hash, duration.
    pub shape: ShareOffer,
    /// The plaintext MIME type.
    ///
    /// Unlike the hosted path — which deliberately uploads
    /// `application/octet-stream` so the *host* learns nothing — the type is
    /// plain here, because there is no host: the only reader is the person being
    /// sent the file, and they need it to open what arrives.
    pub mime_type: String,
    /// The sender's own filename, for the recipient to save under.
    ///
    /// Sent here and nowhere else, and the reason is the same one that keeps it
    /// out of the caption: a device-chosen `IMG_20260731_114233.jpg` is local
    /// filesystem vocabulary. The difference is that a 400 MB file being written
    /// to the receiver's disk needs *a* name, and the sender's is a better guess
    /// than a hash.
    pub file_name: String,
    /// The caption, exactly as the hosted path carries it.
    pub caption: String,
}

/// One statement about handing an attachment over.
///
/// Three steps rather than `together`'s four, and no
/// [`Ask`](crate::share::ShareSignal::Ask): the sender picked the file, so nobody
/// has to discover who needs it.
///
/// [`Decline`](Self::Decline) and [`Refuse`](Self::Refuse) are deliberately
/// separate. A person saying "not now, I am on mobile data" and a network saying
/// "there is no direct path" are different facts with different follow-ups — the
/// first should not be retried, the second should be retried on WiFi — and
/// collapsing them into one variant with a reason code is how a UI ends up
/// telling someone their friend refused when actually their carrier did.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(tag = "handoff", rename_all = "snake_case")]
pub enum HandoffSignal {
    /// Sender → receiver: I have this for you, and this is what it is. Size,
    /// name, type and hash all arrive before a single byte does, so the answer
    /// to a 4 GB transfer can be no *before* it starts.
    Offer { attachment: AttachmentHandoff },
    /// Receiver → sender: send it. This yes is what authorises the sender to
    /// build a peer connection at all.
    Accept,
    /// Receiver → sender: a person said no.
    Decline,
    /// Either direction: the network says no, and this is which network fact.
    Refuse { reason: RefusalReason },
    /// Sender → receiver: never mind, I am taking the offer back. Distinct from
    /// [`Decline`](Self::Decline) so a receiver's UI can drop a card that is no
    /// longer answerable instead of leaving a button that does nothing.
    Withdraw,
    /// Either direction: one step of the WebRTC negotiation.
    Transport { signal: TransferSignal },
}

impl HandoffSignal {
    /// Stable discriminant string, for logging.
    pub fn kind_str(&self) -> &'static str {
        match self {
            Self::Offer { .. } => "offer",
            Self::Accept => "accept",
            Self::Decline => "decline",
            Self::Refuse { .. } => "refuse",
            Self::Withdraw => "withdraw",
            Self::Transport { .. } => "transport",
        }
    }

    /// Whether this signal can only legitimately come from the side holding the
    /// file.
    ///
    /// Checked on receipt rather than trusted: a peer that sends its own `Offer`
    /// for a transfer *we* started is either confused or probing, and either way
    /// the answer is to drop it. [`Transport`](Self::Transport) is absent from
    /// both lists because ICE genuinely flows both ways.
    pub fn is_sender_only(&self) -> bool {
        matches!(self, Self::Offer { .. } | Self::Withdraw)
    }

    /// Whether this signal can only legitimately come from the side receiving.
    pub fn is_receiver_only(&self) -> bool {
        matches!(self, Self::Accept | Self::Decline)
    }
}

/// A handoff signal as it crosses the DM channel.
///
/// Scoped by `transfer_id` rather than by a session: an attachment is not a
/// shared activity with a lifetime, it is one file going one way once. The id is
/// what lets two handoffs between the same pair proceed at the same time, and
/// what lets a late `Accept` for an abandoned transfer be dropped instead of
/// reviving it.
///
/// No `seq` and no `at_ms`, unlike [`TogetherEnvelope`](crate::together::TogetherEnvelope):
/// there are no commands to order here and no clock to synchronise. The
/// negotiation is idempotent per step, and the transfer itself is
/// receiver-driven — the receiver asking again for chunk 41 is the whole of the
/// retry logic.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HandoffEnvelope {
    /// Format marker / version; must equal [`HANDOFF_ENVELOPE_MARKER`].
    pub comrade_handoff: u8,
    /// Groups every signal of one transfer, and the primary replay guard: a
    /// signal naming a transfer this device does not have is dropped.
    pub transfer_id: String,
    pub signal: HandoffSignal,
}

impl HandoffEnvelope {
    pub fn new(transfer_id: impl Into<String>, signal: HandoffSignal) -> Self {
        Self {
            comrade_handoff: HANDOFF_ENVELOPE_MARKER,
            transfer_id: transfer_id.into(),
            signal,
        }
    }

    /// Serialise to the JSON string that becomes the DM body.
    pub fn to_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }
}

/// Detect and parse a handoff envelope out of a decrypted DM body.
///
/// Returns `None` for chat text, receipts, profile shares, presence beacons,
/// nudges, call, media *and together* envelopes — so the runtime falls through to
/// its other handlers exactly as it does for those.
pub fn parse_handoff_envelope(content: &str) -> Option<HandoffEnvelope> {
    let env: HandoffEnvelope = serde_json::from_str(content).ok()?;
    (env.comrade_handoff == HANDOFF_ENVELOPE_MARKER).then_some(env)
}

/// Mint a fresh, unguessable transfer id (128 bits of randomness, hex-encoded).
///
/// Unguessable because it is the replay guard: an id a third party could predict
/// would let them inject an `Accept` and start a transfer nobody asked for.
pub fn new_transfer_id() -> String {
    let mut bytes = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut bytes);
    hex::encode(bytes)
}

/// The filename an incoming handoff is staged under, derived from the offer's
/// content hash.
///
/// **The hash is peer-supplied and this becomes a path**, which is the whole
/// reason this is a function with tests rather than an interpolation at the call
/// site. Anything that is not lowercase hex is rejected, so `../../etc/passwd`
/// and a 4 KB name are both impossible rather than merely unlikely. Mirrors
/// `ShareDecisions.incomingFileName` on the Android side, which learned this the
/// same way.
///
/// Returns `None` for a hash that is not exactly 64 hex characters — a caller
/// with no name must refuse the transfer, not invent one.
pub fn staged_file_name(sha256_hex: &str) -> Option<String> {
    let hash = sha256_hex.trim().to_ascii_lowercase();
    if hash.len() != 64 || !hash.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    Some(format!("{hash}.part"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn shape(total: u64) -> ShareOffer {
        ShareOffer {
            total_bytes: total,
            chunk_bytes: crate::share::SHARE_CHUNK_BYTES,
            sha256: "a".repeat(64),
            duration_ms: 0,
        }
    }

    fn handoff(total: u64) -> AttachmentHandoff {
        AttachmentHandoff {
            shape: shape(total),
            mime_type: "video/mp4".into(),
            file_name: "holiday.mp4".into(),
            caption: "the last morning".into(),
        }
    }

    // ── Which road ──────────────────────────────────────────────────────────

    #[test]
    fn anything_the_hosted_path_can_carry_goes_that_way() {
        // Not a preference: below the cap the hosted path reaches someone who is
        // offline and needs no direct path, so there is nothing to trade off.
        assert_eq!(route_for_bytes(0), AttachmentRoute::Hosted);
        assert_eq!(route_for_bytes(1), AttachmentRoute::Hosted);
        assert_eq!(
            route_for_bytes(MAX_MEDIA_BYTES as u64),
            AttachmentRoute::Hosted,
            "the cap is inclusive — the core accepts exactly this many"
        );
    }

    #[test]
    fn one_byte_over_the_cap_goes_to_the_other_device_directly() {
        assert_eq!(
            route_for_bytes(MAX_MEDIA_BYTES as u64 + 1),
            AttachmentRoute::PeerToPeer
        );
        assert_eq!(
            route_for_bytes(4 * 1024 * 1024 * 1024),
            AttachmentRoute::PeerToPeer,
            "there is no second ceiling: the point of this road is not having one"
        );
    }

    #[test]
    fn the_threshold_is_the_hosted_ceiling_and_not_a_number_of_its_own() {
        // Pins the relationship, not the value: if MAX_MEDIA_BYTES ever moves,
        // the boundary must move with it rather than being separately edited.
        let cap = MAX_MEDIA_BYTES as u64;
        assert_eq!(route_for_bytes(cap), AttachmentRoute::Hosted);
        assert_eq!(route_for_bytes(cap + 1), AttachmentRoute::PeerToPeer);
    }

    // ── The shape is share's, not a copy of it ──────────────────────────────

    #[test]
    fn a_handoff_chunks_exactly_as_a_together_handover_does() {
        let h = handoff(40 * 1024);
        // 16 KiB chunks: two full and one short. Asserted through `shape` on
        // purpose — the arithmetic must be share's and not a reimplementation.
        assert_eq!(h.shape.chunk_count(), 3);
        assert_eq!(h.shape.range(0), Some((0, 16 * 1024)));
        assert_eq!(h.shape.range(2), Some((32 * 1024, 8 * 1024)));
        assert_eq!(h.shape.range(3), None);
    }

    #[test]
    fn a_huge_file_is_a_lot_of_chunks_and_the_count_does_not_overflow() {
        // 4 GB at 16 KiB is 262144 chunks — well inside u32, which is what the
        // chunk index on the wire is.
        let h = handoff(4 * 1024 * 1024 * 1024);
        assert_eq!(h.shape.chunk_count(), 262_144);
        assert!(h.shape.range(262_143).is_some());
        assert_eq!(h.shape.range(262_144), None);
    }

    // ── The wire ────────────────────────────────────────────────────────────

    #[test]
    fn every_handoff_signal_round_trips_through_json() {
        let all = [
            HandoffSignal::Offer {
                attachment: handoff(50 * 1024 * 1024),
            },
            HandoffSignal::Accept,
            HandoffSignal::Decline,
            HandoffSignal::Refuse {
                reason: RefusalReason::RelayForbidden,
            },
            HandoffSignal::Withdraw,
            HandoffSignal::Transport {
                signal: TransferSignal::Offer {
                    sdp: "v=0\r\n".into(),
                },
            },
        ];
        for signal in all {
            let env = HandoffEnvelope::new("abc123", signal.clone());
            let json = env.to_json().expect("serialises");
            let back = parse_handoff_envelope(&json).expect("parses");
            assert_eq!(back, env, "{json}");
            assert!(
                json.contains("\"handoff\":"),
                "the tag must be on the wire: {json}"
            );
        }
    }

    #[test]
    fn a_handoff_signal_is_tagged_apart_from_the_transport_signal_inside_it() {
        // Two internally-tagged enums nested one inside the other; sharing a tag
        // name would let the inner one silently overwrite the outer.
        let json = HandoffEnvelope::new(
            "t1",
            HandoffSignal::Transport {
                signal: TransferSignal::Answer { sdp: "s".into() },
            },
        )
        .to_json()
        .unwrap();
        let value: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(value["signal"]["handoff"], "transport");
        assert_eq!(value["signal"]["signal"]["kind"], "answer");
    }

    #[test]
    fn a_body_that_is_not_a_handoff_is_left_for_the_other_handlers() {
        assert!(parse_handoff_envelope("just a message").is_none());
        assert!(parse_handoff_envelope("{}").is_none());
        // A together envelope must fall through, not be swallowed.
        let together = crate::together::TogetherEnvelope::new(
            "s1",
            1,
            0,
            None,
            crate::together::TogetherSignal::Join,
        )
        .to_json()
        .unwrap();
        assert!(parse_handoff_envelope(&together).is_none());
    }

    #[test]
    fn a_wrong_marker_version_is_refused_rather_than_guessed_at() {
        let json = r#"{"comrade_handoff":9,"transfer_id":"t","signal":{"handoff":"accept"}}"#;
        assert!(parse_handoff_envelope(json).is_none());
    }

    #[test]
    fn a_transfer_id_is_unguessable_and_hex() {
        let a = new_transfer_id();
        let b = new_transfer_id();
        assert_eq!(a.len(), 32, "128 bits, hex-encoded");
        assert!(a.bytes().all(|c| c.is_ascii_hexdigit()));
        assert_ne!(a, b, "an id a third party could predict is an injection");
    }

    // ── Who may say what ────────────────────────────────────────────────────

    #[test]
    fn an_offer_from_the_receiving_side_is_recognisable_as_wrong() {
        assert!(HandoffSignal::Offer {
            attachment: handoff(1)
        }
        .is_sender_only());
        assert!(HandoffSignal::Withdraw.is_sender_only());
        assert!(HandoffSignal::Accept.is_receiver_only());
        assert!(HandoffSignal::Decline.is_receiver_only());
    }

    #[test]
    fn ice_is_not_owned_by_either_side_because_it_genuinely_flows_both_ways() {
        let ice = HandoffSignal::Transport {
            signal: TransferSignal::Ice {
                candidate: String::new(),
                sdp_mid: None,
                sdp_m_line_index: None,
            },
        };
        assert!(!ice.is_sender_only());
        assert!(!ice.is_receiver_only());
        // And a refusal can come from whichever side's network gave up first.
        let refuse = HandoffSignal::Refuse {
            reason: RefusalReason::PathUnknown,
        };
        assert!(!refuse.is_sender_only());
        assert!(!refuse.is_receiver_only());
    }

    #[test]
    fn a_person_declining_is_not_the_same_signal_as_a_network_refusing() {
        // Collapsing these is how a UI tells someone their friend said no when
        // actually their carrier did.
        assert_ne!(
            HandoffSignal::Decline.kind_str(),
            HandoffSignal::Refuse {
                reason: RefusalReason::RelayForbidden
            }
            .kind_str()
        );
    }

    // ── Staging a peer-supplied name ────────────────────────────────────────

    #[test]
    fn an_incoming_file_is_staged_under_its_content_hash() {
        let hash = "b".repeat(64);
        assert_eq!(staged_file_name(&hash), Some(format!("{hash}.part")));
        // Case and surrounding space are normalised rather than refused: a peer
        // sending uppercase hex is not attacking anyone.
        assert_eq!(
            staged_file_name(&format!("  {}  ", "B".repeat(64))),
            Some(format!("{}.part", "b".repeat(64)))
        );
    }

    #[test]
    fn a_hash_that_is_not_a_hash_never_becomes_a_path() {
        // The hash arrives from the peer and this is a filename.
        assert_eq!(staged_file_name("../../etc/passwd"), None);
        assert_eq!(staged_file_name(""), None);
        assert_eq!(staged_file_name(&"a".repeat(63)), None, "too short");
        assert_eq!(staged_file_name(&"a".repeat(65)), None, "too long");
        assert_eq!(
            staged_file_name(&format!("{}/", "a".repeat(63))),
            None,
            "right length, wrong alphabet"
        );
        assert_eq!(staged_file_name(&"z".repeat(64)), None, "not hex");
    }
}
