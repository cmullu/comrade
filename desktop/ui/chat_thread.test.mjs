import { test } from "node:test";
import assert from "node:assert/strict";

import { QUOTE_HIGHLIGHT_MS, quoteScrollTargetId } from "./chat_thread.mjs";

// Mirrored in `app/test/chat_thread_test.dart` and
// `android/.../ui/ChatThreadTest.kt` — same cases, same answers.

const thread = [
  { id: "a", content: "Boarded" },
  { id: "b", content: "Safe travels." },
  { id: "c", content: "Landed." },
];

test("a quote tap finds the message it points at", () => {
  assert.equal(quoteScrollTargetId(thread, "a"), "a");
  assert.equal(quoteScrollTargetId(thread, "c"), "c");
});

// The case that decides the feature's behaviour: a reply to something older than
// the loaded thread quotes fine but has nowhere to scroll to. The caller must
// leave the thread where it is rather than land somewhere arbitrary.
test("an original outside the loaded thread has no destination", () => {
  assert.equal(quoteScrollTargetId(thread, "older"), null);
  assert.equal(quoteScrollTargetId([], "a"), null);
  assert.equal(quoteScrollTargetId(undefined, "a"), null);
});

test("a message that is not a reply has nothing to go to", () => {
  assert.equal(quoteScrollTargetId(thread, null), null);
  assert.equal(quoteScrollTargetId(thread, undefined), null);
  assert.equal(quoteScrollTargetId(thread, ""), null);
});

// An unsent message has no event id yet, so it can be neither quoted nor jumped
// to. Matching it would mean matching on undefined and scrolling to the wrong
// bubble.
test("a message with no id is never a destination", () => {
  assert.equal(quoteScrollTargetId([{ content: "pending" }], undefined), null);
  assert.equal(quoteScrollTargetId([{ id: "", content: "pending" }], ""), null);
});

test("the flash length matches the other frontends", () => {
  assert.equal(QUOTE_HIGHLIGHT_MS, 1400);
});
