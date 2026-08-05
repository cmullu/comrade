//! Watch-together soak: two hours of heartbeats, on an injected clock.
//!
//! Every timing constant in `comrade_core::together` was chosen against a
//! single round trip. What none of the unit tests ask is what happens after
//! **720 of them** — one film's worth at [`TOGETHER_HEARTBEAT_SECS`] — with two
//! crystals running at genuinely different rates. Three things could go wrong
//! over that span and none of them are visible in a short test:
//!
//!  - the clock filter growing without bound, because the ring is only trimmed
//!    on insert and the window only filters on read;
//!  - the correction ladder thrashing — a session that seeks every few minutes
//!    is technically "in sync" and unwatchable;
//!  - the skew term and the phase term fighting, so the estimate wanders away
//!    from an offset that never actually moved.
//!
//! Marked `#[ignore]` and run as its own CI gate, the way `feed_flood_load`
//! is: it is a soak, not a per-commit unit test. Deterministic despite the
//! name — there is no relay, no sleeping and no randomness, so a failure here
//! is a real regression and not a flake.
//!
//! ```bash
//! cargo test -p comrade_core --test together_soak --locked -- --ignored --nocapture
//! ```

use comrade_core::together::{
    sync_verdict, ClockFilter, SyncSample, SyncTuning, SyncVerdict, CLOCK_PROBE_RING,
    TOGETHER_HEARTBEAT_SECS,
};

/// Two hours, which is a film.
const HEARTBEATS: u64 = 720;
const HEARTBEAT_MS: u64 = TOGETHER_HEARTBEAT_SECS * 1000;

/// What the peer's clock really is, ahead of ours. Constant for the whole run:
/// the estimate is supposed to *find* this, and anything it does other than
/// converge on it is the bug.
const TRUE_OFFSET_MS: i64 = 3_500;

/// One-way delay to the relay and back. Asymmetric on purpose — a symmetric
/// path is the case NTP arithmetic is exact for, so it would prove nothing.
const UP_MS: u64 = 120;
const DOWN_MS: u64 = 80;

/// How much faster the follower's playback clock runs than the leader's, in
/// parts per million. Real crystals are within ±50 ppm of each other; 80 is
/// pessimistic and still small enough that only a two-hour run reveals it —
/// over 720 heartbeats it is worth just over half a second of genuine drift.
const FOLLOWER_PPM: i64 = 80;

fn tuning() -> SyncTuning {
    // A local file: the tight deadband, and rate trim available.
    SyncTuning {
        min_deadband_ms: 250,
        can_rate_trim: true,
    }
}

/// The follower's session, driven one heartbeat at a time.
struct Follower {
    filter: ClockFilter,
    /// Our wall clock.
    now_ms: u64,
    /// Where our player is. Fractional because the whole point of this test is
    /// a drift of well under a millisecond per heartbeat, which integer
    /// arithmetic rounds to nothing — an earlier version of this file did
    /// exactly that and passed while simulating no drift at all.
    pos_ms: f64,
    /// Our clock when we last moved the playhead automatically.
    last_seek_ms: u64,
    /// The rate trim currently applied, as a multiplier.
    rate: f64,
    seeks: u64,
    nudges: u64,
}

impl Follower {
    fn new() -> Self {
        Self {
            filter: ClockFilter::new(),
            now_ms: 1_000_000,
            pos_ms: 0.0,
            last_seek_ms: 0,
            rate: 1.0,
            seeks: 0,
            nudges: 0,
        }
    }

    /// The leader's playhead at our `now_ms`, which is the truth we are chasing.
    fn leader_pos_ms(&self, started_ms: u64) -> u64 {
        self.now_ms.saturating_sub(started_ms)
    }
}

#[test]
#[ignore = "soak: 720 simulated heartbeats, run explicitly or in the Together CI lane"]
fn two_hours_of_heartbeats_stay_bounded_and_watchable() {
    let mut f = Follower::new();
    let started_ms = f.now_ms;
    // Both start together, so everything below is drift the run itself
    // produced rather than an error seeded at the start.
    let mut worst_drift_ms: i64 = 0;

    for beat in 0..HEARTBEATS {
        // ── Advance one heartbeat ───────────────────────────────────────
        // Our player runs slightly fast, and any rate trim currently applied
        // is on top of that. This is the only thing moving the two apart.
        let elapsed = HEARTBEAT_MS;
        f.now_ms += elapsed;
        // Speed = whatever trim is currently applied, on top of this device's
        // crystal. The trim persists until something changes it, because that
        // is what both frontends actually do with a `Nudge`.
        let speed = f.rate * (1.0 + FOLLOWER_PPM as f64 / 1_000_000.0);
        f.pos_ms = (f.pos_ms + elapsed as f64 * speed).max(0.0);

        // ── The probe the heartbeat carries for free ────────────────────
        // t1 our send, t2 their receive, t3 their send, t4 our receive.
        let t1 = f.now_ms - (UP_MS + DOWN_MS);
        let t2 = (t1 as i64 + TRUE_OFFSET_MS + UP_MS as i64) as u64;
        let t3 = t2;
        let t4 = f.now_ms;
        f.filter.observe(t1, t2, t3, t4);

        // The ring is the whole memory claim: bounded regardless of how long
        // anyone watches for.
        assert!(
            f.filter.len() <= CLOCK_PROBE_RING,
            "clock filter grew to {} probes at heartbeat {beat}",
            f.filter.len(),
        );

        let clock = f.filter.estimate(f.now_ms);
        assert!(
            clock.uncertainty_at(f.now_ms) > 0,
            "uncertainty read as zero at heartbeat {beat} — unmeasured must never read as certain",
        );

        // ── What the peer told us, stamped on their clock ───────────────
        let leader_pos = f.leader_pos_ms(started_ms);
        let sample = SyncSample {
            local_pos_ms: f.pos_ms.round() as u64,
            local_playing: true,
            local_seq: 1,
            peer_pos_ms: leader_pos,
            peer_playing: true,
            peer_seq: 1,
            peer_at_ms: (f.now_ms as i64 + TRUE_OFFSET_MS) as u64,
            now_ms: f.now_ms,
            last_seek_ms: f.last_seek_ms,
            clock,
            we_lead: false,
            local_output_latency_ms: 0,
            peer_output_latency_ms: 0,
            local_rate: f.rate,
        };

        let drift = f.pos_ms.round() as i64 - leader_pos as i64;
        worst_drift_ms = worst_drift_ms.max(drift.abs());

        match sync_verdict(&sample, tuning()) {
            SyncVerdict::Hold => {}
            SyncVerdict::Nudge { rate } => {
                f.rate = rate;
                f.nudges += 1;
            }
            SyncVerdict::Seek { pos_ms } => {
                f.pos_ms = pos_ms as f64;
                f.last_seek_ms = f.now_ms;
                f.rate = 1.0;
                f.seeks += 1;
            }
            SyncVerdict::Adopt { pos_ms, .. } => {
                f.pos_ms = pos_ms as f64;
                f.last_seek_ms = f.now_ms;
                f.seeks += 1;
            }
        }
    }

    println!(
        "two hours: {} seeks, {} nudges, worst drift {worst_drift_ms} ms",
        f.seeks, f.nudges,
    );

    // The offset never moved, so the estimate must not have wandered off it.
    let estimate = f.filter.estimate(f.now_ms);
    let error = (estimate.offset_at(f.now_ms) - TRUE_OFFSET_MS).abs();
    assert!(
        error <= 250,
        "clock estimate wandered {error} ms from an offset that never moved",
    );

    // The watchability claim. A seek is the most disruptive thing this feature
    // does, and 80 ppm of honest crystal drift is a rate-trim's job — if the
    // ladder is reaching for a seek every few minutes over a steady link, the
    // thresholds are wrong even though every unit test still passes.
    assert!(
        f.seeks <= 2,
        "{} seeks over two hours on a steady link — the ladder is thrashing",
        f.seeks,
    );

    // The regression this file was written to catch. A rate trim is sticky:
    // the player keeps whatever multiplier it was last given. Before
    // `sync_verdict` learned to hand back `Nudge { rate: 1.0 }` on the way
    // into the deadband, nothing ever took a trim off — so the follower ran
    // permanently off-speed, sailed through the deadband, and was trimmed the
    // other way, for 191 rate changes over these same two hours. Bounded, and
    // an audible tempo wobble on music.
    assert!(
        f.nudges <= 8,
        "{} rate changes over two hours — the trim is never being taken back off",
        f.nudges,
    );
    assert!(
        (f.rate - 1.0).abs() < 0.001,
        "the session ended still trimmed at {:.4}x",
        f.rate,
    );

    // And it has to have actually worked: staying "smooth" by never correcting
    // would pass the line above and fail the person watching.
    assert!(
        worst_drift_ms <= 1_500,
        "drifted {worst_drift_ms} ms — smooth, but not together",
    );
}

/// The filter is trimmed on insert and *filtered* on read, so a session left
/// running with no probes arriving must not report a stale offset as current.
#[test]
#[ignore = "soak: paired with the run above, same lane"]
fn an_estimate_ages_out_rather_than_going_stale() {
    let mut filter = ClockFilter::new();
    let base = 1_000_000u64;
    for i in 0..CLOCK_PROBE_RING as u64 {
        let t1 = base + i * HEARTBEAT_MS;
        let t2 = (t1 as i64 + TRUE_OFFSET_MS + UP_MS as i64) as u64;
        filter.observe(t1, t2, t2, t1 + UP_MS + DOWN_MS);
    }
    let fresh = filter.estimate(base + CLOCK_PROBE_RING as u64 * HEARTBEAT_MS);
    assert!(fresh.uncertainty_at(base) > 0);

    // Two hours later with nothing new, every probe is outside the window.
    let later = base + 2 * 3_600_000;
    let stale = filter.estimate(later);
    assert!(
        stale.uncertainty_at(later) >= fresh.uncertainty_at(base),
        "an estimate with no live probes claimed more confidence than a fresh one",
    );
}
