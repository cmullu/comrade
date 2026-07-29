/*!
 * Geohash location channels — public rooms scoped to a place instead of to a
 * follow graph.
 *
 * Adopted from bitchat's `Geohash` / `LocationChannel` / `GeohashPresenceService`
 * and its `docs/GeohashPresenceSpec.md`. Comrade's Sabha feed is scoped by
 * *author* (self plus accepted contacts); a location channel is the other
 * useful axis — "who else is around here right now" — and it needs none of the
 * follow graph, which makes it the natural surface for talking to strangers
 * without exposing your identity.
 *
 * Three pieces, all engine-level and transport-agnostic:
 *  • [`encode`] / [`decode_center`] / [`is_valid`] — base32 geohash arithmetic.
 *  • [`Precision`] — the named levels a UI offers, and crucially which of them
 *    presence may be broadcast at.
 *  • [`PresenceTracker`] — bitchat's participant counting (a pubkey is "here"
 *    for [`PRESENCE_WINDOW_SECS`] after its last heartbeat or message).
 *
 * **Privacy rules carried over verbatim, because they are the design.**
 *  1. Presence heartbeats go out only at [`Precision::Region`], `Province`, or
 *     `City` ([`Precision::presence_allowed`]). A heartbeat at building
 *     precision would broadcast "this person is in this building", so the
 *     finer levels are receive-only.
 *  2. A channel is spoken under a per-cell persona
 *     ([`crate::anon::derive_scoped`] with [`crate::anon::SCOPE_GEOHASH`]),
 *     never the identity key.
 *  3. A zero count at a fine precision means *unknown*, not *empty* — nobody
 *     announces there. [`PresenceTracker::count`] returns [`Presence::Unknown`]
 *     for that case so a UI cannot render a misleading "0 people".
 */

use std::collections::HashMap;

/// Base32 alphabet geohashes use ("ghs" ordering: no a, i, l, o).
const BASE32: &[u8; 32] = b"0123456789bcdefghjkmnpqrstuvwxyz";

/// How long a participant stays "here" after their last heartbeat or message.
pub const PRESENCE_WINDOW_SECS: u64 = 5 * 60;

/// Named geohash precisions a location-channel picker offers.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Precision {
    /// ~1250 km cell.
    Region,
    /// ~40 km cell.
    Province,
    /// ~5 km cell.
    City,
    /// ~1.2 km cell.
    Neighborhood,
    /// ~150 m cell.
    Block,
    /// ~40 m cell.
    Building,
}

impl Precision {
    /// Geohash character count for this level.
    pub const fn chars(self) -> usize {
        match self {
            Self::Region => 2,
            Self::Province => 4,
            Self::City => 5,
            Self::Neighborhood => 6,
            Self::Block => 7,
            Self::Building => 8,
        }
    }

    /// Whether a presence heartbeat may be broadcast at this level.
    ///
    /// Coarse levels only: a heartbeat is a public "I am here", and at block or
    /// building precision that is a location disclosure, not a room count.
    pub const fn presence_allowed(self) -> bool {
        matches!(self, Self::Region | Self::Province | Self::City)
    }

    /// The level a geohash of `len` characters belongs to, if it maps to a
    /// named one.
    pub fn from_len(len: usize) -> Option<Self> {
        [
            Self::Region,
            Self::Province,
            Self::City,
            Self::Neighborhood,
            Self::Block,
            Self::Building,
        ]
        .into_iter()
        .find(|p| p.chars() == len)
    }

    pub const ALL: [Precision; 6] = [
        Self::Region,
        Self::Province,
        Self::City,
        Self::Neighborhood,
        Self::Block,
        Self::Building,
    ];
}

/// A validated base32 geohash cell — the identifier of one location channel.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct Geohash(String);

impl Geohash {
    /// Validate and normalise (lowercase) a geohash string.
    pub fn parse(raw: &str) -> Option<Self> {
        let lowered = raw.to_lowercase();
        if is_valid(&lowered) {
            Some(Self(lowered))
        } else {
            None
        }
    }

    /// The cell containing `lat`/`lon` at `precision`.
    pub fn at(lat: f64, lon: f64, precision: Precision) -> Self {
        Self(encode(lat, lon, precision.chars()))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }

    /// Named precision of this cell, when its length maps to one.
    pub fn precision(&self) -> Option<Precision> {
        Precision::from_len(self.0.chars().count())
    }

    /// May this device announce presence in this cell?
    pub fn presence_allowed(&self) -> bool {
        self.precision().is_some_and(Precision::presence_allowed)
    }

    /// The enclosing cell one character coarser, e.g. `tdr1w` → `tdr1`.
    pub fn parent(&self) -> Option<Self> {
        let mut chars: Vec<char> = self.0.chars().collect();
        chars.pop()?;
        if chars.is_empty() {
            return None;
        }
        Some(Self(chars.into_iter().collect()))
    }

    /// Scope name to pass to [`crate::anon::derive_scoped`] for this channel's
    /// persona. Keeping it here means the persona label and the wire tag can
    /// never drift apart.
    pub fn persona_scope(&self) -> &str {
        self.as_str()
    }
}

impl std::fmt::Display for Geohash {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

/// Is `geohash` a well-formed base32 geohash of 1–12 characters?
pub fn is_valid(geohash: &str) -> bool {
    let len = geohash.chars().count();
    (1..=12).contains(&len)
        && geohash
            .chars()
            .all(|c| BASE32.contains(&(c.to_ascii_lowercase() as u8)))
}

/// Encode a coordinate to a `precision`-character geohash. Out-of-range inputs
/// are clamped rather than rejected, matching every other geohash library.
pub fn encode(lat: f64, lon: f64, precision: usize) -> String {
    if precision == 0 {
        return String::new();
    }
    let lat = lat.clamp(-90.0, 90.0);
    let lon = lon.clamp(-180.0, 180.0);

    let mut lat_range = (-90.0f64, 90.0f64);
    let mut lon_range = (-180.0f64, 180.0f64);
    let mut out = String::with_capacity(precision);
    let mut even = true;
    let mut bit = 0u8;
    let mut acc = 0usize;

    while out.len() < precision {
        if even {
            let mid = (lon_range.0 + lon_range.1) / 2.0;
            if lon >= mid {
                acc |= 1 << (4 - bit);
                lon_range.0 = mid;
            } else {
                lon_range.1 = mid;
            }
        } else {
            let mid = (lat_range.0 + lat_range.1) / 2.0;
            if lat >= mid {
                acc |= 1 << (4 - bit);
                lat_range.0 = mid;
            } else {
                lat_range.1 = mid;
            }
        }
        even = !even;
        if bit < 4 {
            bit += 1;
        } else {
            out.push(BASE32[acc] as char);
            bit = 0;
            acc = 0;
        }
    }
    out
}

/// Bounding box of a geohash cell as `(min_lat, min_lon, max_lat, max_lon)`.
/// Returns `None` for anything [`is_valid`] rejects.
pub fn decode_bounds(geohash: &str) -> Option<(f64, f64, f64, f64)> {
    if !is_valid(geohash) {
        return None;
    }
    let mut lat_range = (-90.0f64, 90.0f64);
    let mut lon_range = (-180.0f64, 180.0f64);
    let mut even = true;

    for ch in geohash.to_lowercase().bytes() {
        let value = BASE32.iter().position(|b| *b == ch)?;
        for bit in (0..5).rev() {
            let set = (value >> bit) & 1 == 1;
            if even {
                let mid = (lon_range.0 + lon_range.1) / 2.0;
                if set {
                    lon_range.0 = mid;
                } else {
                    lon_range.1 = mid;
                }
            } else {
                let mid = (lat_range.0 + lat_range.1) / 2.0;
                if set {
                    lat_range.0 = mid;
                } else {
                    lat_range.1 = mid;
                }
            }
            even = !even;
        }
    }
    Some((lat_range.0, lon_range.0, lat_range.1, lon_range.1))
}

/// Centre coordinate of a geohash cell.
pub fn decode_center(geohash: &str) -> Option<(f64, f64)> {
    let (min_lat, min_lon, max_lat, max_lon) = decode_bounds(geohash)?;
    Some(((min_lat + max_lat) / 2.0, (min_lon + max_lon) / 2.0))
}

/// Result of counting who is in a channel.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Presence {
    /// An observed count. Exact for channels where presence is broadcast, and a
    /// lower bound (people who spoke) for finer ones.
    Count(usize),
    /// Presence is not broadcast at this precision *and* nobody has spoken, so
    /// the channel may well have silent listeners. Render "?", never "0".
    Unknown,
}

/// Tracks which personas are currently present in each location channel.
///
/// Keys are per-cell personas ([`crate::anon::derive_scoped`]), so this holds
/// no identity-linkable data even in memory. Timestamps come from the caller
/// (unix seconds), keeping the type pure and directly testable.
#[derive(Debug, Default)]
pub struct PresenceTracker {
    /// cell → (persona pubkey hex → last seen unix seconds)
    seen: HashMap<String, HashMap<String, u64>>,
}

impl PresenceTracker {
    pub fn new() -> Self {
        Self::default()
    }

    /// Record activity — a presence heartbeat *or* a chat message; bitchat
    /// counts both, so someone who speaks without heart-beating still shows up.
    pub fn record(&mut self, cell: &Geohash, persona_pubkey: &str, at_secs: u64) {
        let entry = self.seen.entry(cell.as_str().to_string()).or_default();
        // Never let a stale duplicate pull a fresher sighting backwards.
        let slot = entry.entry(persona_pubkey.to_string()).or_insert(at_secs);
        *slot = (*slot).max(at_secs);
    }

    /// Who is present in `cell` as of `now_secs`.
    pub fn count(&self, cell: &Geohash, now_secs: u64) -> Presence {
        let live = self
            .seen
            .get(cell.as_str())
            .map(|peers| {
                peers
                    .values()
                    .filter(|at| now_secs.saturating_sub(**at) < PRESENCE_WINDOW_SECS)
                    .count()
            })
            .unwrap_or(0);

        if live == 0 && !cell.presence_allowed() {
            // Nobody announces at this precision, so silence proves nothing.
            Presence::Unknown
        } else {
            Presence::Count(live)
        }
    }

    /// Drop entries older than the presence window — call periodically so a
    /// long session does not accumulate every persona ever seen.
    pub fn prune(&mut self, now_secs: u64) {
        for peers in self.seen.values_mut() {
            peers.retain(|_, at| now_secs.saturating_sub(*at) < PRESENCE_WINDOW_SECS);
        }
        self.seen.retain(|_, peers| !peers.is_empty());
    }

    /// Forget everything (channel switch, panic wipe).
    pub fn clear(&mut self) {
        self.seen.clear();
    }
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encodes_known_vectors() {
        // The two canonical geohash examples.
        assert_eq!(encode(42.6, -5.6, 5), "ezs42");
        assert_eq!(encode(57.649_11, 10.407_44, 11), "u4pruydqqvj");
    }

    #[test]
    fn decode_center_lands_inside_the_cell() {
        let (lat, lon) = decode_center("ezs42").expect("valid geohash");
        assert!((lat - 42.6).abs() < 0.05, "lat {lat} near 42.6");
        assert!((lon + 5.6).abs() < 0.05, "lon {lon} near -5.6");
    }

    #[test]
    fn encode_decode_roundtrips_at_every_named_precision() {
        for precision in Precision::ALL {
            let cell = Geohash::at(19.076, 72.8777, precision); // Mumbai
            assert_eq!(cell.as_str().len(), precision.chars());
            let (min_lat, min_lon, max_lat, max_lon) =
                decode_bounds(cell.as_str()).expect("encoded cell is valid");
            assert!(
                (min_lat..=max_lat).contains(&19.076) && (min_lon..=max_lon).contains(&72.8777),
                "{precision:?} cell must contain the point it was encoded from"
            );
        }
    }

    #[test]
    fn validation_rejects_non_base32_and_overlong_input() {
        assert!(is_valid("tdr1w"));
        assert!(Geohash::parse("TDR1W").is_some(), "case is normalised");
        assert!(!is_valid(""), "empty is not a cell");
        assert!(!is_valid("abc"), "a, i, l, o are not in the alphabet");
        assert!(!is_valid("tdr1wtdr1wtdr1w"), "over 12 characters");
        assert!(!is_valid("tdr 1"), "no whitespace");
    }

    #[test]
    fn presence_is_only_broadcast_at_coarse_precisions() {
        assert!(Precision::Region.presence_allowed());
        assert!(Precision::Province.presence_allowed());
        assert!(Precision::City.presence_allowed());
        assert!(
            !Precision::Neighborhood.presence_allowed()
                && !Precision::Block.presence_allowed()
                && !Precision::Building.presence_allowed(),
            "a heartbeat at street precision would broadcast where someone is"
        );
    }

    #[test]
    fn parent_walks_one_level_coarser() {
        let cell = Geohash::parse("tdr1w").unwrap();
        assert_eq!(cell.parent().unwrap().as_str(), "tdr1");
        assert_eq!(Geohash::parse("t").unwrap().parent(), None);
    }

    #[test]
    fn presence_counts_live_personas_and_expires_them() {
        let cell = Geohash::parse("tdr1").unwrap(); // province: presence allowed
        let mut tracker = PresenceTracker::new();
        tracker.record(&cell, "persona-a", 1_000);
        tracker.record(&cell, "persona-b", 1_010);
        assert_eq!(tracker.count(&cell, 1_020), Presence::Count(2));

        // persona-a's sighting has aged out of the window, persona-b's has not.
        assert_eq!(
            tracker.count(&cell, 1_000 + PRESENCE_WINDOW_SECS + 5),
            Presence::Count(1)
        );
        assert_eq!(
            tracker.count(&cell, 1_010 + PRESENCE_WINDOW_SECS + 5),
            Presence::Count(0),
            "presence is broadcast here, so zero really means empty"
        );
    }

    #[test]
    fn a_repeat_sighting_never_moves_a_persona_backwards() {
        let cell = Geohash::parse("tdr1").unwrap();
        let mut tracker = PresenceTracker::new();
        tracker.record(&cell, "persona", 2_000);
        tracker.record(&cell, "persona", 1_000); // relay redelivers an old event
        assert_eq!(tracker.count(&cell, 2_100), Presence::Count(1));
    }

    #[test]
    fn empty_fine_precision_channel_reports_unknown_not_zero() {
        let fine = Geohash::parse("tdr1wxy").unwrap(); // block precision
        let tracker = PresenceTracker::new();
        assert_eq!(
            tracker.count(&fine, 5_000),
            Presence::Unknown,
            "nobody announces at this precision, so 0 would be a lie"
        );

        let mut spoken = PresenceTracker::new();
        spoken.record(&fine, "persona", 5_000);
        assert_eq!(
            spoken.count(&fine, 5_010),
            Presence::Count(1),
            "someone who speaks is still counted"
        );
    }

    #[test]
    fn prune_drops_stale_entries_and_empty_cells() {
        let cell = Geohash::parse("tdr1").unwrap();
        let mut tracker = PresenceTracker::new();
        tracker.record(&cell, "persona", 100);
        tracker.prune(100 + PRESENCE_WINDOW_SECS + 1);
        assert!(tracker.seen.is_empty(), "pruning must reclaim the cell too");
        assert_eq!(tracker.count(&cell, 10_000), Presence::Count(0));
    }
}
