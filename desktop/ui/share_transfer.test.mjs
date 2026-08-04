import { test } from "node:test";
import assert from "node:assert/strict";

import {
  BUFFER_HIGH_WATER,
  BUFFER_LOW_WATER,
  CHUNK_BYTES,
  chunksToSend,
  createTransferPump,
  iceServersFor,
  selectedPairTypes,
  shouldPause,
} from "./share_transfer.mjs";

/**
 * A data channel that behaves like the real one in the way that matters:
 * `send()` grows `bufferedAmount` and it only comes down when the test drains
 * it, firing `bufferedamountlow` exactly as a browser would.
 */
function fakeChannel() {
  const listeners = new Map();
  const sent = [];
  return {
    bufferedAmount: 0,
    bufferedAmountLowThreshold: 0,
    sent,
    send(payload) {
      sent.push(payload);
      this.bufferedAmount += payload.byteLength ?? payload.length ?? CHUNK_BYTES;
    },
    addEventListener(name, fn) {
      listeners.set(name, fn);
    },
    removeEventListener(name) {
      listeners.delete(name);
    },
    /** Drain to `to` bytes and fire the low-water event like a browser does. */
    drainTo(to) {
      this.bufferedAmount = to;
      if (to <= this.bufferedAmountLowThreshold) listeners.get("bufferedamountlow")?.();
    },
    get hasLowListener() {
      return listeners.has("bufferedamountlow");
    },
  };
}

const payload = () => new Uint8Array(CHUNK_BYTES);

// ── The arithmetic, mirroring share::transport's vectors ─────────────────────

test("a drained channel takes a full window's worth of chunks", () => {
  assert.equal(chunksToSend(0), Math.floor(BUFFER_HIGH_WATER / CHUNK_BYTES));
});

test("a full channel takes nothing and says to wait", () => {
  assert.equal(chunksToSend(BUFFER_HIGH_WATER), 0);
  assert.equal(chunksToSend(BUFFER_HIGH_WATER + 1), 0, "over the ceiling is not negative room");
  assert.ok(shouldPause(BUFFER_HIGH_WATER));
  assert.ok(!shouldPause(BUFFER_LOW_WATER));
});

test("a partly full channel takes only what fits", () => {
  assert.equal(chunksToSend(BUFFER_HIGH_WATER - 3 * CHUNK_BYTES), 3);
});

test("the two watermarks leave room for a real burst", () => {
  assert.ok(BUFFER_LOW_WATER < BUFFER_HIGH_WATER);
  assert.ok(chunksToSend(BUFFER_LOW_WATER) >= 8, "waking for a couple of chunks is a busy loop");
});

// ── The pump: the bug this module exists to prevent ──────────────────────────

test("the pump never queues an unbounded file into the send buffer", () => {
  const channel = fakeChannel();
  let remaining = 100_000; // far more than the window
  const pump = createTransferPump({
    channel,
    nextChunks: (budget) => {
      const n = Math.min(budget, remaining);
      remaining -= n;
      return Array.from({ length: n }, payload);
    },
    isDone: () => remaining === 0,
  });
  pump.kick();
  assert.ok(
    channel.bufferedAmount <= BUFFER_HIGH_WATER + CHUNK_BYTES,
    `queued ${channel.bufferedAmount} bytes — a naive loop would have queued the whole file`,
  );
  assert.ok(remaining > 0, "it must have stopped, not run to completion");
});

test("it sets the low-water threshold and resumes on the drain event, not a poll", () => {
  const channel = fakeChannel();
  let remaining = 100_000;
  const pump = createTransferPump({
    channel,
    nextChunks: (budget) => {
      const n = Math.min(budget, remaining);
      remaining -= n;
      return Array.from({ length: n }, payload);
    },
    isDone: () => remaining === 0,
  });
  assert.equal(channel.bufferedAmountLowThreshold, BUFFER_LOW_WATER);

  pump.kick();
  const afterFirst = pump.chunksSent;
  assert.ok(afterFirst > 0);

  // The browser drains and fires; the pump must pick up by itself.
  channel.drainTo(0);
  assert.ok(pump.chunksSent > afterFirst, "the drain event should have resumed the pump");
});

test("a transfer that fits inside one window completes in a single kick", () => {
  const channel = fakeChannel();
  let remaining = 4;
  const pump = createTransferPump({
    channel,
    nextChunks: (budget) => {
      const n = Math.min(budget, remaining);
      remaining -= n;
      return Array.from({ length: n }, payload);
    },
    isDone: () => remaining === 0,
  });
  pump.kick();
  assert.equal(pump.chunksSent, 4);
  assert.equal(remaining, 0);
});

test("nothing to send right now is normal, not the end", () => {
  const channel = fakeChannel();
  let allowed = 0;
  let done = false;
  const pump = createTransferPump({
    channel,
    // The receiver has not asked for anything yet; what it does ask for is
    // consumed, so an idle pump reads as idle rather than as an endless supply.
    nextChunks: (budget) => {
      const n = Math.min(budget, allowed);
      allowed -= n;
      return Array.from({ length: n }, payload);
    },
    isDone: () => done,
  });
  pump.kick();
  assert.equal(pump.chunksSent, 0);
  assert.ok(!pump.isStopped, "an idle moment must not end the transfer");

  // The receiver asks; a kick resumes.
  allowed = 2;
  pump.kick();
  assert.equal(pump.chunksSent, 2);
});

test("stopping detaches the listener and sends nothing more", () => {
  const channel = fakeChannel();
  let remaining = 100_000;
  const pump = createTransferPump({
    channel,
    nextChunks: (budget) => {
      const n = Math.min(budget, remaining);
      remaining -= n;
      return Array.from({ length: n }, payload);
    },
    isDone: () => remaining === 0,
  });
  pump.kick();
  const sent = pump.chunksSent;
  pump.stop();
  assert.ok(!channel.hasLowListener);
  channel.drainTo(0);
  pump.kick();
  assert.equal(pump.chunksSent, sent, "a stopped pump must stay stopped");
  pump.stop(); // idempotent
});

test("a send that throws stops the transfer and reports once", () => {
  const channel = fakeChannel();
  channel.send = () => {
    throw new Error("channel closed");
  };
  const errors = [];
  const pump = createTransferPump({
    channel,
    nextChunks: () => [payload()],
    isDone: () => false,
    onError: (e) => errors.push(e),
  });
  pump.kick();
  assert.equal(errors.length, 1);
  assert.ok(pump.isStopped);
  pump.kick();
  assert.equal(errors.length, 1, "a stopped pump must not keep throwing");
});

test("it needs a real channel", () => {
  assert.throws(() => createTransferPump({ channel: null, nextChunks: () => [], isDone: () => true }));
});

// ── Policy enforcement at configuration time ─────────────────────────────────

const SERVERS = [
  { urls: "stun:stun.example:3478" },
  { urls: ["turn:turn.example:3478", "turns:turn.example:5349"], username: "u", credential: "c" },
  { urls: ["stun:other.example:3478", "turn:sneaky.example:3478"] },
];

test("a direct-only transfer connection is given no TURN at all", () => {
  const allowed = iceServersFor("direct_only", SERVERS);
  assert.equal(allowed.length, 1);
  assert.equal(allowed[0].urls, "stun:stun.example:3478");
});

test("a server mixing stun and turn urls does not smuggle a relay through", () => {
  const allowed = iceServersFor("direct_only", SERVERS);
  assert.ok(
    !allowed.some((s) => [].concat(s.urls).some((u) => u.startsWith("turn"))),
    "one turn url in an entry makes the whole entry a relay",
  );
});

test("any other policy keeps the full list, including TURN", () => {
  for (const kind of ["under_bytes", "ask_each_time", "always"]) {
    assert.equal(iceServersFor(kind, SERVERS).length, SERVERS.length);
  }
  assert.deepEqual(iceServersFor("direct_only", null), []);
});

// ── Reading the selected pair ────────────────────────────────────────────────

function statsOf(entries) {
  return new Map(entries.map((e) => [e.id, e]));
}

test("the selected pair is read through the transport report", () => {
  const stats = statsOf([
    { id: "T", type: "transport", selectedCandidatePairId: "P" },
    { id: "P", type: "candidate-pair", localCandidateId: "L", remoteCandidateId: "R" },
    { id: "L", type: "local-candidate", candidateType: "srflx" },
    { id: "R", type: "remote-candidate", candidateType: "relay" },
  ]);
  assert.deepEqual(selectedPairTypes(stats), { local: "srflx", remote: "relay" });
});

test("an older report that only marks the pair still works", () => {
  const stats = statsOf([
    { id: "P", type: "candidate-pair", nominated: true, state: "succeeded", localCandidateId: "L", remoteCandidateId: "R" },
    { id: "L", type: "local-candidate", candidateType: "host" },
    { id: "R", type: "remote-candidate", candidateType: "host" },
  ]);
  assert.deepEqual(selectedPairTypes(stats), { local: "host", remote: "host" });
});

test("nothing selected yet reads as unknown, never as direct", () => {
  assert.equal(selectedPairTypes(null), null);
  assert.equal(selectedPairTypes(statsOf([])), null);
  // A pair that exists but lost is not the selected one.
  const stats = statsOf([
    { id: "P", type: "candidate-pair", nominated: false, state: "failed", localCandidateId: "L", remoteCandidateId: "R" },
    { id: "L", type: "local-candidate", candidateType: "host" },
    { id: "R", type: "remote-candidate", candidateType: "host" },
  ]);
  assert.equal(selectedPairTypes(stats), null);
});

test("a pair whose candidates are missing from the report is unknown", () => {
  const stats = statsOf([
    { id: "T", type: "transport", selectedCandidatePairId: "P" },
    { id: "P", type: "candidate-pair", localCandidateId: "gone", remoteCandidateId: "alsogone" },
  ]);
  assert.equal(selectedPairTypes(stats), null);
});
