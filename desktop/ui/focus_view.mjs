/**
 * Decisions behind the desktop Focus view — `docs/ATTENTION.md` phase 2, the
 * desktop half of what Android's `FocusScreen`/`ReaderScreen` already do.
 *
 * ## Why this is its own module
 *
 * `main.js` is a classic script full of DOM glue and cannot be unit tested, so
 * the parts that decide something live here and `node --test` covers them —
 * the split `call_decisions.mjs` and `media_cache.mjs` already use. What is
 * "just a couple of lines in the click handler" here is exactly how the rest
 * of this UI became untestable.
 *
 * ## What is *not* here
 *
 * The ladder. Which duration to suggest, whether a session lapsed, and how the
 * text is chunked are all `comrade_core::attention`'s answers, reached over the
 * Tauri bridge. Nothing in this file re-derives them; [`chosenPreset`] exists
 * precisely so the UI cannot quietly invent a length the engine would never
 * suggest back.
 *
 * There is also no usage mirror on desktop. It is fed by Android's
 * `UsageStatsManager`, the store is per-device, and a panel that could only
 * ever read zero would be a worse answer than no panel — see §7 of
 * `docs/ATTENTION.md`.
 */

/**
 * `24:05` — a countdown reads in minutes and seconds, so an hour-and-a-half
 * session counts down through `90:00`, not `1:30:00`.
 *
 * Anything not a finite number, and anything past the end of the session,
 * floors at `0:00`: the engine is the authority on remaining time and it
 * already clamps, but a NaN painted into the DOM would be the one bug the user
 * sees rather than a log line.
 *
 * @param {number} remainingSecs seconds left, from `FocusSessionDto`
 * @returns {string}
 */
export function formatCountdown(remainingSecs) {
  const total = Number.isFinite(remainingSecs) ? Math.max(0, Math.floor(remainingSecs)) : 0;
  const secs = total % 60;
  return `${Math.floor(total / 60)}:${String(secs).padStart(2, "0")}`;
}

/**
 * How a finished session is named. Deliberately flat: "Stopped early" is
 * information, not a verdict, and matches the Android wording exactly
 * (`R.string.focus_outcome_*`) so the same history reads the same on both.
 *
 * An unrecognised outcome reads as "Lapsed" rather than throwing — the value
 * comes from storage written by a possibly newer build, and a Focus tab that
 * blanked on one unknown row would lose the rest of the history with it.
 *
 * @param {string | null | undefined} outcome `completed` / `abandoned` / `lapsed`
 * @returns {string}
 */
export function outcomeLabel(outcome) {
  switch (outcome) {
    case "completed":
      return "Completed";
    case "abandoned":
      return "Stopped early";
    default:
      return "Lapsed";
  }
}

/**
 * One line of history: `45m · Completed · write the letter`.
 *
 * The intent is optional (the engine accepts an empty one), so the separator
 * has to go with it rather than leaving a dangling ` · `.
 *
 * @param {{planned_minutes: number, outcome: string | null, intent: string}} session
 * @returns {string}
 */
export function historyLine(session) {
  const head = `${session.planned_minutes}m · ${outcomeLabel(session.outcome)}`;
  const intent = (session.intent || "").trim();
  return intent ? `${head} · ${intent}` : head;
}

/**
 * Which duration chip is selected.
 *
 * The engine offers the rungs (`focus_presets`) and suggests one
 * (`suggested_focus_minutes`); the user may have clicked a third thing. This
 * resolves the three, and its whole job is that the answer is **always a rung
 * the engine offered**: a length outside the list is one
 * `suggest_focus_minutes` can never return, so offering it would make the
 * ladder's next suggestion look like a demotion.
 *
 * With no presets at all — the bridge failed, or the vault call errored — it
 * returns `null` and the caller shows no chips rather than a made-up row.
 *
 * @param {number[]} presets ascending rungs from the engine
 * @param {number} suggested the engine's suggestion
 * @param {number | null} [chosen] what the user clicked, if anything
 * @returns {number | null}
 */
export function chosenPreset(presets, suggested, chosen = null) {
  if (!Array.isArray(presets) || presets.length === 0) return null;
  if (presets.includes(chosen)) return chosen;
  if (presets.includes(suggested)) return suggested;
  return presets[0];
}

/**
 * What the reader's controls should say and whether they are live.
 *
 * @param {number} position zero-based chunk index
 * @param {number} total how many chunks the engine produced
 * @returns {{position: number, total: number, label: string, canPrev: boolean,
 *   canNext: boolean, atEnd: boolean}}
 */
export function readerNav(position, total) {
  const count = Number.isFinite(total) ? Math.max(0, Math.floor(total)) : 0;
  const at = clampPosition(position, count);
  return {
    position: at,
    total: count,
    // One-based for the reader, matching Android's `reader_progress`.
    label: count === 0 ? "" : `${at + 1} of ${count}`,
    canPrev: at > 0,
    canNext: count > 0 && at < count - 1,
    atEnd: count > 0 && at === count - 1,
  };
}

/**
 * Move the reader, or report that it did not move.
 *
 * `null` means "nothing changed, do not write" — the caller persists the
 * position through `set_reading_position`, which is a disk write into the
 * encrypted store, and holding the Next key down at the last chunk should not
 * turn into a write per repeat.
 *
 * @param {number} position current chunk index
 * @param {number} total chunk count
 * @param {number} delta `+1` / `-1`
 * @returns {number | null} the new position, or null if it would not move
 */
export function stepReader(position, total, delta) {
  const count = Number.isFinite(total) ? Math.max(0, Math.floor(total)) : 0;
  if (count === 0) return null;
  const from = clampPosition(position, count);
  const to = clampPosition(from + (Number.isFinite(delta) ? Math.trunc(delta) : 0), count);
  return to === from ? null : to;
}

/**
 * A stored position past the end — the text was replaced by a shorter one —
 * reads as the last chunk, not as an out-of-range index. The Rust side clamps
 * too; this keeps the DOM honest in the frame before its answer arrives.
 *
 * @param {number} position
 * @param {number} count
 * @returns {number}
 */
function clampPosition(position, count) {
  if (count <= 0) return 0;
  const at = Number.isFinite(position) ? Math.floor(position) : 0;
  return Math.min(Math.max(at, 0), count - 1);
}

/* ── The reading shelf ─────────────────────────────────────────────────────────
 *
 * What replaced "paste an article": the shelf is filled by importing the user's
 * own platform export or by pasting a link, and the reader opens one row of it.
 * Same split as above — the engine decides, this names things. */

/**
 * Where a save came from, in the words a person reads.
 *
 * The keys are `comrade_core::library::SaveSource::as_str`'s and the labels must
 * match Android's `R.string.library_source_*`, for the reason `outcomeLabel`'s
 * do: the same shelf, read on a phone and on a laptop, should not describe
 * itself differently. An unknown key reads as "Saved" rather than throwing — the
 * value is off disk and may have been written by a newer build.
 *
 * @param {string | null | undefined} source
 * @returns {string}
 */
export function sourceLabel(source) {
  switch (source) {
    case "instagram":
      return "Instagram";
    case "twitter":
      return "X";
    case "facebook":
      return "Facebook";
    case "reddit":
      return "Reddit";
    case "youtube":
      return "YouTube";
    case "readlater":
      return "Read later";
    case "pasted":
      return "Pasted here";
    case "web":
      return "Web";
    default:
      return "Saved";
  }
}

/**
 * The host of a URL, for a row whose title is empty. `null` when there is no
 * parsable host — the caller then has nothing better to show than the raw
 * string, which is still better than an empty row.
 *
 * @param {string | null | undefined} url
 * @returns {string | null}
 */
export function hostOf(url) {
  if (typeof url !== "string") return null;
  const match = /^https?:\/\/([^/?#\s]+)/i.exec(url.trim());
  if (!match) return null;
  const authority = match[1];
  // An `@` means credentials (or a host being hidden behind one), which is a
  // shape to refuse rather than to parse — the same line the Rust side draws.
  if (authority.includes("@")) return null;
  return authority.split(":")[0].replace(/^www\./i, "").toLowerCase() || null;
}

/**
 * One row of the shelf: what to show, and what the row can do.
 *
 * The load-bearing part is `canRead`. A bookmark imported from Instagram is a
 * title and a link and **nothing to read** — Comrade does not fetch the page —
 * so a row that offered "Read" for it would open an empty reader and read as a
 * bug. The honest offer there is the link, plus somewhere to paste the text.
 *
 * @param {{title?: string, url?: string | null, source?: string,
 *   has_text?: boolean, estimated_minutes?: number, chunk_count?: number,
 *   position?: number, finished_at?: number | null, is_open?: boolean}} item
 * @returns {{title: string, meta: string, canRead: boolean, canOpenLink: boolean,
 *   progressLabel: string}}
 */
export function shelfRow(item = {}) {
  const url = typeof item.url === "string" ? item.url : null;
  const title = (item.title || "").trim() || hostOf(url) || url || "Untitled";
  const canRead = item.has_text === true;
  const minutes = Number.isFinite(item.estimated_minutes) ? item.estimated_minutes : 0;
  const parts = [sourceLabel(item.source)];
  if (canRead && minutes > 0) parts.push(`~${minutes} min`);
  if (!canRead) parts.push("link only");
  if (item.finished_at) parts.push("finished");
  else if (item.is_open) parts.push("open");
  return {
    title,
    meta: parts.join(" · "),
    canRead,
    canOpenLink: url !== null,
    progressLabel: readerNav(item.position ?? 0, item.chunk_count ?? 0).label,
  };
}

/**
 * What to say after an import, in one sentence, from `ImportReportDto`.
 *
 * Every number the engine counted gets said. "Added 412" alone is not an honest
 * answer to a file with 900 rows in it — the person who chose that file will
 * assume the other 488 are lost, and the two facts that stop them assuming it
 * (they were already here; we could not read them) are exactly the ones a
 * cheerful one-number summary drops.
 *
 * @param {{added?: number, duplicates?: number, skipped?: number,
 *   truncated?: boolean, fell_back?: boolean} | null | undefined} report
 * @returns {string}
 */
export function importSummary(report) {
  if (!report) return "";
  const num = (n) => (Number.isFinite(n) ? Math.max(0, Math.floor(n)) : 0);
  const added = num(report.added);
  const dupes = num(report.duplicates);
  const skipped = num(report.skipped);
  const parts = [added === 1 ? "Added 1 save" : `Added ${added} saves`];
  if (dupes > 0) parts.push(`${dupes} already on your shelf`);
  if (skipped > 0) parts.push(`${skipped} we could not read`);
  let sentence = `${parts.join(", ")}.`;
  if (report.truncated) {
    sentence += " That file had more in it than one import takes — run it again to continue.";
  }
  if (report.fell_back) {
    sentence += " We did not recognise the format, so we took the links out of it; the titles did not survive.";
  }
  return sentence;
}

/* ── The countdown ring ───────────────────────────────────────────────────────
 *
 * The Opera Air-shaped part of this tab: the clock is a ring that empties rather
 * than a number that shrinks. The geometry is here because it is arithmetic, and
 * arithmetic in an event handler is what made the rest of this UI untestable. */

/**
 * Radius of the countdown ring, in the SVG's own units. The stroke is drawn
 * inside a 128×128 box, so the radius plus half the stroke width has to clear
 * the edge or the ring is clipped at the cardinal points only — which looks like
 * a rendering glitch rather than like a wrong number.
 */
export const RING_RADIUS = 56;

/** Full circumference of the ring. */
export const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;

/**
 * `stroke-dasharray` / `stroke-dashoffset` for a ring showing `remaining` of
 * `total` seconds.
 *
 * The ring **empties** as the session runs: full at the start, gone at the end.
 * The other direction (filling up) is the progress bar of a download, and it
 * invites the question "how much have I earned" — which is the scoring this
 * pillar refuses. A ring draining is just where the time went.
 *
 * A zero or nonsense `total` gives a full ring rather than a NaN dash, which
 * would silently paint nothing at all.
 *
 * @param {number} remainingSecs
 * @param {number} totalSecs
 * @returns {{dash: number, offset: number, fraction: number}}
 */
export function ringGeometry(remainingSecs, totalSecs) {
  const total = Number.isFinite(totalSecs) && totalSecs > 0 ? totalSecs : 0;
  const remaining = Number.isFinite(remainingSecs) ? Math.max(0, remainingSecs) : 0;
  const fraction = total === 0 ? 1 : Math.min(1, remaining / total);
  return {
    dash: RING_CIRCUMFERENCE,
    offset: RING_CIRCUMFERENCE * (1 - fraction),
    fraction,
  };
}

/**
 * The ambient background's drifting blobs — position, size and animation delay
 * per blob.
 *
 * This is the "aeromorphic" layer: three big soft radial gradients moving slowly
 * behind glass panels. It is data rather than CSS so the count and the timings
 * are testable, and because the one rule that matters here is enforceable in
 * code and not in a stylesheet: **`reduceMotion` freezes every blob**. A calm
 * surface that ignores `prefers-reduced-motion` is not calm, it is just pretty,
 * and this is the tab someone opens when they already feel scattered.
 *
 * Durations are deliberately long and mutually prime-ish, so the three never
 * settle into a visible shared beat — a repeating pulse is the thing an eye
 * catches, and catching the background is the opposite of the point.
 *
 * @param {boolean} reduceMotion
 * @returns {Array<{x: number, y: number, size: number, durationMs: number, delayMs: number}>}
 */
export function ambientBlobs(reduceMotion = false) {
  const blobs = [
    { x: 18, y: 22, size: 52, durationMs: 47_000, delayMs: 0 },
    { x: 74, y: 30, size: 44, durationMs: 61_000, delayMs: -12_000 },
    { x: 46, y: 82, size: 60, durationMs: 73_000, delayMs: -26_000 },
  ];
  return reduceMotion ? blobs.map((b) => ({ ...b, durationMs: 0, delayMs: 0 })) : blobs;
}
