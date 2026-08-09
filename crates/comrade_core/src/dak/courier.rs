/*!
 * Couriers — a person carries your sealed message to someone who is offline.
 *
 * Adopted from bitchat's courier system (whitepaper §5.2 + §6.2), the most
 * original idea in that codebase: when no transport can deliver, the message is
 * sealed and handed to peers who may *physically* encounter the recipient. The
 * properties that make it safe to run on a stranger's phone are all kept here:
 *
 *  • **Opaque addressing.** The only routing datum is a 16-byte tag,
 *    `HMAC-SHA256(recipient pubkey, context ‖ UTC day)`, computable only by
 *    someone who already knows the recipient's key. Tags do not correlate
 *    across days ([`recipient_tag`], [`candidate_tags`]).
 *  • **Trust tiers and quotas.** A trusted contact may deposit
 *    [`MAX_PER_TRUSTED_DEPOSITOR`]; anyone else [`MAX_PER_VERIFIED_DEPOSITOR`],
 *    into a sub-pool that can never crowd out trusted mail
 *    ([`MAX_VERIFIED_ENVELOPES`] of [`MAX_ENVELOPES`]).
 *  • **Spray and wait.** Each envelope carries a copy budget; a courier meeting
 *    another courier hands over half, so mail diffuses through a moving crowd
 *    instead of riding one person ([`CourierStore::spray_to`]).
 *  • **Bounded everything.** Size, lifetime, per-depositor count, and a
 *    handover cooldown, so a device cannot be turned into a public mailbag or
 *    an amplifier.
 *
 * ## Sealing
 *
 * bitchat seals with the one-way Noise `X` pattern. Comrade has no Noise stack,
 * so [`seal`] builds the same shape from primitives already in
 * [`crate::crypto`]:
 *
 * ```text
 * sealed := 0x01 ‖ ephemeral_xonly(32) ‖ AES-256-GCM(nonce ‖ ct+tag)
 * AEAD key := HKDF(ECDH(ephemeral_sk, recipient_pk), "comrade-dak-seal-v1")
 * plaintext := sender_xonly(32) ‖ auth_mac(32) ‖ pad(payload)
 * auth_mac := HMAC-SHA256(HKDF(ECDH(sender_sk, recipient_pk),
 *                              "comrade-dak-auth-v1"),
 *                         ephemeral_xonly ‖ payload)
 * ```
 *
 * The ephemeral key means the wire carries **no sender identity** — a courier
 * sees a tag and a blob. The inner MAC, keyed by the static-static shared
 * secret, is what proves who wrote it: only the real sender could compute it,
 * and only the recipient can check it. It is a MAC rather than a signature on
 * purpose — the recipient cannot use it to prove to a third party that you
 * wrote anything.
 *
 * The payload is padded to a bucket ([`crate::pad`]) so ciphertext length does
 * not leak message length, closing a gap bitchat left open for non-Noise
 * frames.
 *
 * **No forward secrecy**, exactly as in bitchat §5.2: compromise of a
 * recipient's identity key exposes sealed-but-undelivered mail addressed to it.
 * bitchat's answer is one-time prekeys; that is deliberately out of scope here
 * and recorded in `docs/BITCHAT_ADOPTION.md`.
 */

use std::collections::{BTreeMap, BTreeSet};

use hmac::{Hmac, Mac};
use nostr_sdk::prelude::{Keys, PublicKey};
use serde::{Deserialize, Serialize};
use sha2::Sha256;

use crate::{
    crypto::{aes256gcm_open, aes256gcm_seal, compute_dh_shared_secret, derive_symmetric_key},
    error::DakError,
    metrics::{self, Metric},
    pad,
};

type HmacSha256 = Hmac<Sha256>;

/// Bytes of recipient tag. 16 is plenty to route on and short enough that the
/// tag itself is not a useful fingerprint.
pub const TAG_LEN: usize = 16;

/// Domain separator for the tag HMAC.
const TAG_CONTEXT: &[u8] = b"comrade-dak-tag-v1";
/// HKDF label for the ephemeral→recipient sealing key.
const SEAL_LABEL: &str = "comrade-dak-seal-v1";
/// HKDF label for the sender→recipient authentication key.
const AUTH_LABEL: &str = "comrade-dak-auth-v1";
/// Envelope wire version.
const WIRE_VERSION: u8 = 1;
/// Length of an x-only secp256k1 public key.
const XONLY_LEN: usize = 32;
/// Length of the inner authentication MAC.
const MAC_LEN: usize = 32;

/// Couriered mail is text-sized; media stays on the media pipeline.
pub const MAX_CIPHERTEXT_BYTES: usize = 16 * 1024;
/// Longest an envelope may live, matching [`super::outbox::TTL_SECS`].
pub const MAX_LIFETIME_SECS: u64 = 24 * 60 * 60;
/// Slack on top of the lifetime for depositor clock skew.
pub const EXPIRY_SLACK_SECS: u64 = 60 * 60;
/// Copy budget a fresh envelope starts with.
pub const INITIAL_COPIES: u8 = 4;
/// Cap on the budget a depositor may claim, so the courier network cannot be
/// turned into an amplifier.
pub const MAX_COPIES: u8 = 8;
/// Total envelopes one device will carry.
pub const MAX_ENVELOPES: usize = 40;
/// Of those, how many may come from non-trusted depositors.
pub const MAX_VERIFIED_ENVELOPES: usize = 20;
/// Per-depositor caps by tier.
pub const MAX_PER_TRUSTED_DEPOSITOR: usize = 5;
pub const MAX_PER_VERIFIED_DEPOSITOR: usize = 2;
/// Minimum gap between speculative pushes of the same envelope toward a
/// recipient heard only via a relayed announcement.
pub const REMOTE_HANDOVER_COOLDOWN_SECS: u64 = 10 * 60;

/// UTC day number that rotates recipient tags.
pub fn epoch_day(unix_secs: u64) -> u32 {
    (unix_secs / 86_400) as u32
}

/// Rotating recipient hint for one day.
///
/// Keyed by the recipient's public key, so only a party that already knows that
/// key can compute — or recognise — the tag.
pub fn recipient_tag(recipient: &PublicKey, day: u32) -> [u8; TAG_LEN] {
    let mut mac = <HmacSha256 as Mac>::new_from_slice(&recipient.to_bytes())
        .expect("HMAC-SHA256 accepts any key length");
    mac.update(TAG_CONTEXT);
    mac.update(&day.to_be_bytes());
    let digest = mac.finalize().into_bytes();
    let mut tag = [0u8; TAG_LEN];
    tag.copy_from_slice(&digest[..TAG_LEN]);
    tag
}

/// Tags to test when checking whether carried mail is addressed to a peer:
/// yesterday, today, tomorrow. Envelopes sealed near midnight (or under modest
/// clock skew) still match while being carried.
pub fn candidate_tags(recipient: &PublicKey, now_secs: u64) -> Vec<[u8; TAG_LEN]> {
    let today = epoch_day(now_secs);
    [today.saturating_sub(1), today, today + 1]
        .into_iter()
        .map(|day| recipient_tag(recipient, day))
        .collect()
}

/// Sealed mail in transit, as it goes on the wire.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Envelope {
    #[serde(with = "tag_bytes")]
    pub recipient_tag: [u8; TAG_LEN],
    /// Unix **milliseconds** after which the envelope must be discarded.
    pub expiry_ms: u64,
    /// Remaining spray-and-wait budget (1 = carry-only).
    pub copies: u8,
    /// Opaque sealed bytes — see [`seal`].
    pub ciphertext: Vec<u8>,
}

impl Envelope {
    pub fn new(
        recipient_tag: [u8; TAG_LEN],
        expiry_ms: u64,
        copies: u8,
        ciphertext: Vec<u8>,
    ) -> Self {
        Self {
            recipient_tag,
            expiry_ms,
            copies: copies.clamp(1, MAX_COPIES),
            ciphertext,
        }
    }

    pub fn is_expired(&self, now_ms: u64) -> bool {
        now_ms >= self.expiry_ms
    }

    /// The same envelope with a different remaining copy budget.
    pub fn with_copies(&self, copies: u8) -> Self {
        Self {
            copies: copies.clamp(1, MAX_COPIES),
            ..self.clone()
        }
    }

    /// Wire encoding: `version(1) ‖ tag(16) ‖ expiry_ms(8 BE) ‖ copies(1) ‖ ciphertext`.
    ///
    /// A fixed layout rather than bitchat's TLV: Comrade has no deployed
    /// courier wire format to stay compatible with, and the version byte is the
    /// forward-compatibility seam.
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(1 + TAG_LEN + 8 + 1 + self.ciphertext.len());
        out.push(WIRE_VERSION);
        out.extend_from_slice(&self.recipient_tag);
        out.extend_from_slice(&self.expiry_ms.to_be_bytes());
        out.push(self.copies);
        out.extend_from_slice(&self.ciphertext);
        out
    }

    /// Parse a wire envelope.
    ///
    /// # Errors
    /// [`DakError::Malformed`] for a truncated buffer or an unknown version,
    /// and [`DakError::Oversize`] when the ciphertext exceeds the policy cap —
    /// both before any allocation proportional to attacker-controlled input.
    pub fn decode(bytes: &[u8]) -> Result<Self, DakError> {
        const HEADER: usize = 1 + TAG_LEN + 8 + 1;
        if bytes.len() <= HEADER {
            return Err(DakError::Malformed(
                "envelope shorter than its header".into(),
            ));
        }
        if bytes[0] != WIRE_VERSION {
            return Err(DakError::Malformed(format!(
                "unsupported envelope version {}",
                bytes[0]
            )));
        }
        let mut recipient_tag = [0u8; TAG_LEN];
        recipient_tag.copy_from_slice(&bytes[1..1 + TAG_LEN]);
        let mut expiry = [0u8; 8];
        expiry.copy_from_slice(&bytes[1 + TAG_LEN..1 + TAG_LEN + 8]);
        let copies = bytes[1 + TAG_LEN + 8];
        let ciphertext = bytes[HEADER..].to_vec();
        if ciphertext.len() > MAX_CIPHERTEXT_BYTES {
            return Err(DakError::Oversize {
                size: ciphertext.len(),
                max: MAX_CIPHERTEXT_BYTES,
            });
        }
        Ok(Self {
            recipient_tag,
            expiry_ms: u64::from_be_bytes(expiry),
            copies: copies.clamp(1, MAX_COPIES),
            ciphertext,
        })
    }
}

/// Fixed-size byte array as a hex string, so an envelope can also travel as
/// JSON (the Saathi mesh frame format) without a base64 dance.
mod tag_bytes {
    use serde::{Deserialize, Deserializer, Serializer};

    pub fn serialize<S: Serializer>(tag: &[u8; super::TAG_LEN], s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&hex::encode(tag))
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<[u8; super::TAG_LEN], D::Error> {
        let hex_str = String::deserialize(d)?;
        let bytes = hex::decode(&hex_str).map_err(serde::de::Error::custom)?;
        bytes
            .try_into()
            .map_err(|_| serde::de::Error::custom("recipient tag must be 16 bytes"))
    }
}

/// What a courier grants a depositor.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum DepositTier {
    /// An accepted contact (Comrade's analogue of a bitchat mutual favorite).
    Trusted,
    /// Any peer whose identity we could verify but have not accepted.
    Verified,
}

impl DepositTier {
    const fn per_depositor_cap(self) -> usize {
        match self {
            Self::Trusted => MAX_PER_TRUSTED_DEPOSITOR,
            Self::Verified => MAX_PER_VERIFIED_DEPOSITOR,
        }
    }
}

/// Sealed mail plus the local bookkeeping a courier keeps about it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CarriedEnvelope {
    pub envelope: Envelope,
    /// Who handed it to us (npub), for per-depositor quotas.
    pub depositor: String,
    pub tier: DepositTier,
    /// When we accepted it (unix ms), for eviction order.
    pub stored_at_ms: u64,
    /// Couriers we already sprayed a copy to, so a repeat encounter does not
    /// burn budget on a copy they already hold.
    pub sprayed_to: BTreeSet<String>,
    /// Last speculative push toward a relayed sighting of the recipient.
    pub last_remote_handover_ms: Option<u64>,
}

/// The persisted form of a courier bag.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct CourierSnapshot {
    pub carried: Vec<CarriedEnvelope>,
}

/// Mail this device is carrying for third parties.
///
/// Not interior-mutable: every mutation is a policy decision the caller must
/// sequence (deposit, handover, spray), so `&mut self` is the honest signature.
#[derive(Debug, Default)]
pub struct CourierStore {
    carried: Vec<CarriedEnvelope>,
}

impl CourierStore {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn from_snapshot(snapshot: CourierSnapshot) -> Self {
        Self {
            carried: snapshot.carried,
        }
    }

    /// Accept an envelope to carry.
    ///
    /// The caller decides the tier (that is trust policy); this enforces
    /// resource bounds only.
    ///
    /// # Errors
    /// [`DakError::Expired`] / [`DakError::LifetimeTooLong`] for a lifetime
    /// outside policy, [`DakError::Oversize`] for a too-large ciphertext,
    /// [`DakError::Malformed`] for an empty one, [`DakError::DepositorQuotaFull`]
    /// when this depositor has used its share, and [`DakError::BagFull`] when
    /// the bag cannot make room without evicting mail of an equal-or-higher
    /// tier.
    pub fn deposit(
        &mut self,
        envelope: Envelope,
        depositor: &str,
        tier: DepositTier,
        now_ms: u64,
    ) -> Result<(), DakError> {
        if envelope.ciphertext.is_empty() {
            return Err(DakError::Malformed("empty ciphertext".into()));
        }
        if envelope.ciphertext.len() > MAX_CIPHERTEXT_BYTES {
            return Err(DakError::Oversize {
                size: envelope.ciphertext.len(),
                max: MAX_CIPHERTEXT_BYTES,
            });
        }
        if envelope.is_expired(now_ms) {
            metrics::record(Metric::CourierRejected);
            return Err(DakError::Expired);
        }
        // A depositor must not be able to pin storage for longer than the
        // sender's own outbox would retain the message.
        let max_expiry_ms = now_ms + (MAX_LIFETIME_SECS + EXPIRY_SLACK_SECS) * 1_000;
        if envelope.expiry_ms > max_expiry_ms {
            metrics::record(Metric::CourierRejected);
            return Err(DakError::LifetimeTooLong(MAX_LIFETIME_SECS));
        }

        self.prune(now_ms);

        // Identical ciphertext is the same envelope arriving by another route.
        // Before any spray, a carry-only copy may legitimately arrive ahead of
        // the original higher-budget one, so keep the larger budget. Once this
        // branch has sprayed, replaying the depositor's packet must never
        // replenish spent copies — that would defeat spray-and-wait.
        if let Some(existing) = self
            .carried
            .iter_mut()
            .find(|c| c.envelope.ciphertext == envelope.ciphertext)
        {
            if existing.sprayed_to.is_empty() {
                existing.envelope.copies = existing.envelope.copies.max(envelope.copies);
            }
            return Ok(());
        }

        let from_depositor = self
            .carried
            .iter()
            .filter(|c| c.depositor == depositor)
            .count();
        if from_depositor >= tier.per_depositor_cap() {
            metrics::record(Metric::CourierRejected);
            return Err(DakError::DepositorQuotaFull);
        }

        if tier == DepositTier::Verified {
            let verified = self
                .carried
                .iter()
                .filter(|c| c.tier == DepositTier::Verified)
                .count();
            if verified >= MAX_VERIFIED_ENVELOPES {
                metrics::record(Metric::CourierRejected);
                return Err(DakError::BagFull);
            }
        }

        if self.carried.len() >= MAX_ENVELOPES {
            // Make room by evicting the oldest verified-tier envelope. Trusted
            // mail is never evicted for a new deposit, so a crowd of strangers
            // cannot crowd out a loved one's message.
            let victim = self
                .carried
                .iter()
                .enumerate()
                .filter(|(_, c)| c.tier == DepositTier::Verified)
                .min_by_key(|(_, c)| c.stored_at_ms)
                .map(|(i, _)| i);
            match victim {
                Some(index) => {
                    self.carried.remove(index);
                }
                None => {
                    metrics::record(Metric::CourierRejected);
                    return Err(DakError::BagFull);
                }
            }
        }

        self.carried.push(CarriedEnvelope {
            envelope,
            depositor: depositor.to_string(),
            tier,
            stored_at_ms: now_ms,
            sprayed_to: BTreeSet::new(),
            last_remote_handover_ms: None,
        });
        metrics::record(Metric::CourierAccepted);
        Ok(())
    }

    /// The recipient is here: hand over everything addressed to them and stop
    /// carrying it.
    pub fn take_for(&mut self, tags: &[[u8; TAG_LEN]], now_ms: u64) -> Vec<Envelope> {
        self.prune(now_ms);
        let mut handed = Vec::new();
        self.carried.retain(|c| {
            if tags.contains(&c.envelope.recipient_tag) {
                handed.push(c.envelope.clone());
                false
            } else {
                true
            }
        });
        metrics::record_n(Metric::CourierHandedOver, handed.len() as u64);
        handed
    }

    /// The recipient was heard about at a distance: push a *copy* toward them
    /// and keep carrying the original, at most once per envelope per
    /// [`REMOTE_HANDOVER_COOLDOWN_SECS`].
    pub fn remote_handover(&mut self, tags: &[[u8; TAG_LEN]], now_ms: u64) -> Vec<Envelope> {
        self.prune(now_ms);
        let cooldown_ms = REMOTE_HANDOVER_COOLDOWN_SECS * 1_000;
        let mut pushed = Vec::new();
        for carried in &mut self.carried {
            if !tags.contains(&carried.envelope.recipient_tag) {
                continue;
            }
            let ready = carried
                .last_remote_handover_ms
                .is_none_or(|last| now_ms.saturating_sub(last) >= cooldown_ms);
            if ready {
                carried.last_remote_handover_ms = Some(now_ms);
                pushed.push(carried.envelope.clone());
            }
        }
        pushed
    }

    /// Met another courier: split copy budgets with them.
    ///
    /// Each envelope with budget to spare hands over half (rounded down),
    /// keeping the rest — bitchat's binary-split spray-and-wait. Returns the
    /// copies to transfer; a courier already sprayed is skipped so a repeat
    /// encounter cannot drain the budget.
    pub fn spray_to(&mut self, courier: &str, now_ms: u64) -> Vec<Envelope> {
        self.prune(now_ms);
        let mut handed = Vec::new();
        for carried in &mut self.carried {
            if carried.envelope.copies < 2 || carried.sprayed_to.contains(courier) {
                continue;
            }
            let give = carried.envelope.copies / 2;
            carried.envelope.copies -= give;
            carried.sprayed_to.insert(courier.to_string());
            handed.push(carried.envelope.with_copies(give));
        }
        metrics::record_n(Metric::CourierSprayed, handed.len() as u64);
        handed
    }

    /// Drop expired mail. Returns how many envelopes were discarded.
    pub fn prune(&mut self, now_ms: u64) -> usize {
        let before = self.carried.len();
        self.carried.retain(|c| !c.envelope.is_expired(now_ms));
        let dropped = before - self.carried.len();
        metrics::record_n(Metric::CourierExpired, dropped as u64);
        dropped
    }

    pub fn carried_count(&self) -> usize {
        self.carried.len()
    }

    pub fn is_empty(&self) -> bool {
        self.carried.is_empty()
    }

    /// Envelope counts per tier — for the diagnostics surface, never per peer.
    pub fn tier_counts(&self) -> BTreeMap<&'static str, usize> {
        let mut counts = BTreeMap::new();
        for carried in &self.carried {
            let key = match carried.tier {
                DepositTier::Trusted => "trusted",
                DepositTier::Verified => "verified",
            };
            *counts.entry(key).or_insert(0) += 1;
        }
        counts
    }

    pub fn snapshot(&self) -> CourierSnapshot {
        CourierSnapshot {
            carried: self.carried.clone(),
        }
    }

    /// Drop all carried mail — part of the panic wipe.
    pub fn clear(&mut self) {
        self.carried.clear();
    }
}

/// A successfully opened envelope.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Opened {
    /// Authenticated sender.
    pub sender: PublicKey,
    pub payload: Vec<u8>,
}

/// Seal `payload` for `recipient`, authenticated as `sender`.
///
/// # Errors
/// [`DakError::Oversize`] when the sealed result would exceed
/// [`MAX_CIPHERTEXT_BYTES`], and [`DakError::Crypto`] if key agreement or the
/// AEAD fails.
pub fn seal(recipient: &PublicKey, sender: &Keys, payload: &[u8]) -> Result<Vec<u8>, DakError> {
    let ephemeral = Keys::generate();
    let ephemeral_xonly = ephemeral.public_key().to_bytes();

    let seal_secret = compute_dh_shared_secret(ephemeral.secret_key(), recipient)?;
    let seal_key = derive_symmetric_key(&seal_secret, SEAL_LABEL);

    let auth_secret = compute_dh_shared_secret(sender.secret_key(), recipient)?;
    let auth_key = derive_symmetric_key(&auth_secret, AUTH_LABEL);
    let auth_mac = authenticate(&auth_key, &ephemeral_xonly, payload);

    let mut plaintext = Vec::with_capacity(XONLY_LEN + MAC_LEN + payload.len() + 64);
    plaintext.extend_from_slice(&sender.public_key().to_bytes());
    plaintext.extend_from_slice(&auth_mac);
    // Pad inside the AEAD so the sealed length reveals only a bucket.
    plaintext.extend_from_slice(&pad::pad(payload));

    let mut sealed = Vec::with_capacity(1 + XONLY_LEN + plaintext.len() + 32);
    sealed.push(WIRE_VERSION);
    sealed.extend_from_slice(&ephemeral_xonly);
    sealed.extend_from_slice(&aes256gcm_seal(&seal_key, &plaintext)?);

    if sealed.len() > MAX_CIPHERTEXT_BYTES {
        return Err(DakError::Oversize {
            size: sealed.len(),
            max: MAX_CIPHERTEXT_BYTES,
        });
    }
    Ok(sealed)
}

/// Open mail addressed to us and authenticate its sender.
///
/// # Errors
/// [`DakError::Malformed`] for a truncated buffer or unknown version,
/// [`DakError::Crypto`] when the AEAD rejects it (which is what a wrong
/// recipient looks like), and [`DakError::AuthFailed`] when the inner MAC does
/// not match the claimed sender.
pub fn open(recipient: &Keys, sealed: &[u8]) -> Result<Opened, DakError> {
    if sealed.len() <= 1 + XONLY_LEN {
        return Err(DakError::Malformed("sealed buffer too short".into()));
    }
    if sealed[0] != WIRE_VERSION {
        return Err(DakError::Malformed(format!(
            "unsupported seal version {}",
            sealed[0]
        )));
    }
    let ephemeral_xonly = &sealed[1..1 + XONLY_LEN];
    let ephemeral_pk = PublicKey::from_slice(ephemeral_xonly)
        .map_err(|e| DakError::Malformed(format!("ephemeral key: {e}")))?;

    let seal_secret = compute_dh_shared_secret(recipient.secret_key(), &ephemeral_pk)?;
    let seal_key = derive_symmetric_key(&seal_secret, SEAL_LABEL);
    let plaintext = aes256gcm_open(&seal_key, &sealed[1 + XONLY_LEN..])?;

    if plaintext.len() < XONLY_LEN + MAC_LEN {
        return Err(DakError::Malformed("sealed plaintext too short".into()));
    }
    let sender = PublicKey::from_slice(&plaintext[..XONLY_LEN])
        .map_err(|e| DakError::Malformed(format!("sender key: {e}")))?;
    let claimed_mac = &plaintext[XONLY_LEN..XONLY_LEN + MAC_LEN];
    let payload = pad::unpad(&plaintext[XONLY_LEN + MAC_LEN..])?.to_vec();

    let auth_secret = compute_dh_shared_secret(recipient.secret_key(), &sender)?;
    let auth_key = derive_symmetric_key(&auth_secret, AUTH_LABEL);
    let expected = authenticate(&auth_key, ephemeral_xonly, &payload);
    // Constant-time comparison: `verify_slice` on the freshly keyed MAC.
    let mut mac =
        <HmacSha256 as Mac>::new_from_slice(&auth_key).expect("HMAC-SHA256 accepts any key length");
    mac.update(ephemeral_xonly);
    mac.update(&payload);
    if mac.verify_slice(claimed_mac).is_err() {
        debug_assert_ne!(expected.as_slice(), claimed_mac);
        return Err(DakError::AuthFailed);
    }

    metrics::record(Metric::CourierOpened);
    Ok(Opened { sender, payload })
}

fn authenticate(auth_key: &[u8; 32], ephemeral_xonly: &[u8], payload: &[u8]) -> [u8; MAC_LEN] {
    let mut mac =
        <HmacSha256 as Mac>::new_from_slice(auth_key).expect("HMAC-SHA256 accepts any key length");
    mac.update(ephemeral_xonly);
    mac.update(payload);
    let mut out = [0u8; MAC_LEN];
    out.copy_from_slice(&mac.finalize().into_bytes());
    out
}

/// Build a ready-to-deposit envelope for `recipient`, sealed by `sender`.
///
/// # Errors
/// Whatever [`seal`] reports.
pub fn seal_envelope(
    recipient: &PublicKey,
    sender: &Keys,
    payload: &[u8],
    now_secs: u64,
) -> Result<Envelope, DakError> {
    let ciphertext = seal(recipient, sender, payload)?;
    let envelope = Envelope::new(
        recipient_tag(recipient, epoch_day(now_secs)),
        (now_secs + MAX_LIFETIME_SECS) * 1_000,
        INITIAL_COPIES,
        ciphertext,
    );
    metrics::record(Metric::CourierDeposited);
    Ok(envelope)
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::KeyProfile;

    const DAY: u64 = 86_400;
    /// 2023-11-14ish, and a whole number of days so midnight tests are exact.
    const T0: u64 = 1_700_000_000 / DAY * DAY;

    fn keys() -> Keys {
        KeyProfile::generate().expect("keygen").keys
    }

    fn envelope_for(recipient: &PublicKey, sender: &Keys, body: &str) -> Envelope {
        seal_envelope(recipient, sender, body.as_bytes(), T0).expect("seal")
    }

    // ── Addressing ──────────────────────────────────────────────────────────

    #[test]
    fn tags_rotate_daily_and_do_not_correlate() {
        let recipient = keys().public_key();
        let today = recipient_tag(&recipient, epoch_day(T0));
        let tomorrow = recipient_tag(&recipient, epoch_day(T0 + DAY));
        assert_ne!(
            today, tomorrow,
            "a courier must not be able to link a peer's mail across days"
        );
        assert_eq!(
            today,
            recipient_tag(&recipient, epoch_day(T0 + 60)),
            "the tag is stable within a day"
        );
    }

    #[test]
    fn tags_differ_per_recipient() {
        let day = epoch_day(T0);
        assert_ne!(
            recipient_tag(&keys().public_key(), day),
            recipient_tag(&keys().public_key(), day)
        );
    }

    #[test]
    fn candidate_tags_cover_the_midnight_boundary() {
        let recipient = keys().public_key();
        // Sealed just before midnight, checked just after.
        let sealed_tag = recipient_tag(&recipient, epoch_day(T0 + DAY - 5));
        let after_midnight = candidate_tags(&recipient, T0 + DAY + 5);
        assert!(
            after_midnight.contains(&sealed_tag),
            "mail sealed before midnight must still be recognised after it"
        );
        assert_eq!(after_midnight.len(), 3);
    }

    #[test]
    fn a_courier_cannot_recognise_a_tag_without_the_recipient_key() {
        // The tag is keyed by the recipient's public key; a courier that has
        // never seen that key holds a value with no computable relationship to
        // any peer it knows.
        let recipient = keys().public_key();
        let stranger = keys().public_key();
        let day = epoch_day(T0);
        assert_ne!(
            recipient_tag(&recipient, day),
            recipient_tag(&stranger, day)
        );
    }

    // ── Sealing ─────────────────────────────────────────────────────────────

    #[test]
    fn seal_open_roundtrip_authenticates_the_sender() {
        let sender = keys();
        let recipient = keys();
        let sealed = seal(&recipient.public_key(), &sender, b"are you ok?").expect("seal");
        let opened = open(&recipient, &sealed).expect("open");
        assert_eq!(opened.payload, b"are you ok?");
        assert_eq!(opened.sender, sender.public_key());
    }

    #[test]
    fn a_third_party_cannot_open_the_envelope() {
        let sealed = seal(&keys().public_key(), &keys(), b"private").expect("seal");
        let courier = keys();
        assert!(
            matches!(open(&courier, &sealed), Err(DakError::Crypto(_))),
            "a courier carries ciphertext it cannot read"
        );
    }

    #[test]
    fn the_wire_never_carries_the_sender_identity() {
        let sender = keys();
        let recipient = keys();
        let sealed = seal(&recipient.public_key(), &sender, b"hello").expect("seal");
        let sender_bytes = sender.public_key().to_bytes();
        assert!(
            !sealed.windows(XONLY_LEN).any(|w| w == sender_bytes),
            "the sender key must only exist inside the ciphertext"
        );
    }

    #[test]
    fn sealed_length_reveals_only_a_bucket() {
        let sender = keys();
        let recipient = keys();
        let short = seal(&recipient.public_key(), &sender, b"ok").unwrap();
        let longer = seal(&recipient.public_key(), &sender, &[b'x'; 200]).unwrap();
        assert_eq!(
            short.len(),
            longer.len(),
            "a 2-byte and a 200-byte message must look identical on the wire"
        );
    }

    #[test]
    fn tampering_with_the_ciphertext_is_rejected() {
        let recipient = keys();
        let mut sealed = seal(&recipient.public_key(), &keys(), b"hello").expect("seal");
        let last = sealed.len() - 1;
        sealed[last] ^= 0xFF;
        assert!(matches!(
            open(&recipient, &sealed),
            Err(DakError::Crypto(_))
        ));
    }

    #[test]
    fn a_forged_sender_claim_fails_authentication() {
        // Mallory seals mail but names Alice as the sender inside the
        // plaintext. She cannot compute the MAC keyed by Alice↔Bob's shared
        // secret, so Bob rejects it.
        let alice = keys();
        let bob = keys();
        let mallory = keys();

        let payload = b"trust me, this is alice";
        let ephemeral = Keys::generate();
        let ephemeral_xonly = ephemeral.public_key().to_bytes();
        let seal_secret =
            compute_dh_shared_secret(ephemeral.secret_key(), &bob.public_key()).unwrap();
        let seal_key = derive_symmetric_key(&seal_secret, SEAL_LABEL);

        // Mallory can only build the MAC from her own shared secret with Bob.
        let mallory_secret =
            compute_dh_shared_secret(mallory.secret_key(), &bob.public_key()).unwrap();
        let mallory_auth = derive_symmetric_key(&mallory_secret, AUTH_LABEL);
        let mac = authenticate(&mallory_auth, &ephemeral_xonly, payload);

        let mut plaintext = Vec::new();
        plaintext.extend_from_slice(&alice.public_key().to_bytes()); // the lie
        plaintext.extend_from_slice(&mac);
        plaintext.extend_from_slice(&pad::pad(payload));

        let mut sealed = vec![WIRE_VERSION];
        sealed.extend_from_slice(&ephemeral_xonly);
        sealed.extend_from_slice(&aes256gcm_seal(&seal_key, &plaintext).unwrap());

        assert!(matches!(open(&bob, &sealed), Err(DakError::AuthFailed)));
    }

    #[test]
    fn open_rejects_malformed_input() {
        let recipient = keys();
        assert!(matches!(open(&recipient, &[]), Err(DakError::Malformed(_))));
        assert!(matches!(
            open(&recipient, &[0u8; 40]),
            Err(DakError::Malformed(_))
        ));
        let mut wrong_version = seal(&recipient.public_key(), &keys(), b"x").unwrap();
        wrong_version[0] = 9;
        assert!(matches!(
            open(&recipient, &wrong_version),
            Err(DakError::Malformed(_))
        ));
    }

    // ── Wire format ─────────────────────────────────────────────────────────

    #[test]
    fn envelope_encode_decode_roundtrip() {
        let envelope = envelope_for(&keys().public_key(), &keys(), "hi");
        let decoded = Envelope::decode(&envelope.encode()).expect("decode");
        assert_eq!(decoded, envelope);
    }

    #[test]
    fn envelope_decode_rejects_garbage() {
        assert!(matches!(
            Envelope::decode(&[1, 2, 3]),
            Err(DakError::Malformed(_))
        ));
        let mut wrong_version = envelope_for(&keys().public_key(), &keys(), "hi").encode();
        wrong_version[0] = 7;
        assert!(matches!(
            Envelope::decode(&wrong_version),
            Err(DakError::Malformed(_))
        ));
    }

    #[test]
    fn envelope_roundtrips_as_json_for_mesh_frames() {
        let envelope = envelope_for(&keys().public_key(), &keys(), "hi");
        let json = serde_json::to_string(&envelope).expect("serialise");
        assert!(json.contains(&hex::encode(envelope.recipient_tag)));
        assert_eq!(
            serde_json::from_str::<Envelope>(&json).expect("parse"),
            envelope
        );
    }

    // ── Courier bag policy ──────────────────────────────────────────────────

    #[test]
    fn deposit_and_handover_delivers_then_forgets() {
        let recipient = keys();
        let sender = keys();
        let mut store = CourierStore::new();
        let envelope = envelope_for(&recipient.public_key(), &sender, "thinking of you");

        store
            .deposit(envelope, "npub-sender", DepositTier::Trusted, T0 * 1_000)
            .expect("deposit");
        assert_eq!(store.carried_count(), 1);

        let handed = store.take_for(&candidate_tags(&recipient.public_key(), T0), T0 * 1_000);
        assert_eq!(handed.len(), 1);
        assert!(store.is_empty(), "handed-over mail is not carried on");

        let opened = open(&recipient, &handed[0].ciphertext).expect("open");
        assert_eq!(opened.payload, b"thinking of you");
        assert_eq!(opened.sender, sender.public_key());
    }

    #[test]
    fn mail_for_someone_else_is_not_handed_over() {
        let mut store = CourierStore::new();
        store
            .deposit(
                envelope_for(&keys().public_key(), &keys(), "x"),
                "npub-sender",
                DepositTier::Trusted,
                T0 * 1_000,
            )
            .expect("deposit");
        let stranger = keys().public_key();
        assert!(store
            .take_for(&candidate_tags(&stranger, T0), T0 * 1_000)
            .is_empty());
        assert_eq!(store.carried_count(), 1);
    }

    #[test]
    fn per_depositor_quota_is_enforced_by_tier() {
        let mut store = CourierStore::new();
        for i in 0..MAX_PER_VERIFIED_DEPOSITOR {
            store
                .deposit(
                    envelope_for(&keys().public_key(), &keys(), &format!("v{i}")),
                    "npub-stranger",
                    DepositTier::Verified,
                    T0 * 1_000,
                )
                .expect("within quota");
        }
        assert!(matches!(
            store.deposit(
                envelope_for(&keys().public_key(), &keys(), "one too many"),
                "npub-stranger",
                DepositTier::Verified,
                T0 * 1_000,
            ),
            Err(DakError::DepositorQuotaFull)
        ));

        // A trusted depositor still has its own, larger allowance.
        for i in 0..MAX_PER_TRUSTED_DEPOSITOR {
            store
                .deposit(
                    envelope_for(&keys().public_key(), &keys(), &format!("t{i}")),
                    "npub-friend",
                    DepositTier::Trusted,
                    T0 * 1_000,
                )
                .expect("trusted quota is larger");
        }
    }

    #[test]
    fn verified_mail_cannot_crowd_out_trusted_mail() {
        let mut store = CourierStore::new();
        // Fill the verified sub-pool from distinct depositors.
        for i in 0..MAX_VERIFIED_ENVELOPES {
            store
                .deposit(
                    envelope_for(&keys().public_key(), &keys(), &format!("v{i}")),
                    &format!("npub-stranger-{}", i / MAX_PER_VERIFIED_DEPOSITOR),
                    DepositTier::Verified,
                    T0 * 1_000,
                )
                .expect("within the verified pool");
        }
        assert!(
            matches!(
                store.deposit(
                    envelope_for(&keys().public_key(), &keys(), "over"),
                    "npub-stranger-new",
                    DepositTier::Verified,
                    T0 * 1_000,
                ),
                Err(DakError::BagFull)
            ),
            "the verified sub-pool is capped well below the bag"
        );
        // Trusted mail still fits — that is the point of the sub-pool.
        store
            .deposit(
                envelope_for(&keys().public_key(), &keys(), "loved one"),
                "npub-friend",
                DepositTier::Trusted,
                T0 * 1_000,
            )
            .expect("trusted deposits are never blocked by stranger mail");
    }

    #[test]
    fn a_full_bag_of_trusted_mail_rejects_rather_than_evicting() {
        let mut store = CourierStore::new();
        for i in 0..MAX_ENVELOPES {
            store
                .deposit(
                    envelope_for(&keys().public_key(), &keys(), &format!("t{i}")),
                    &format!("npub-friend-{}", i / MAX_PER_TRUSTED_DEPOSITOR),
                    DepositTier::Trusted,
                    T0 * 1_000,
                )
                .expect("bag fills to capacity");
        }
        assert_eq!(store.carried_count(), MAX_ENVELOPES);
        assert!(matches!(
            store.deposit(
                envelope_for(&keys().public_key(), &keys(), "extra"),
                "npub-friend-new",
                DepositTier::Trusted,
                T0 * 1_000,
            ),
            Err(DakError::BagFull)
        ));
    }

    #[test]
    fn oversize_and_empty_ciphertext_are_refused() {
        let mut store = CourierStore::new();
        let tag = recipient_tag(&keys().public_key(), epoch_day(T0));
        assert!(matches!(
            store.deposit(
                Envelope::new(
                    tag,
                    (T0 + 60) * 1_000,
                    1,
                    vec![0u8; MAX_CIPHERTEXT_BYTES + 1]
                ),
                "npub-x",
                DepositTier::Trusted,
                T0 * 1_000,
            ),
            Err(DakError::Oversize { .. })
        ));
        assert!(matches!(
            store.deposit(
                Envelope::new(tag, (T0 + 60) * 1_000, 1, Vec::new()),
                "npub-x",
                DepositTier::Trusted,
                T0 * 1_000,
            ),
            Err(DakError::Malformed(_))
        ));
    }

    #[test]
    fn an_over_long_lifetime_is_refused() {
        let mut store = CourierStore::new();
        let recipient = keys().public_key();
        let mut envelope = envelope_for(&recipient, &keys(), "x");
        envelope.expiry_ms = (T0 + 30 * DAY) * 1_000;
        assert!(matches!(
            store.deposit(envelope, "npub-x", DepositTier::Trusted, T0 * 1_000),
            Err(DakError::LifetimeTooLong(_))
        ));
    }

    #[test]
    fn expired_mail_is_refused_and_pruned() {
        let mut store = CourierStore::new();
        let recipient = keys().public_key();
        let envelope = envelope_for(&recipient, &keys(), "x");
        let expiry_ms = envelope.expiry_ms;

        assert!(matches!(
            store.deposit(
                envelope.clone(),
                "npub-x",
                DepositTier::Trusted,
                expiry_ms + 1
            ),
            Err(DakError::Expired)
        ));

        store
            .deposit(envelope, "npub-x", DepositTier::Trusted, T0 * 1_000)
            .expect("fresh deposit");
        assert_eq!(store.prune(expiry_ms), 1);
        assert!(store.is_empty());
    }

    // ── Spray and wait ──────────────────────────────────────────────────────

    #[test]
    fn spray_splits_the_budget_in_half() {
        let mut store = CourierStore::new();
        store
            .deposit(
                envelope_for(&keys().public_key(), &keys(), "x"),
                "npub-sender",
                DepositTier::Trusted,
                T0 * 1_000,
            )
            .expect("deposit");

        let handed = store.spray_to("npub-courier-2", T0 * 1_000);
        assert_eq!(handed.len(), 1);
        assert_eq!(handed[0].copies, INITIAL_COPIES / 2, "half is handed over");
        assert_eq!(
            store.snapshot().carried[0].envelope.copies,
            INITIAL_COPIES - INITIAL_COPIES / 2,
            "the rest stays with us"
        );
    }

    #[test]
    fn the_same_courier_is_never_sprayed_twice() {
        let mut store = CourierStore::new();
        store
            .deposit(
                envelope_for(&keys().public_key(), &keys(), "x"),
                "npub-sender",
                DepositTier::Trusted,
                T0 * 1_000,
            )
            .expect("deposit");
        assert_eq!(store.spray_to("npub-courier-2", T0 * 1_000).len(), 1);
        assert!(
            store.spray_to("npub-courier-2", T0 * 1_000).is_empty(),
            "a repeat encounter must not drain the budget"
        );
    }

    #[test]
    fn a_carry_only_envelope_is_never_sprayed() {
        let mut store = CourierStore::new();
        let recipient = keys().public_key();
        let mut envelope = envelope_for(&recipient, &keys(), "x");
        envelope.copies = 1;
        store
            .deposit(envelope, "npub-sender", DepositTier::Trusted, T0 * 1_000)
            .expect("deposit");
        assert!(store.spray_to("npub-courier-2", T0 * 1_000).is_empty());
    }

    #[test]
    fn replaying_a_deposit_cannot_replenish_spent_copies() {
        let mut store = CourierStore::new();
        let envelope = envelope_for(&keys().public_key(), &keys(), "x");
        store
            .deposit(
                envelope.clone(),
                "npub-sender",
                DepositTier::Trusted,
                T0 * 1_000,
            )
            .expect("deposit");
        store.spray_to("npub-courier-2", T0 * 1_000);
        let after_spray = store.snapshot().carried[0].envelope.copies;

        // The depositor replays the original packet at full budget.
        store
            .deposit(envelope, "npub-sender", DepositTier::Trusted, T0 * 1_000)
            .expect("replay is accepted as a duplicate");
        assert_eq!(
            store.snapshot().carried[0].envelope.copies,
            after_spray,
            "a replay must not turn the courier network into an amplifier"
        );
        assert_eq!(
            store.carried_count(),
            1,
            "a replay is not a second envelope"
        );
    }

    #[test]
    fn a_carry_only_copy_arriving_first_keeps_the_larger_budget() {
        let mut store = CourierStore::new();
        let full = envelope_for(&keys().public_key(), &keys(), "x");
        let carry_only = full.with_copies(1);

        store
            .deposit(carry_only, "npub-a", DepositTier::Trusted, T0 * 1_000)
            .expect("deposit");
        store
            .deposit(full, "npub-b", DepositTier::Trusted, T0 * 1_000)
            .expect("same envelope by another route");
        assert_eq!(
            store.snapshot().carried[0].envelope.copies,
            INITIAL_COPIES,
            "before any spray, the higher budget wins"
        );
    }

    #[test]
    fn copy_budget_is_capped_against_a_malicious_depositor() {
        let envelope = Envelope::new([0u8; TAG_LEN], (T0 + 60) * 1_000, 250, vec![1, 2, 3]);
        assert_eq!(envelope.copies, MAX_COPIES);
    }

    // ── Remote handover ─────────────────────────────────────────────────────

    #[test]
    fn remote_handover_keeps_the_original_and_throttles_repeats() {
        let mut store = CourierStore::new();
        let recipient = keys().public_key();
        store
            .deposit(
                envelope_for(&recipient, &keys(), "x"),
                "npub-sender",
                DepositTier::Trusted,
                T0 * 1_000,
            )
            .expect("deposit");

        let tags = candidate_tags(&recipient, T0);
        assert_eq!(store.remote_handover(&tags, T0 * 1_000).len(), 1);
        assert_eq!(
            store.carried_count(),
            1,
            "a speculative push keeps the original"
        );
        assert!(
            store.remote_handover(&tags, T0 * 1_000 + 1_000).is_empty(),
            "throttled inside the cooldown"
        );
        assert_eq!(
            store
                .remote_handover(&tags, T0 * 1_000 + REMOTE_HANDOVER_COOLDOWN_SECS * 1_000)
                .len(),
            1,
            "allowed again after the cooldown"
        );
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    #[test]
    fn snapshot_roundtrips_and_preserves_spray_history() {
        let mut store = CourierStore::new();
        store
            .deposit(
                envelope_for(&keys().public_key(), &keys(), "x"),
                "npub-sender",
                DepositTier::Trusted,
                T0 * 1_000,
            )
            .expect("deposit");
        store.spray_to("npub-courier-2", T0 * 1_000);

        let json = serde_json::to_string(&store.snapshot()).expect("serialise");
        let restored: CourierSnapshot = serde_json::from_str(&json).expect("parse");
        let mut after = CourierStore::from_snapshot(restored);

        assert_eq!(after.carried_count(), 1);
        assert!(
            after.spray_to("npub-courier-2", T0 * 1_000).is_empty(),
            "spray history must survive a restart"
        );
        assert_eq!(after.tier_counts().get("trusted").copied(), Some(1));
    }

    #[test]
    fn clear_drops_all_carried_mail() {
        let mut store = CourierStore::new();
        store
            .deposit(
                envelope_for(&keys().public_key(), &keys(), "x"),
                "npub-sender",
                DepositTier::Trusted,
                T0 * 1_000,
            )
            .expect("deposit");
        store.clear();
        assert!(store.is_empty(), "panic wipe must reach carried mail");
    }
}
