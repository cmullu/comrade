/*!
 * Golomb-Coded Set filters — compact "here is what I already have" summaries
 * for reconciling public history between two peers.
 *
 * Adopted from bitchat's `Sync/GCSFilter.swift` and the gossip-sync protocol
 * around it (whitepaper §6.3): every ~15 s each side advertises a filter over
 * the message ids it holds, and the other side replies with only what is
 * missing. A device that walks between two partitions — or relaunches later —
 * then serves the room's recent history to whoever missed it.
 *
 * Comrade needs the same primitive for two places that today have no way to
 * heal a gap: the Saathi mesh (where a peer that was out of range simply never
 * learns what was said) and the Chitthi cache across relays. Advertising a
 * filter instead of a list of ids is what makes it affordable — a few hundred
 * ids fit in a few hundred bytes, versus 32 bytes each.
 *
 * ## Wire format (v1, byte-compatible with bitchat's)
 *
 * An id is hashed with SHA-256; the first 8 bytes (big-endian, top bit
 * cleared) are mapped into `[1, m)`. Mapped values are sorted, and their
 * successive deltas are Golomb-Rice coded with parameter `p`: `q = (x-1) >> p`
 * as unary (`q` ones then a zero), then the low `p` bits of `x-1`. The
 * bitstream is MSB-first within each byte.
 *
 * A filter is a **probabilistic** set: `contains` never returns false for
 * something that was inserted, but returns true for roughly one in `2^p`
 * outsiders. In sync terms a false positive means one message is not re-sent
 * this round — it is retried next round, so the cost is latency, not loss.
 */

use sha2::{Digest, Sha256};

/// Highest Golomb-Rice parameter accepted from the wire. `p` sets the false
/// positive rate at ~`1/2^p`; past 32 the remainder width exceeds any practical
/// filter and shifts would overflow into garbage.
pub const MAX_P: u32 = 32;

/// A built filter, ready to put on the wire.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GcsFilter {
    /// Golomb-Rice parameter.
    pub p: u32,
    /// Hash modulus the ids were mapped into.
    pub m: u32,
    /// Golomb-Rice coded payload.
    pub data: Vec<u8>,
    /// How many of the input ids (in input order) the payload actually encodes.
    ///
    /// Below `ids.len()` when the byte budget forced the tail to be trimmed.
    /// Callers pass ids **newest first**, so the encoded set is always a
    /// contiguous newest-prefix — which is what lets a "since" cursor derived
    /// from `included` stay exact instead of naming an arbitrary hash-order
    /// subset.
    pub included: usize,
}

impl GcsFilter {
    /// Build a filter over `ids`, spending at most `max_bytes` and targeting a
    /// false-positive rate of `target_fpr`.
    pub fn build(ids: &[impl AsRef<[u8]>], max_bytes: usize, target_fpr: f64) -> Self {
        let p = derive_p(target_fpr);
        if ids.is_empty() {
            return Self {
                p,
                m: 1,
                data: Vec::new(),
                included: 0,
            };
        }

        let cap = estimate_max_elements(max_bytes, p);
        // The modulus is fixed to the initial candidate count so `m` stays
        // stable while the tail is trimmed to fit the budget below.
        let m = hash_range(ids.len().min(cap), p);
        let modulo = u64::from(m);

        let encode_first = |count: usize| -> Vec<u8> {
            let mut mapped: Vec<u64> = ids[..count]
                .iter()
                .map(|id| map_hash(h64(id.as_ref()), modulo))
                .collect();
            mapped.sort_unstable();
            let mapped = normalize(&mapped, modulo);
            if mapped.is_empty() {
                Vec::new()
            } else {
                encode_deltas(&mapped, p)
            }
        };

        let mut count = ids.len().min(cap);
        let mut encoded = encode_first(count);
        while encoded.len() > max_bytes && count > 1 {
            count = (count * 9 / 10).max(1);
            encoded = encode_first(count);
        }
        if encoded.len() > max_bytes {
            // A single element that still overflows cannot be represented; an
            // empty filter is honest ("I hold nothing you can skip").
            return Self {
                p,
                m,
                data: Vec::new(),
                included: 0,
            };
        }

        let included = if encoded.is_empty() { 0 } else { count };
        Self {
            p,
            m,
            data: encoded,
            included,
        }
    }

    /// Decode the payload into its sorted mapped values.
    ///
    /// Out-of-range parameters yield an empty set rather than garbage — callers
    /// then treat the peer as holding nothing and send everything, which is
    /// safe (redundant) rather than lossy.
    pub fn decode(&self) -> Vec<u64> {
        decode_to_sorted_set(self.p, self.m, &self.data)
    }

    /// Does this filter (probably) contain `id`?
    pub fn contains_id(&self, id: impl AsRef<[u8]>) -> bool {
        let decoded = self.decode();
        contains(&decoded, bucket(id.as_ref(), self.m))
    }

    /// Which of `local` ids are absent from this filter — i.e. exactly what a
    /// responder should send back. Decodes once, so this is the API to use for
    /// a whole store rather than calling [`Self::contains_id`] in a loop.
    pub fn missing_from<'a, T: AsRef<[u8]>>(&self, local: &'a [T]) -> Vec<&'a T> {
        let decoded = self.decode();
        if decoded.is_empty() {
            // Peer advertised nothing (or an undecodable filter): everything is
            // missing.
            return local.iter().collect();
        }
        local
            .iter()
            .filter(|id| !contains(&decoded, bucket(id.as_ref(), self.m)))
            .collect()
    }
}

/// `ceil(log2(1/fpr))`, clamped to a usable range.
pub fn derive_p(target_fpr: f64) -> u32 {
    let f = target_fpr.clamp(0.000_001, 0.25);
    let p = (1.0f64 / f).log2().ceil() as i64;
    (p.max(1) as u32).min(MAX_P)
}

/// Rough capacity of a `size_bytes` filter at parameter `p` (~`p + 2` bits per
/// element).
pub fn estimate_max_elements(size_bytes: usize, p: u32) -> usize {
    let bits = size_bytes.saturating_mul(8).max(8);
    let per = (p as usize + 2).max(3);
    (bits / per).max(1)
}

/// Decode a filter payload into its sorted mapped values.
pub fn decode_to_sorted_set(p: u32, m: u32, data: &[u8]) -> Vec<u64> {
    if !(1..=MAX_P).contains(&p) || m <= 1 {
        return Vec::new();
    }
    let mut values = Vec::new();
    let mut reader = BitReader::new(data);
    let mut acc: u64 = 0;
    while let Some(q) = reader.read_unary() {
        let Some(r) = reader.read_bits(p) else { break };
        let x = (u64::from(q) << p) + r + 1;
        acc = acc.wrapping_add(x);
        if acc >= u64::from(m) {
            break;
        }
        values.push(acc);
    }
    values
}

/// Binary-search membership over a sorted mapped-value set.
pub fn contains(sorted_values: &[u64], candidate: u64) -> bool {
    sorted_values.binary_search(&candidate).is_ok()
}

/// Mapped bucket of `id` under modulus `m`.
pub fn bucket(id: &[u8], m: u32) -> u64 {
    let modulo = u64::from(m.max(1));
    if modulo <= 1 {
        return 0;
    }
    map_hash(h64(id), modulo)
}

fn h64(id: &[u8]) -> u64 {
    let digest = Sha256::digest(id);
    let mut x: u64 = 0;
    for byte in digest.iter().take(8) {
        x = (x << 8) | u64::from(*byte);
    }
    // Clear the top bit so mapped values stay comfortably inside i64/u64
    // arithmetic on every platform that reimplements this.
    x & 0x7fff_ffff_ffff_ffff
}

fn hash_range(count: usize, p: u32) -> u32 {
    if count == 0 {
        return 1;
    }
    let multiplier = 1u64 << p;
    match (count as u64).checked_mul(multiplier) {
        Some(0) => 1,
        Some(product) if product <= u64::from(u32::MAX) => product as u32,
        _ => u32::MAX,
    }
}

/// Map a hash into `[1, modulo)` — zero is remapped to 1 so no delta is zero.
fn map_hash(hash: u64, modulo: u64) -> u64 {
    if modulo <= 1 {
        return 0;
    }
    let value = hash % modulo;
    if value == 0 {
        1
    } else {
        value
    }
}

/// Clamp into range and drop duplicates so the delta stream is strictly
/// increasing (a zero delta is not encodable).
fn normalize(values: &[u64], modulo: u64) -> Vec<u64> {
    if modulo <= 1 {
        return Vec::new();
    }
    let mut out = Vec::with_capacity(values.len());
    let mut last = 0u64;
    for value in values {
        let normalized = (*value).min(modulo - 1);
        if normalized > last {
            out.push(normalized);
            last = normalized;
        }
    }
    out
}

fn encode_deltas(sorted: &[u64], p: u32) -> Vec<u8> {
    let mut writer = BitWriter::default();
    let mut prev = 0u64;
    let mask: u64 = if p >= 64 { u64::MAX } else { (1u64 << p) - 1 };
    for value in sorted {
        let delta = value.wrapping_sub(prev);
        prev = *value;
        let q = (delta.wrapping_sub(1)) >> p;
        let r = (delta.wrapping_sub(1)) & mask;
        for _ in 0..q {
            writer.write_bit(1);
        }
        writer.write_bit(0);
        writer.write_bits(r, p);
    }
    writer.finish()
}

// ── MSB-first bit plumbing ───────────────────────────────────────────────────

#[derive(Default)]
struct BitWriter {
    buf: Vec<u8>,
    cur: u8,
    nbits: u32,
}

impl BitWriter {
    fn write_bit(&mut self, bit: u8) {
        self.cur = (self.cur << 1) | (bit & 1);
        self.nbits += 1;
        if self.nbits == 8 {
            self.buf.push(self.cur);
            self.cur = 0;
            self.nbits = 0;
        }
    }

    fn write_bits(&mut self, value: u64, count: u32) {
        for i in (0..count).rev() {
            self.write_bit(((value >> i) & 1) as u8);
        }
    }

    fn finish(mut self) -> Vec<u8> {
        if self.nbits > 0 {
            self.buf.push(self.cur << (8 - self.nbits));
        }
        self.buf
    }
}

struct BitReader<'a> {
    data: &'a [u8],
    idx: usize,
    cur: u8,
    left: u32,
}

impl<'a> BitReader<'a> {
    fn new(data: &'a [u8]) -> Self {
        let (cur, left) = match data.first() {
            Some(byte) => (*byte, 8),
            None => (0, 0),
        };
        Self {
            data,
            idx: 0,
            cur,
            left,
        }
    }

    fn read_bit(&mut self) -> Option<u8> {
        if self.idx >= self.data.len() {
            return None;
        }
        let bit = (self.cur >> 7) & 1;
        self.cur <<= 1;
        self.left -= 1;
        if self.left == 0 {
            self.idx += 1;
            if let Some(byte) = self.data.get(self.idx) {
                self.cur = *byte;
                self.left = 8;
            }
        }
        Some(bit)
    }

    fn read_unary(&mut self) -> Option<u32> {
        let mut q = 0u32;
        loop {
            match self.read_bit()? {
                1 => q += 1,
                _ => return Some(q),
            }
        }
    }

    fn read_bits(&mut self, count: u32) -> Option<u64> {
        let mut value = 0u64;
        for _ in 0..count {
            value = (value << 1) | u64::from(self.read_bit()?);
        }
        Some(value)
    }
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn ids(range: std::ops::Range<u32>) -> Vec<Vec<u8>> {
        range
            .map(|i| Sha256::digest(i.to_be_bytes()).to_vec())
            .collect()
    }

    #[test]
    fn every_encoded_id_tests_as_present() {
        let set = ids(0..200);
        let filter = GcsFilter::build(&set, 4096, 0.01);
        assert_eq!(filter.included, set.len(), "budget is ample");
        for id in &set {
            assert!(
                filter.contains_id(id),
                "a filter must never miss an id it encoded"
            );
        }
    }

    #[test]
    fn outsiders_are_rejected_at_roughly_the_target_rate() {
        let set = ids(0..500);
        let filter = GcsFilter::build(&set, 8192, 0.01);
        let outsiders = ids(10_000..11_000);
        let decoded = filter.decode();
        let false_positives = outsiders
            .iter()
            .filter(|id| contains(&decoded, bucket(id, filter.m)))
            .count();
        // Target 1% over 1000 samples; allow generous slack so the test is not
        // flaky, while still failing loudly if the mapping is broken.
        assert!(
            false_positives < 60,
            "{false_positives}/1000 false positives is far above the 1% target"
        );
    }

    #[test]
    fn missing_from_returns_exactly_the_gap() {
        let theirs = ids(0..50);
        let filter = GcsFilter::build(&theirs, 4096, 0.001);
        let mut mine = theirs.clone();
        let extra = ids(900..905);
        mine.extend(extra.iter().cloned());

        let missing = filter.missing_from(&mine);
        assert!(
            missing.len() >= extra.len() - 1,
            "the peer's gap must be reported (allowing one false positive)"
        );
        for id in &missing {
            assert!(
                extra.contains(id),
                "nothing the peer already holds should be re-sent"
            );
        }
    }

    #[test]
    fn empty_filter_means_send_everything() {
        let empty: Vec<Vec<u8>> = Vec::new();
        let filter = GcsFilter::build(&empty, 1024, 0.01);
        assert_eq!(filter.included, 0);
        assert!(filter.data.is_empty());

        let mine = ids(0..10);
        assert_eq!(filter.missing_from(&mine).len(), mine.len());
    }

    #[test]
    fn byte_budget_trims_the_oldest_tail_and_reports_it() {
        let set = ids(0..400); // newest first, by caller contract
        let filter = GcsFilter::build(&set, 64, 0.01);
        assert!(filter.data.len() <= 64, "the budget is a hard cap");
        assert!(
            filter.included < set.len() && filter.included > 0,
            "a trimmed filter must report how much it covers, got {}",
            filter.included
        );
        // The surviving set is the newest contiguous prefix — that is what lets
        // a caller derive an exact since-cursor from `included`.
        for id in &set[..filter.included] {
            assert!(filter.contains_id(id));
        }
    }

    #[test]
    fn decode_rejects_out_of_range_parameters() {
        let set = ids(0..20);
        let good = GcsFilter::build(&set, 4096, 0.01);
        assert!(!good.decode().is_empty());

        for bad in [
            GcsFilter {
                p: 0,
                ..good.clone()
            },
            GcsFilter {
                p: MAX_P + 1,
                ..good.clone()
            },
            GcsFilter {
                m: 1,
                ..good.clone()
            },
            GcsFilter {
                m: 0,
                ..good.clone()
            },
        ] {
            assert!(
                bad.decode().is_empty(),
                "a malformed filter must decode to nothing, not to garbage"
            );
        }
    }

    #[test]
    fn derive_p_tracks_the_target_rate() {
        assert_eq!(derive_p(0.5), 2, "clamped to the 0.25 maximum → p = 2");
        assert_eq!(derive_p(0.25), 2);
        assert_eq!(derive_p(0.01), 7);
        assert_eq!(derive_p(0.001), 10);
        assert!(derive_p(0.000_000_001) <= MAX_P, "p stays decodable");
    }

    #[test]
    fn duplicate_ids_do_not_break_the_delta_stream() {
        let one = ids(0..1);
        let dupes: Vec<Vec<u8>> = vec![one[0].clone(); 5];
        let filter = GcsFilter::build(&dupes, 1024, 0.01);
        assert!(filter.contains_id(&one[0]));
        assert_eq!(
            filter.decode().len(),
            1,
            "duplicates must collapse, not emit a zero delta"
        );
    }

    #[test]
    fn a_single_id_roundtrips() {
        let set = ids(7..8);
        let filter = GcsFilter::build(&set, 32, 0.01);
        assert_eq!(filter.included, 1);
        assert!(filter.contains_id(&set[0]));
    }
}
