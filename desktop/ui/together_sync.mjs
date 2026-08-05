/**
 * Watch/listen-together: the decisions that must not live in an event handler.
 *
 * ## Why this exists
 *
 * The drift arithmetic is not here — it is in `comrade_core::together`, so all
 * three frontends inherit one answer (`docs/COMMS_ARCHITECTURE.md` ADR-2). What
 * *is* here is the part that is unavoidably about this player's event
 * semantics, and that is where the one bug every sync-play implementation ships
 * lives: **applying a remote position makes the local player fire the very
 * events we listen to, and re-broadcasting those is a feedback loop.**
 *
 * The obvious fixes all fail, which is why this is a module and not a boolean:
 *
 *  - `applying = true; el.currentTime = x; applying = false` — `seeked` fires
 *    asynchronously, several ticks later. The flag is already false.
 *  - clear the flag inside the `seeked` handler — now a *user* seek during an
 *    in-flight apply is swallowed and never reaches the other person. Worse,
 *    assigning `currentTime` the value it already holds fires **no event at
 *    all**, so the flag never clears and the session goes permanently deaf.
 *  - "ignore events for 500 ms after an apply" — swallows genuine user actions,
 *    and they are *most* likely exactly then, because the user is reacting to
 *    the jump they just saw.
 *
 * So: a value-matched, deadline-bounded ledger. An apply records what it
 * expects to see; an observed event that matches a live entry consumes it and
 * stays silent; anything else is the user and is reported. Entries are reaped
 * by deadline rather than popped in order, because two rapid assignments can
 * coalesce into a single `seeked` and leave a stale entry behind that must
 * expire rather than swallow a later genuine seek to the same spot.
 *
 * The invariant a reviewer should check any new call site against:
 * **the only outbound signals are ones the local user caused.**
 */

/** Tunables, all in the units the DOM uses (seconds for positions). */
export const TOGETHER_UI = Object.freeze({
  /** Browsers snap `currentTime` to frame/sample boundaries, so what comes back
   *  is never exactly what was set. */
  EPSILON_SECS: 0.25,
  /** How long an apply may wait for its event before we assume it produced
   *  none (the no-op assignment case). */
  SUPPRESS_TTL_MS: 1500,
  /** A correction must never produce chipmunk audio, whatever the core says. */
  RATE_MIN: 0.9,
  RATE_MAX: 1.1,
  /** How often we report our own playhead inward (no network traffic). */
  REPORT_MS: 1000,
  /** After this long with no word from them, say so rather than guess. */
  SILENCE_MS: 90_000,
  /** A ratio outside these is a broken or hostile header, not a recording.
   *  Mirrors `TogetherDecisions.MIN_ASPECT`/`MAX_ASPECT`. */
  MIN_ASPECT: 0.25,
  MAX_ASPECT: 4.0,
});

/**
 * What the recording turned out to be, from the dimensions the player reports.
 *
 * A `<video>` element plays audio perfectly well — which is why there is only
 * one of them — but it renders a black rectangle for a file with no picture, so
 * someone sharing an album gets a large dead box above the controls. The
 * element cannot answer this before `loadedmetadata`, and the picked MIME type
 * is not an answer either: an `.mkv` of an album is `video/x-matroska` with no
 * video track and a `.mp4` podcast is `video/mp4`.
 *
 * Deliberately identical to `TogetherDecisions.pictureOf` on Android, vectors
 * and all, so the two frontends disagreeing is a red test rather than a field
 * report (`docs/COMMS_ARCHITECTURE.md` ADR-2).
 *
 * @param {number} width `videoWidth`, 0 when there is no video track
 * @param {number} height `videoHeight`
 * @returns {{kind: "video", width: number, height: number} | {kind: "none"}}
 */
export function pictureOf(width, height) {
  return width > 0 && height > 0
    ? { kind: "video", width, height }
    : { kind: "none" };
}

/**
 * The shape to give the player, or `null` when there is no picture to shape.
 *
 * Clamped for the same reason as the Android side: the dimensions come out of a
 * file the other person chose, and a header claiming `1x1000000` must produce
 * something drawable rather than a layout that takes the window with it.
 *
 * @param {{kind: string, width?: number, height?: number}} picture
 * @returns {number | null}
 */
export function aspectRatioOf(picture) {
  if (!picture || picture.kind !== "video") return null;
  const ratio = picture.width / picture.height;
  if (!Number.isFinite(ratio) || ratio <= 0) return null;
  return Math.min(Math.max(ratio, TOGETHER_UI.MIN_ASPECT), TOGETHER_UI.MAX_ASPECT);
}

/**
 * A ledger of player events an apply is about to cause.
 *
 * @param {object} options
 * @param {() => number} options.now monotonic-ish clock in ms (injected: node
 *   has no reason to share the DOM's)
 * @param {number} [options.epsilonSecs]
 * @param {number} [options.ttlMs]
 */
export function createEchoSuppressor({
  now,
  epsilonSecs = TOGETHER_UI.EPSILON_SECS,
  ttlMs = TOGETHER_UI.SUPPRESS_TTL_MS,
} = {}) {
  if (typeof now !== "function") {
    throw new TypeError("createEchoSuppressor needs now()");
  }
  /** @type {{kind: string, position: number|null, deadline: number}[]} */
  let pending = [];

  /** Drop entries whose apply never produced an event. */
  function reap(at) {
    pending = pending.filter((e) => e.deadline > at);
  }

  return {
    /** Record that we are about to cause `entry`. */
    expect({ kind, position = null }) {
      const at = now();
      reap(at);
      pending.push({ kind, position, deadline: at + ttlMs });
      // Bounded: an apply storm must not grow this without limit.
      if (pending.length > 16) pending.shift();
    },

    /**
     * Is this observed event explained by something we did? Consumes the entry
     * if so — one apply explains exactly one event.
     */
    explains({ kind, position = null }) {
      const at = now();
      reap(at);
      const idx = pending.findIndex(
        (e) =>
          e.kind === kind &&
          (e.position === null ||
            position === null ||
            Math.abs(e.position - position) <= epsilonSecs),
      );
      if (idx === -1) return false;
      pending.splice(idx, 1);
      return true;
    },

    clear() {
      pending = [];
    },

    get size() {
      return pending.length;
    },
  };
}

/**
 * Turn a verdict from `comrade_core::together::sync_verdict` into player
 * operations **and the ledger entries that must be armed alongside them**.
 *
 * The pairing is the whole point of returning a value rather than doing it
 * inline: an op that can raise an event without a matching `expect` *is* the
 * feedback loop, and it is a one-line mistake. A test can assert the pairing
 * without a DOM.
 *
 * @param {{kind: string, pos_ms?: number, rate?: number, playing?: boolean}} verdict
 * @param {{playing: boolean, positionSecs: number}} local
 */
export function planApply(verdict, local) {
  const ops = [];
  const expect = [];
  if (!verdict || typeof verdict.kind !== "string") return { ops, expect };

  switch (verdict.kind) {
    case "hold":
      break;

    case "nudge": {
      // A rate trim raises only `ratechange`, which is never signalled — so
      // this branch is structurally silent rather than merely suppressed, and
      // it is why the common case needs no ledger entry at all.
      const rate = Math.min(
        TOGETHER_UI.RATE_MAX,
        Math.max(TOGETHER_UI.RATE_MIN, Number(verdict.rate) || 1),
      );
      ops.push({ kind: "rate", value: rate });
      break;
    }

    case "seek": {
      const secs = (Number(verdict.pos_ms) || 0) / 1000;
      ops.push({ kind: "seek", secs });
      expect.push({ kind: "seek", position: secs });
      // Deliberately no play op: a drift correction must never start playback
      // on a session the user has paused.
      break;
    }

    case "adopt": {
      // A command we missed — take their state wholesale, playing included.
      const secs = (Number(verdict.pos_ms) || 0) / 1000;
      if (Math.abs(secs - local.positionSecs) > TOGETHER_UI.EPSILON_SECS) {
        ops.push({ kind: "seek", secs });
        expect.push({ kind: "seek", position: secs });
      }
      if (verdict.playing !== local.playing) {
        const kind = verdict.playing ? "play" : "pause";
        ops.push({ kind });
        expect.push({ kind });
      }
      break;
    }

    default:
      break;
  }
  return { ops, expect };
}

/**
 * What, if anything, a local player event should tell the other person.
 *
 * @param {{type: string, positionSecs: number, playing?: boolean}} event
 * @param {ReturnType<typeof createEchoSuppressor>} suppressor
 * @returns {{emit: null | {playing: boolean, positionSecs: number}}}
 */
export function classifyLocalEvent(event, suppressor) {
  const { type, positionSecs } = event;

  // Rate is a local correction and is never news. Buffering is deliberately
  // not signalled either: `waiting` as a remote pause is the worst ping-pong
  // available here — one side stalls, pauses the other, and that pause makes
  // the first side re-evaluate. A stall is ridden out locally and the next
  // drift verdict closes the gap.
  if (type === "ratechange" || type === "waiting" || type === "stalled") {
    return { emit: null };
  }

  // Reaching the end is a pause, never a seek: the playhead did not move
  // anywhere the other person needs to follow.
  if (type === "ended") {
    return { emit: { playing: false, positionSecs } };
  }

  const kind = type === "seeked" ? "seek" : type;
  if (kind !== "seek" && kind !== "play" && kind !== "pause") {
    return { emit: null };
  }
  if (suppressor.explains({ kind, position: kind === "seek" ? positionSecs : null })) {
    return { emit: null };
  }
  return {
    emit: {
      playing: kind === "pause" ? false : kind === "play" ? true : event.playing === true,
      positionSecs,
    },
  };
}

/**
 * The status line, as a pure function so the words can be pinned by a test.
 *
 * Two rules carry the honesty here, both borrowed from `docs/PRESENCE.md` §5.
 * We never say "in sync" or "synced" — we do not know that, and a green tick
 * beside two players eleven seconds apart is exactly the small lie this app
 * avoids. And when the heartbeats stop we say we lost track of *them*, because
 * that is what we observed; we did not observe them diverging.
 */
export function sessionStatusLabel(state) {
  const {
    joined = false,
    weLead = false,
    theirsReady = true,
    oursReady = true,
    playing = false,
    correcting = false,
    silentForMs = 0,
    peerName = "They",
    durationDeltaSecs = null,
    ended = null,
  } = state || {};

  if (ended === "peer") return `${peerName} left`;
  if (ended === "timeout") return "we've lost track of them";
  if (silentForMs >= TOGETHER_UI.SILENCE_MS) return "we've lost track of them";
  if (!joined) {
    return weLead ? "waiting for them to open it" : "waiting for you to join";
  }
  if (!oursReady) return "open your copy to start";
  if (!theirsReady) return "waiting for them to open their copy";
  if (durationDeltaSecs !== null && Math.abs(durationDeltaSecs) >= 1) {
    const secs = Math.round(Math.abs(durationDeltaSecs));
    const longer = durationDeltaSecs > 0 ? "longer" : "shorter";
    return `their copy runs ${secs} second${secs === 1 ? "" : "s"} ${longer} than yours`;
  }
  if (!playing) return `${peerName} paused`;
  if (correcting) return "catching up…";
  return "together";
}
