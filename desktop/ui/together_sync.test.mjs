import { test } from "node:test";
import assert from "node:assert/strict";

import {
  TOGETHER_UI,
  classifyLocalEvent,
  createEchoSuppressor,
  planApply,
  sessionStatusLabel,
} from "./together_sync.mjs";

/** A suppressor over a clock the test drives by hand. */
function harness() {
  let clock = 1_000;
  const suppressor = createEchoSuppressor({ now: () => clock });
  return {
    suppressor,
    advance: (ms) => {
      clock += ms;
    },
  };
}

// ── Echo suppression ────────────────────────────────────────────────────────

test("a seek we applied does not come back out", () => {
  const { suppressor } = harness();
  suppressor.expect({ kind: "seek", position: 42 });
  const { emit } = classifyLocalEvent(
    { type: "seeked", positionSecs: 42 },
    suppressor,
  );
  assert.equal(emit, null);
  assert.equal(suppressor.size, 0, "the entry should have been consumed");
});

test("frame snapping still counts as the seek we applied", () => {
  const { suppressor } = harness();
  suppressor.expect({ kind: "seek", position: 42 });
  const { emit } = classifyLocalEvent(
    { type: "seeked", positionSecs: 42.004 },
    suppressor,
  );
  assert.equal(emit, null);
});

test("a user seek during an in-flight apply is still reported", () => {
  const { suppressor } = harness();
  suppressor.expect({ kind: "seek", position: 42 });
  const { emit } = classifyLocalEvent(
    { type: "seeked", positionSecs: 12 },
    suppressor,
  );
  assert.deepEqual(emit, { playing: false, positionSecs: 12 });
});

// The case that permanently wedges a boolean latch: assigning `currentTime`
// the value it already holds raises no event at all, so nothing ever clears
// the flag and every later user seek is swallowed.
test("an apply that raises no event expires instead of going deaf", () => {
  const { suppressor, advance } = harness();
  suppressor.expect({ kind: "seek", position: 42 });
  advance(TOGETHER_UI.SUPPRESS_TTL_MS + 1);
  const { emit } = classifyLocalEvent(
    { type: "seeked", positionSecs: 42 },
    suppressor,
  );
  assert.deepEqual(
    emit,
    { playing: false, positionSecs: 42 },
    "a stale entry must not keep swallowing user seeks",
  );
  assert.equal(suppressor.size, 0);
});

// Two rapid assignments can coalesce into one `seeked`, leaving an entry that
// was never matched. It must expire rather than eat a later genuine seek.
test("coalesced applies leave no entry that swallows a later real seek", () => {
  const { suppressor, advance } = harness();
  suppressor.expect({ kind: "seek", position: 42 });
  suppressor.expect({ kind: "seek", position: 60 });
  // Only the second one produced an event.
  assert.equal(
    classifyLocalEvent({ type: "seeked", positionSecs: 60 }, suppressor).emit,
    null,
  );
  advance(TOGETHER_UI.SUPPRESS_TTL_MS + 1);
  const { emit } = classifyLocalEvent(
    { type: "seeked", positionSecs: 42 },
    suppressor,
  );
  assert.deepEqual(emit, { playing: false, positionSecs: 42 });
});

test("an applied pause is silent but the user's next one is not", () => {
  const { suppressor } = harness();
  suppressor.expect({ kind: "pause" });
  assert.equal(
    classifyLocalEvent({ type: "pause", positionSecs: 10 }, suppressor).emit,
    null,
  );
  assert.deepEqual(
    classifyLocalEvent({ type: "pause", positionSecs: 10 }, suppressor).emit,
    { playing: false, positionSecs: 10 },
  );
});

test("a rate trim is never reported, under any input", () => {
  const { suppressor } = harness();
  for (const positionSecs of [0, 42, 9999]) {
    assert.equal(
      classifyLocalEvent({ type: "ratechange", positionSecs }, suppressor).emit,
      null,
    );
  }
});

test("buffering is deliberately not signalled", () => {
  const { suppressor } = harness();
  assert.equal(
    classifyLocalEvent({ type: "waiting", positionSecs: 5 }, suppressor).emit,
    null,
  );
  assert.equal(
    classifyLocalEvent({ type: "stalled", positionSecs: 5 }, suppressor).emit,
    null,
  );
});

test("reaching the end is a pause, never a seek", () => {
  const { suppressor } = harness();
  assert.deepEqual(
    classifyLocalEvent({ type: "ended", positionSecs: 7200 }, suppressor).emit,
    { playing: false, positionSecs: 7200 },
  );
});

test("the ledger stays bounded under an apply storm", () => {
  const { suppressor } = harness();
  for (let i = 0; i < 100; i += 1) {
    suppressor.expect({ kind: "seek", position: i });
  }
  assert.ok(suppressor.size <= 16, `unbounded ledger: ${suppressor.size}`);
});

// ── planApply ───────────────────────────────────────────────────────────────

const LOCAL = { playing: true, positionSecs: 60 };

test("holding does nothing at all", () => {
  assert.deepEqual(planApply({ kind: "hold" }, LOCAL), { ops: [], expect: [] });
});

test("a rate trim raises no event, so it arms nothing", () => {
  const { ops, expect } = planApply({ kind: "nudge", rate: 1.04 }, LOCAL);
  assert.deepEqual(ops, [{ kind: "rate", value: 1.04 }]);
  assert.deepEqual(expect, []);
  assert.ok(!ops.some((o) => o.kind === "seek"), "a trim must never seek");
});

test("a rate the core asked for is still clamped to something listenable", () => {
  assert.equal(planApply({ kind: "nudge", rate: 3 }, LOCAL).ops[0].value, TOGETHER_UI.RATE_MAX);
  assert.equal(planApply({ kind: "nudge", rate: 0.1 }, LOCAL).ops[0].value, TOGETHER_UI.RATE_MIN);
});

test("a correction seeks but never starts playback", () => {
  const paused = { playing: false, positionSecs: 10 };
  const { ops } = planApply({ kind: "seek", pos_ms: 42_000 }, paused);
  assert.deepEqual(ops, [{ kind: "seek", secs: 42 }]);
  assert.ok(
    !ops.some((o) => o.kind === "play"),
    "drift correction must not start a player the user paused",
  );
});

test("adopting a missed command takes their playing state too", () => {
  const paused = { playing: false, positionSecs: 10 };
  const { ops } = planApply(
    { kind: "adopt", pos_ms: 42_000, playing: true, seq: 7 },
    paused,
  );
  assert.deepEqual(ops, [{ kind: "seek", secs: 42 }, { kind: "play" }]);
});

test("adopting a state we are already in does nothing", () => {
  const { ops, expect } = planApply(
    { kind: "adopt", pos_ms: 60_000, playing: true, seq: 7 },
    LOCAL,
  );
  assert.deepEqual(ops, []);
  assert.deepEqual(expect, []);
});

// The property that makes the feedback loop unreachable rather than merely
// unlikely: anything that can raise an event arms a matching ledger entry.
test("every op that can raise an event arms a matching expectation", () => {
  const verdicts = [
    { kind: "hold" },
    { kind: "nudge", rate: 1.04 },
    { kind: "seek", pos_ms: 42_000 },
    { kind: "adopt", pos_ms: 42_000, playing: true, seq: 3 },
    { kind: "adopt", pos_ms: 42_000, playing: false, seq: 3 },
    { kind: "unknown-future-verdict" },
  ];
  for (const verdict of verdicts) {
    for (const local of [LOCAL, { playing: false, positionSecs: 0 }]) {
      const { ops, expect } = planApply(verdict, local);
      const noisy = ops.filter((o) => o.kind !== "rate");
      assert.equal(
        noisy.length,
        expect.length,
        `unarmed op in ${JSON.stringify(verdict)}: ${JSON.stringify(ops)}`,
      );
      // And feeding those events back in must produce nothing outbound.
      const { suppressor } = harness();
      for (const e of expect) suppressor.expect(e);
      for (const op of noisy) {
        const type = op.kind === "seek" ? "seeked" : op.kind;
        const positionSecs = op.kind === "seek" ? op.secs : local.positionSecs;
        assert.equal(
          classifyLocalEvent({ type, positionSecs }, suppressor).emit,
          null,
          `applying ${JSON.stringify(verdict)} echoed back out`,
        );
      }
    }
  }
});

test("an unrecognised verdict is ignored rather than guessed at", () => {
  assert.deepEqual(planApply({ kind: "something-new" }, LOCAL), { ops: [], expect: [] });
  assert.deepEqual(planApply(null, LOCAL), { ops: [], expect: [] });
});

// ── The status line ─────────────────────────────────────────────────────────

test("the status line never claims a precision we do not have", () => {
  const rows = [
    [{ joined: false, weLead: true }, "waiting for them to open it"],
    [{ joined: false, weLead: false }, "waiting for you to join"],
    [{ joined: true, oursReady: false }, "open your copy to start"],
    [{ joined: true, theirsReady: false }, "waiting for them to open their copy"],
    [{ joined: true, playing: true }, "together"],
    [{ joined: true, playing: true, correcting: true }, "catching up…"],
    [{ joined: true, playing: false, peerName: "Ana" }, "Ana paused"],
    [{ joined: true, playing: true, silentForMs: 120_000 }, "we've lost track of them"],
    [{ ended: "peer", peerName: "Ana" }, "Ana left"],
    [{ ended: "timeout" }, "we've lost track of them"],
    [
      { joined: true, playing: true, durationDeltaSecs: 4 },
      "their copy runs 4 seconds longer than yours",
    ],
    [
      { joined: true, playing: true, durationDeltaSecs: -1 },
      "their copy runs 1 second shorter than yours",
    ],
  ];
  for (const [state, expected] of rows) {
    assert.equal(sessionStatusLabel(state), expected);
  }
});

test("nothing in the vocabulary says synced or in sync", () => {
  const states = [
    { joined: true, playing: true },
    { joined: true, playing: true, correcting: true },
    { joined: true, playing: false },
    { joined: false, weLead: true },
  ];
  for (const state of states) {
    const label = sessionStatusLabel(state).toLowerCase();
    assert.ok(!label.includes("sync"), `"${label}" claims more than we know`);
  }
});
