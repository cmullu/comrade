//! `metrics::reset()` clears every tally — asserted in its own process.
//!
//! This lives here rather than beside the code because the counter registry is
//! **process-global**, and the assertion is the one thing about it that cannot
//! be phrased as a relative delta: "the registry is empty" is absolute.
//!
//! As a unit test it was flaky, and provably so. `metrics.rs`'s own test module
//! says its tests "share a lock and read *relative* deltas rather than absolute
//! values" — but that lock is private to the module, while production code in
//! `dak::mesh` and `dak::outbox` calls `metrics::record()` on paths that *other*
//! unit tests drive. Those tests hold no lock, so one of them recording a tally
//! between the `reset()` and the `snapshot()` is enough to fail the assertion.
//! It did, on CI, with "panic wipe must leave no tallies".
//!
//! An integration test is compiled into its own binary and run in its own
//! process, so nothing else can touch the registry while this runs. That is a
//! real fix rather than a wider lock nobody can be made to take.

use comrade_core::metrics::{record, reset, snapshot, value, Metric};

#[test]
fn reset_clears_every_counter() {
    for m in Metric::ALL {
        record(m);
    }
    // Precondition, so a future refactor that quietly stops recording cannot
    // make this test pass by having nothing to clear.
    assert!(
        !snapshot().is_empty(),
        "recording every metric should leave tallies to clear"
    );

    reset();

    assert!(snapshot().is_empty(), "panic wipe must leave no tallies");
    for m in Metric::ALL {
        assert_eq!(value(m), 0, "{} survived the wipe", m.key());
    }
}
