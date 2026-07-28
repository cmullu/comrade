/*!
 * Anonymous and scoped identities — posting without your permanent key.
 *
 * Adopted from bitchat's `NostrIdentityBridge`, which derives an unlinkable
 * per-geohash Nostr identity from a device seed
 * (`HMAC-SHA256(deviceSeed, label)` as private-key material, rehashed until the
 * candidate is a valid secp256k1 scalar). That is exactly the primitive
 * `AUDIT.md` §8 says the "anonymous thoughts" pillar needs and Comrade did not
 * have: today a public Chitthi is signed by the identity key, so it is
 * *pseudonymous*, not anonymous.
 *
 * Two derivations, for two different privacy shapes:
 *
 * | | key | linkable within a scope | linkable across scopes | linkable to you |
 * |---|---|---|---|---|
 * | [`derive_scoped`] | deterministic per label | **yes** (that is the point) | no | no |
 * | [`ephemeral`] | fresh random | no | no | no |
 *
 * Use [`derive_scoped`] where continuity is the feature — a location channel
 * where replies need to reach the same pseudonym, and where the identity must
 * survive a relaunch without being stored. Use [`ephemeral`] for a one-shot
 * anonymous Chitthi, where even two posts by the same person must not be
 * linkable to each other.
 *
 * **What this does and does not buy.** The signing key is unlinkable; the
 * *network* is not. Timing, relay choice, and IP address remain correlatable
 * unless traffic also goes out over a proxy, and a geohash channel discloses a
 * coarse area by construction. That honesty is copied from bitchat's own
 * privacy assessment rather than glossed over.
 */

use hmac::{Hmac, Mac};
use nostr_sdk::{Keys, SecretKey};
use sha2::{Digest, Sha256};
use zeroize::{Zeroize, ZeroizeOnDrop};

use crate::error::CryptoError;

type HmacSha256 = Hmac<Sha256>;

/// HMAC label prefix for a geohash location channel. Distinct prefixes keep two
/// different *kinds* of scope from colliding on the same string (bitchat uses a
/// `"bridge|"` prefix for exactly this reason).
pub const SCOPE_GEOHASH: &str = "geohash";
/// HMAC label prefix for an anonymous public-Chitthi persona.
pub const SCOPE_CHITTHI: &str = "chitthi";

/// How many candidate HMAC outputs to try before falling back to a hash of the
/// seed. A uniformly random 32-byte string is a valid secp256k1 scalar with
/// overwhelming probability, so the loop realistically never runs twice — it
/// exists so an unlucky derivation cannot fail the caller.
const MAX_CANDIDATES: u32 = 10;

/// A 32-byte device secret from which every scoped identity is derived.
///
/// Never leaves the device and is never published. It lives in the encrypted
/// store next to the identity key, and — like the identity — is destroyed by
/// the panic wipe: after that, previously derived personas cannot be
/// regenerated, which is the intended effect.
#[derive(Clone, Zeroize, ZeroizeOnDrop)]
pub struct DeviceSeed([u8; 32]);

impl DeviceSeed {
    /// Fresh seed from the OS CSPRNG.
    pub fn generate() -> Self {
        let mut bytes = [0u8; 32];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut bytes);
        Self(bytes)
    }

    /// Restore a seed persisted by [`Self::as_bytes`].
    ///
    /// # Errors
    /// [`CryptoError::InvalidKey`] if `bytes` is not exactly 32 bytes long — a
    /// short seed would silently weaken every persona derived from it.
    pub fn from_slice(bytes: &[u8]) -> Result<Self, CryptoError> {
        let array: [u8; 32] = bytes.try_into().map_err(|_| CryptoError::InvalidKey)?;
        Ok(Self(array))
    }

    /// Raw seed bytes, for sealing into the encrypted store.
    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }
}

impl std::fmt::Debug for DeviceSeed {
    /// Never render the seed — one leaked log line would deanonymise every
    /// persona this device has ever used.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("DeviceSeed(<redacted>)")
    }
}

/// Derive the deterministic keypair for one scope, e.g.
/// `derive_scoped(&seed, SCOPE_GEOHASH, "tdr1")`.
///
/// The same seed and scope always produce the same keys (so a persona survives
/// a relaunch with nothing stored per scope), and two different scopes produce
/// keys with no computable relationship — including no relationship to the
/// device's real identity key, which is generated independently.
///
/// # Errors
/// [`CryptoError::Derivation`] only if every candidate — and the final
/// fallback — fails to be a valid secp256k1 secret key, which is
/// cryptographically implausible.
pub fn derive_scoped(seed: &DeviceSeed, scope: &str, name: &str) -> Result<Keys, CryptoError> {
    let label = format!("{scope}|{name}");

    for iteration in 0..MAX_CANDIDATES {
        let candidate = hmac_candidate(seed, label.as_bytes(), iteration);
        if let Ok(secret) = SecretKey::from_slice(&candidate) {
            return Ok(Keys::new(secret));
        }
    }

    // Final fallback: hash seed || label and try once more, so the function is
    // total rather than "usually total".
    let mut hasher = Sha256::new();
    hasher.update(seed.as_bytes());
    hasher.update(label.as_bytes());
    let digest = hasher.finalize();
    let secret = SecretKey::from_slice(&digest)
        .map_err(|e| CryptoError::Derivation(format!("scoped identity: {e}")))?;
    Ok(Keys::new(secret))
}

/// A brand-new random keypair for a single anonymous post.
///
/// The caller must not persist it: keeping it would make the next post
/// linkable to this one, which is the property [`ephemeral`] exists to break.
pub fn ephemeral() -> Keys {
    Keys::generate()
}

fn hmac_candidate(seed: &DeviceSeed, label: &[u8], iteration: u32) -> [u8; 32] {
    let mut mac = <HmacSha256 as Mac>::new_from_slice(seed.as_bytes())
        .expect("HMAC-SHA256 accepts any key length");
    mac.update(label);
    mac.update(&iteration.to_be_bytes());
    let mut out = [0u8; 32];
    out.copy_from_slice(&mac.finalize().into_bytes());
    out
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::KeyProfile;

    #[test]
    fn scoped_derivation_is_deterministic() {
        let seed = DeviceSeed::generate();
        let a = derive_scoped(&seed, SCOPE_GEOHASH, "tdr1").unwrap();
        let b = derive_scoped(&seed, SCOPE_GEOHASH, "tdr1").unwrap();
        assert_eq!(
            a.public_key(),
            b.public_key(),
            "a persona must survive a relaunch without being stored"
        );
    }

    #[test]
    fn different_scopes_and_names_are_unlinkable() {
        let seed = DeviceSeed::generate();
        let geo = derive_scoped(&seed, SCOPE_GEOHASH, "tdr1").unwrap();
        let other_cell = derive_scoped(&seed, SCOPE_GEOHASH, "tdr2").unwrap();
        let chitthi = derive_scoped(&seed, SCOPE_CHITTHI, "tdr1").unwrap();

        assert_ne!(geo.public_key(), other_cell.public_key());
        assert_ne!(
            geo.public_key(),
            chitthi.public_key(),
            "the scope prefix must keep same-name labels apart"
        );
    }

    #[test]
    fn different_seeds_produce_different_personas() {
        let one = derive_scoped(&DeviceSeed::generate(), SCOPE_GEOHASH, "tdr1").unwrap();
        let two = derive_scoped(&DeviceSeed::generate(), SCOPE_GEOHASH, "tdr1").unwrap();
        assert_ne!(one.public_key(), two.public_key());
    }

    #[test]
    fn persona_is_never_the_identity_key() {
        let identity = KeyProfile::generate().unwrap();
        let seed = DeviceSeed::generate();
        let persona = derive_scoped(&seed, SCOPE_CHITTHI, "anon").unwrap();
        assert_ne!(
            identity.public_key(),
            persona.public_key(),
            "an anonymous post must not be signed by the identity key"
        );
    }

    #[test]
    fn ephemeral_keys_are_unlinkable_to_each_other() {
        let a = ephemeral();
        let b = ephemeral();
        assert_ne!(
            a.public_key(),
            b.public_key(),
            "two anonymous posts must not share a key"
        );
    }

    #[test]
    fn seed_roundtrips_through_persistence() {
        let seed = DeviceSeed::generate();
        let restored = DeviceSeed::from_slice(seed.as_bytes()).unwrap();
        assert_eq!(
            derive_scoped(&seed, SCOPE_GEOHASH, "x")
                .unwrap()
                .public_key(),
            derive_scoped(&restored, SCOPE_GEOHASH, "x")
                .unwrap()
                .public_key()
        );
    }

    #[test]
    fn seed_rejects_wrong_length() {
        assert!(matches!(
            DeviceSeed::from_slice(&[0u8; 16]),
            Err(CryptoError::InvalidKey)
        ));
    }

    #[test]
    fn seed_debug_never_prints_key_material() {
        let seed = DeviceSeed::from_slice(&[0xAB; 32]).unwrap();
        let rendered = format!("{seed:?}");
        assert_eq!(rendered, "DeviceSeed(<redacted>)");
        assert!(!rendered.contains("ab"));
    }
}
