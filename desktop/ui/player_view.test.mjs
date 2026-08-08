import { test } from "node:test";
import assert from "node:assert/strict";

import {
  READING_STALE_MS,
  TOGETHER_MS,
  driftLabel,
  formatTime,
  measurementLines,
  playingTitle,
  qualityLabel,
  seekPosition,
  seekToMs,
  stateLabel,
  toggle,
} from "./player_view.mjs";

// ── Clock formatting ─────────────────────────────────────────────────────────

test("times read the way a player shows them", () => {
  assert.equal(formatTime(0), "0:00");
  assert.equal(formatTime(9), "0:09");
  assert.equal(formatTime(75), "1:15");
  assert.equal(formatTime(600), "10:00");
  assert.equal(formatTime(3600), "1:00:00");
  assert.equal(formatTime(7325), "2:02:05");
});

test("an unmeasured duration shows a zero, not NaN", () => {
  // The normal state for the first moment of every file, and permanent for a
  // stream that never reports one.
  for (const bad of [NaN, Infinity, -1, undefined, null, "x"]) {
    assert.equal(formatTime(bad), "0:00", String(bad));
  }
});

// ── The scrubber ─────────────────────────────────────────────────────────────

test("the thumb sits where the playhead is", () => {
  assert.equal(seekPosition(0, 100_000), 0);
  assert.equal(seekPosition(50_000, 100_000), 500);
  assert.equal(seekPosition(100_000, 100_000), 1000);
});

test("a thumb over an unknown whole sits at the start rather than somewhere arbitrary", () => {
  for (const d of [0, -1, NaN, undefined]) {
    assert.equal(seekPosition(30_000, d), 0, String(d));
  }
});

test("a position past the end is clamped rather than overflowing the track", () => {
  assert.equal(seekPosition(200_000, 100_000), 1000);
  assert.equal(seekPosition(-5_000, 100_000), 0);
});

test("the scrubber round-trips", () => {
  const duration = 213_000;
  for (const pos of [0, 1_000, 106_500, 213_000]) {
    const back = seekToMs(seekPosition(pos, duration), duration);
    assert.ok(Math.abs(back - pos) <= duration / 1000 + 1, `${pos} -> ${back}`);
  }
});

test("dragging with no duration cannot seek anywhere", () => {
  assert.equal(seekToMs(500, 0), 0);
});

// ── What we are entitled to say ──────────────────────────────────────────────

test("losing track of them outranks every other reading", () => {
  // Once the heartbeats stop, every other number on screen describes the past.
  const label = stateLabel({
    joined: true,
    lostTrack: true,
    correcting: true,
    theyPaused: true,
  });
  assert.equal(label, "We've lost track of them");
});

test("the vocabulary never claims more than it knows", () => {
  const states = [
    {},
    { joined: true },
    { joined: true, correcting: true },
    { joined: true, theyPaused: true },
    { joined: true, lostTrack: true },
  ];
  for (const s of states) {
    const label = stateLabel(s).toLowerCase();
    assert.ok(!label.includes("sync"), `"${label}" claims more than we know`);
  }
});

test("it says we lost track of them, not that they left", () => {
  // We observed our own silence. What they did is not ours to report.
  const label = stateLabel({ joined: true, lostTrack: true }).toLowerCase();
  assert.ok(label.includes("lost track"));
  assert.ok(!label.includes("left"));
});

// ── The honest gap ───────────────────────────────────────────────────────────

/**
 * The central claim of the readout, and the reason this module exists: a gap
 * smaller than our own measurement error is not a gap we can report.
 */
test("a drift under the measurement error is not reported", () => {
  assert.equal(driftLabel(400, 2000), null);
  assert.equal(driftLabel(-700, 900), null);
});

test("a drift over the measurement error is reported, with a direction", () => {
  assert.match(driftLabel(1500, 100), /1\.5s ahead of them/);
  assert.match(driftLabel(-1500, 100), /1\.5s behind them/);
});

test("a small gap on a good path still stays quiet until it matters", () => {
  // Even perfectly measured, a fifth of a second is not worth narrating — the
  // floor is TOGETHER_MS, not the measurement error alone.
  assert.equal(driftLabel(200, 10), null);
  assert.equal(driftLabel(TOGETHER_MS, 10), null);
  assert.ok(driftLabel(TOGETHER_MS + 200, 10));
});

test("an unmeasured drift says nothing rather than zero", () => {
  for (const bad of [NaN, undefined, null, "x"]) {
    assert.equal(driftLabel(bad, 100), null, String(bad));
  }
});

test("a missing quality figure does not make a drift look well measured", () => {
  // Absent quality is treated as zero error, so the TOGETHER_MS floor is what
  // holds — it must not become a licence to report a 50 ms gap.
  assert.equal(driftLabel(50, null), null);
});

// ── How well we can measure ──────────────────────────────────────────────────

test("the path is named, because it decides what the drift number is worth", () => {
  assert.match(qualityLabel(30), /direct/);
  assert.match(qualityLabel(120), /direct/);
  assert.match(qualityLabel(600), /relayed/);
});

test("an unmeasured path claims nothing", () => {
  for (const bad of [0, -1, NaN, undefined, null]) {
    assert.equal(qualityLabel(bad), null, String(bad));
  }
});

test("a relayed path never reads as tighter than a direct one", () => {
  const relayed = qualityLabel(800);
  const direct = qualityLabel(40);
  assert.ok(relayed.includes("relayed"));
  assert.ok(direct.includes("direct"));
  assert.notEqual(relayed, direct);
});

// ── Transport and titles ─────────────────────────────────────────────────────

test("the transport button says what it will do, not what is happening", () => {
  assert.equal(toggle(true).label, "Pause");
  assert.equal(toggle(false).label, "Play");
  assert.notEqual(toggle(true).glyph, toggle(false).glyph);
});

test("a title falls back to the person, never to a filename", () => {
  assert.equal(playingTitle({ title: "Kun Faya Kun" }), "Kun Faya Kun");
  assert.match(playingTitle({ peerLabel: "Ana" }), /Ana/);
  assert.equal(playingTitle({}), "Together");
  assert.equal(playingTitle({ title: "   ", peerLabel: "" }), "Together");
});

// ── When a reading stops describing now ──────────────────────────────────────
//
// Corrections arrive only when the verdict is not `hold`, so the steady state
// emits nothing at all. A screen that just keeps the last pair therefore shows
// a gap that was closed minutes ago, under the word "Together".

test("a fresh reading shows both lines", () => {
  const m = measurementLines({ driftMs: 3200, qualityMs: 100, ageMs: 1_000 });
  assert.match(m.drift, /3\.2s ahead of them/);
  assert.match(m.path, /direct/);
});

test("a reading older than two heartbeats is not shown at all", () => {
  const stale = { driftMs: 3200, qualityMs: 100, ageMs: READING_STALE_MS };
  assert.deepEqual(measurementLines(stale), { drift: null, path: null });
});

test("the error line ages out with the drift line, never after it", () => {
  // Leaving "±0.05s" on screen once the gap it qualified has gone would claim
  // we are still measuring, which is the exact inversion of the point.
  const m = measurementLines({ driftMs: 9_000, qualityMs: 40, ageMs: 60_000 });
  assert.equal(m.drift, null);
  assert.equal(m.path, null);
});

test("a session that has never been corrected shows blanks, not zeroes", () => {
  // What `main.js` computes before the first correction: `Date.now() - 0`.
  const m = measurementLines({ ageMs: Date.now() });
  assert.deepEqual(m, { drift: null, path: null });
  assert.deepEqual(measurementLines({}), { drift: null, path: null });
});

test("a fresh reading inside the deadband still names the path", () => {
  // The gap is not worth mentioning; how well we can see it still is.
  const m = measurementLines({ driftMs: 50, qualityMs: 40, ageMs: 500 });
  assert.equal(m.drift, null);
  assert.match(m.path, /direct/);
});
