/*!
 * Privacy-safe local counters.
 *
 * Adopted from bitchat's `StoreAndForwardMetrics`: bare event tallies with **no
 * message ids, peer identities, content, or timestamps**, so delivery behaviour
 * can be measured on-device without recording who talked to whom. Nothing here
 * ever leaves the device — there is no exporter, no endpoint, and no per-peer
 * dimension a snapshot could be re-identified from.
 *
 * That constraint is what makes the counters safe to keep in a wellbeing app:
 * "17 messages were queued while offline" is operational data; "17 messages
 * were queued for @asha at 02:14" is a diary of someone's crisis. Only the
 * former is representable by this module — [`Metric`] is a closed enum of
 * static strings and the values are `u64`, so a caller *cannot* attach a
 * label even by accident.
 *
 * Counters live in memory for the process lifetime. They are reset by
 * [`reset`], which the panic wipe calls alongside the stores they describe.
 */

use std::{
    collections::BTreeMap,
    sync::{Mutex, OnceLock},
};

/// Every event the app counts. Adding a variant is the only way to add a
/// counter, which keeps the set auditable in one place (bitchat's release
/// checklist discipline: a new persistent store must add a wipe hook and a
/// test; a new counter must stay identity-free).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Metric {
    // ── Outbox (sender-side store and forward) ──────────────────────────────
    /// A DM could not be published and entered the outbox.
    OutboxQueued,
    /// A retained DM was re-sent on a flush.
    OutboxResent,
    /// A delivery/read receipt cleared a retained DM.
    OutboxDelivered,
    /// A retained DM was dropped (attempt cap, TTL, or per-peer overflow).
    OutboxDropped,

    // ── Courier (carrying mail for third parties) ───────────────────────────
    /// We handed sealed mail to a courier.
    CourierDeposited,
    /// We accepted sealed mail to carry for someone else.
    CourierAccepted,
    /// We rejected a deposit (quota, size, or lifetime policy).
    CourierRejected,
    /// We handed carried mail to its recipient.
    CourierHandedOver,
    /// We split spray-and-wait copies with another courier.
    CourierSprayed,
    /// Mail addressed to us was opened successfully.
    CourierOpened,
    /// Carried mail expired unread and was pruned.
    CourierExpired,

    // ── Local-network mesh transport ─────────────────────────────────────────
    /// A DM was published as a sealed frame onto the local mesh.
    MeshSent,
    /// A sealed mesh frame turned out to be addressed to us and opened.
    MeshReceived,

    // ── Bluetooth transport (no router at all) ──────────────────────────────
    /// A sealed envelope was fragmented and handed to the BLE radio.
    BleSent,
    /// Fragments rebuilt into a complete envelope.
    BleReassembled,
    /// A fragment was refused or evicted — malformed, contradictory, or a
    /// reassembly abandoned. Expected to be non-zero on a busy or hostile
    /// radio; a climbing count next to a flat [`Self::BleReassembled`] is the
    /// signal that something in range is misbehaving.
    BleFragmentDropped,
    /// A packet heard from one peer was forwarded on to others.
    BleRelayed,

    // ── Ingress dedup ───────────────────────────────────────────────────────
    /// A redelivered event was dropped by a [`crate::seen::SeenSet`].
    DuplicateDropped,

    // ── Public history reconciliation ───────────────────────────────────────
    /// We answered a peer's sync request with packets it was missing.
    SyncServed,
    /// A peer's sync response filled a gap in our history.
    SyncReceived,
}

impl Metric {
    /// Stable key used in snapshots and logs.
    pub const fn key(self) -> &'static str {
        match self {
            Self::OutboxQueued => "outbox.queued",
            Self::OutboxResent => "outbox.resent",
            Self::OutboxDelivered => "outbox.delivered",
            Self::OutboxDropped => "outbox.dropped",
            Self::CourierDeposited => "courier.deposited",
            Self::CourierAccepted => "courier.accepted",
            Self::CourierRejected => "courier.rejected",
            Self::CourierHandedOver => "courier.handed_over",
            Self::CourierSprayed => "courier.sprayed",
            Self::CourierOpened => "courier.opened",
            Self::CourierExpired => "courier.expired",
            Self::MeshSent => "mesh.sent",
            Self::MeshReceived => "mesh.received",
            Self::BleSent => "ble.sent",
            Self::BleReassembled => "ble.reassembled",
            Self::BleFragmentDropped => "ble.fragment_dropped",
            Self::BleRelayed => "ble.relayed",
            Self::DuplicateDropped => "dedup.dropped",
            Self::SyncServed => "sync.served",
            Self::SyncReceived => "sync.received",
        }
    }

    /// Every metric, for exhaustive snapshots and tests.
    pub const ALL: [Metric; 20] = [
        Self::OutboxQueued,
        Self::OutboxResent,
        Self::OutboxDelivered,
        Self::OutboxDropped,
        Self::CourierDeposited,
        Self::CourierAccepted,
        Self::CourierRejected,
        Self::CourierHandedOver,
        Self::CourierSprayed,
        Self::CourierOpened,
        Self::CourierExpired,
        Self::MeshSent,
        Self::MeshReceived,
        Self::BleSent,
        Self::BleReassembled,
        Self::BleFragmentDropped,
        Self::BleRelayed,
        Self::DuplicateDropped,
        Self::SyncServed,
        Self::SyncReceived,
    ];
}

fn counters() -> &'static Mutex<BTreeMap<&'static str, u64>> {
    static COUNTERS: OnceLock<Mutex<BTreeMap<&'static str, u64>>> = OnceLock::new();
    COUNTERS.get_or_init(|| Mutex::new(BTreeMap::new()))
}

fn with_counters<R>(f: impl FnOnce(&mut BTreeMap<&'static str, u64>) -> R) -> R {
    // A panic elsewhere while holding this lock leaves a plain map behind;
    // recovering keeps a counter bump from turning into a second panic on a
    // hot ingress path.
    let mut guard = counters().lock().unwrap_or_else(|e| e.into_inner());
    f(&mut guard)
}

/// Count one occurrence of `metric`.
pub fn record(metric: Metric) {
    let total = with_counters(|c| {
        let slot = c.entry(metric.key()).or_insert(0);
        *slot = slot.saturating_add(1);
        *slot
    });
    tracing::debug!(metric = metric.key(), total, "metric");
}

/// Add `n` occurrences at once (a batched flush, a multi-id receipt).
pub fn record_n(metric: Metric, n: u64) {
    if n == 0 {
        return;
    }
    let total = with_counters(|c| {
        let slot = c.entry(metric.key()).or_insert(0);
        *slot = slot.saturating_add(n);
        *slot
    });
    tracing::debug!(metric = metric.key(), total, "metric");
}

/// Current value of one counter.
pub fn value(metric: Metric) -> u64 {
    with_counters(|c| c.get(metric.key()).copied().unwrap_or(0))
}

/// All non-zero counters, sorted by key — what a diagnostics screen renders and
/// what a bug report can safely include.
pub fn snapshot() -> BTreeMap<String, u64> {
    with_counters(|c| {
        c.iter()
            .filter(|(_, v)| **v > 0)
            .map(|(k, v)| ((*k).to_string(), *v))
            .collect()
    })
}

/// Zero every counter — called by the panic wipe.
pub fn reset() {
    with_counters(|c| c.clear());
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    /// The counter registry is process-global (one app, one set of tallies),
    /// so the tests that mutate it share a lock and read *relative* deltas
    /// rather than absolute values.
    ///
    /// This lock only covers *these* tests. Production code in `dak::mesh` and
    /// `dak::outbox` records metrics on paths other unit tests drive, and those
    /// tests take nothing — so any assertion about the registry's absolute
    /// state is unsound here however careful this module is. The one such
    /// assertion, that `reset()` empties it, therefore lives in
    /// `tests/metrics_reset.rs`, where it gets its own process.
    static TEST_LOCK: Mutex<()> = Mutex::new(());

    #[test]
    fn record_increments_and_snapshot_reports() {
        let _guard = TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let before = value(Metric::OutboxQueued);
        record(Metric::OutboxQueued);
        record_n(Metric::OutboxQueued, 4);
        assert_eq!(value(Metric::OutboxQueued), before + 5);
        assert_eq!(
            snapshot().get("outbox.queued").copied(),
            Some(before + 5),
            "snapshot uses the stable key"
        );
    }

    #[test]
    fn record_n_of_zero_is_a_no_op() {
        let _guard = TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let before = value(Metric::SyncServed);
        record_n(Metric::SyncServed, 0);
        assert_eq!(value(Metric::SyncServed), before);
    }

    #[test]
    fn keys_are_unique_and_identity_free() {
        let mut keys: Vec<&str> = Metric::ALL.iter().map(|m| m.key()).collect();
        let count = keys.len();
        keys.sort_unstable();
        keys.dedup();
        assert_eq!(keys.len(), count, "two metrics share a key");
        for key in keys {
            assert!(
                key.chars()
                    .all(|c| c.is_ascii_lowercase() || c == '.' || c == '_'),
                "metric key {key} looks like it carries data, not a name"
            );
        }
    }
}
