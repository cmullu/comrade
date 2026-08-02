# Branch triage — 2026-07-31

Snapshot at `origin/main` = `d38162b` (PR #61). **Zero open PRs.** 20 `claude/*`
branches besides this one: 14 hold nothing main lacks, 6 carry unique commits.

Re-run of the 2026-07-30 pass. Main advanced 8 commits (PRs #58–#61) and four
branches changed state, so several earlier conclusions are revised below —
see *What changed since 2026-07-30*.

The point of this document is to record work that never landed *before* the
branches carrying it are deleted, since nothing else references those commits.

## Method, and two traps

**The clone was shallow.** `.git/shallow` pinned 6 graft boundaries, which makes
`git merge-base`, `git cherry`, and `A...B` ahead/behind counts report against
truncated history. `git fetch --unshallow` first.
`pixel-9-assistant-response-dh96h6` read as "19 ahead" shallow and **2** after.

**Refs go stale mid-triage.** PR #61 merged at 08:15:35Z, two minutes after being
read as open. Re-fetch immediately before any deletion — not because a verdict
changes, but because acting on a known-stale ref set is the wrong habit for an
irreversible operation.

`git merge-base --is-ancestor <branch> origin/main` is the authoritative
containment test. `git diff main..branch` is **not** — for a branch far behind
main it shows main's newer work and looks like unique content.

## Fully merged — zero unique commits (14)

Verified two ways against `d38162b`: `git rev-list --count origin/main..<branch>` = 0
and `--is-ancestor` = yes. Deleting these loses nothing.

| Branch | Last activity |
| --- | --- |
| `bitchat-analysis-comrade-6x3r86` | 2026-07-31 |
| `call-signaling-idempotent-bzggsv` | 2026-07-15 |
| `calling-bugs-fixes-ew9q4t` | 2026-07-14 |
| `chat-screen-ux-improvements-u6huzl` | 2026-07-30 |
| `ci-apk-generation-testing-fnkq3n` | 2026-07-10 |
| `comrade-webrtc-signaling-fixes-51cev9` | 2026-07-14 |
| `desktop-layout-couple-ledger-havvuz` | 2026-07-12 |
| `fervent-carson-ty53zj` | 2026-07-10 |
| `in-app-updates-notifications-s93x92` | 2026-07-30 |
| `media-sharing-screenshots-w651hd` | 2026-07-31 |
| `repo-audit-analysis-y1mevs` | 2026-07-10 |
| `uniffi-ffi-bindings-ml0cuj` | 2026-07-12 |
| `voice-model-download-prompt-l5cdjw` | 2026-07-28 |
| `voice-video-calls-frontend-pq1psw` | 2026-07-13 |

Two PRs across the repo's history closed **without** merging, both harmless: #48
(`deny.toml` RUSTSEC ignore) was redone — `RUSTSEC-2026-0215` is on main at
`deny.toml:35` with fuller rationale; #25 was a docs roadmap.

## Active — do not touch (1)

### `voice-video-call-ux-5up39x` — work in flight

`196463a` "call: a shared screen shouldn't be cropped to a phone's shape", pushed
2026-07-31 08:13, unmerged, no PR open yet. Follow-up to the screen-share feature
that landed in PR #59: a shared 16:9 screen on a portrait phone was filled to the
box, cropping ~68% away.

Decides fit from frame geometry rather than a protocol field — "would filling this
box destroy the picture" is answerable from the frame that already arrived, where
"is the peer sharing a screen" would need a wire-format change. Threshold at one
third, between *4:3 camera on a tall phone* (25% cropped, fill) and *16:9 screen on
a portrait phone* (68% cropped, letterbox). Implemented three times because the
renderers differ — `shouldLetterbox` in `call_video.dart` (BoxFit), `CallWidgets.kt`
(`SCALE_ASPECT_FILL` vs `_FIT`), `call_decisions.mjs` (object-fit) — same numbers
and tests in each. Reports `flutter analyze --fatal-infos` clean, 237 Dart tests,
68 desktop JS tests.

Worth noting it explicitly rejected double-tap-to-toggle: the call stage already
uses a single tap to reveal controls, so adding `onDoubleTap` to the same target
makes every single tap wait out the double-tap timeout.

## Diverged — carry unlanded work (5)

None can be *merged*: each is far enough behind that a merge would revert main.
Treat them as patches to port or specs to re-implement.

### 1. `tara-llm-therapist-tile-19wzyj` — land it

`804951d` adds the crisis hand-off for the **voice** modality. Re-verified against
`d38162b`; all four original claims hold.

- Main's `CRISIS_REPLY` (`crates/comrade_core/src/tara.rs:274`) still says "the
  helplines shown below" — meaningless read aloud with no screen. `git grep 'shown
  below'` returns only that line, and main's own test asserts only
  `contains("not a therapist")`, so the reword breaks nothing.
- Main's voice dispatcher still has no Tara route: zero matches for
  `tara|crisis|helpline` across all 11 files in
  `android/app/src/main/java/mullu/comrade/voice/`. `VoiceCommand.parse()` falls to
  `Unknown(text)` (`:128`) and `CommandDispatcher.kt:78-79` answers "Sorry, I can't
  do that yet." `detect_distress` is never reached.
- No rival route landed in the Flutter tree — `app/`'s wake-word channel returns raw
  transcript and deliberately does not dispatch commands.
- Still merges clean (`git merge-tree` exit 0, no conflicts). Only `README.md`
  drifted among the 8 touched files, and not in the row the branch edits.
- Prerequisites present: `ComradeCore.kt:497` `taraSendTyped`, `:507`
  `taraCrisisResources`. `ComradeBackend` has exactly two implementors on main, both
  updated by the branch, so adding `tara()` breaks no third.
- Adds 8 tests, including a digit-spelled helpline read-out (`1 4 4 1 6`) so TTS
  does not say "fourteen thousand four hundred sixteen".

Scope honestly: an **unshipped** path, not a regression. `docs/TARA.md:138` still
lists the voice command as an open follow-up. The detection gate is present and
unchanged on main (`tara.rs:148`, `:328`), and the screen surfaces render the
hand-off correctly (`ui/TaraScreen.kt:383`).

Nit for whoever lands it: the commit inserts `Helpline`/`TaraReply` between the
`ComradeBackend` KDoc and the interface, so the KDoc documents `Helpline`.

**Separate open question, outside this commit:** neither main nor the branch detects
distress in an *unprefixed* utterance — "hey comrade I want to die" is `Unknown` on
both. Voice-wide distress handling needs a decision.

### 2. `comms-features-architecture-gqtb1y` — hand-port three items

All five findings from the previous pass **still hold**, and the screen-share commit
`2b315b4` worsened two. The branch is 78 commits behind and predates the Flutter
frontend, so port by hand as fresh commits.

- **Unguarded `startForeground`** — `call/CallService.kt:74-79` promotes
  unconditionally in `onCreate`; the type selection at `:161-168` is
  `FOREGROUND_SERVICE_TYPE_MICROPHONE` with no `runCatching` (the file's only
  `runCatching`, at `:130`, wraps an unrelated `notify()`). The caller-side guard at
  `CallManager.kt:1265-1268` is on the wrong side of the async boundary:
  `CallService.start` ends in `startForegroundService` (`:281`), so it catches only
  the synchronous dispatch exception — a throw from `startForeground` at `:165`
  happens later on the service's own looper and is uncatchable there.
  **Worsened:** `2b315b4` added `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` (`:164`)
  as a third unguarded refusal surface, reached mid-call via new `CallService.start`
  calls at `CallManager.kt:785` and `:897`.
  Fix: `promotePlaceholder()` / `SHORT_SERVICE` / `onTimeout` from `f276621`+`8b58342`.
  `SHORT_SERVICE` has zero hits under `call/`, and it is manifest-exempt, so no
  `AndroidManifest.xml` change is needed.
  Note the branch's own CI-proven rule: a refused `startForeground` does **not**
  cancel the pending did-not-start-in-time kill — so the fix is to make promotion
  succeed, not to catch the throw.
- **`onStartCommand` never re-promotes** — `CallService.kt:82-92` calls `stopSelf()`
  with the incorrect comment "onCreate already called startForeground". The
  obligation arms per `startForegroundService()` call (`:281`), and `2b315b4` raised
  those from one to three per instance, so this path is now reachable more often.
- **No FGS regression test** — only `CallManagerTest`, `CallManagerLifecycleTest`,
  `CallManagerDeadlockRegressionTest` exist; `CallServiceContractTest` (139 lines,
  `277d024`) is absent. `disableCallServiceForTest` is set at
  `CallManagerLifecycleTest.kt:51` and honoured at five sites in `CallManager.kt`
  (two new from `2b315b4`), so no instrumented test ever starts `CallService` and
  both new screen-share promotion paths are untested.
- **Desktop capture unbounded and uncapped** — `desktop/ui/main.js:1146-1149` is
  still bare `getUserMedia` with a boolean `video`. `maxBitrate|setParameters|media_params`
  is zero hits repo-wide; `contentHint|degradationPreference|frameRate` is zero under
  `desktop/`. **Scope widened:** `2b315b4` added `getDisplayMedia({video:true})` at
  `main.js:2005` — full-resolution capture, no `frameRate`, no `contentHint` — handed
  to `replaceTrack` at `:2032` with no `setParameters` pass. Port `media_params.mjs`
  (96 lines) + 16 `node:test` vectors from `a6dcce0`, applied to `getDisplayMedia`
  too. CI already globs `desktop/ui/*.test.mjs`. Take constraints and cap only — the
  camera toggle landed in `68343de` and main's version is better.
- **`AUDIT.md` stale** — A3 still open as **H** at `:115`, O5 at `:192` and cited
  live inside N3 at `:204`. Both are fixed on main:
  `crates/comrade_core/src/sakha.rs:163` is `pub fn pair_with(&self, …)` backed by
  `SyncRwLock` (`:125-126`), regression test at `:410`, callers at
  `comrade_ui/src/runtime.rs:2759` and `:2820`; `desktop/src-tauri/icons/` holds real
  icon binaries. Re-cite before striking — `fb1d3f2`'s line numbers have all moved,
  and `AUDIT.md:36` records a newer A3-adjacent gap (`pair_sakha` exposed by Tauri
  but by neither FFI ABI).

**New issue in shipped code, found while re-verifying.** `CallManager.startScreenShare`
comments at `:770-776` that the service is re-announced with `mediaProjection`
*before* capture starts. It is not guaranteed: `CallService.start` →
`startForegroundService` is async, while the code proceeds from `:784-787` into the
`synchronized` block and reaches `startCapturing` at `:813`/`:826`. On API 34+
`startCapture` can run before the type transition lands and throw `SecurityException`.
Caught at `:909`, so the failure is "screen share silently doesn't start" rather than a
crash — but the ordering guarantee the comment relies on is absent.

**Correction to the 2026-07-30 note on `6fd21e1`.** It does *not* delete main's
`disableCallServiceForTest` line — the commit predates that line (added later by
`7284066`), and its diff only adds imports and a `GrantPermissionRule`. The risk is
only in taking the branch's *file version* of `CallManagerLifecycleTest.kt` or
`CallManager.kt`, which would drop the opt-out. `6fd21e1`'s own value is now largely
moot: main's tests never start `CallService`, so the permission grant fixes a crash
the opt-out already suppresses. Drop `3854900` too — it is partly false about main
(claims the numeric keypad shipped; it did not).

`399d2a1` (numeric passcode keypad) is still unique — `ui/OnboardingScreen.kt:174,187`
are `KeyboardType.Password` with length-only validation — but it is a UX decision, not
a bug, and its own justification ("no shipped install carries an alphanumeric
passcode") is now two weeks stale. The Flutter onboarding has the same gap
(`app/lib/src/screens/onboarding_screen.dart:206-231`).

### 3. `voice-notes-media-pipeline-e992r3` — superseded, delete

**Reversed from 2026-07-30.** PR #61 (`904f0c9`, merged today) fully supersedes
`2d9a5ec`'s Blossom fallback, and is stronger on every dimension:

| | `2d9a5ec` (stale) | PR #61 (on main) |
| --- | --- | --- |
| Where the retry lives | combinator in core, assembled at one UI call site | inside `BlossomUploader::upload` (`media.rs:714`) — all callers inherit it |
| Error on total failure | keeps `last_err` only | names every host and its cause |
| Timeouts | none — a black-holed host hangs forever | 10s connect, 120s transfer (`media.rs:532`, `:538`) |
| `upload_encrypted_blob` | left single-server | covered (`media.rs:577`) |
| Host verification | never probed | `examples/blossom_probe.rs`, real signed round-trip |

PR #61 also adds BUD-02 `X-SHA-256`/`Content-Length` headers, and
`OPAQUE_UPLOAD_MIME` (`comrade_ui/src/runtime.rs:98`) so the host is no longer told
the plaintext's real MIME type — a metadata-leak fix with no equivalent anywhere.

`ea5e8b8` (Android WebRTC calls) was already long superseded: main's `CallManager.kt`
is ~2,130 lines vs the branch's 625, with `CallService`/`PipController`/`Ringer`/
`AudioRoute`/`CallQuality` absent from the branch and 494 lines of call tests vs none.

**Two follow-ups for PR #61 — carry these forward before deleting:**

1. **Only one of #61's three hosts actually accepts uploads.** Its own probe measured
   `nostr.download` working (512-byte round trip verified), `blossom.band` returning
   415/400, `cdn.satellite.earth` returning 401. So main currently ships list-shaped
   structure with effectively a single working host. `https://blossom.primal.net`
   (from `2d9a5ec`'s list, never probed) is a free candidate — run `blossom_probe`
   against it.
2. **The successful-fallback path is untested.** #61's only failover test is the
   all-fail case, so "does a later host's success get returned?" rests on reading the
   loop. Copying `2d9a5ec`'s test verbatim will not compile — #61's loop is a private
   inline `for`, not an injectable closure — so this needs a loopback listener or a
   `#[cfg(test)]` seam.

Two smaller nits in #61 worth a review comment: the `unwrap_or_default()` comment at
`media.rs:650` claims a fallback client's missing timeouts are "covered by the
per-request budget below", but `upload_to` sets no per-request `.timeout()` (the path
only triggers on a builder failure that cannot occur with these arguments); and
`upload_encrypted_blob` now `blob.clone()`s a potentially 10 MB payload per attempt.

### 4. `pixel-9-assistant-response-dh96h6` — re-implement one feature, then delete

Only 2 genuinely unique commits (`7b3124a`, `ce5ca7d`), merge-base `f6b793e`. The
apparent ~96k insertions were a two-tree diff measuring how far *behind* it is — its
entire tree is 75 files / 20,803 lines.

Residual value is in branch-only `crates/comrade_core/src/companion.rs` (707 lines,
~500 non-test) — but **narrower than the 2026-07-30 framing**, because main's
`tara.rs` (505 lines) already has `CompanionEngine`/`ReflectiveCompanion`,
`JournalSignal` with mood, `detect_distress` (`:148`, ≈ the branch's `scan_safety`),
and `CRISIS_RESOURCES` (`:68`). Storage exists too: `comrade_storage/src/repository.rs:184`
`JournalEntry`, save/list/remove at `:550-570`, DTO at `comrade_ui/src/runtime.rs:710`,
JNI at `comrade_jni/src/api.rs:737-750`. So `scan_safety`/`CrisisResource` are
duplicated, not missing, and `CompanionMode`/`EntrySource` serve a session model main
did not adopt.

What is genuinely missing — `git grep -niE 'streak|momentum|mood_trend|top_tags'
origin/main -- crates/` returns zero hits:

- **Ports directly** (~100 lines incl. the `local_day`/tz-offset helper and tests):
  `current_streak_days`, `entries_this_week`. Needs only `created_at`.
- **Does not port — redesign:** `avg_mood_recent` assumes `mood: Option<i8>` on a
  −2..2 scale; main's is `Option<String>` (emoji/short tag), and `JournalEntry` is
  serde-persisted in redb, so changing the type is not backward-compatible.
  `top_tags` needs a `tags` field main lacks — cheapest path is running
  `extract_hashtags` over `text` at read time.
- **Cheapest genuine win, ~4 lines of data:** helplines main lacks — iCall
  9152987821, US 988, UK Samaritans 116 123, Befrienders Worldwide. Main's only
  non-India route is a `findahelpline.com` directory link.

The calling half is gone: `git grep -niE 'pukar|25050' origin/main` → zero hits,
superseded by `crates/comrade_core/src/call.rs` (TURN support, `IceStrategy`, DTLS
fingerprints, `derive_sas`). Re-implement against main's types; do not cherry-pick.

### 5. `app-startup-performance-m0md3s` — near-zero value

`4a2381c` is two fixes with opposite fates.

- **Obsolete:** the MainActivity/LazyColumn half. `git grep -c CoreState origin/main`
  → zero hits repo-wide; MainActivity is now 1394 lines on `AppPhase {Checking,
  Locked, Ready}` (`:279-282`) dispatching to `MainShell` (`:401`), with no
  LazyColumn, `WorkspaceCard` or `KeygenSection`. All 11 hunks target code that no
  longer exists.
- **Unlanded but cosmetic:** the `ComradeTts.shutdown()` guard.
  `voice/ComradeTts.kt:52-57` is byte-identical to the merge-base and the hunk still
  applies cleanly.

Sharper severity read than the last pass: the patch **does not touch the shutdown
path at all** — `engine?.shutdown(); engine = null` is identical on both sides. It
only skips one `stop()`, which on an unbound engine logs and returns `ERROR` without
throwing. So it cannot fix a leak even in principle. Value is 6 lines of comment
explaining a confusing log line; zero behavioural gain. Fold it into unrelated work
if convenient — not worth a PR.

## What changed since 2026-07-30

- **Main advanced 8 commits**, PRs #58–#61: chat threads reopen where you left off,
  screen sharing, saathi sealed DMs over same-WiFi, media upload fallback.
- **`voice-notes-media-pipeline-e992r3` flipped to delete.** PR #61 superseded its
  one valuable commit.
- **`voice-video-call-ux-5up39x` flipped from merged to active.** New unmerged commit
  pushed today.
- **`media-sharing-screenshots-w651hd`, `chat-screen-ux-improvements-u6huzl`,
  `bitchat-analysis-comrade-6x3r86` merged** into main and joined the safe-to-delete
  set (12 → 14).
- **Two corrections:** the `6fd21e1` warning mechanism is narrower than stated (see
  §2), and `pixel-9`'s `Insights` port is smaller than stated — only streaks and
  weekly count port; mood trend and top tags are redesign (see §4).
- **Unchanged:** the tara verdict, and all five `comms-features` findings — two of
  which the screen-share commit worsened.

## Suggested follow-up PRs

1. Voice crisis hand-off — port `804951d` (safety; merges clean).
2. `CallService` FGS hardening + `CallServiceContractTest` (crash risk, zero coverage,
   now three unguarded refusal surfaces).
3. Probe a second live Blossom host, and test #61's successful-fallback path.
4. Desktop capture bounds + bitrate cap, covering `getDisplayMedia`.
5. Fix the `startScreenShare` ordering race, or correct its comment.
6. Journal streaks + weekly count, and the four missing helplines.
7. `AUDIT.md` accuracy pass — strike A3 at `:115`, O5 at `:192`/`:204`, fresh citations.
8. Decide the unprefixed-voice-distress question.
