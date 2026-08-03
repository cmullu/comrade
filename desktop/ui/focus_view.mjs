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
