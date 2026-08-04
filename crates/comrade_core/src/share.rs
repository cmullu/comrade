/*!
 * Sharing the file itself — receiver-driven, chunked, resumable, playable early.
 *
 * `together` assumes both people already have what they are listening to. That
 * is often false, and when it is, the honest fix is for the one who has it to
 * send it: two people already in an end-to-end conversation passing a file to
 * each other is what a messaging app does, and this one already ships encrypted
 * attachments.
 *
 * What it is deliberately **not** is a server. beatsync — the closest prior art
 * — has every client upload to a room on its own backend, which then serves
 * everyone. Comrade has no backend and should not grow one for this: a server
 * holding and redistributing copies is both an architectural reversal and the
 * most exposed possible version of the copyright question. Here the bytes go
 * from one device to the other and touch nothing else. `AUDIT.md` §8.2 rules out
 * *proxying* media between users; it does not rule out one person handing
 * something to one other person, which is the existing attachment path in a
 * different shape.
 *
 * ## The transport is WebRTC, and this module does not know that
 * None of the three transports already in the app can carry bulk: relay DMs are
 * gift-wrapped control traffic, the media pipeline caps at 10 MiB *and* uploads
 * to a third-party host (exactly the intermediary this must avoid), and the
 * Saathi mesh is gossipsub — a 16 KiB frame broadcast to every peer on the
 * network, which is both too small and far too public.
 *
 * So bulk rides a **WebRTC data channel**, which is already peer-to-peer,
 * already encrypted, already solves NAT traversal, and is already a dependency
 * on both frontends. A second bulk protocol of our own over libp2p would
 * duplicate all of that and still not reach anyone outside the local network.
 *
 * The framing, the integrity and the "can we start playing yet" arithmetic stay
 * here and stay transport-agnostic; which paths may carry a transfer, and how
 * fast to push into one, are [`transport`]'s business. Neither module knows the
 * other's policy, which is what lets the relay rules change without touching
 * the code that moves bytes.
 *
 * ## Receiver-driven, and why
 * The receiver asks for ranges rather than the sender pushing them. That single
 * choice buys three things at once: **resume** costs nothing (ask again for what
 * is missing), **seek** costs nothing (ask for the chunks under the new
 * playhead first), and the sender holds no per-receiver state, so a dropped
 * connection leaves nothing to clean up.
 */

use serde::{Deserialize, Serialize};

pub mod transport;

/// How much file rides in one chunk.
///
/// Big enough that per-chunk overhead is noise, small enough that a chunk lost
/// to a dropped connection is cheap to ask for again — and, decisively, small
/// enough to be one WebRTC data-channel message everywhere. 64 KiB sits right
/// at the practical ceiling for a reliable channel and is refused outright by
/// some older implementations; 16 KiB is the size every stack accepts without
/// fragmenting, and the throughput difference is noise next to the flow-control
/// window in [`transport`].
pub const SHARE_CHUNK_BYTES: u32 = 16 * 1024;

/// How much contiguous audio from the playhead is enough to start on.
///
/// Playing the instant the first chunk lands means stuttering a second later;
/// waiting for the whole file means waiting minutes for a film. A few seconds of
/// runway is the same trade every progressive player makes.
pub const SHARE_PLAYABLE_RUNWAY_MS: u64 = 5_000;

/// What one side has and is willing to send.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ShareOffer {
    pub total_bytes: u64,
    pub chunk_bytes: u32,
    /// Hex SHA-256 of the whole file.
    ///
    /// Note this is *not* the identity question `together` refuses to answer
    /// with a hash — nobody is being asked "do you have these exact bytes". It
    /// is here only so the receiver can prove that what arrived is what was
    /// sent, which is a different question and needs a different answer.
    pub sha256: String,
    /// Milliseconds of playback, so the receiver can turn bytes into runway.
    pub duration_ms: u64,
}

impl ShareOffer {
    /// How many chunks the file is. Zero-length files have none.
    pub fn chunk_count(&self) -> u32 {
        if self.total_bytes == 0 || self.chunk_bytes == 0 {
            return 0;
        }
        self.total_bytes.div_ceil(self.chunk_bytes as u64) as u32
    }

    /// The byte range chunk `index` covers, or `None` if it is past the end.
    /// The last chunk is short — deliberately, rather than padded, so the hash
    /// is of the file and not of the framing.
    pub fn range(&self, index: u32) -> Option<(u64, u32)> {
        if index >= self.chunk_count() {
            return None;
        }
        let start = index as u64 * self.chunk_bytes as u64;
        let len = (self.total_bytes - start).min(self.chunk_bytes as u64) as u32;
        Some((start, len))
    }

    /// Roughly how many milliseconds of playback one chunk is worth.
    ///
    /// Assumes a constant rate, which is wrong for a variable-bitrate file and
    /// close enough for deciding when to start: being a second out on the
    /// runway estimate costs a second of buffering, not correctness.
    pub fn chunk_ms(&self) -> u64 {
        let chunks = self.chunk_count() as u64;
        if chunks == 0 {
            0
        } else {
            self.duration_ms / chunks
        }
    }
}

/// "Send me these." The receiver drives — see the module comment.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct ShareRequest {
    pub from: u32,
    pub count: u32,
}

/// Which chunks have arrived, and what that means for playback.
///
/// A plain bitmap rather than a set of ranges: a file worth sharing is at most a
/// few tens of thousands of chunks, so this is kilobytes, and every question
/// asked of it — is this one here, where is the first gap, how much runway is
/// there — is a straight scan rather than an interval merge.
#[derive(Debug, Clone)]
pub struct ShareTracker {
    offer: ShareOffer,
    have: Vec<bool>,
    received: u32,
}

impl ShareTracker {
    pub fn new(offer: ShareOffer) -> Self {
        let count = offer.chunk_count() as usize;
        Self {
            offer,
            have: vec![false; count],
            received: 0,
        }
    }

    pub fn offer(&self) -> &ShareOffer {
        &self.offer
    }

    /// Record a chunk. Returns whether it was new — a duplicate is not an error,
    /// because a receiver that re-asked after a timeout may well get both.
    pub fn accept(&mut self, index: u32) -> bool {
        let Some(slot) = self.have.get_mut(index as usize) else {
            return false;
        };
        if *slot {
            return false;
        }
        *slot = true;
        self.received += 1;
        true
    }

    pub fn has(&self, index: u32) -> bool {
        self.have.get(index as usize).copied().unwrap_or(false)
    }

    pub fn is_complete(&self) -> bool {
        self.received as usize == self.have.len()
    }

    /// 0.0 to 1.0. An empty file is complete, not divided by zero.
    pub fn fraction(&self) -> f64 {
        if self.have.is_empty() {
            1.0
        } else {
            self.received as f64 / self.have.len() as f64
        }
    }

    /// The chunk containing `pos_ms`.
    pub fn chunk_at_ms(&self, pos_ms: u64) -> u32 {
        let chunk_ms = self.offer.chunk_ms();
        if chunk_ms == 0 {
            return 0;
        }
        ((pos_ms / chunk_ms) as u32).min(self.offer.chunk_count().saturating_sub(1))
    }

    /// How many milliseconds of *uninterrupted* playback are available from
    /// `pos_ms`. A gap ends the runway however much lies beyond it — audio after
    /// a hole is not playable, it is a stutter waiting to happen.
    pub fn runway_ms(&self, pos_ms: u64) -> u64 {
        let start = self.chunk_at_ms(pos_ms);
        let mut contiguous = 0u64;
        for i in start..self.offer.chunk_count() {
            if !self.has(i) {
                break;
            }
            contiguous += 1;
        }
        contiguous * self.offer.chunk_ms()
    }

    /// Whether playback may start (or resume) at `pos_ms`.
    ///
    /// Either there is enough runway, or the rest of the file is here and the
    /// runway is simply all that remains — a track with two seconds left is
    /// playable even though two seconds is under the threshold.
    pub fn playable_at(&self, pos_ms: u64) -> bool {
        if self.is_complete() {
            return true;
        }
        let start = self.chunk_at_ms(pos_ms);
        let tail_is_all_here = (start..self.offer.chunk_count()).all(|i| self.has(i));
        tail_is_all_here || self.runway_ms(pos_ms) >= SHARE_PLAYABLE_RUNWAY_MS
    }

    /// What to ask for next, given where the listener is.
    ///
    /// Requests are anchored at the **playhead**, not at the start of the file,
    /// which is what makes a seek into un-fetched territory cost one request
    /// rather than a re-download. Once everything from the playhead on has
    /// arrived it falls back to the earliest gap, so a session that seeked
    /// forward still ends up with a whole file rather than a file with a hole in
    /// the middle.
    pub fn next_request(&self, pos_ms: u64, count: u32) -> Option<ShareRequest> {
        if self.is_complete() || count == 0 {
            return None;
        }
        let from_playhead = self.first_gap_at_or_after(self.chunk_at_ms(pos_ms));
        let from = from_playhead.or_else(|| self.first_gap_at_or_after(0))?;
        let mut span = 0;
        for i in from..self.offer.chunk_count() {
            if self.has(i) || span == count {
                break;
            }
            span += 1;
        }
        Some(ShareRequest { from, count: span })
    }

    fn first_gap_at_or_after(&self, start: u32) -> Option<u32> {
        (start..self.offer.chunk_count()).find(|i| !self.has(*i))
    }
}

/// Whether an assembled file is the one that was offered.
///
/// Case-insensitive and constant-length-checked, because the hash arrives from
/// the peer and a comparison that short-circuits on the first byte would be a
/// (very mild) oracle. Mostly it is here so the answer is computed in one place.
pub fn verify_sha256(expected_hex: &str, actual_hex: &str) -> bool {
    expected_hex.len() == actual_hex.len()
        && expected_hex
            .bytes()
            .zip(actual_hex.bytes())
            .fold(0u8, |acc, (a, b)| {
                acc | (a.to_ascii_lowercase() ^ b.to_ascii_lowercase())
            })
            == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    fn offer(total: u64, chunk: u32, duration_ms: u64) -> ShareOffer {
        ShareOffer {
            total_bytes: total,
            chunk_bytes: chunk,
            sha256: "a".repeat(64),
            duration_ms,
        }
    }

    #[test]
    fn a_file_divides_into_chunks_with_a_short_last_one() {
        let o = offer(250, 100, 0);
        assert_eq!(o.chunk_count(), 3);
        assert_eq!(o.range(0), Some((0, 100)));
        assert_eq!(o.range(2), Some((200, 50)), "the tail is short, not padded");
        assert_eq!(o.range(3), None);
    }

    #[test]
    fn an_empty_offer_has_nothing_to_send_and_is_already_complete() {
        let o = offer(0, SHARE_CHUNK_BYTES, 0);
        assert_eq!(o.chunk_count(), 0);
        assert_eq!(o.range(0), None);
        let t = ShareTracker::new(o);
        assert!(t.is_complete());
        assert_eq!(t.fraction(), 1.0);
        assert_eq!(t.next_request(0, 4), None);
    }

    #[test]
    fn a_duplicate_chunk_is_not_an_error_and_is_not_counted_twice() {
        let mut t = ShareTracker::new(offer(300, 100, 0));
        assert!(t.accept(1));
        assert!(!t.accept(1), "a re-ask can legitimately deliver both");
        assert!(
            !t.accept(99),
            "a chunk past the end is refused, not panicked on"
        );
        assert!((t.fraction() - 1.0 / 3.0).abs() < 1e-9);
    }

    // ── Playable early ──────────────────────────────────────────────────────

    /// Ten chunks, one second each.
    fn ten_seconds() -> ShareTracker {
        ShareTracker::new(offer(1000, 100, 10_000))
    }

    #[test]
    fn playback_waits_for_a_runway_rather_than_starting_on_the_first_chunk() {
        let mut t = ten_seconds();
        t.accept(0);
        t.accept(1);
        assert!(
            !t.playable_at(0),
            "two seconds would stutter a moment later"
        );
        for i in 2..5 {
            t.accept(i);
        }
        assert_eq!(t.runway_ms(0), SHARE_PLAYABLE_RUNWAY_MS);
        assert!(t.playable_at(0));
    }

    #[test]
    fn a_gap_ends_the_runway_however_much_lies_beyond_it() {
        let mut t = ten_seconds();
        for i in [0, 1, 3, 4, 5, 6, 7, 8, 9] {
            t.accept(i);
        }
        assert_eq!(
            t.runway_ms(0),
            2_000,
            "audio after a hole is a stutter, not runway"
        );
        assert!(!t.playable_at(0));
        // From past the gap there is plenty.
        assert!(t.playable_at(3_000));
    }

    #[test]
    fn the_last_few_seconds_of_a_file_are_playable_even_though_they_are_short() {
        let mut t = ten_seconds();
        t.accept(8);
        t.accept(9);
        assert!(t.runway_ms(8_000) < SHARE_PLAYABLE_RUNWAY_MS);
        assert!(
            t.playable_at(8_000),
            "there is nothing more coming; waiting for a runway would wait forever"
        );
    }

    // ── Receiver-driven fetching ────────────────────────────────────────────

    #[test]
    fn requests_start_at_the_playhead_not_at_the_start_of_the_file() {
        let t = ten_seconds();
        assert_eq!(
            t.next_request(6_000, 3),
            Some(ShareRequest { from: 6, count: 3 }),
            "a seek must cost one request, not a re-download"
        );
    }

    #[test]
    fn a_request_stops_at_the_first_chunk_already_held() {
        let mut t = ten_seconds();
        t.accept(2);
        assert_eq!(
            t.next_request(0, 8),
            Some(ShareRequest { from: 0, count: 2 }),
            "asking across something we already have would waste the transfer"
        );
    }

    /// Having seeked forward and filled the tail, the transfer should go back
    /// and close the hole rather than declaring itself finished.
    #[test]
    fn once_the_tail_is_here_it_falls_back_to_the_earliest_gap() {
        let mut t = ten_seconds();
        for i in 5..10 {
            t.accept(i);
        }
        assert_eq!(
            t.next_request(5_000, 4),
            Some(ShareRequest { from: 0, count: 4 }),
            "a session that seeked must still end up with a whole file"
        );
    }

    #[test]
    fn a_complete_transfer_asks_for_nothing_more() {
        let mut t = ten_seconds();
        for i in 0..10 {
            t.accept(i);
        }
        assert!(t.is_complete());
        assert_eq!(t.fraction(), 1.0);
        assert_eq!(t.next_request(0, 4), None);
        assert!(t.playable_at(0));
    }

    #[test]
    fn resuming_after_a_drop_asks_only_for_what_is_missing() {
        let mut t = ten_seconds();
        for i in [0, 1, 2, 5, 6] {
            t.accept(i);
        }
        // The connection died; ask again from where the listener is.
        assert_eq!(
            t.next_request(0, 10),
            Some(ShareRequest { from: 3, count: 2 })
        );
    }

    // ── Integrity ───────────────────────────────────────────────────────────

    #[test]
    fn an_assembled_file_is_checked_against_what_was_offered() {
        let a = "abc123".repeat(10);
        assert!(verify_sha256(&a, &a));
        assert!(verify_sha256(&a.to_uppercase(), &a));
        assert!(!verify_sha256(&a, &"abc124".repeat(10)));
        assert!(!verify_sha256(&a, "short"));
    }
}
