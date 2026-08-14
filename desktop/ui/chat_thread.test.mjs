import { test } from "node:test";
import assert from "node:assert/strict";

import { QUOTE_HIGHLIGHT_MS, isNearBottom, quoteScrollTargetId } from "./chat_thread.mjs";

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

// Auto-scroll. The pixel cases mirror `isNearBottomByOffset` in
// `app/test/chat_thread_test.dart`, with a scrollable's travel spelled the way
// the DOM spells it: scrollHeight - clientHeight.

test("at the very bottom counts as near", () => {
  assert.equal(
    isNearBottom({ scrollTop: 1000, scrollHeight: 1400, clientHeight: 400 }),
    true,
  );
});

test("within the slack window counts as near", () => {
  assert.equal(
    isNearBottom({ scrollTop: 900, scrollHeight: 1400, clientHeight: 400 }),
    true,
  );
});

// The case that decides the feature: reading history, a tick or a rename
// rebuilds the thread, and the reader must stay where they were.
test("scrolled up beyond the slack window does not", () => {
  assert.equal(
    isNearBottom({ scrollTop: 400, scrollHeight: 1400, clientHeight: 400 }),
    false,
  );
  assert.equal(
    isNearBottom({ scrollTop: 0, scrollHeight: 10000, clientHeight: 400 }),
    false,
  );
});

test("a log with nothing to scroll counts as bottom", () => {
  assert.equal(
    isNearBottom({ scrollTop: 0, scrollHeight: 0, clientHeight: 0 }),
    true,
  );
  // Shorter than its box, and the not-yet-laid-out read the DOM gives before
  // the first paint — both are "at the bottom", never "scrolled up".
  assert.equal(
    isNearBottom({ scrollTop: 0, scrollHeight: 120, clientHeight: 400 }),
    true,
  );
  assert.equal(isNearBottom({}), true);
});
