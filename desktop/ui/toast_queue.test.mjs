import { test } from "node:test";
import assert from "node:assert/strict";
import {
  MAX_VISIBLE,
  admitToast,
  dropToast,
  repeatLabel,
  toastKey,
  ttlFor,
} from "./toast_queue.mjs";

const toast = (type, message, title = "") => ({ type, message, title });

/** Feed a list of toasts through the stack, returning what ends up on screen. */
function run(...incoming) {
  let live = [];
  const evicted = [];
  for (const t of incoming) {
    const out = admitToast(live, t);
    live = out.live;
    evicted.push(...out.evicted);
  }
  return { live, evicted };
}

test("an identical message repeats the card it already has", () => {
  const first = admitToast([], toast("error", "relay unreachable", "Backend error"));
  const second = admitToast(first.live, toast("error", "relay unreachable", "Backend error"));

  assert.equal(second.live.length, 1, "one relay flapping is one card");
  assert.equal(second.live[0].repeats, 2);
  assert.equal(second.repeated, toastKey(toast("error", "relay unreachable", "Backend error")));
  assert.deepEqual(second.evicted, []);
});

test("the same sentence at a different severity is a different toast", () => {
  const { live } = run(toast("warn", "relay slow"), toast("error", "relay slow"));
  assert.equal(live.length, 2);
});

test("the stack is capped, oldest out first", () => {
  const { live, evicted } = run(
    toast("info", "one"),
    toast("info", "two"),
    toast("info", "three"),
    toast("info", "four"),
  );
  assert.equal(live.length, MAX_VISIBLE);
  assert.deepEqual(
    live.map((t) => t.message),
    ["two", "three", "four"],
  );
  assert.deepEqual(evicted, [toastKey(toast("info", "one"))]);
});

test("a routine toast makes way before the reason something failed does", () => {
  const { live } = run(
    toast("error", "vault locked"),
    toast("info", "copied"),
    toast("info", "sent"),
    toast("info", "saved"),
  );
  assert.deepEqual(
    live.map((t) => t.message),
    ["vault locked", "sent", "saved"],
    "the error outlived the older 'copied'",
  );
});

test("a full stack of errors still moves rather than dropping the new toast", () => {
  const { live } = run(
    toast("error", "one"),
    toast("error", "two"),
    toast("error", "three"),
    toast("info", "copied"),
  );
  assert.equal(live.length, MAX_VISIBLE);
  assert.equal(
    live[live.length - 1].message,
    "copied",
    "the toast a person's own click produced is never the one silently dropped",
  );
});

test("dismissing removes exactly the one dismissed", () => {
  const { live } = run(toast("info", "one"), toast("info", "two"));
  const after = dropToast(live, toastKey(toast("info", "one")));
  assert.deepEqual(
    after.map((t) => t.message),
    ["two"],
  );
  // Dropping something that already left is not an error.
  assert.equal(dropToast(after, "nothing-like-this").length, 1);
});

test("errors linger longer than confirmations", () => {
  assert.ok(ttlFor("error") > ttlFor("success"));
  assert.equal(ttlFor("info"), ttlFor("success"));
});

test("the repeat counter only appears once there is a repeat", () => {
  assert.equal(repeatLabel(1), "");
  assert.equal(repeatLabel(undefined), "");
  assert.equal(repeatLabel(2), "×2");
});

test("a missing type is an info toast, not an undefined one", () => {
  const { live } = admitToast([], { message: "hello" });
  assert.equal(live[0].type, "info");
  assert.equal(toastKey({}), "info::::");
});
