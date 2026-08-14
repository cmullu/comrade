import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const mainJs = readFileSync(join(here, "main.js"), "utf8");
const indexHtml = readFileSync(join(here, "index.html"), "utf8");

/**
 * Ids `main.js` looks up but the page does not declare, because the UI builds
 * them at runtime — each one's lookup is an "is it already open" check, not a
 * binding to markup. Anything else missing is a dead selector.
 */
const CREATED_AT_RUNTIME = {
  "media-lightbox": "built by the media viewer on first open (main.js `id: \"media-lightbox\"`)",
};

const declaredIds = new Set([...indexHtml.matchAll(/id="([^"]+)"/g)].map((m) => m[1]));
const createdIds = new Set([...mainJs.matchAll(/id:\s*"([A-Za-z0-9_-]+)"/g)].map((m) => m[1]));
const lookedUpIds = [...mainJs.matchAll(/\$\("#([A-Za-z0-9_-]+)"\)/g)].map((m) => m[1]);

/**
 * A `$("#id")` for an id nothing ever creates is not a visible failure: `$`
 * returns null, the guard below it returns early, and the feature is simply
 * dead — the button still renders and still looks clickable. That is how the
 * reply-quote jump shipped doing nothing on desktop, so it is worth a test even
 * though `main.js` itself cannot be unit tested.
 */
test("every id main.js looks up is one the page or the UI creates", () => {
  const missing = [...new Set(lookedUpIds)].filter(
    (id) => !declaredIds.has(id) && !(id in CREATED_AT_RUNTIME),
  );
  assert.deepEqual(
    missing,
    [],
    `main.js queries ids that no markup declares and nothing creates: ${missing.join(", ")}`,
  );
});

// Keeps the allowance above honest: an entry that stops being created at
// runtime has to be re-justified rather than silently covering a dead lookup.
test("the runtime-created allowance still matches the code", () => {
  for (const [id, why] of Object.entries(CREATED_AT_RUNTIME)) {
    assert.ok(createdIds.has(id), `${id} is allowed as runtime-created (${why}) but nothing creates it`);
  }
});

// The thread the reply-quote jump scrolls is the one the conversation renders
// into. These two drifting apart is exactly the bug this file was added for.
test("the quote jump and the thread render address the same log", () => {
  assert.ok(declaredIds.has("chat-log"), "the page must declare the thread log");
  assert.match(mainJs, /function goToQuoted\(targetId\) \{[\s\S]*?\$\("#chat-log"\)/);
});
