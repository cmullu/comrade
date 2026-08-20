import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

// The executable half of docs/DESIGN_SYSTEM.md §3: the token contract, the
// undefined-variable regression (finding #2 in the design-system task), the
// "every skin re-values the same set" rule, and the 4.5:1 pairing rule §3.1
// states outright. All of it works by parsing `styles.css` as text — there is
// no DOM here, so nothing short of that would catch a token silently missing
// from one skin or a fallback quietly covering a gap.

const here = dirname(fileURLToPath(import.meta.url));
const rawCss = readFileSync(join(here, "styles.css"), "utf8");

// Comments are prose, not declarations — strip them before anything else so
// a backtick-quoted `--name` in an explanatory comment can never be mistaken
// for a definition or a usage.
const css = rawCss.replace(/\/\*[\s\S]*?\*\//g, "");

/**
 * Splits `text` into its top-level rules only: `{ ... }` bodies at brace
 * depth 0. An `@media`/`@supports` block comes back as a single entry whose
 * `body` is everything inside it, unparsed — callers that care what is
 * nested inside one run this again on that body. Depth-based, so nested
 * braces (a `@keyframes` block's `from { }`/`to { }` steps, or a nested
 * `@media` block) never confuse it the way a non-greedy regex would.
 */
function topLevelBlocks(text) {
  const blocks = [];
  let depth = 0;
  let selectorStart = 0;
  let bodyStart = -1;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (ch === "{") {
      if (depth === 0) bodyStart = i + 1;
      depth++;
    } else if (ch === "}") {
      depth--;
      if (depth === 0) {
        const selector = text.slice(selectorStart, bodyStart - 1).trim();
        const body = text.slice(bodyStart, i);
        blocks.push({ selector, body });
        selectorStart = i + 1;
      }
    }
  }
  return blocks;
}

const topBlocks = topLevelBlocks(css);

// The dark ramp's `:root` — the only one at top level (the light ramp's
// lives inside `@media (prefers-color-scheme: light)`, one level down).
const rootDark = topBlocks.find((b) => b.selector === ":root");

// Theme skin blocks that actually declare custom properties — Travel has a
// *second* `body.theme-travel { ... }` further down for the dashed-edge
// box-shadow/outline, which declares none and must not be mistaken for one
// of the three skins under test.
const declaresCustomProps = (body) => /--[\w-]+\s*:/.test(body);
const themeBlocksDark = topBlocks.filter(
  (b) => /^body\.theme-[\w-]+$/.test(b.selector) && declaresCustomProps(b.body),
);

const lightMedia = topBlocks.find((b) =>
  b.selector.includes("prefers-color-scheme: light") && b.selector.startsWith("@media"),
);

const lightBlocks = topLevelBlocks(lightMedia?.body ?? "");
const rootLight = lightBlocks.find((b) => b.selector === ":root");
const themeBlocksLight = lightBlocks.filter(
  (b) => /^body\.theme-[\w-]+$/.test(b.selector) && declaresCustomProps(b.body),
);

/**
 * These three used to be bare module-scope assertions. That made any one of
 * them abort the whole file at import, so a missing light ramp hid the eleven
 * unrelated checks below it — the failure everyone would actually want to read
 * was the one they could not see. They are a test like everything else now.
 */
test("styles.css declares both ramps", () => {
  assert.ok(rootDark, "styles.css must have a top-level :root block");
  assert.ok(
    lightMedia,
    "styles.css must have an @media (prefers-color-scheme: light) block",
  );
  assert.ok(rootLight, "the light-ramp media query must re-value :root");
});

test("the light ramp actually declares the three skins, not just :root", () => {
  assert.deepEqual(
    themeBlocksLight.map((b) => b.selector).sort(),
    ["body.theme-couple-sakha", "body.theme-couple-sakhi", "body.theme-travel"],
    "each skin must re-value inside the light media query too, not only the dark ramp",
  );
});

// ── Every var(--x) resolves to a real definition ──────────────────────────

/** Every `--name: value;` declared anywhere in the file, any selector. */
function allDefinedNames(text) {
  return new Set([...text.matchAll(/--([\w-]+)\s*:/g)].map((m) => m[1]));
}

/** Every `--name` referenced inside a `var(...)` call, anywhere. */
function allUsedNames(text) {
  return new Set([...text.matchAll(/var\(\s*--([\w-]+)/g)].map((m) => m[1]));
}

const definedNames = allDefinedNames(css);
const usedNames = allUsedNames(css);

test("every var(--x) used in the file is defined somewhere in the file", () => {
  const undefinedUses = [...usedNames].filter((n) => !definedNames.has(n));
  assert.deepEqual(
    undefinedUses,
    [],
    `these tokens are used with var() but never defined anywhere: ${undefinedUses.join(", ")}. ` +
      "A var() fallback (e.g. var(--x, #232323)) still renders when this happens — silently, " +
      "and never in a colour that responds to a theme swap.",
  );
});

test("no var() fallback papers over an undefined token", () => {
  // Every var(...) call that supplies a fallback: `var(--name, fallback)`.
  const withFallback = [...css.matchAll(/var\(\s*--([\w-]+)\s*,/g)].map((m) => m[1]);
  const paperedOver = [...new Set(withFallback)].filter((n) => !definedNames.has(n));
  assert.deepEqual(
    paperedOver,
    [],
    `these tokens are only reachable through their var() fallback, never their own definition: ${paperedOver.join(", ")}`,
  );
});

// ── Regression coverage for the four specific undefined tokens the design-
// system task named (styles.css review: --accent-soft, --surface-2, --fg,
// --hover) — named explicitly so a future revert of just one definition
// fails on the token it broke, not a generic diff. ──────────────────────
test("the four previously-undefined tokens are defined for real", () => {
  for (const name of ["accent-soft", "surface-2", "fg", "hover"]) {
    assert.ok(definedNames.has(name), `--${name} must be defined, not only used with a var() fallback`);
  }
});

// ── Every skin re-values the same token set ────────────────────────────────

function propNames(body) {
  return new Set([...body.matchAll(/--([\w-]+)\s*:/g)].map((m) => m[1]));
}

function assertSameTokenSet(blocks, label) {
  assert.ok(blocks.length >= 2, `expected at least two ${label} skin blocks to compare, found ${blocks.length}`);
  const [first, ...rest] = blocks;
  const firstSet = propNames(first.body);
  for (const block of rest) {
    const set = propNames(block.body);
    const missing = [...firstSet].filter((n) => !set.has(n));
    const extra = [...set].filter((n) => !firstSet.has(n));
    assert.deepEqual(
      missing,
      [],
      `${block.selector} (${label}) is missing tokens that ${first.selector} defines: ${missing.join(", ")}`,
    );
    assert.deepEqual(
      extra,
      [],
      `${block.selector} (${label}) defines tokens ${first.selector} does not: ${extra.join(", ")}`,
    );
  }
}

test("every body.theme-* block re-values the same token set as the others (dark ramp)", () => {
  assertSameTokenSet(themeBlocksDark, "dark");
});

test("every body.theme-* block re-values the same token set as the others (light ramp)", () => {
  assertSameTokenSet(themeBlocksLight, "light");
});

// ── Contrast: every X-foreground clears 4.5:1 on its X, in both ramps ─────

/** WCAG relative luminance of a `#rrggbb` colour. */
function relativeLuminance(hex) {
  const n = hex.replace("#", "");
  const r = parseInt(n.slice(0, 2), 16) / 255;
  const g = parseInt(n.slice(2, 4), 16) / 255;
  const b = parseInt(n.slice(4, 6), 16) / 255;
  const linear = (c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
  return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b);
}

/** WCAG contrast ratio between two `#rrggbb` colours. */
function contrastRatio(hexA, hexB) {
  const lA = relativeLuminance(hexA);
  const lB = relativeLuminance(hexB);
  const [lighter, darker] = lA >= lB ? [lA, lB] : [lB, lA];
  return (lighter + 0.05) / (darker + 0.05);
}

const HEX_RE = /^#[0-9a-fA-F]{6}$/;

/** The literal `#rrggbb` value of `--name` declared directly in `body`. */
function tokenValue(body, name) {
  const m = body.match(new RegExp(`--${name}\\s*:\\s*([^;]+);`));
  return m ? m[1].trim() : undefined;
}

const AA_NORMAL_TEXT = 4.5;

// docs/DESIGN_SYSTEM.md §3.1's paired semantics.
const PAIRS = [
  ["background", "foreground"],
  ["card", "card-foreground"],
  ["popover", "popover-foreground"],
  ["muted", "muted-foreground"],
  ["primary", "primary-foreground"],
  ["secondary", "secondary-foreground"],
  ["destructive", "destructive-foreground"],
  ["success", "success-foreground"],
  ["warning", "warning-foreground"],
];

function checkPairsInBlock(block, ramp) {
  for (const [fill, fg] of PAIRS) {
    const fillValue = tokenValue(block.body, fill);
    const fgValue = tokenValue(block.body, fg);
    if (fillValue === undefined || fgValue === undefined) {
      // Skins only re-value primary/primary-foreground (see the token-set
      // test above) — that is fine here, the base :root block below still
      // covers the other seven pairs for this ramp.
      continue;
    }
    assert.match(fillValue, HEX_RE, `${block.selector} --${fill} (${ramp}) must be a literal #rrggbb to contrast-check`);
    assert.match(fgValue, HEX_RE, `${block.selector} --${fg} (${ramp}) must be a literal #rrggbb to contrast-check`);
    const ratio = contrastRatio(fillValue, fgValue);
    assert.ok(
      ratio >= AA_NORMAL_TEXT,
      `${block.selector} (${ramp}): --${fg} (${fgValue}) on --${fill} (${fillValue}) is ${ratio.toFixed(2)}:1, below the ${AA_NORMAL_TEXT}:1 AA floor §3.1 requires`,
    );
  }
}

test("dark ramp: every X-foreground clears 4.5:1 on its X (base :root)", () => {
  checkPairsInBlock(rootDark, "dark");
});

test("light ramp: every X-foreground clears 4.5:1 on its X (base :root)", () => {
  checkPairsInBlock(rootLight, "light");
});

// primary/primary-foreground is the pair every skin re-values — this is the
// regression test for the trap app/lib/src/theme/comrade_theme.dart already
// found once: a dark-ramp accent, reused directly as light-mode text,
// measuring 2.1:1. Each skin, each ramp, checked independently.
test("dark ramp: every skin's primary/primary-foreground clears 4.5:1", () => {
  for (const block of themeBlocksDark) checkPairsInBlock(block, `dark ${block.selector}`);
});

test("light ramp: every skin's primary/primary-foreground clears 4.5:1", () => {
  for (const block of themeBlocksLight) checkPairsInBlock(block, `light ${block.selector}`);
});

// ── `--ring` must not equal `--border` (§3.1) ──────────────────────────────

test("--ring is never the same value as --border, in either ramp", () => {
  for (const [label, block] of [
    ["dark", rootDark],
    ["light", rootLight],
  ]) {
    const ring = tokenValue(block.body, "ring");
    const border = tokenValue(block.body, "border");
    assert.ok(ring, `${label} :root must define --ring`);
    assert.ok(border, `${label} :root must define --border`);
    assert.notEqual(ring, border, `${label} ramp: --ring must not equal --border`);
  }
});

// ── Glass is never applied to dense/body content (§2) ──────────────────────

test("glass tint/blur never lands on chat-log, bubbles, list rows, or the lightbox", () => {
  const forbiddenSelectors = [
    ".chat-log",
    ".bubble",
    ".contact",
    ".thread-row",
    ".lightbox",
  ];
  for (const { selector, body } of topBlocks) {
    if (!/backdrop-filter|--glass-tint/.test(body)) continue;
    for (const forbidden of forbiddenSelectors) {
      const parts = selector.split(",").map((s) => s.trim());
      for (const part of parts) {
        assert.ok(
          !(part === forbidden || part.startsWith(`${forbidden}.`) || part.startsWith(`${forbidden} `)),
          `${selector} carries glass (backdrop-filter/--glass-tint) but §2 forbids it on ${forbidden}`,
        );
      }
    }
  }
});
