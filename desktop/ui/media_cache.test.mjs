import { test } from "node:test";
import assert from "node:assert/strict";

import { createBlobUrlCache } from "./media_cache.mjs";

/** A cache over fake URL minting, recording every mint and every revoke. */
function harness({ capacity = 2 } = {}) {
  const minted = [];
  const revoked = [];
  const evicted = [];
  let n = 0;
  const cache = createBlobUrlCache({
    create: (blob) => {
      const url = `blob:${blob}#${++n}`;
      minted.push(url);
      return url;
    },
    revoke: (url) => revoked.push(url),
    capacity,
    onEvict: (key) => evicted.push(key),
  });
  return { cache, minted, revoked, evicted };
}

test("a hit reuses the URL instead of minting a second one", () => {
  const { cache, minted } = harness();
  const first = cache.get("a", "photo");
  const second = cache.get("a", "photo");
  assert.equal(first, second);
  assert.equal(minted.length, 1, "two URLs for one blob would leak one of them");
});

test("the least recently used entry is the one evicted", () => {
  const { cache, revoked, evicted } = harness({ capacity: 2 });
  const a = cache.get("a", "one");
  cache.get("b", "two");
  // Touching 'a' makes 'b' the oldest.
  assert.equal(cache.get("a", "one"), a);

  cache.get("c", "three");

  assert.equal(cache.size, 2);
  assert.ok(cache.has("a") && cache.has("c"));
  assert.ok(!cache.has("b"));
  assert.deepEqual(evicted, ["b"], "the owner is told which key died");
  assert.equal(revoked.length, 1, "the evicted URL is revoked, exactly once");
});

test("clear revokes everything it is holding", () => {
  const { cache, minted, revoked } = harness({ capacity: 4 });
  cache.get("a", "one");
  cache.get("b", "two");
  cache.get("c", "three");

  cache.clear();

  assert.equal(cache.size, 0);
  assert.deepEqual(revoked.sort(), minted.sort(), "no plaintext left pinned");
  // Idempotent: a second clear (a re-lock, a double unlock) must not
  // double-revoke a URL another blob may since have been given.
  cache.clear();
  assert.equal(revoked.length, 3);
});

test("releasing an unknown key is a no-op, not a double revoke", () => {
  const { cache, revoked } = harness();
  cache.get("a", "one");

  assert.equal(cache.release("nope"), false);
  assert.equal(cache.release("a"), true);
  assert.equal(cache.release("a"), false);
  assert.equal(revoked.length, 1);
});

test("a re-view after eviction mints a fresh URL rather than a dead one", () => {
  const { cache, minted } = harness({ capacity: 1 });
  const first = cache.get("a", "one");
  cache.get("b", "two"); // evicts 'a'
  const again = cache.get("a", "one");

  assert.notEqual(again, first, "the old URL was revoked and cannot be reused");
  assert.equal(minted.length, 3);
});

test("a nonsense configuration fails loudly", () => {
  assert.throws(() => createBlobUrlCache({ create: () => "u" }), TypeError);
  assert.throws(
    () => createBlobUrlCache({ create: () => "u", revoke: () => {}, capacity: 0 }),
    RangeError,
  );
});
