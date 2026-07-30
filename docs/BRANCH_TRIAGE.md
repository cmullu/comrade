# Branch triage — 2026-07-30

Snapshot of the 17 `claude/*` remote branches, taken at `origin/main` = `6ad096c`
(PR #57). Every PR in the repo (#1–#57) is closed; **there were no open PRs**, so
all 17 branches are post-merge leftovers rather than work in flight.

The point of this document is to record the work that never landed *before* the
branches carrying it are deleted, since nothing else references those commits.

## Method, and one correction worth knowing

The clone was **shallow** (`.git/shallow` pinned 6 graft boundaries), which makes
`git merge-base`, `git cherry`, and `A...B` ahead/behind counts unreliable — they
report against the truncated local history, not the real one. `git fetch --unshallow`
first; otherwise a branch can look wildly diverged when it is two commits ahead.
`pixel-9-assistant-response-dh96h6` read as "19 ahead" shallow and **2 ahead** once
unshallowed.

`git merge-base --is-ancestor <branch> origin/main` is the authoritative containment
test. Note that `git diff main..branch` is *not* — for a branch far behind main it
shows main's newer work and looks like unique content.

## Fully merged — zero unique commits (12)

Each verified with `git rev-list --count origin/main..<branch>` = 0 on full history.
Deleting these loses nothing; every commit stays reachable from `main`.

| Branch | Last activity | PRs |
| --- | --- | --- |
| `call-signaling-idempotent-bzggsv` | 2026-07-15 | #37 |
| `calling-bugs-fixes-ew9q4t` | 2026-07-14 | #35 |
| `ci-apk-generation-testing-fnkq3n` | 2026-07-10 | #10 |
| `comrade-webrtc-signaling-fixes-51cev9` | 2026-07-14 | #34 |
| `desktop-layout-couple-ledger-havvuz` | 2026-07-12 | #19 |
| `fervent-carson-ty53zj` | 2026-07-10 | #3, #4, #5, #9 |
| `in-app-updates-notifications-s93x92` | 2026-07-30 | #57 |
| `repo-audit-analysis-y1mevs` | 2026-07-10 | #7, #8 |
| `uniffi-ffi-bindings-ml0cuj` | 2026-07-12 | #20, #21 |
| `voice-model-download-prompt-l5cdjw` | 2026-07-28 | #41, #44, #48 |
| `voice-video-call-ux-5up39x` | 2026-07-29 | #52, #53 |
| `voice-video-calls-frontend-pq1psw` | 2026-07-13 | #22–#33 |

Two PRs closed **without** merging, both harmless: #48 (`deny.toml` RUSTSEC ignore)
was redone — `RUSTSEC-2026-0215` is on main at `deny.toml:35` with fuller rationale;
#25 was a docs roadmap.

## Diverged — carry unlanded work (5)

Ordered by what is actually worth recovering. None of these can be *merged*: each is
far enough behind that a merge would revert main. Treat them as patches to port or
specs to re-implement.

### 1. `tara-llm-therapist-tile-19wzyj` — land it

`804951d` adds the crisis hand-off for the **voice** modality, and it is the one item
here that is safety-relevant.

- Main's `CRISIS_REPLY` (`crates/comrade_core/src/tara.rs:272`) says "the helplines
  shown below" — meaningless read aloud with no screen. The commit rewords it to be
  modality-neutral.
- Main's voice dispatcher has no Tara route: `VoiceCommand.parse()` falls through to
  `Unknown(text)` (`android/.../voice/VoiceCommand.kt:127`), so a spoken "tara, i want
  to end it all" gets "Sorry, I can't do that yet." `detect_distress` is never reached
  because the utterance never enters the Tara engine.
- Adds 8 tests, including a digit-spelled helpline read-out (`1 4 4 1 6`) so TTS does
  not say "fourteen thousand four hundred sixteen".

All 8 touched files are byte-identical between the commit's parent and main, and
`git merge-tree` reports **no conflicts**. Prerequisites already exist on main
(`ComradeCore.taraSendTyped`, `taraCrisisResources`).

Scope honestly: this is an **unshipped** path, not a regression — voice→Tara does not
exist on main, and `docs/TARA.md:138` still lists it as an open follow-up. The
detection gate itself is present and unchanged on main (`tara.rs:148`, `:328`), and
the screen surfaces render the hand-off correctly (`ui/TaraScreen.kt:383`).

Nit for whoever lands it: the commit inserts `Helpline`/`TaraReply` between the
`ComradeBackend` KDoc and the interface, so the KDoc ends up documenting `Helpline`.

**Separate open question, outside this commit:** neither main nor the branch detects
distress in an *unprefixed* utterance — "hey comrade I want to die" is `Unknown` on
both. Voice-wide distress handling needs a decision.

### 2. `comms-features-architecture-gqtb1y` — hand-port three items

Real Android foreground-service bugs still live on main. The branch is **65,887
deletions** behind (predates the whole Flutter frontend), so port by hand.

- **Unguarded `startForeground` in `CallService.onCreate`** — `call/CallService.kt:74`
  → `:132-150`, typed `FOREGROUND_SERVICE_TYPE_MICROPHONE` with no `runCatching`. A
  platform refusal throws on the service's own looper and kills the process. The
  `runCatching` at `CallManager.kt:1050` does not cover it: `startForegroundService()`
  is async, so `onCreate` runs later and the throw is uncaught. Port
  `promotePlaceholder()` / `SHORT_SERVICE` / `onTimeout` from `f276621`+`8b58342`
  (`SHORT_SERVICE` has zero hits on main).
- **`onStartCommand` never re-promotes** — `CallService.kt:79-87` calls `stopSelf()`
  on the blank/redelivered path, commented "onCreate already called startForeground".
  The obligation arms per `startForegroundService()` call, not per instance, so
  place → cancel → place again risks `ForegroundServiceDidNotStartInTimeException`.
- **No FGS regression test exists** — `CallServiceContractTest` (139 lines, `277d024`)
  is absent, and `CallManagerLifecycleTest.kt:51` disables `CallService` outright, so
  both holes are unexercised in CI.
- **`media_params.mjs`** + 16 `node:test` vectors from `a6dcce0` — main's desktop
  capture is still bare `getUserMedia` (`desktop/ui/main.js:1130-1133`) with no
  bitrate cap (`maxBitrate`/`setParameters` have zero hits). CI already globs
  `desktop/ui/*.test.mjs`, so no workflow change needed. Take the constraints and cap
  only — the camera toggle landed separately in `68343de` and main's version is better.
- **`AUDIT.md` is stale**: A3 is listed **H**/open at `AUDIT.md:113` and O5 at `:190`,
  but both are fixed on main (`crates/comrade_core/src/sakha.rs:163`, regression test
  at `:410`). Re-cite before striking — the line numbers in `fb1d3f2` have all moved,
  and `AUDIT.md:36` records a newer A3-adjacent gap (`pair_sakha` exposed by Tauri but
  by neither FFI ABI).

**Do not apply `6fd21e1`** — it deletes main's `disableCallServiceForTest = true` and
re-enables real `CallService` starts in tests. Drop `3854900` too; it is now partly
false about main (claims the numeric keypad shipped; it did not).

`399d2a1` (numeric passcode keypad) is still unique — `ui/OnboardingScreen.kt:174,187`
are `KeyboardType.Password` with length-only validation — but it is a UX decision, not
a bug, and its own justification ("no shipped install carries an alphanumeric
passcode") is two weeks stale. The Flutter onboarding has the same gap
(`app/lib/src/screens/onboarding_screen.dart:206-231`).

### 3. `voice-notes-media-pipeline-e992r3` — cherry-pick one commit

`2d9a5ec` adds multi-server Blossom fallback. **The weakness is live on main:**
`crates/comrade_core/src/media.rs:385` is a single hardcoded
`DEFAULT_BLOSSOM_SERVER = "https://cdn.hackers.town"`, and `crates/comrade_ui/src/runtime.rs:3452-3461`
performs exactly one `.upload()` with no retry, called unconditionally at `:3253`. If
that host is unreachable, every image, voice note, and video send fails.

PR #54 did not incidentally fix this — it never touched `media.rs`; it queues the
NIP-94 reference DM when a *relay* rejects it, i.e. the post-upload step.

Cherry-pick verified in a throwaway worktree: `media.rs` applies clean; `runtime.rs`
has one positional conflict because main moved `upload_blob` to a free function. After
a ~15-line hand-port, `cargo test -p comrade_core --features media-http media::` gave
**13 passed, 0 failed** and `cargo check -p comrade_ui --features media-http` was clean.

Caveats: the patch's four default hosts were never reachability-tested (the authoring
environment blocked them) — verify `blossom.band` and `cdn.satellite.earth` are live
before merging. `upload_encrypted_blob` also stays single-server under this patch.

Drop `ea5e8b8` (calling) as superseded: main's `CallManager.kt` is 2,130 lines vs 625,
with 494 lines of call tests vs none, plus `CallService`/`PipController`/`Ringer`/
`AudioRoute` with no branch equivalent.

### 4. `pixel-9-assistant-response-dh96h6` — re-implement one feature, then delete

Only 2 genuinely unique commits (`7b3124a`, `ce5ca7d`), both pushed after PR #6 had
already merged. The apparent ~96k insertions were a two-tree diff measuring how far
*behind* the branch is — its entire tree is 75 files / 20,803 lines.

Worth recovering from branch-only `crates/comrade_core/src/companion.rs` (640 lines):

- **`Insights`** — journal streaks, weekly momentum, mood trend, top tags. `streak`,
  `momentum`, `mood_trend`, `top_tags` each return zero files on main. The most
  substantive gap.
- `CompanionMode` (journal/vent/brainstorm/reflect taxonomy + curated prompt banks),
  `EntrySource` (typed-vs-voice provenance), `scan_safety`/`SafetyAssessment`,
  `extract_hashtags`.
- Extra helplines (988, Samaritans, Befrienders, iCall). Main's `findahelpline.com`
  directory link is arguably the more maintainable design — optional.

`ce5ca7d`'s calling work is superseded: branch `pukar.rs` (1,761 lines, kind-25050
ephemeral signaling) has zero hits on main, replaced by `crates/comrade_core/src/call.rs`
with TURN support, `IceStrategy`, DTLS fingerprints, and `derive_sas`.

This predates the package rename, the Flutter layer, flutter_rust_bridge codegen, and
the storage envelope rework — re-implement against `tara.rs`, do not cherry-pick.

### 5. `app-startup-performance-m0md3s` — optional ~6-line hygiene fix

`4a2381c` is two fixes with opposite fates.

- **Obsolete:** the `MainActivity` LazyColumn-keys half. `CoreState` → `AppPhase`, the
  LazyColumn became `MainShell`, `WorkspaceCard`/`KeygenSection` no longer exist
  (`git merge-tree` conflicts). Main already keys every data-driven list
  (`ui/ChatsScreen.kt:181`, `ui/FeedScreen.kt:127`, and others).
- **Unlanded:** the `ComradeTts.shutdown()` guard. `voice/ComradeTts.kt:52-57` is
  byte-identical to the pre-patch version and `git apply --check --3way` applies
  cleanly. Three call sites can invoke `shutdown()` before the TTS binding completes
  (`ui/SettingsScreen.kt:860`, `voice/WakeWordService.kt:297`,
  `voice/ComradeInteractionSession.kt:56`).

Honest sizing: `engine?.stop()` on a half-bound engine logs a warning and no-ops while
`shutdown()` still releases the connection. This is log noise, not a crash, hang, or
leak. Re-apply the guard with a unit test if convenient; not worth a dedicated PR.

## Suggested follow-up PRs

1. Voice crisis hand-off — port `804951d` (safety; merges clean).
2. `CallService` FGS hardening + `CallServiceContractTest` (crash risk, no coverage today).
3. Blossom multi-server fallback — port `2d9a5ec` after verifying the host list.
4. Journal `Insights` — re-implement against `tara.rs`.
5. `AUDIT.md` accuracy pass — strike A3/O5 with fresh citations.
6. Decide the unprefixed-voice-distress question.
7. Desktop capture bounds + bitrate cap (`media_params.mjs` + vectors).
