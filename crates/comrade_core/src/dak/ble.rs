/*!
 * Bluetooth Low Energy mail — a sealed [`Envelope`] carried phone-to-phone with
 * no router, no relay, and no infrastructure of any kind.
 *
 * This is bitchat's actual transport (whitepaper §4), and the case
 * [`super::mesh`] explicitly could not cover: [`crate::saathi`] needs a shared
 * IP network, so two phones in a field, at a protest, or in a blackout have
 * nothing to talk over. BLE is the answer to that, and it is the reason the
 * sealed-frame abstraction was kept transport-agnostic even while only one
 * radio used it — nothing in [`super::courier`] or [`super::mesh`] knows what
 * carries a frame.
 *
 * ## What this module is, and is not
 *
 * It is the **policy half**: framing, fragmentation, reassembly and controlled
 * flooding, as pure functions and bounded data structures with no I/O. It runs
 * and is tested on any platform.
 *
 * It is **not the radio**. GATT roles, advertising, scanning, connection
 * management and MTU negotiation are inherently per-platform and live in
 * `android/…/ble/BleMeshService.kt`. That split is deliberate: the parts that
 * decide what goes on the wire, what gets relayed, and what an attacker can
 * make us allocate are the parts worth testing, and they are exactly the parts
 * that do not need a radio to test.
 *
 * ## Why fragmentation exists here
 *
 * A negotiated BLE ATT MTU gives roughly 180–500 usable bytes per write, while
 * a sealed envelope runs to [`super::courier::MAX_CIPHERTEXT_BYTES`] (16 KiB).
 * So every envelope is split across numbered packets and rebuilt on arrival.
 * Gossipsub did this for us on the LAN; BLE has no such layer, so it is written
 * out here.
 *
 * ## Why flooding is controlled
 *
 * Every device relays what it hears, which is what makes a mesh out of a set of
 * pairwise links — and, unmanaged, is also a broadcast storm. Three bounds keep
 * it finite, all of them bitchat's:
 *
 *  * **TTL.** Every packet carries a hop budget, decremented on relay and
 *    dropped at zero, so nothing circulates forever.
 *  * **Dedup.** A packet already seen is never relayed again, which is what
 *    stops a cycle in the peer graph from echoing.
 *  * **Jitter.** Relays wait a short random delay, so ten phones that heard the
 *    same packet do not all transmit into each other on the same millisecond.
 *
 * ## Threat model note
 *
 * Reassembly is the one place an attacker gets to make us allocate: anybody in
 * radio range can send a first fragment claiming a large `count` and never send
 * the rest. [`Reassembler`] is therefore bounded in both directions — a cap on
 * concurrent partial packets and a cap on fragments per packet — and evicts by
 * age. The sealed payload is *still* unauthenticated at this layer; the MAC
 * inside [`super::mesh::open_dm`] is what decides whether a rebuilt envelope is
 * genuine, exactly as on the LAN path.
 */

use std::collections::HashMap;

use crate::{
    error::DakError,
    metrics::{self, Metric},
};

// ── Wire format ──────────────────────────────────────────────────────────────

/// Bumped only for an incompatible packet layout, and checked on decode so a
/// future format is rejected rather than misparsed. Mirrors
/// [`super::courier::Envelope`]'s own version byte.
pub const WIRE_VERSION: u8 = 1;

/// `version(1) ‖ packet_id(8) ‖ ttl(1) ‖ index(2) ‖ count(2)`.
///
/// Fourteen bytes on every packet, which is why it is worth being small: at a
/// conservative 185-byte MTU it is roughly 8% of the budget.
pub const HEADER_LEN: usize = 1 + 8 + 1 + 2 + 2;

/// The smallest MTU worth trying to run on.
///
/// BLE guarantees only 23 bytes before negotiation, which after the 3-byte ATT
/// overhead and [`HEADER_LEN`] would leave 6 bytes of payload — a 16 KiB
/// envelope would need thousands of packets. Every modern stack negotiates far
/// above this; refusing to fragment below it turns a pathological case into an
/// error the caller can report instead of a transfer that never finishes.
pub const MIN_USABLE_MTU: usize = 64;

/// Hop budget a locally-originated packet starts with.
///
/// Seven is bitchat's, and the reasoning carries over: it comfortably spans a
/// room or a march (each hop is a device, not a metre) while bounding the
/// worst-case echo of a single packet to something a phone battery can afford.
pub const DEFAULT_TTL: u8 = 7;

/// Concurrent partial packets [`Reassembler`] will hold.
///
/// The attacker-facing bound: anyone in radio range can open a reassembly by
/// sending one fragment and never sending the rest. Past this the oldest is
/// evicted, so memory is capped no matter who is transmitting.
pub const MAX_PARTIAL_PACKETS: usize = 32;

/// Fragments one packet may claim.
///
/// Caps a single envelope's reassembly buffer independently of the count byte
/// an attacker supplies: 512 × a ~500-byte MTU still comfortably exceeds the
/// 16 KiB envelope cap, so this never rejects a legitimate transfer.
pub const MAX_FRAGMENTS: u16 = 512;

/// How long a partial packet waits for its missing fragments before being
/// dropped. Long enough for a slow link to finish a large envelope, short
/// enough that abandoned reassemblies do not accumulate.
pub const PARTIAL_TTL_SECS: u64 = 60;

/// One BLE packet: a slice of a sealed envelope, plus what the mesh needs to
/// rebuild and relay it.
///
/// Deliberately carries no sender, no recipient and no sequence beyond its own
/// packet — [`packet_id`](Self::packet_id) is a random per-envelope value, not
/// anything derived from identity, so a listener learns only that *a* device
/// nearby sent *some* frame in *n* pieces.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Fragment {
    /// Random id shared by every fragment of one envelope. The dedup key for
    /// flooding, and the reassembly key.
    pub packet_id: u64,
    /// Remaining hops. Decremented on relay; a packet at zero is not forwarded.
    pub ttl: u8,
    /// Zero-based position of this fragment.
    pub index: u16,
    /// How many fragments the whole envelope was split into.
    pub count: u16,
    /// This slice of the envelope bytes.
    pub payload: Vec<u8>,
}

impl Fragment {
    /// Serialise for transmission. The inverse of [`Self::decode`].
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEADER_LEN + self.payload.len());
        out.push(WIRE_VERSION);
        out.extend_from_slice(&self.packet_id.to_be_bytes());
        out.push(self.ttl);
        out.extend_from_slice(&self.index.to_be_bytes());
        out.extend_from_slice(&self.count.to_be_bytes());
        out.extend_from_slice(&self.payload);
        out
    }

    /// Parse a packet received over the air.
    ///
    /// # Errors
    /// [`DakError::Malformed`] for a truncated buffer, an unknown version, an
    /// empty payload, a zero `count`, an `index` outside `count`, or a `count`
    /// past [`MAX_FRAGMENTS`] — every one of them checked *before* anything is
    /// allocated in proportion to what the sender claimed.
    pub fn decode(bytes: &[u8]) -> Result<Self, DakError> {
        if bytes.len() <= HEADER_LEN {
            return Err(DakError::Malformed("packet shorter than its header".into()));
        }
        if bytes[0] != WIRE_VERSION {
            return Err(DakError::Malformed(format!(
                "unsupported packet version {}",
                bytes[0]
            )));
        }
        let packet_id = u64::from_be_bytes(
            bytes[1..9]
                .try_into()
                .map_err(|_| DakError::Malformed("truncated packet id".into()))?,
        );
        let ttl = bytes[9];
        let index = u16::from_be_bytes([bytes[10], bytes[11]]);
        let count = u16::from_be_bytes([bytes[12], bytes[13]]);

        if count == 0 {
            return Err(DakError::Malformed("packet claims zero fragments".into()));
        }
        if count > MAX_FRAGMENTS {
            return Err(DakError::Malformed(format!(
                "packet claims {count} fragments, over the {MAX_FRAGMENTS} cap"
            )));
        }
        if index >= count {
            return Err(DakError::Malformed(format!(
                "fragment {index} is outside a {count}-fragment packet"
            )));
        }
        Ok(Self {
            packet_id,
            ttl,
            index,
            count,
            payload: bytes[HEADER_LEN..].to_vec(),
        })
    }

    /// This packet as it should go out on a relay: one hop spent.
    ///
    /// `None` when the budget is exhausted, which is the caller's signal to
    /// drop it rather than forward it.
    pub fn relayed(&self) -> Option<Self> {
        let ttl = self.ttl.checked_sub(1).filter(|t| *t > 0)?;
        Some(Self {
            ttl,
            ..self.clone()
        })
    }
}

/// Split an encoded envelope into packets that fit the negotiated MTU.
///
/// `mtu` is the **usable application payload** per write — what the platform
/// reports after subtracting its own ATT overhead — so the only subtraction
/// done here is [`HEADER_LEN`].
///
/// # Errors
/// [`DakError::Malformed`] when `mtu` is below [`MIN_USABLE_MTU`] or the
/// envelope is empty, and [`DakError::Oversize`] when it would need more than
/// [`MAX_FRAGMENTS`] packets — better a refusal the sender can report than a
/// transfer that cannot complete.
pub fn fragment(
    envelope: &[u8],
    packet_id: u64,
    ttl: u8,
    mtu: usize,
) -> Result<Vec<Fragment>, DakError> {
    if envelope.is_empty() {
        return Err(DakError::Malformed("nothing to fragment".into()));
    }
    if mtu < MIN_USABLE_MTU {
        return Err(DakError::Malformed(format!(
            "{mtu}-byte MTU is below the {MIN_USABLE_MTU}-byte floor"
        )));
    }
    let per_packet = mtu - HEADER_LEN;
    let total = envelope.len().div_ceil(per_packet);
    if total > MAX_FRAGMENTS as usize {
        return Err(DakError::Oversize {
            size: envelope.len(),
            max: per_packet * MAX_FRAGMENTS as usize,
        });
    }
    let count = total as u16;
    Ok(envelope
        .chunks(per_packet)
        .enumerate()
        .map(|(i, chunk)| Fragment {
            packet_id,
            ttl,
            index: i as u16,
            count,
            payload: chunk.to_vec(),
        })
        .collect())
}

// ── Reassembly ───────────────────────────────────────────────────────────────

/// A packet being rebuilt from fragments still arriving.
#[derive(Debug)]
struct Partial {
    count: u16,
    /// Slot per fragment index; `None` until that fragment arrives. Indexed
    /// rather than appended so out-of-order arrival — the normal case on a
    /// multi-hop mesh — needs no sorting and a duplicate overwrites in place.
    slots: Vec<Option<Vec<u8>>>,
    have: u16,
    first_seen: u64,
}

/// Rebuilds envelopes from fragments, bounded against a hostile sender.
///
/// Not `Send`-shared internally — the caller owns it behind whatever lock its
/// transport needs, matching [`super::outbox::Outbox`]'s posture of keeping
/// policy types free of their own synchronisation.
#[derive(Debug, Default)]
pub struct Reassembler {
    partials: HashMap<u64, Partial>,
}

impl Reassembler {
    pub fn new() -> Self {
        Self::default()
    }

    /// Feed in a fragment. Returns the complete envelope bytes on the fragment
    /// that finishes it, and `None` while a packet is still incomplete.
    ///
    /// `now_secs` drives eviction of abandoned reassemblies; pass the same
    /// clock the rest of the dak layer uses.
    ///
    /// A fragment whose `count` disagrees with the one already recorded for its
    /// packet id is dropped rather than trusted: that is either corruption or
    /// somebody trying to resize a buffer mid-flight.
    pub fn accept(&mut self, fragment: Fragment, now_secs: u64) -> Option<Vec<u8>> {
        self.evict_stale(now_secs);

        // The single-fragment case is the overwhelmingly common one — a text
        // message seals to a few hundred bytes — so it never touches the map.
        if fragment.count == 1 {
            metrics::record(Metric::BleReassembled);
            return Some(fragment.payload);
        }

        if self.partials.len() >= MAX_PARTIAL_PACKETS
            && !self.partials.contains_key(&fragment.packet_id)
        {
            self.evict_oldest();
        }

        let partial = self
            .partials
            .entry(fragment.packet_id)
            .or_insert_with(|| Partial {
                count: fragment.count,
                slots: vec![None; fragment.count as usize],
                have: 0,
                first_seen: now_secs,
            });

        if partial.count != fragment.count {
            metrics::record(Metric::BleFragmentDropped);
            return None;
        }
        let slot = &mut partial.slots[fragment.index as usize];
        if slot.is_none() {
            partial.have += 1;
        }
        *slot = Some(fragment.payload);

        if partial.have < partial.count {
            return None;
        }
        let done = self.partials.remove(&fragment.packet_id)?;
        metrics::record(Metric::BleReassembled);
        Some(done.slots.into_iter().flatten().flatten().collect())
    }

    /// Partial packets currently held — for tests and diagnostics.
    pub fn pending(&self) -> usize {
        self.partials.len()
    }

    fn evict_stale(&mut self, now_secs: u64) {
        self.partials.retain(|_, p| {
            let alive = now_secs.saturating_sub(p.first_seen) < PARTIAL_TTL_SECS;
            if !alive {
                metrics::record(Metric::BleFragmentDropped);
            }
            alive
        });
    }

    fn evict_oldest(&mut self) {
        if let Some((&oldest, _)) = self
            .partials
            .iter()
            .min_by_key(|(id, p)| (p.first_seen, **id))
        {
            self.partials.remove(&oldest);
            metrics::record(Metric::BleFragmentDropped);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A realistic negotiated MTU: Android commonly lands here after
    /// `requestMtu(517)` on a modern peer.
    const MTU: usize = 244;

    fn envelope(len: usize) -> Vec<u8> {
        (0..len).map(|i| (i % 251) as u8).collect()
    }

    fn rebuild(fragments: Vec<Fragment>) -> Option<Vec<u8>> {
        let mut r = Reassembler::new();
        let mut out = None;
        for f in fragments {
            out = r.accept(f, 0).or(out);
        }
        out
    }

    #[test]
    fn a_short_message_is_one_packet_and_skips_reassembly_entirely() {
        // The common case by a wide margin: a sealed text DM is a few hundred
        // bytes, so it must not pay for buffering or leave state behind.
        let body = envelope(100);
        let fragments = fragment(&body, 7, DEFAULT_TTL, MTU).unwrap();
        assert_eq!(fragments.len(), 1);

        let mut r = Reassembler::new();
        assert_eq!(r.accept(fragments[0].clone(), 0), Some(body));
        assert_eq!(r.pending(), 0, "a one-packet envelope leaves no partials");
    }

    #[test]
    fn a_long_envelope_survives_the_round_trip_through_the_wire_format() {
        let body = envelope(4096);
        let fragments = fragment(&body, 42, DEFAULT_TTL, MTU).unwrap();
        assert!(fragments.len() > 1);

        // Through encode/decode, because that is what actually crosses the air.
        let on_air: Vec<Fragment> = fragments
            .iter()
            .map(|f| Fragment::decode(&f.encode()).expect("our own packets must parse"))
            .collect();
        assert_eq!(rebuild(on_air), Some(body));
    }

    #[test]
    fn fragments_arriving_out_of_order_or_twice_still_rebuild() {
        // Both are normal on a multi-hop mesh: paths differ in length, and the
        // same packet reaches us from two neighbours.
        let body = envelope(2000);
        let mut fragments = fragment(&body, 1, DEFAULT_TTL, MTU).unwrap();
        fragments.reverse();
        let duplicated = fragments[0].clone();
        fragments.insert(2, duplicated);

        assert_eq!(rebuild(fragments), Some(body));
    }

    #[test]
    fn every_packet_fits_the_mtu() {
        // The one property the radio cannot recover from: a write larger than
        // the negotiated MTU is not truncated, it fails.
        for mtu in [MIN_USABLE_MTU, 100, MTU, 512] {
            for len in [1, 63, 500, 8192] {
                for f in fragment(&envelope(len), 3, DEFAULT_TTL, mtu).unwrap() {
                    assert!(
                        f.encode().len() <= mtu,
                        "a {len}-byte envelope produced a packet over a {mtu}-byte MTU"
                    );
                }
            }
        }
    }

    #[test]
    fn ttl_bounds_how_far_a_packet_travels() {
        let hop = Fragment {
            packet_id: 1,
            ttl: 3,
            index: 0,
            count: 1,
            payload: vec![9],
        };
        let once = hop.relayed().expect("3 hops left, so it travels");
        assert_eq!(once.ttl, 2);
        let twice = once.relayed().expect("2 hops left");
        assert_eq!(twice.ttl, 1);
        assert_eq!(
            twice.relayed(),
            None,
            "the last hop is delivered, not forwarded again — otherwise a \
             packet circulates until the dedup cache forgets it"
        );

        // And a packet that arrives already spent is never forwarded.
        assert_eq!(Fragment { ttl: 0, ..hop }.relayed(), None);
    }

    #[test]
    fn relaying_changes_only_the_hop_count() {
        // Anything else would break reassembly downstream, and changing the
        // packet id would defeat every other device's dedup.
        let f = Fragment {
            packet_id: 77,
            ttl: 5,
            index: 2,
            count: 4,
            payload: vec![1, 2, 3],
        };
        let r = f.relayed().unwrap();
        assert_eq!(r.packet_id, f.packet_id);
        assert_eq!(r.index, f.index);
        assert_eq!(r.count, f.count);
        assert_eq!(r.payload, f.payload);
    }

    #[test]
    fn a_hostile_packet_cannot_make_us_allocate_what_it_claims() {
        // The reassembler is the one place in range of anyone with a radio, so
        // every field a sender controls is checked before it sizes anything.
        let mut header = vec![WIRE_VERSION];
        header.extend_from_slice(&1u64.to_be_bytes());
        header.push(DEFAULT_TTL);

        let with = |index: u16, count: u16| {
            let mut b = header.clone();
            b.extend_from_slice(&index.to_be_bytes());
            b.extend_from_slice(&count.to_be_bytes());
            b.push(0xAA);
            b
        };

        assert!(Fragment::decode(&with(0, 0)).is_err(), "zero fragments");
        assert!(
            Fragment::decode(&with(5, 3)).is_err(),
            "index outside count"
        );
        assert!(
            Fragment::decode(&with(0, MAX_FRAGMENTS + 1)).is_err(),
            "a count past the cap must be refused before a Vec is sized from it"
        );
        assert!(
            Fragment::decode(&with(0, MAX_FRAGMENTS)).is_ok(),
            "the cap itself is legal"
        );

        // Header-only and truncated buffers.
        assert!(Fragment::decode(&header).is_err());
        assert!(Fragment::decode(&[]).is_err());
        assert!(Fragment::decode(&with(0, 1)[..4]).is_err());

        // A future wire version is rejected, not misparsed.
        let mut wrong_version = with(0, 1);
        wrong_version[0] = WIRE_VERSION + 1;
        assert!(Fragment::decode(&wrong_version).is_err());
    }

    #[test]
    fn incomplete_reassemblies_are_bounded_not_unbounded() {
        // Someone in range sends one fragment of many, over and over, from a
        // fresh packet id each time. Memory must not follow.
        let mut r = Reassembler::new();
        for id in 0..(MAX_PARTIAL_PACKETS as u64 * 4) {
            let opener = Fragment {
                packet_id: id,
                ttl: DEFAULT_TTL,
                index: 0,
                count: 8,
                payload: vec![0; 200],
            };
            assert_eq!(r.accept(opener, 0), None);
            assert!(
                r.pending() <= MAX_PARTIAL_PACKETS,
                "reassembly buffer grew past its cap at packet {id}"
            );
        }
    }

    #[test]
    fn an_abandoned_reassembly_is_dropped_rather_than_held_forever() {
        let mut r = Reassembler::new();
        let first = Fragment {
            packet_id: 5,
            ttl: DEFAULT_TTL,
            index: 0,
            count: 2,
            payload: vec![1],
        };
        assert_eq!(r.accept(first, 1_000), None);
        assert_eq!(r.pending(), 1);

        // Its other half never arrives; something unrelated turns up later.
        let unrelated = Fragment {
            packet_id: 6,
            ttl: DEFAULT_TTL,
            index: 0,
            count: 1,
            payload: vec![2],
        };
        r.accept(unrelated, 1_000 + PARTIAL_TTL_SECS + 1);
        assert_eq!(r.pending(), 0, "the stale partial should have been swept");
    }

    #[test]
    fn a_fragment_that_disagrees_about_the_packet_size_is_dropped() {
        // Corruption, or an attempt to resize a buffer that is already open.
        let mut r = Reassembler::new();
        r.accept(
            Fragment {
                packet_id: 1,
                ttl: DEFAULT_TTL,
                index: 0,
                count: 3,
                payload: vec![1],
            },
            0,
        );
        let liar = Fragment {
            packet_id: 1,
            ttl: DEFAULT_TTL,
            index: 1,
            count: 9,
            payload: vec![2],
        };
        assert_eq!(r.accept(liar, 0), None);
        assert_eq!(r.pending(), 1, "the original reassembly is untouched");
    }

    #[test]
    fn an_unusable_mtu_is_an_error_rather_than_a_transfer_that_never_ends() {
        assert!(fragment(&envelope(100), 1, DEFAULT_TTL, MIN_USABLE_MTU - 1).is_err());
        assert!(
            fragment(&[], 1, DEFAULT_TTL, MTU).is_err(),
            "nothing to send"
        );

        // More packets than the cap allows: refused, so the sender can say so
        // rather than starting a transfer that cannot complete.
        let huge = envelope(MIN_USABLE_MTU * MAX_FRAGMENTS as usize);
        assert!(matches!(
            fragment(&huge, 1, DEFAULT_TTL, MIN_USABLE_MTU),
            Err(DakError::Oversize { .. })
        ));
    }

    #[test]
    fn the_packet_header_carries_no_identity() {
        // The privacy property the whole sealed-frame design rests on: a
        // listener with a radio learns that *a* device sent *some* frame in n
        // pieces, and nothing else. Everything identifying is inside the
        // envelope, which is inside the payload.
        let body = envelope(300);
        let fragments = fragment(&body, 0xDEAD_BEEF, DEFAULT_TTL, MTU).unwrap();
        let wire = fragments[0].encode();
        assert_eq!(
            wire.len() - fragments[0].payload.len(),
            HEADER_LEN,
            "the header is exactly the documented fields — no room for a sender"
        );
    }
}
