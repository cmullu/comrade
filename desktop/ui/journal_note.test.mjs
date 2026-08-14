import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import {
  NOTE_PREVIEW_CHARS,
  NOTE_PREVIEW_LINES,
  noteHeader,
  notePreview,
} from "./journal_note.mjs";

// Mirrored in `android/.../ui/JournalNoteTest.kt` and
// `app/test/journal_note_test.dart` — same cases, same numbers.

test("a short note arrives whole and offers nothing to expand", () => {
  const note = "rough morning, but I went for the walk";
  assert.deepEqual(notePreview(note), { text: note, truncated: false });
});

test("a long note is folded at the line limit", () => {
  const note = Array.from({ length: 10 }, (_, i) => `line ${i + 1}`).join("\n");
  const preview = notePreview(note);
  assert.equal(preview.text.split("\n").length, NOTE_PREVIEW_LINES);
  assert.equal(preview.truncated, true);
  assert.ok(preview.text.startsWith("line 1"));
});

// The line limit alone would let this through whole: it is one line.
test("one very long paragraph is folded too", () => {
  const preview = notePreview("word ".repeat(200).trim());
  assert.equal(preview.truncated, true);
  assert.ok(preview.text.length <= NOTE_PREVIEW_CHARS);
});

test("the fold falls on a word boundary", () => {
  const preview = notePreview("supercalifragilistic ".repeat(40).trim());
  assert.ok(!preview.text.endsWith("superc"), "cut mid-word");
  assert.ok(preview.text.split(" ").pop().length > 0);
});

// A "show more" that expands nothing tells the reader words are being withheld
// when none are.
test("a note that fits exactly is not called truncated", () => {
  const note = "x".repeat(NOTE_PREVIEW_CHARS);
  assert.deepEqual(notePreview(note), { text: note, truncated: false });
});

// No usable boundary: a hard cut is right, and one word must never stand in for
// the whole note.
test("a single unbroken word is cut rather than shown whole", () => {
  const preview = notePreview("x".repeat(NOTE_PREVIEW_CHARS * 2));
  assert.equal(preview.text.length, NOTE_PREVIEW_CHARS);
  assert.equal(preview.truncated, true);
});

test("an empty or missing note does not throw", () => {
  assert.deepEqual(notePreview(""), { text: "", truncated: false });
  assert.deepEqual(notePreview(undefined), { text: "", truncated: false });
});

// The same line on both sides would be ambiguous exactly where ambiguity is
// expensive: whose journal this came out of.
test("the header says whose journal it was", () => {
  assert.equal(noteHeader(true), "From your journal");
  assert.equal(noteHeader(false), "From their journal");
});

// ── Three copies, one set of numbers ────────────────────────────────────────
//
// The fold is implemented per frontend because the renderers differ, and the
// numbers are what must not. They can drift silently — someone shortens the
// preview on Android to fit a phone and desktop keeps six lines — and the
// symptom is not a crash but the same note reading differently on two screens
// of the same conversation. `together_parity.test.mjs` is the precedent, and
// so is its honest failure mode: a constant that cannot be found fails loudly
// rather than passing by finding nothing.

const KOTLIN = readFileSync(
  new URL(
    "../../android/app/src/main/java/mullu/comrade/ui/JournalNote.kt",
    import.meta.url,
  ),
  "utf8",
);

const DART = readFileSync(
  new URL("../../app/lib/src/util/journal_note.dart", import.meta.url),
  "utf8",
);

function kotlinConst(name) {
  const m = KOTLIN.match(new RegExp(`const val ${name}\\s*=\\s*([0-9_]+)`));
  assert.ok(m, `could not find "const val ${name}" in JournalNote.kt — renamed?`);
  return Number(m[1].replace(/_/g, ""));
}

function dartConst(name) {
  const m = DART.match(new RegExp(`const int ${name}\\s*=\\s*([0-9_]+)`));
  assert.ok(m, `could not find "const int ${name}" in journal_note.dart — renamed?`);
  return Number(m[1].replace(/_/g, ""));
}

test("all three frontends fold a note at the same place", () => {
  assert.equal(kotlinConst("NOTE_PREVIEW_LINES"), NOTE_PREVIEW_LINES);
  assert.equal(dartConst("kNotePreviewLines"), NOTE_PREVIEW_LINES);
  assert.equal(kotlinConst("NOTE_PREVIEW_CHARS"), NOTE_PREVIEW_CHARS);
  assert.equal(dartConst("kNotePreviewChars"), NOTE_PREVIEW_CHARS);
});
