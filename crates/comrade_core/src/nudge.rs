/*!
 * Comrade nudge — "they nearly wrote to you", once, and only to a comrade.
 *
 * Someone opens your chat, types a few words, and then clears the box or walks
 * away without sending. Nothing about that reaches you today: the message was
 * never sent, so there is nothing to deliver, and the moment passes silently.
 * Often it is the moment that mattered most.
 *
 * A nudge is the smallest honest signal for that moment: a [`Nudge`] envelope
 * riding the same NIP-44/NIP-17 gift-wrapped DM channel as receipts, profile
 * shares, call signals and presence beacons ([`crate::dm`], [`crate::call`],
 * [`crate::presence`]), addressed to one chosen comrade, saying only *that it
 * happened*.
 *
 * ## This is not a typing indicator
 * `docs/PRESENCE.md` §7 rules typing indicators out, and that still holds — a
 * live "typing…" is a keystroke-resolution feed of someone's hesitation, on
 * for every chat, disclosed continuously. A nudge is the opposite shape on
 * every axis that matters:
 *
 * | | typing indicator | nudge |
 * |---|---|---|
 * | when | continuously, while typing | once, after the draft is gone |
 * | how often | every keystroke burst | at most once per [`NUDGE_COOLDOWN_SECS`] |
 * | to whom | whoever you are chatting with | a comrade you chose, one at a time |
 * | payload | the fact you are typing, live | one bit: something was written and not sent |
 *
 * What it deliberately does **not** carry: the draft, its length, how long it
 * was typed for, how many times it was rewritten, or which of "cleared" and
 * "walked away" happened. Those are all things a person did not choose to
 * send, and none of them are needed to say "your comrade might need you".
 *
 * ## Two triggers, one envelope
 * A nudge is also sent when someone deliberately reaches for the breathing
 * screen (`docs/ATTENTION.md`) — a different moment with the same meaning.
 *
 * That the envelope is *the same one bit* is the point, not an economy: a
 * comrade learns "they might need you" and **cannot tell which trigger fired**,
 * so adding this second reason added nothing to what anyone learns about
 * anyone. A `reason` field would have — and is exactly why there isn't one.
 * The two share the cooldown for the same reason ([`nudged_recently`]).
 *
 * ## The rules, stated honestly
 * - **A nudge is a claim about now.** It expires [`NUDGE_TTL_SECS`] after it
 *   was *sent*, the same arithmetic (and for the same reasons) as a presence
 *   beacon: relays redeliver at-least-once and the vault inbox backfills two
 *   days on every launch, so a replayed nudge must not raise anything.
 * - **A nudge is never sent about something old.** The sender drops a pending
 *   nudge that has aged past [`NUDGE_TTL_SECS`] rather than sending it late —
 *   a phone that was asleep for an hour must not wake up and announce a
 *   hesitation from an hour ago as if it just happened.
 * - **A hesitation has to look like one.** A draft that existed for less than
 *   [`NUDGE_MIN_DWELL_SECS`] was a stray keystroke, not a message someone
 *   thought better of; and nothing is sent until [`NUDGE_SETTLE_SECS`] have
 *   passed, so clearing the box to rewrite the same sentence stays private.
 *
 * Pure and framework-free like its neighbours: the wire shape and every timing
 * rule live here and are unit-tested. Who is watched, when the timers are
 * looked at, and what the recipient's UI does with it all live in `comrade_ui`.
 */

use serde::{Deserialize, Serialize};

use crate::presence::{PRESENCE_MAX_TTL_SECS, PRESENCE_TTL_SECS};

/// Envelope marker for a nudge riding inside a DM body — the same
/// detection pattern [`crate::presence::PRESENCE_ENVELOPE_MARKER`] and
/// [`crate::dm::ProfileShare`] use, so [`parse_nudge`] accepts only its own
/// shape and the inbox dispatcher can fall through to its other handlers.
pub const NUDGE_ENVELOPE_MARKER: u8 = 1;

/// How long a draft must have existed before abandoning it means anything.
///
/// Opening a chat and hitting a key by accident is not a message someone
/// thought better of, and a comrade should not be paged about it. Three
/// seconds is the shortest span that separates "I started writing" from
/// "my thumb touched the screen".
pub const NUDGE_MIN_DWELL_SECS: u64 = 3;

/// How long an abandoned draft must stay abandoned before anything is sent.
///
/// Clearing the box to rewrite the same sentence is the single most common way
/// to abandon a draft, and it is not what this feature is about. Waiting a
/// short while first means the ordinary edit — clear, retype, send — discloses
/// nothing at all, because the draft is back before the nudge was ever due.
pub const NUDGE_SETTLE_SECS: u64 = 10;

/// The floor on how often one comrade can be nudged.
///
/// Someone who writes and deletes six times in ten minutes is having a hard
/// time, not sending six signals — and a notification channel that fires on
/// every one of them stops being read. Half an hour keeps a nudge worth
/// noticing.
pub const NUDGE_COOLDOWN_SECS: u64 = 1800;

/// How long a nudge stays believable, measured from its send time.
///
/// Deliberately equal to [`PRESENCE_TTL_SECS`]: the notification a nudge
/// raises says the sender is *around* as well as that they nearly wrote, so it
/// must not outlive the window in which the rest of the app would still
/// believe an "online" claim from them. Two different answers to "are they
/// there?" is how a shade ends up disagreeing with every dot in the app.
pub const NUDGE_TTL_SECS: u64 = PRESENCE_TTL_SECS;

/// Hard ceiling on any peer-supplied TTL, shared with presence for one reason:
/// a nudge is a claim by someone else's device, and without a clamp a buggy or
/// hostile peer could pin "your comrade might need you" in the shade for a
/// year.
pub const NUDGE_MAX_TTL_SECS: u64 = PRESENCE_MAX_TTL_SECS;

// The nudge is only ever *sent* about something inside its own believability
// window (see [`DraftVerdict::Forget`]), so the sender's own staleness bound and
// the receiver's clamp must not disagree.
const _: () = assert!(NUDGE_TTL_SECS <= NUDGE_MAX_TTL_SECS);
const _: () = assert!(NUDGE_SETTLE_SECS < NUDGE_TTL_SECS);

/// The JSON envelope carried inside an E2E DM that tells one comrade a message
/// was written for them and never sent.
///
/// One marker and one deadline. There is no field for the draft, its size, or
/// how the writing ended, because none of those are ours to send.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Nudge {
    /// Format marker / version; must equal [`NUDGE_ENVELOPE_MARKER`].
    pub comrade_nudge: u8,
    /// How many seconds this nudge stays worth raising, clamped by the
    /// receiver to [`NUDGE_MAX_TTL_SECS`].
    pub ttl_secs: u64,
}

impl Default for Nudge {
    fn default() -> Self {
        Self::new()
    }
}

impl Nudge {
    /// "I nearly wrote to you" — worth raising for [`NUDGE_TTL_SECS`].
    pub fn new() -> Self {
        Self {
            comrade_nudge: NUDGE_ENVELOPE_MARKER,
            ttl_secs: NUDGE_TTL_SECS,
        }
    }

    /// Serialise to the JSON string that becomes the DM body.
    pub fn to_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }
}

/// Detect and parse a nudge out of a decrypted DM body. Returns `None` for
/// chat text, receipts, profile shares, presence beacons, call or media
/// envelopes, or any JSON without the nudge marker.
pub fn parse_nudge(content: &str) -> Option<Nudge> {
    let env: Nudge = serde_json::from_str(content).ok()?;
    (env.comrade_nudge == NUDGE_ENVELOPE_MARKER).then_some(env)
}

/// When a nudge sent at `created_at` stops being worth raising.
///
/// Measured from the *send* time, not arrival, exactly like
/// [`crate::presence::presence_expires_at`] — a nudge delayed by a slow relay
/// is already partly spent, and one replayed out of a days-old backfill is
/// entirely spent, which is what keeps at-least-once redelivery from paging
/// someone about a hesitation from Tuesday.
pub fn nudge_expires_at(created_at: u64, ttl_secs: u64) -> u64 {
    created_at.saturating_add(ttl_secs.min(NUDGE_MAX_TTL_SECS))
}

/// Whether a nudge that expires at `expires_at` is still worth raising at
/// `now`. The single rule every read goes through, so a stored or in-flight
/// nudge can never surface after its own deadline.
pub fn is_fresh_at(expires_at: u64, now: u64) -> bool {
    expires_at > now
}

/// One composer's story, as far as this module needs it: text appeared, then it
/// was gone. The unit [`draft_verdict`] decides on.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AbandonedDraft {
    /// When text first appeared in the composer.
    pub started_at: u64,
    /// When it was emptied, or the thread was left with it still unsent. The
    /// two are not distinguished — which of them happened is the writer's
    /// business, and the signal is the same either way.
    pub abandoned_at: u64,
    /// When this peer was last nudged, or 0 if they never have been.
    pub last_nudge_at: u64,
}

/// What to do about an [`AbandonedDraft`] on this pass.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DraftVerdict {
    /// Still inside [`NUDGE_SETTLE_SECS`] — keep waiting, the draft may come
    /// back.
    NotYet,
    /// Tell them.
    Nudge,
    /// Drop it, silently and for good: it was a stray keystroke, it has aged
    /// out of being news, or this comrade was nudged too recently.
    Forget,
}

/// The whole sending-side policy, in one pure function so every frontend
/// inherits the same answer and a test can pin it.
///
/// Order matters, and each rule exists for a case that actually happens:
///  1. **Too brief to mean anything** — a keystroke on a chat that was opened
///     by accident. Never worth a notification on someone else's phone.
///  2. **Not settled yet** — the overwhelmingly common "clear it and rewrite
///     the same sentence". Waiting means that edit discloses nothing.
///  3. **Too old to be news** — a phone that slept for an hour with a pending
///     nudge must not wake up and announce an hour-old hesitation as if it had
///     just happened. Sending it would timestamp it *now*, which would be a
///     lie the receiver has no way to catch.
///  4. **Nudged too recently** — dropped rather than deferred, because a
///     hesitation held until the cooldown lapses is exactly the stale claim
///     rule 3 refuses.
pub fn draft_verdict(draft: AbandonedDraft, now: u64) -> DraftVerdict {
    if draft.abandoned_at.saturating_sub(draft.started_at) < NUDGE_MIN_DWELL_SECS {
        return DraftVerdict::Forget;
    }
    let waited = now.saturating_sub(draft.abandoned_at);
    if waited < NUDGE_SETTLE_SECS {
        return DraftVerdict::NotYet;
    }
    if waited > NUDGE_TTL_SECS {
        return DraftVerdict::Forget;
    }
    if nudged_recently(draft.last_nudge_at, now) {
        return DraftVerdict::Forget;
    }
    DraftVerdict::Nudge
}

/// Whether this comrade was nudged recently enough to be left alone —
/// [`NUDGE_COOLDOWN_SECS`] measured from `last_nudge_at`, where `0` means
/// "never nudged".
///
/// Shared by both triggers on purpose. An abandoned draft and a deliberate
/// "I might need you" are two ways of reaching the same person, and a comrade
/// who gets one must not get the other minutes later — the cooldown is a floor
/// on *notifications*, not on any one reason for them. The `0` case is checked
/// explicitly rather than subtracted, because on a device whose clock is badly
/// wrong `now - 0` would otherwise read as "nudged long ago" or "nudged in the
/// future" depending on which way it is wrong.
pub fn nudged_recently(last_nudge_at: u64, now: u64) -> bool {
    last_nudge_at != 0 && now.saturating_sub(last_nudge_at) < NUDGE_COOLDOWN_SECS
}

/// One watched composer.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
struct WatchedDraft {
    /// When text first appeared, or `None` while the box is empty.
    started_at: Option<u64>,
    /// When the text went away, or `None` while it is still being written.
    abandoned_at: Option<u64>,
    /// When this peer was last nudged; 0 if never.
    last_nudge_at: u64,
}

impl WatchedDraft {
    /// Whether this row still remembers anything worth keeping.
    fn is_empty(&self) -> bool {
        self.started_at.is_none() && self.abandoned_at.is_none() && self.last_nudge_at == 0
    }
}

/// Which composers have unsent text in them, which of them were walked away
/// from, and when each comrade was last nudged — the sending side's whole
/// memory of this feature.
///
/// The last of those is why this is a `NudgeWatch` rather than a
/// `DraftWatch`: the cooldown is shared by every trigger ([`Self::due`] for an
/// abandoned draft, [`Self::due_among`] for a deliberate one), so it cannot
/// live inside whichever one happens to have fired.
///
/// **In memory only, deliberately.** A draft abandoned before the app was last
/// killed is a question for whoever opens the app next, not a promise still
/// owed to their comrade; and a nudge that outlived a process would be
/// announcing a moment from another session as if it were this one. Locking the
/// vault clears it for the same reason ([`Self::clear`]).
///
/// Every method is short, synchronous and takes the lock only for its own
/// duration — [`Self::due`] hands back owned keys precisely so a caller can
/// send without holding anything (this codebase has shipped two deadlocks from
/// a lock held across an `await`).
#[derive(Debug, Default)]
pub struct NudgeWatch {
    drafts: std::sync::Mutex<std::collections::HashMap<String, WatchedDraft>>,
}

impl NudgeWatch {
    pub fn new() -> Self {
        Self::default()
    }

    /// There is unsent text in `peer`'s composer, as of `now`. Called as the
    /// user types, so it is idempotent and cheap: the dwell is always measured
    /// from when text *first* appeared, and text reappearing cancels a pending
    /// nudge — which is what makes "clear it and rewrite the same sentence"
    /// disclose nothing.
    pub fn writing(&self, peer: &str, now: u64) {
        let mut drafts = self.lock();
        let row = drafts.entry(peer.to_string()).or_default();
        row.abandoned_at = None;
        if row.started_at.is_none() {
            row.started_at = Some(now);
        }
    }

    /// `peer`'s draft is gone — emptied, or left behind when the thread was
    /// closed. The two are not distinguished (see [`AbandonedDraft`]).
    ///
    /// A composer that never had text in it does nothing here, so a frontend
    /// can call this unconditionally when a thread closes.
    pub fn abandoned(&self, peer: &str, now: u64) {
        let mut drafts = self.lock();
        let Some(row) = drafts.get_mut(peer) else {
            return;
        };
        // The first abandonment is the honest timestamp: a box cleared at 12:01
        // and left at 12:05 was abandoned at 12:01.
        if row.started_at.is_some() && row.abandoned_at.is_none() {
            row.abandoned_at = Some(now);
        }
    }

    /// A message actually reached `peer` — there is nothing left unsaid, so
    /// whatever was pending is dropped. The cooldown is *not* reset: sending is
    /// not a reason to allow another nudge sooner.
    pub fn sent(&self, peer: &str) {
        let mut drafts = self.lock();
        let Some(row) = drafts.get_mut(peer) else {
            return;
        };
        row.started_at = None;
        row.abandoned_at = None;
        if row.is_empty() {
            drafts.remove(peer);
        }
    }

    /// Forget every composer and every cooldown — for a vault lock, where the
    /// session that owed the nudge is over.
    pub fn clear(&self) {
        self.lock().clear();
    }

    /// Which of `peers` to nudge for a reason that is **not** a draft — the
    /// user deliberately reaching for something (today, the breathing screen),
    /// where the trigger is one tap rather than a timer. Records the nudge
    /// against each returned peer, exactly like [`Self::due`].
    ///
    /// No dwell, no settle window and no staleness check, because none of them
    /// have anything to answer here: there is no draft that might come back,
    /// and the tap is happening *now*. The one rule that does carry over is the
    /// cooldown ([`nudged_recently`]) — a comrade who was told twenty minutes
    /// ago that you might need them learns nothing from being told again, and
    /// two triggers must not add up to two notifications.
    ///
    /// Whatever draft was pending for a returned peer is cleared with it: they
    /// have just been told, and the pending one would be a second notification
    /// for the same stretch of time.
    pub fn due_among(&self, peers: &[String], now: u64) -> Vec<String> {
        let mut drafts = self.lock();
        let mut due = Vec::new();
        for peer in peers {
            let row = drafts.entry(peer.clone()).or_default();
            if nudged_recently(row.last_nudge_at, now) {
                continue;
            }
            row.last_nudge_at = now;
            row.started_at = None;
            row.abandoned_at = None;
            due.push(peer.clone());
        }
        due
    }

    /// The peers whose abandoned draft is due a nudge at `now`, in no
    /// particular order.
    ///
    /// Deciding and recording happen together: each returned peer's cooldown
    /// starts here, so a caller that is slow to send — or fails to — can never
    /// be asked twice for the same hesitation. Drafts the policy has finished
    /// with are dropped on the same pass, so this doubles as the sweep that
    /// keeps the map from growing.
    pub fn due(&self, now: u64) -> Vec<String> {
        let mut drafts = self.lock();
        let mut due = Vec::new();
        for (peer, row) in drafts.iter_mut() {
            let (Some(started_at), Some(abandoned_at)) = (row.started_at, row.abandoned_at) else {
                continue;
            };
            let verdict = draft_verdict(
                AbandonedDraft {
                    started_at,
                    abandoned_at,
                    last_nudge_at: row.last_nudge_at,
                },
                now,
            );
            match verdict {
                DraftVerdict::NotYet => continue,
                DraftVerdict::Nudge => {
                    row.last_nudge_at = now;
                    due.push(peer.clone());
                }
                DraftVerdict::Forget => {}
            }
            row.started_at = None;
            row.abandoned_at = None;
        }
        drafts.retain(|_, row| !row.is_empty());
        due
    }

    /// Poisoning cannot corrupt this map — every method that touches it does a
    /// single infallible edit — so a panicking sibling must not be allowed to
    /// silence the feature for the rest of the session. Same idiom as
    /// [`crate::seen::SeenSet`] and [`crate::dak::Outbox`].
    fn lock(&self) -> std::sync::MutexGuard<'_, std::collections::HashMap<String, WatchedDraft>> {
        self.drafts.lock().unwrap_or_else(|e| e.into_inner())
    }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    /// A draft that satisfies every rule at `now = 1_000_000`, for tests that
    /// want to break exactly one of them.
    fn due_draft() -> AbandonedDraft {
        AbandonedDraft {
            started_at: 1_000_000 - NUDGE_SETTLE_SECS - NUDGE_MIN_DWELL_SECS,
            abandoned_at: 1_000_000 - NUDGE_SETTLE_SECS,
            last_nudge_at: 0,
        }
    }

    #[test]
    fn a_nudge_round_trips() {
        let nudge = Nudge::new();
        let json = nudge.to_json().unwrap();
        assert_eq!(parse_nudge(&json).unwrap(), nudge);
        assert_eq!(nudge, Nudge::default());
        assert_eq!(nudge.ttl_secs, NUDGE_TTL_SECS);
    }

    #[test]
    fn the_envelope_carries_nothing_about_what_was_written() {
        // The privacy claim in this module's docs, enforced rather than
        // asserted: adding a field for the draft, its length, or how the
        // writing ended would fail here.
        let json = Nudge::new().to_json().unwrap();
        let value: serde_json::Value = serde_json::from_str(&json).unwrap();
        let fields: Vec<&str> = value
            .as_object()
            .expect("an object")
            .keys()
            .map(String::as_str)
            .collect();
        assert_eq!(fields, vec!["comrade_nudge", "ttl_secs"]);
    }

    #[test]
    fn a_nudge_is_not_confused_with_any_other_envelope_or_chat_text() {
        for other in [
            "just saying hi",
            "{}",
            r#"{"comrade_profile":1,"username":"neo"}"#,
            r#"{"comrade_receipt":1,"status":"read","message_ids":["m1"]}"#,
            r#"{"comrade_presence":1,"state":"online","ttl_secs":480}"#,
            r#"{"comrade_call":1,"call_id":"c","media":"audio","signal":{"kind":"ringing"}}"#,
            r#"{"comrade_media":1,"event_id":"e","url":"u","mime":"m","caption":"","size":1}"#,
        ] {
            assert!(
                parse_nudge(other).is_none(),
                "must not parse as a nudge: {other}"
            );
        }
        // …and the reverse: a nudge is not a presence beacon, a profile share,
        // a receipt or a call signal.
        let nudge = Nudge::new().to_json().unwrap();
        assert!(crate::presence::parse_presence_beacon(&nudge).is_none());
        assert!(crate::dm::parse_profile_share(&nudge).is_none());
        assert!(crate::dm::parse_receipt(&nudge).is_none());
        assert!(crate::call::parse_call_envelope(&nudge).is_none());
    }

    #[test]
    fn wrong_marker_value_is_rejected() {
        assert!(parse_nudge(r#"{"comrade_nudge":2,"ttl_secs":480}"#).is_none());
        assert!(parse_nudge(r#"{"comrade_nudge":0,"ttl_secs":480}"#).is_none());
    }

    #[test]
    fn a_nudge_expires_from_when_it_was_sent() {
        let sent_at = 1_000_000;
        let expires = nudge_expires_at(sent_at, NUDGE_TTL_SECS);
        assert_eq!(expires, sent_at + NUDGE_TTL_SECS);
        assert!(is_fresh_at(expires, sent_at + NUDGE_TTL_SECS - 1));
        assert!(
            !is_fresh_at(expires, sent_at + NUDGE_TTL_SECS),
            "deadline is exclusive"
        );
    }

    #[test]
    fn a_peer_cannot_pin_a_nudge_indefinitely() {
        let sent_at = 1_000_000;
        let expires = nudge_expires_at(sent_at, u64::MAX);
        assert_eq!(expires, sent_at + NUDGE_MAX_TTL_SECS);
        assert!(!is_fresh_at(expires, sent_at + NUDGE_MAX_TTL_SECS + 1));
    }

    #[test]
    fn a_replayed_nudge_from_the_backfill_window_is_already_spent() {
        // Relays redeliver at-least-once and the vault inbox backfills up to
        // two days on every launch: a nudge that old must raise nothing.
        let two_days = 2 * 24 * 60 * 60;
        let now = 5_000_000;
        assert!(!is_fresh_at(
            nudge_expires_at(now - two_days, NUDGE_TTL_SECS),
            now
        ));
    }

    #[test]
    fn a_settled_hesitation_is_worth_telling_them_about() {
        assert_eq!(draft_verdict(due_draft(), 1_000_000), DraftVerdict::Nudge);
    }

    #[test]
    fn a_stray_keystroke_is_never_a_nudge() {
        let now = 1_000_000;
        let mut draft = due_draft();
        // Text appeared and was gone again inside the dwell window — a thumb
        // on a chat that was opened by accident.
        draft.started_at = draft.abandoned_at - (NUDGE_MIN_DWELL_SECS - 1);
        assert_eq!(draft_verdict(draft, now), DraftVerdict::Forget);
        // The boundary itself counts as writing.
        draft.started_at = draft.abandoned_at - NUDGE_MIN_DWELL_SECS;
        assert_eq!(draft_verdict(draft, now), DraftVerdict::Nudge);
    }

    #[test]
    fn clearing_the_box_to_rewrite_the_same_sentence_stays_private() {
        // The verdict during the settle window is "wait", never "send" — which
        // is what gives a rewrite the chance to cancel it.
        let mut draft = due_draft();
        draft.abandoned_at = 1_000_000;
        for elapsed in 0..NUDGE_SETTLE_SECS {
            assert_eq!(
                draft_verdict(draft, 1_000_000 + elapsed),
                DraftVerdict::NotYet,
                "must still be waiting {elapsed}s after the draft went away"
            );
        }
        assert_eq!(
            draft_verdict(draft, 1_000_000 + NUDGE_SETTLE_SECS),
            DraftVerdict::Nudge
        );
    }

    #[test]
    fn a_phone_that_slept_for_an_hour_does_not_announce_an_old_hesitation() {
        // The dishonest direction: sending stamps the nudge with *now*, so a
        // stale pending entry would present an hour-old moment as this one.
        let draft = due_draft();
        let long_after = draft.abandoned_at + NUDGE_TTL_SECS + 1;
        assert_eq!(draft_verdict(draft, long_after), DraftVerdict::Forget);
        // Right on the deadline it is still news.
        assert_eq!(
            draft_verdict(draft, draft.abandoned_at + NUDGE_TTL_SECS),
            DraftVerdict::Nudge
        );
    }

    #[test]
    fn a_comrade_nudged_recently_is_left_alone() {
        let now = 1_000_000;
        let mut draft = due_draft();
        draft.last_nudge_at = now - (NUDGE_COOLDOWN_SECS - 1);
        assert_eq!(
            draft_verdict(draft, now),
            DraftVerdict::Forget,
            "a second hesitation inside the cooldown is dropped, not queued"
        );
        draft.last_nudge_at = now - NUDGE_COOLDOWN_SECS;
        assert_eq!(draft_verdict(draft, now), DraftVerdict::Nudge);
    }

    // ── NudgeWatch ───────────────────────────────────────────────────────────

    /// `T0` plus enough dwell that abandoning at `T0 + DWELL` is a hesitation.
    const T0: u64 = 1_000_000;
    const DWELL: u64 = NUDGE_MIN_DWELL_SECS;

    #[test]
    fn a_draft_left_behind_comes_due_once_it_has_settled() {
        let watch = NudgeWatch::new();
        watch.writing("alice", T0);
        watch.abandoned("alice", T0 + DWELL);
        assert!(
            watch.due(T0 + DWELL + NUDGE_SETTLE_SECS - 1).is_empty(),
            "nothing is due while the draft could still come back"
        );
        assert_eq!(
            watch.due(T0 + DWELL + NUDGE_SETTLE_SECS),
            vec!["alice".to_string()]
        );
    }

    #[test]
    fn a_rewritten_draft_never_comes_due() {
        // Clear the box, start typing again inside the settle window, send.
        // Nothing should ever have left the device.
        let watch = NudgeWatch::new();
        watch.writing("alice", T0);
        watch.abandoned("alice", T0 + DWELL);
        watch.writing("alice", T0 + DWELL + 1);
        assert!(watch.due(T0 + DWELL + NUDGE_SETTLE_SECS + 1).is_empty());
        watch.sent("alice");
        assert!(watch.due(T0 + 10_000).is_empty());
    }

    #[test]
    fn sending_the_message_cancels_the_nudge_it_would_have_replaced() {
        let watch = NudgeWatch::new();
        watch.writing("alice", T0);
        watch.abandoned("alice", T0 + DWELL);
        watch.sent("alice");
        assert!(
            watch.due(T0 + DWELL + NUDGE_SETTLE_SECS).is_empty(),
            "a delivered message says everything the nudge would have"
        );
    }

    #[test]
    fn one_hesitation_is_nudged_once_however_often_the_sweep_runs() {
        let watch = NudgeWatch::new();
        watch.writing("alice", T0);
        watch.abandoned("alice", T0 + DWELL);
        let due_at = T0 + DWELL + NUDGE_SETTLE_SECS;
        assert_eq!(watch.due(due_at).len(), 1);
        assert!(
            watch.due(due_at + 1).is_empty(),
            "the decision is recorded when it is made, not when the send lands"
        );
    }

    #[test]
    fn the_cooldown_outlives_the_draft_it_was_earned_by() {
        let watch = NudgeWatch::new();
        watch.writing("alice", T0);
        watch.abandoned("alice", T0 + DWELL);
        let first = T0 + DWELL + NUDGE_SETTLE_SECS;
        assert_eq!(watch.due(first).len(), 1);

        // Hesitate again a minute later: dropped, because the cooldown is
        // remembered even though the draft row it came from is finished with.
        watch.writing("alice", first + 60);
        watch.abandoned("alice", first + 60 + DWELL);
        assert!(watch.due(first + 60 + DWELL + NUDGE_SETTLE_SECS).is_empty());

        // …and once it lapses, a fresh hesitation is news again.
        let later = first + NUDGE_COOLDOWN_SECS;
        watch.writing("alice", later);
        watch.abandoned("alice", later + DWELL);
        assert_eq!(
            watch.due(later + DWELL + NUDGE_SETTLE_SECS).len(),
            1,
            "half an hour on, a hesitation is worth telling them about again"
        );
    }

    #[test]
    fn an_empty_composer_that_is_closed_is_not_a_hesitation() {
        let watch = NudgeWatch::new();
        // A frontend calls this unconditionally when a thread closes; a box
        // that never had text in it must produce nothing.
        watch.abandoned("alice", T0);
        assert!(watch.due(T0 + NUDGE_SETTLE_SECS + DWELL).is_empty());
    }

    #[test]
    fn the_first_abandonment_is_the_one_that_counts() {
        // Cleared at T0+DWELL, then the thread closed 5 minutes later. The
        // hesitation happened at the clearing, and dating it from the close
        // would both delay the nudge and overstate when they were there.
        let watch = NudgeWatch::new();
        watch.writing("alice", T0);
        watch.abandoned("alice", T0 + DWELL);
        watch.abandoned("alice", T0 + DWELL + 300);
        assert_eq!(
            watch.due(T0 + DWELL + NUDGE_SETTLE_SECS).len(),
            1,
            "due on the settle window after the box was emptied"
        );
    }

    #[test]
    fn locking_the_vault_forgets_every_composer_and_every_cooldown() {
        let watch = NudgeWatch::new();
        watch.writing("alice", T0);
        watch.abandoned("alice", T0 + DWELL);
        watch.clear();
        assert!(watch.due(T0 + DWELL + NUDGE_SETTLE_SECS).is_empty());
    }

    #[test]
    fn each_comrade_is_watched_separately() {
        let watch = NudgeWatch::new();
        watch.writing("alice", T0);
        watch.writing("bhaskar", T0);
        watch.abandoned("alice", T0 + DWELL);
        watch.sent("bhaskar");
        assert_eq!(
            watch.due(T0 + DWELL + NUDGE_SETTLE_SECS),
            vec!["alice".to_string()]
        );
    }

    // ── The deliberate trigger (due_among) ───────────────────────────────────

    fn peers(names: &[&str]) -> Vec<String> {
        names.iter().map(|n| n.to_string()).collect()
    }

    #[test]
    fn reaching_for_a_pause_tells_every_comrade_once() {
        let watch = NudgeWatch::new();
        let comrades = peers(&["alice", "bhaskar"]);
        assert_eq!(watch.due_among(&comrades, T0), comrades);
        assert!(
            watch.due_among(&comrades, T0 + 1).is_empty(),
            "tapping twice is one moment, not two"
        );
        assert_eq!(
            watch.due_among(&comrades, T0 + NUDGE_COOLDOWN_SECS),
            comrades,
            "half an hour on, it is worth telling them again"
        );
    }

    #[test]
    fn the_two_triggers_share_one_cooldown() {
        // The property that keeps a hard afternoon from becoming a pager: a
        // comrade told by one trigger is not told again by the other.
        let watch = NudgeWatch::new();
        let comrades = peers(&["alice"]);
        assert_eq!(watch.due_among(&comrades, T0).len(), 1);

        watch.writing("alice", T0 + 60);
        watch.abandoned("alice", T0 + 60 + DWELL);
        assert!(
            watch.due(T0 + 60 + DWELL + NUDGE_SETTLE_SECS).is_empty(),
            "a draft given up on minutes after a pause must not nudge again"
        );
    }

    #[test]
    fn the_cooldown_holds_in_the_other_direction_too() {
        let watch = NudgeWatch::new();
        watch.writing("alice", T0);
        watch.abandoned("alice", T0 + DWELL);
        let first = T0 + DWELL + NUDGE_SETTLE_SECS;
        assert_eq!(watch.due(first).len(), 1);
        assert!(
            watch.due_among(&peers(&["alice"]), first + 60).is_empty(),
            "reaching for a pause just after being nudged says nothing new"
        );
    }

    #[test]
    fn a_deliberate_nudge_settles_the_draft_it_overtakes() {
        // Mid-sentence to Alice, then a pause is taken. She is told once; the
        // draft that follows must not become a second notification for the
        // same stretch of time — and the cooldown would refuse it anyway, so
        // this only makes that explicit.
        let watch = NudgeWatch::new();
        watch.writing("alice", T0);
        assert_eq!(watch.due_among(&peers(&["alice"]), T0 + 1).len(), 1);
        watch.abandoned("alice", T0 + 1 + DWELL);
        assert!(watch.due(T0 + 1 + DWELL + NUDGE_SETTLE_SECS).is_empty());
    }

    #[test]
    fn with_no_comrades_a_pause_tells_nobody_and_remembers_nothing() {
        // The feature has to be free for someone who never chose anyone.
        let watch = NudgeWatch::new();
        assert!(watch.due_among(&[], T0).is_empty());
        assert!(watch.lock().is_empty());
    }

    #[test]
    fn a_finished_draft_leaves_nothing_behind_to_accumulate() {
        // The map is swept by the same pass that decides, so watching a
        // composer for a peer who is never nudged costs nothing lasting.
        let watch = NudgeWatch::new();
        watch.writing("alice", T0);
        // Too brief to mean anything → Forget, and the row goes with it.
        watch.abandoned("alice", T0);
        assert!(watch.due(T0 + NUDGE_SETTLE_SECS).is_empty());
        assert!(watch.lock().is_empty());
    }

    #[test]
    fn a_never_nudged_comrade_is_not_mistaken_for_one_nudged_at_the_epoch() {
        // `last_nudge_at: 0` means "never", and must not be read as a
        // timestamp — with a small `now` (every test clock, and a device whose
        // clock is badly wrong) the subtraction would otherwise say "nudged
        // 1000 seconds ago" and suppress a first nudge forever.
        let draft = AbandonedDraft {
            started_at: 100,
            abandoned_at: 100 + NUDGE_MIN_DWELL_SECS,
            last_nudge_at: 0,
        };
        assert_eq!(
            draft_verdict(draft, 100 + NUDGE_MIN_DWELL_SECS + NUDGE_SETTLE_SECS),
            DraftVerdict::Nudge
        );
    }
}
