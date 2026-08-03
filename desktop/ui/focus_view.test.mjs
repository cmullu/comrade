import { test } from "node:test";
import assert from "node:assert/strict";

import {
  chosenPreset,
  formatCountdown,
  historyLine,
  outcomeLabel,
  readerNav,
  stepReader,
} from "./focus_view.mjs";

test("the countdown counts minutes, not hours", () => {
  assert.equal(formatCountdown(0), "0:00");
  assert.equal(formatCountdown(7), "0:07");
  assert.equal(formatCountdown(65), "1:05");
  assert.equal(formatCountdown(25 * 60), "25:00");
  // A 90-minute session reads 90:00, not 1:30:00 — the point of the clock is
  // "how much of the block is left", and the block is measured in minutes.
  assert.equal(formatCountdown(90 * 60), "90:00");
});

test("a nonsense remaining time floors at zero instead of painting NaN", () => {
  assert.equal(formatCountdown(-5), "0:00");
  assert.equal(formatCountdown(NaN), "0:00");
  assert.equal(formatCountdown(undefined), "0:00");
  assert.equal(formatCountdown(1.9), "0:01", "partial seconds round down");
});

test("stopping early is named plainly, not scored", () => {
  assert.equal(outcomeLabel("completed"), "Completed");
  assert.equal(outcomeLabel("abandoned"), "Stopped early");
  assert.equal(outcomeLabel("lapsed"), "Lapsed");
  // Gate 3: nothing in this vocabulary is a failure state.
  for (const key of ["completed", "abandoned", "lapsed"]) {
    assert.ok(!/fail|missed|lost|broke/i.test(outcomeLabel(key)), key);
  }
});

test("an outcome this build does not know still renders a row", () => {
  // Written by a newer build, read by this one. Losing the whole history to
  // one unrecognised string would be a worse answer than a conservative label.
  assert.equal(outcomeLabel("something-new"), "Lapsed");
  assert.equal(outcomeLabel(null), "Lapsed");
});

test("a history line drops the separator with the intent", () => {
  assert.equal(
    historyLine({ planned_minutes: 45, outcome: "completed", intent: "write the letter" }),
    "45m · Completed · write the letter",
  );
  assert.equal(
    historyLine({ planned_minutes: 25, outcome: "abandoned", intent: "" }),
    "25m · Stopped early",
  );
  assert.equal(
    historyLine({ planned_minutes: 25, outcome: "lapsed", intent: "   " }),
    "25m · Lapsed",
    "whitespace is not an intent",
  );
});

test("the selected duration is always one the engine offered", () => {
  const presets = [25, 45, 90];
  // Nothing clicked yet: the engine's suggestion.
  assert.equal(chosenPreset(presets, 45), 45);
  // A click wins over the suggestion.
  assert.equal(chosenPreset(presets, 45, 25), 25);
  // A length outside the ladder cannot be selected — `suggest_focus_minutes`
  // could never return it, so the next suggestion would look like a demotion.
  assert.equal(chosenPreset(presets, 45, 60), 45);
  // …and if the suggestion itself is off-ladder, the lowest rung.
  assert.equal(chosenPreset(presets, 60, 60), 25);
});

test("no presets means no chips, not an invented row", () => {
  assert.equal(chosenPreset([], 25), null);
  assert.equal(chosenPreset(undefined, 25), null);
  assert.equal(chosenPreset(null, 25, 25), null);
});

test("reader controls know where they are", () => {
  assert.deepEqual(readerNav(0, 3), {
    position: 0,
    total: 3,
    label: "1 of 3",
    canPrev: false,
    canNext: true,
    atEnd: false,
  });
  assert.deepEqual(readerNav(2, 3), {
    position: 2,
    total: 3,
    label: "3 of 3",
    canPrev: true,
    canNext: false,
    atEnd: true,
  });
  // A single chunk is both the start and the end.
  const only = readerNav(0, 1);
  assert.equal(only.canPrev, false);
  assert.equal(only.canNext, false);
  assert.equal(only.atEnd, true);
});

test("a position left over from longer text lands on the last chunk", () => {
  // The text was replaced by a shorter one; the stored position outlived it.
  const nav = readerNav(11, 3);
  assert.equal(nav.position, 2);
  assert.equal(nav.label, "3 of 3");
  assert.equal(nav.atEnd, true);
});

test("an empty read has no progress to report", () => {
  const nav = readerNav(0, 0);
  assert.equal(nav.label, "");
  assert.equal(nav.canPrev, false);
  assert.equal(nav.canNext, false);
  assert.equal(nav.atEnd, false, "nothing to be at the end of");
});

test("a step that would not move reports no write", () => {
  assert.equal(stepReader(1, 3, 1), 2);
  assert.equal(stepReader(1, 3, -1), 0);
  // Each of these persists to the encrypted store, so a held key at either
  // end must not turn into a write per repeat.
  assert.equal(stepReader(2, 3, 1), null, "already at the end");
  assert.equal(stepReader(0, 3, -1), null, "already at the start");
  assert.equal(stepReader(0, 0, 1), null, "nothing loaded");
  assert.equal(stepReader(1, 3, 0), null, "a zero step is not a move");
});
