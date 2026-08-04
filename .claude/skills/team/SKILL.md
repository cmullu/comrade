---
name: team
description: Form an agent team for work that spans Comrade's layers — cross-layer feature, parallel review, or competing-hypothesis debugging. Use when a task touches the Rust core and two or more frontends, or when one reviewer would miss things a panel would catch.
argument-hint: "feature <what> | review <target> | hunt <symptom>"
allowed-tools: Bash(git *), Read, Grep, Glob
---

# Form a team

Comrade is five near-disjoint file sets behind one FFI surface, which is what
makes teams work here: each owner edits files nobody else touches, and the
coordination that remains is a real contract rather than merge luck.

**Teams are experimental and off unless `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1`
is set** — it is set in `.claude/settings.json`, so it applies in this repo. If
teammates never appear, that variable did not reach the session; check `/context`
before retrying.

Reusable roles live in `.claude/agents/`: `comrade-core`, `comrade-ffi`,
`comrade-android`, `comrade-flutter`, `comrade-desktop`, plus `comrade-reviewer`.
Spawn teammates **by agent type** so each inherits its file ownership and traps.

## Rules that apply to every formation

- **You are the lead and you own `AUDIT.md` and `docs/`.** Five teammates editing
  one ledger is the one guaranteed conflict in this repo. Collect their findings
  and write those files yourself.
- **One owner per file set.** Never give two teammates the same directory.
- **Name teammates after their role** (`core`, `ffi`, `android`, `flutter`,
  `desktop`) so you and they can address each other predictably.
- **Do the work you assigned? No.** If you start implementing, teammates idle.
  Wait for them, then synthesize.
- **Approve permission prompts yourself** — they surface in your session.
- Teammates load `CLAUDE.md` and the path-scoped rules, but **not** your
  conversation. Put the specifics in the spawn prompt.

## `feature` — cross-layer change

The contract is the critical path, so it is not parallel with everything else.

1. Spawn `ffi` first (agent type `comrade-ffi`) and have it publish the exact
   command, DTO, `BridgeEvent` variant, and `_UiError` case — names and payloads.
   Everything else is blocked on that message.
2. Spawn `core` (`comrade-core`) for the engine decision. Tell it to put the rule
   and its numbers in the engine and broadcast the thresholds.
3. Spawn `android`, `flutter`, `desktop` for the surfaces. Give each the same
   feature description and tell them to agree parity numbers with each other
   directly rather than through you.
4. Require plan approval when the change touches call/foreground-service code or
   storage schema. Only approve plans that name a test.

Make the blocking order explicit in the task list: surface tasks depend on the
FFI task. Say so when you create them, or teammates will start guessing signatures.

Tell `android` and `flutter` in as many words: a new `BridgeEvent` variant makes
their exhaustive matches fail — Kotlin one lane, Dart four jobs — so handling it
everywhere is step one, not cleanup.

## `review` — parallel lenses

One reviewer converges on one class of issue. Split by lens, not by file, and let
them contest each other:

- **security** — key handling, what leaks to a relay or an upload host, crisis
  and safety paths
- **correctness** — locks across awaits, foreground-service contract, exhaustive
  matches, error variants lost at the FFI mirror
- **tests** — does a fix have a test that fails before it; does a feature-gated
  guard have a lane that runs it
- **honesty** — comments and docs asserting guarantees the code does not provide;
  `AUDIT.md` entries whose citations have drifted

Have each report `file:line` with a concrete failure scenario, then tell them to
try to refute each other's findings before you accept any. Reject "this could be
racy" without a scenario.

## `hunt` — competing hypotheses

For a symptom whose cause is unclear. Sequential investigation anchors on the
first plausible story; a panel that must disprove each other does not.

Spawn 3–5 investigators, each assigned a *different* layer as prime suspect
(engine, FFI boundary, one per surface), and instruct them explicitly to attack
each other's theories rather than defend their own. The theory that survives is
the likely cause. Ask for the evidence that would falsify each claim.

## Sizing and cost

**Five agents is the ceiling, not a target.** Prefer three. Each teammate is a
separate Claude instance with its own context, so cost scales roughly linearly,
and three focused teammates beat five scattered ones. 5–6 tasks each.

Five is also the natural cap here rather than an arbitrary one: there are exactly
five owned file sets, so a sixth teammate has nothing of its own to edit and
becomes a conflict risk. The `feature` formation uses all five only when a change
genuinely reaches every surface — if it touches two, spawn two.

The same ceiling applies to dynamic workflows: `.claude/settings.json` sets
`workflowSizeGuideline` to `small` (fewer than 5 agents), which also drops the
large-run warning threshold from 25 to 5. It is advice to the model, not an
enforced cap, so do not exceed it without saying why.

A team is worth it for cross-layer features, review panels and murky bugs, and
wasteful for anything sequential or single-file. Prefer a subagent when only the
result matters and no worker needs to talk to another.

## Before you finish

Two of the five surfaces cannot be built in this sandbox. When you synthesize,
state per surface whether it was compiled and tested or only reasoned about —
`android/` and `app/` are the latter, always, and CI is their first build. Do not
let a teammate's confident report become your unqualified claim.
