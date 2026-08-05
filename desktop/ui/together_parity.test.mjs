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

test("the check itself still works", () => {
  // Guards the guard: if `kotlinConst` silently stopped matching, every
  // assertion above would need to fail for the right reason.
  assert.throws(
    () => kotlinConst("A_CONSTANT_THAT_DOES_NOT_EXIST"),
    /could not find/,
  );
});
