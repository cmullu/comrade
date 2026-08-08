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

/// How little runway a reader that is *already playing* may run down to before
/// it stops and waits.
///
/// Two numbers rather than one, deliberately. A reader that stopped and started
/// at the same threshold would sit on it and chatter — stop, one chunk arrives,
/// start, stop again — and a reader that only stopped at exactly zero would hit
/// the end of its bytes inside the decoder rather than on a decision, which on
/// Android is a `MediaDataSource.readAt` with nothing to return. So starting
/// costs [`SHARE_PLAYABLE_RUNWAY_MS`] and continuing costs this, and the gap
/// between them is the hysteresis.
///
/// A second's worth is about one [`SHARE_CHUNK_BYTES`] chunk of a typical music
/// bitrate (16 KiB at 128 kbps is 1.02 s), which is also the quantum
/// [`ShareTracker::runway_ms`] measures in — so this is "roughly one chunk of
/// slack", not a tuned constant, and it has to stay well under
/// [`SHARE_PLAYABLE_RUNWAY_MS`] or the hysteresis collapses back into one
/// threshold.
pub const SHARE_STALL_FLOOR_MS: u64 = 1_000;

// Checked here rather than in a test because editing one of the two numbers is
// exactly how the pair goes wrong, and a pair that has stopped being hysteresis
// should not build. A floor of zero would stall inside the decoder instead of on
// a decision; a floor at or near the start threshold is one threshold wearing two
// names, and the reader chatters across it.
const _: () = assert!(SHARE_STALL_FLOOR_MS > 0);
const _: () = assert!(SHARE_STALL_FLOOR_MS * 2 < SHARE_PLAYABLE_RUNWAY_MS);

/// What one side has and is willing to send.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
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
        self.duration_ms
            .checked_div(self.chunk_count() as u64)
            .unwrap_or(0)
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
        let Some(index) = pos_ms.checked_div(self.offer.chunk_ms()) else {
            return 0;
        };
        (index as u32).min(self.offer.chunk_count().saturating_sub(1))
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

    /// Whether every chunk from `pos_ms` to the end of the file is already here.
    ///
    /// The difference between "short runway" and "short *file*": a runway of two
    /// seconds with the end of the file inside it is not a transfer running
    /// behind, it is a track that is nearly over, and waiting for more would wait
    /// forever.
    pub fn tail_complete_at(&self, pos_ms: u64) -> bool {
        self.is_complete()
            || (self.chunk_at_ms(pos_ms)..self.offer.chunk_count()).all(|i| self.has(i))
    }

    /// Whether playback may start (or resume) at `pos_ms`.
    ///
    /// Either there is enough runway, or the rest of the file is here and the
    /// runway is simply all that remains — a track with two seconds left is
    /// playable even though two seconds is under the threshold.
    pub fn playable_at(&self, pos_ms: u64) -> bool {
        self.tail_complete_at(pos_ms) || self.runway_ms(pos_ms) >= SHARE_PLAYABLE_RUNWAY_MS
    }

    /// What a reader at `pos_ms` should do, given what has arrived —
    /// [`read_verdict`] over this tracker's own numbers.
    ///
    /// `playing` is whether sound is coming out *right now*, which is what picks
    /// the threshold: see [`SHARE_STALL_FLOOR_MS`].
    pub fn read_verdict_at(&self, pos_ms: u64, playing: bool) -> ReadVerdict {
        read_verdict(&ReadSample {
            playing,
            runway_ms: self.runway_ms(pos_ms),
            tail_complete: self.tail_complete_at(pos_ms),
        })
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

// ── Playing it before it has all arrived ─────────────────────────────────────

/// What a reader should do at the playhead it is at.
///
/// Three answers, and the fourth one is missing on purpose: **nothing here is
/// ever sent to the other person.** `docs/TOGETHER.md` §10 rules out reporting
/// buffering, because a stall signalled as a remote pause is the worst
/// ping-pong available — one side stalls, pauses the other, and that pause makes
/// the first re-evaluate. So there is no wire signal for [`Self::Hold`], and
/// adding one is the change this enum exists to make people argue about first.
///
/// Internally tagged (`{"kind":"hold"}`) like
/// [`crate::together::SyncVerdict`], so a frontend switches on `kind` and
/// uniffi hands over a closed enum.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum ReadVerdict {
    /// The bytes are there to begin here — or to resume from a [`Self::Hold`].
    ///
    /// It is permission, not an instruction: it says the *transfer* is no longer
    /// a reason not to play, and says nothing about whether the session wants
    /// to. A player the person paused, or one a `pause` command from the peer
    /// paused, stays paused.
    Start,
    /// Keep going. There is less here than starting would have needed, and more
    /// than stopping for is worth — see [`SHARE_STALL_FLOOR_MS`].
    Continue,
    /// Stop the player **on this device only** and wait for bytes.
    ///
    /// Concretely, on a frontend: pause the local player and keep asking for
    /// chunks. Do *not* express it as `together_set_state(.., playing: false,
    /// ..)` — that is a command, it takes the next sequence number and it pauses
    /// the other person, which is exactly the ping-pong §10 forbids. Report it
    /// as position instead (`together_report_position(pos, false, latency)`),
    /// which travels only as a heartbeat: the peer's next
    /// [`sync_verdict`](crate::together::sync_verdict) sees a peer that is not
    /// playing and holds rather than correcting, and when the bytes arrive and
    /// this side plays again, the verdict after that closes the gap.
    Hold,
}

/// The three facts [`read_verdict`] decides from, gathered by the caller.
///
/// A separate struct from [`ShareTracker`] because the tracker is not always on
/// this side of the boundary: the desktop keeps a JS twin of it in
/// `share_transfer.mjs` (it needs answers inside a synchronous event handler)
/// and Android will keep one next to the file it is writing. Those are facts
/// about their own bitmap, which is theirs to know; the thresholds are policy,
/// which is not. This split is what lets a frontend hold the bytes and still not
/// own the decision — the same division [`transport::chunks_to_send`] makes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ReadSample {
    /// Whether sound is coming out of this device right now.
    ///
    /// Not "does the session want to play" — this only picks which threshold
    /// applies, because the cost of stopping a running player is a stutter and
    /// the cost of not starting one is a wait.
    pub playing: bool,
    /// [`ShareTracker::runway_ms`] at the playhead.
    pub runway_ms: u64,
    /// [`ShareTracker::tail_complete_at`] at the playhead.
    pub tail_complete: bool,
}

/// The whole starve policy, in one pure function so both frontends inherit the
/// same answer instead of each inventing one.
///
/// The rule, in full:
///
/// 1. **Everything from here to the end of the file has arrived** — play, at any
///    runway. Nothing more is coming, so waiting waits forever.
/// 2. **At least [`SHARE_PLAYABLE_RUNWAY_MS`] of uninterrupted audio** — play.
/// 3. **Already playing, and at least [`SHARE_STALL_FLOOR_MS`] left** — keep
///    going. This is the whole of the hysteresis: what is too little to start on
///    is not too little to carry on with.
/// 4. **Otherwise hold** — locally, silently, as [`ReadVerdict::Hold`] says.
///
/// What it deliberately does **not** promise:
///
/// - **It is not a prediction.** Every input is about bytes that are *already
///   here*; nothing in it knows the throughput of the transfer, and a
///   [`ReadVerdict::Continue`] on five seconds of runway is not a claim that the
///   sixth second will arrive in time. A transfer that stops dead still stalls,
///   one second of playback later per second of runway. The only thing that
///   makes the answer change is a chunk arriving (or the playhead moving), so a
///   caller that never re-asks never learns.
/// - **The runway it is given is quantised and approximate.** `runway_ms` counts
///   whole chunks from the *start* of the chunk holding the playhead, so it
///   over-states by up to a chunk, and `chunk_ms` assumes a constant bitrate
///   which a VBR file is not. Being a chunk out costs a second of buffering,
///   not correctness — which is also why the floor is about a chunk wide.
/// - **It says nothing about sync.** Holding takes this device out of step with
///   the other one by exactly as long as the hold; closing that gap is
///   [`sync_verdict`](crate::together::sync_verdict)'s job on the next pass, and
///   it can only do it once this side reports itself playing again.
pub fn read_verdict(s: &ReadSample) -> ReadVerdict {
    if s.tail_complete || s.runway_ms >= SHARE_PLAYABLE_RUNWAY_MS {
        return if s.playing {
            ReadVerdict::Continue
        } else {
            ReadVerdict::Start
        };
    }
    if s.playing && s.runway_ms >= SHARE_STALL_FLOOR_MS {
        return ReadVerdict::Continue;
    }
    ReadVerdict::Hold
}

// ── The wire: getting from "I don't have it" to a live connection ────────────

/// One step of negotiating the connection a transfer will run over.
///
/// Deliberately its own three variants rather than reusing
/// [`crate::call::CallSignal`]: that type also carries `ringing`, `busy` and
/// `hangup`, none of which mean anything here, and a frontend handed an enum
/// with three impossible arms will eventually write a branch for one of them.
/// The shapes match because WebRTC's negotiation is the same negotiation — the
/// *connection* is not.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum TransferSignal {
    /// Sender → receiver. The side holding the file offers, because it is the
    /// side that opens the data channel.
    Offer { sdp: String },
    /// Receiver → sender.
    Answer { sdp: String },
    /// Either direction: a trickled ICE candidate.
    Ice {
        candidate: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        sdp_mid: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        sdp_m_line_index: Option<u16>,
    },
}

impl TransferSignal {
    /// Stable discriminant string, for logging.
    pub fn kind_str(&self) -> &'static str {
        match self {
            Self::Offer { .. } => "offer",
            Self::Answer { .. } => "answer",
            Self::Ice { .. } => "ice",
        }
    }
}

/// One statement about handing the file over, carried inside the together
/// envelope so it inherits the acceptance gate, the age gate and the session
/// scoping rather than growing its own copies of all three.
///
/// The sequence, and why it has four steps rather than two:
///
/// 1. [`Ask`](ShareSignal::Ask) — "I don't have this." Sent by the side whose
///    library came up empty. It is a separate signal from the offer because the
///    side that *has* the file should not have to guess whether the other one
///    needs it; guessing wrong means either an unwanted upload prompt or a
///    session that silently never starts.
/// 2. [`Offer`](ShareSignal::Offer) — "I have it, and here is its shape."
///    Size and hash arrive before a single byte does, so the receiver can decide
///    against a 4 GB transfer *before* it starts rather than after.
/// 3. [`Accept`](ShareSignal::Accept) — "go ahead." The receiver's yes is what
///    authorises the sender to build a peer connection at all.
/// 4. [`Transport`](ShareSignal::Transport) — the negotiation itself.
///
/// [`Refuse`](ShareSignal::Refuse) may replace any of the replies, and unlike
/// [`crate::together::TogetherSignal::End`] it *does* carry a reason. The
/// argument that keeps a reason off `End` is that leaving is nobody's business;
/// the reason a transfer did not happen is a fact about the network, not about
/// the person, and it is the one thing that tells them whether trying again
/// could work.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(tag = "share", rename_all = "snake_case")]
pub enum ShareSignal {
    /// Receiver → sender: my copy of this is missing; can you send yours?
    Ask,
    /// Sender → receiver: yes, and this is what it is.
    Offer { offer: ShareOffer },
    /// Receiver → sender: send it. Negotiation starts on receipt.
    Accept,
    /// Either direction: this is not happening, and this is why.
    Refuse { reason: transport::RefusalReason },
    /// Either direction: one step of the WebRTC negotiation.
    Transport { signal: TransferSignal },
}

impl ShareSignal {
    /// Stable discriminant string, for logging.
    pub fn kind_str(&self) -> &'static str {
        match self {
            Self::Ask => "ask",
            Self::Offer { .. } => "offer",
            Self::Accept => "accept",
            Self::Refuse { .. } => "refuse",
            Self::Transport { .. } => "transport",
        }
    }
}

// ── Framing on the data channel ──────────────────────────────────────────────

/// Bytes of header on a chunk message: a big-endian `u32` index.
///
/// The index is on the wire even though the receiver asked for the range,
/// because a data channel is ordered but a *transfer* is not: a receiver that
/// seeks re-asks from a new anchor while chunks from the old one are still in
/// flight, so "the next message is the next chunk I asked for" is false exactly
/// when it matters. Four bytes against a 16 KiB payload is 0.02% overhead for
/// removing a whole class of silent corruption.
pub const CHUNK_FRAME_HEADER_BYTES: usize = 4;

/// Prefix `bytes` with its chunk index, ready to hand to a data channel.
pub fn frame_chunk(index: u32, bytes: &[u8]) -> Vec<u8> {
    let mut framed = Vec::with_capacity(CHUNK_FRAME_HEADER_BYTES + bytes.len());
    framed.extend_from_slice(&index.to_be_bytes());
    framed.extend_from_slice(bytes);
    framed
}

/// Split a received data-channel message back into `(index, payload)`.
///
/// Returns `None` for anything too short to carry a header — a peer can put any
/// bytes at all on this channel, so "it is at least four bytes long" is checked
/// rather than assumed.
pub fn parse_chunk_frame(message: &[u8]) -> Option<(u32, &[u8])> {
    if message.len() < CHUNK_FRAME_HEADER_BYTES {
        return None;
    }
    let (head, rest) = message.split_at(CHUNK_FRAME_HEADER_BYTES);
    let index = u32::from_be_bytes(head.try_into().ok()?);
    Some((index, rest))
}

/// Whether a received chunk is one this offer could have produced.
///
/// The index must exist and the payload must be exactly the length the offer's
/// own arithmetic says that chunk is. Both halves matter: a wrong index writes
/// bytes into the wrong place in the file, and a wrong length silently shifts
/// everything after it. The hash would catch both at the end — after the whole
/// transfer — which is much too late to be useful.
pub fn chunk_frame_fits(offer: &ShareOffer, index: u32, payload_len: usize) -> bool {
    matches!(offer.range(index), Some((_, len)) if len as usize == payload_len)
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

    // ── Starving mid-playback ───────────────────────────────────────────────

    /// The whole point of two thresholds: what is too little to start on is not
    /// too little to carry on with.
    #[test]
    fn a_reader_starts_on_a_full_runway_and_carries_on_over_a_shorter_one() {
        let mut t = ten_seconds();
        for i in 0..2 {
            t.accept(i);
        }
        assert_eq!(
            t.read_verdict_at(0, false),
            ReadVerdict::Hold,
            "two seconds is not enough to start on"
        );
        assert_eq!(
            t.read_verdict_at(0, true),
            ReadVerdict::Continue,
            "but a player already running rides those two seconds out"
        );
        for i in 2..5 {
            t.accept(i);
        }
        assert_eq!(t.read_verdict_at(0, false), ReadVerdict::Start);
        assert_eq!(t.read_verdict_at(0, true), ReadVerdict::Continue);
    }

    /// The case §10 is about: the bytes ran out under a playing reader.
    #[test]
    fn a_starved_reader_holds_rather_than_stuttering_into_a_gap() {
        let mut t = ten_seconds();
        for i in [0, 1, 2, 3, 4, 6, 7, 8, 9] {
            t.accept(i);
        }
        // Playing into the hole at chunk 5: the chunk under the playhead is not
        // here at all, so there is nothing to decode however much lies beyond.
        assert_eq!(t.runway_ms(5_000), 0);
        assert_eq!(t.read_verdict_at(5_000, true), ReadVerdict::Hold);
        // And past the hole there is a whole tail, so the same tracker says play.
        assert_eq!(t.read_verdict_at(6_000, false), ReadVerdict::Start);
    }

    /// Without this the reader chatters: hold, one chunk lands, start, stop.
    #[test]
    fn a_reader_that_held_needs_the_full_runway_again_not_a_scrap() {
        let mut t = ten_seconds();
        t.accept(0);
        assert_eq!(t.read_verdict_at(0, false), ReadVerdict::Hold);
        for i in 1..3 {
            t.accept(i);
        }
        assert_eq!(
            t.read_verdict_at(0, false),
            ReadVerdict::Hold,
            "three seconds would start a player that stops again immediately"
        );
        for i in 3..5 {
            t.accept(i);
        }
        assert_eq!(t.read_verdict_at(0, false), ReadVerdict::Start);
    }

    #[test]
    fn the_last_seconds_of_a_file_are_played_even_though_the_runway_is_short() {
        let mut t = ten_seconds();
        t.accept(8);
        t.accept(9);
        assert!(t.runway_ms(8_000) < SHARE_PLAYABLE_RUNWAY_MS);
        assert!(t.tail_complete_at(8_000));
        assert_eq!(
            t.read_verdict_at(8_000, false),
            ReadVerdict::Start,
            "nothing more is coming; holding here would hold forever"
        );
        assert_eq!(t.read_verdict_at(8_000, true), ReadVerdict::Continue);
    }

    /// `playable_at` is the older answer to the same question and the two must
    /// not drift apart, so every arrangement of a ten-chunk file is checked
    /// against it rather than a handful of chosen ones.
    #[test]
    fn starting_agrees_with_playable_at_for_every_arrangement_of_chunks() {
        for bits in 0u32..1024 {
            let mut t = ten_seconds();
            for i in 0..10 {
                if bits & (1 << i) != 0 {
                    t.accept(i);
                }
            }
            for pos in (0..10_000).step_by(1_000) {
                assert_eq!(
                    t.read_verdict_at(pos, false) == ReadVerdict::Start,
                    t.playable_at(pos),
                    "bits {bits:#012b} at {pos} ms"
                );
                // And a running player is never told to stop where a stopped one
                // would have been allowed to start.
                if t.playable_at(pos) {
                    assert_eq!(t.read_verdict_at(pos, true), ReadVerdict::Continue);
                }
            }
        }
    }

    /// Both thresholds are `>=`, and a frontend porting the rule needs to know
    /// which side of each boundary it is on.
    #[test]
    fn each_threshold_is_an_exact_boundary_and_not_an_approximate_one() {
        let at = |runway_ms, playing| {
            read_verdict(&ReadSample {
                playing,
                runway_ms,
                tail_complete: false,
            })
        };
        assert_eq!(at(SHARE_PLAYABLE_RUNWAY_MS, false), ReadVerdict::Start);
        assert_eq!(at(SHARE_PLAYABLE_RUNWAY_MS - 1, false), ReadVerdict::Hold);
        assert_eq!(at(SHARE_STALL_FLOOR_MS, true), ReadVerdict::Continue);
        assert_eq!(at(SHARE_STALL_FLOOR_MS - 1, true), ReadVerdict::Hold);
        assert_eq!(
            at(0, true),
            ReadVerdict::Hold,
            "nothing to decode is a hold"
        );
        assert_eq!(
            at(0, false),
            ReadVerdict::Hold,
            "and an empty runway never starts anything"
        );
    }

    /// A verdict is a decision about this device. There is no arm for telling
    /// the peer, and the tags are what the desktop switches on.
    #[test]
    fn every_read_verdict_round_trips_and_none_of_them_names_the_peer() {
        for (verdict, tag) in [
            (ReadVerdict::Start, "start"),
            (ReadVerdict::Continue, "continue"),
            (ReadVerdict::Hold, "hold"),
        ] {
            let json = serde_json::to_string(&verdict).expect("serialises");
            let value: serde_json::Value = serde_json::from_str(&json).unwrap();
            assert_eq!(value["kind"], tag);
            assert_eq!(
                value.as_object().map(|o| o.len()),
                Some(1),
                "a verdict carries a decision and nothing to send: {json}"
            );
            let back: ReadVerdict = serde_json::from_str(&json).expect("parses");
            assert_eq!(back, verdict);
        }
    }

    /// The frontends call the free function with their own tracker's numbers,
    /// so that path is pinned directly too.
    #[test]
    fn a_frontends_own_numbers_get_the_same_answer_as_the_tracker() {
        let mut t = ten_seconds();
        for i in 0..3 {
            t.accept(i);
        }
        for (pos, playing) in [(0, false), (0, true), (2_000, true), (9_000, false)] {
            assert_eq!(
                read_verdict(&ReadSample {
                    playing,
                    runway_ms: t.runway_ms(pos),
                    tail_complete: t.tail_complete_at(pos),
                }),
                t.read_verdict_at(pos, playing),
                "at {pos} ms, playing {playing}"
            );
        }
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

    // ── The wire ────────────────────────────────────────────────────────────

    #[test]
    fn every_share_signal_round_trips_through_json() {
        let all = [
            ShareSignal::Ask,
            ShareSignal::Offer {
                offer: offer(1024, 256, 4000),
            },
            ShareSignal::Accept,
            ShareSignal::Refuse {
                reason: transport::RefusalReason::RelayForbidden,
            },
            ShareSignal::Transport {
                signal: TransferSignal::Offer {
                    sdp: "v=0\r\n".into(),
                },
            },
            ShareSignal::Transport {
                signal: TransferSignal::Ice {
                    candidate: "candidate:1 1 udp 2 10.0.0.1 5 typ host".into(),
                    sdp_mid: Some("0".into()),
                    sdp_m_line_index: Some(0),
                },
            },
        ];
        for signal in all {
            let json = serde_json::to_string(&signal).expect("serialises");
            let back: ShareSignal = serde_json::from_str(&json).expect("parses");
            assert_eq!(back, signal, "{json}");
            assert!(json.contains("\"share\":"), "the tag must be on the wire");
        }
    }

    #[test]
    fn a_share_signal_is_tagged_apart_from_the_transport_signal_inside_it() {
        // Two internally-tagged enums nested one inside the other, and if they
        // shared a tag name the inner one would silently overwrite the outer.
        let json = serde_json::to_string(&ShareSignal::Transport {
            signal: TransferSignal::Answer { sdp: "s".into() },
        })
        .unwrap();
        let value: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(value["share"], "transport");
        assert_eq!(value["signal"]["kind"], "answer");
    }

    #[test]
    fn a_chunk_carries_its_own_index_rather_than_relying_on_arrival_order() {
        let framed = frame_chunk(7, b"payload");
        assert_eq!(framed.len(), CHUNK_FRAME_HEADER_BYTES + 7);
        assert_eq!(parse_chunk_frame(&framed), Some((7, &b"payload"[..])));
    }

    #[test]
    fn a_frame_too_short_to_hold_a_header_is_rejected_rather_than_indexed() {
        // The peer can put any bytes at all on this channel.
        assert_eq!(parse_chunk_frame(&[]), None);
        assert_eq!(parse_chunk_frame(&[0, 0, 0]), None);
        // Exactly a header and no payload parses — it is a zero-length chunk,
        // which `chunk_frame_fits` is the one to reject, not the parser.
        assert_eq!(parse_chunk_frame(&[0, 0, 0, 1]), Some((1, &[][..])));
    }

    #[test]
    fn a_chunk_of_the_wrong_length_is_caught_now_and_not_by_the_hash_later() {
        let o = offer(250, 100, 0);
        assert!(chunk_frame_fits(&o, 0, 100));
        assert!(chunk_frame_fits(&o, 2, 50), "the last chunk is short");
        assert!(!chunk_frame_fits(&o, 2, 100), "and is not padded to full");
        assert!(!chunk_frame_fits(&o, 0, 99));
        assert!(!chunk_frame_fits(&o, 3, 100), "past the end of the file");
    }

    #[test]
    fn a_signal_names_itself_for_the_log() {
        assert_eq!(ShareSignal::Ask.kind_str(), "ask");
        assert_eq!(
            ShareSignal::Transport {
                signal: TransferSignal::Ice {
                    candidate: String::new(),
                    sdp_mid: None,
                    sdp_m_line_index: None,
                }
            }
            .kind_str(),
            "transport"
        );
        assert_eq!(
            TransferSignal::Offer { sdp: String::new() }.kind_str(),
            "offer"
        );
    }
}
