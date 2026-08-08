/**
 * Bulk file transfer over a WebRTC data channel — the pump, and nothing else.
 *
 * ## What lives where, and why
 *
 * Three concerns, deliberately split so none of them can quietly assume
 * another's answers:
 *
 * - **What to send next** — chunking, resume, seek, "can we play yet" — is
 *   `comrade_core::share`, shared with Android so the two frontends cannot
 *   drift apart.
 * - **Whether this path may carry it** — direct vs relay, size thresholds,
 *   consent — is `comrade_core::share::transport`, for the same reason. The
 *   pump below never learns which policy is in force; it is handed a decision.
 * - **How fast to push** is here, because it is the one part that is genuinely
 *   about *this* runtime's data channel and cannot be answered anywhere else.
 *
 * ## The flow-control trap this exists for
 *
 * `RTCDataChannel.send()` accepts writes long after it has stopped putting them
 * on the wire; the bytes queue in the SCTP send buffer and `bufferedAmount`
 * climbs. A naive `for (chunk of file) dc.send(chunk)` therefore queues a whole
 * 2 GB film in memory in a few milliseconds, and either the connection stalls or
 * the tab is killed for it. It also *looks fine* on a 50 MB test file, which is
 * how it reaches production.
 *
 * So the pump fills to a high-water mark, stops, and waits for
 * `bufferedamountlow` — an event, not a poll. `bufferedAmountLowThreshold` is
 * set well under the ceiling rather than just below it, because waking on every
 * few drained bytes would be an event per chunk, which is the busy loop the
 * threshold exists to prevent.
 *
 * ## Separate peer connection, not a second channel on the call
 *
 * A transfer gets its **own** `RTCPeerConnection`. Sharing the call's would put
 * bulk and live media under one congestion controller and one SCTP association,
 * where a 2 GB push and a voice stream compete and the voice loses. Separate
 * connections cost one extra ICE negotiation and buy complete isolation — the
 * call cannot be degraded by a transfer it knows nothing about.
 *
 * It is also what makes the relay policy enforceable: the transfer connection is
 * built from its own ICE server list, so under a direct-only policy it is given
 * no TURN at all and a relay candidate is never gathered. The call keeps its
 * TURN fallback, because a relayed *call* is a few tens of kilobits and entirely
 * reasonable; a relayed film is not.
 */

/** Stop feeding at this much queued. Mirrors `SHARE_BUFFER_HIGH_WATER`. */
export const BUFFER_HIGH_WATER = 1024 * 1024;

/** Resume once drained to here. Mirrors `SHARE_BUFFER_LOW_WATER`. */
export const BUFFER_LOW_WATER = 256 * 1024;

/** One data-channel message. Mirrors `SHARE_CHUNK_BYTES`. */
export const CHUNK_BYTES = 16 * 1024;

/**
 * How many chunks may be pushed into a channel currently holding `buffered`
 * bytes. Zero means stop and wait for the drain event rather than poll.
 *
 * The Rust twin is `share::transport::chunks_to_send`, and the vectors below are
 * the same ones — a divergence here is a red test rather than a field bug.
 */
export function chunksToSend(buffered, chunkBytes = CHUNK_BYTES, highWater = BUFFER_HIGH_WATER) {
  if (!Number.isFinite(buffered) || chunkBytes <= 0 || buffered >= highWater) return 0;
  return Math.floor((highWater - buffered) / chunkBytes);
}

/** Whether the sender should pause and wait for `bufferedamountlow`. */
export function shouldPause(buffered, highWater = BUFFER_HIGH_WATER) {
  return buffered >= highWater;
}

/**
 * The ICE server list a *transfer* connection may use, given the policy.
 *
 * Under a direct-only policy the answer is "none of the TURN ones", which is the
 * structural half of the enforcement: a connection with no TURN cannot gather a
 * relay candidate, so the policy holds even before anything inspects the
 * selected pair. A server is treated as TURN if any of its URLs is — an entry
 * mixing STUN and TURN urls would otherwise smuggle one through.
 */
export function iceServersFor(policyKind, allServers) {
  const servers = Array.isArray(allServers) ? allServers : [];
  if (policyKind !== "direct_only") return servers;
  return servers.filter((s) => {
    const urls = [].concat(s?.urls ?? s?.url ?? []);
    return !urls.some((u) => String(u).startsWith("turn:") || String(u).startsWith("turns:"));
  });
}

/**
 * Read the selected candidate pair's two candidate types out of an
 * `RTCStatsReport`, so the path can be classified.
 *
 * Two shapes in the wild and both are handled: modern browsers expose a
 * `candidate-pair` with `selected`/`nominated` plus a `transport` pointing at
 * it, while older ones only mark the pair. Returns `null` when nothing is
 * selected yet — which the policy treats as "unknown", never as "direct".
 */
export function selectedPairTypes(stats) {
  if (!stats) return null;
  const byId = new Map();
  for (const report of stats.values ? stats.values() : Object.values(stats)) {
    if (report?.id) byId.set(report.id, report);
  }
  let pair = null;
  for (const report of byId.values()) {
    if (report.type === "transport" && report.selectedCandidatePairId) {
      pair = byId.get(report.selectedCandidatePairId) ?? pair;
    }
  }
  if (!pair) {
    for (const report of byId.values()) {
      if (report.type !== "candidate-pair") continue;
      const chosen = report.selected === true || (report.nominated === true && report.state === "succeeded");
      if (chosen) pair = report;
    }
  }
  if (!pair) return null;
  const local = byId.get(pair.localCandidateId);
  const remote = byId.get(pair.remoteCandidateId);
  if (!local || !remote) return null;
  return {
    local: local.candidateType ?? "",
    remote: remote.candidateType ?? "",
  };
}

// ── Framing, ported from `comrade_core::share` ───────────────────────────────
//
// The Rust module is the source of truth and its test vectors are ported
// verbatim below, the way `call_decisions.test.mjs` ports `CallManagerTest.kt`.
// A port rather than an FFI call because the receiver touches this once per
// 16 KiB chunk — a Tauri round trip per chunk would be tens of thousands of
// them for one film — and because it has to run inside a synchronous
// `onmessage` handler, where there is nothing to await on.

/** Bytes of header on a chunk message. Mirrors `CHUNK_FRAME_HEADER_BYTES`. */
export const CHUNK_HEADER_BYTES = 4;

/** How much contiguous playback to have in hand before starting. */
export const PLAYABLE_RUNWAY_MS = 5000;

/**
 * How little may be left before a *running* reader stops. Mirrors
 * `SHARE_STALL_FLOOR_MS`.
 *
 * The gap between this and [PLAYABLE_RUNWAY_MS] is the whole point rather than
 * a spare knob: a reader that stopped and started at the same number would sit
 * on it and chatter, and one that stopped only at exactly zero would run out
 * inside the decoder rather than on a decision.
 */
export const STALL_FLOOR_MS = 1000;

/**
 * What a reader at the playhead should do — the JS twin of
 * `comrade_core::share::read_verdict`.
 *
 * Ported rather than called over the bridge for the same reason the framing
 * above is: the answer is wanted inside a synchronous `Range` handler, where
 * there is nothing to await on. The Rust vectors are ported into
 * `share_transfer.test.mjs` so the two cannot quietly disagree.
 *
 * Returns `"start"`, `"continue"` or `"hold"` — the same three arms, snake-cased
 * the way `ReadVerdict`'s `kind` tag spells them.
 *
 * **A hold is local.** It means pause this device's player and keep asking for
 * chunks; it must be reported as `together_report_position(pos, false, latency)`
 * and never as `together_set_state(.., playing: false, ..)`, which is a command
 * and would pause the other person — the ping-pong `docs/TOGETHER.md` §10 rules
 * out.
 */
export function readVerdict({ playing, runwayMs, tailComplete }) {
  if (tailComplete || runwayMs >= PLAYABLE_RUNWAY_MS) return playing ? "continue" : "start";
  if (playing && runwayMs >= STALL_FLOOR_MS) return "continue";
  return "hold";
}

/** Prefix `bytes` with its big-endian chunk index. */
export function frameChunk(index, bytes) {
  const out = new Uint8Array(CHUNK_HEADER_BYTES + bytes.byteLength);
  new DataView(out.buffer).setUint32(0, index, false);
  out.set(bytes, CHUNK_HEADER_BYTES);
  return out;
}

/**
 * Split a received message into `{index, payload}`, or `null` if it is too
 * short to carry a header. The peer can put any bytes at all on this channel,
 * so the length is checked rather than assumed.
 */
export function parseChunkFrame(message) {
  const bytes = message instanceof Uint8Array ? message : new Uint8Array(message);
  if (bytes.byteLength < CHUNK_HEADER_BYTES) return null;
  const index = new DataView(bytes.buffer, bytes.byteOffset, CHUNK_HEADER_BYTES).getUint32(0, false);
  return { index, payload: bytes.subarray(CHUNK_HEADER_BYTES) };
}

/** How many chunks an offer divides into. */
export function chunkCount(offer) {
  if (!offer || !offer.total_bytes || !offer.chunk_bytes) return 0;
  return Math.ceil(offer.total_bytes / offer.chunk_bytes);
}

/** The `[start, length]` of chunk `index`, or `null` past the end. */
export function chunkRange(offer, index) {
  if (index < 0 || index >= chunkCount(offer)) return null;
  const start = index * offer.chunk_bytes;
  return [start, Math.min(offer.total_bytes - start, offer.chunk_bytes)];
}

/**
 * Whether a received chunk is one this offer could have produced.
 *
 * Both halves matter: a wrong index writes bytes into the wrong place, and a
 * wrong length silently shifts everything after it. The hash catches both — at
 * the end of the whole transfer, which is far too late to be useful.
 */
export function chunkFrameFits(offer, index, payloadLength) {
  const range = chunkRange(offer, index);
  return range !== null && range[1] === payloadLength;
}

/**
 * Which chunks have arrived, and what that means for playback. The JS twin of
 * `comrade_core::share::ShareTracker`.
 *
 * Requests are anchored at the **playhead**, not at the start of the file, so a
 * seek into un-fetched territory costs one request rather than a re-download;
 * once everything past the playhead is here it falls back to the earliest gap,
 * so a session that seeked forward still ends with a whole file rather than one
 * with a hole in the middle.
 */
export function createTracker(offer) {
  const count = chunkCount(offer);
  const have = new Array(count).fill(false);
  let received = 0;

  const chunkMs = count === 0 ? 0 : Math.floor((offer.duration_ms ?? 0) / count);
  const firstGapAtOrAfter = (start) => {
    for (let i = start; i < count; i += 1) if (!have[i]) return i;
    return null;
  };

  const api = {
    get chunkCount() {
      return count;
    },
    get chunkMs() {
      return chunkMs;
    },
    accept(index) {
      if (index < 0 || index >= count || have[index]) return false;
      have[index] = true;
      received += 1;
      return true;
    },
    has: (index) => have[index] === true,
    isComplete: () => received === count,
    fraction: () => (count === 0 ? 1 : received / count),
    chunkAtMs(posMs) {
      if (chunkMs === 0) return 0;
      return Math.min(Math.floor(posMs / chunkMs), Math.max(count - 1, 0));
    },
    runwayMs(posMs) {
      let contiguous = 0;
      for (let i = api.chunkAtMs(posMs); i < count; i += 1) {
        if (!have[i]) break;
        contiguous += 1;
      }
      return contiguous * chunkMs;
    },
    /**
     * Whether everything from `posMs` to the end of the file is already here.
     *
     * Named for what it is rather than folded into [playableAt] because it is
     * the arm that means "nothing more is coming": a two-second runway with the
     * last chunk inside it is not a transfer running behind, it is a track
     * nearly over, and waiting for more would wait forever.
     */
    tailCompleteAt(posMs) {
      if (api.isComplete()) return true;
      for (let i = api.chunkAtMs(posMs); i < count; i += 1) if (!have[i]) return false;
      return true;
    },
    /**
     * Whether playback may start at `posMs` — either there is enough runway, or
     * the rest of the file is here and the runway is simply all that remains.
     *
     * Built **on** [tailCompleteAt] and [runwayMs] rather than beside them, the
     * same construction `ShareTracker::playable_at` uses, so the two answers
     * cannot drift apart.
     */
    playableAt(posMs) {
      return api.tailCompleteAt(posMs) || api.runwayMs(posMs) >= PLAYABLE_RUNWAY_MS;
    },
    /**
     * What a reader at `posMs` should do. The tracker-side spelling of
     * [readVerdict], mirroring `ShareTracker::read_verdict_at`.
     *
     * `playing` is "is sound coming out of this device right now", not "does the
     * session want to play" — it only picks which threshold applies, because the
     * cost of stopping a running player is a stutter and the cost of not
     * starting one is a wait.
     */
    readVerdictAt(posMs, playing) {
      return readVerdict({
        playing,
        runwayMs: api.runwayMs(posMs),
        tailComplete: api.tailCompleteAt(posMs),
      });
    },
    nextRequest(posMs, maxCount) {
      if (api.isComplete() || maxCount <= 0) return null;
      const from = firstGapAtOrAfter(api.chunkAtMs(posMs)) ?? firstGapAtOrAfter(0);
      if (from === null) return null;
      let span = 0;
      for (let i = from; i < count; i += 1) {
        if (have[i] || span === maxCount) break;
        span += 1;
      }
      return { from, count: span };
    },
  };
  return api;
}

/**
 * Turn a `ShareVerdictDto` into what the person should be told.
 *
 * A refused transfer gets a specific sentence rather than "transfer failed",
 * because each of these has a different thing the person could do about it:
 * change the policy, use a smaller file, or simply wait a moment longer.
 */
export function describeVerdict(verdict) {
  if (!verdict) return { proceed: false, message: "Couldn't work out how to send this." };
  if (verdict.verdict === "allow") return { proceed: true, message: null };
  if (verdict.verdict === "needs_consent") {
    const mb = Math.round((verdict.relayed_bytes ?? 0) / (1024 * 1024));
    return {
      proceed: false,
      needsConsent: true,
      message: `There's no direct route to them, so ${mb} MB would go through a relay server. Send it anyway?`,
    };
  }
  const reason = verdict.reason?.kind ?? verdict.reason;
  switch (reason) {
    case "relay_forbidden":
      return {
        proceed: false,
        message: "No direct route to them — the file would have to go through a relay, which this device doesn't do.",
      };
    case "too_large_for_relay": {
      const limit = Math.round((verdict.reason?.limit ?? 0) / (1024 * 1024));
      return {
        proceed: false,
        message: `No direct route to them, and this is over the ${limit} MB relay limit.`,
      };
    }
    case "path_unknown":
      return { proceed: false, retryable: true, message: "Still working out a route to them…" };
    default:
      return { proceed: false, message: "Couldn't send this over the route we have." };
  }
}

/**
 * Drive a transfer over `channel`, asking `nextChunks` what to send and pausing
 * on the channel's own backpressure.
 *
 * `nextChunks(budget)` returns up to `budget` payloads (each already framed by
 * the caller) or an empty array when there is nothing to send right now — the
 * receiver-driven protocol means "nothing right now" is normal, not the end.
 * `isDone()` says when to stop.
 *
 * Everything the pump needs from the channel is injected, so this is testable
 * without a browser: node has no `RTCDataChannel`, and the whole point of this
 * module is that the backpressure logic is exercised rather than assumed.
 */
export function createTransferPump({
  channel,
  nextChunks,
  isDone,
  highWater = BUFFER_HIGH_WATER,
  lowWater = BUFFER_LOW_WATER,
  chunkBytes = CHUNK_BYTES,
  onError,
}) {
  if (!channel || typeof channel.send !== "function") {
    throw new TypeError("createTransferPump needs a data channel");
  }
  channel.bufferedAmountLowThreshold = lowWater;

  let running = false;
  let stopped = false;
  let sent = 0;

  function pump() {
    if (stopped || running) return;
    running = true;
    try {
      while (!stopped && !isDone()) {
        const budget = chunksToSend(channel.bufferedAmount, chunkBytes, highWater);
        if (budget === 0) return; // wait for `bufferedamountlow`
        const batch = nextChunks(budget);
        if (!batch || batch.length === 0) return; // nothing to send yet
        for (const payload of batch) {
          if (stopped) return;
          channel.send(payload);
          sent += 1;
          // Re-check inside the batch: `bufferedAmount` moves as we write, and
          // a batch sized against a stale reading is how the ceiling gets
          // overshot on a slow link.
          if (shouldPause(channel.bufferedAmount, highWater)) return;
        }
      }
    } catch (err) {
      stopped = true;
      if (onError) onError(err);
    } finally {
      running = false;
    }
  }

  const onLow = () => pump();
  channel.addEventListener?.("bufferedamountlow", onLow);

  return {
    /** Start, or resume after the receiver asked for more. */
    kick: pump,
    /** Stop for good; safe to call twice. */
    stop() {
      stopped = true;
      channel.removeEventListener?.("bufferedamountlow", onLow);
    },
    get chunksSent() {
      return sent;
    },
    get isStopped() {
      return stopped;
    },
  };
}
