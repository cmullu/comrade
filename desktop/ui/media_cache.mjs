/**
 * A bounded cache of decrypted-attachment object URLs.
 *
 * ## Why this exists
 *
 * `URL.createObjectURL(blob)` pins its `Blob` — here, a *decrypted* attachment —
 * in the page's memory until the URL is revoked. `main.js` never revoked one, so
 * every attachment viewed in a session stayed in the webview's heap for the life
 * of the process, and locking (or re-unlocking as a different identity) left the
 * plaintext behind. Android had bounded the equivalent at 24 bitmaps and purged
 * it on backgrounding; the Flutter app keeps a bounded in-memory LRU. This is
 * the desktop half of the same rule.
 *
 * ## Why it is its own module
 *
 * The eviction rule is the part worth testing, and it cannot be tested through
 * `main.js`: there is no `URL.createObjectURL` in node, and the DOM glue around
 * it is untestable by design. So the *policy* lives here with `create`/`revoke`
 * injected — exactly the split `call_decisions.mjs` already uses — and node
 * covers "the oldest goes first", "a re-view protects an entry", "every URL is
 * revoked exactly once", and "clear leaves nothing behind".
 */

/**
 * @param {object} options
 * @param {(blob: unknown) => string} options.create mint a URL for a blob
 * @param {(url: string) => void} options.revoke release one
 * @param {number} [options.capacity] how many decrypted blobs may be held
 * @param {(key: string) => void} [options.onEvict] called after a key's URL is
 *   revoked, so the caller can forget its own reference to the dead URL
 */
export function createBlobUrlCache({ create, revoke, capacity = 8, onEvict }) {
  if (typeof create !== "function" || typeof revoke !== "function") {
    throw new TypeError("createBlobUrlCache needs create() and revoke()");
  }
  if (!Number.isInteger(capacity) || capacity < 1) {
    throw new RangeError("capacity must be a positive integer");
  }

  // Insertion-ordered Map as an LRU: re-inserting a key moves it to the end, so
  // the first key is always the least recently used.
  const urls = new Map();

  /** Revoke one entry (if present) and notify the owner. */
  function release(key) {
    if (!urls.has(key)) return false;
    revoke(urls.get(key));
    urls.delete(key);
    if (onEvict) onEvict(key);
    return true;
  }

  return {
    /**
     * The URL for `key`, minting one from `blob` on a miss. A hit does **not**
     * re-mint: two URLs for one blob would mean two things to revoke and one of
     * them forgotten.
     */
    get(key, blob) {
      if (urls.has(key)) {
        const existing = urls.get(key);
        urls.delete(key);
        urls.set(key, existing); // most-recently used
        return existing;
      }
      const url = create(blob);
      urls.set(key, url);
      while (urls.size > capacity) {
        const oldest = urls.keys().next().value;
        release(oldest);
      }
      return url;
    },

    /** Release one entry early (a failed render, a deleted message). */
    release,

    /** Drop every decrypted attachment this cache is holding. */
    clear() {
      for (const key of Array.from(urls.keys())) release(key);
    },

    /** Present for tests and diagnostics; not part of the rendering path. */
    get size() {
      return urls.size;
    },

    has(key) {
      return urls.has(key);
    },
  };
}
