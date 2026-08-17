import { test } from "node:test";
import assert from "node:assert/strict";
import {
  APPEARANCES,
  DEFAULT_APPEARANCE,
  appearanceGlyph,
  appearanceLabel,
  bodyClassName,
  bodyClasses,
  nextAppearance,
  normalizeAppearance,
  resolveAppearance,
} from "./theme.mjs";

test("an unknown stored preference reads as dark, the appearance this app had", () => {
  // Not "system": adopting shadcn's default would turn every existing install
  // white on the launch after an update.
  assert.equal(normalizeAppearance("sepia"), "dark");
  assert.equal(normalizeAppearance(undefined), "dark");
  assert.equal(normalizeAppearance(null), "dark");
  assert.equal(normalizeAppearance(""), "dark");
  assert.equal(DEFAULT_APPEARANCE, "dark");
  for (const p of APPEARANCES) assert.equal(normalizeAppearance(p), p);
});

test("the toggle cycles dark → light → follow the system and back", () => {
  assert.equal(nextAppearance("dark"), "light");
  assert.equal(nextAppearance("light"), "system");
  assert.equal(nextAppearance("system"), "dark");
  // Three presses return you to where you started, which is what makes the
  // control safe to poke at.
  assert.equal(nextAppearance(nextAppearance(nextAppearance("light"))), "light");
});

test("an explicit choice ignores the system, `system` obeys it", () => {
  assert.equal(resolveAppearance("light", true), "light");
  assert.equal(resolveAppearance("dark", false), "dark");
  assert.equal(resolveAppearance("system", true), "dark");
  assert.equal(resolveAppearance("system", false), "light");
});

test("the label and glyph describe the choice, not the resolved appearance", () => {
  assert.equal(appearanceLabel("system"), "Match system");
  assert.equal(appearanceLabel("light"), "Light");
  assert.equal(appearanceLabel("dark"), "Dark");
  assert.equal(appearanceGlyph("light"), "☀");
  assert.equal(appearanceGlyph("dark"), "☾");
});

test("the body carries the modality skin and the appearance together", () => {
  // Both at once is the whole point: main.js assigns className wholesale, so
  // a modality change that returned only "theme-base" would drop light mode.
  assert.deepEqual(bodyClasses("theme-base", "light", false), ["theme-base", "theme-light"]);
  assert.deepEqual(bodyClasses("theme-travel", "light", false), ["theme-travel", "theme-light"]);
  assert.deepEqual(bodyClasses("theme-base", "dark", false), ["theme-base"]);
  assert.equal(bodyClassName("theme-travel", "system", true), "theme-travel");
  assert.equal(bodyClassName("theme-travel", "system", false), "theme-travel theme-light");
});

test("the couple sandbox keeps its own skin in either appearance", () => {
  for (const skin of ["theme-couple-sakha", "theme-couple-sakhi"]) {
    assert.deepEqual(bodyClasses(skin, "light", false), [skin]);
    assert.deepEqual(bodyClasses(skin, "system", false), [skin]);
  }
});

test("a missing modality falls back to the base skin rather than no skin", () => {
  assert.deepEqual(bodyClasses("", "dark", false), ["theme-base"]);
  assert.deepEqual(bodyClasses(null, "dark", false), ["theme-base"]);
});
