/*!
 * stretch — guided body stretches, for the body attached to the attention.
 *
 * The attention pillar already has a paced-breathing screen (`BreathingScreen`
 * on Android) for the nervous system and a focus timer for the mind. What it
 * had nothing for is the part of a long focus session that actually hurts: an
 * hour still in one chair, neck forward, shoulders up. Opera Air's neck
 * exercises are the reference here, and the reason to copy the idea is not that
 * it is fashionable — it is that a break someone will *take* has to be short,
 * guided, and impossible to fail, and a timer that says "get up for a bit" is
 * none of those.
 *
 * This module is the choreography and nothing else: which movements, in which
 * order, for how many seconds, and which side of the body each one is for. It
 * holds no state, does no I/O, and stores nothing.
 *
 * ## Keys, not sentences
 *
 * A step is a `key` (`"neck_tilt_left"`), not an instruction. The words are the
 * frontend's — `R.string.stretch_neck_tilt_left` on Android,
 * `stretch_view.mjs`'s table on desktop — for the same reason
 * `FocusOutcome::as_str` is a key and `breathe_lines_in` lives in
 * `strings.xml`: an engine that shipped English prose would ship a screen that
 * cannot be translated, and this is a screen a person reads while holding a
 * position they cannot see.
 *
 * ## The gates this module holds
 *
 * These follow `docs/ATTENTION.md` §2, and two of them are enforced by tests
 * rather than by good intentions:
 *
 * 1. **No claims.** Nothing here says a stretch treats, prevents or corrects
 *    anything. It is movement that feels better than not moving, offered as a
 *    break. Frontend copy that promises more than that is a bug.
 * 2. **Symmetry is not optional** ([`tests::every_sided_step_has_its_mirror`]).
 *    A routine that stretches the left side for twenty seconds and the right
 *    for ten is worse than no routine, and it is the single easiest thing to
 *    get wrong when editing a table by hand.
 * 3. **No breath-holding, no end-range forcing, no "push through".** The
 *    movements are all gentle-range and self-limiting; see
 *    [`BreathPhase`](crate::attention)'s neighbour in `BreathingScreen.kt` for
 *    why an end-expiratory hold in particular is avoided in this codebase.
 * 4. **Nothing is recorded.** Like the breathing screen, there is no count of
 *    stretch breaks taken, because a number would turn a break into a task —
 *    and this pillar's third gate forbids exactly that.
 */

/// Which part of the body a step moves. Drives the frontend's animated guide
/// (which region of the figure highlights), never the copy.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BodyPart {
    Neck,
    Shoulders,
    /// Wrists, hands, forearms — the typing end.
    Arms,
    Back,
    Hips,
    Legs,
    Eyes,
    /// The whole body at once: standing tall, shaking out.
    Whole,
}

impl BodyPart {
    /// Storage/FFI form; the frontend owns the label.
    pub fn as_str(&self) -> &'static str {
        match self {
            BodyPart::Neck => "neck",
            BodyPart::Shoulders => "shoulders",
            BodyPart::Arms => "arms",
            BodyPart::Back => "back",
            BodyPart::Hips => "hips",
            BodyPart::Legs => "legs",
            BodyPart::Eyes => "eyes",
            BodyPart::Whole => "whole",
        }
    }
}

/// Which side a step is for. The frontend mirrors its guide on this, and
/// [`tests::every_sided_step_has_its_mirror`] pins that both sides get equal
/// time.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Side {
    Left,
    Right,
    /// Both sides, or neither — a shoulder roll, a chin tuck.
    Center,
}

impl Side {
    pub fn as_str(&self) -> &'static str {
        match self {
            Side::Left => "left",
            Side::Right => "right",
            Side::Center => "center",
        }
    }

    /// The other side, for the symmetry check and for a frontend that draws one
    /// figure and flips it.
    pub fn mirror(&self) -> Side {
        match self {
            Side::Left => Side::Right,
            Side::Right => Side::Left,
            Side::Center => Side::Center,
        }
    }
}

/// How a step moves, which is what the guide animation has to know.
///
/// This exists because a still figure is useless for the two thirds of these
/// movements that *are* the movement — a shoulder roll shown as a held pose is
/// a picture of someone sitting there.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Motion {
    /// Ease into the position and stay: the guide travels once and holds.
    Hold,
    /// A slow continuous cycle for the length of the step: the guide oscillates.
    Cycle,
    /// Stillness on purpose — eyes closed, hands resting. The guide is calm.
    Still,
}

impl Motion {
    pub fn as_str(&self) -> &'static str {
        match self {
            Motion::Hold => "hold",
            Motion::Cycle => "cycle",
            Motion::Still => "still",
        }
    }
}

/// One movement in a routine.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StretchStep {
    /// Stable key the frontend looks its words up by. Never shown raw.
    pub key: &'static str,
    pub seconds: u32,
    pub part: BodyPart,
    pub side: Side,
    pub motion: Motion,
}

/// A named sequence of movements.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StretchRoutine {
    pub key: &'static str,
    pub steps: &'static [StretchStep],
}

impl StretchRoutine {
    /// How long the whole routine takes. Derived, never written down twice —
    /// the same rule `attention::CYCLE_SECONDS` follows, and for the same
    /// reason: a total that can disagree with its parts eventually does.
    pub fn total_seconds(&self) -> u32 {
        self.steps.iter().map(|s| s.seconds).sum()
    }
}

/// Shorthand so the tables below read as choreography rather than as struct
/// literals. Fifty-odd steps written out longhand is fifty chances to put a
/// duration in the `part` slot.
const fn step(
    key: &'static str,
    seconds: u32,
    part: BodyPart,
    side: Side,
    motion: Motion,
) -> StretchStep {
    StretchStep {
        key,
        seconds,
        part,
        side,
        motion,
    }
}

/// **Neck** — the one a screen actually causes, and the one Opera Air picked
/// first. Slow tilts and turns plus a chin tuck; no circles, because a full
/// neck circumduction takes the joint through its end range under the weight of
/// the head, and that is the one movement here where "gentle" depends on the
/// person doing it rather than on the instruction.
const NECK: &[StretchStep] = &[
    step(
        "neck_release",
        10,
        BodyPart::Neck,
        Side::Center,
        Motion::Still,
    ),
    step(
        "neck_tilt_left",
        20,
        BodyPart::Neck,
        Side::Left,
        Motion::Hold,
    ),
    step(
        "neck_tilt_right",
        20,
        BodyPart::Neck,
        Side::Right,
        Motion::Hold,
    ),
    step(
        "neck_turn_left",
        15,
        BodyPart::Neck,
        Side::Left,
        Motion::Hold,
    ),
    step(
        "neck_turn_right",
        15,
        BodyPart::Neck,
        Side::Right,
        Motion::Hold,
    ),
    step("chin_tuck", 20, BodyPart::Neck, Side::Center, Motion::Cycle),
];

/// **Shoulders and upper back** — where the forward-head posture of a long
/// session ends up.
const SHOULDERS: &[StretchStep] = &[
    step(
        "shoulder_roll_back",
        20,
        BodyPart::Shoulders,
        Side::Center,
        Motion::Cycle,
    ),
    step(
        "shoulder_shrug",
        15,
        BodyPart::Shoulders,
        Side::Center,
        Motion::Cycle,
    ),
    step(
        "shoulder_blade_squeeze",
        20,
        BodyPart::Shoulders,
        Side::Center,
        Motion::Hold,
    ),
    step(
        "cross_body_left",
        20,
        BodyPart::Shoulders,
        Side::Left,
        Motion::Hold,
    ),
    step(
        "cross_body_right",
        20,
        BodyPart::Shoulders,
        Side::Right,
        Motion::Hold,
    ),
];

/// **Wrists and hands** — the typing end, and the part nobody thinks to move
/// until it complains.
const WRISTS: &[StretchStep] = &[
    step(
        "wrist_circles",
        20,
        BodyPart::Arms,
        Side::Center,
        Motion::Cycle,
    ),
    step(
        "wrist_extend_left",
        15,
        BodyPart::Arms,
        Side::Left,
        Motion::Hold,
    ),
    step(
        "wrist_extend_right",
        15,
        BodyPart::Arms,
        Side::Right,
        Motion::Hold,
    ),
    step(
        "finger_spread",
        15,
        BodyPart::Arms,
        Side::Center,
        Motion::Cycle,
    ),
    step(
        "hands_shake_out",
        10,
        BodyPart::Arms,
        Side::Center,
        Motion::Cycle,
    ),
];

/// **Back** — seated twists and side reaches, both self-limiting: the range is
/// bounded by the chair and by the arm, not by how hard someone pushes.
const BACK: &[StretchStep] = &[
    step(
        "seated_twist_left",
        20,
        BodyPart::Back,
        Side::Left,
        Motion::Hold,
    ),
    step(
        "seated_twist_right",
        20,
        BodyPart::Back,
        Side::Right,
        Motion::Hold,
    ),
    step("cat_curl", 20, BodyPart::Back, Side::Center, Motion::Cycle),
    step(
        "side_reach_left",
        20,
        BodyPart::Back,
        Side::Left,
        Motion::Hold,
    ),
    step(
        "side_reach_right",
        20,
        BodyPart::Back,
        Side::Right,
        Motion::Hold,
    ),
];

/// **Eyes** — the 20-20-20 habit, offered as a break rather than as a
/// prescription. Distance focus and a palm rest relieve the near-work
/// convergence a screen holds you in; that is a comfort claim and the copy must
/// keep it to one.
const EYES: &[StretchStep] = &[
    step(
        "eyes_far_focus",
        20,
        BodyPart::Eyes,
        Side::Center,
        Motion::Still,
    ),
    step(
        "eyes_slow_blink",
        20,
        BodyPart::Eyes,
        Side::Center,
        Motion::Cycle,
    ),
    step(
        "eyes_palm_rest",
        20,
        BodyPart::Eyes,
        Side::Center,
        Motion::Still,
    ),
];

/// **Stand up** — the only routine that needs leaving the chair, and the one
/// worth doing if there is time for exactly one. Last step is a shake-out
/// because ending on stillness after standing work leaves people hovering,
/// unsure whether it is over.
const STAND: &[StretchStep] = &[
    step(
        "stand_tall",
        15,
        BodyPart::Whole,
        Side::Center,
        Motion::Hold,
    ),
    step(
        "calf_raises",
        20,
        BodyPart::Legs,
        Side::Center,
        Motion::Cycle,
    ),
    step(
        "hamstring_reach_left",
        20,
        BodyPart::Legs,
        Side::Left,
        Motion::Hold,
    ),
    step(
        "hamstring_reach_right",
        20,
        BodyPart::Legs,
        Side::Right,
        Motion::Hold,
    ),
    step(
        "hip_open_left",
        20,
        BodyPart::Hips,
        Side::Left,
        Motion::Hold,
    ),
    step(
        "hip_open_right",
        20,
        BodyPart::Hips,
        Side::Right,
        Motion::Hold,
    ),
    step(
        "shake_out",
        10,
        BodyPart::Whole,
        Side::Center,
        Motion::Cycle,
    ),
];

/// Every routine, in the order a frontend should offer them: neck first because
/// it is what a screen causes, standing last because it asks the most.
pub const ROUTINES: &[StretchRoutine] = &[
    StretchRoutine {
        key: "neck",
        steps: NECK,
    },
    StretchRoutine {
        key: "shoulders",
        steps: SHOULDERS,
    },
    StretchRoutine {
        key: "wrists",
        steps: WRISTS,
    },
    StretchRoutine {
        key: "back",
        steps: BACK,
    },
    StretchRoutine {
        key: "eyes",
        steps: EYES,
    },
    StretchRoutine {
        key: "stand",
        steps: STAND,
    },
];

/// One routine by key, or `None`. Frontends pass a key back across the FFI, so
/// an unknown one must be a `None` the caller handles and never a panic.
pub fn routine(key: &str) -> Option<&'static StretchRoutine> {
    ROUTINES.iter().find(|r| r.key == key)
}

/// Which routine to offer, rotated by how many breaks came before — the same
/// turn-seeded rotation `attention::focus_prompt` uses, so the second break of
/// a long day is not the first one again.
pub fn suggest_routine(prior_breaks: u64) -> &'static StretchRoutine {
    &ROUTINES[(prior_breaks as usize) % ROUTINES.len()]
}

/// Where a routine has got to at `elapsed_secs`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StretchCursor {
    /// Index into the routine's steps.
    pub index: usize,
    pub step: StretchStep,
    /// Seconds spent in this step so far — what an animation's phase is read
    /// off, so a step joined late does not restart its motion.
    pub secs_into_step: u32,
    /// Seconds left in this step (at least 1 while the step is current).
    pub secs_left_in_step: u32,
    /// Seconds left in the whole routine.
    pub secs_left_total: u32,
}

/// Which step a routine is on at `elapsed_secs`, or `None` once it is over.
///
/// Walks the cumulative boundaries rather than dividing, because the steps are
/// not the same length — the same reasoning `attention::breathing_phase`'s
/// Kotlin twin records after a `% 3` bug shipped twice. `None` at the end is
/// deliberate and load-bearing: the caller shows a "that's the set" state, and
/// a cursor that clamped to the last step instead would leave the guide holding
/// a hamstring reach for ever.
pub fn step_at(routine: &StretchRoutine, elapsed_secs: u32) -> Option<StretchCursor> {
    let total = routine.total_seconds();
    if elapsed_secs >= total {
        return None;
    }
    let mut boundary = 0u32;
    for (index, step) in routine.steps.iter().enumerate() {
        let next = boundary + step.seconds;
        if elapsed_secs < next {
            return Some(StretchCursor {
                index,
                step: *step,
                secs_into_step: elapsed_secs - boundary,
                secs_left_in_step: next - elapsed_secs,
                secs_left_total: total - elapsed_secs,
            });
        }
        boundary = next;
    }
    // Unreachable: `elapsed_secs < total` and `total` is the sum of exactly
    // these steps.
    None
}

/// How far through the routine, as 0..=1. Clamped at both ends, for the same
/// reason `attention`'s breathing progress is: a progress bar handed 1.4 is a
/// crash on some Compose versions and a wrong drawing on the rest.
pub fn progress(routine: &StretchRoutine, elapsed_secs: u32) -> f32 {
    let total = routine.total_seconds();
    if total == 0 {
        return 1.0;
    }
    (elapsed_secs as f32 / total as f32).clamp(0.0, 1.0)
}

// ── When to offer a break ─────────────────────────────────────────────────────

/// How long someone sits before a stretch is worth offering.
///
/// Half an hour. Not a physiological threshold — there isn't a crisp one — but
/// the interval at which an offer is still an offer rather than a nag, and it
/// lines up with the shortest focus preset being 25 minutes, so a session at
/// the bottom rung is never interrupted at all.
pub const BREAK_AFTER_MINUTES: u32 = 30;

/// Never offer a break within this long of the planned end.
///
/// Interrupting the last four minutes of a session to suggest a stretch is the
/// version of this feature that gets it switched off: the work is nearly done,
/// the break can wait, and being asked anyway teaches the person that the
/// prompt is not worth reading.
pub const BREAK_TAIL_GUARD_MINUTES: u32 = 8;

/// The elapsed-minute marks at which a session of `planned_minutes` should
/// offer a stretch.
///
/// 25 → nothing; 45 → one, at 30; 90 → two, at 30 and 60. Returned as a list
/// rather than as "the next one" so a frontend can show the whole shape of a
/// long session up front, and so this stays a pure function of the plan
/// instead of of the clock.
pub fn break_marks(planned_minutes: u32) -> Vec<u32> {
    if planned_minutes <= BREAK_AFTER_MINUTES {
        return Vec::new();
    }
    let last_useful = planned_minutes.saturating_sub(BREAK_TAIL_GUARD_MINUTES);
    (1..)
        .map(|n| n * BREAK_AFTER_MINUTES)
        .take_while(|mark| *mark <= last_useful)
        .collect()
}

/// Whether a break is due at `elapsed_minutes`, given the last mark already
/// offered (`None` if none has been).
///
/// Returns the mark that is due, so the caller can record it and not ask again
/// for the same one — an offer that reappears every second because the user did
/// not take it is the nag this whole pillar refuses to be.
pub fn break_due(
    planned_minutes: u32,
    elapsed_minutes: u32,
    last_offered: Option<u32>,
) -> Option<u32> {
    // The *latest* due mark, not the earliest: coming back to a session that
    // ran past two marks should offer one stretch, not a queue of them.
    break_marks(planned_minutes)
        .into_iter()
        .rfind(|mark| *mark <= elapsed_minutes && last_offered.is_none_or(|last| *mark > last))
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_sided_step_has_its_mirror_for_the_same_length() {
        // Gate 2. A routine that gives the left side twenty seconds and the
        // right side ten is worse than no routine, and a hand-edited table is
        // exactly where that happens.
        for r in ROUTINES {
            for step in r.steps {
                if step.side == Side::Center {
                    continue;
                }
                // Paired by key *stem*, not by body part. `neck` has two
                // left-side steps — a tilt and a turn — and matching on the
                // part alone paired the turn with the tilt's mirror and then
                // called a routine symmetric that gave the two turns 15s and
                // the two tilts 20s. Which it does, correctly; the bug was in
                // the check, and a symmetry test that can pass on the wrong
                // pairing is not one.
                let stem = step
                    .key
                    .strip_suffix("_left")
                    .or_else(|| step.key.strip_suffix("_right"))
                    .unwrap_or_else(|| panic!("{} is sided but its key is not", step.key));
                let mirror = r
                    .steps
                    .iter()
                    .find(|other| {
                        other.side == step.side.mirror()
                            && other.key.starts_with(stem)
                            && other.key != step.key
                    })
                    .unwrap_or_else(|| panic!("{} has no mirror in routine {}", step.key, r.key));
                assert_eq!(
                    mirror.seconds, step.seconds,
                    "{} and {} must get equal time",
                    step.key, mirror.key
                );
                assert_eq!(mirror.motion, step.motion, "{} vs {}", step.key, mirror.key);
                assert_eq!(mirror.part, step.part, "{} vs {}", step.key, mirror.key);
            }
        }
    }

    #[test]
    fn every_routine_is_short_enough_to_actually_be_taken() {
        // A break nobody takes is not a break. Two minutes is the ceiling: past
        // that this is an exercise session, which is a different product and
        // one this app has no business claiming.
        for r in ROUTINES {
            let total = r.total_seconds();
            assert!(
                total >= 45,
                "{} is {total}s — too short to be worth it",
                r.key
            );
            assert!(total <= 130, "{} is {total}s — too long to be taken", r.key);
            assert!(!r.steps.is_empty());
            for s in r.steps {
                assert!(s.seconds > 0, "{} has a zero-length step", r.key);
                // Long enough to arrive in, short enough not to be a plank.
                assert!(s.seconds <= 30, "{} holds {}s", s.key, s.seconds);
            }
        }
    }

    #[test]
    fn step_keys_are_unique_across_every_routine() {
        // The frontends look words up by key, so two steps sharing one would
        // silently show the same instruction for different movements.
        let mut keys: Vec<&str> = ROUTINES
            .iter()
            .flat_map(|r| r.steps)
            .map(|s| s.key)
            .collect();
        let before = keys.len();
        keys.sort_unstable();
        keys.dedup();
        assert_eq!(keys.len(), before, "duplicate step key");

        let mut routine_keys: Vec<&str> = ROUTINES.iter().map(|r| r.key).collect();
        let before = routine_keys.len();
        routine_keys.sort_unstable();
        routine_keys.dedup();
        assert_eq!(routine_keys.len(), before, "duplicate routine key");
    }

    #[test]
    fn routine_lookup_is_by_key_and_unknown_is_none() {
        assert_eq!(routine("neck").map(|r| r.key), Some("neck"));
        assert!(routine("levitation").is_none());
    }

    #[test]
    fn the_cursor_walks_the_steps_and_ends() {
        let neck = routine("neck").unwrap();
        let total = neck.total_seconds();

        let first = step_at(neck, 0).expect("a routine starts on its first step");
        assert_eq!(first.index, 0);
        assert_eq!(first.secs_into_step, 0);
        assert_eq!(first.secs_left_in_step, neck.steps[0].seconds);
        assert_eq!(first.secs_left_total, total);

        // The last second of the first step is still the first step.
        let edge = step_at(neck, neck.steps[0].seconds - 1).unwrap();
        assert_eq!(edge.index, 0);
        assert_eq!(edge.secs_left_in_step, 1);
        // And the boundary second is the second step, not the first again.
        let next = step_at(neck, neck.steps[0].seconds).unwrap();
        assert_eq!(next.index, 1);
        assert_eq!(next.secs_into_step, 0);

        // One second before the end is the last step...
        let last = step_at(neck, total - 1).unwrap();
        assert_eq!(last.index, neck.steps.len() - 1);
        assert_eq!(last.secs_left_total, 1);
        // ...and the end is `None`, so the guide stops rather than holding the
        // final position for ever.
        assert!(step_at(neck, total).is_none());
        assert!(step_at(neck, total + 600).is_none());
    }

    #[test]
    fn every_second_of_every_routine_maps_to_exactly_one_step() {
        // The arithmetic is where the bugs are (see `breathing_phase`), so this
        // walks all of it rather than sampling.
        for r in ROUTINES {
            let mut expected_index = 0usize;
            let mut consumed = 0u32;
            for second in 0..r.total_seconds() {
                let cursor = step_at(r, second).expect("inside the routine");
                if consumed == r.steps[expected_index].seconds {
                    expected_index += 1;
                    consumed = 0;
                }
                assert_eq!(cursor.index, expected_index, "{} at {second}s", r.key);
                assert_eq!(cursor.secs_into_step, consumed);
                consumed += 1;
            }
            assert_eq!(
                expected_index,
                r.steps.len() - 1,
                "{} skipped a step",
                r.key
            );
        }
    }

    #[test]
    fn progress_is_clamped_at_both_ends() {
        let neck = routine("neck").unwrap();
        assert_eq!(progress(neck, 0), 0.0);
        assert_eq!(progress(neck, neck.total_seconds()), 1.0);
        assert_eq!(progress(neck, neck.total_seconds() * 3), 1.0);
    }

    #[test]
    fn suggestions_rotate_and_cover_every_routine() {
        for (i, r) in ROUTINES.iter().enumerate() {
            assert_eq!(suggest_routine(i as u64).key, r.key);
        }
        assert_eq!(
            suggest_routine(0).key,
            suggest_routine(ROUTINES.len() as u64).key
        );
    }

    #[test]
    fn the_shortest_session_is_never_interrupted() {
        // 25 minutes is the first rung of the focus ladder. A break offered
        // inside it would interrupt the one session length someone new to this
        // is most likely to be attempting.
        assert!(break_marks(25).is_empty());
        assert!(break_marks(30).is_empty());
    }

    #[test]
    fn breaks_land_on_the_half_hour_and_never_in_the_last_stretch() {
        assert_eq!(break_marks(45), vec![30]);
        assert_eq!(break_marks(90), vec![30, 60]);
        assert_eq!(break_marks(180), vec![30, 60, 90, 120, 150]);
        // 37 minutes: the 30-minute mark falls inside the tail guard, so
        // nothing is offered — the work is nearly done and the break can wait.
        assert!(break_marks(37).is_empty());
        // 38 is the first length where the mark clears the guard exactly. The
        // boundary is inclusive on purpose: "within 8 minutes of the end" and
        // "exactly 8 minutes before it" are different, and picking the stricter
        // reading would silently move the guard to 9.
        assert_eq!(break_marks(38), vec![30]);
    }

    #[test]
    fn a_break_offer_does_not_repeat_once_it_has_been_made() {
        // Gate 3: an offer that reappears every second is a nag.
        assert_eq!(break_due(90, 10, None), None);
        assert_eq!(break_due(90, 30, None), Some(30));
        assert_eq!(break_due(90, 45, Some(30)), None, "already asked");
        assert_eq!(break_due(90, 61, Some(30)), Some(60), "the next mark");
        assert_eq!(break_due(90, 89, Some(60)), None, "no third mark exists");
        // Coming back to a session that ran past two marks offers the later
        // one, not a queue of both.
        assert_eq!(break_due(180, 95, None), Some(90));
    }

    #[test]
    fn part_side_and_motion_keys_are_stable_strings() {
        // These cross the FFI, so they are a contract like `FocusOutcome`'s.
        assert_eq!(BodyPart::Neck.as_str(), "neck");
        assert_eq!(Side::Left.as_str(), "left");
        assert_eq!(Motion::Cycle.as_str(), "cycle");
    }
}
