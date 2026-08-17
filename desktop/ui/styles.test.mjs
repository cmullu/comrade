import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const css = readFileSync(join(here, "styles.css"), "utf8");
const html = readFileSync(join(here, "index.html"), "utf8");
const mainJs = readFileSync(join(here, "main.js"), "utf8");

/**
 * Every class name the UI actually applies, from both places it can come
 * from: the page's own `class="…"` attributes and `main.js`'s `el()` calls.
 * Half the app is built at runtime, so reading only the HTML would miss it.
 */
const appliedClasses = new Set(
  [
    ...html.matchAll(/class="([^"]*)"/g),
    ...mainJs.matchAll(/class:\s*[`"]([^`"]*)[`"]/g),
    // Variants chosen inside a template literal — `class: \`btn ${cond ?
    // "btn-destructive" : "btn-outline"}\`` — are invisible to the pattern
    // above, and that is exactly where a `btn-danger` that no rule defined
    // survived: the button rendered as a transparent rectangle and nothing
    // failed.
    ...mainJs.matchAll(/"(btn-[a-z]+)"/g),
  ]
    .flatMap((m) => m[1].split(/\s+/))
    .filter(Boolean),
);

/** Comments hold prose about tokens; strip them before reading declarations. */
const code = css.replace(/\/\*[\s\S]*?\*\//g, "");

/**
 * The one token nothing declares in the stylesheet: `main.js` writes it per
 * element while the profile header collapses. Anything else missing is a
 * typo that renders as "the property is simply absent" — a border that does
 * not draw, a colour that falls back to inherited black.
 */
const SET_BY_SCRIPT = new Set(["--profile-avatar-size"]);

const declared = new Set([...code.matchAll(/(--[a-z0-9-]+)\s*:/g)].map((m) => m[1]));
const used = new Set([...code.matchAll(/var\((--[a-z0-9-]+)/g)].map((m) => m[1]));

test("the stylesheet's braces balance", () => {
  const opens = (code.match(/\{/g) || []).length;
  const closes = (code.match(/\}/g) || []).length;
  assert.equal(opens, closes, "an unbalanced brace silently swallows every rule after it");
});

test("every token used is a token defined", () => {
  const missing = [...used].filter((t) => !declared.has(t) && !SET_BY_SCRIPT.has(t));
  assert.deepEqual(
    missing,
    [],
    `styles.css reads custom properties nothing declares: ${missing.join(", ")}`,
  );
});

/**
 * `var(--x, fallback)` is how four undefined tokens (`--fg`, `--hover`,
 * `--surface-2`, `--accent-soft`) survived in this file unnoticed: the
 * fallback rendered something plausible in *one* theme and the token was
 * simply never real. With the test above in place a fallback can only hide a
 * mistake, so there are none.
 */
test("no token is used with a fallback value", () => {
  const withFallback = [...code.matchAll(/var\(\s*--[a-z0-9-]+\s*,/g)].map((m) => m[0]);
  assert.deepEqual(withFallback, []);
});

/**
 * The shadcn/ui token contract. Components below are written against these
 * names only — a screen that reaches for a literal colour is a screen that
 * will be wrong in one of the two appearances.
 */
const SHADCN_TOKENS = [
  "--background",
  "--foreground",
  "--card",
  "--card-foreground",
  "--popover",
  "--popover-foreground",
  "--primary",
  "--primary-foreground",
  "--secondary",
  "--secondary-foreground",
  "--muted",
  "--muted-foreground",
  "--accent",
  "--accent-foreground",
  "--destructive",
  "--border",
  "--input",
  "--ring",
  "--radius",
];

test(":root carries the whole shadcn token contract", () => {
  const root = code.slice(code.indexOf(":root"), code.indexOf("body.theme-light"));
  for (const token of SHADCN_TOKENS) {
    assert.match(root, new RegExp(`${token}\\s*:`), `:root is missing ${token}`);
  }
});

test("the light appearance restates every colour token, not just some", () => {
  const start = code.indexOf("body.theme-light");
  const light = code.slice(start, code.indexOf("}", start));
  // --ring and --radius are derived, so they are deliberately not restated;
  // every token that is a literal colour must be, or that one lands dark-mode
  // navy on a white card.
  const colours = SHADCN_TOKENS.filter((t) => t !== "--ring" && t !== "--radius");
  for (const token of colours) {
    assert.match(light, new RegExp(`${token}\\s*:`), `body.theme-light is missing ${token}`);
  }
});

test("the radius scale is derived from one value", () => {
  for (const step of ["--radius-sm", "--radius-md", "--radius-lg", "--radius-xl"]) {
    assert.match(
      code,
      new RegExp(`${step}:\\s*(calc\\(var\\(--radius\\)|var\\(--radius\\))`),
      `${step} should be derived from --radius so one change re-rounds the app`,
    );
  }
});

/**
 * The focus ring is declared once, for everything interactive. It is the rule
 * most easily lost to a later refactor and the one whose absence is invisible
 * to anyone using a mouse.
 */
test("there is a single global focus-visible ring", () => {
  assert.match(code, /:where\([^)]*button[^)]*\):focus-visible\s*\{[^}]*--ring/);
});

test("the classes the palette markup uses are all styled", () => {
  for (const cls of [
    "palette-overlay",
    "palette-input",
    "palette-list",
    "palette-item",
    "palette-empty",
    "palette-foot",
    "palette-trigger",
    "kbd",
  ]) {
    assert.ok(appliedClasses.has(cls), `the UI applies .${cls}`);
    assert.match(code, new RegExp(`\\.${cls}[\\s,:{]`), `.${cls} has no rule`);
  }
});

/**
 * Button variants are only useful if the markup and the stylesheet agree on
 * their names — a `btn-outline` with no rule is an unstyled transparent
 * rectangle, which reads as a rendering bug rather than a missing class.
 */
test("every button variant the markup asks for exists", () => {
  const variants = [...appliedClasses].filter((c) => /^btn-[a-z]+$/.test(c));
  assert.ok(variants.length > 0, "the UI should use at least one button variant");
  for (const variant of variants) {
    assert.match(code, new RegExp(`\\.${variant}[\\s,:{]`), `.${variant} has no rule`);
  }
});
