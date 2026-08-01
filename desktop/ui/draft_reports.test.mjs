import { test } from "node:test";
import assert from "node:assert/strict";

import { ABANDON, NOTE, editReports, switchReports } from "./draft_reports.mjs";

test("text in the box is a draft; an empty box is one given up on", () => {
  assert.deepEqual(editReports("npub1ana", "are you"), [
    { command: NOTE, peer: "npub1ana" },
  ]);
  assert.deepEqual(editReports("npub1ana", ""), [
    { command: ABANDON, peer: "npub1ana" },
  ]);
});

test("whitespace is not a half-written message", () => {
  // The send path would refuse it, so it must not look like something that was
  // nearly sent.
  assert.deepEqual(editReports("npub1ana", "   \n\t"), [
    { command: ABANDON, peer: "npub1ana" },
  ]);
});

test("with no conversation open there is nobody to report about", () => {
  assert.deepEqual(editReports(null, "typing into the void"), []);
  assert.deepEqual(editReports(undefined, ""), []);
  // A composer can also be edited before anything is selected.
  assert.deepEqual(editReports("", "x"), []);
});

test("switching away abandons the draft whatever the box still holds", () => {
  // The bug this exists to prevent: asking "does the box have text?" about the
  // peer being *left* would report that we are still writing to them.
  assert.deepEqual(switchReports("npub1ana", "npub1bo", "half a thought"), [
    { command: ABANDON, peer: "npub1ana" },
    { command: NOTE, peer: "npub1bo" },
  ]);
});

test("text left in the box becomes the newly opened peer's draft", () => {
  // This composer keeps its contents across a switch, so the text on screen is
  // now addressed to whoever is on screen — reporting it against the previous
  // peer would be attributing it to the wrong person.
  const reports = switchReports("npub1ana", "npub1bo", "still here");
  assert.deepEqual(reports.at(-1), { command: NOTE, peer: "npub1bo" });
});

test("opening the first conversation reports only about that one", () => {
  assert.deepEqual(switchReports(null, "npub1ana", ""), [
    { command: ABANDON, peer: "npub1ana" },
  ]);
  assert.deepEqual(switchReports(null, "npub1ana", "hi"), [
    { command: NOTE, peer: "npub1ana" },
  ]);
});

test("re-selecting the conversation already open abandons nothing", () => {
  // Clicking the same row must not read as walking away from it.
  assert.deepEqual(switchReports("npub1ana", "npub1ana", "mid-sentence"), [
    { command: NOTE, peer: "npub1ana" },
  ]);
});
