/*!
 * Milestone 3a — Sabha: Public Microblogging Engine ("Chitthi Feed")
 *
 * Connects to public Nostr relays, subscribes to Kind-1 text notes (each one a
 * public *Chitthi* — a letter to the world), and parses a flat unsorted stream
 * of events into a structured NIP-10 `ChitthiThread` using recursive
 * parent-child resolution.
 *
 * Nomenclature: at the application layer a public post is a **Chitthi** and a
 * reply tree is a **ChitthiThread**. The Nostr protocol constant `Kind::TextNote`
 * is left untouched — only the execution/timeline layer adopts the Chitthi name.
 */

use std::{collections::HashMap, sync::Arc};

use nostr_sdk::prelude::*;
use serde::{Deserialize, Serialize};
use tracing::{debug, info, warn};

use crate::{error::SabhaError, geo::Geohash};

// ── Location channels (adopted from bitchat) ─────────────────────────────────

/// Ephemeral kind carrying a note in a geohash location channel.
pub const KIND_GEOHASH_NOTE: u16 = 20_000;
/// Ephemeral kind carrying a bare presence heartbeat — no content, no name.
pub const KIND_GEOHASH_PRESENCE: u16 = 20_001;
/// Tag naming the geohash cell an event belongs to.
const GEOHASH_TAG: &str = "g";

/// Round a timestamp down to the hour.
///
/// Anonymous posts carry this instead of a to-the-second `created_at`, so an
/// observer cannot align a post with the rest of a device's traffic by exact
/// time. bitchat does the same thing to its seal/envelope timestamps (±15
/// minutes of jitter); truncation is used here instead of jitter because it also
/// makes every post in an hour share one value, and it can never land in the
/// future — which relays reject.
pub fn coarse_timestamp(now: Timestamp) -> Timestamp {
    const HOUR: u64 = 3_600;
    Timestamp::from(now.as_secs() / HOUR * HOUR)
}

/// Event for an anonymous Chitthi: a normal Kind-1 note with a coarsened
/// timestamp and no identity-bearing tags.
fn anonymous_chitthi_builder(content: &str, now: Timestamp) -> EventBuilder {
    EventBuilder::text_note(content).custom_created_at(coarse_timestamp(now))
}

/// Event for a note in a location channel.
fn geohash_note_builder(cell: &Geohash, content: &str) -> EventBuilder {
    EventBuilder::new(Kind::Custom(KIND_GEOHASH_NOTE), content).tags([geohash_tag(cell)])
}

/// Event for a presence heartbeat: same shape, empty content, no nickname.
fn geohash_presence_builder(cell: &Geohash) -> EventBuilder {
    EventBuilder::new(Kind::Custom(KIND_GEOHASH_PRESENCE), "").tags([geohash_tag(cell)])
}

/// Filter for one location channel's notes and heartbeats.
fn geohash_channel_filter(cell: &Geohash, since_secs: u64) -> Filter {
    Filter::new()
        .kinds([
            Kind::Custom(KIND_GEOHASH_NOTE),
            Kind::Custom(KIND_GEOHASH_PRESENCE),
        ])
        .custom_tags(SingleLetterTag::lowercase(Alphabet::G), [cell.to_string()])
        .since(Timestamp::now() - since_secs)
}

fn geohash_tag(cell: &Geohash) -> Tag {
    Tag::custom(
        TagKind::SingleLetter(SingleLetterTag::lowercase(Alphabet::G)),
        [cell.to_string()],
    )
}

/// The geohash cell an event belongs to, if it carries a valid `g` tag.
pub fn geohash_of(event: &Event) -> Option<Geohash> {
    event
        .tags
        .iter()
        .filter_map(|tag| {
            let slice = tag.as_slice();
            (slice.len() >= 2 && slice[0] == GEOHASH_TAG).then(|| slice[1].clone())
        })
        .find_map(|value| Geohash::parse(&value))
}

// ── Well-known public relays ─────────────────────────────────────────────────

pub const DEFAULT_RELAYS: &[&str] = &[
    "wss://relay.damus.io",
    "wss://relay.nostr.band",
    "wss://nos.lol",
];

/// Relays known to answer NIP-50 full-text `search` filters. Profile search
/// queries go to these directly: a non-search relay either ignores the
/// `search` field (returning arbitrary profiles) or returns nothing at all,
/// so fanning the query across the whole pool produces garbage or silence.
/// `relay.nostr.band` doubles as a normal read/write relay in
/// [`DEFAULT_RELAYS`], which also makes profiles *published* there findable.
pub const SEARCH_RELAYS: &[&str] = &["wss://relay.nostr.band", "wss://search.nos.today"];

/// How long to wait for at least one relay connection before a publish/fetch.
/// Covers the onboarding race where engines were just built and `connect()`
/// has only *initiated* the dials.
pub(crate) const CONNECT_WAIT: std::time::Duration = std::time::Duration::from_secs(5);

/// Wait (bounded) until at least **one** relay in the pool is connected.
/// Returns whether any relay is connected when it returns.
///
/// Deliberately not `Client::wait_for_connection`: that joins a per-relay
/// wait across the *whole* pool, so one flaky relay (e.g. a search relay
/// cycling reconnects) taxes every publish/fetch with the full timeout even
/// while the main relays are already up. One live relay is all a send needs.
pub(crate) async fn wait_for_any_relay(client: &Client, timeout: std::time::Duration) -> bool {
    let deadline = tokio::time::Instant::now() + timeout;
    loop {
        if client
            .relays()
            .await
            .values()
            .any(|r| r.status() == RelayStatus::Connected)
        {
            return true;
        }
        if tokio::time::Instant::now() >= deadline {
            return false;
        }
        tokio::time::sleep(std::time::Duration::from_millis(200)).await;
    }
}

// ── NIP-10 thread-tree structures ────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChitthiNode {
    /// The raw Nostr event at this tree position.
    pub event: Event,
    /// Zero-indexed depth from a root node.
    pub depth: usize,
    /// Direct replies to this node, sorted by created_at ascending.
    pub children: Vec<ChitthiNode>,
}

impl ChitthiNode {
    fn new(event: Event, depth: usize) -> Self {
        Self {
            event,
            depth,
            children: Vec::new(),
        }
    }
}

#[derive(Debug, Default, Clone)]
pub struct ChitthiThread {
    /// Top-level events — those that have no parent within the local set.
    pub roots: Vec<ChitthiNode>,
}

impl ChitthiThread {
    /// Total number of events across all levels.
    pub fn len(&self) -> usize {
        fn count(nodes: &[ChitthiNode]) -> usize {
            nodes.iter().map(|n| 1 + count(&n.children)).sum()
        }
        count(&self.roots)
    }

    pub fn is_empty(&self) -> bool {
        self.roots.is_empty()
    }
}

// ── Tag parsing helpers ──────────────────────────────────────────────────────

/// Extract NIP-10 "e" tags from an event using the canonical JSON representation.
///
/// Returns `(event_id_hex, relay_url, marker)` for each "e" tag where marker
/// is one of "root", "reply", "mention", or "" (positional).
fn extract_e_tags(event: &Event) -> Vec<(String, String, String)> {
    let val = match serde_json::to_value(event) {
        Ok(v) => v,
        Err(e) => {
            warn!("failed to serialise event for tag extraction: {e}");
            return Vec::new();
        }
    };

    let Some(tags_arr) = val.get("tags").and_then(|t| t.as_array()) else {
        return Vec::new();
    };

    let mut out = Vec::new();
    for tag in tags_arr {
        let Some(arr) = tag.as_array() else { continue };
        let kind = arr.first().and_then(|v| v.as_str()).unwrap_or("");
        if kind != "e" {
            continue;
        }
        let event_id = arr
            .get(1)
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let relay_url = arr
            .get(2)
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let marker = arr
            .get(3)
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();

        if !event_id.is_empty() {
            out.push((event_id, relay_url, marker));
        }
    }
    out
}

/// Determine the immediate parent event ID for a given event following NIP-10.
fn resolve_parent_id(event: &Event) -> Option<String> {
    let e_tags = extract_e_tags(event);
    if e_tags.is_empty() {
        return None;
    }
    if let Some((id, _, _)) = e_tags.iter().find(|(_, _, m)| m == "reply") {
        return Some(id.clone());
    }
    if let Some((id, _, _)) = e_tags.iter().find(|(_, _, m)| m == "root") {
        return Some(id.clone());
    }
    e_tags.last().map(|(id, _, _)| id.clone())
}

// ── Tree builder ─────────────────────────────────────────────────────────────

/// Transform a flat, unsorted vector of Kind-1 Nostr events into a structured
/// NIP-10 comment tree.
pub fn build_chitthi_thread(events: Vec<Event>) -> ChitthiThread {
    if events.is_empty() {
        return ChitthiThread::default();
    }

    let event_map: HashMap<String, Event> =
        events.iter().map(|e| (e.id.to_hex(), e.clone())).collect();

    let parent_of: HashMap<String, Option<String>> = event_map
        .keys()
        .map(|id| {
            let event = &event_map[id];
            let parent = resolve_parent_id(event).filter(|pid| event_map.contains_key(pid));
            (id.clone(), parent)
        })
        .collect();

    let mut children_of: HashMap<String, Vec<String>> = HashMap::new();
    let mut root_ids: Vec<String> = Vec::new();

    for (id, maybe_parent) in &parent_of {
        match maybe_parent {
            Some(pid) => children_of.entry(pid.clone()).or_default().push(id.clone()),
            None => root_ids.push(id.clone()),
        }
    }

    root_ids.sort_by_key(|id| event_map[id].created_at);

    fn build_node(
        id: &str,
        depth: usize,
        event_map: &HashMap<String, Event>,
        children_of: &HashMap<String, Vec<String>>,
    ) -> ChitthiNode {
        let event = event_map[id].clone();
        let mut node = ChitthiNode::new(event, depth);
        if let Some(child_ids) = children_of.get(id) {
            let mut sorted = child_ids.clone();
            sorted.sort_by_key(|cid| event_map[cid].created_at);
            for child_id in &sorted {
                node.children
                    .push(build_node(child_id, depth + 1, event_map, children_of));
            }
        }
        node
    }

    let roots = root_ids
        .iter()
        .map(|id| build_node(id, 0, &event_map, &children_of))
        .collect();

    ChitthiThread { roots }
}

// ── Feed subscription policy (AUDIT.md COMMS-04) ────────────────────────────
//
// `subscribe_chitthi_feed` used to take only a `since_secs` window, which
// meant "every Kind-1 note any pool relay has" — a relay-wide firehose with no
// author or count bound. [`FeedFilterSpec`] replaces that with an explicit,
// always-bounded product policy: every call site must pick one of
// [`FeedScope`]'s variants rather than getting the firehose as a silent
// default.

/// Which authors' notes the feed subscription matches. Never "everyone,
/// unbounded" — [`FeedScope::BoundedGlobal`] is the closest equivalent, and it
/// still caps the result count.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FeedScope {
    /// Only notes from these authors — the normal case: self plus accepted
    /// contacts. An empty vec is a valid (if unusual) choice that simply
    /// matches nothing; it is never silently widened to "everyone".
    Authors(Vec<PublicKey>),
    /// No author filter, but capped to the most recent `limit` events within
    /// the subscription's time window — for a fresh identity with no
    /// contacts yet. Still an explicit, bounded product choice, not a
    /// firehose: relays are free to return fewer, never asked for more.
    BoundedGlobal { limit: usize },
}

/// The full feed-subscription policy: a [`FeedScope`] plus how far back to
/// look. Constructed once per subscription (typically by
/// `comrade_ui::ComradeRuntime` from the caller's contact list) and consumed
/// by [`SabhaEngine::subscribe_chitthi_feed`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FeedFilterSpec {
    pub scope: FeedScope,
    pub since_secs: u64,
}

impl FeedFilterSpec {
    fn into_filter(self) -> Filter {
        let filter = Filter::new()
            .kind(Kind::TextNote)
            .since(Timestamp::now() - self.since_secs);
        match self.scope {
            FeedScope::Authors(authors) => filter.authors(authors),
            FeedScope::BoundedGlobal { limit } => filter.limit(limit),
        }
    }
}

// ── Sabha Engine ─────────────────────────────────────────────────────────────

pub type ChitthiCallback = Box<dyn Fn(Event) + Send + Sync + 'static>;

/// Connects to Nostr relays and streams Kind-1 text notes as public Chitthis.
pub struct SabhaEngine {
    client: Client,
    /// Our own public key — the author of everything this engine publishes.
    our_pk: PublicKey,
}

impl SabhaEngine {
    /// Connect to the default public relay set (see [`DEFAULT_RELAYS`]).
    pub async fn new(keys: &Keys) -> Result<Self, SabhaError> {
        Self::new_with_relays(keys, DEFAULT_RELAYS.iter().map(|r| r.to_string())).await
    }

    /// Connect to an explicit relay set instead of [`DEFAULT_RELAYS`] — the
    /// hook an isolated test environment (COMMS-03: a local relay, no public
    /// internet dependency) or a future "selected relays" product setting
    /// needs. Search relays ([`SEARCH_RELAYS`]) are unaffected: profile search
    /// is a separate, opt-in query path, not part of the main feed/publish set.
    pub async fn new_with_relays(
        keys: &Keys,
        relays: impl IntoIterator<Item = String>,
    ) -> Result<Self, SabhaError> {
        let our_pk = keys.public_key();
        let client = Client::new(keys.clone());
        let relays: Vec<String> = relays.into_iter().collect();
        for relay in &relays {
            client
                .add_relay(relay)
                .await
                .map_err(|e| SabhaError::RelayError(e.to_string()))?;
        }
        // Search relays join read-only: we query them for NIP-50 profile
        // lookups but never publish feed events or subscribe writes to them.
        for relay in SEARCH_RELAYS {
            if !relays.iter().any(|r| r == relay) {
                client
                    .add_read_relay(*relay)
                    .await
                    .map_err(|e| SabhaError::RelayError(e.to_string()))?;
            }
        }
        Ok(Self { client, our_pk })
    }

    pub async fn add_relay(&self, url: &str) -> Result<(), SabhaError> {
        self.client
            .add_relay(url)
            .await
            .map_err(|e| SabhaError::RelayError(e.to_string()))?;
        Ok(())
    }

    pub async fn connect(&self) {
        self.client.connect().await;
        info!("Sabha engine connected to public relays");
    }

    pub async fn disconnect(&self) {
        self.client.disconnect().await;
    }

    /// Broadcast a Chitthi (Kind-1 text note) to the public relay set.
    pub async fn broadcast_chitthi(&self, content: &str) -> Result<EventId, SabhaError> {
        self.broadcast_chitthi_reply(content, None).await
    }

    /// Broadcast a Chitthi, optionally as a NIP-10 reply to `reply_to`.
    ///
    /// When `reply_to` is `Some`, an `["e", <id>, "", "reply"]` tag is attached so
    /// relays and clients can thread the response under its parent. This is the
    /// engine entry point behind the IPC bridge's `broadcast_chitthi` command.
    pub async fn broadcast_chitthi_reply(
        &self,
        content: &str,
        reply_to: Option<EventId>,
    ) -> Result<EventId, SabhaError> {
        let mut builder = EventBuilder::text_note(content);
        if let Some(parent) = reply_to {
            let tag = Tag::parse(["e", parent.to_hex().as_str(), "", "reply"])
                .map_err(|e| SabhaError::ParseError(e.to_string()))?;
            builder = builder.tags([tag]);
        }
        // Same silent-void race as profile publishing: right after unlock the
        // dials may still be in flight, and the pool "succeeds" against zero
        // relays. Wait for one live relay and require an acceptance.
        wait_for_any_relay(&self.client, CONNECT_WAIT).await;
        let output = self
            .client
            .send_event_builder(builder)
            .await
            .map_err(|e| SabhaError::RelayError(e.to_string()))?;
        if output.success.is_empty() {
            return Err(SabhaError::RelayError(
                "no relay accepted the chitthi".into(),
            ));
        }
        info!(event_id = %output.id(), reply = reply_to.is_some(), "Chitthi broadcast to Sabha");
        Ok(*output.id())
    }

    /// Broadcast an **anonymous** Chitthi, signed by `signer` instead of by the
    /// device identity.
    ///
    /// Pass a throwaway key from [`crate::anon::ephemeral`] for a post that
    /// links to nothing (not even to your other anonymous posts), or a scoped
    /// persona from [`crate::anon::derive_scoped`] when replies need to reach
    /// the same pseudonym.
    ///
    /// The timestamp is coarsened to the hour ([`coarse_timestamp`]) so a
    /// relay-side observer cannot line a post up with your device's other
    /// traffic to the second. What this cannot hide is the network layer:
    /// connection timing and IP address still identify the *device* to the relay
    /// unless the socket goes through a proxy.
    pub async fn broadcast_anonymous_chitthi(
        &self,
        content: &str,
        signer: &Keys,
    ) -> Result<EventId, SabhaError> {
        let event = anonymous_chitthi_builder(content, Timestamp::now())
            .sign_with_keys(signer)
            .map_err(|e| SabhaError::ParseError(e.to_string()))?;
        self.send_signed(event, "anonymous chitthi").await
    }

    /// Publish a note into a geohash location channel under a per-cell persona.
    pub async fn publish_geohash_note(
        &self,
        cell: &Geohash,
        content: &str,
        signer: &Keys,
    ) -> Result<EventId, SabhaError> {
        let event = geohash_note_builder(cell, content)
            .sign_with_keys(signer)
            .map_err(|e| SabhaError::ParseError(e.to_string()))?;
        self.send_signed(event, "geohash note").await
    }

    /// Announce presence in a location channel (an empty ephemeral heartbeat).
    ///
    /// Refuses fine-precision cells: a heartbeat is a public "I am here", and
    /// past city precision that is a location disclosure rather than a room
    /// count (see [`crate::geo::Precision::presence_allowed`]).
    pub async fn publish_geohash_presence(
        &self,
        cell: &Geohash,
        signer: &Keys,
    ) -> Result<EventId, SabhaError> {
        if !cell.presence_allowed() {
            return Err(SabhaError::ParseError(format!(
                "presence is not broadcast at {} precision",
                cell.as_str().len()
            )));
        }
        let event = geohash_presence_builder(cell)
            .sign_with_keys(signer)
            .map_err(|e| SabhaError::ParseError(e.to_string()))?;
        self.send_signed(event, "geohash presence").await
    }

    /// Subscribe to a location channel: notes **and** presence heartbeats, so a
    /// participant count reflects listeners as well as speakers.
    pub async fn subscribe_geohash_channel(
        &self,
        cell: &Geohash,
        since_secs: u64,
        callback: ChitthiCallback,
    ) -> Result<(), SabhaError> {
        let filter = geohash_channel_filter(cell, since_secs);
        self.client
            .subscribe(filter, None)
            .await
            .map_err(|e| SabhaError::SubscriptionError(e.to_string()))?;
        info!(cell = %cell, "subscribed to geohash channel");

        let cb = Arc::new(callback);
        self.client
            .handle_notifications(move |notification| {
                let cb = cb.clone();
                async move {
                    if let RelayPoolNotification::Event { event, .. } = notification {
                        cb(*event);
                    }
                    Ok(false)
                }
            })
            .await
            .map_err(|e| SabhaError::SubscriptionError(e.to_string()))?;
        Ok(())
    }

    /// Shared publish tail: wait for a live relay, require an acceptance.
    async fn send_signed(&self, event: Event, what: &str) -> Result<EventId, SabhaError> {
        wait_for_any_relay(&self.client, CONNECT_WAIT).await;
        let output = self
            .client
            .send_event(&event)
            .await
            .map_err(|e| SabhaError::RelayError(e.to_string()))?;
        if output.success.is_empty() {
            return Err(SabhaError::RelayError(format!(
                "no relay accepted the {what}"
            )));
        }
        info!(event_id = %output.id(), kind = %event.kind, "{what} published");
        Ok(*output.id())
    }

    /// Publish (or update) our public profile — a Kind-0 metadata event carrying
    /// the chosen @handle — so peers can discover this identity by name.
    ///
    /// Kind-0 is *replaceable* (newest wins), and this runs on every launch —
    /// so the current published profile is fetched first and only the handle
    /// fields are overwritten. Without the merge, a user who set a bio/avatar
    /// from another Nostr client on the same keypair would have it wiped by
    /// every Comrade start.
    ///
    /// Handles are display names, not unique identifiers: the network cannot
    /// stop two people from publishing the same handle. Identity remains the
    /// keypair; callers must always bind contacts to the npub, never the handle.
    pub async fn publish_profile(
        &self,
        name: &str,
        about: Option<&str>,
    ) -> Result<EventId, SabhaError> {
        // `None` has always meant "don't touch the published bio", which is
        // exactly `Leave` — so the old signature keeps its old meaning and every
        // existing caller keeps its behaviour.
        self.publish_profile_patch(ProfilePatch {
            name,
            about: about.map_or(MetadataEdit::Leave, MetadataEdit::Set),
            picture: MetadataEdit::Leave,
        })
        .await
    }

    /// Publish our Kind-0 profile, saying explicitly which fields to touch.
    ///
    /// The reason this exists alongside [`Self::publish_profile`]: `Option<&str>`
    /// cannot express both "leave what is published alone" and "remove it", and
    /// both are needed. Launch republishes must leave a bio set from another
    /// client untouched; a user clearing their own bio must actually clear it.
    /// With one `Option` those are the same value.
    pub async fn publish_profile_patch(
        &self,
        patch: ProfilePatch<'_>,
    ) -> Result<EventId, SabhaError> {
        let name = patch.name;
        // At onboarding this runs moments after `connect()` merely *initiated*
        // the relay dials; sending immediately would reach zero relays and the
        // handle would silently never become discoverable. Wait (bounded) for
        // at least one live connection first — a no-op once connected.
        wait_for_any_relay(&self.client, CONNECT_WAIT).await;
        // Best-effort: a missing/unreachable current profile merges from empty.
        let existing = self.fetch_profile(&self.our_pk).await.unwrap_or_default();
        let metadata = merged_metadata(existing, &patch);
        let output = self
            .client
            .send_event_builder(EventBuilder::metadata(&metadata))
            .await
            .map_err(|e| SabhaError::RelayError(e.to_string()))?;
        if output.success.is_empty() {
            return Err(SabhaError::RelayError(
                "no relay accepted the profile event".into(),
            ));
        }
        info!(event_id = %output.id(), name, relays = output.success.len(), "profile published");
        Ok(*output.id())
    }

    /// Fetch a single author's newest Kind-0 profile from the relay pool.
    /// `None` means no relay knew a profile for that key.
    pub async fn fetch_profile(&self, author: &PublicKey) -> Result<Option<Metadata>, SabhaError> {
        let mut found = self.fetch_profiles(std::slice::from_ref(author)).await?;
        Ok(found.remove(author))
    }

    /// Fetch the newest Kind-0 profile for each of `authors` in **one** relay
    /// round-trip. Authors with no known profile are absent from the map.
    pub async fn fetch_profiles(
        &self,
        authors: &[PublicKey],
    ) -> Result<HashMap<PublicKey, Metadata>, SabhaError> {
        if authors.is_empty() {
            return Ok(HashMap::new());
        }
        let filter = Filter::new()
            .kind(Kind::Metadata)
            .authors(authors.iter().copied())
            .limit(authors.len() * 2);
        wait_for_any_relay(&self.client, CONNECT_WAIT).await;
        let events = self
            .client
            .fetch_events(filter, std::time::Duration::from_secs(8))
            .await
            .map_err(|e| SabhaError::RelayError(e.to_string()))?;
        let wanted: std::collections::HashSet<&PublicKey> = authors.iter().collect();
        Ok(newest_metadata_per_author(
            events.into_iter().filter(|e| wanted.contains(&e.pubkey)),
        ))
    }

    /// Best-effort people search: query Kind-0 profiles via NIP-50 full-text
    /// search, directed at the [`SEARCH_RELAYS`] that actually implement it.
    ///
    /// Results are additionally filtered client-side against the query — a
    /// relay that ignores the `search` field would otherwise inject arbitrary
    /// unrelated profiles into the list.
    ///
    /// Returns at most `limit` profiles, newest metadata per author, as
    /// `(author, metadata)` pairs. An empty result is normal — it means no
    /// search-capable relay knew the handle, not that the person doesn't exist.
    pub async fn search_profiles(
        &self,
        query: &str,
        limit: usize,
    ) -> Result<Vec<(PublicKey, Metadata)>, SabhaError> {
        let filter = Filter::new()
            .kind(Kind::Metadata)
            .search(query)
            .limit(limit * 3); // headroom: duplicates collapse per author below
        wait_for_any_relay(&self.client, CONNECT_WAIT).await;
        // Owned URLs, not a borrowed slice iterator: the borrowed form trips
        // rustc's higher-ranked auto-trait check inside the generic
        // `fetch_events_from` future, making it non-Send — which the Tauri
        // command layer requires (desktop clippy lane caught this).
        let search_relays: Vec<String> = SEARCH_RELAYS.iter().map(|r| r.to_string()).collect();
        let events = self
            .client
            .fetch_events_from(search_relays, filter, std::time::Duration::from_secs(8))
            .await
            .map_err(|e| SabhaError::RelayError(e.to_string()))?;

        let mut profiles: Vec<(PublicKey, Metadata)> = newest_metadata_per_author(events)
            .into_iter()
            .filter(|(_, m)| metadata_matches(m, query))
            .collect();
        profiles.sort_by_key(|a| profile_sort_key(&a.1));
        profiles.truncate(limit);
        Ok(profiles)
    }

    /// Subscribe to the Chitthi feed under an explicit, bounded [`FeedFilterSpec`]
    /// — never the relay-wide firehose (AUDIT.md COMMS-04). See [`FeedScope`]
    /// for the two shapes a caller can pick.
    pub async fn subscribe_chitthi_feed(
        &self,
        spec: FeedFilterSpec,
        callback: ChitthiCallback,
    ) -> Result<(), SabhaError> {
        let filter = spec.into_filter();

        self.client
            .subscribe(filter, None)
            .await
            .map_err(|e| SabhaError::SubscriptionError(e.to_string()))?;

        info!("Chitthi feed subscription active");

        let callback = Arc::new(callback);
        self.client
            .handle_notifications(move |notification| {
                let cb = callback.clone();
                async move {
                    if let RelayPoolNotification::Event { event, .. } = notification {
                        if event.kind == Kind::TextNote {
                            debug!(event_id = %event.id, "Chitthi received");
                            cb(*event);
                        }
                    }
                    Ok::<bool, Box<dyn std::error::Error>>(false)
                }
            })
            .await
            .map_err(|e| SabhaError::SubscriptionError(e.to_string()))
    }
}

/// Stable ordering for search results: named profiles first, then by name.
fn profile_sort_key(m: &Metadata) -> String {
    m.name.clone().unwrap_or_else(|| "\u{10FFFF}".to_string())
}

/// The one displayable-handle rule: `name`, else `display_name`. Every layer
/// that titles a peer by their published profile goes through this, so the
/// search list and the chat list can never disagree about the same Kind-0.
pub fn display_name_of(m: &Metadata) -> Option<String> {
    m.name.clone().or_else(|| m.display_name.clone())
}

/// Reduce a stream of Kind-0 events to the newest parseable Metadata per
/// author. Shared by search and (batch) profile fetch so tie-breaking and
/// parse-failure handling cannot drift between the two paths.
fn newest_metadata_per_author(
    events: impl IntoIterator<Item = Event>,
) -> HashMap<PublicKey, Metadata> {
    let mut newest: HashMap<PublicKey, Event> = HashMap::new();
    for event in events {
        match newest.get(&event.pubkey) {
            Some(existing) if existing.created_at >= event.created_at => {}
            _ => {
                newest.insert(event.pubkey, event);
            }
        }
    }
    newest
        .into_values()
        .filter_map(|e| Metadata::from_json(&e.content).ok().map(|m| (e.pubkey, m)))
        .collect()
}

/// What to do with one field of a published profile.
///
/// Three states rather than an `Option`, because "leave it alone" and "remove
/// it" are different intentions with the same `None`. Kind-0 replaces wholesale,
/// so conflating them means either a launch wipes a bio somebody set elsewhere,
/// or a user can never clear one. Both have to be expressible.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum MetadataEdit<'a> {
    /// Keep whatever is currently published, whoever published it.
    #[default]
    Leave,
    /// Replace it with this.
    Set(&'a str),
    /// Remove the field.
    Clear,
}

impl MetadataEdit<'_> {
    /// Apply this edit to one field of the metadata being built.
    fn apply(self, field: &mut Option<String>) {
        match self {
            MetadataEdit::Leave => {}
            MetadataEdit::Set(value) => *field = Some(value.to_string()),
            MetadataEdit::Clear => *field = None,
        }
    }
}

/// Which fields of our published profile to write, and how.
#[derive(Debug, Clone, Copy)]
pub struct ProfilePatch<'a> {
    /// The @handle. Always written — it is the field Comrade owns, and every
    /// caller has one.
    pub name: &'a str,
    pub about: MetadataEdit<'a>,
    pub picture: MetadataEdit<'a>,
}

/// Overlay the patch onto the currently published profile, preserving every
/// field it says nothing about (banner, nip05, lud16, custom fields, …) —
/// Kind-0 replaces wholesale, so publishing a stub would wipe whatever the user
/// set from other Nostr clients on the same keypair.
fn merged_metadata(existing: Option<Metadata>, patch: &ProfilePatch<'_>) -> Metadata {
    let mut metadata = existing.unwrap_or_default();
    metadata.name = Some(patch.name.to_string());
    metadata.display_name = Some(patch.name.to_string());
    patch.about.apply(&mut metadata.about);
    patch.picture.apply(&mut metadata.picture);
    metadata
}

/// Whether a profile plausibly matches the search query. Guards against
/// relays that ignore the NIP-50 `search` field and return arbitrary Kind-0
/// events. `about` is included so legitimate relay-side bio matches (NIP-50
/// searches all content) survive the client-side check.
fn metadata_matches(m: &Metadata, query: &str) -> bool {
    let q = query.trim().trim_start_matches('@').to_lowercase();
    if q.is_empty() {
        return false;
    }
    [
        m.name.as_deref(),
        m.display_name.as_deref(),
        m.nip05.as_deref(),
        m.about.as_deref(),
    ]
    .into_iter()
    .flatten()
    .any(|v| v.to_lowercase().contains(&q))
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn make_bare_event(parent_hex: Option<&str>, marker: Option<&str>) -> Event {
        let keys = Keys::generate();
        let mut builder = EventBuilder::new(Kind::TextNote, "test content");

        if let Some(pid) = parent_hex {
            let eid = EventId::from_hex(pid).unwrap();
            if let Some(m) = marker {
                // Build a tagged event: ["e", "<pid>", "", "<marker>"]
                if let Ok(t) = Tag::parse(["e", pid, "", m]) {
                    builder = builder.tag(t);
                } else {
                    builder = builder.tag(Tag::event(eid));
                }
            } else {
                builder = builder.tag(Tag::event(eid));
            }
        }

        builder.sign_with_keys(&keys).unwrap()
    }

    #[test]
    fn empty_input_gives_empty_tree() {
        let tree = build_chitthi_thread(vec![]);
        assert!(tree.is_empty());
    }

    #[test]
    fn single_event_becomes_root() {
        let event = make_bare_event(None, None);
        let tree = build_chitthi_thread(vec![event]);
        assert_eq!(tree.roots.len(), 1);
        assert_eq!(tree.len(), 1);
    }

    #[test]
    fn parent_child_depth_is_correct() {
        let root = make_bare_event(None, None);
        let root_id = root.id.to_hex();

        let child = make_bare_event(Some(&root_id), Some("reply"));
        let tree = build_chitthi_thread(vec![child, root]);

        assert_eq!(tree.roots.len(), 1);
        assert_eq!(tree.roots[0].children.len(), 1);
        assert_eq!(tree.roots[0].children[0].depth, 1);
        assert_eq!(tree.len(), 2);
    }

    #[test]
    fn orphan_events_become_independent_roots() {
        let orphan_id = "a".repeat(64);
        let child = make_bare_event(Some(&orphan_id), Some("reply"));
        let tree = build_chitthi_thread(vec![child]);
        assert_eq!(tree.roots.len(), 1);
    }

    #[test]
    fn metadata_match_guards_against_non_search_relays() {
        let named = Metadata::new().name("charlie");
        assert!(metadata_matches(&named, "charlie"));
        assert!(metadata_matches(&named, "@Charlie"), "case + @ insensitive");
        assert!(metadata_matches(&named, "charl"), "substring matches");
        assert!(
            !metadata_matches(&named, "bob"),
            "unrelated profile dropped"
        );
        assert!(
            !metadata_matches(&named, "  @ "),
            "blank query never matches"
        );

        let display_only = Metadata::new().display_name("Charlie B");
        assert!(metadata_matches(&display_only, "charlie"));

        // NIP-50 searches all profile content — a legitimate relay match on
        // the bio must survive the client-side guard.
        let bio = Metadata::new().name("sunita_r").about("climate researcher");
        assert!(metadata_matches(&bio, "climate"));

        let unrelated = Metadata::new().about("gardening");
        assert!(
            !metadata_matches(&unrelated, "charlie"),
            "profiles matching nowhere are dropped"
        );
    }

    #[test]
    fn merged_metadata_preserves_foreign_profile_fields() {
        // Kind-0 replaces wholesale: republishing the handle must not wipe a
        // bio/avatar/nip05 the user set from another client on this keypair.
        let mut published = Metadata::new()
            .name("old_name")
            .about("my bio")
            .nip05("me@example.com");
        published.picture = Some("https://example.com/me.png".into());
        published.lud16 = Some("me@wallet.example".into());

        let merged = merged_metadata(
            Some(published),
            &ProfilePatch {
                name: "new_name",
                about: MetadataEdit::Leave,
                picture: MetadataEdit::Leave,
            },
        );
        assert_eq!(merged.name.as_deref(), Some("new_name"));
        assert_eq!(merged.display_name.as_deref(), Some("new_name"));
        assert_eq!(merged.about.as_deref(), Some("my bio"), "bio preserved");
        assert_eq!(
            merged.picture.as_deref(),
            Some("https://example.com/me.png"),
            "avatar preserved"
        );
        assert_eq!(merged.nip05.as_deref(), Some("me@example.com"));
        assert_eq!(merged.lud16.as_deref(), Some("me@wallet.example"));

        // No published profile yet → clean two-field start (+ about if given).
        let fresh = merged_metadata(
            None,
            &ProfilePatch {
                name: "charlie",
                about: MetadataEdit::Set("hi"),
                picture: MetadataEdit::Leave,
            },
        );
        assert_eq!(fresh.name.as_deref(), Some("charlie"));
        assert_eq!(fresh.about.as_deref(), Some("hi"));
        assert_eq!(fresh.picture, None);
    }

    #[test]
    fn leave_set_and_clear_are_three_distinct_outcomes() {
        // The reason `Option<&str>` was not enough: "don't touch it" and "remove
        // it" both used to be `None`, so one of them was unreachable. A launch
        // republish needs the first; a user clearing their bio needs the second.
        let published = || {
            let mut m = Metadata::new().name("me").about("my bio");
            m.picture = Some("https://example.com/me.png".into());
            Some(m)
        };

        let left = merged_metadata(
            published(),
            &ProfilePatch {
                name: "me",
                about: MetadataEdit::Leave,
                picture: MetadataEdit::Leave,
            },
        );
        assert_eq!(
            left.about.as_deref(),
            Some("my bio"),
            "Leave must not touch it"
        );
        assert_eq!(left.picture.as_deref(), Some("https://example.com/me.png"));

        let set = merged_metadata(
            published(),
            &ProfilePatch {
                name: "me",
                about: MetadataEdit::Set("new bio"),
                picture: MetadataEdit::Set("https://example.com/new.png"),
            },
        );
        assert_eq!(set.about.as_deref(), Some("new bio"));
        assert_eq!(set.picture.as_deref(), Some("https://example.com/new.png"));

        let cleared = merged_metadata(
            published(),
            &ProfilePatch {
                name: "me",
                about: MetadataEdit::Clear,
                picture: MetadataEdit::Clear,
            },
        );
        assert_eq!(cleared.about, None, "Clear must actually remove the field");
        assert_eq!(cleared.picture, None);
        // Clearing one field must not disturb the other, or "remove my picture"
        // silently becomes "remove my picture and my bio".
        let one = merged_metadata(
            published(),
            &ProfilePatch {
                name: "me",
                about: MetadataEdit::Leave,
                picture: MetadataEdit::Clear,
            },
        );
        assert_eq!(one.about.as_deref(), Some("my bio"));
        assert_eq!(one.picture, None);
    }

    #[test]
    fn the_old_publish_profile_signature_keeps_its_old_meaning() {
        // `publish_profile(name, None)` has always meant "leave the bio alone".
        // If that ever became `Clear`, every launch would wipe a bio set from
        // another client — the exact bug the merge exists to prevent.
        assert_eq!(
            Option::<&str>::None.map_or(MetadataEdit::Leave, MetadataEdit::Set),
            MetadataEdit::Leave
        );
        assert_eq!(
            Some("hi").map_or(MetadataEdit::Leave, MetadataEdit::Set),
            MetadataEdit::Set("hi")
        );
    }

    #[test]
    fn newest_metadata_per_author_keeps_latest_kind0() {
        let keys = Keys::generate();
        let old = EventBuilder::metadata(&Metadata::new().name("old"))
            .custom_created_at(Timestamp::from(100))
            .sign_with_keys(&keys)
            .unwrap();
        let new = EventBuilder::metadata(&Metadata::new().name("new"))
            .custom_created_at(Timestamp::from(200))
            .sign_with_keys(&keys)
            .unwrap();
        let other = EventBuilder::metadata(&Metadata::new().name("other"))
            .custom_created_at(Timestamp::from(150))
            .sign_with_keys(&Keys::generate())
            .unwrap();

        let map = newest_metadata_per_author(vec![new.clone(), old, other]);
        assert_eq!(map.len(), 2);
        assert_eq!(map[&keys.public_key()].name.as_deref(), Some("new"));
    }

    #[test]
    fn display_name_prefers_name_over_display_name() {
        let both = Metadata::new().name("handle").display_name("Fancy Name");
        assert_eq!(display_name_of(&both).as_deref(), Some("handle"));
        let display_only = Metadata::new().display_name("Fancy Name");
        assert_eq!(
            display_name_of(&display_only).as_deref(),
            Some("Fancy Name")
        );
        assert_eq!(display_name_of(&Metadata::new()), None);
    }

    // ── Anonymous posting + location channels ───────────────────────────────

    #[test]
    fn anonymous_chitthi_is_signed_by_the_throwaway_key_only() {
        let identity = Keys::generate();
        let throwaway = crate::anon::ephemeral();
        let event = anonymous_chitthi_builder("i am not ok today", Timestamp::now())
            .sign_with_keys(&throwaway)
            .unwrap();

        assert_eq!(event.pubkey, throwaway.public_key());
        assert_ne!(
            event.pubkey,
            identity.public_key(),
            "an anonymous post must never carry the identity key"
        );
        assert!(event.verify().is_ok());
        assert!(
            event.tags.is_empty(),
            "no tags: a tag is another correlation handle"
        );
    }

    #[test]
    fn anonymous_chitthi_timestamp_is_coarsened_to_the_hour() {
        let now = Timestamp::from(1_700_003_671); // 01:14:31 past the hour
        let event = anonymous_chitthi_builder("x", now)
            .sign_with_keys(&crate::anon::ephemeral())
            .unwrap();
        assert_eq!(event.created_at.as_secs() % 3_600, 0);
        assert!(
            event.created_at <= now,
            "a coarsened stamp must never be in the future — relays reject that"
        );
        assert_eq!(coarse_timestamp(now).as_secs(), 1_700_002_800);
    }

    #[test]
    fn two_anonymous_posts_are_unlinkable() {
        let one = anonymous_chitthi_builder("a", Timestamp::now())
            .sign_with_keys(&crate::anon::ephemeral())
            .unwrap();
        let two = anonymous_chitthi_builder("b", Timestamp::now())
            .sign_with_keys(&crate::anon::ephemeral())
            .unwrap();
        assert_ne!(one.pubkey, two.pubkey);
    }

    #[test]
    fn geohash_note_carries_the_cell_tag_and_ephemeral_kind() {
        let cell = Geohash::parse("tdr1w").unwrap();
        let event = geohash_note_builder(&cell, "anyone around?")
            .sign_with_keys(&Keys::generate())
            .unwrap();
        assert_eq!(event.kind, Kind::Custom(KIND_GEOHASH_NOTE));
        assert_eq!(geohash_of(&event).as_ref(), Some(&cell));
        assert_eq!(event.content, "anyone around?");
    }

    #[test]
    fn presence_heartbeat_carries_no_content_or_nickname() {
        let cell = Geohash::parse("tdr1").unwrap();
        let event = geohash_presence_builder(&cell)
            .sign_with_keys(&Keys::generate())
            .unwrap();
        assert_eq!(event.kind, Kind::Custom(KIND_GEOHASH_PRESENCE));
        assert!(
            event.content.is_empty(),
            "a heartbeat is liveness, not data"
        );
        assert_eq!(event.tags.len(), 1, "the cell tag and nothing else");
        assert_eq!(geohash_of(&event).as_ref(), Some(&cell));
    }

    #[test]
    fn geohash_of_ignores_events_without_a_valid_cell() {
        let plain = EventBuilder::text_note("no cell")
            .sign_with_keys(&Keys::generate())
            .unwrap();
        assert_eq!(geohash_of(&plain), None);

        let bogus = EventBuilder::new(Kind::Custom(KIND_GEOHASH_NOTE), "x")
            .tags([Tag::custom(
                TagKind::SingleLetter(SingleLetterTag::lowercase(Alphabet::G)),
                ["not a geohash!"],
            )])
            .sign_with_keys(&Keys::generate())
            .unwrap();
        assert_eq!(geohash_of(&bogus), None);
    }

    #[test]
    fn channel_filter_targets_both_kinds_of_the_cell() {
        let cell = Geohash::parse("tdr1").unwrap();
        let filter = geohash_channel_filter(&cell, 900);
        let kinds = filter.kinds.clone().expect("kinds are set");
        assert!(kinds.contains(&Kind::Custom(KIND_GEOHASH_NOTE)));
        assert!(kinds.contains(&Kind::Custom(KIND_GEOHASH_PRESENCE)));
        // The cell must ride in the `g` tag filter, not in the content.
        let note = geohash_note_builder(&cell, "hello")
            .sign_with_keys(&Keys::generate())
            .unwrap();
        assert!(
            filter.match_event(&note, MatchEventOptions::default()),
            "the filter must match its channel"
        );

        let elsewhere = Geohash::parse("u4pr").unwrap();
        let other = geohash_note_builder(&elsewhere, "hello")
            .sign_with_keys(&Keys::generate())
            .unwrap();
        assert!(
            !filter.match_event(&other, MatchEventOptions::default()),
            "another cell must not match"
        );
    }
}
