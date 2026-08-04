import { test } from "node:test";
import assert from "node:assert/strict";
import {
  DECLINE,
  DONE,
  STATES,
  WITHDRAW,
  actionsFor,
  emptyCopy,
  groupTasks,
  stateLabel,
  subtitleFor,
  wireState,
} from "./task_list.mjs";

// TaskDto as the bridge delivers it.
const task = (over = {}) => ({
  id: "t1",
  text: "get some work done",
  assigner: "npub1ana",
  assignee: "npub1bina",
  created_at: 100,
  updated_at: 100,
  state: "open",
  assigned_by_me: true,
  mine_to_do: false,
  ...over,
});

const noteToSelf = (over = {}) =>
  task({ assignee: null, assigned_by_me: true, mine_to_do: true, ...over });

// ── The buttons must mirror karya::may_transition ─────────────────────────────

test("whoever was asked may finish or decline, and may not withdraw", () => {
  const mine = task({ assigned_by_me: false, mine_to_do: true });
  assert.deepEqual(actionsFor(mine), [DONE, DECLINE]);
});

test("whoever asked may withdraw, and may not finish it for them", () => {
  // The rule that stops an assigner ticking off someone else's work.
  const theirs = task({ assigned_by_me: true, mine_to_do: false });
  assert.deepEqual(actionsFor(theirs), [WITHDRAW]);
});

test("a note to self holds both powers, withdraw included", () => {
  // The exception the whole table bends around — deleting your own private note
  // is not somebody else's business. `Task::apply` special-cases it too.
  assert.deepEqual(actionsFor(noteToSelf()), [DONE, DECLINE, WITHDRAW]);
});

test("nothing is offered on a task that has already landed", () => {
  // Nothing reopens, so a terminal row is a record and not a control.
  for (const state of ["done", "declined", "withdrawn"]) {
    assert.deepEqual(actionsFor(task({ state })), [], state);
    assert.deepEqual(actionsFor(noteToSelf({ state })), [], `self/${state}`);
  }
});

test("a row nobody is party to offers nothing", () => {
  // Should not arrive — `tasks()` only returns rows this device is party to —
  // but offering a button core would refuse is the failure to avoid.
  assert.deepEqual(actionsFor(task({ assigned_by_me: false, mine_to_do: false })), []);
  assert.deepEqual(actionsFor(null), []);
  assert.deepEqual(actionsFor(undefined), []);
});

test("every state this build knows is handled explicitly", () => {
  // A state treated as open by accident would show buttons on a finished task.
  for (const state of STATES) {
    const actions = actionsFor(task({ state, mine_to_do: true, assigned_by_me: false }));
    if (state === "open") assert.ok(actions.length > 0, state);
    else assert.deepEqual(actions, [], state);
  }
});

test("a state from a newer build is treated as resolved, not as open", () => {
  assert.deepEqual(actionsFor(task({ state: "snoozed" })), []);
});

// ── The wire contract ────────────────────────────────────────────────────────

test("a button asks for the state core actually accepts", () => {
  // `TaskState` is snake_case on the wire, so "Done" is rejected by serde and
  // every button comes back an error. This shipped once; it is a test now.
  assert.equal(wireState(DONE), "done");
  assert.equal(wireState(DECLINE), "declined");
  assert.equal(wireState(WITHDRAW), "withdrawn");
});

test("every state a button can ask for is one core knows", () => {
  for (const action of [DONE, DECLINE, WITHDRAW]) {
    assert.ok(STATES.includes(wireState(action)), action);
    assert.notEqual(wireState(action), "open", "nothing reopens");
  }
  assert.equal(wireState("snooze"), null, "an unknown action asks for nothing");
});

// ── Grouping ─────────────────────────────────────────────────────────────────

test("open first, resolved after, each keeping the store's order", () => {
  const list = [
    task({ id: "a", state: "open", created_at: 30 }),
    task({ id: "b", state: "done", created_at: 20 }),
    task({ id: "c", state: "open", created_at: 10 }),
  ];
  const { open, resolved } = groupTasks(list);
  assert.deepEqual(
    open.map((t) => t.id),
    ["a", "c"],
    "a partition, not a re-sort — tasks() is already newest-first",
  );
  assert.deepEqual(
    resolved.map((t) => t.id),
    ["b"],
  );
});

test("an empty or missing list groups without throwing", () => {
  assert.deepEqual(groupTasks([]), { open: [], resolved: [] });
  assert.deepEqual(groupTasks(undefined), { open: [], resolved: [] });
});

// ── Subtitles ────────────────────────────────────────────────────────────────

test("a subtitle names a person, never a key", () => {
  const nameFor = (npub) => ({ npub1ana: "Ana", npub1bina: "Bina" })[npub] || npub;
  assert.equal(subtitleFor(task({ assigned_by_me: true }), nameFor), "you asked Bina");
  assert.equal(
    subtitleFor(task({ assigned_by_me: false, mine_to_do: true }), nameFor),
    "Ana asked you",
  );
  assert.equal(subtitleFor(noteToSelf(), nameFor), "yours");
});

test("a subtitle with no resolver still says something", () => {
  // Degrades to the key rather than throwing — a list that crashes on a missing
  // contact is worse than one that shows an npub.
  assert.match(subtitleFor(task(), null), /npub1bina/);
  assert.equal(subtitleFor(null, null), "");
});

// ── Copy ─────────────────────────────────────────────────────────────────────

test("resolved states read as words, open says nothing", () => {
  assert.equal(stateLabel("done"), "Done");
  assert.equal(stateLabel("declined"), "Declined");
  assert.equal(stateLabel("withdrawn"), "Withdrawn");
  assert.equal(stateLabel("open"), "", "an open row is the point; it needs no badge");
  assert.equal(stateLabel("snoozed"), "");
});

test("the empty state teaches the command that fills it", () => {
  // This screen is reachable before you know /task exists, so "no tasks" would
  // waste the one place that can teach it.
  const copy = emptyCopy();
  assert.match(copy, /\/task/);
  assert.match(copy, /@ana/, "and that a comrade can be asked, not just yourself");
});
