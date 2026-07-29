/*!
 * Sender outbox — the message you sent while offline does not evaporate.
 *
 * Adopted from bitchat's `MessageRouter` outbox + `MessageOutboxStore`
 * (whitepaper §6.1): private messages without a prompt route are retained per
 * peer (100 messages/peer, 24 h TTL) and re-sent on reconnect until a delivery
 * or read receipt clears them, or an attempt cap drops them with a *visible*
 * failure.
 *
 * Comrade needs this badly. Today `send_dm` propagates a relay publish failure
 * straight to the caller: the text is gone, nothing is queued, and the only
 * record is a toast. For an app whose pitch is "stay close to the people who
 * hold you up", losing a message because a relay blinked is the worst possible
 * failure mode.
 *
 * This type is pure policy — no clocks, no I/O, no relays. Callers pass `now`
 * (unix seconds) and persist [`Outbox::snapshot`] into the encrypted store,
 * which is where Comrade improves on bitchat's design: bitchat needs a separate
 * ChaChaPoly seal and a Keychain key for the outbox because nothing else it
 * stores holds plaintext, whereas Comrade's whole store is already
 * Argon2id + AES-256-GCM sealed behind the passphrase.
 */

use std::{
    collections::{BTreeMap, BTreeSet},
    sync::Mutex,
};

use serde::{Deserialize, Serialize};

use crate::metrics::{self, Metric};

/// Retained messages per peer. Past this the oldest is dropped: a peer who has
/// been unreachable for a hundred messages is not coming back inside the TTL,
/// and an unbounded queue would grow into the encrypted store forever.
pub const MAX_PER_PEER: usize = 100;

/// How long a queued message is worth retrying. Matches the courier envelope
/// lifetime so the two halves of store-and-forward expire together.
pub const TTL_SECS: u64 = 24 * 60 * 60;

/// Send attempts before a message is dropped as failed. Bounded so a peer who
/// has genuinely gone away does not make every reconnect re-publish forever.
pub const MAX_ATTEMPTS: u8 = 8;

/// One message waiting for a transport that can carry it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct QueuedMessage {
    /// The id the receipt will name — a Nostr event id once published, or a
    /// locally minted id while the message has never reached a relay.
    pub message_id: String,
    /// Recipient, as an npub.
    pub peer_npub: String,
    pub content: String,
    /// NIP-10 parent when this is a reply.
    pub reply_to: Option<String>,
    /// When the user pressed send (unix seconds).
    pub queued_at: u64,
    /// Publish attempts so far.
    pub attempts: u8,
    /// Couriers already carrying a sealed copy, so a deposit retry adds new
    /// couriers instead of re-burning the ones that already have it.
    pub deposited_with: BTreeSet<String>,
}

impl QueuedMessage {
    pub fn new(
        message_id: impl Into<String>,
        peer_npub: impl Into<String>,
        content: impl Into<String>,
        reply_to: Option<String>,
        queued_at: u64,
    ) -> Self {
        Self {
            message_id: message_id.into(),
            peer_npub: peer_npub.into(),
            content: content.into(),
            reply_to,
            queued_at,
            attempts: 0,
            deposited_with: BTreeSet::new(),
        }
    }

    pub fn is_expired(&self, now_secs: u64) -> bool {
        now_secs.saturating_sub(self.queued_at) >= TTL_SECS
    }

    pub fn attempts_exhausted(&self) -> bool {
        self.attempts >= MAX_ATTEMPTS
    }
}

/// Prefix marking an id minted locally rather than assigned by a relay.
const LOCAL_ID_PREFIX: &str = "queued:";

/// Mint an id for a message that has never reached a relay, so it can be
/// stored, shown in the conversation, and later re-keyed to the event id the
/// relay finally assigns it.
///
/// The prefix keeps it unmistakable for a 64-hex Nostr event id; the random
/// nonce keeps two identical messages sent in the same second (a double tap on
/// send) from colliding.
pub fn local_message_id(peer_npub: &str, content: &str, at_secs: u64) -> String {
    let mut nonce = [0u8; 8];
    rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut nonce);
    let digest = crate::crypto::sha256_hex(
        format!("{peer_npub}|{content}|{at_secs}|{}", hex::encode(nonce)).as_bytes(),
    );
    format!("{LOCAL_ID_PREFIX}{}", &digest[..32])
}

/// Whether `id` was minted by [`local_message_id`] — i.e. the message is still
/// queued and has no relay-assigned event id yet.
pub fn is_local_message_id(id: &str) -> bool {
    id.starts_with(LOCAL_ID_PREFIX)
}

/// What happened to a message handed to [`Outbox::queue`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum QueueOutcome {
    /// Queued, nothing displaced.
    Queued,
    /// Queued, and the peer's oldest message was dropped to make room. The
    /// caller should mark that id failed in the UI — a silent drop would leave
    /// a message showing "sending" forever.
    Displaced(String),
    /// Already queued under this id; nothing changed.
    AlreadyQueued,
}

/// Result of recording a send attempt.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AttemptOutcome {
    /// Attempt recorded; the message stays queued for a later flush.
    Retained { attempts: u8 },
    /// The attempt cap is now reached and the message was removed. Surface a
    /// visible failure for this id.
    Exhausted,
    /// No such message (already acked or pruned).
    Unknown,
}

/// The persisted form of an outbox.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct OutboxSnapshot {
    /// Peer npub → queued messages, oldest first.
    pub queues: BTreeMap<String, Vec<QueuedMessage>>,
}

/// Per-peer queues of messages awaiting a transport.
///
/// Interior-mutable so the relay callback, the flush loop, and the UI bridge
/// can share one `Arc<Outbox>`.
#[derive(Default)]
pub struct Outbox {
    state: Mutex<BTreeMap<String, Vec<QueuedMessage>>>,
}

impl Outbox {
    pub fn new() -> Self {
        Self::default()
    }

    /// Restore from a snapshot loaded out of the encrypted store.
    pub fn from_snapshot(snapshot: OutboxSnapshot) -> Self {
        Self {
            state: Mutex::new(snapshot.queues),
        }
    }

    /// Queue a message for a peer we could not reach.
    pub fn queue(&self, message: QueuedMessage) -> QueueOutcome {
        let mut state = self.lock();
        let queue = state.entry(message.peer_npub.clone()).or_default();

        if queue.iter().any(|m| m.message_id == message.message_id) {
            return QueueOutcome::AlreadyQueued;
        }

        let displaced = if queue.len() >= MAX_PER_PEER {
            let dropped = queue.remove(0);
            metrics::record(Metric::OutboxDropped);
            Some(dropped.message_id)
        } else {
            None
        };

        queue.push(message);
        metrics::record(Metric::OutboxQueued);

        match displaced {
            Some(id) => QueueOutcome::Displaced(id),
            None => QueueOutcome::Queued,
        }
    }

    /// Messages worth sending now: not expired, attempts not exhausted, oldest
    /// first across all peers. Expired and exhausted entries are dropped as a
    /// side effect, so a flush loop never re-walks dead mail. Call
    /// [`Self::prune`] directly when the caller needs the dropped ids in order
    /// to mark them failed in the UI.
    pub fn due(&self, now_secs: u64) -> Vec<QueuedMessage> {
        self.prune(now_secs);
        let state = self.lock();
        let mut due: Vec<QueuedMessage> = state
            .values()
            .flat_map(|queue| queue.iter().cloned())
            .collect();
        due.sort_by_key(|m| (m.queued_at, m.message_id.clone()));
        due
    }

    /// Everything queued for one peer, oldest first.
    pub fn pending_for(&self, peer_npub: &str) -> Vec<QueuedMessage> {
        self.lock().get(peer_npub).cloned().unwrap_or_default()
    }

    /// Record that a send was attempted and failed.
    pub fn record_attempt(&self, peer_npub: &str, message_id: &str) -> AttemptOutcome {
        let mut state = self.lock();
        let Some(queue) = state.get_mut(peer_npub) else {
            return AttemptOutcome::Unknown;
        };
        let Some(index) = queue.iter().position(|m| m.message_id == message_id) else {
            return AttemptOutcome::Unknown;
        };

        queue[index].attempts = queue[index].attempts.saturating_add(1);
        metrics::record(Metric::OutboxResent);

        if queue[index].attempts_exhausted() {
            queue.remove(index);
            if queue.is_empty() {
                state.remove(peer_npub);
            }
            metrics::record(Metric::OutboxDropped);
            return AttemptOutcome::Exhausted;
        }
        AttemptOutcome::Retained {
            attempts: queue[index].attempts,
        }
    }

    /// A delivery or read receipt arrived from `peer_npub`: clear those ids.
    ///
    /// Deliberately **peer-scoped**. A receipt names ids the sender chose, so a
    /// global sweep would let one peer clear a colliding id queued for someone
    /// else — a bug bitchat had to fix in its own outbox.
    ///
    /// Returns the ids actually cleared.
    pub fn ack(&self, peer_npub: &str, message_ids: &[String]) -> Vec<String> {
        let mut state = self.lock();
        let Some(queue) = state.get_mut(peer_npub) else {
            return Vec::new();
        };
        let mut cleared = Vec::new();
        queue.retain(|m| {
            if message_ids.contains(&m.message_id) {
                cleared.push(m.message_id.clone());
                false
            } else {
                true
            }
        });
        if queue.is_empty() {
            state.remove(peer_npub);
        }
        metrics::record_n(Metric::OutboxDelivered, cleared.len() as u64);
        cleared
    }

    /// A publish finally succeeded and the relay assigned a real event id:
    /// re-key the queued entry so a later receipt (which names the *event* id)
    /// can clear it.
    ///
    /// Returns `false` if the message was no longer queued.
    pub fn reassign_id(&self, peer_npub: &str, old_id: &str, new_id: &str) -> bool {
        let mut state = self.lock();
        let Some(queue) = state.get_mut(peer_npub) else {
            return false;
        };
        match queue.iter_mut().find(|m| m.message_id == old_id) {
            Some(message) => {
                message.message_id = new_id.to_string();
                true
            }
            None => false,
        }
    }

    /// Note that `courier_npub` accepted a sealed copy of this message.
    /// Returns `false` when that courier already had it (so no metric is
    /// double-counted and no retry is wasted).
    pub fn note_courier(&self, peer_npub: &str, message_id: &str, courier_npub: &str) -> bool {
        let mut state = self.lock();
        let Some(queue) = state.get_mut(peer_npub) else {
            return false;
        };
        match queue.iter_mut().find(|m| m.message_id == message_id) {
            Some(message) => message.deposited_with.insert(courier_npub.to_string()),
            None => false,
        }
    }

    /// Drop expired and attempt-exhausted messages. Returns the dropped ids so
    /// the caller can mark them failed.
    pub fn prune(&self, now_secs: u64) -> Vec<String> {
        let mut state = self.lock();
        let mut dropped = Vec::new();
        for queue in state.values_mut() {
            queue.retain(|m| {
                if m.is_expired(now_secs) || m.attempts_exhausted() {
                    dropped.push(m.message_id.clone());
                    false
                } else {
                    true
                }
            });
        }
        state.retain(|_, queue| !queue.is_empty());
        metrics::record_n(Metric::OutboxDropped, dropped.len() as u64);
        dropped
    }

    /// Total queued messages across all peers.
    pub fn len(&self) -> usize {
        self.lock().values().map(Vec::len).sum()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Peers with something waiting.
    pub fn peers(&self) -> Vec<String> {
        self.lock().keys().cloned().collect()
    }

    /// Serialisable state for the encrypted store.
    pub fn snapshot(&self) -> OutboxSnapshot {
        OutboxSnapshot {
            queues: self.lock().clone(),
        }
    }

    /// Empty the outbox — part of the panic wipe.
    pub fn clear(&self) {
        self.lock().clear();
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, BTreeMap<String, Vec<QueuedMessage>>> {
        // A panic while holding this lock leaves plain collections behind;
        // recovering beats turning one panic into two on a send path.
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }
}

impl std::fmt::Debug for Outbox {
    /// Queued entries hold message plaintext — print shape only.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Read both counts under one guard: the lock is not reentrant, and
        // holding a guard across a second `lock()` in the same expression would
        // deadlock.
        let (peers, queued) = {
            let state = self.lock();
            (state.len(), state.values().map(Vec::len).sum::<usize>())
        };
        f.debug_struct("Outbox")
            .field("peers", &peers)
            .field("queued", &queued)
            .finish()
    }
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    const T0: u64 = 1_700_000_000;

    fn msg(id: &str, peer: &str, at: u64) -> QueuedMessage {
        QueuedMessage::new(id, peer, "i had a hard day", None, at)
    }

    #[test]
    fn queued_message_survives_and_is_returned_as_due() {
        let outbox = Outbox::new();
        assert_eq!(outbox.queue(msg("m1", "npub-a", T0)), QueueOutcome::Queued);
        let due = outbox.due(T0 + 60);
        assert_eq!(due.len(), 1);
        assert_eq!(due[0].message_id, "m1");
        assert_eq!(due[0].attempts, 0);
    }

    #[test]
    fn duplicate_queue_is_idempotent() {
        let outbox = Outbox::new();
        outbox.queue(msg("m1", "npub-a", T0));
        assert_eq!(
            outbox.queue(msg("m1", "npub-a", T0)),
            QueueOutcome::AlreadyQueued
        );
        assert_eq!(outbox.len(), 1);
    }

    #[test]
    fn per_peer_cap_displaces_the_oldest_and_names_it() {
        let outbox = Outbox::new();
        for i in 0..MAX_PER_PEER {
            outbox.queue(msg(&format!("m{i}"), "npub-a", T0 + i as u64));
        }
        let outcome = outbox.queue(msg("overflow", "npub-a", T0 + 999));
        assert_eq!(
            outcome,
            QueueOutcome::Displaced("m0".into()),
            "the caller must learn which message it lost"
        );
        assert_eq!(outbox.len(), MAX_PER_PEER);
    }

    #[test]
    fn cap_is_per_peer_not_global() {
        let outbox = Outbox::new();
        for i in 0..MAX_PER_PEER {
            outbox.queue(msg(&format!("a{i}"), "npub-a", T0 + i as u64));
        }
        assert_eq!(outbox.queue(msg("b0", "npub-b", T0)), QueueOutcome::Queued);
        assert_eq!(outbox.len(), MAX_PER_PEER + 1);
    }

    #[test]
    fn attempts_are_capped_then_the_message_is_dropped() {
        let outbox = Outbox::new();
        outbox.queue(msg("m1", "npub-a", T0));
        for attempt in 1..MAX_ATTEMPTS {
            assert_eq!(
                outbox.record_attempt("npub-a", "m1"),
                AttemptOutcome::Retained { attempts: attempt }
            );
        }
        assert_eq!(
            outbox.record_attempt("npub-a", "m1"),
            AttemptOutcome::Exhausted,
            "the {MAX_ATTEMPTS}th failure must surface, not retry silently"
        );
        assert!(outbox.is_empty());
        assert_eq!(
            outbox.record_attempt("npub-a", "m1"),
            AttemptOutcome::Unknown
        );
    }

    #[test]
    fn expired_messages_are_pruned_and_reported() {
        let outbox = Outbox::new();
        outbox.queue(msg("old", "npub-a", T0));
        outbox.queue(msg("fresh", "npub-a", T0 + TTL_SECS));
        let dropped = outbox.prune(T0 + TTL_SECS + 1);
        assert_eq!(dropped, vec!["old".to_string()]);
        assert_eq!(outbox.pending_for("npub-a").len(), 1);
    }

    #[test]
    fn a_receipt_clears_only_that_peers_messages() {
        let outbox = Outbox::new();
        // Same id queued for two peers — a global ack would clear both.
        outbox.queue(msg("shared-id", "npub-a", T0));
        outbox.queue(msg("shared-id", "npub-b", T0));

        let cleared = outbox.ack("npub-a", &["shared-id".to_string()]);
        assert_eq!(cleared, vec!["shared-id".to_string()]);
        assert!(outbox.pending_for("npub-a").is_empty());
        assert_eq!(
            outbox.pending_for("npub-b").len(),
            1,
            "another peer's queue must be untouched"
        );
    }

    #[test]
    fn ack_of_unknown_ids_is_harmless() {
        let outbox = Outbox::new();
        outbox.queue(msg("m1", "npub-a", T0));
        assert!(outbox.ack("npub-a", &["nope".into()]).is_empty());
        assert!(outbox.ack("npub-z", &["m1".into()]).is_empty());
        assert_eq!(outbox.len(), 1);
    }

    #[test]
    fn reassign_id_lets_a_later_receipt_match() {
        let outbox = Outbox::new();
        outbox.queue(msg("local-1", "npub-a", T0));
        assert!(outbox.reassign_id("npub-a", "local-1", "event-abc"));
        assert!(!outbox.reassign_id("npub-a", "local-1", "event-abc"));
        assert_eq!(
            outbox.ack("npub-a", &["event-abc".to_string()]),
            vec!["event-abc".to_string()]
        );
    }

    #[test]
    fn courier_bookkeeping_does_not_re_burn_the_same_courier() {
        let outbox = Outbox::new();
        outbox.queue(msg("m1", "npub-a", T0));
        assert!(outbox.note_courier("npub-a", "m1", "courier-1"));
        assert!(
            !outbox.note_courier("npub-a", "m1", "courier-1"),
            "a courier that already carries it is not a new deposit"
        );
        assert!(outbox.note_courier("npub-a", "m1", "courier-2"));
        assert_eq!(outbox.pending_for("npub-a")[0].deposited_with.len(), 2);
    }

    #[test]
    fn snapshot_roundtrips_through_serde() {
        let outbox = Outbox::new();
        outbox.queue(msg("m1", "npub-a", T0));
        outbox.record_attempt("npub-a", "m1");
        outbox.note_courier("npub-a", "m1", "courier-1");

        let json = serde_json::to_string(&outbox.snapshot()).expect("snapshot serialises");
        let restored: OutboxSnapshot = serde_json::from_str(&json).expect("snapshot parses");
        let after = Outbox::from_snapshot(restored);

        let pending = after.pending_for("npub-a");
        assert_eq!(pending.len(), 1);
        assert_eq!(
            pending[0].attempts, 1,
            "attempt count must survive a restart"
        );
        assert!(pending[0].deposited_with.contains("courier-1"));
    }

    #[test]
    fn due_is_oldest_first_across_peers() {
        let outbox = Outbox::new();
        outbox.queue(msg("second", "npub-b", T0 + 10));
        outbox.queue(msg("first", "npub-a", T0));
        let ids: Vec<String> = outbox
            .due(T0 + 100)
            .into_iter()
            .map(|m| m.message_id)
            .collect();
        assert_eq!(ids, vec!["first".to_string(), "second".to_string()]);
    }

    #[test]
    fn clear_empties_everything() {
        let outbox = Outbox::new();
        outbox.queue(msg("m1", "npub-a", T0));
        outbox.clear();
        assert!(outbox.is_empty());
        assert!(outbox.peers().is_empty());
    }

    #[test]
    fn local_ids_are_recognisable_and_unique() {
        let a = local_message_id("npub-a", "same text", T0);
        let b = local_message_id("npub-a", "same text", T0);
        assert!(is_local_message_id(&a) && is_local_message_id(&b));
        assert_ne!(a, b, "a double tap on send must not collide");
        assert!(
            !is_local_message_id(&"a".repeat(64)),
            "a relay event id is not a local id"
        );
        // Short enough to key a store row, long enough not to collide.
        assert_eq!(a.len(), "queued:".len() + 32);
    }

    #[test]
    fn debug_never_prints_message_content() {
        let outbox = Outbox::new();
        outbox.queue(QueuedMessage::new("m1", "npub-a", "a secret", None, T0));
        let rendered = format!("{outbox:?}");
        assert!(!rendered.contains("a secret"), "got {rendered}");
    }
}
