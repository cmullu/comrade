/**
 * What the toast stack should do with the next message — Sonner's behaviour
 * (which is what shadcn/ui ships for toasts), as a pure decision.
 *
 * `safeInvoke` toasts every backend error. One relay flapping is a dozen
 * identical "Backend error" cards stacked up the right-hand edge, each with
 * its own timer, burying whatever was already there. Sonner's answers are a
 * visible cap and a repeat counter, and both are decisions rather than DOM, so
 * they live here.
 */

/** How many toasts may be on screen at once. Sonner's default. */
export const MAX_VISIBLE = 3;

/** How long a toast lives. An error stays longer: it may need acting on. */
export function ttlFor(type) {
  return type === "error" ? 6500 : 3500;
}

/**
 * What makes two toasts "the same toast". Type included, so the same sentence
 * arriving as a warning and then as an error is two events, not a repeat.
 */
export function toastKey({ type, title, message } = {}) {
  return [type || "info", title || "", message || ""].join("::");
}

/**
 * Decide what the stack becomes when `incoming` arrives.
 *
 * `live` is the toasts currently on screen, oldest first, each
 * `{ key, type, title, message, repeats }`. The answer says what the DOM
 * should do rather than doing it:
 *
 *   { live, repeated: key | null, evicted: [key] }
 *
 * `repeated` means "no new card — bump this one's counter and restart its
 * timer". `evicted` are cards to remove because the stack is full: the oldest
 * non-error first, so "Copied" makes way before the reason something failed
 * does. When everything older *is* an error one of those goes anyway — the
 * stack has to move, and dropping the toast that just arrived would leave the
 * action a person took with no answer at all.
 */
export function admitToast(live, incoming) {
  const stack = Array.isArray(live) ? live.slice() : [];
  const key = toastKey(incoming);

  const existing = stack.find((t) => t.key === key);
  if (existing) {
    existing.repeats = (existing.repeats || 1) + 1;
    return { live: stack, repeated: key, evicted: [] };
  }

  stack.push({
    key,
    type: incoming?.type || "info",
    title: incoming?.title || "",
    message: incoming?.message || "",
    repeats: 1,
  });

  const evicted = [];
  while (stack.length > MAX_VISIBLE) {
    const index = evictionIndex(stack);
    evicted.push(stack[index].key);
    stack.splice(index, 1);
  }
  return { live: stack, repeated: null, evicted };
}

/** Remove a toast that expired or was dismissed. */
export function dropToast(live, key) {
  return (Array.isArray(live) ? live : []).filter((t) => t.key !== key);
}

/**
 * Which toast makes way: the oldest non-error, or the oldest of anything when
 * the only non-error present is the one that just arrived.
 */
function evictionIndex(stack) {
  const oldestNonError = stack.findIndex((t) => t.type !== "error");
  // Never evict the toast that just arrived to make room for itself.
  if (oldestNonError !== -1 && oldestNonError !== stack.length - 1) return oldestNonError;
  return 0;
}

/**
 * The counter shown on a repeated toast, or "" while it has only happened
 * once — a "1" badge on every toast is noise that teaches nothing.
 */
export function repeatLabel(repeats) {
  return repeats > 1 ? `×${repeats}` : "";
}
