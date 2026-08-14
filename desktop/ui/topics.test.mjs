import { test } from "node:test";
import assert from "node:assert/strict";
import {
  ASSIGN_FILE,
  ASSIGN_NEEDS_TARGET,
  ASSIGN_OPEN_PICKER,
  UNFILED,
  planAssign,
  threadPreview,
  threadsFor,
  unreadThreadCount,
  visibleTopics,
} from "./topics.mjs";

// The bridge shapes, as serde delivers them.
const topic = (slug, over = {}) => ({
  slug,
  name: slug,
  peer: "npub1peer",
  created_by: "npub1me",
  mine: true,
  created_at: 100,
  closed: false,
  thread_count: 1,
  message_count: 3,
  last_activity_at: 100,
  ...over,
});

const thread = (root_id, over = {}) => ({
  root_id,
  peer: "npub1peer",
  topic_slug: null,
  preview: "the deposit still hasn't come back",
  root_is_media: false,
  root_missing: false,
  started_at: 100,
  reply_count: 2,
  last_at: 200,
  unread: false,
  ...over,
});

test("topics list liveliest first, archived last", () => {
  const rows = visibleTopics(
    [
      topic("old", { last_activity_at: 10 }),
      topic("archived", { last_activity_at: 999, closed: true }),
      topic("live", { last_activity_at: 500 }),
    ],
    { includeClosed: true },
  );
  assert.deepEqual(
    rows.map((t) => t.slug),
    ["live", "old", "archived"],
    "an archive that outranks live topics is not an archive",
  );
});

test("archived topics are out of the picker by default", () => {
  const rows = visibleTopics([topic("live"), topic("gone", { closed: true })]);
  assert.deepEqual(
    rows.map((t) => t.slug),
    ["live"],
  );
});

test("threads with no replies are hidden unless asked for", () => {
  const rows = threadsFor([thread("a"), thread("b", { reply_count: 0 })]);
  assert.deepEqual(
    rows.map((t) => t.root_id),
    ["a"],
    "listing every message would make the sheet a worse copy of the chat",
  );
  const all = threadsFor([thread("a"), thread("b", { reply_count: 0 })], {
    includeSingletons: true,
  });
  assert.equal(all.length, 2, "a thread has to be startable from the sheet");
});

test("a topic filter is exact, and unfiled is asked for by name", () => {
  const rows = [
    thread("a", { topic_slug: "deposit" }),
    thread("b", { topic_slug: "repairs" }),
    thread("c"),
  ];
  assert.deepEqual(
    threadsFor(rows, { topicSlug: "deposit" }).map((t) => t.root_id),
    ["a"],
  );
  assert.deepEqual(
    threadsFor(rows, { topicSlug: UNFILED }).map((t) => t.root_id),
    ["c"],
  );
  assert.equal(threadsFor(rows, { topicSlug: null }).length, 3, "null is everything");
});

test("threads sort by last activity, newest first", () => {
  const rows = threadsFor([
    thread("a", { last_at: 100 }),
    thread("c", { last_at: 300 }),
    thread("b", { last_at: 200 }),
  ]);
  assert.deepEqual(
    rows.map((t) => t.root_id),
    ["c", "b", "a"],
  );
});

test("the preview names what core deliberately left unworded", () => {
  // Core hands up an empty preview plus two booleans; the word is ours so it
  // can be translated. These assert the branch, not the wording.
  const labels = { attachment: "ATT", missing: "MISS", empty: "NONE" };
  assert.equal(threadPreview(thread("a"), labels), "the deposit still hasn't come back");
  assert.equal(
    threadPreview(thread("a", { preview: "", root_is_media: true }), labels),
    "ATT",
  );
  assert.equal(
    threadPreview(thread("a", { preview: "sunset.jpg", root_is_media: true }), labels),
    "📎 sunset.jpg",
  );
  assert.equal(threadPreview(thread("a", { root_missing: true }), labels), "MISS");
  assert.equal(threadPreview(thread("a", { preview: "  " }), labels), "NONE");
});

test("a missing root wins over an empty preview", () => {
  // Both are true at once for a filing that arrived before its thread, and
  // "(no text)" would read as a bug where "not on this device" reads as a fact.
  const labels = { attachment: "ATT", missing: "MISS", empty: "NONE" };
  assert.equal(
    threadPreview(thread("a", { preview: "", root_missing: true }), labels),
    "MISS",
  );
});

test("unread counts threads, not messages", () => {
  const rows = [
    thread("a", { topic_slug: "deposit", unread: true }),
    thread("b", { topic_slug: "deposit" }),
    thread("c", { topic_slug: "repairs", unread: true }),
  ];
  assert.equal(unreadThreadCount(rows, "deposit"), 1);
  assert.equal(unreadThreadCount(rows, null), 2);
});

test("a bare /assign opens the picker rather than refusing", () => {
  assert.deepEqual(planAssign([], "msg1"), { action: ASSIGN_OPEN_PICKER });
  assert.deepEqual(planAssign(null, null), { action: ASSIGN_OPEN_PICKER });
});

test("/assign with a topic and nothing selected says what is missing", () => {
  const plan = planAssign([{ slug: "deposit" }], null);
  assert.equal(plan.action, ASSIGN_NEEDS_TARGET);
  assert.match(plan.message, /#deposit/, "the sentence has to name the topic they typed");
});

test("/assign with a topic and a selection files that thread", () => {
  assert.deepEqual(planAssign([{ slug: "deposit" }, { slug: "second" }], "msg1"), {
    action: ASSIGN_FILE,
    slug: "deposit",
    messageId: "msg1",
  });
});
