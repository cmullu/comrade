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
