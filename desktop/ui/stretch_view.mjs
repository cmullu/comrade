/**
 * Decisions behind the desktop stretch break — the desktop half of what
 * `comrade_core::stretch` choreographs.
 *
 * ## What is here and what is not
 *
 * Not here: which movements, in which order, for how long, when a break is due,
 * or where a running routine has got to. All of that is the engine's, reached
 * over the Tauri bridge (`stretch_routines`, `stretch_step_at`), for the reason
 * `focus_view.mjs` does not re-derive the duration ladder — two frontends
 * stepping their own copy of a timeline is two chances to drift from the
 * durations the engine's symmetry test guards.
 *
 * Here: the **words**, and the **pose maths** for the animated guide.
 *
 * The words are here because the engine ships keys, not prose (see
 * `comrade_core::stretch`'s module docs) — Android looks the same keys up in
 * `strings.xml`, and this is desktop's table. Keeping the copy out of the engine
 * is what makes it translatable; keeping it in one file per frontend is what
 * makes it reviewable as copy rather than as code.
 *
 * The pose maths is here because it is arithmetic, and because it is the part
 * that decides whether the guide is *following the same breath* as the
 * instruction: a figure that finishes its lean two seconds after the text says
 * to hold is a guide that is out of time with itself.
 */

/**
 * Every routine's name and one line about what it is for.
 *
 * The lines say what the movement is, never what it treats. "Loosens the neck"
 * is a description; "corrects tech neck" is a medical claim, and this is a
 * browser tab, not a clinician (`docs/ATTENTION.md` gate 1).
 */
const ROUTINE_COPY = {
  neck: {
    name: "Neck",
    blurb: "Slow tilts and turns for the part a screen actually pulls on.",
  },
  shoulders: {
    name: "Shoulders",
    blurb: "Rolls and a squeeze, for where an hour of sitting collects.",
  },
  wrists: { name: "Wrists & hands", blurb: "For the end of you that has been typing." },
  back: { name: "Back", blurb: "Seated twists and side reaches. No standing needed." },
  eyes: { name: "Eyes", blurb: "Look far away for a while. That is most of it." },
  stand: { name: "Stand up", blurb: "The one worth doing if there is time for one." },
};

/**
 * Every step's instruction, keyed exactly as `comrade_core::stretch` names it.
 *
 * Second person, present tense, and each one is an invitation with a size:
 * *"until you feel a stretch"*, never *"as far as it goes"*. Nothing here tells
 * anyone to push, to hold their breath, or to keep going through pain — the
 * screen's standing note covers the last one, and the wording covers the rest.
 */
const STEP_COPY = {
  neck_release: "Sit tall. Let your shoulders drop away from your ears.",
  neck_tilt_left: "Tilt your left ear toward your left shoulder. Just to where you feel it.",
  neck_tilt_right: "And the other side — right ear toward right shoulder.",
  neck_turn_left: "Turn slowly to look over your left shoulder.",
  neck_turn_right: "Now over your right shoulder, just as slowly.",
  chin_tuck: "Draw your chin gently back, like making a double chin. Release. Again.",

  shoulder_roll_back: "Roll both shoulders backward, slowly, all the way round.",
  shoulder_shrug: "Lift both shoulders up toward your ears, then let them fall.",
  shoulder_blade_squeeze: "Draw your shoulder blades toward each other and hold.",
  cross_body_left: "Bring your left arm across your chest and hold it there with the right.",
  cross_body_right: "Swap: right arm across, held with the left.",

  wrist_circles: "Circle both wrists, one way and then the other.",
  wrist_extend_left: "Straighten your left arm, fingers up, and draw them gently back.",
  wrist_extend_right: "The right hand now — fingers up, drawn gently back.",
  finger_spread: "Spread your fingers wide, then make a soft fist. Repeat.",
  hands_shake_out: "Shake your hands out loosely.",

  seated_twist_left: "Turn your torso to the left, using the chair for a little leverage.",
  seated_twist_right: "And to the right.",
  cat_curl: "Round your back forward, then open your chest. Slowly, either way.",
  side_reach_left: "Reach your left arm up and lean gently to the right.",
  side_reach_right: "Right arm up, lean gently to the left.",

  eyes_far_focus: "Look at the furthest thing you can see. Let your eyes rest there.",
  eyes_slow_blink: "Blink slowly, all the way closed, a few times.",
  eyes_palm_rest: "Cup your palms over your closed eyes. No pressure — just dark.",

  stand_tall: "Stand up. Feet under your hips, weight even.",
  calf_raises: "Rise onto your toes and lower back down, slowly.",
  hamstring_reach_left: "Left foot forward, hinge at the hips, reach toward it.",
  hamstring_reach_right: "Right foot forward this time, and hinge again.",
  hip_open_left: "Left ankle across the opposite knee, sit back a little.",
  hip_open_right: "Right ankle across now, and sit back.",
  shake_out: "Shake it all out. That's the set.",
};

/**
 * Name and blurb for a routine key. An unknown key gets the key back as a name
 * rather than an empty card: a new routine in the engine should show up here as
 * something usable before this file catches up, not as a blank row.
 *
 * @param {string} key
 * @returns {{name: string, blurb: string}}
 */
export function routineCopy(key) {
  return ROUTINE_COPY[key] || { name: String(key ?? ""), blurb: "" };
}

/**
 * The instruction for a step key, or an empty string.
 *
 * Empty rather than the key: a step key painted into the UI ("hamstring_reach_left")
 * is worse than no instruction at all, because the guide animation and the
 * countdown still say what to do.
 *
 * @param {string} key
 * @returns {string}
 */
export function stepCopy(key) {
  return STEP_COPY[key] || "";
}

/** Every step key this file has words for — used by the parity test. */
export function knownStepKeys() {
  return Object.keys(STEP_COPY);
}

/** Every routine key this file has words for — used by the parity test. */
export function knownRoutineKeys() {
  return Object.keys(ROUTINE_COPY);
}

/**
 * How far into the movement the guide should be, 0..1, at `secsIntoStep`.
 *
 * One function for all three motions, because the guide is one figure:
 *
 * - **hold** — travels out over [`HOLD_TRAVEL_SECS`] and then stays. The travel
 *   is not decoration: a figure that snaps to the end position is showing the
 *   thing this routine most wants not to be done, which is arriving at the end
 *   of a stretch quickly.
 * - **cycle** — a sine wave of [`CYCLE_SECS`], starting and ending at rest, so a
 *   shoulder roll or a chin tuck reads as a repeated movement rather than as a
 *   pose. Starting at 0 matters: the first thing the guide does must be to
 *   *begin*, the same argument `BreathingScreen`'s circle records for opening at
 *   its minimum rather than its maximum.
 * - **still** — 0, for ever. Stillness is the instruction.
 *
 * @param {string} motion `hold` / `cycle` / `still`
 * @param {number} secsIntoStep
 * @param {number} stepSeconds
 * @returns {number} 0..1
 */
export function guideExtent(motion, secsIntoStep, stepSeconds) {
  const at = Number.isFinite(secsIntoStep) ? Math.max(0, secsIntoStep) : 0;
  const length = Number.isFinite(stepSeconds) && stepSeconds > 0 ? stepSeconds : 1;
  switch (motion) {
    case "hold": {
      const travel = Math.min(HOLD_TRAVEL_SECS, length);
      return Math.min(1, at / travel);
    }
    case "cycle": {
      // `(1 - cos)/2` rather than `sin`, so the wave starts at 0, peaks at half
      // a period and returns — a movement out and back, not a jump to halfway.
      const phase = (at % CYCLE_SECS) / CYCLE_SECS;
      return (1 - Math.cos(phase * 2 * Math.PI)) / 2;
    }
    default:
      return 0;
  }
}

/** How long a held stretch takes to ease into. */
export const HOLD_TRAVEL_SECS = 2.5;

/** One out-and-back of a cycling movement. */
export const CYCLE_SECS = 4;

/**
 * Where to put the guide figure's moving part, given a step and how far into it
 * we are.
 *
 * Returns plain numbers — degrees and per-cent offsets — so the DOM layer is a
 * one-line `transform` and this stays testable. `side` decides the **sign**,
 * which is the whole reason a left and a right step can share one drawing: the
 * figure is mirrored, not duplicated, and a sign error would have the guide lean
 * away from the side the instruction names.
 *
 * @param {{key?: string, part?: string, side?: string, motion?: string, seconds?: number}} step
 * @param {number} secsIntoStep
 * @returns {{rotate: number, tiltX: number, tiltY: number, scale: number, part: string}}
 */
export function guidePose(step = {}, secsIntoStep = 0) {
  const extent = guideExtent(step.motion, secsIntoStep, step.seconds);
  const sign = step.side === "left" ? -1 : step.side === "right" ? 1 : 0;
  const part = step.part || "whole";
  // Per-part amplitudes. Small on purpose: the guide is a diagram of a movement,
  // and a figure flinging itself 40° sideways is a cartoon of the thing rather
  // than a picture of it.
  switch (part) {
    case "neck":
      return {
        // A turn reads as rotation about the vertical axis, which a flat figure
        // cannot show — so a turn is drawn as a smaller lean plus a shift, and
        // the copy carries the rest.
        rotate: sign * 18 * extent,
        tiltX: sign * 6 * extent,
        tiltY: step.key === "chin_tuck" ? -4 * extent : 0,
        scale: 1,
        part,
      };
    case "shoulders":
      return {
        rotate: sign * 8 * extent,
        tiltX: sign * 10 * extent,
        tiltY: -8 * extent,
        scale: 1,
        part,
      };
    case "arms":
      return { rotate: sign * 12 * extent, tiltX: sign * 8 * extent, tiltY: 0, scale: 1, part };
    case "back":
      return { rotate: sign * 14 * extent, tiltX: sign * 12 * extent, tiltY: 0, scale: 1, part };
    case "hips":
    case "legs":
      return { rotate: sign * 6 * extent, tiltX: sign * 6 * extent, tiltY: 6 * extent, scale: 1, part };
    case "eyes":
      // Nothing moves. The figure holds still and the ring counts down; an
      // animated face would be the one thing on screen asking to be watched
      // during the step whose instruction is to look away from the screen.
      return { rotate: 0, tiltX: 0, tiltY: 0, scale: 1, part };
    default:
      return { rotate: 0, tiltX: 0, tiltY: -4 * extent, scale: 1 + 0.02 * extent, part };
  }
}

/**
 * `3` — the seconds remaining in the current step, as a bare number.
 *
 * A bare number and not `0:03`: a step is always under a minute, so the
 * `m:ss` shape would be three characters of zero for every one of information.
 * Floors at 0 rather than going negative on a late tick.
 *
 * @param {number} secsLeft
 * @returns {string}
 */
export function stepClock(secsLeft) {
  const n = Number.isFinite(secsLeft) ? Math.max(0, Math.ceil(secsLeft)) : 0;
  return String(n);
}

/**
 * `Step 2 of 6` — where in the routine we are.
 *
 * One-based, matching the reader's `2 of 9`, and it says "step" because a naked
 * `2 of 6` next to a countdown reads as a second timer.
 *
 * @param {number} index zero-based
 * @param {number} total
 * @returns {string}
 */
export function stepLabel(index, total) {
  const count = Number.isFinite(total) ? Math.max(0, Math.floor(total)) : 0;
  if (count === 0) return "";
  const at = Math.min(Math.max(Number.isFinite(index) ? Math.floor(index) : 0, 0), count - 1);
  return `Step ${at + 1} of ${count}`;
}
