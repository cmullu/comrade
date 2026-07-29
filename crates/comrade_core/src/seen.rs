/*!
 * Deduplication seen-set — one bounded, optionally TTL-expiring "have I
 * already handled this?" gate, shared by every ingress path.
 *
 * Adopted from bitchat's `LRUDeduplicationCache` / `MessageDeduplicationService`
 * and its mesh dedup policy (a 1000-entry, 5-minute seen-set keyed by sender,
 * timestamp, type, and a payload digest). Comrade needs the same gate for the
 * same reasons:
 *
 *  • Nostr relays deliver **at least once**, and the Vault inbox re-subscribes
 *    with a multi-day lookback on every launch, so the same gift-wrapped event
 *    arrives repeatedly.
 *  • Gossipsub floods a mesh frame from several neighbours at once.
 *
 * Two shapes are provided:
 *  • [`SeenSet`] — exact keys (event ids, message ids, packet digests).
 *  • [`content_key`] — bitchat's `ContentNormalizer`, for *near*-duplicate
 *    suppression when the same human message reaches us twice over different
 *    transports (mesh copy and relay copy) with different ids.
 */

use std::{
    collections::{HashMap, VecDeque},
    sync::Mutex,
    time::{Duration, Instant},
};

use sha2::{Digest, Sha256};

/// A bounded set of keys already handled, with LRU eviction by insertion order
/// and optional per-entry expiry.
///
/// Interior-mutable (`&self` mutates) so a single instance can sit behind an
/// `Arc` and be shared by the relay callback, the mesh driver, and the UI
/// bridge without any of them needing a mutable handle.
pub struct SeenSet {
    capacity: usize,
    ttl: Option<Duration>,
    state: Mutex<SeenState>,
}

#[derive(Default)]
struct SeenState {
    /// Insertion order, oldest first — evicted from the front at capacity.
    order: VecDeque<String>,
    /// Key → when it was recorded, for TTL expiry.
    seen: HashMap<String, Instant>,
}

impl SeenSet {
    /// A set that only ever forgets by eviction (never by age).
    ///
    /// # Panics
    /// If `capacity` is zero — a zero-capacity set would silently accept every
    /// duplicate, which is worse than failing loudly at construction.
    pub fn new(capacity: usize) -> Self {
        assert!(capacity > 0, "SeenSet capacity must be positive");
        Self {
            capacity,
            ttl: None,
            state: Mutex::new(SeenState::default()),
        }
    }

    /// A set whose entries also expire after `ttl`, so a key can be re-accepted
    /// once the window it was suppressing has passed (bitchat's 5-minute mesh
    /// dedup window).
    ///
    /// # Panics
    /// If `capacity` is zero.
    pub fn with_ttl(capacity: usize, ttl: Duration) -> Self {
        let mut set = Self::new(capacity);
        set.ttl = Some(ttl);
        set
    }

    /// Record `key` and report whether it had already been seen.
    ///
    /// Returns `true` when the caller should **drop** the item as a duplicate.
    /// This is one atomic check-and-record: two threads racing on the same key
    /// cannot both be told it is new.
    pub fn already_seen(&self, key: &str) -> bool {
        let now = Instant::now();
        let mut state = self.lock();
        self.expire(&mut state, now);

        if state.seen.contains_key(key) {
            return true;
        }
        while state.order.len() >= self.capacity {
            match state.order.pop_front() {
                Some(oldest) => {
                    state.seen.remove(&oldest);
                }
                None => break,
            }
        }
        state.order.push_back(key.to_string());
        state.seen.insert(key.to_string(), now);
        false
    }

    /// Non-mutating membership check.
    pub fn contains(&self, key: &str) -> bool {
        let now = Instant::now();
        let mut state = self.lock();
        self.expire(&mut state, now);
        state.seen.contains_key(key)
    }

    /// Number of live (unexpired, unevicted) entries.
    pub fn len(&self) -> usize {
        let now = Instant::now();
        let mut state = self.lock();
        self.expire(&mut state, now);
        state.seen.len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Forget everything — part of the panic wipe, and of a scope switch (a
    /// new anonymous geohash channel should not inherit the last one's state).
    pub fn clear(&self) {
        let mut state = self.lock();
        state.order.clear();
        state.seen.clear();
    }

    /// Seed the set from a persisted snapshot (see [`Self::snapshot`]) — how a
    /// relaunch avoids reprocessing the events it already handled.
    pub fn restore(&self, keys: impl IntoIterator<Item = String>) {
        for key in keys {
            self.already_seen(&key);
        }
    }

    /// Live keys, oldest first — the shape [`Self::restore`] expects.
    pub fn snapshot(&self) -> Vec<String> {
        let now = Instant::now();
        let mut state = self.lock();
        self.expire(&mut state, now);
        state
            .order
            .iter()
            .filter(|k| state.seen.contains_key(*k))
            .cloned()
            .collect()
    }

    /// A poisoned mutex here means another thread panicked *while holding* a
    /// dedup set — the data is still structurally sound (plain collections),
    /// so recovering beats propagating a panic into a relay callback.
    fn lock(&self) -> std::sync::MutexGuard<'_, SeenState> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn expire(&self, state: &mut SeenState, now: Instant) {
        let Some(ttl) = self.ttl else { return };
        while let Some(oldest) = state.order.front() {
            match state.seen.get(oldest) {
                // Insertion order is also expiry order, so the first live
                // entry inside the window ends the sweep.
                Some(at) if now.duration_since(*at) < ttl => break,
                Some(_) => {
                    let key = state.order.pop_front().expect("front checked above");
                    state.seen.remove(&key);
                }
                // Already removed by eviction; drop the stale order entry.
                None => {
                    state.order.pop_front();
                }
            }
        }
    }
}

impl std::fmt::Debug for SeenSet {
    /// Keys are message/event identifiers — never print them.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SeenSet")
            .field("capacity", &self.capacity)
            .field("ttl", &self.ttl)
            .field("live", &self.len())
            .finish()
    }
}

/// Normalise message text into a short, stable key for *near*-duplicate
/// detection — bitchat's `ContentNormalizer`.
///
/// Case-folds, strips URL query strings and fragments (the same link shared
/// twice often differs only by tracking parameters), collapses whitespace,
/// truncates to `prefix_len` characters, and hashes. Returns a hex digest, so
/// the key can be stored or logged without carrying the message text.
pub fn content_key(content: &str, prefix_len: usize) -> String {
    let lowered = content.to_lowercase();
    let stripped = strip_url_queries(&lowered);
    let collapsed = stripped.split_whitespace().collect::<Vec<_>>().join(" ");
    let prefix: String = collapsed.chars().take(prefix_len).collect();

    let mut hasher = Sha256::new();
    hasher.update(prefix.as_bytes());
    // 16 hex chars is plenty for a dedup bucket and keeps the key short
    // enough to store thousands of them in the encrypted settings tree.
    hex::encode(&hasher.finalize()[..8])
}

/// Default prefix length for [`content_key`] — long enough that two different
/// messages rarely share a prefix, short enough that an edited tail (a typo
/// fix on a re-send) still collapses to the same key.
pub const CONTENT_KEY_PREFIX: usize = 128;

/// Cut `?query` / `#fragment` off anything that looks like an http(s) URL.
/// Deliberately hand-rolled rather than regex-driven: this runs on every
/// inbound message, and the rule is "scan to the first `?`/`#`/whitespace".
fn strip_url_queries(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let bytes = text.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        let rest = &text[i..];
        if rest.starts_with("http://") || rest.starts_with("https://") {
            let end = rest.find(|c: char| c.is_whitespace()).unwrap_or(rest.len());
            let url = &rest[..end];
            let keep = url.find(['?', '#']).unwrap_or(url.len());
            out.push_str(&url[..keep]);
            i += end;
        } else {
            let ch = text[i..].chars().next().expect("in-bounds index");
            out.push(ch);
            i += ch.len_utf8();
        }
    }
    out
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_sighting_is_new_second_is_duplicate() {
        let set = SeenSet::new(8);
        assert!(!set.already_seen("event-a"));
        assert!(set.already_seen("event-a"));
        assert!(!set.already_seen("event-b"));
        assert_eq!(set.len(), 2);
    }

    #[test]
    fn evicts_oldest_at_capacity() {
        let set = SeenSet::new(3);
        for key in ["a", "b", "c"] {
            assert!(!set.already_seen(key));
        }
        assert!(!set.already_seen("d"), "d is new");
        assert!(!set.contains("a"), "oldest entry must be evicted");
        assert!(set.contains("b") && set.contains("c") && set.contains("d"));
        assert_eq!(set.len(), 3, "capacity is never exceeded");
    }

    #[test]
    fn entries_expire_after_the_ttl() {
        let set = SeenSet::with_ttl(16, Duration::from_millis(30));
        assert!(!set.already_seen("packet"));
        assert!(set.already_seen("packet"), "still inside the window");
        std::thread::sleep(Duration::from_millis(50));
        assert!(
            !set.already_seen("packet"),
            "past the window the key is accepted again"
        );
    }

    #[test]
    fn snapshot_restore_survives_a_relaunch() {
        let before = SeenSet::new(64);
        for key in ["e1", "e2", "e3"] {
            before.already_seen(key);
        }
        let after = SeenSet::new(64);
        after.restore(before.snapshot());
        assert!(after.already_seen("e2"), "a persisted key stays deduped");
        assert!(!after.already_seen("e4"));
    }

    #[test]
    fn snapshot_keeps_insertion_order_so_restore_evicts_the_right_end() {
        let set = SeenSet::new(3);
        for key in ["a", "b", "c", "d"] {
            set.already_seen(key);
        }
        assert_eq!(set.snapshot(), vec!["b", "c", "d"]);
    }

    #[test]
    fn clear_forgets_everything() {
        let set = SeenSet::new(4);
        set.already_seen("x");
        set.clear();
        assert!(set.is_empty());
        assert!(!set.already_seen("x"));
    }

    #[test]
    fn content_key_ignores_case_whitespace_and_url_noise() {
        let a = content_key(
            "Look at  this\nhttps://x.test/a?utm_source=q",
            CONTENT_KEY_PREFIX,
        );
        let b = content_key(
            "look at this https://x.test/a?utm_source=other",
            CONTENT_KEY_PREFIX,
        );
        assert_eq!(a, b, "same message, different tracking params");

        let c = content_key("look at that https://x.test/a", CONTENT_KEY_PREFIX);
        assert_ne!(a, c, "different words must not collapse");
    }

    #[test]
    fn content_key_leaks_no_plaintext() {
        let key = content_key("my therapist appointment is at four", CONTENT_KEY_PREFIX);
        assert_eq!(key.len(), 16);
        assert!(key.chars().all(|c| c.is_ascii_hexdigit()));
        assert!(!key.contains("therapist"));
    }

    #[test]
    fn content_key_handles_multibyte_text() {
        // The URL scanner walks bytes; a Devanagari message must not panic or
        // slice mid-codepoint.
        let key = content_key("मैं ठीक हूँ https://x.test/a?b=1", CONTENT_KEY_PREFIX);
        assert_eq!(key.len(), 16);
    }
}
