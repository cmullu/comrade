/*!
 * Length padding — hide a payload's true size from anyone who can see the
 * ciphertext (relay operators, mesh neighbours, a passive radio listener).
 *
 * Adopted from bitchat's `MessagePadding` (bucket sizes 256/512/1024/2048),
 * with one deliberate change: bitchat's PKCS#7-style scheme stores the pad
 * length in a **single byte**, so any frame needing more than 255 bytes to
 * reach its bucket is emitted *unpadded* — the exact gap bitchat's own
 * whitepaper lists as future work ("closing the gap where a frame needing
 * more than 255 bytes of padding is emitted unpadded"). Comrade has no
 * deployed wire format to stay byte-compatible with, so the trailer here is
 * **two big-endian bytes**: every payload up to [`MAX_PADDED_LEN`] reaches its
 * bucket, and payloads above the largest bucket round up to the next
 * [`LARGE_STEP`] boundary instead of travelling at their natural length.
 *
 * Where this is used: the [`crate::dak`] store-and-forward envelopes and
 * Saathi mesh frames, i.e. everything Comrade seals itself. It is deliberately
 * **not** applied to Vault DMs — NIP-44 v2 already pads its plaintext to its
 * own bucket ladder before encrypting, and double padding would only add
 * bytes. `docs/BITCHAT_ADOPTION.md` records that reasoning.
 *
 * Padding always goes *inside* the AEAD, so a truncated or tampered buffer is
 * rejected by the tag before [`unpad`] ever sees it; the length checks here
 * guard against malformed input, not against an attacker.
 */

use crate::error::PaddingError;

/// Bucket ladder every small payload is rounded up to.
pub const BUCKETS: [usize; 4] = [256, 512, 1024, 2048];

/// Payloads larger than the biggest bucket round up to a multiple of this, so
/// a 3 KiB voice note and a 4 KiB one are indistinguishable on the wire while
/// the overhead stays bounded by `LARGE_STEP`.
pub const LARGE_STEP: usize = 2048;

/// Bytes of trailer [`pad`] appends: a big-endian `u16` pad length.
const TRAILER_LEN: usize = 2;

/// Largest pad the two-byte trailer can describe. The bucket ladder never
/// needs more than `LARGE_STEP` bytes of pad, so this only bounds an explicit
/// [`pad_to`] target.
pub const MAX_PAD_LEN: usize = u16::MAX as usize;

/// Smallest bucket that fits `len` bytes of payload *plus* the trailer.
pub fn bucket_for(len: usize) -> usize {
    let needed = len + TRAILER_LEN;
    for bucket in BUCKETS {
        if needed <= bucket {
            return bucket;
        }
    }
    // Round up to the next LARGE_STEP boundary (never below `needed`).
    needed.div_ceil(LARGE_STEP) * LARGE_STEP
}

/// Pad `data` up to its bucket. Layout: `data || zero filler || pad_len_be16`,
/// where `pad_len` counts the filler *and* the two trailer bytes.
pub fn pad(data: &[u8]) -> Vec<u8> {
    pad_to(data, bucket_for(data.len()))
}

/// Pad `data` to exactly `target` bytes. A `target` that leaves no room for
/// the trailer, is smaller than the payload, or would need a pad longer than
/// [`MAX_PAD_LEN`] yields the input unchanged — the caller loses length hiding
/// for that frame but never correctness.
pub fn pad_to(data: &[u8], target: usize) -> Vec<u8> {
    let Some(pad_len) = target.checked_sub(data.len()) else {
        return data.to_vec();
    };
    if !(TRAILER_LEN..=MAX_PAD_LEN).contains(&pad_len) {
        return data.to_vec();
    }
    let mut out = Vec::with_capacity(target);
    out.extend_from_slice(data);
    out.resize(target - TRAILER_LEN, 0);
    out.extend_from_slice(&(pad_len as u16).to_be_bytes());
    out
}

/// Strip padding written by [`pad`]/[`pad_to`].
///
/// # Errors
/// [`PaddingError::TooShort`] when the buffer cannot even hold the trailer,
/// and [`PaddingError::Invalid`] when the declared pad length does not fit the
/// buffer — both mean the input was never produced by [`pad`].
pub fn unpad(data: &[u8]) -> Result<&[u8], PaddingError> {
    if data.len() < TRAILER_LEN {
        return Err(PaddingError::TooShort);
    }
    let trailer = &data[data.len() - TRAILER_LEN..];
    let pad_len = u16::from_be_bytes([trailer[0], trailer[1]]) as usize;
    if pad_len < TRAILER_LEN || pad_len > data.len() {
        return Err(PaddingError::Invalid(pad_len));
    }
    Ok(&data[..data.len() - pad_len])
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_across_every_bucket() {
        for len in [
            0usize, 1, 100, 253, 254, 255, 256, 300, 510, 1000, 2040, 5000,
        ] {
            let data: Vec<u8> = (0..len).map(|i| (i % 251) as u8).collect();
            let padded = pad(&data);
            assert_eq!(
                unpad(&padded).expect("padded buffer must unpad"),
                &data[..],
                "roundtrip failed at len {len}"
            );
        }
    }

    /// The improvement over bitchat: a payload needing >255 bytes of padding
    /// still reaches its bucket instead of going out at its natural length.
    #[test]
    fn payload_needing_more_than_255_pad_bytes_still_reaches_its_bucket() {
        let data = vec![7u8; 300]; // 212 bytes short of 512, well over 255
        let padded = pad(&data);
        assert_eq!(padded.len(), 512);
        assert_eq!(unpad(&padded).unwrap(), &data[..]);
    }

    #[test]
    fn lengths_within_a_bucket_are_indistinguishable() {
        let sizes: Vec<usize> = [10usize, 100, 200, 253]
            .iter()
            .map(|n| pad(&vec![0u8; *n]).len())
            .collect();
        assert!(
            sizes.windows(2).all(|w| w[0] == w[1]),
            "payloads in the same bucket must pad to one size, got {sizes:?}"
        );
    }

    #[test]
    fn oversize_payloads_round_up_to_the_large_step() {
        let padded = pad(&vec![0u8; 5000]);
        assert_eq!(padded.len(), 6144, "5000 bytes rounds to 3 × 2048");
        assert_eq!(padded.len() % LARGE_STEP, 0);
    }

    #[test]
    fn bucket_leaves_room_for_the_trailer() {
        // 255 payload bytes + 2 trailer bytes still fits 256; 255 + 2 == 257
        // does not, so it must move up a bucket rather than silently truncate.
        assert_eq!(bucket_for(254), 256);
        assert_eq!(bucket_for(255), 512);
    }

    #[test]
    fn unpad_rejects_malformed_buffers() {
        assert!(matches!(unpad(&[]), Err(PaddingError::TooShort)));
        assert!(matches!(unpad(&[0x01]), Err(PaddingError::TooShort)));
        // Declared pad length longer than the buffer.
        assert!(matches!(
            unpad(&[0x00, 0xFF, 0xFF]),
            Err(PaddingError::Invalid(0xFFFF))
        ));
        // Declared pad length smaller than the trailer itself.
        assert!(matches!(
            unpad(&[0x00, 0x00, 0x01]),
            Err(PaddingError::Invalid(1))
        ));
    }

    #[test]
    fn unrepresentable_pad_length_returns_the_input_unchanged() {
        // A pad longer than the two-byte trailer can describe: refuse rather
        // than write a trailer that would decode to the wrong length.
        let data = vec![3u8; 8];
        assert_eq!(pad_to(&data, 8 + MAX_PAD_LEN + 1), data);
        // Target below the payload, and a target with no room for the trailer.
        assert_eq!(pad_to(&data, 4), data);
        assert_eq!(pad_to(&data, 9), data);
    }
}
