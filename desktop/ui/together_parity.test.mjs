/**
 * The two frontends must agree on the numbers, and this is what makes
 * disagreeing a red test.
 *
 * `TogetherDecisionsTest.kt` and `together_sync.test.mjs` deliberately hold the
 * same vectors, but nothing checked that the *constants* those vectors are
 * written against still matched. They can drift silently — someone widens the
 * echo deadline on Android to chase a flaky device and desktop keeps the old
 * one — and the symptom is not a crash but a session where one person's seeks
 * occasionally vanish. That is the divergence `docs/COMMS_ARCHITECTURE.md`
 * ADR-3 was written after, so it gets a gate rather than a convention.
 *
 * Reading Kotlin with a regular expression is crude, and the failure mode is
 * honest: if a constant is renamed or reformatted this test fails loudly saying
 * it could not find it, rather than passing by finding nothing.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import { TOGETHER_UI } from "./together_sync.mjs";
import { READING_STALE_MS, TOGETHER_MS, qualityLabel } from "./player_view.mjs";

const KOTLIN = readFileSync(
  new URL(
    "../../android/app/src/main/java/mullu/comrade/together/TogetherDecisions.kt",
    import.meta.url,
  ),
  "utf8",
);

/**
 * Pull `const val NAME: Type = 123` out of the Kotlin source.
 *
 * Throws rather than returning null: a constant that cannot be found is the
 * same failure as one that does not match — the check stopped working.
 */
function kotlinConst(name) {
  const m = KOTLIN.match(
    new RegExp(`const val ${name}\\s*:\\s*\\w+\\s*=\\s*([0-9_.]+)f?`),
  );
  assert.ok(m, `could not find "const val ${name}" in TogetherDecisions.kt — renamed?`);
  return Number(m[1].replace(/_/g, ""));
}

const RUST = readFileSync(
  new URL("../../crates/comrade_core/src/together.rs", import.meta.url),
  "utf8",
);

/** The same trick against `pub const NAME: T = 123;` in the Rust source. */
function rustConst(name) {
  const m = RUST.match(new RegExp(`pub const ${name}\\s*:\\s*\\w+\\s*=\\s*([0-9_]+)\\s*;`));
  assert.ok(m, `could not find "pub const ${name}" in together.rs — renamed?`);
  return Number(m[1].replace(/_/g, ""));
}

test("the staleness bound follows the heartbeat it is derived from", () => {
  // Both UI bounds are "two heartbeats", and the heartbeat is a Rust constant
  // that has already been retuned once. If it moves again, a readout that
  // still ages out at twenty seconds is either blanking a fresh number or
  // holding a dead one — neither of which anyone would think to check.
  const twoHeartbeats = rustConst("TOGETHER_HEARTBEAT_SECS") * 2 * 1000;
  assert.equal(READING_STALE_MS, twoHeartbeats);
  assert.equal(kotlinConst("READING_STALE_MS"), twoHeartbeats);
});

test("the echo epsilon is the same tolerance on both frontends", () => {
  // Kotlin works in milliseconds because MediaPlayer does; the DOM works in
  // seconds. Same quantity, two units — which is exactly how these drift.
  assert.equal(kotlinConst("EPSILON_MS") / 1000, TOGETHER_UI.EPSILON_SECS);
});

test("an apply waits the same time for its echo on both frontends", () => {
  assert.equal(kotlinConst("SUPPRESS_TTL_MS"), TOGETHER_UI.SUPPRESS_TTL_MS);
});

test("neither frontend will produce chipmunk audio before the other", () => {
  assert.equal(kotlinConst("RATE_MIN"), TOGETHER_UI.RATE_MIN);
  assert.equal(kotlinConst("RATE_MAX"), TOGETHER_UI.RATE_MAX);
});

test("a broken video header is clamped identically on both frontends", () => {
  assert.equal(kotlinConst("MIN_ASPECT"), TOGETHER_UI.MIN_ASPECT);
  assert.equal(kotlinConst("MAX_ASPECT"), TOGETHER_UI.MAX_ASPECT);
});

test("both frontends fall silent about the gap at the same width", () => {
  assert.equal(kotlinConst("TOGETHER_MS"), TOGETHER_MS);
});

test("a reading goes stale at the same age on both frontends", () => {
  // The one that would be least visible if it drifted: nothing crashes, one
  // frontend just keeps reporting a gap the other has already stopped
  // claiming, and the two people compare screens and disbelieve both.
  assert.equal(kotlinConst("READING_STALE_MS"), READING_STALE_MS);
});

test("neither frontend claims a tighter path than the other", () => {
  // `qualityLabel`'s thresholds are literals in the desktop module rather than
  // named constants, so these are pinned against the wording it produces —
  // which is the thing a reader actually sees.
  const floorMs = kotlinConst("QUALITY_FLOOR_MS");
  const directMaxMs = kotlinConst("QUALITY_DIRECT_MAX_MS");
  assert.equal(qualityLabel(floorMs), `direct · ±${(floorMs / 1000).toFixed(2)}s`);
  assert.match(qualityLabel(directMaxMs), /^direct/);
  assert.match(qualityLabel(directMaxMs + 1), /^relayed/);
});

test("the check itself still works", () => {
  // Guards the guard: if `kotlinConst` silently stopped matching, every
  // assertion above would need to fail for the right reason.
  assert.throws(
    () => kotlinConst("A_CONSTANT_THAT_DOES_NOT_EXIST"),
    /could not find/,
  );
});
