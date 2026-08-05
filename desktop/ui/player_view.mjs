/**
 * What the Together player *says*, separated from the DOM that shows it.
 *
 * Most of this file is one problem: the app knows two numbers — how far apart
 * the two playheads are, and how well it can measure that — and only the second
 * one decides whether the first is worth reporting. A UI that prints "0.4 s
 * apart" while its own measurement error is 0.8 s is not being precise, it is
 * making something up. `docs/TOGETHER.md` §3 is the long version; this is where
 * the sentence gets chosen.
 *
 * The vocabulary rule from `docs/PRESENCE.md` §5 holds throughout: **never
 * "synced", never "in sync"**, because we do not know that. "Together" means
 * inside the deadband. "We've lost track of them" means we stopped hearing, not
 * that they left.
 */

/** How close the two must be before the app stops narrating the gap. */
export const TOGETHER_MS = 400;

/**
 * `m:ss`, or `h:mm:ss` past an hour.
 *
 * Takes seconds because that is what a `<video>` reports. Guards the non-finite
 * cases rather than printing `NaN:aN`: an unmeasured duration is the normal
 * state for the first moment of every file, and for a stream that never says.
 *
 * @param {number} secs
 */
export function formatTime(secs) {
  if (!Number.isFinite(secs) || secs < 0) return "0:00";
  const whole = Math.floor(secs);
  const s = whole % 60;
  const m = Math.floor(whole / 60) % 60;
  const h = Math.floor(whole / 3600);
  const ss = String(s).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${ss}` : `${m}:${ss}`;
}

/**
 * Where the scrubber thumb goes, as a 0–1000 integer.
 *
 * Clamped, and zero when the duration is unknown — a thumb at a fraction of an
 * unknown whole is a thumb in a meaningless place.
 *
 * @param {number} posMs
 * @param {number} durationMs
 */
export function seekPosition(posMs, durationMs) {
  if (!Number.isFinite(posMs) || !Number.isFinite(durationMs) || durationMs <= 0) {
    return 0;
  }
  const fraction = posMs / durationMs;
  return Math.round(Math.min(1, Math.max(0, fraction)) * 1000);
}

/** The inverse, for a thumb the user moved. */
export function seekToMs(position, durationMs) {
  if (!Number.isFinite(durationMs) || durationMs <= 0) return 0;
  const clamped = Math.min(1000, Math.max(0, Number(position) || 0));
  return Math.round((clamped / 1000) * durationMs);
}

/**
 * How the session is going, in the words we are entitled to use.
 *
 * @param {object} s
 * @param {boolean} [s.joined] they have accepted and opened their copy
 * @param {boolean} [s.lostTrack] the session's TTL lapsed
 * @param {boolean} [s.theyPaused]
 * @param {boolean} [s.correcting] a verdict other than hold is being applied
 * @param {number|null} [s.driftMs]
 * @param {number|null} [s.qualityMs] our own measurement error
 */
export function stateLabel(s = {}) {
  // Ordered by what overrides what. Losing track outranks everything: once the
  // heartbeats stop, every other number on screen is describing the past.
  if (s.lostTrack) return "We've lost track of them";
  if (!s.joined) return "Waiting for them";
  if (s.theyPaused) return "They paused";
  if (s.correcting) return "Catching up…";
  return "Together";
}

/**
 * The gap, reported only when it is bigger than our ability to measure it.
 *
 * Returns `null` when there is nothing honest to say — which the UI shows as
 * blank rather than as a reassuring zero. Two people genuinely in step and two
 * people we cannot measure both produce no number, and the difference between
 * them is carried by [`qualityLabel`], not smuggled into this one.
 *
 * @param {number|null} driftMs signed; positive means we are ahead
 * @param {number|null} qualityMs
 */
export function driftLabel(driftMs, qualityMs) {
  if (!Number.isFinite(driftMs)) return null;
  const quality = Number.isFinite(qualityMs) ? Math.abs(qualityMs) : 0;
  const gap = Math.abs(driftMs);
  // Below our own error, or below the point of mentioning: say nothing.
  if (gap <= Math.max(quality, TOGETHER_MS)) return null;
  const secs = (gap / 1000).toFixed(1);
  return driftMs > 0 ? `${secs}s ahead of them` : `${secs}s behind them`;
}

/**
 * How well we can measure, in plain words.
 *
 * This is the figure that makes the drift number meaningful, so it is shown
 * beside it rather than buried: a session on a fast path can say something
 * useful about a 300 ms gap, and one on a slow path cannot, and the user is
 * entitled to know which they have.
 *
 * @param {number|null} qualityMs half the measured round trip
 */
export function qualityLabel(qualityMs) {
  if (!Number.isFinite(qualityMs) || qualityMs <= 0) return null;
  if (qualityMs <= 50) return "direct · ±0.05s";
  if (qualityMs <= 150) return `direct · ±${(qualityMs / 1000).toFixed(2)}s`;
  return `relayed · ±${(qualityMs / 1000).toFixed(1)}s`;
}

/** `▶` or `⏸`, and the label a screen reader gets. */
export function toggle(playing) {
  return playing
    ? { glyph: "⏸", label: "Pause" }
    : { glyph: "▶", label: "Play" };
}

/**
 * What to call the thing being played.
 *
 * Falls back to the peer's name rather than to a filename — the filename is the
 * one piece of local vocabulary this app never discloses, and showing it on our
 * own screen while refusing to send it would be an odd place to draw the line,
 * but the real reason is simpler: for a handed-over file we do not have one.
 *
 * @param {{title?: string|null, peerLabel?: string|null}} s
 */
export function playingTitle(s = {}) {
  const title = (s.title || "").trim();
  if (title) return title;
  const peer = (s.peerLabel || "").trim();
  return peer ? `Something with ${peer}` : "Together";
}
