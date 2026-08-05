import { test } from "node:test";
import assert from "node:assert/strict";
import {
  ACTION_KEYS,
  ASIDE,
  BLOCKED,
  CHOOSE,
  DESKTOP_CAN_PLAY,
  DESKTOP_SCREENS,
  HELP,
  INCOMPLETE,
  OFFER,
  OPEN,
  SEND,
  TARA_HERE,
  TARA_PRIVATE,
  TARA_SHARED,
  TASK,
  candidateLabel,
  completionFor,
  labelFor,
  offerAffordance,
  pickerRows,
  planFor,
  splitAuthor,
  taraDraft,
  withChoices,
} from "./chat_commands.mjs";

// The bridge shapes, as serde delivers them.
const plain = { kind: "plain" };
const task = (text, assignees = []) => ({ kind: "task", text, assignees });
const offer = (action, targets) => ({ kind: "offer_to", action, targets });
const mention = (handle) => ({ handle, start: 0, end: handle.length + 1 });
const resolved = (handle, npub) => ({ handle, npub, candidates: [] });
const ambiguous = (handle) => ({
  handle,
  npub: null,
  candidates: [
    { npub: "npub1a", alias: "ana" },
    { npub: "npub1b", alias: "ana" },
  ],
});

test("ordinary text is sent as typed", () => {
  assert.deepEqual(planFor(plain), { action: SEND });
});

test("a command this build has never heard of is reported, never swallowed", () => {
  // A message silently eaten as an unknown command is worse than one that
  // arrives confusing — the sender thinks they sent it.
  const p = planFor({ kind: "unknown", name: "frobnicate" });
  assert.equal(p.action, INCOMPLETE);
  assert.match(p.message, /no \/frobnicate/);
});

test("a grammar variant newer than this file falls back to sending", () => {
  // Release skew between the Rust grammar and this window, not a bug. Sending
  // the text is the safe outcome.
  assert.deepEqual(planFor({ kind: "something_new_entirely" }), { action: SEND });
  assert.deepEqual(planFor(null), { action: SEND });
  assert.deepEqual(planFor({}), { action: SEND });
});

test("/pay still goes out as text", () => {
  // Recognised so it is not called a typo; the chip and the peer's own client
  // do the rest, exactly as before this feature existed.
  assert.deepEqual(planFor({ kind: "pay" }), { action: SEND });
});

test("/help opens the list", () => {
  assert.deepEqual(planFor({ kind: "help" }), { action: HELP });
});

// ── Tara asides ──────────────────────────────────────────────────────────────

test("an aside routes to tara, never to the peer", () => {
  const p = planFor({ kind: "ask_tara", text: "i keep putting this off" });
  assert.equal(p.action, ASIDE);
  assert.equal(p.text, "i keep putting this off");
});

test("an addressed but empty aside prompts instead of sending an empty turn", () => {
  const p = planFor({ kind: "ask_tara", text: "  " });
  assert.equal(p.action, INCOMPLETE);
  assert.match(p.message, /only you will see it/);
});

test("the composer knows the audience from the moment tara is addressed", () => {
  // Eager on purpose, and now in two directions: `/tara` never leaves the device
  // and `@tara` reaches the other person. Either label being wrong is how
  // somebody publishes a private thought, or asks a question the peer never sees.
  assert.equal(taraDraft("@tara "), TARA_SHARED);
  assert.equal(taraDraft("@tara"), TARA_SHARED);
  assert.equal(taraDraft("@Tara what about"), TARA_SHARED);
  assert.equal(taraDraft("  @tara hello"), TARA_SHARED);
  assert.equal(taraDraft("/tara hello"), TARA_PRIVATE);
  assert.equal(taraDraft("/tara"), TARA_PRIVATE);
});

test("a person whose handle starts with tara is not tara", () => {
  assert.equal(taraDraft("@taranjeet are you around"), null);
  assert.equal(taraDraft("hello @tara"), null);
  assert.equal(taraDraft(""), null);
  assert.equal(taraDraft(undefined), null);
});

// ── Tara in the room (`@tara …`) ──────────────────────────────────────────────

test("shared and private tara are different plans", () => {
  // Same words, two audiences: one reaches `tara_in_chat` (two messages into the
  // thread), the other `tara_aside` (nothing sent).
  const shared = planFor({ kind: "tara_here", text: "what film should we watch" });
  assert.equal(shared.action, TARA_HERE);
  assert.equal(shared.text, "what film should we watch");
  assert.equal(planFor({ kind: "ask_tara", text: "what film should we watch" }).action, ASIDE);
});

test("an empty shared address says the answer will be seen by both", () => {
  const p = planFor({ kind: "tara_here", text: " " });
  assert.equal(p.action, INCOMPLETE);
  assert.match(p.message, /you'll both see/);
});

// ── Tasks ────────────────────────────────────────────────────────────────────

test("a task with nobody named is a note to self", () => {
  const p = planFor(task("water the plants"));
  assert.equal(p.action, TASK);
  assert.equal(p.text, "water the plants");
  assert.equal(p.peer, null);
});

test("a task naming a known contact carries their npub", () => {
  const p = planFor(task("get some work done", [mention("ana")]), {
    mentions: [resolved("ana", "npub1ana")],
  });
  assert.equal(p.action, TASK);
  assert.equal(p.peer, "npub1ana");
});

test("an empty task asks what needs doing", () => {
  const p = planFor(task("   "));
  assert.equal(p.action, INCOMPLETE);
  assert.match(p.message, /What needs doing/);
});

test("a handle that is nobody's is reported rather than guessed at", () => {
  const p = planFor(task("do it", [mention("bina")]), {
    mentions: [{ handle: "bina", npub: null, candidates: [] }],
  });
  assert.equal(p.action, INCOMPLETE);
  assert.match(p.message, /@bina isn't in your contacts/);
});

test("two contacts answering to one handle becomes a question, not a coin flip", () => {
  // A handle is a self-declared alias; anyone may publish any name. Picking one
  // is how a private message reaches the wrong person.
  const p = planFor(task("do it", [mention("ana")]), {
    mentions: [
      { handle: "ana", npub: null, candidates: [{ npub: "npub1a" }, { npub: "npub1b" }] },
    ],
  });
  assert.equal(p.action, CHOOSE);
  assert.equal(p.handle, "ana");
  assert.equal(p.candidates.length, 2);
});

test("choosing one of two lets the same draft through", () => {
  // The fix for the dead end: the old plan said "pick which one" and gave
  // nothing to pick, so the command could never be completed at all.
  const mentions = withChoices([ambiguous("ana")], { ana: "npub1b" });
  const p = planFor(task("do it", [mention("ana")]), { mentions });
  assert.equal(p.action, TASK);
  assert.equal(p.peer, "npub1b");
});

test("a choice that names nobody on the list is ignored", () => {
  // A pin left over from an earlier draft must not silently retarget a message —
  // that is the failure the ambiguity exists to prevent, arriving by another door.
  const stale = withChoices([ambiguous("ana")], { ana: "npub1someone-else" });
  assert.equal(stale[0].npub, null);
  assert.equal(planFor(task("do it", [mention("ana")]), { mentions: stale }).action, CHOOSE);
});

test("a chooser row carries the key because both may share the name", () => {
  // The usual reason a handle is ambiguous is that both people chose the same
  // name, so a row showing only the name offers two identical choices.
  const a = candidateLabel("ana", "npub1aaaaaaaaaaaaaaaaaaaa");
  const b = candidateLabel("ana", "npub1bbbbbbbbbbbbbbbbbbbb");
  assert.notEqual(a, b);
  assert.match(a, /ana/);
});

test("an ambiguous handle in an offer asks too, rather than picking one", () => {
  const p = planFor(offer("breathe", [mention("ana")]), { mentions: [ambiguous("ana")] });
  assert.equal(p.action, CHOOSE);
});

// ── Offers ───────────────────────────────────────────────────────────────────

test("an offer to a known comrade carries the action and the npub", () => {
  const p = planFor(offer("breathe", [mention("ana")]), {
    mentions: [resolved("ana", "npub1ana")],
  });
  assert.equal(p.action, OFFER);
  assert.equal(p.appAction, "breathe");
  assert.deepEqual(p.peers, ["npub1ana"]);
});

test("an offer with nobody named asks who it is for", () => {
  const p = planFor(offer("breathe", []));
  assert.equal(p.action, INCOMPLETE);
  assert.match(p.message, /Name the comrade/);
});

test("an offer to an unresolvable handle does not silently go nowhere", () => {
  const p = planFor(offer("breathe", [mention("ghost")]), {
    mentions: [{ handle: "ghost", npub: null, candidates: [] }],
  });
  assert.equal(p.action, INCOMPLETE);
});

// ── Screens this window does and does not have ───────────────────────────────

test("a screen desktop has opens; one it lacks says so instead of doing nothing", () => {
  // `docs/FRONTEND_STRATEGY.md`'s divergence table: journal, Tara and the
  // breathing screen are Android-only today. A dead button is the worse answer.
  assert.deepEqual(planFor({ kind: "open", action: "focus" }), {
    action: OPEN,
    appAction: "focus",
  });
  const blocked = planFor({ kind: "open", action: "journal" });
  assert.equal(blocked.action, BLOCKED);
  assert.match(blocked.message, /not here yet/);
  assert.match(blocked.message, /journal/i);
});

test("every desktop screen in the set actually opens", () => {
  for (const key of DESKTOP_SCREENS) {
    assert.equal(planFor({ kind: "open", action: key }).action, OPEN, key);
  }
});

test("play is refused honestly while there is no player in this window", () => {
  // `docs/TOGETHER.md` §9: the commands and the decision module are here, the
  // <video> element and the file picker are not. When that lands, flip
  // DESKTOP_CAN_PLAY and this test's other branch takes over.
  const p = planFor({ kind: "play", query: "Kun Faya Kun", service: "spotify" });
  if (DESKTOP_CAN_PLAY) {
    assert.equal(p.action, "play");
    assert.equal(p.query, "Kun Faya Kun");
  } else {
    assert.equal(p.action, BLOCKED);
    assert.match(p.message, /no player here yet/);
  }
});

// ── The / picker ─────────────────────────────────────────────────────────────

const CATALOG = [
  { name: "play", aliases: ["listen", "watch"], argument: "<song>", takes_mention: false },
  { name: "task", aliases: ["todo"], argument: "<what>", takes_mention: true },
  { name: "breathe", aliases: ["breath"], argument: "", takes_mention: false },
];

test("the picker matches aliases as well as names", () => {
  // An alias that works on submit but is invisible while typing reads as not
  // existing.
  const rows = pickerRows("/lis", CATALOG);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].name, "play");
});

test("the picker is closed rather than empty for ordinary text", () => {
  assert.equal(pickerRows("hello", CATALOG), null);
  assert.equal(pickerRows("20/80 split", CATALOG), null);
  assert.equal(pickerRows("/nomatch", CATALOG), null);
  assert.equal(pickerRows(undefined, CATALOG), null);
});

test("the picker closes once the command has been chosen", () => {
  // A space means what follows is an argument, not a name.
  assert.equal(pickerRows("/task ship it", CATALOG), null);
});

test("a bare slash offers everything", () => {
  assert.equal(pickerRows("/", CATALOG).length, 3);
});

test("completion leaves the caret where the next word goes, or submits", () => {
  assert.equal(completionFor(CATALOG[0]), "/play ");
  assert.equal(completionFor(CATALOG[1]), "/task ");
  // Nothing follows /breathe, so no trailing space — Enter submits instead of
  // sending "/breathe ".
  assert.equal(completionFor(CATALOG[2]), "/breathe");
  assert.equal(completionFor(null), "");
});

// ── Received offers ──────────────────────────────────────────────────────────

test("every action key the bridge can send has a real label", () => {
  // The drift this catches: `labelFor` falls back to the raw key, so a
  // mismatched spelling produced "breathe is on the phone app, not here yet"
  // and nothing failed. Assert the exact strings, not a loose /breath/i.
  for (const key of ACTION_KEYS) {
    assert.notEqual(labelFor(key), key, `${key} fell through to the raw key`);
  }
  assert.equal(labelFor("breathe"), "Taking a deep breath");
  assert.equal(labelFor("read"), "The reader");
});

test("every action key is either a desktop screen or honestly refused", () => {
  for (const key of ACTION_KEYS) {
    const plan = planFor({ kind: "open", action: key });
    if (DESKTOP_SCREENS.has(key)) {
      assert.equal(plan.action, OPEN, key);
    } else {
      assert.equal(plan.action, BLOCKED, key);
      // The sentence must name the thing, not echo a wire key at the user.
      assert.ok(!plan.message.startsWith(`${key} is`), `${key} leaked its wire key`);
    }
  }
});

test("a received offer is shown even when this window lacks the screen", () => {
  // The sentence is the point; hiding a comrade's message because our build has
  // no screen would be the wrong trade.
  const here = offerAffordance("focus");
  assert.equal(here.actionable, true);
  const elsewhere = offerAffordance("breathe");
  assert.equal(elsewhere.actionable, false);
  assert.equal(elsewhere.label, "Taking a deep breath");
  assert.equal(offerAffordance(null), null);
});

test("a shared Tara answer is split the same way core splits it", () => {
  // The mirror of `comrade_ui::split_author`, needed because a live-arriving DM
  // reaches this frontend as the raw wire body rather than through `MessageDto`.
  assert.deepEqual(splitAuthor("Tara: one thing at a time"), {
    author: "tara",
    content: "one thing at a time",
  });
  assert.deepEqual(splitAuthor("what do you think"), {
    author: "human",
    content: "what do you think",
  });
  // A prefix, not a substring: "Tara" is an ordinary word in an ordinary
  // sentence, and attributing this to her would put words in her mouth.
  assert.equal(splitAuthor("Tara said something like that").author, "human");
  assert.equal(splitAuthor("ask Tara: what now").author, "human");
  // Anyone can type the marker. This asserts the limitation rather than hiding
  // it: a match is the sender's claim, never proof — AUDIT.md Q17.
  assert.equal(splitAuthor("Tara: I made this up").author, "tara");
  assert.deepEqual(splitAuthor(null), { author: "human", content: "" });
});
