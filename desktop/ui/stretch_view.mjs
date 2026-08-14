/**
 * Decisions behind the desktop stretch break — the guided body-stretch
 * routine on the Focus tab (`docs/ATTENTION.md` phase 2, extended 2026-08-14).
 *
 * The routine itself (which stretches, how long, which need a left and a
 * right side) is `comrade_core::attention::STRETCH_ROUTINE`, reached over the
 * Tauri bridge as `stretch_routine` — this file never invents a step. What it
 * decides is *presentation pacing*: flattening mirrored steps into left/right
 * segments, and answering "which segment is the clock in, and how far along?"
 * so `main.js` can stay DOM glue. Same split as `focus_view.mjs`, same reason.
 *
 * Pacing is local on purpose, unlike the focus countdown: a stretch break is
 * purely presentational — nothing is persisted, nothing lapses, nothing is
 * scored — so there is no engine state for a second-by-second re-read to keep
 * honest. If the machine sleeps mid-break, the break is simply over sooner.
 */

/**
 * Flatten the engine's routine into playable segments: a mirrored step
 * becomes a left segment then a right one, an unmirrored step one segment
 * with `side: null`.
 *
 * A malformed entry (no key, no positive duration) is skipped rather than
 * crashing the break — the routine crosses a bridge, and one bad row must
 * not cost the rest of the stretch.
 *
 * @param {Array<{key: string, name: string, cue: string, seconds: number,
 *   mirrored: boolean}>} routine from the `stretch_routine` command
 * @returns {Array<{key: string, name: string, cue: string, seconds: number,
 *   side: "left" | "right" | null}>}
 */
export function stretchSegments(routine) {
  if (!Array.isArray(routine)) return [];
  const segments = [];
  for (const step of routine) {
    const seconds = Number.isFinite(step?.seconds) ? Math.floor(step.seconds) : 0;
    if (!step || typeof step.key !== "string" || !step.key || seconds <= 0) continue;
    const base = {
      key: step.key,
      name: typeof step.name === "string" ? step.name : "",
      cue: typeof step.cue === "string" ? step.cue : "",
      seconds,
    };
    if (step.mirrored) {
      segments.push({ ...base, side: "left" }, { ...base, side: "right" });
    } else {
      segments.push({ ...base, side: null });
    }
  }
  return segments;
}

/**
 * The whole break's length in seconds, sides included.
 *
 * @param {ReturnType<typeof stretchSegments>} segments
 * @returns {number}
 */
export function stretchTotalSecs(segments) {
  if (!Array.isArray(segments)) return 0;
  return segments.reduce((sum, s) => sum + s.seconds, 0);
}

/**
 * Where the clock is in the break.
 *
 * `null` with no segments (the bridge failed — show no player rather than an
 * empty one). Past the end it pins to the last segment with `done: true`, so
 * the closing frame shows the final stretch at rest instead of blanking.
 *
 * @param {ReturnType<typeof stretchSegments>} segments
 * @param {number} elapsedSecs seconds since the break started (fractional ok)
 * @returns {{index: number, segment: object, into: number, remaining: number,
 *   done: boolean} | null}
 */
export function stretchAt(segments, elapsedSecs) {
  const total = stretchTotalSecs(segments);
  if (total === 0) return null;
  const at = Number.isFinite(elapsedSecs) ? Math.max(0, elapsedSecs) : 0;
  let start = 0;
  for (let i = 0; i < segments.length; i += 1) {
    const end = start + segments[i].seconds;
    if (at < end) {
      return {
        index: i,
        segment: segments[i],
        into: at - start,
        remaining: end - at,
        done: false,
      };
    }
    start = end;
  }
  const last = segments.length - 1;
  return {
    index: last,
    segment: segments[last],
    into: segments[last].seconds,
    remaining: 0,
    done: true,
  };
}

/**
 * How far through the whole break, `0..1` — feeds the progress bar, which
 * (like the breathing screen's) carries no digits: a stretch with a countdown
 * on it becomes a race.
 *
 * @param {ReturnType<typeof stretchSegments>} segments
 * @param {number} elapsedSecs
 * @returns {number}
 */
export function stretchProgress(segments, elapsedSecs) {
  const total = stretchTotalSecs(segments);
  if (total === 0) return 0;
  const at = Number.isFinite(elapsedSecs) ? Math.max(0, elapsedSecs) : 0;
  return Math.min(1, at / total);
}

/**
 * The side line under the stretch name. Words, not an "L/R" glyph — the
 * reader is mid-stretch with their neck bent; the screen should not need
 * decoding.
 *
 * @param {"left" | "right" | null | undefined} side
 * @returns {string}
 */
export function sideLabel(side) {
  switch (side) {
    case "left":
      return "Left side";
    case "right":
      return "Right side";
    default:
      return "";
  }
}
