import { test } from "node:test";
import assert from "node:assert/strict";
import {
  MAX_RESULTS,
  filterCommands,
  fuzzyScore,
  groupCommands,
  keepSelection,
  moveSelection,
} from "./command_palette.mjs";

const cmd = (id, label, over = {}) => ({ id, label, group: "Go to", ...over });

const CATALOGUE = [
  cmd("together", "Together"),
  cmd("vault", "Vault"),
  cmd("focus", "Focus"),
  cmd("tasks", "Tasks"),
  cmd("travel", "Off-Grid / Travel", { group: "Modes" }),
  cmd("stretch", "Stretch break", { group: "Do", keywords: ["neck", "shoulders"] }),
];

test("no query keeps the caller's order", () => {
  const out = filterCommands(CATALOGUE, "");
  assert.deepEqual(
    out.map((c) => c.id),
    CATALOGUE.map((c) => c.id),
  );
  assert.deepEqual(filterCommands(CATALOGUE, "   ").map((c) => c.id), CATALOGUE.map((c) => c.id));
});

test("letters need only appear in order, not together", () => {
  assert.deepEqual(
    filterCommands(CATALOGUE, "tgh").map((c) => c.id),
    ["together"],
  );
});

test("a match at the start beats a match in the middle", () => {
  // "Stretch break" starts with `st`; "Tasks" does not contain it at all.
  const ids = filterCommands(CATALOGUE, "st").map((c) => c.id);
  assert.equal(ids[0], "stretch");
});

test("a word-start match beats a mid-word one", () => {
  const items = [cmd("a", "Vault settings"), cmd("b", "Set a session")];
  assert.equal(filterCommands(items, "se")[0].id, "b");
});

test("keywords find a command whose label does not say the word", () => {
  const ids = filterCommands(CATALOGUE, "neck").map((c) => c.id);
  assert.deepEqual(ids, ["stretch"]);
});

test("a label match always outranks a keyword match", () => {
  const items = [
    cmd("keyword-only", "Something else", { keywords: ["focus"] }),
    cmd("labelled", "Focus session"),
  ];
  assert.equal(filterCommands(items, "focus")[0].id, "labelled");
});

test("nothing matching is an empty list, not the whole catalogue", () => {
  assert.deepEqual(filterCommands(CATALOGUE, "zzzz"), []);
});

test("the result list is capped so the palette never becomes a scroll", () => {
  const many = Array.from({ length: 40 }, (_, i) => cmd(`c${i}`, `Command ${i}`));
  assert.equal(filterCommands(many, "").length, MAX_RESULTS);
  assert.equal(filterCommands(many, "command").length, MAX_RESULTS);
});

test("equally good answers keep the order they came in", () => {
  const items = [cmd("first", "Copy key"), cmd("second", "Copy link")];
  assert.deepEqual(
    filterCommands(items, "copy").map((c) => c.id),
    ["first", "second"],
  );
});

test("scoring reports no-match as null rather than zero", () => {
  assert.equal(fuzzyScore("Together", "zz"), null);
  assert.equal(fuzzyScore("", "a"), null);
  // An empty query matches everything equally — that is what "show me
  // everything" means, and it must not be confused with "no match".
  assert.equal(fuzzyScore("Together", ""), 0);
});

test("groups come out in first-seen order, keeping the ranking inside them", () => {
  const groups = groupCommands(filterCommands(CATALOGUE, ""));
  assert.deepEqual(
    groups.map((g) => g.group),
    ["Go to", "Modes", "Do"],
  );
  assert.deepEqual(groups[0].commands.map((c) => c.id), ["together", "vault", "focus", "tasks"]);
});

test("the cursor wraps at both ends", () => {
  assert.equal(moveSelection(4, 0, 1), 1);
  assert.equal(moveSelection(4, 3, 1), 0);
  assert.equal(moveSelection(4, 0, -1), 3);
  // A delta bigger than the list still lands somewhere real.
  assert.equal(moveSelection(4, 0, -9), 3);
  assert.equal(moveSelection(4, 0, 9), 1);
});

test("an empty list has no cursor at all", () => {
  assert.equal(moveSelection(0, 0, 1), -1);
  assert.equal(keepSelection([], "vault"), -1);
});

test("re-filtering follows the highlighted entry rather than its row number", () => {
  // What was highlighted stays highlighted even though it moved up two rows.
  assert.equal(keepSelection(CATALOGUE, "focus"), 2);
  assert.equal(keepSelection([CATALOGUE[2], CATALOGUE[0]], "focus"), 0);
  // …and when it is filtered out, the cursor goes to the top rather than
  // pointing at whatever inherited its index.
  assert.equal(keepSelection(CATALOGUE, "gone"), 0);
});
