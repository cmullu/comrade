import { test } from "node:test";
import assert from "node:assert/strict";
import {
  sideLabel,
  stretchAt,
  stretchProgress,
  stretchSegments,
  stretchTotalSecs,
} from "./stretch_view.mjs";

// The shape `stretch_routine` returns; values deliberately small so the
// boundary arithmetic below is readable at a glance.
const ROUTINE = [
  { key: "neck-tilt", name: "Neck tilt", cue: "Ear to shoulder.", seconds: 10, mirrored: true },
  { key: "chin-tuck", name: "Chin tuck", cue: "Chin back.", seconds: 5, mirrored: false },
];

test("mirrored steps flatten to a left segment then a right one", () => {
  const segments = stretchSegments(ROUTINE);
  assert.deepEqual(
    segments.map((s) => [s.key, s.side]),
    [
      ["neck-tilt", "left"],
      ["neck-tilt", "right"],
      ["chin-tuck", null],
    ],
  );
  assert.equal(stretchTotalSecs(segments), 25);
});

test("a malformed step is skipped, not a crash — the routine crosses a bridge", () => {
  const segments = stretchSegments([
    { key: "", name: "x", cue: "", seconds: 10, mirrored: false },
    { key: "ok", name: "", cue: "", seconds: 0, mirrored: false },
    { key: "ok", name: "", cue: "", seconds: -5, mirrored: false },
    null,
    { key: "good", name: "Good", cue: "Fine.", seconds: 8, mirrored: false },
  ]);
  assert.deepEqual(
    segments.map((s) => s.key),
    ["good"],
  );
  assert.deepEqual(stretchSegments(undefined), []);
  assert.equal(stretchAt([], 5), null, "no segments: no player, not an empty one");
});

test("stretchAt walks segment boundaries and never skips a side", () => {
  const segments = stretchSegments(ROUTINE);
  // Opening frame.
  assert.deepEqual(stretchAt(segments, 0), {
    index: 0,
    segment: segments[0],
    into: 0,
    remaining: 10,
    done: false,
  });
  // Last instant of the left side still belongs to the left side…
  assert.equal(stretchAt(segments, 9.5).index, 0);
  // …and the boundary second starts the right side.
  const right = stretchAt(segments, 10);
  assert.equal(right.index, 1);
  assert.equal(right.segment.side, "right");
  assert.equal(right.remaining, 10);
  // Mid final step.
  const tuck = stretchAt(segments, 22);
  assert.equal(tuck.segment.key, "chin-tuck");
  assert.equal(tuck.into, 2);
  assert.equal(tuck.done, false);
});

test("past the end pins to the final stretch at rest rather than blanking", () => {
  const segments = stretchSegments(ROUTINE);
  for (const elapsed of [25, 26, 9999]) {
    const end = stretchAt(segments, elapsed);
    assert.equal(end.done, true);
    assert.equal(end.index, segments.length - 1);
    assert.equal(end.remaining, 0);
  }
  // A NaN clock reads as the opening frame, not a crash mid-break.
  assert.equal(stretchAt(segments, NaN).index, 0);
  assert.equal(stretchAt(segments, -3).index, 0);
});

test("progress runs 0..1 and clamps at both ends", () => {
  const segments = stretchSegments(ROUTINE);
  assert.equal(stretchProgress(segments, 0), 0);
  assert.equal(stretchProgress(segments, 12.5), 0.5);
  assert.equal(stretchProgress(segments, 25), 1);
  assert.equal(stretchProgress(segments, 9999), 1);
  assert.equal(stretchProgress(segments, -4), 0);
  assert.equal(stretchProgress([], 10), 0);
});

test("side labels are words a bent neck can read", () => {
  assert.equal(sideLabel("left"), "Left side");
  assert.equal(sideLabel("right"), "Right side");
  assert.equal(sideLabel(null), "");
  assert.equal(sideLabel(undefined), "");
});
