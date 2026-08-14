import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import {
  CYCLE_SECS,
  HOLD_TRAVEL_SECS,
  guideExtent,
  guidePose,
  knownRoutineKeys,
  knownStepKeys,
  routineCopy,
  stepClock,
  stepLabel,
  stepCopy,
} from "./stretch_view.mjs";

/**
 * The engine's own choreography, read out of the Rust source.
 *
 * Same trick — and the same honest failure mode — as `together_parity.test.mjs`:
 * a regular expression over another language is crude, and if it stops matching
 * the test fails saying so rather than passing by finding nothing. The
 * alternative is a hand-copied list of step keys in a third place, which is the
 * drift this is here to catch.
 */
const RUST = readFileSync(
  new URL("../../crates/comrade_core/src/stretch.rs", import.meta.url),
  "utf8",
);

/** Every `step("key", …)` in the routine tables, ignoring the helper's own definition. */
function engineStepKeys() {
  const keys = [...RUST.matchAll(/^\s*step\(\s*\n?\s*"([a-z_]+)"/gm)].map((m) => m[1]);
  assert.ok(keys.length > 10, "could not read the step table out of stretch.rs");
  return [...new Set(keys)];
}

/** Every `key: "…"` on a `StretchRoutine` literal. */
function engineRoutineKeys() {
  const keys = [...RUST.matchAll(/StretchRoutine\s*\{\s*key:\s*"([a-z_]+)"/g)].map((m) => m[1]);
  assert.ok(keys.length > 2, "could not read the routine table out of stretch.rs");
  return keys;
}

test("every movement the engine can show has words on this side", () => {
  // The engine ships keys, not prose. A key with no entry here renders as a
  // countdown with no instruction — technically a stretch break, practically a
  // figure moving for no stated reason.
  const missing = engineStepKeys().filter((key) => stepCopy(key) === "");
  assert.deepEqual(missing, [], `steps with no instruction: ${missing.join(", ")}`);

  const routines = engineRoutineKeys();
  for (const key of routines) {
    assert.notEqual(routineCopy(key).name, key, `routine ${key} has no name`);
    assert.ok(routineCopy(key).blurb.length > 0, `routine ${key} has no blurb`);
  }
});

test("this side has no words for movements the engine cannot show", () => {
  // The other direction, which matters because a stale entry is invisible: a
  // step renamed in Rust leaves its old copy here looking correct for ever.
  const engine = new Set(engineStepKeys());
  const orphans = knownStepKeys().filter((key) => !engine.has(key));
  assert.deepEqual(orphans, [], `copy for steps that no longer exist: ${orphans.join(", ")}`);

  const engineRoutines = new Set(engineRoutineKeys());
  const orphanRoutines = knownRoutineKeys().filter((key) => !engineRoutines.has(key));
  assert.deepEqual(orphanRoutines, []);
});

test("the copy invites and never prescribes", () => {
  // Gate 1: this is a browser tab, not a clinician. Nothing here may claim to
  // treat, correct or prevent anything, and nothing may tell anyone to push.
  const forbidden =
    /\b(cure|cures|treat|treats|treatment|corrects?|prevents?|heals?|fixes|therapy|diagnos|as far as you can|push through|force|no pain)\b/i;
  for (const key of knownStepKeys()) {
    assert.ok(!forbidden.test(stepCopy(key)), `${key}: ${stepCopy(key)}`);
  }
  for (const key of knownRoutineKeys()) {
    const { name, blurb } = routineCopy(key);
    assert.ok(!forbidden.test(blurb), `${key}: ${blurb}`);
    assert.ok(name.length > 0);
  }
});

test("an unknown key degrades rather than painting itself", () => {
  assert.equal(stepCopy("levitation"), "", "a raw key is worse than no instruction");
  assert.equal(routineCopy("levitation").name, "levitation");
  assert.equal(routineCopy(undefined).name, "");
});

test("a held stretch is eased into, not snapped to", () => {
  // A figure that arrives instantly is demonstrating the one thing this routine
  // most wants not to be done.
  assert.equal(guideExtent("hold", 0, 20), 0);
  assert.ok(guideExtent("hold", HOLD_TRAVEL_SECS / 2, 20) > 0.4);
  assert.equal(guideExtent("hold", HOLD_TRAVEL_SECS, 20), 1);
  assert.equal(guideExtent("hold", 19, 20), 1, "and then it holds");
  // A step shorter than the travel still completes inside itself.
  assert.equal(guideExtent("hold", 1, 1), 1);
});

test("a cycling movement starts at rest, goes out and comes back", () => {
  // Starting at 0 is the point: the first thing the guide does must be to
  // begin. The circle on the breathing screen records the same argument.
  assert.equal(guideExtent("cycle", 0, 20), 0);
  assert.ok(Math.abs(guideExtent("cycle", CYCLE_SECS / 2, 20) - 1) < 1e-9, "peak at half");
  assert.ok(guideExtent("cycle", CYCLE_SECS, 20) < 1e-9, "back to rest");
  // It repeats for as long as the step lasts.
  assert.ok(Math.abs(guideExtent("cycle", CYCLE_SECS * 1.5, 20) - 1) < 1e-9);
  // And it stays in range the whole way, which is what the DOM depends on.
  for (let t = 0; t <= 20; t += 0.25) {
    const e = guideExtent("cycle", t, 20);
    assert.ok(e >= 0 && e <= 1, `extent ${e} at ${t}s`);
  }
});

test("stillness is the instruction, so nothing moves", () => {
  for (const t of [0, 1, 5, 19]) {
    assert.equal(guideExtent("still", t, 20), 0);
  }
  // An unrecognised motion is still, not undefined — a NaN in a transform is a
  // figure that disappears.
  assert.equal(guideExtent("levitate", 3, 20), 0);
  assert.equal(guideExtent(undefined, 3, 20), 0);
});

test("nonsense timings never reach the DOM as NaN", () => {
  for (const args of [
    ["hold", NaN, 20],
    ["hold", -5, 20],
    ["cycle", 3, 0],
    ["cycle", 3, NaN],
    ["hold", undefined, undefined],
  ]) {
    const e = guideExtent(...args);
    assert.ok(Number.isFinite(e) && e >= 0 && e <= 1, `extent for ${args.join(",")} was ${e}`);
  }
});

test("the guide leans toward the side the instruction names", () => {
  // A sign error here would have the figure lean away from the named side —
  // which is the whole reason one drawing can serve a left step and a right one.
  const left = guidePose({ part: "neck", side: "left", motion: "hold", seconds: 20 }, 20);
  const right = guidePose({ part: "neck", side: "right", motion: "hold", seconds: 20 }, 20);
  assert.ok(left.rotate < 0);
  assert.ok(right.rotate > 0);
  assert.equal(left.rotate, -right.rotate, "mirrored, not two different poses");

  // A centred movement does not lean at all.
  const centre = guidePose({ part: "shoulders", side: "center", motion: "cycle", seconds: 20 }, 2);
  assert.equal(centre.rotate, 0);
  assert.equal(centre.tiltX, 0);
  assert.ok(centre.tiltY !== 0, "but it still moves");
});

test("the eyes step holds the figure still", () => {
  // The step whose instruction is to look away from the screen must not put the
  // one animated thing in the room on the screen.
  const pose = guidePose({ part: "eyes", side: "center", motion: "still", seconds: 20 }, 10);
  assert.deepEqual(
    [pose.rotate, pose.tiltX, pose.tiltY, pose.scale],
    [0, 0, 0, 1],
  );
});

test("every pose the engine can ask for is finite and small", () => {
  // The guide is a diagram of a movement, not a cartoon of one.
  for (const part of ["neck", "shoulders", "arms", "back", "hips", "legs", "eyes", "whole"]) {
    for (const side of ["left", "right", "center"]) {
      for (const motion of ["hold", "cycle", "still"]) {
        const pose = guidePose({ part, side, motion, seconds: 20 }, 7);
        for (const [name, value] of Object.entries(pose)) {
          if (name === "part") continue;
          assert.ok(Number.isFinite(value), `${part}/${side}/${motion} ${name}`);
        }
        assert.ok(Math.abs(pose.rotate) <= 20, `${part} rotates ${pose.rotate}°`);
        assert.ok(Math.abs(pose.tiltX) <= 15);
        assert.ok(Math.abs(pose.tiltY) <= 15);
      }
    }
  }
  // An empty step is a still figure, not a crash.
  const empty = guidePose();
  assert.ok(Number.isFinite(empty.rotate));
  assert.equal(empty.part, "whole");
});

test("a step's clock is a bare number of seconds", () => {
  // `0:03` would be three characters of zero for every one of information, and
  // no step is longer than 30 seconds.
  assert.equal(stepClock(20), "20");
  assert.equal(stepClock(3), "3");
  assert.equal(stepClock(0.2), "1", "a part-second still has time left in it");
  assert.equal(stepClock(0), "0");
  assert.equal(stepClock(-4), "0", "a late tick does not go negative");
  assert.equal(stepClock(NaN), "0");
});

test("the step counter is one-based and says what it is counting", () => {
  assert.equal(stepLabel(0, 6), "Step 1 of 6");
  assert.equal(stepLabel(5, 6), "Step 6 of 6");
  // Out of range clamps rather than reading "Step 12 of 6".
  assert.equal(stepLabel(11, 6), "Step 6 of 6");
  assert.equal(stepLabel(-1, 6), "Step 1 of 6");
  assert.equal(stepLabel(0, 0), "", "nothing to count");
});
