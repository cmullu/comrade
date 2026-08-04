/*!
 * karya (कार्य, work) — naming a piece of work, yours or a comrade's.
 *
 * `/task get some work done @xyz` in a conversation. The wire is the same
 * NIP-44/NIP-17 gift-wrapped DM channel as receipts, profile shares, call
 * signals, presence beacons, nudges and together envelopes
 * ([`crate::dm`], [`crate::call`], [`crate::presence`], [`crate::together`]),
 * with its own marker key so the inbox dispatcher can fall through to its other
 * handlers.
 *
 * Pure and framework-free like its neighbours: the wire shape, the id, the state
 * machine and the bounds live here and are unit-tested. Persistence is
 * `comrade_storage`, the session bookkeeping is `comrade_ui`, and the lists are
 * the frontends'.
 *
 * ## A task is a request, not a write to someone else's list
 * This is the load-bearing decision, and it is a product one rather than a
 * technical one. Somebody else's day is not yours to add rows to. So an incoming
 * assignment arrives [`TaskState::Open`] and **can be declined**
 * ([`may_transition`]), the decline is a first-class outcome rather than a
 * failure, and nothing here lets an assigner mark someone else's task done.
 *
 * The same reasoning bounds who may send one at all: the dispatcher accepts an
 * assignment only from an **already-accepted conversation**, exactly as it does
 * a call signal. A stranger who can push items onto your list has been handed a
 * harassment channel, and in an app whose whole premise is wellbeing that is
 * worse than the feature is good.
 *
 * ## Who may change what
 * Two parties, two powers, and they do not overlap:
 *
 * | | assigner | assignee |
 * |---|---|---|
 * | withdraw it | yes | no |
 * | mark it done | no* | yes |
 * | decline it | no | yes |
 * | reopen it | no | no |
 *
 * *A task with no assignee is a note to self, and there the two roles are the
 * same person: [`Task::party`] reports [`Party::Assignee`], so the holder may
 * mark it done or decline it — **and withdraw it**, which is the one place the
 * table above does not apply. Withdrawing your own private note is deleting it,
 * and refusing that with "that is not yours to change" would be absurd.
 *
 * Nothing reopens. A task that comes back is a new task, because "done, no
 * wait, not done" is an argument the two devices would have to arbitrate and
 * neither has the standing to win it. [`crate::together`] pays for a Lamport
 * clock because playback genuinely needs one; a task list does not, and a
 * terminal state is what lets it not need one.
 */

use rand::RngCore;
use serde::{Deserialize, Serialize};

/// Envelope marker for a karya payload riding inside a DM body — the same
/// detection pattern [`crate::call::CALL_ENVELOPE_MARKER`] and
/// [`crate::command::OFFER_ENVELOPE_MARKER`] use.
pub const KARYA_ENVELOPE_MARKER: u8 = 1;

/// Longest task text we send or store.
///
/// A task is a line, not an essay, and the number is chosen for the same reason
/// `comrade_ui`'s `MAX_CAPTION_LEN` is: this text is free-form, chosen by whoever
/// sent it, and rendered verbatim in a list on the other person's device.
/// Bounded on the way out **and** on the way in, because only the second one is a
/// security property.
pub const MAX_TASK_LEN: usize = 280;

// ── State ────────────────────────────────────────────────────────────────────

/// Where a task has got to.
///
/// Three of the four are terminal. See the module header for why nothing
/// reopens.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "snake_case")]
pub enum TaskState {
    /// Named, and not yet resolved. The only non-terminal state.
    Open,
    /// The assignee did it.
    Done,
    /// The assignee said no. A first-class outcome, not a failure — see the
    /// module header.
    Declined,
    /// The assigner took it back.
    Withdrawn,
}

impl TaskState {
    /// Stable wire/store key.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Open => "open",
            Self::Done => "done",
            Self::Declined => "declined",
            Self::Withdrawn => "withdrawn",
        }
    }

    /// The state named by `s`, or `None` — so a row or envelope from a newer
    /// build is skipped rather than guessed at.
    pub fn from_str_opt(s: &str) -> Option<Self> {
        match s {
            "open" => Some(Self::Open),
            "done" => Some(Self::Done),
            "declined" => Some(Self::Declined),
            "withdrawn" => Some(Self::Withdrawn),
            _ => None,
        }
    }

    /// Whether nothing further can happen to a task in this state.
    pub fn is_terminal(self) -> bool {
        !matches!(self, Self::Open)
    }
}

/// Which side of a task somebody is on.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Party {
    /// They named it.
    Assigner,
    /// They were asked. On a note to self this is the same person as
    /// [`Self::Assigner`], and [`Task::party`] returns this one.
    Assignee,
}

/// Whether `party` may move a task from `from` to `to`.
///
/// The whole table is in the module header. Two rules do the work: only `Open`
/// goes anywhere, and the two powers do not overlap — an assigner can withdraw
/// but not complete, an assignee can complete or decline but not withdraw.
///
/// A note to self has one person holding both roles, which is why
/// [`Task::party`] reports [`Party::Assignee`] for it: that side holds the
/// powers a solitary task actually needs.
pub fn may_transition(from: TaskState, to: TaskState, party: Party) -> bool {
    if from != TaskState::Open {
        return false;
    }
    // Exhaustive on `to` rather than wildcarded, so adding a fifth state is a
    // compile error here instead of a silent `false`.
    match to {
        TaskState::Open => false,
        TaskState::Done | TaskState::Declined => party == Party::Assignee,
        TaskState::Withdrawn => party == Party::Assigner,
    }
}

// ── The task ─────────────────────────────────────────────────────────────────

/// One piece of work, as both devices hold it.
///
/// Keyed by [`Self::id`], which is minted by the assigner and carried on the
/// wire. It is **not** derived from the DM's event id, though that was the first
/// idea: the assigner does not know the event id until the relay has accepted
/// the send, so there would be nothing to put in the envelope, and the two sides
/// see different ids anyway (the wrapper's, not the rumor's).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct Task {
    /// 128 random bits, hex. Minted once by the assigner.
    pub id: String,
    /// What needs doing, bounded by [`MAX_TASK_LEN`] and stripped of control
    /// characters — it is rendered verbatim in a list.
    pub text: String,
    /// npub of whoever named it.
    pub assigner_npub: String,
    /// npub of whoever was asked, or `None` for a note to self — which never
    /// reaches a relay at all.
    pub assignee_npub: Option<String>,
    pub created_at: u64,
    pub state: TaskState,
    /// When the state last moved. Equals [`Self::created_at`] until it does.
    pub updated_at: u64,
}

impl Task {
    /// A fresh open task, with `text` bounded and stripped.
    ///
    /// `assignee_npub` of `None` is a note to self.
    pub fn new(
        id: impl Into<String>,
        text: &str,
        assigner_npub: impl Into<String>,
        assignee_npub: Option<String>,
        created_at: u64,
    ) -> Self {
        Self {
            id: id.into(),
            text: bound_text(text),
            assigner_npub: assigner_npub.into(),
            assignee_npub,
            created_at,
            state: TaskState::Open,
            updated_at: created_at,
        }
    }

    /// Whether this is a note to self — no relay, no other party.
    pub fn is_self_assigned(&self) -> bool {
        self.assignee_npub.is_none()
    }

    /// Which side `npub` is on, or `None` if they are not party to this task at
    /// all — which is the check that stops a third peer transitioning it.
    ///
    /// On a note to self the assigner *is* the assignee, and this reports
    /// [`Party::Assignee`] so the one person holding it can complete it.
    pub fn party(&self, npub: &str) -> Option<Party> {
        if self.assignee_npub.as_deref() == Some(npub) {
            return Some(Party::Assignee);
        }
        if self.assigner_npub == npub {
            return Some(if self.is_self_assigned() {
                Party::Assignee
            } else {
                Party::Assigner
            });
        }
        None
    }

    /// Whether this task is one person's alone, so both powers are theirs.
    fn holder_is_sole_party(&self, npub: &str) -> bool {
        self.is_self_assigned() && self.assigner_npub == npub
    }

    /// Move to `to` on `npub`'s say-so, or leave it alone and return `false`.
    ///
    /// Returns `false` for all three ways this can be wrong — a peer who is not
    /// party to the task, a party without that power, and an already-terminal
    /// state — deliberately without distinguishing them to the caller. A peer
    /// learning *why* their attempt failed learns something about a task that is
    /// not theirs.
    pub fn apply(&mut self, to: TaskState, npub: &str, now: u64) -> bool {
        let Some(party) = self.party(npub) else {
            return false;
        };
        // On a note to self the two roles are one person, so they hold both
        // powers — including withdraw, which `may_transition` reserves for an
        // assigner and `party` never reports for a solitary task. Without this
        // you could not delete your own note.
        let allowed = if self.holder_is_sole_party(npub) {
            to != TaskState::Open && self.state == TaskState::Open
        } else {
            may_transition(self.state, to, party)
        };
        if !allowed {
            return false;
        }
        self.state = to;
        self.updated_at = now;
        true
    }
}

/// Make task text safe to store and render: strip control characters (a task is
/// one line in every frontend, and an embedded newline would let a sender forge
/// extra rows) and truncate to [`MAX_TASK_LEN`] on a character boundary.
///
/// Same shape and the same reason as `sanitise_untrusted_text` in `comrade_ui`,
/// duplicated here rather than shared because that one is private to the
/// runtime and this crate must not depend upwards.
pub fn bound_text(text: &str) -> String {
    text.chars()
        .filter(|c| !c.is_control())
        .take(MAX_TASK_LEN)
        .collect::<String>()
        .trim()
        .to_string()
}

/// Mint a fresh, unguessable task id (128 bits of randomness, hex-encoded) —
/// the same shape as [`crate::call::new_call_id`] and
/// [`crate::together::new_session_id`].
pub fn new_task_id() -> String {
    let mut bytes = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut bytes);
    hex::encode(bytes)
}

// ── The wire ─────────────────────────────────────────────────────────────────

/// One statement about a task.
///
/// Two signals, not five. An assignment creates it; everything afterwards is
/// "it is now in this state", so the receiver derives which of done / declined /
/// withdrawn happened from the state itself rather than three near-identical
/// messages — the same collapse [`crate::together::TogetherSignal::State`] makes
/// for play, pause and seek.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum TaskSignal {
    /// "Would you do this?" Carries the id, so both sides key the same row.
    Assign { id: String, text: String },
    /// "It is now done / declined / withdrawn."
    State { id: String, state: TaskState },
}

impl TaskSignal {
    /// A short name for logs and metrics — never the task text.
    pub fn kind_str(&self) -> &'static str {
        match self {
            Self::Assign { .. } => "assign",
            Self::State { .. } => "state",
        }
    }

    /// The task this signal is about.
    pub fn task_id(&self) -> &str {
        match self {
            Self::Assign { id, .. } | Self::State { id, .. } => id,
        }
    }
}

/// A karya signal, wrapped for the DM channel.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct KaryaEnvelope {
    /// Format marker; must equal [`KARYA_ENVELOPE_MARKER`].
    pub comrade_karya: u8,
    pub signal: TaskSignal,
}

impl KaryaEnvelope {
    pub fn new(signal: TaskSignal) -> Self {
        Self {
            comrade_karya: KARYA_ENVELOPE_MARKER,
            signal,
        }
    }

    pub fn to_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }
}

/// Detect and parse a karya envelope out of a decrypted DM body.
pub fn parse_karya_envelope(content: &str) -> Option<KaryaEnvelope> {
    let env: KaryaEnvelope = serde_json::from_str(content).ok()?;
    (env.comrade_karya == KARYA_ENVELOPE_MARKER).then_some(env)
}

// ── The chat line ────────────────────────────────────────────────────────────

/// Prefix marking a rendered task line, so [`parse_task_line`] recognises one
/// without matching the whole sentence.
const TASK_LINE_SIGIL: &str = "🗒";

/// The chat-bubble line for a task signal.
///
/// **Stable English on purpose**, for the reason
/// [`crate::command::render_offer_line`] gives: the envelope is the wire, this
/// is the bubble, and a frontend that localises reads the parse back and renders
/// its own words. Translating this would mean two phones in different languages
/// could not read each other's threads.
pub fn render_task_line(state: TaskState, text: &str) -> String {
    let verb = match state {
        TaskState::Open => "Task",
        TaskState::Done => "Done",
        TaskState::Declined => "Declined",
        TaskState::Withdrawn => "Withdrawn",
    };
    format!("{TASK_LINE_SIGIL} {verb}: {}", bound_text(text))
}

/// The state and text a rendered task line names, or `None` for ordinary text.
pub fn parse_task_line(line: &str) -> Option<(TaskState, String)> {
    let rest = line.trim().strip_prefix(TASK_LINE_SIGIL)?.trim_start();
    for state in [
        TaskState::Open,
        TaskState::Done,
        TaskState::Declined,
        TaskState::Withdrawn,
    ] {
        let verb = match state {
            TaskState::Open => "Task: ",
            TaskState::Done => "Done: ",
            TaskState::Declined => "Declined: ",
            TaskState::Withdrawn => "Withdrawn: ",
        };
        if let Some(text) = rest.strip_prefix(verb) {
            return Some((state, text.trim().to_string()));
        }
    }
    None
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    const ANA: &str = "npub1ana";
    const BINA: &str = "npub1bina";
    const EVE: &str = "npub1eve";

    fn assigned() -> Task {
        Task::new("t1", "get some work done", ANA, Some(BINA.into()), 100)
    }

    fn note_to_self() -> Task {
        Task::new("t2", "water the plants", ANA, None, 100)
    }

    // ── Who may change what ──────────────────────────────────────────────────

    #[test]
    fn the_assignee_can_finish_or_decline() {
        let mut t = assigned();
        assert!(t.clone().apply(TaskState::Done, BINA, 200));
        assert!(t.apply(TaskState::Declined, BINA, 200));
        assert_eq!(t.state, TaskState::Declined);
        assert_eq!(t.updated_at, 200);
    }

    #[test]
    fn the_assigner_can_withdraw_but_not_finish() {
        let mut t = assigned();
        assert!(!t.clone().apply(TaskState::Done, ANA, 200));
        assert!(t.apply(TaskState::Withdrawn, ANA, 200));
        assert_eq!(t.state, TaskState::Withdrawn);
    }

    #[test]
    fn the_assignee_cannot_withdraw_someone_elses_ask() {
        let mut t = assigned();
        assert!(!t.apply(TaskState::Withdrawn, BINA, 200));
        assert_eq!(t.state, TaskState::Open);
    }

    #[test]
    fn a_peer_who_is_not_party_to_it_changes_nothing() {
        // The forged-state-change case: a third peer sending a state signal for
        // a task id they happened to learn.
        let mut t = assigned();
        assert!(t.party(EVE).is_none());
        for to in [TaskState::Done, TaskState::Declined, TaskState::Withdrawn] {
            assert!(!t.apply(to, EVE, 200), "{to:?}");
        }
        assert_eq!(t.state, TaskState::Open);
        assert_eq!(t.updated_at, 100);
    }

    #[test]
    fn a_note_to_self_can_be_finished_by_the_one_person_holding_it() {
        let mut t = note_to_self();
        assert_eq!(t.party(ANA), Some(Party::Assignee));
        assert!(t.is_self_assigned());
        assert!(t.apply(TaskState::Done, ANA, 200));
        assert_eq!(t.state, TaskState::Done);
    }

    #[test]
    fn a_note_to_self_can_also_be_withdrawn() {
        // Withdrawing your own private note is deleting it. `may_transition`
        // reserves withdraw for an assigner and `party` reports `Assignee` for a
        // solitary task, so without the sole-party rule this returned "that is
        // not yours to change" for a note nobody else can even see.
        for to in [TaskState::Done, TaskState::Declined, TaskState::Withdrawn] {
            let mut t = note_to_self();
            assert!(t.apply(to, ANA, 200), "{to:?}");
            assert_eq!(t.state, to);
        }
    }

    #[test]
    fn a_sole_party_still_cannot_reopen_or_touch_a_finished_note() {
        let mut t = note_to_self();
        assert!(t.apply(TaskState::Done, ANA, 200));
        assert!(!t.apply(TaskState::Open, ANA, 300));
        assert!(!t.apply(TaskState::Withdrawn, ANA, 300));
        assert_eq!(t.state, TaskState::Done);
    }

    #[test]
    fn a_stranger_cannot_touch_a_note_to_self() {
        let mut t = note_to_self();
        assert!(t.party(EVE).is_none());
        assert!(!t.apply(TaskState::Withdrawn, EVE, 200));
        assert_eq!(t.state, TaskState::Open);
    }

    #[test]
    fn nothing_reopens() {
        let mut t = assigned();
        assert!(t.apply(TaskState::Done, BINA, 200));
        assert!(!t.apply(TaskState::Open, BINA, 300));
        assert!(!t.apply(TaskState::Open, ANA, 300));
        assert_eq!(t.state, TaskState::Done);
    }

    #[test]
    fn a_terminal_task_stays_where_it_landed() {
        for terminal in [TaskState::Done, TaskState::Declined, TaskState::Withdrawn] {
            let mut t = assigned();
            t.state = terminal;
            for to in [TaskState::Done, TaskState::Declined, TaskState::Withdrawn] {
                assert!(!t.apply(to, BINA, 300), "{terminal:?} -> {to:?}");
                assert!(!t.apply(to, ANA, 300), "{terminal:?} -> {to:?}");
            }
            assert_eq!(t.state, terminal);
        }
        assert!(TaskState::Done.is_terminal());
        assert!(!TaskState::Open.is_terminal());
    }

    #[test]
    fn a_failed_transition_leaves_the_timestamp_alone() {
        // Otherwise a rejected attempt would still reorder somebody's list,
        // which is a peer writing to it after all.
        let mut t = assigned();
        assert!(!t.apply(TaskState::Done, EVE, 999));
        assert_eq!(t.updated_at, 100);
    }

    // ── Bounds ───────────────────────────────────────────────────────────────

    #[test]
    fn a_newline_cannot_forge_extra_rows() {
        let t = Task::new("t", "real task\n🗒 Task: forged", ANA, None, 1);
        assert!(!t.text.contains('\n'));
        assert_eq!(t.text, "real task🗒 Task: forged");
    }

    #[test]
    fn overlong_text_is_truncated_on_a_character_boundary() {
        let long = "मैं".repeat(400);
        let t = Task::new("t", &long, ANA, None, 1);
        assert_eq!(t.text.chars().count(), MAX_TASK_LEN);
    }

    #[test]
    fn text_is_trimmed() {
        assert_eq!(bound_text("  spaced  "), "spaced");
    }

    // ── Ids ──────────────────────────────────────────────────────────────────

    #[test]
    fn task_ids_are_unguessable_and_distinct() {
        let a = new_task_id();
        let b = new_task_id();
        assert_ne!(a, b);
        assert_eq!(a.len(), 32);
        assert!(a.chars().all(|c| c.is_ascii_hexdigit()));
    }

    // ── The wire ─────────────────────────────────────────────────────────────

    #[test]
    fn both_signals_round_trip() {
        for signal in [
            TaskSignal::Assign {
                id: "t1".into(),
                text: "get some work done".into(),
            },
            TaskSignal::State {
                id: "t1".into(),
                state: TaskState::Done,
            },
        ] {
            let env = KaryaEnvelope::new(signal.clone());
            let json = env.to_json().unwrap();
            assert_eq!(parse_karya_envelope(&json).unwrap(), env);
            assert_eq!(env.signal.task_id(), "t1");
        }
    }

    #[test]
    fn chat_text_and_the_neighbouring_envelopes_are_not_karya() {
        assert!(parse_karya_envelope("get some work done").is_none());
        assert!(parse_karya_envelope(r#"{"comrade_receipt":1}"#).is_none());
        assert!(parse_karya_envelope(r#"{"comrade_offer":1,"action":"breath"}"#).is_none());
        // A marker that is present but wrong is still not ours.
        assert!(
            parse_karya_envelope(r#"{"comrade_karya":0,"signal":{"kind":"assign"}}"#).is_none()
        );
    }

    #[test]
    fn a_state_this_build_does_not_know_is_skipped() {
        assert!(TaskState::from_str_opt("snoozed").is_none());
        for state in [
            TaskState::Open,
            TaskState::Done,
            TaskState::Declined,
            TaskState::Withdrawn,
        ] {
            assert_eq!(TaskState::from_str_opt(state.as_str()), Some(state));
        }
    }

    // ── The chat line ────────────────────────────────────────────────────────

    #[test]
    fn every_task_line_round_trips() {
        for state in [
            TaskState::Open,
            TaskState::Done,
            TaskState::Declined,
            TaskState::Withdrawn,
        ] {
            let line = render_task_line(state, "get some work done");
            assert_eq!(
                parse_task_line(&line),
                Some((state, "get some work done".to_string())),
                "{state:?}"
            );
        }
    }

    #[test]
    fn ordinary_text_is_not_a_task_line() {
        assert!(parse_task_line("Task: get some work done").is_none());
        assert!(parse_task_line("hello").is_none());
        assert!(parse_task_line("🗒 something else entirely").is_none());
    }

    #[test]
    fn a_task_line_containing_a_colon_keeps_all_of_it() {
        let line = render_task_line(TaskState::Open, "ship it: the whole thing");
        assert_eq!(
            parse_task_line(&line),
            Some((TaskState::Open, "ship it: the whole thing".to_string()))
        );
    }
}
