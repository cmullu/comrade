/*!
 * attention — the attention-restoration engine (wellbeing pillar #5).
 *
 * Pure, deterministic logic for helping someone rebuild an attention span
 * eroded by doomscrolling and short-video feeds: self-relative usage
 * comparisons (the *mirror*), a focus-session lifecycle with a progressive
 * duration rule (the *practice*), and chapter-sized chunking for
 * distraction-free long reading. See `docs/ATTENTION.md` for the full design
 * and its honesty gates; two are load-bearing here:
 *
 * 1. **Practice, not treatment.** Nothing in this module scores cognition,
 *    compares the user to anyone else, or claims to "restore" anything. Every
 *    comparison is against the user's *own* recent days, and every string is
 *    an invitation, never a verdict.
 * 2. **No loss-aversion mechanics.** An abandoned session is recorded without
 *    ceremony; a lapsed one resolves quietly. There is no streak to "break",
 *    and the reflection prompts below say what happened, not what it cost.
 *
 * Like `tara`, determinism doubles as testability: the same inputs always
 * produce the same suggestion/prompt, so behaviour is pinned by unit tests.
 */

/// One day of usage as the engine sees it — the *rollup only*. The raw
/// per-app event stream is read and reduced on the frontend and never
/// persisted; data minimisation is the point (`docs/ATTENTION.md` gate 2).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UsageSignal {
    pub screen_minutes: u32,
    /// Minutes in the apps the user themself tagged as their doom apps.
    pub doom_minutes: u32,
    pub pickups: u32,
}

/// Today's usage next to the user's own recent medians — the self-relative
/// framing the mirror card renders ("less than your usual", never a red
/// number against someone else's normal).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UsageComparison {
    pub today: UsageSignal,
    pub median_screen_minutes: u32,
    pub median_doom_minutes: u32,
    pub median_pickups: u32,
    /// How many prior days the medians are drawn from (0–7). The UI must not
    /// pretend one prior day is "your usual" — it says how thin the sample is.
    pub sample_days: u32,
}

/// Median of `values` (upper of the two middles for an even count — the
/// conservative choice: it never makes today look worse than the sample
/// actually supports).
fn median(values: &mut [u32]) -> Option<u32> {
    if values.is_empty() {
        return None;
    }
    values.sort_unstable();
    Some(values[values.len() / 2])
}

/// Compare `today` against up to the last 7 `prior_days` (any order).
pub fn compare_today(today: UsageSignal, prior_days: &[UsageSignal]) -> UsageComparison {
    let sample: Vec<UsageSignal> = prior_days.iter().copied().take(7).collect();
    let m = |f: fn(&UsageSignal) -> u32| {
        let mut v: Vec<u32> = sample.iter().map(f).collect();
        median(&mut v).unwrap_or(0)
    };
    UsageComparison {
        today,
        median_screen_minutes: m(|s| s.screen_minutes),
        median_doom_minutes: m(|s| s.doom_minutes),
        median_pickups: m(|s| s.pickups),
        sample_days: sample.len() as u32,
    }
}

/// The Tara opener nudge for a heavy scroll day, or `None` when there is
/// nothing worth saying. Deliberately quiet by default: it fires only when
/// yesterday was *both* absolutely heavy (an hour or more in doom apps) and
/// clearly above the user's own median — a normal day never earns a comment.
///
/// The wording is an invitation to reflect, not a judgement; this is a
/// journaling nudge, and the number it quotes is the user's own.
pub fn usage_opener(
    yesterday: Option<UsageSignal>,
    prior_median_doom: Option<u32>,
) -> Option<String> {
    let y = yesterday?;
    let heavy = match prior_median_doom {
        // 1.5× the user's own median, and at least an hour.
        Some(median) => y.doom_minutes >= 60 && y.doom_minutes as u64 * 2 >= median as u64 * 3,
        // No baseline yet: only an unmistakably heavy day (90+ min) speaks.
        None => y.doom_minutes >= 90,
    };
    heavy.then(|| {
        format!(
            "Yesterday had about {} minutes in the apps you marked as scroll traps. \
             No judgement — the number is yours alone. Want to note how you feel \
             about it, or just talk?",
            y.doom_minutes
        )
    })
}

// ── Focus sessions ────────────────────────────────────────────────────────────

/// Deliberate single-task durations, in minutes, smallest first. The
/// progressive rule ([`suggest_focus_minutes`]) walks this ladder.
pub const FOCUS_PRESETS: &[u32] = &[25, 45, 90];

/// How long past its planned end a running session may still be finished
/// honestly. Beyond this it resolves to [`FocusOutcome::Lapsed`] — the app was
/// killed or forgotten, and pretending the whole span was focused would make
/// the history lie.
pub const FOCUS_GRACE_SECS: u64 = 300;

/// Sessions shorter than this teach nothing; longer than this is a workday,
/// not a session. Bounds for a caller-supplied custom duration.
pub const FOCUS_MIN_MINUTES: u32 = 5;
pub const FOCUS_MAX_MINUTES: u32 = 180;

/// How a focus session ended.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FocusOutcome {
    /// The user saw it through and said so.
    Completed,
    /// The user stopped it early — recorded without ceremony (gate 2).
    Abandoned,
    /// Nobody came back to say either way (app killed, phone down); resolved
    /// automatically once the planned end plus [`FOCUS_GRACE_SECS`] passed.
    Lapsed,
}

impl FocusOutcome {
    /// The stored form. `as_str`/`from_key` rather than `Display`/`FromStr`
    /// deliberately, matching `AppWorkspace`'s `key`/`from_key` pair: these
    /// strings are a storage and FFI contract, not user-facing text, so they
    /// must not drift with any future formatting change.
    pub fn as_str(&self) -> &'static str {
        match self {
            FocusOutcome::Completed => "completed",
            FocusOutcome::Abandoned => "abandoned",
            FocusOutcome::Lapsed => "lapsed",
        }
    }

    /// Parse a stored outcome; `None` for anything unrecognised.
    pub fn from_key(s: &str) -> Option<Self> {
        match s {
            "completed" => Some(FocusOutcome::Completed),
            "abandoned" => Some(FocusOutcome::Abandoned),
            "lapsed" => Some(FocusOutcome::Lapsed),
            _ => None,
        }
    }
}

/// The next duration worth suggesting, from `(planned_minutes, outcome)`
/// history (any order). This is the "rebuild the span" mechanic, honestly
/// framed as practice: each preset unlocks after **two completions** at that
/// length or longer, and nothing ever locks again — an abandoned session
/// costs no progress (gate 2: no loss aversion).
pub fn suggest_focus_minutes(history: &[(u32, FocusOutcome)]) -> u32 {
    let completions_at_least = |minutes: u32| {
        history
            .iter()
            .filter(|(m, o)| *o == FocusOutcome::Completed && *m >= minutes)
            .count()
    };
    let mut suggestion = FOCUS_PRESETS[0];
    for pair in FOCUS_PRESETS.windows(2) {
        if completions_at_least(pair[0]) >= 2 {
            suggestion = pair[1];
        } else {
            break;
        }
    }
    suggestion
}

/// Whether a still-running session has quietly expired. `Some(Lapsed)` once
/// `now` is past the planned end plus the grace window, else `None` (still
/// honestly in progress, or in the grace window where "I just finished" is
/// still believable).
pub fn resolve_stale(planned_minutes: u32, started_at: u64, now: u64) -> Option<FocusOutcome> {
    let deadline = started_at + u64::from(planned_minutes) * 60 + FOCUS_GRACE_SECS;
    (now > deadline).then_some(FocusOutcome::Lapsed)
}

/// Seconds left in a running session (0 once the planned end has passed).
pub fn remaining_secs(planned_minutes: u32, started_at: u64, now: u64) -> u64 {
    (started_at + u64::from(planned_minutes) * 60).saturating_sub(now)
}

/// Pre-session nudges, rotated by how many sessions came before — the same
/// turn-seeded rotation Tara's prompts use. Each asks for a concrete
/// intention, because "focus" without an object is just a timer.
const FOCUS_PROMPTS: &[&str] = &[
    "One thing, named out loud: what will you give this time to?",
    "What would make you close this session glad you started it?",
    "Pick the task you've been circling — the one you keep almost starting.",
    "Small is fine. What's the next concrete step you can actually finish?",
];

/// The nudge shown before starting a session. `prior_sessions` seeds the
/// rotation so consecutive sessions don't repeat.
pub fn focus_prompt(prior_sessions: u64) -> &'static str {
    FOCUS_PROMPTS[(prior_sessions as usize) % FOCUS_PROMPTS.len()]
}

/// Reflection prompts after a completed session, rotated like the above.
const COMPLETION_REFLECTIONS: &[&str] = &[
    "Done. What did you actually get into? One line is plenty.",
    "That's the practice working. What surprised you about staying with it?",
    "Session complete. Where did your attention try to wander, and what pulled it back?",
];

/// The line shown when a session ends, honest about how it ended. Completed
/// sessions get a reflection prompt (which hands off to a journal line);
/// abandoned and lapsed ones get plain acknowledgement — never guilt (gate 2).
pub fn focus_reflection(outcome: FocusOutcome, prior_sessions: u64) -> String {
    match outcome {
        FocusOutcome::Completed => {
            COMPLETION_REFLECTIONS[(prior_sessions as usize) % COMPLETION_REFLECTIONS.len()].to_string()
        }
        FocusOutcome::Abandoned => {
            "Stopped early — that's information, not failure. Worth a line about what pulled you away?".to_string()
        }
        FocusOutcome::Lapsed => {
            "That session ended without a check-in, so it's recorded as lapsed. Nothing lost — start another when you're ready.".to_string()
        }
    }
}

// ── Long-read chunking ────────────────────────────────────────────────────────

/// Soft target for one reading chunk, in characters — roughly a few minutes
/// of sustained reading. Paragraphs pack greedily up to this.
const CHUNK_TARGET_CHARS: usize = 1400;

/// A single paragraph longer than this splits at a whitespace boundary — a
/// wall of unbroken text should not defeat the "one chapter at a time" rule.
const CHUNK_HARD_MAX_CHARS: usize = 2200;

/// Split `text` into chapter-sized chunks for the distraction-free reader.
///
/// **Lossless by contract**: `chunks.concat() == text`, always — the reader
/// must show the user exactly what they saved, so chunking may never eat a
/// character (the same rule `TaraStream.chunk` pins on the Kotlin side).
/// Splits prefer paragraph boundaries, then whitespace; only a single
/// unbroken run longer than [`CHUNK_HARD_MAX_CHARS`] splits mid-word.
pub fn chunk_reading(text: &str) -> Vec<String> {
    if text.is_empty() {
        return Vec::new();
    }
    // Paragraph pieces, each carrying its trailing separator so concatenation
    // stays lossless.
    let mut pieces: Vec<&str> = Vec::new();
    let mut rest = text;
    while let Some(idx) = rest.find("\n\n") {
        // Include the blank line (and any further consecutive newlines) with
        // the paragraph it ends.
        let mut end = idx + 2;
        while rest[end..].starts_with('\n') {
            end += 1;
        }
        pieces.push(&rest[..end]);
        rest = &rest[end..];
    }
    if !rest.is_empty() {
        pieces.push(rest);
    }

    let mut chunks: Vec<String> = Vec::new();
    let mut current = String::new();
    for piece in pieces {
        if !current.is_empty() && current.len() + piece.len() > CHUNK_TARGET_CHARS {
            chunks.push(std::mem::take(&mut current));
        }
        if piece.len() > CHUNK_HARD_MAX_CHARS {
            if !current.is_empty() {
                chunks.push(std::mem::take(&mut current));
            }
            for part in split_long_run(piece) {
                chunks.push(part.to_string());
            }
        } else {
            current.push_str(piece);
        }
    }
    if !current.is_empty() {
        chunks.push(current);
    }
    chunks
}

/// Split one oversized paragraph at whitespace (or, failing that, at char
/// boundaries) into pieces of at most [`CHUNK_HARD_MAX_CHARS`].
fn split_long_run(piece: &str) -> Vec<&str> {
    let mut parts = Vec::new();
    let mut rest = piece;
    while rest.len() > CHUNK_HARD_MAX_CHARS {
        // Latest whitespace at or before the hard max, respecting UTF-8.
        let mut cut = None;
        for (i, c) in rest.char_indices() {
            if i > CHUNK_HARD_MAX_CHARS {
                break;
            }
            if c.is_whitespace() {
                cut = Some(i + c.len_utf8());
            }
        }
        let cut = cut.unwrap_or_else(|| {
            // No whitespace at all: cut at the last char boundary in range.
            let mut boundary = CHUNK_HARD_MAX_CHARS;
            while !rest.is_char_boundary(boundary) {
                boundary -= 1;
            }
            boundary
        });
        parts.push(&rest[..cut]);
        rest = &rest[cut..];
    }
    if !rest.is_empty() {
        parts.push(rest);
    }
    parts
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn day(screen: u32, doom: u32, pickups: u32) -> UsageSignal {
        UsageSignal {
            screen_minutes: screen,
            doom_minutes: doom,
            pickups,
        }
    }

    #[test]
    fn comparison_is_self_relative_and_honest_about_thin_samples() {
        let today = day(200, 90, 60);
        let cmp = compare_today(today, &[]);
        assert_eq!(cmp.sample_days, 0, "no prior days: the UI must say so");
        assert_eq!(cmp.median_screen_minutes, 0);

        let prior = [day(100, 30, 40), day(300, 90, 80), day(200, 60, 60)];
        let cmp = compare_today(today, &prior);
        assert_eq!(cmp.sample_days, 3);
        assert_eq!(cmp.median_screen_minutes, 200);
        assert_eq!(cmp.median_doom_minutes, 60);
        assert_eq!(cmp.median_pickups, 60);
        assert_eq!(cmp.today, today);
    }

    #[test]
    fn comparison_uses_at_most_seven_prior_days() {
        let prior: Vec<UsageSignal> = (0..10).map(|i| day(i * 100, 0, 0)).collect();
        let cmp = compare_today(day(0, 0, 0), &prior);
        assert_eq!(cmp.sample_days, 7);
    }

    #[test]
    fn usage_opener_stays_quiet_on_ordinary_days() {
        // No data at all.
        assert_eq!(usage_opener(None, None), None);
        // A normal day near the median says nothing.
        assert_eq!(usage_opener(Some(day(200, 65, 50)), Some(60)), None);
        // Heavy relative to median but under the absolute floor: quiet.
        assert_eq!(usage_opener(Some(day(200, 45, 50)), Some(10)), None);
        // No baseline yet and not unmistakably heavy: quiet.
        assert_eq!(usage_opener(Some(day(200, 80, 50)), None), None);
    }

    #[test]
    fn usage_opener_speaks_only_on_genuinely_heavy_days_and_quotes_the_users_own_number() {
        let s = usage_opener(Some(day(400, 120, 90)), Some(60)).expect("should speak");
        assert!(s.contains("120 minutes"), "got: {s}");
        assert!(s.contains("No judgement"), "the framing gate: {s}");

        // First heavy day with no baseline.
        assert!(usage_opener(Some(day(300, 95, 70)), None).is_some());
    }

    #[test]
    fn focus_suggestion_starts_small_and_unlocks_by_completion() {
        assert_eq!(suggest_focus_minutes(&[]), 25);
        // One completion isn't enough.
        assert_eq!(suggest_focus_minutes(&[(25, FocusOutcome::Completed)]), 25);
        // Two completions at 25 unlock 45.
        let two = [(25, FocusOutcome::Completed), (25, FocusOutcome::Completed)];
        assert_eq!(suggest_focus_minutes(&two), 45);
        // Completions at 45 count toward the 25 threshold too.
        let mixed = [(45, FocusOutcome::Completed), (45, FocusOutcome::Completed)];
        assert_eq!(suggest_focus_minutes(&mixed), 90);
        // 90 is the ceiling.
        let many: Vec<(u32, FocusOutcome)> =
            (0..10).map(|_| (90, FocusOutcome::Completed)).collect();
        assert_eq!(suggest_focus_minutes(&many), 90);
    }

    #[test]
    fn every_offered_length_is_one_the_ladder_can_suggest_back() {
        // The frontends draw a chip per FOCUS_PRESETS entry and the ladder
        // reads the same list, so a rung that no history can reach would be a
        // length the app offers and then never suggests again — and one the
        // engine's own `windows(2)` walk would step straight past.
        assert!(!FOCUS_PRESETS.is_empty());
        assert!(FOCUS_PRESETS.windows(2).all(|w| w[0] < w[1]), "ascending");

        let mut history: Vec<(u32, FocusOutcome)> = Vec::new();
        for (i, &rung) in FOCUS_PRESETS.iter().enumerate() {
            assert_eq!(
                suggest_focus_minutes(&history),
                rung,
                "rung {i} ({rung}m) is unreachable",
            );
            // Two completions at this rung are what unlocks the next one.
            history.push((rung, FocusOutcome::Completed));
            history.push((rung, FocusOutcome::Completed));
        }
    }

    #[test]
    fn abandoned_and_lapsed_sessions_cost_no_progress() {
        // Gate 2: no loss aversion — an abandon never drops the suggestion.
        let history = [
            (25, FocusOutcome::Completed),
            (25, FocusOutcome::Completed),
            (45, FocusOutcome::Abandoned),
            (45, FocusOutcome::Lapsed),
        ];
        assert_eq!(suggest_focus_minutes(&history), 45);
    }

    #[test]
    fn stale_sessions_resolve_to_lapsed_only_after_the_grace_window() {
        let started = 1_000_000;
        // Mid-session: still running.
        assert_eq!(resolve_stale(25, started, started + 10 * 60), None);
        // Just past the end, inside grace: the user may still be finishing.
        assert_eq!(resolve_stale(25, started, started + 25 * 60 + 60), None);
        // Past end + grace: lapsed.
        assert_eq!(
            resolve_stale(25, started, started + 25 * 60 + FOCUS_GRACE_SECS + 1),
            Some(FocusOutcome::Lapsed)
        );
    }

    #[test]
    fn remaining_seconds_count_down_and_floor_at_zero() {
        assert_eq!(remaining_secs(25, 1000, 1000), 25 * 60);
        assert_eq!(remaining_secs(25, 1000, 1000 + 10 * 60), 15 * 60);
        assert_eq!(remaining_secs(25, 1000, 1000 + 30 * 60), 0);
    }

    #[test]
    fn focus_prompts_rotate_deterministically() {
        assert_ne!(focus_prompt(0), focus_prompt(1));
        assert_eq!(focus_prompt(0), focus_prompt(FOCUS_PROMPTS.len() as u64));
    }

    #[test]
    fn reflections_match_the_outcome_and_never_guilt() {
        let done = focus_reflection(FocusOutcome::Completed, 0);
        assert!(done.contains("One line"), "got: {done}");
        assert_ne!(
            focus_reflection(FocusOutcome::Completed, 0),
            focus_reflection(FocusOutcome::Completed, 1)
        );
        let abandoned = focus_reflection(FocusOutcome::Abandoned, 3);
        assert!(abandoned.contains("not failure"), "got: {abandoned}");
        let lapsed = focus_reflection(FocusOutcome::Lapsed, 3);
        assert!(lapsed.contains("Nothing lost"), "got: {lapsed}");
    }

    #[test]
    fn outcome_string_roundtrip() {
        for o in [
            FocusOutcome::Completed,
            FocusOutcome::Abandoned,
            FocusOutcome::Lapsed,
        ] {
            assert_eq!(FocusOutcome::from_key(o.as_str()), Some(o));
        }
        assert_eq!(FocusOutcome::from_key("great"), None);
    }

    // ── chunking ──────────────────────────────────────────────────────────

    #[test]
    fn chunking_is_lossless() {
        // The reader must show exactly what was saved — same contract as
        // TaraStream.chunk on the Kotlin side.
        let texts = [
            String::new(),
            "one short line".to_string(),
            "para one\n\npara two\n\n\npara three with trailing\n\n".to_string(),
            "word ".repeat(2000),
            "x".repeat(10_000), // one unbroken run, no whitespace at all
            format!("{}\n\n{}", "a".repeat(3000), "short tail"),
            "🦀".repeat(1000), // multi-byte chars must never split mid-boundary
        ];
        for text in &texts {
            let chunks = chunk_reading(text);
            assert_eq!(
                &chunks.concat(),
                text,
                "lost characters chunking {:.20}…",
                text
            );
        }
    }

    #[test]
    fn chunks_stay_chapter_sized() {
        let text = "A paragraph of respectable length, maybe a hundred characters or so, to pack repeatedly.\n\n".repeat(60);
        let chunks = chunk_reading(&text);
        assert!(chunks.len() > 1, "long text must actually split");
        for c in &chunks {
            assert!(
                c.len() <= CHUNK_HARD_MAX_CHARS,
                "chunk of {} chars",
                c.len()
            );
        }
    }

    #[test]
    fn paragraphs_are_preferred_boundaries() {
        let text = format!("{}\n\n{}", "first ".repeat(150), "second ".repeat(150));
        let chunks = chunk_reading(&text);
        assert_eq!(chunks.len(), 2);
        assert!(chunks[0].ends_with("\n\n"));
        assert!(chunks[1].starts_with("second"));
    }
}
