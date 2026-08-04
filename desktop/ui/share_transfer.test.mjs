import { test } from "node:test";
import assert from "node:assert/strict";

import {
  BUFFER_HIGH_WATER,
  BUFFER_LOW_WATER,
  CHUNK_BYTES,
  CHUNK_HEADER_BYTES,
  chunkFrameFits,
  chunkRange,
  chunksToSend,
  createTracker,
  createTransferPump,
  describeVerdict,
  frameChunk,
  iceServersFor,
  parseChunkFrame,
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

// ── Framing and the tracker: the same vectors as the Rust twin ───────────────
//
// Every case below is ported from `comrade_core::share`'s own tests. A
// divergence between the two implementations is a red test here rather than a
// file that arrives corrupted in the field.

const offer = (total, chunk, durationMs) => ({
  total_bytes: total,
  chunk_bytes: chunk,
  sha256: "a".repeat(64),
  duration_ms: durationMs,
});

test("a file divides into chunks with a short last one", () => {
  const o = offer(250, 100, 0);
  assert.deepEqual(chunkRange(o, 0), [0, 100]);
  assert.deepEqual(chunkRange(o, 2), [200, 50], "the tail is short, not padded");
  assert.equal(chunkRange(o, 3), null);
});

test("a chunk carries its own index rather than relying on arrival order", () => {
  const framed = frameChunk(7, new Uint8Array([1, 2, 3]));
  assert.equal(framed.byteLength, CHUNK_HEADER_BYTES + 3);
  const parsed = parseChunkFrame(framed);
  assert.equal(parsed.index, 7);
  assert.deepEqual([...parsed.payload], [1, 2, 3]);
});

test("a frame too short to hold a header is rejected rather than indexed", () => {
  assert.equal(parseChunkFrame(new Uint8Array([])), null);
  assert.equal(parseChunkFrame(new Uint8Array([0, 0, 0])), null);
  assert.equal(parseChunkFrame(new Uint8Array([0, 0, 0, 1])).index, 1);
});

test("a chunk of the wrong length is caught now and not by the hash later", () => {
  const o = offer(250, 100, 0);
  assert.ok(chunkFrameFits(o, 0, 100));
  assert.ok(chunkFrameFits(o, 2, 50), "the last chunk is short");
  assert.ok(!chunkFrameFits(o, 2, 100), "and is not padded to full");
  assert.ok(!chunkFrameFits(o, 0, 99));
  assert.ok(!chunkFrameFits(o, 3, 100), "past the end of the file");
});

test("a large index cannot be sign-flipped into a negative slot", () => {
  // A u32 index past 2^31 must read as a large positive number; a signed read
  // would make it negative and `accept` would silently write nowhere.
  const framed = frameChunk(0xffffffff, new Uint8Array([9]));
  assert.equal(parseChunkFrame(framed).index, 0xffffffff);
});

test("an empty offer has nothing to send and is already complete", () => {
  const t = createTracker(offer(0, CHUNK_BYTES, 0));
  assert.equal(t.chunkCount, 0);
  assert.ok(t.isComplete());
  assert.equal(t.fraction(), 1);
  assert.equal(t.nextRequest(0, 4), null);
  assert.ok(t.playableAt(0));
});

test("a duplicate chunk is not an error and is not counted twice", () => {
  const t = createTracker(offer(250, 100, 0));
  assert.ok(t.accept(1));
  assert.ok(!t.accept(1), "a re-ask after a timeout can deliver both");
  assert.ok(!t.accept(99), "and an impossible index is refused, not stored");
  assert.equal(t.fraction(), 1 / 3);
});

test("a gap ends the runway however much lies beyond it", () => {
  // Ten one-second chunks; everything but the fourth.
  const t = createTracker(offer(10_000, 1000, 10_000));
  for (const i of [0, 1, 2, 4, 5, 6, 7, 8, 9]) t.accept(i);
  assert.equal(t.runwayMs(0), 3000, "audio after a hole is a stutter waiting to happen");
  assert.ok(!t.playableAt(0), "three seconds is under the runway threshold");
  assert.ok(t.playableAt(4000), "but from past the hole the rest is contiguous");
});

test("what is asked for next is anchored at the playhead, not the file start", () => {
  const t = createTracker(offer(10_000, 1000, 10_000));
  // A seek to 06:00-equivalent with nothing fetched: ask from there.
  assert.deepEqual(t.nextRequest(6000, 3), { from: 6, count: 3 });
  for (let i = 6; i < 10; i += 1) t.accept(i);
  // Everything from the playhead on is here, so fall back to the earliest gap
  // rather than declaring victory with a hole in the middle.
  assert.deepEqual(t.nextRequest(6000, 3), { from: 0, count: 3 });
});

test("resuming after a drop asks only for what is missing", () => {
  const t = createTracker(offer(10_000, 1000, 10_000));
  for (const i of [0, 1, 2, 5, 6]) t.accept(i);
  assert.deepEqual(t.nextRequest(0, 10), { from: 3, count: 2 });
});

test("a tail that is entirely here is playable even below the runway", () => {
  const t = createTracker(offer(2000, 1000, 2000));
  t.accept(1);
  assert.ok(t.playableAt(1000), "a track with two seconds left is playable");
});

// ── Refusals say something a person can act on ───────────────────────────────

test("each refusal names the thing that could be changed about it", () => {
  const relay = describeVerdict({ verdict: "refuse", path: "relay", reason: { kind: "relay_forbidden" } });
  assert.ok(!relay.proceed);
  assert.match(relay.message, /relay/i);

  const big = describeVerdict({
    verdict: "refuse",
    path: "relay",
    reason: { kind: "too_large_for_relay", limit: 50 * 1024 * 1024 },
  });
  assert.match(big.message, /50 MB/, "the limit is the actionable part");

  const unsettled = describeVerdict({ verdict: "refuse", path: "unknown", reason: { kind: "path_unknown" } });
  assert.ok(unsettled.retryable, "ICE not having settled is worth waiting out, not failing on");
});

test("consent is asked with the number attached, and is not a silent yes", () => {
  const v = describeVerdict({ verdict: "needs_consent", path: "relay", relayed_bytes: 100 * 1024 * 1024 });
  assert.ok(!v.proceed, "needing consent must never read as having it");
  assert.ok(v.needsConsent);
  assert.match(v.message, /100 MB/);
});

test("allow is the only verdict that proceeds, and an absent one does not", () => {
  assert.ok(describeVerdict({ verdict: "allow", path: "host" }).proceed);
  assert.ok(!describeVerdict(null).proceed);
  assert.ok(!describeVerdict({ verdict: "something new" }).proceed);
});
