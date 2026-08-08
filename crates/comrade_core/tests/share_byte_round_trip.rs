//! The gate this repo did not have: **the bytes that arrived are the bytes that
//! were sent** (`AUDIT.md` T5, `docs/FILESYNC_REVIEW.md` §2.4).
//!
//! Every decision layer around the transfer is covered — `ShareDecisionsTest`,
//! `ShareReadPolicyTest`, `share_transfer.test.mjs`, `share/transport.rs`'s own
//! tests — and none of them moves a byte. This does, over the half of the path
//! that is pure: framing, the tracker, and the whole-file hash. The other half
//! is a real WebRTC data channel, which a Rust test cannot have, and a fake one
//! would only be re-asserting this file's own assumptions in a costlier way.
//!
//! So the data channel is modelled as the only three things a receiver may
//! actually assume about it — messages arrive **out of order**, **more than
//! once**, and **occasionally are not chunks at all** — and the receiver drives
//! the tracker in exactly the order `FileTransfer.onChunk` does:
//! `chunk_frame_fits` → `tracker.has` → write at `offer.range(index)` →
//! `tracker.accept`. That ordering is load-bearing (a chunk recorded before its
//! bytes are on disk is a partial-file reader decoding zeroes), so a test that
//! reorders it is testing something else.
//!
//! Deterministic by construction: the payload comes out of a seeded splitmix64
//! written here rather than from `rand`, so a failure reproduces byte for byte
//! and does not depend on `StdRng`'s stream staying stable across versions.

use comrade_core::share::{
    chunk_frame_fits, frame_chunk, parse_chunk_frame, verify_sha256, ShareOffer, ShareTracker,
    CHUNK_FRAME_HEADER_BYTES, SHARE_CHUNK_BYTES,
};
use sha2::{Digest, Sha256};

/// Twenty-three whole chunks and a short tail — deliberately **not** a multiple
/// of [`SHARE_CHUNK_BYTES`], because a framing off-by-one only shows up on the
/// chunk whose length is not the chunk length.
const TOTAL_BYTES: usize = 23 * SHARE_CHUNK_BYTES as usize + 4_321;

/// Any fixed value; it is in a constant so a failing run can be reproduced by
/// reading this file rather than by guessing.
const SEED: u64 = 0x5341_4b48_4131_3233;

fn splitmix64(state: &mut u64) -> u64 {
    *state = state.wrapping_add(0x9E37_79B9_7F4A_7C15);
    let mut z = *state;
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    z ^ (z >> 31)
}

fn pseudo_random_file(len: usize, seed: u64) -> Vec<u8> {
    let mut state = seed;
    let mut out = Vec::with_capacity(len);
    while out.len() < len {
        out.extend_from_slice(&splitmix64(&mut state).to_le_bytes());
    }
    out.truncate(len);
    out
}

fn sha256_hex(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

/// What the receiver did with one data-channel message. Distinguished so the
/// test can tell "refused" from "already had it" — a guard that silently
/// counted a rejection as a duplicate would look identical from the outside.
#[derive(Debug, PartialEq, Eq)]
enum Delivery {
    Rejected,
    AlreadyHad,
    Written,
}

/// One data-channel message, handled the way `FileTransfer.onChunk` handles it.
fn on_chunk(tracker: &mut ShareTracker, assembled: &mut [u8], message: &[u8]) -> Delivery {
    let Some((index, payload)) = parse_chunk_frame(message) else {
        return Delivery::Rejected;
    };
    let offer = tracker.offer().clone();
    if !chunk_frame_fits(&offer, index, payload.len()) {
        return Delivery::Rejected;
    }
    if tracker.has(index) {
        return Delivery::AlreadyHad;
    }
    // `chunk_frame_fits` has already agreed this range exists and is this long,
    // which is what makes the slice below safe to take without a second check.
    let (start, len) = offer.range(index).expect("checked by chunk_frame_fits");
    let start = start as usize;
    assembled[start..start + len as usize].copy_from_slice(payload);
    // Bytes first, then the record — see the module comment.
    tracker.accept(index);
    Delivery::Written
}

/// The whole path, end to end: frame every chunk, shuffle it, duplicate some of
/// it, feed rubbish into the middle of it, and still land on the offered hash.
#[test]
fn a_shuffled_duplicated_transfer_reassembles_to_the_offered_hash() {
    let source = pseudo_random_file(TOTAL_BYTES, SEED);
    let offer = ShareOffer {
        total_bytes: source.len() as u64,
        chunk_bytes: SHARE_CHUNK_BYTES,
        sha256: sha256_hex(&source),
        duration_ms: 240_000,
    };
    let count = offer.chunk_count();
    assert_eq!(count, 24, "23 whole chunks and a short one");
    let last = count - 1;
    assert_eq!(
        offer.range(last),
        Some((23 * SHARE_CHUNK_BYTES as u64, 4_321)),
        "the tail is short, not padded — this is the case framing bugs live in"
    );

    // Sender side: one frame per chunk, straight off the source.
    let frames: Vec<Vec<u8>> = (0..count)
        .map(|i| {
            let (start, len) = offer.range(i).expect("in range");
            frame_chunk(i, &source[start as usize..start as usize + len as usize])
        })
        .collect();

    // The channel: everything except the short tail, shuffled, with every fifth
    // frame sent twice. The tail is held back so the guards below have a chunk
    // that has genuinely not arrived to try to corrupt.
    let mut order: Vec<u32> = (0..last).collect();
    let mut state = SEED ^ 0xF00D;
    for i in (1..order.len()).rev() {
        let j = (splitmix64(&mut state) % (i as u64 + 1)) as usize;
        order.swap(i, j);
    }
    assert_ne!(
        order,
        (0..last).collect::<Vec<_>>(),
        "an in-order 'shuffle' would not exercise anything"
    );
    let wire: Vec<u32> = order
        .iter()
        .enumerate()
        .flat_map(|(n, &i)| if n % 5 == 0 { vec![i, i] } else { vec![i] })
        .collect();

    let mut tracker = ShareTracker::new(offer.clone());
    let mut assembled = vec![0u8; source.len()];
    let mut written = 0;
    let mut duplicates = 0;
    for &index in &wire {
        match on_chunk(&mut tracker, &mut assembled, &frames[index as usize]) {
            Delivery::Written => written += 1,
            Delivery::AlreadyHad => duplicates += 1,
            Delivery::Rejected => panic!("chunk {index} was refused"),
        }
    }
    assert_eq!(written, last, "every chunk but the held-back tail");
    assert!(duplicates > 0, "the duplicate half of the test did nothing");
    assert!(!tracker.is_complete(), "the tail has not been sent yet");

    // ── The guards, aimed at the one chunk still missing ────────────────────
    //
    // Each of these would write bytes into the file if it got through, and the
    // hash at the end would catch it — after the whole transfer, which is much
    // too late to be useful. The point of checking them here is that they bite
    // *now*, and leave the assembly exactly as it was.
    let tail_start = 23 * SHARE_CHUNK_BYTES as usize;
    let before = assembled.clone();
    let fraction_before = tracker.fraction();
    let rubbish: Vec<Vec<u8>> = vec![
        // A full-length payload for the short last chunk: the off-by-one that
        // a padded tail would produce.
        frame_chunk(last, &vec![0xAA; SHARE_CHUNK_BYTES as usize]),
        // A short payload for a full chunk, which would shift everything after
        // it if the length were not checked.
        frame_chunk(0, &vec![0xBB; SHARE_CHUNK_BYTES as usize - 1]),
        // An index past the end of the file.
        frame_chunk(count, &vec![0xCC; SHARE_CHUNK_BYTES as usize]),
        // Not a chunk at all — a peer may put any bytes on this channel.
        vec![0u8; CHUNK_FRAME_HEADER_BYTES - 1],
        Vec::new(),
    ];
    for (n, message) in rubbish.iter().enumerate() {
        assert_eq!(
            on_chunk(&mut tracker, &mut assembled, message),
            Delivery::Rejected,
            "rubbish frame {n} was accepted"
        );
    }
    assert_eq!(assembled, before, "a refused frame still moved bytes");
    assert_eq!(tracker.fraction(), fraction_before);
    assert!(!tracker.has(last), "a refused frame still recorded a chunk");
    assert!(
        assembled[tail_start..].iter().all(|&b| b == 0),
        "the tail was written by something that was supposed to be refused"
    );

    // ── The short tail, for real ────────────────────────────────────────────
    assert_eq!(
        on_chunk(&mut tracker, &mut assembled, &frames[last as usize]),
        Delivery::Written
    );
    assert!(tracker.is_complete());
    assert_eq!(tracker.fraction(), 1.0);
    assert_eq!(tracker.next_request(0, 8), None, "nothing left to ask for");

    assert_eq!(assembled, source, "the assembled file is not the source");
    assert!(
        verify_sha256(&offer.sha256, &sha256_hex(&assembled)),
        "the hash of what arrived is not the hash of what was sent"
    );
}

/// The other half of a hash check: that it can say no. Without this the test
/// above passes with `verify_sha256` returning `true` unconditionally.
#[test]
fn one_flipped_byte_fails_the_hash_the_offer_carried() {
    let source = pseudo_random_file(TOTAL_BYTES, SEED);
    let expected = sha256_hex(&source);

    let mut corrupt = source.clone();
    // Inside the short tail, which is the region a framing bug reaches.
    let victim = 23 * SHARE_CHUNK_BYTES as usize + 7;
    corrupt[victim] ^= 0x01;
    assert!(!verify_sha256(&expected, &sha256_hex(&corrupt)));

    // And a truncation, which is what a lost last chunk looks like to a
    // receiver that never checked the tracker.
    corrupt.truncate(source.len() - 1);
    assert!(!verify_sha256(&expected, &sha256_hex(&corrupt)));

    assert!(verify_sha256(&expected, &sha256_hex(&source)));
}
