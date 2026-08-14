/*!
 * topic — naming a subject inside a conversation, and the threads filed under it.
 *
 * Telegram's forum topics and Slack's threads, for a two-person chat. A
 * **thread** is a message and everything that replied into it; a **topic** is a
 * name you can file threads under, so "the flat deposit" stops being forty
 * messages scattered through six months of chat.
 *
 * The wire is the same NIP-44/NIP-17 gift-wrapped DM channel as receipts,
 * tasks, call signals, presence beacons and together envelopes
 * ([`crate::dm`], [`crate::karya`], [`crate::call`], [`crate::presence`],
 * [`crate::together`]), with its own marker key so the inbox dispatcher can
 * fall through to its other handlers.
 *
 * Pure and framework-free like its neighbours: the slug rules, the wire shape
 * and the reply-graph walk live here and are unit-tested. Persistence is
 * `comrade_storage`, the session bookkeeping is `comrade_ui`, and the sheets
 * are the frontends'.
 *
 * ## The slug is the id
 *
 * Both people can type `/assign #deposit` in the same conversation before
 * either envelope lands, and a random id per creation would leave them filing
 * into two different topics with the same name — a split neither side can see,
 * which is worse than a name collision. So a topic is keyed by its
 * [`slugify`]d name within the conversation, and creation is idempotent by
 * construction: the same word reaches the same topic on both phones with no
 * merge, no tie-break, and no clock.
 *
 * The cost is the honest one and worth stating: **a topic cannot be renamed
 * into a different word.** [`TopicSignal::Create`] carries a display `name`
 * beside the slug so `#Deposit` and `#deposit` are one topic that remembers
 * which spelling was used first, but `#deposit` → `#flat` is a new topic, not
 * a rename. That is the same trade [`crate::karya`] makes when it refuses to
 * reopen a task: a terminal rule is what lets two devices not need to
 * arbitrate.
 *
 * ## A topic change is not a chat message
 *
 * Filing a thread emits **no bubble**, on either side. This is the one place
 * this module departs from [`crate::karya`], which renders a line for every
 * signal, and the reason is that a task is something a person is being *asked*
 * while a topic is where a message already in front of them is *filed*. A
 * conversation that grew a "moved to #deposit" line every time someone tidied
 * would punish tidying. The signal moves the structure and the frontends
 * redraw; nothing is said out loud.
 */

use std::collections::{HashMap, HashSet};

use serde::{Deserialize, Serialize};

/// Envelope marker for a topic payload riding inside a DM body — the same
/// detection pattern [`crate::karya::KARYA_ENVELOPE_MARKER`] and
/// [`crate::command::OFFER_ENVELOPE_MARKER`] use.
pub const TOPIC_ENVELOPE_MARKER: u8 = 1;

/// Longest slug we mint or accept.
///
/// A slug is typed after a `#` in a composer and rendered as a chip in a list;
/// 32 is long enough for `#flat-deposit-followup` and short enough that a chip
/// row stays a row. Bounded on the way out **and** on the way in, because only
/// the second one is a security property — see [`slugify`].
pub const MAX_TOPIC_SLUG_LEN: usize = 32;

/// Shortest slug. One character is a typo far more often than a topic, and
/// `#a` in ordinary prose (`grade #a`) should stay prose.
pub const MIN_TOPIC_SLUG_LEN: usize = 2;

/// Longest display name we store for a topic. Longer than the slug because the
/// name keeps spacing and case (`Flat deposit`), and it is rendered as a
/// heading rather than a chip.
pub const MAX_TOPIC_NAME_LEN: usize = 64;

/// How far up a reply chain [`root_of`] will walk before giving up.
///
/// A bound rather than a promise: the chain is built from `reply_to` values
/// that arrived over the network, so a peer can hand us one as long as they
/// like. 64 is far past any real conversation — Slack's own threads are flat —
/// and the walk stops at the last id it reached rather than erroring, so a
/// pathological chain files under *something* instead of vanishing.
pub const MAX_THREAD_DEPTH: usize = 64;

// ── Slugs ────────────────────────────────────────────────────────────────────

/// The canonical slug for a topic name, or `None` if the name cannot be one.
///
/// Lowercase ASCII letters, digits, `-` and `_`. Everything else — spaces,
/// punctuation, emoji, non-ASCII script — collapses to a single `-`, leading
/// and trailing separators are trimmed, and the result must start with a letter
/// or digit and be within [`MIN_TOPIC_SLUG_LEN`]..=[`MAX_TOPIC_SLUG_LEN`].
///
/// **ASCII-only is a deliberate limitation, not an oversight, and it is the
/// wrong answer for this app's users.** Comrade's own strings ship in Hindi;
/// `#जमा` is a name somebody will type. It is refused here because the slug is
/// the *key* — two devices must agree on it byte for byte, and Unicode gives
/// several spellings of one word (NFC/NFD, ZWJ, Kashida) that a naive
/// lowercase does not fold together. Getting that wrong splits a topic in
/// half silently, which is the failure this module's whole keying scheme
/// exists to avoid. The exit condition is real work, not a flag: NFKC
/// normalisation plus a confusables check, at which point `MIN`/`MAX` become
/// grapheme counts. Recorded in `AUDIT.md` as T-1 so it is not rediscovered as
/// a bug. Until then a non-ASCII name is *rejected*, never silently mangled
/// into `#--`.
///
/// Takes the name with or without a leading `#`, because a user typing
/// `/assign #deposit` and a frontend passing `deposit` mean the same thing and
/// making the caller strip it is how one of the four call sites forgets.
pub fn slugify(name: &str) -> Option<String> {
    let trimmed = name.trim().trim_start_matches('#').trim();
    if trimmed.is_empty() || !trimmed.is_ascii() {
        return None;
    }
    let mut slug = String::with_capacity(trimmed.len());
    for ch in trimmed.chars() {
        if ch.is_ascii_alphanumeric() {
            slug.push(ch.to_ascii_lowercase());
        } else if matches!(ch, '-' | '_') {
            // Kept as typed: `flat_deposit` and `flat-deposit` are different
            // words to the person who wrote them, and folding them together
            // would make one of the two unreachable.
            if !slug.is_empty() && !slug.ends_with(['-', '_']) {
                slug.push(ch);
            }
        } else if !slug.is_empty() && !slug.ends_with(['-', '_']) {
            slug.push('-');
        }
    }
    let slug = slug.trim_end_matches(['-', '_']).to_string();
    if slug.len() < MIN_TOPIC_SLUG_LEN || slug.len() > MAX_TOPIC_SLUG_LEN {
        return None;
    }
    if !slug.starts_with(|c: char| c.is_ascii_alphanumeric()) {
        return None;
    }
    Some(slug)
}

/// A topic's display name as we will store it: control characters stripped
/// (it is rendered verbatim in a heading, and an embedded newline would let a
/// sender forge extra rows) and truncated on a character boundary.
///
/// Same shape and the same reason as [`crate::karya::bound_text`].
pub fn bound_name(name: &str) -> String {
    name.trim()
        .trim_start_matches('#')
        .chars()
        .filter(|c| !c.is_control())
        .take(MAX_TOPIC_NAME_LEN)
        .collect::<String>()
        .trim()
        .to_string()
}

// ── References in composer text ──────────────────────────────────────────────

/// A `#topic` found in composer text, with the span it occupies so a composer
/// can draw a chip over it.
///
/// `slug` is the canonical form; the span refers to the original text, so the
/// two are not interchangeable. Deliberately the same shape as
/// [`crate::command::Mention`] — a frontend that can chip one can chip the
/// other.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct TopicRef {
    /// Canonical slug without the leading `#`.
    pub slug: String,
    /// Byte offset of the `#`.
    pub start: u32,
    /// Byte offset one past the last character of the reference.
    pub end: u32,
}

/// Every `#topic` in `text`, in the order they appear.
///
/// A reference must start at the beginning of the text or after a non-word
/// character — the same rule [`crate::command::mentions`] uses, and for the
/// same reason. Without it `issue#12` references a topic called `12`, and a
/// pasted URL fragment (`docs.md#install`) becomes a filing instruction.
///
/// Runs of `#` are not references either: `## heading` in pasted Markdown is
/// prose, and a `#` immediately followed by another `#` yields nothing.
pub fn topic_refs(text: &str) -> Vec<TopicRef> {
    let bytes = text.as_bytes();
    let mut out = Vec::new();
    let mut i = 0usize;
    while i < bytes.len() {
        if bytes[i] != b'#' {
            i += 1;
            continue;
        }
        // Preceded by a word character → part of something else (`issue#12`,
        // a URL fragment), not a reference.
        if i > 0 && is_slug_byte(bytes[i - 1]) {
            i += 1;
            continue;
        }
        let start = i;
        let mut end = i + 1;
        while end < bytes.len() && is_slug_byte(bytes[end]) {
            end += 1;
        }
        if let Some(slug) = slugify(&text[start + 1..end]) {
            out.push(TopicRef {
                slug,
                start: start as u32,
                end: end as u32,
            });
        }
        i = end.max(start + 1);
    }
    out
}

/// The charset a slug can contain, for scanning composer text. Upper case is
/// included so `#Deposit` is found and then folded by [`slugify`] — refusing it
/// would be a puzzle rather than a rule.
fn is_slug_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'-' || b == b'_'
}

// ── The topic ────────────────────────────────────────────────────────────────

/// One named subject inside one conversation, as both devices hold it.
///
/// Keyed by `(peer_npub, slug)`. See the module header for why the slug is the
/// id and what that costs.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct Topic {
    /// Canonical slug — the key. See [`slugify`].
    pub slug: String,
    /// Display name, bounded by [`bound_name`]. The first spelling seen wins;
    /// see [`Topic::merge_name`] for why a later one does not overwrite it.
    pub name: String,
    /// The conversation this topic belongs to, as the peer's npub. Topics are
    /// per-conversation: `#deposit` with your landlord and `#deposit` with your
    /// flatmate are different subjects, and merging them would leak one
    /// conversation's structure into another.
    pub peer_npub: String,
    /// npub of whoever first named it.
    pub created_by: String,
    pub created_at: u64,
    /// Closed topics stay readable and stop appearing in the picker — the
    /// archive, not the delete. Nothing here removes a topic, for the same
    /// reason [`crate::karya`] has no reopen: two devices deleting and
    /// recreating one name would have an argument neither can win.
    pub closed: bool,
    /// When [`Self::closed`] last moved. Equals [`Self::created_at`] until it
    /// does, and is what refuses a replayed close — see [`Topic::set_closed`].
    pub updated_at: u64,
}

impl Topic {
    /// A fresh open topic, or `None` if `name` cannot be a slug.
    pub fn new(
        name: &str,
        peer_npub: impl Into<String>,
        created_by: impl Into<String>,
        created_at: u64,
    ) -> Option<Self> {
        let slug = slugify(name)?;
        let display = bound_name(name);
        Some(Self {
            name: if display.is_empty() {
                slug.clone()
            } else {
                display
            },
            slug,
            peer_npub: peer_npub.into(),
            created_by: created_by.into(),
            created_at,
            closed: false,
            updated_at: created_at,
        })
    }

    /// Take a display spelling seen for this slug, keeping the earlier one.
    ///
    /// First spelling wins on a tie rather than last, so a topic does not
    /// flicker between `#Deposit` and `#deposit` every time the two devices
    /// re-announce it. `created_at` is the clock, and an equal timestamp keeps
    /// what is already here — the store cannot order two creations, and stable
    /// is better than arbitrary. Same rule as
    /// `EncryptedStore::set_reaction`'s replay guard.
    pub fn merge_name(&mut self, name: &str, created_at: u64) -> bool {
        if created_at >= self.created_at {
            return false;
        }
        let display = bound_name(name);
        if display.is_empty() || display == self.name {
            self.created_at = created_at;
            return false;
        }
        self.name = display;
        self.created_at = created_at;
        true
    }

    /// Archive or unarchive, refusing anything not newer than what we hold.
    ///
    /// Returns whether the visible state actually moved, so a caller can skip
    /// an event for a replay. Either party may close a topic and either may
    /// reopen it: unlike a task, a topic is a shared filing cabinet rather than
    /// a request made of one person, so there is no asymmetry of standing to
    /// enforce.
    pub fn set_closed(&mut self, closed: bool, at: u64) -> bool {
        if at <= self.updated_at {
            return false;
        }
        self.updated_at = at;
        if self.closed == closed {
            return false;
        }
        self.closed = closed;
        true
    }
}

// ── The reply graph ──────────────────────────────────────────────────────────

/// The id of the thread `id` belongs to: the oldest ancestor reachable by
/// following `reply_to` links.
///
/// `parents` maps a message id to the id it replied to. A message that replies
/// to nothing is its own root, and so is one whose parent is not in the map —
/// which is the common case, not an edge case: a reply can arrive before its
/// parent, or quote something older than the loaded window. Filing it under
/// itself keeps it visible as its own thread instead of dropping it, which is
/// what a `None` return would have made every caller re-implement.
///
/// Cycles cannot happen honestly — a reply is always to an older event — but
/// `reply_to` arrives over the network, so they can happen dishonestly. The
/// visited set and [`MAX_THREAD_DEPTH`] bound the walk; both stop at the last
/// id actually reached, so a malicious chain costs a wrong-looking thread and
/// never a hang.
pub fn root_of(id: &str, parents: &HashMap<String, String>) -> String {
    let mut seen: HashSet<&str> = HashSet::new();
    let mut current = id;
    for _ in 0..MAX_THREAD_DEPTH {
        if !seen.insert(current) {
            break;
        }
        match parents.get(current) {
            Some(parent) if parent != current => current = parent.as_str(),
            _ => break,
        }
    }
    current.to_string()
}

/// Group every id in `parents`' universe by the thread it belongs to.
///
/// `ids` is every message in the conversation — the roots come from here, not
/// from `parents`, so a message that replied to nothing still gets a thread of
/// its own. The returned map is root id → member ids, in the order `ids` were
/// given, which is the caller's time order.
///
/// One walk per id rather than a memoised union-find: a conversation is
/// thousands of messages, the depth is bounded at [`MAX_THREAD_DEPTH`], and a
/// clever version would be a second place the cycle guard has to be right.
pub fn group_threads(
    ids: &[String],
    parents: &HashMap<String, String>,
) -> HashMap<String, Vec<String>> {
    let mut out: HashMap<String, Vec<String>> = HashMap::new();
    for id in ids {
        out.entry(root_of(id, parents))
            .or_default()
            .push(id.clone());
    }
    out
}

// ── The wire ─────────────────────────────────────────────────────────────────

/// One statement about a conversation's topics.
///
/// Three signals. `Create` names one; `Assign` files a thread (or, with `slug`
/// of `None`, takes it out of wherever it was); `Close` archives or restores.
/// There is no rename and no delete — see the module header.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum TopicSignal {
    /// "This conversation has a topic called this." Idempotent: the slug is the
    /// id, so a second `Create` for the same word is a no-op that may only
    /// settle which spelling is displayed.
    Create { slug: String, name: String },
    /// "That thread belongs under this topic." `slug` of `None` unfiles it.
    ///
    /// `root_id` is the *thread root's* event id rather than the message that
    /// was tapped: filing is per thread, and two people filing two different
    /// replies of one thread must reach the same row. The sender resolves the
    /// root, because only the sender's device is guaranteed to hold the chain.
    Assign {
        root_id: String,
        slug: Option<String>,
    },
    /// "Archive this topic" — or, with `closed: false`, bring it back.
    Close { slug: String, closed: bool },
}

impl TopicSignal {
    /// A short name for logs and metrics — never the topic name, which is user
    /// text about a conversation's contents.
    pub fn kind_str(&self) -> &'static str {
        match self {
            Self::Create { .. } => "create",
            Self::Assign { .. } => "assign",
            Self::Close { .. } => "close",
        }
    }
}

/// A topic signal, wrapped for the DM channel.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TopicEnvelope {
    /// Format marker; must equal [`TOPIC_ENVELOPE_MARKER`].
    pub comrade_topic: u8,
    pub signal: TopicSignal,
}

impl TopicEnvelope {
    pub fn new(signal: TopicSignal) -> Self {
        Self {
            comrade_topic: TOPIC_ENVELOPE_MARKER,
            signal,
        }
    }

    /// Serialise for the DM body.
    pub fn to_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }
}

/// Read a DM body as a topic envelope, or `None` if it is not one.
///
/// The marker check is what keeps an ordinary message that happens to be JSON
/// from being swallowed as a control envelope — the same guard
/// [`crate::karya::parse_karya_envelope`] applies.
pub fn parse_topic_envelope(body: &str) -> Option<TopicEnvelope> {
    let env: TopicEnvelope = serde_json::from_str(body.trim()).ok()?;
    (env.comrade_topic == TOPIC_ENVELOPE_MARKER).then_some(env)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parents(pairs: &[(&str, &str)]) -> HashMap<String, String> {
        pairs
            .iter()
            .map(|(c, p)| (c.to_string(), p.to_string()))
            .collect()
    }

    #[test]
    fn slugify_folds_case_and_spacing() {
        assert_eq!(slugify("Flat Deposit").as_deref(), Some("flat-deposit"));
        assert_eq!(slugify("#Deposit").as_deref(), Some("deposit"));
        assert_eq!(slugify("  deposit  ").as_deref(), Some("deposit"));
        assert_eq!(slugify("flat   deposit").as_deref(), Some("flat-deposit"));
    }

    #[test]
    fn slugify_keeps_the_separator_that_was_typed() {
        // `flat_deposit` and `flat-deposit` are two words to whoever wrote
        // them; folding them together makes one unreachable.
        assert_eq!(slugify("flat_deposit").as_deref(), Some("flat_deposit"));
        assert_eq!(slugify("flat-deposit").as_deref(), Some("flat-deposit"));
        assert_ne!(slugify("flat_deposit"), slugify("flat-deposit"));
    }

    #[test]
    fn slugify_refuses_what_cannot_be_a_key() {
        assert_eq!(slugify(""), None);
        assert_eq!(slugify("#"), None);
        assert_eq!(
            slugify("a"),
            None,
            "one character is a typo more often than a topic"
        );
        assert_eq!(slugify("---"), None);
        assert_eq!(slugify(&"x".repeat(MAX_TOPIC_SLUG_LEN + 1)), None);
    }

    #[test]
    fn slugify_trims_a_leading_separator_rather_than_refusing_it() {
        // `#-deposit` is a typo with an obvious intent, and the slug that comes
        // out still satisfies the "starts with a letter or digit" rule the key
        // depends on — so trimming is friendlier than a refusal nobody can act
        // on.
        assert_eq!(slugify("-deposit").as_deref(), Some("deposit"));
        assert_eq!(slugify("_deposit").as_deref(), Some("deposit"));
    }

    #[test]
    fn slugify_refuses_non_ascii_rather_than_mangling_it() {
        // The limitation the module header records as AUDIT T-1. What must not
        // happen is `#जमा` quietly becoming `#--` or an empty key.
        assert_eq!(slugify("जमा"), None);
        assert_eq!(slugify("dépôt"), None);
        assert_eq!(slugify("🏠"), None);
    }

    #[test]
    fn bound_name_strips_control_characters_and_truncates() {
        assert_eq!(bound_name("#Flat deposit"), "Flat deposit");
        assert_eq!(bound_name("one\nline"), "oneline");
        assert_eq!(bound_name(&"n".repeat(200)).len(), MAX_TOPIC_NAME_LEN);
    }

    #[test]
    fn topic_refs_finds_references_and_their_spans() {
        let found = topic_refs("filing this under #deposit and #Repairs");
        assert_eq!(found.len(), 2);
        assert_eq!(found[0].slug, "deposit");
        assert_eq!(
            &"filing this under #deposit and #Repairs"
                [found[0].start as usize..found[0].end as usize],
            "#deposit"
        );
        assert_eq!(found[1].slug, "repairs");
    }

    #[test]
    fn topic_refs_ignores_hashes_that_are_not_references() {
        // A URL fragment and an issue number are the two ways this goes wrong
        // in real text, and both are preceded by a word character.
        assert!(topic_refs("see docs.md#install").is_empty());
        assert!(topic_refs("issue#12").is_empty());
        assert!(topic_refs("## heading").is_empty());
        assert!(topic_refs("# ").is_empty());
        assert!(
            topic_refs("grade #a").is_empty(),
            "one character is not a slug"
        );
    }

    #[test]
    fn a_topic_remembers_the_first_spelling() {
        let mut t = Topic::new("Deposit", "npub_peer", "npub_me", 100).unwrap();
        assert_eq!(t.slug, "deposit");
        assert_eq!(t.name, "Deposit");
        // A later announcement of the same slug does not repaint the heading.
        assert!(!t.merge_name("deposit", 200));
        assert_eq!(t.name, "Deposit");
        // An *earlier* one does — that spelling was first.
        assert!(t.merge_name("DEPOSIT", 50));
        assert_eq!(t.name, "DEPOSIT");
        assert_eq!(t.created_at, 50);
    }

    #[test]
    fn a_topic_with_an_unusable_name_is_not_created() {
        assert!(Topic::new("🏠", "peer", "me", 1).is_none());
    }

    #[test]
    fn closing_is_idempotent_and_refuses_a_replay() {
        let mut t = Topic::new("deposit", "peer", "me", 100).unwrap();
        assert!(t.set_closed(true, 200));
        assert!(t.closed);
        // Same signal again, older clock: nothing moves.
        assert!(!t.set_closed(true, 150));
        // A replay of the *close* cannot un-close it either.
        assert!(!t.set_closed(false, 200));
        assert!(t.closed);
        assert!(t.set_closed(false, 300));
        assert!(!t.closed);
    }

    #[test]
    fn root_of_walks_to_the_oldest_ancestor() {
        let p = parents(&[("c", "b"), ("b", "a")]);
        assert_eq!(root_of("c", &p), "a");
        assert_eq!(root_of("b", &p), "a");
        assert_eq!(root_of("a", &p), "a");
    }

    #[test]
    fn a_reply_to_something_we_do_not_have_is_its_own_root() {
        // Not `None`: a reply whose parent is outside the loaded window must
        // stay visible as a thread of its own.
        let p = parents(&[("b", "missing")]);
        assert_eq!(root_of("b", &p), "missing");
        assert_eq!(root_of("standalone", &p), "standalone");
    }

    #[test]
    fn a_cycle_from_the_network_terminates() {
        let p = parents(&[("a", "b"), ("b", "a")]);
        // Whichever it stops on, it stops.
        let root = root_of("a", &p);
        assert!(root == "a" || root == "b");
    }

    #[test]
    fn a_chain_longer_than_the_bound_terminates() {
        let ids: Vec<String> = (0..MAX_THREAD_DEPTH * 2).map(|i| format!("m{i}")).collect();
        let mut p = HashMap::new();
        for w in ids.windows(2) {
            // m1 replies to m0, m2 to m1, …
            p.insert(w[1].clone(), w[0].clone());
        }
        let root = root_of(ids.last().unwrap(), &p);
        assert!(root.starts_with('m'), "stops at the last id it reached");
    }

    #[test]
    fn group_threads_keeps_every_message_in_exactly_one_thread() {
        let ids: Vec<String> = ["a", "b", "c", "d"].iter().map(|s| s.to_string()).collect();
        let p = parents(&[("b", "a"), ("c", "b")]);
        let grouped = group_threads(&ids, &p);
        assert_eq!(grouped.len(), 2);
        assert_eq!(grouped["a"], vec!["a", "b", "c"]);
        assert_eq!(grouped["d"], vec!["d"]);
        let total: usize = grouped.values().map(Vec::len).sum();
        assert_eq!(total, ids.len());
    }

    #[test]
    fn an_envelope_round_trips() {
        for signal in [
            TopicSignal::Create {
                slug: "deposit".into(),
                name: "Deposit".into(),
            },
            TopicSignal::Assign {
                root_id: "abc".into(),
                slug: Some("deposit".into()),
            },
            TopicSignal::Assign {
                root_id: "abc".into(),
                slug: None,
            },
            TopicSignal::Close {
                slug: "deposit".into(),
                closed: true,
            },
        ] {
            let json = TopicEnvelope::new(signal.clone()).to_json().unwrap();
            assert_eq!(parse_topic_envelope(&json).unwrap().signal, signal);
        }
    }

    #[test]
    fn a_message_that_is_not_an_envelope_is_not_read_as_one() {
        assert!(parse_topic_envelope("hey, filing this under #deposit").is_none());
        assert!(parse_topic_envelope("{}").is_none());
        // JSON of the right shape but the wrong marker — a future format, not
        // this one.
        assert!(parse_topic_envelope(
            r#"{"comrade_topic":2,"signal":{"kind":"create","slug":"x","name":"x"}}"#
        )
        .is_none());
    }
}
