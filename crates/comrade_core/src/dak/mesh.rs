/*!
 * Mesh mail — a DM delivered over the local network instead of a relay.
 *
 * This is the transport the [`super::outbox`] was missing. Queuing a message a
 * relay would not take is only half the answer; the other half is a way to
 * deliver it when there is no internet at all but the two people are on the
 * same WiFi (or, later, in Bluetooth range — see `docs/OFFLINE_DELIVERY.md`).
 *
 * bitchat's answer is a BLE mesh that floods opaque frames and lets only the
 * addressee open them. Comrade already has a local-network mesh
 * ([`crate::saathi`]: mDNS discovery + Gossipsub flooding) that was previously
 * carrying nothing but a peer count, and — since the courier work — a sealed
 * envelope whose only routing datum is a day-rotating tag
 * ([`super::courier`]). Putting the second inside the first gives exactly
 * bitchat's property on a different radio:
 *
 *  • every peer on the LAN sees the frame; only the recipient can open it,
 *  • the frame carries no sender identity and no plaintext recipient, and
 *  • its length reveals only a padding bucket.
 *
 * ## Why the payload has its own envelope
 *
 * The sealed bytes carry a [`MeshDm`], not a bare string, so the *same*
 * content that rides a Nostr DM rides the mesh: a chat message, a
 * delivered/read receipt, a profile share, a call signal. The receiving side
 * feeds it through the identical ingress path as a relay-delivered DM, so
 * gating, persistence, receipts and dedup behave the same however the message
 * arrived.
 *
 * `id` is the sender's message id, which is what makes cross-transport dedup
 * possible at all: a message that arrives twice — once over the mesh, once
 * over a relay that finally came back — is one message, and the receiver has
 * to be able to tell.
 */

use nostr_sdk::prelude::{Keys, PublicKey};
use serde::{Deserialize, Serialize};

use super::courier::{self, Envelope};
use crate::{
    error::DakError,
    metrics::{self, Metric},
};

/// A direct message as it travels sealed inside a mesh frame.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MeshDm {
    /// The sender's id for this message — a relay event id if it ever reached
    /// one, else the locally minted [`super::outbox::local_message_id`]. The
    /// receiver dedups on it.
    pub id: String,
    /// Exactly what the DM channel would carry: chat text, or a control
    /// envelope (receipt, profile share, call signal) as JSON.
    pub content: String,
    /// NIP-10 parent, when this is a reply.
    #[serde(default)]
    pub reply_to: Option<String>,
    /// Sender's clock (unix seconds).
    pub created_at: u64,
}

impl MeshDm {
    pub fn new(
        id: impl Into<String>,
        content: impl Into<String>,
        reply_to: Option<String>,
        created_at: u64,
    ) -> Self {
        Self {
            id: id.into(),
            content: content.into(),
            reply_to,
            created_at,
        }
    }
}

/// Seal a DM for `recipient` into a mesh frame, addressed only by the
/// day-rotating recipient tag.
///
/// # Errors
/// [`DakError::Malformed`] if the payload will not serialise, and whatever
/// [`courier::seal`] reports (notably [`DakError::Oversize`] for a message
/// past the 16 KiB cap).
pub fn seal_dm(
    recipient: &PublicKey,
    sender: &Keys,
    dm: &MeshDm,
    now_secs: u64,
) -> Result<Envelope, DakError> {
    let payload =
        serde_json::to_vec(dm).map_err(|e| DakError::Malformed(format!("mesh dm: {e}")))?;
    let envelope = courier::seal_envelope(recipient, sender, &payload, now_secs)?;
    metrics::record(Metric::MeshSent);
    Ok(envelope)
}

/// A mesh frame that turned out to be addressed to us.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OpenedDm {
    /// Authenticated sender — proven by the inner MAC, not merely claimed.
    pub sender: PublicKey,
    pub dm: MeshDm,
}

/// Try to open a mesh frame as a DM addressed to us.
///
/// Returns `Ok(None)` when the frame is simply for someone else — the common
/// case on a shared network, and not an error. `Err` is reserved for a frame
/// that *was* addressed to us (its tag matched, or its seal decrypted) and then
/// failed to validate, which is worth logging.
///
/// # Errors
/// [`DakError::AuthFailed`] when the inner MAC does not match the claimed
/// sender, and [`DakError::Malformed`] when the sealed payload is not a
/// [`MeshDm`].
pub fn open_dm(
    us: &Keys,
    envelope: &Envelope,
    now_secs: u64,
) -> Result<Option<OpenedDm>, DakError> {
    // Cheap reject first: a frame whose tag is not one of ours cannot be for
    // us, and this is the branch nearly every frame on a busy LAN takes.
    let tags = courier::candidate_tags(&us.public_key(), now_secs);
    if !tags.contains(&envelope.recipient_tag) {
        return Ok(None);
    }

    let opened = match courier::open(us, &envelope.ciphertext) {
        Ok(opened) => opened,
        // A tag collision (16 bytes, so vanishingly unlikely, but a hostile
        // peer can send any tag it likes) is not our mail.
        Err(DakError::Crypto(_)) => return Ok(None),
        Err(e) => return Err(e),
    };

    let dm: MeshDm = serde_json::from_slice(&opened.payload)
        .map_err(|e| DakError::Malformed(format!("mesh dm payload: {e}")))?;
    metrics::record(Metric::MeshReceived);
    Ok(Some(OpenedDm {
        sender: opened.sender,
        dm,
    }))
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::KeyProfile;

    const DAY: u64 = 86_400;
    const T0: u64 = 1_700_000_000 / DAY * DAY;

    fn keys() -> Keys {
        KeyProfile::generate().expect("keygen").keys
    }

    fn dm() -> MeshDm {
        MeshDm::new("queued:abc", "are you still up?", None, T0)
    }

    #[test]
    fn the_recipient_opens_it_and_learns_who_sent_it() {
        let alice = keys();
        let bob = keys();
        let frame = seal_dm(&bob.public_key(), &alice, &dm(), T0).expect("seal");

        let opened = open_dm(&bob, &frame, T0).expect("open").expect("for us");
        assert_eq!(opened.sender, alice.public_key());
        assert_eq!(opened.dm, dm());
    }

    #[test]
    fn everyone_else_on_the_network_sees_only_an_opaque_frame() {
        let frame = seal_dm(&keys().public_key(), &keys(), &dm(), T0).expect("seal");
        let bystander = keys();
        assert_eq!(
            open_dm(&bystander, &frame, T0).expect("not an error"),
            None,
            "a frame for someone else is skipped, not failed"
        );
    }

    #[test]
    fn the_frame_carries_no_plaintext_at_all() {
        let alice = keys();
        let bob = keys();
        let frame = seal_dm(&bob.public_key(), &alice, &dm(), T0).expect("seal");
        let wire = frame.encode();

        assert!(
            !wire.windows(9).any(|w| w == b"still up?"),
            "message text must not appear on the wire"
        );
        for key in [alice.public_key(), bob.public_key()] {
            let bytes = key.to_bytes();
            assert!(
                !wire.windows(32).any(|w| w == bytes),
                "neither party's key may appear on the wire"
            );
        }
    }

    #[test]
    fn a_reply_and_its_parent_survive_the_round_trip() {
        let bob = keys();
        let reply = MeshDm::new("queued:def", "i'm here", Some("parent-id".into()), T0 + 5);
        let frame = seal_dm(&bob.public_key(), &keys(), &reply, T0).expect("seal");
        let opened = open_dm(&bob, &frame, T0).expect("open").expect("for us");
        assert_eq!(opened.dm.reply_to.as_deref(), Some("parent-id"));
        assert_eq!(opened.dm.created_at, T0 + 5);
    }

    #[test]
    fn a_control_envelope_rides_the_mesh_like_any_message() {
        // Receipts are what clear the sender's outbox, so they must be able to
        // come back the same way the message went out.
        let bob = keys();
        let receipt = crate::dm::Receipt::new(
            crate::dm::ReceiptKind::Delivered,
            vec!["queued:abc".to_string()],
        )
        .to_json()
        .expect("receipt json");
        let frame = seal_dm(
            &bob.public_key(),
            &keys(),
            &MeshDm::new("r1", &receipt, None, T0),
            T0,
        )
        .expect("seal");

        let opened = open_dm(&bob, &frame, T0).expect("open").expect("for us");
        let parsed = crate::dm::parse_receipt(&opened.dm.content).expect("still a receipt");
        assert_eq!(parsed.message_ids, vec!["queued:abc".to_string()]);
    }

    #[test]
    fn a_frame_sealed_before_midnight_still_opens_after_it() {
        let bob = keys();
        let frame = seal_dm(&bob.public_key(), &keys(), &dm(), T0 + DAY - 5).expect("seal");
        assert!(
            open_dm(&bob, &frame, T0 + DAY + 5).expect("open").is_some(),
            "the ±1-day candidate tags must cover the rollover"
        );
    }

    #[test]
    fn a_tag_that_matches_but_a_seal_that_does_not_is_skipped_quietly() {
        // A hostile peer can put our tag on a frame we cannot decrypt. That is
        // noise to be ignored, not an error to surface.
        let us = keys();
        let mut frame = seal_dm(&keys().public_key(), &keys(), &dm(), T0).expect("seal");
        frame.recipient_tag = courier::recipient_tag(&us.public_key(), courier::epoch_day(T0));
        assert_eq!(open_dm(&us, &frame, T0).expect("not an error"), None);
    }

    #[test]
    fn a_sealed_non_dm_payload_is_reported_as_malformed() {
        // Correctly sealed to us, but the payload is not a MeshDm — a peer
        // running a newer/older wire format. Distinct from "not our mail".
        let bob = keys();
        let ciphertext =
            courier::seal(&bob.public_key(), &keys(), b"not json at all").expect("seal");
        let frame = Envelope::new(
            courier::recipient_tag(&bob.public_key(), courier::epoch_day(T0)),
            (T0 + 60) * 1_000,
            1,
            ciphertext,
        );
        assert!(matches!(
            open_dm(&bob, &frame, T0),
            Err(DakError::Malformed(_))
        ));
    }
}
