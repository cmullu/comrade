/**
 * The ⌘K command palette's decisions — shadcn/ui's Command (cmdk), minus the
 * DOM.
 *
 * Everything this app can do was reachable only by knowing where it lives: a
 * tab in the rail, a button under "Modes", a `/` command that has to be typed
 * into the right conversation first. The palette is one place to ask. What is
 * *in* it is assembled by `main.js` from live state; what is here is the part
 * worth testing — which entries a query keeps, in what order, and where the
 * cursor goes.
 */

/** The palette shows at most this many rows, so it never becomes a scroll. */
export const MAX_RESULTS = 12;

/**
 * How well `query` matches `text`, or `null` for no match at all.
 *
 * A subsequence match (cmdk's rule: "tgh" finds "Together") with three
 * bonuses, in the order a person would rank them — the string starts with what
 * you typed, a *word* starts with it, or the letters at least ran together.
 * Without those bonuses "st" ranks "Vault settings" above "Stretch", which is
 * the wrong answer to a two-letter query.
 */
export function fuzzyScore(text, query) {
  const haystack = String(text || "").toLowerCase();
  const needle = String(query || "").toLowerCase().trim();
  if (!needle) return 0;
  if (!haystack) return null;

  let score = 0;
  let at = 0;
  let previousIndex = -1;

  for (const ch of needle) {
    const found = haystack.indexOf(ch, at);
    if (found === -1) return null;

    if (found === 0) score += 15;
    else if (found === previousIndex + 1) score += 6;
    else if (/[\s\-_/]/.test(haystack[found - 1])) score += 10;
    else score += 1;

    previousIndex = found;
    at = found + 1;
  }

  // A short label that matched is a better answer than a long one that
  // matched the same way — "Tasks" over "Task list settings" for "task".
  return score - Math.min(haystack.length, 40) * 0.1;
}

/**
 * Rank `commands` against `query`.
 *
 * An empty query keeps the caller's order, which is deliberate: the palette
 * opens on the things worth putting first, not on an alphabetical list.
 * Otherwise entries are scored on their label, then — at a penalty, so a label
 * match always wins — on their keywords, so `/play` can be found by typing
 * "music" without "music" appearing on the row.
 */
export function filterCommands(commands, query) {
  const list = Array.isArray(commands) ? commands : [];
  const q = String(query || "").trim();
  if (!q) return list.slice(0, MAX_RESULTS);

  const scored = [];
  list.forEach((command, index) => {
    const label = fuzzyScore(command.label, q);
    const keywords = fuzzyScore((command.keywords || []).join(" "), q);
    const best =
      label !== null ? label : keywords !== null ? keywords - 20 : null;
    if (best !== null) scored.push({ command, score: best, index });
  });

  // Ties keep the caller's order: sorting is not allowed to shuffle two
  // equally good answers between keystrokes.
  scored.sort((a, b) => b.score - a.score || a.index - b.index);
  return scored.slice(0, MAX_RESULTS).map((s) => s.command);
}

/**
 * Split a result list into its groups, in first-seen order, so the palette can
 * print a heading per group without the caller sorting by group and losing the
 * ranking.
 */
export function groupCommands(commands) {
  const groups = [];
  const byName = new Map();
  for (const command of commands || []) {
    const name = command.group || "";
    let group = byName.get(name);
    if (!group) {
      group = { group: name, commands: [] };
      byName.set(name, group);
      groups.push(group);
    }
    group.commands.push(command);
  }
  return groups;
}

/**
 * Where ↑/↓ lands. Wraps at both ends — a palette you can arrow off the bottom
 * of is a palette where the last item is hard to reach.
 */
export function moveSelection(count, index, delta) {
  if (!count || count < 1) return -1;
  const from = Number.isInteger(index) && index >= 0 && index < count ? index : 0;
  // A wrap has to survive a delta larger than the list, so modulo twice.
  return (((from + delta) % count) + count) % count;
}

/**
 * The selection to hold after the list is re-filtered.
 *
 * Keyed on the entry's id rather than its position, because re-ranking moves
 * rows under the cursor — typing another letter must not run a different
 * command than the one that was highlighted a keystroke ago. When the
 * highlighted entry is filtered out entirely there is nothing to keep, so the
 * cursor goes to the top.
 */
export function keepSelection(commands, selectedId) {
  const list = Array.isArray(commands) ? commands : [];
  if (!list.length) return -1;
  const found = list.findIndex((c) => c.id === selectedId);
  return found === -1 ? 0 : found;
}
