import { test } from "node:test";
import assert from "node:assert/strict";

import {
  RING_CIRCUMFERENCE,
  ambientBlobs,
  chosenPreset,
  formatCountdown,
  historyLine,
  hostOf,
  importSummary,
  outcomeLabel,
  readerNav,
  ringGeometry,
  shelfRow,
  sourceLabel,
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

/* ── The reading shelf ───────────────────────────────────────────────────── */

test("a source is named the same way it is named on Android", () => {
  assert.equal(sourceLabel("instagram"), "Instagram");
  assert.equal(sourceLabel("twitter"), "X");
  assert.equal(sourceLabel("facebook"), "Facebook");
  assert.equal(sourceLabel("pasted"), "Pasted here");
  // A row written by a newer build still renders — the value comes off disk.
  assert.equal(sourceLabel("mastodon"), "Saved");
  assert.equal(sourceLabel(null), "Saved");
});

test("a host is read off a URL, and a credentials shape is refused", () => {
  assert.equal(hostOf("https://www.Example.com/a/b?c=1"), "example.com");
  assert.equal(hostOf("http://sub.example.co.uk:8443/x"), "sub.example.co.uk");
  assert.equal(hostOf("https://user@evil.example/real.example.com"), null);
  assert.equal(hostOf("not a url"), null);
  assert.equal(hostOf(undefined), null);
});

test("a bookmark row does not offer a reader it has nothing to fill", () => {
  // The load-bearing case: an imported Instagram save is a title and a link.
  // Comrade does not fetch the page, so "Read" would open an empty reader.
  const bookmark = shelfRow({
    title: "",
    url: "https://www.instagram.com/p/CxAbc123/",
    source: "instagram",
    has_text: false,
    estimated_minutes: 0,
    chunk_count: 0,
  });
  assert.equal(bookmark.canRead, false);
  assert.equal(bookmark.canOpenLink, true);
  assert.equal(bookmark.title, "instagram.com", "no title: the host says something");
  assert.match(bookmark.meta, /Instagram/);
  assert.match(bookmark.meta, /link only/);
  assert.ok(!/min/.test(bookmark.meta), "no reading time for something with no text");
});

test("a readable row says how long it is and where it is up to", () => {
  const row = shelfRow({
    title: "  The attention economy  ",
    url: "https://example.com/essay",
    source: "web",
    has_text: true,
    estimated_minutes: 7,
    chunk_count: 4,
    position: 1,
  });
  assert.equal(row.title, "The attention economy");
  assert.equal(row.canRead, true);
  assert.match(row.meta, /~7 min/);
  assert.equal(row.progressLabel, "2 of 4");
});

test("an open row and a finished row say which they are", () => {
  assert.match(shelfRow({ has_text: true, is_open: true }).meta, /open/);
  assert.match(shelfRow({ has_text: true, finished_at: 123 }).meta, /finished/);
  // Finished wins: a row cannot usefully be both, and "finished" is the fact
  // the reader wants when deciding whether to open it again.
  const both = shelfRow({ has_text: true, is_open: true, finished_at: 123 });
  assert.match(both.meta, /finished/);
  assert.ok(!/open/.test(both.meta));
});

test("a row with nothing at all still renders", () => {
  const empty = shelfRow();
  assert.equal(empty.title, "Untitled");
  assert.equal(empty.canRead, false);
  assert.equal(empty.canOpenLink, false);
});

test("an import summary says everything it dropped, not just what it added", () => {
  // "Added 412" alone invites the reader to assume the rest are lost.
  const s = importSummary({ added: 412, duplicates: 480, skipped: 8 });
  assert.match(s, /412 saves/);
  assert.match(s, /480 already on your shelf/);
  assert.match(s, /8 we could not read/);

  // The quiet cases stay quiet.
  assert.equal(importSummary({ added: 1, duplicates: 0, skipped: 0 }), "Added 1 save.");
  assert.equal(importSummary(null), "");

  // Truncation and a fallback are both things the user has to be told.
  assert.match(importSummary({ added: 2000, truncated: true }), /run it again/);
  assert.match(importSummary({ added: 12, fell_back: true }), /titles did not survive/i);
});

/* ── The ring and the ambient layer ──────────────────────────────────────── */

test("the countdown ring empties rather than filling", () => {
  const full = ringGeometry(1500, 1500);
  assert.equal(full.fraction, 1);
  assert.equal(full.offset, 0, "a full ring is drawn with no gap");

  const half = ringGeometry(750, 1500);
  assert.equal(half.fraction, 0.5);
  assert.ok(Math.abs(half.offset - RING_CIRCUMFERENCE / 2) < 1e-9);

  const done = ringGeometry(0, 1500);
  assert.equal(done.fraction, 0);
  assert.ok(Math.abs(done.offset - RING_CIRCUMFERENCE) < 1e-9, "an empty ring is all gap");
});

test("a nonsense ring input paints a full ring, never a NaN dash", () => {
  for (const [remaining, total] of [
    [NaN, 1500],
    [10, 0],
    [10, NaN],
    [undefined, undefined],
  ]) {
    const g = ringGeometry(remaining, total);
    assert.ok(Number.isFinite(g.offset), `offset for ${remaining}/${total}`);
    assert.ok(g.fraction >= 0 && g.fraction <= 1);
  }
  // Past the end of the session the ring cannot over-fill.
  assert.equal(ringGeometry(3000, 1500).fraction, 1);
});

test("reduced motion freezes the ambient background completely", () => {
  const moving = ambientBlobs(false);
  assert.equal(moving.length, 3);
  assert.ok(moving.every((b) => b.durationMs > 0));
  // Three different periods, so the eye never catches a shared beat.
  assert.equal(new Set(moving.map((b) => b.durationMs)).size, 3);

  // This is the rule worth a test rather than a stylesheet: the calm tab is the
  // one someone opens when they already feel scattered, and a background that
  // ignores prefers-reduced-motion is not calm, only pretty.
  const still = ambientBlobs(true);
  assert.ok(still.every((b) => b.durationMs === 0 && b.delayMs === 0));
  assert.deepEqual(
    still.map((b) => [b.x, b.y, b.size]),
    moving.map((b) => [b.x, b.y, b.size]),
    "the layout is unchanged; only the motion stops",
  );
});
