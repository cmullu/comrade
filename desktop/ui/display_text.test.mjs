import { test } from "node:test";
import assert from "node:assert/strict";

import { UNSAFE_TEXT, sanitizeDisplayText } from "./display_text.mjs";
import { safeDisplayName, boundedCaption } from "./handoff_transfer.mjs";

// The bidi vector below is the same one handoff_transfer.test.mjs uses. Both
// suites now exercise the same character class from the same module, so a change
// that breaks one has to break both — which is the point of the extraction.
//
// The hostile characters are built with fromCharCode rather than pasted, so they
// are visible in a diff and cannot be silently "fixed" by an editor that strips
// them.

/** U+202E RIGHT-TO-LEFT OVERRIDE — the one that makes gnp.exe read as .png. */
const RLO = String.fromCharCode(0x202e);
/** U+200E LEFT-TO-RIGHT MARK — invisible, and inside the same class. */
const LRM = String.fromCharCode(0x200e);
/** U+0085 NEXT LINE — a C1 control, which is why the class covers 007f–009f. */
const NEL = String.fromCharCode(0x85);

test("a bidi override cannot make a name read as something it is not", () => {
  assert.equal(
    sanitizeDisplayText(`holiday${RLO}gnp.exe`, 0),
    "holiday gnp.exe",
    "U+202E left in place renders this as holiday.png in every conforming renderer",
  );
});

test("an invisible bidi mark is removed even though it draws as nothing", () => {
  assert.equal(
    sanitizeDisplayText(`Bob${LRM}`, 0),
    "Bob",
    "a name that compares unequal to Bob while drawing as Bob is a name that impersonates",
  );
});

test("C0 and C1 controls that would forge a second line are removed", () => {
  assert.equal(
    sanitizeDisplayText("Bob\nVerified by Comrade", 0),
    "Bob Verified by Comrade",
    "a newline in a peer-chosen name turns one row into two lines of app chrome",
  );
  assert.equal(
    sanitizeDisplayText(`Bob${NEL}Verified`, 0),
    "Bob Verified",
    "C1 controls are line breaks too in some renderers, which is why the class spans them",
  );
});

test("a stripped control becomes a space, so two words never silently merge", () => {
  // The failure this pins: \n is itself inside the C0 range, so deleting outright
  // turns a two-line name into one word that nobody is called.
  assert.equal(
    sanitizeDisplayText("Bob\nSmith", 0),
    "Bob Smith",
    "deleting rather than spacing would invent the name BobSmith out of nothing",
  );
});

test("runs of whitespace collapse so a bio cannot push a page off screen", () => {
  assert.equal(
    sanitizeDisplayText("hi\n\n\n\n\n\n\n\n\n\nthere", 0),
    "hi there",
    "ten newlines is not a bio, it is a way to relocate the rest of the profile",
  );
  assert.equal(sanitizeDisplayText("  padded  ", 0), "padded");
  assert.equal(sanitizeDisplayText("a\t\t\tb", 0), "a b");
});

test("text longer than the bound is truncated with an ellipsis inside the bound", () => {
  const out = sanitizeDisplayText("abcdefghij", 5);
  assert.equal(out, "abcd…");
  assert.equal(out.length, 5, "the ellipsis has to fit inside the budget, not exceed it");
});

test("text exactly at the bound is left alone rather than truncated", () => {
  assert.equal(
    sanitizeDisplayText("abcde", 5),
    "abcde",
    "an off-by-one here costs a character of every name that happens to fit",
  );
});

test("a bound of zero or nonsense means no bound rather than an empty string", () => {
  assert.equal(sanitizeDisplayText("abcdefghij", 0), "abcdefghij");
  assert.equal(sanitizeDisplayText("abcdefghij", NaN), "abcdefghij");
  assert.equal(sanitizeDisplayText("abcdefghij", undefined), "abcdefghij");
});

test("nothing usable becomes the empty string, never invented wording", () => {
  for (const input of [null, undefined, "", "   ", ` ${RLO}`, "\n\n", LRM]) {
    assert.equal(
      sanitizeDisplayText(input, 32),
      "",
      "callers choose between an empty row and no row; this must not choose for them",
    );
  }
});

test("sanitizing twice gives the same answer, so a shared /g regex cannot leak state", () => {
  // A /g regex carries lastIndex. String.replace resets it, but the shape both
  // callers rely on is worth pinning rather than assuming.
  assert.equal(UNSAFE_TEXT.global, true, "replace-all semantics are what both callers need");
  assert.equal(sanitizeDisplayText(`a${RLO}b`, 0), "a b");
  assert.equal(sanitizeDisplayText(`a${RLO}b`, 0), "a b", "a second call must behave identically");
});

test("handoff_transfer still sanitises filenames through the shared class", () => {
  assert.equal(
    safeDisplayName(`holiday${RLO}gnp.exe`),
    "holidaygnp.exe",
    "the extraction must not have changed the shipped transfer path's behaviour",
  );
  assert.equal(safeDisplayName("../../etc/passwd"), "passwd");
  assert.equal(safeDisplayName(""), "Unnamed file");
});

test("handoff_transfer still strips bidi from captions through the shared class", () => {
  // `boundedCaption` replaces with a space and `safeDisplayName` with nothing.
  // That divergence predates this module; both are pinned, and this asserts the
  // extraction preserved each one rather than unifying them behind anyone's back.
  assert.equal(boundedCaption(`a${RLO}b`), "a b");
  assert.equal(safeDisplayName(`a${RLO}b`), "ab");
});
