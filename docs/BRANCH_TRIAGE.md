# Branch triage — 2026-08-14

Snapshot at `origin/main` = `729845f` (PR #113). **Zero open PRs.** Re-run of
the 2026-07-31 pass (kept in git history at `docs/BRANCH_TRIAGE.md@d38162b`-era
commits); main advanced ~90 commits since (PRs #62–#114), which resolved most
of the previous pass's findings and changed several verdicts.

11 `claude/*` branches carry commits `git rev-list` counts as unique — but
**four of those are fully landed with rewritten history** (squash/rework on
merge), so `rev-list --count` alone overstates the unlanded work. The real
split: 3 branches in flight today, 2 carrying genuinely unlanded work, 6 safe
to delete.

The point of this document is to record work that never landed *before* the
branches carrying it are deleted, since nothing else references those commits.

## Method, and two traps

**The clone was shallow.** `.git/shallow` pinned graft boundaries, which makes
`git merge-base`, `git cherry`, and `A...B` ahead/behind counts report against
truncated history. `git fetch --unshallow` first. (Bit again this pass: the
shallow counts said `desktop-layout-couple-ledger-havvuz` was 77 ahead; after
unshallowing it is 0.)

**Refs go stale mid-triage.** Re-fetch immediately before any deletion — not
because a verdict changes, but because acting on a known-stale ref set is the
wrong habit for an irreversible operation. Three of today's branches were
pushed *today* by concurrent sessions.

`git merge-base --is-ancestor <branch> origin/main` is the authoritative
containment test — but **a squash-merged branch fails it while holding nothing
main lacks**, and `git cherry` reports `+` for commits whose content was
reworked on merge. The decisive test for those is a direct file compare:
`diff <(git show <branch>:<file>) <file>` over the files the branch touches.

## In flight today — do not touch (3)

Pushed 2026-08-14 by concurrent sessions, no PRs open yet:

| Branch | Carries |
| --- | --- |
| `together-mode-grid-listen-m0niww` | 4 commits: album-grid browsing, untagged-album handling, device-lane flake audit note |
| `message-threads-chat-x2mfhl` | 1 commit: tap-a-quote jumps to the message without interrupting history reading |
| `journal-notes-sharing-chats-8o9zgt` | 1 commit: share one journal note to one chat (39 files, all three frontends) |

## Landed with rewritten history — safe to delete (4)

`rev-list` says these are ahead; the files say otherwise. Verified by direct
file compare against main, not by commit identity.

- **`bitchat-analysis-comrade-6x3r86`** (6 "unique" commits, 2026-08-08).
  Dual-radio offline delivery, BLE no-subscriber refusal, mesh-test budgets,
  the preserved-YouTube-player Gradle dep. All on main: `saathi.rs` and
  `ble/BleMeshService.kt` are byte-identical to the branch, main's
  `runtime.rs`/`offline_delivery.rs` carry the "both radios" design (`:8666`,
  `:8722`), and `app/android/app/build.gradle.kts:450` has the YouTube dep.
- **`contact-comrade-notifications-9hgh6b`** (2, 2026-08-01). The
  `presence_active` gating ("online means the app is open") is on main
  (`runtime.rs:1994`, `:2082`, `refresh_presence` at `:2504`); the
  yanked-`nostr-relay-pool` dep move was superseded by the nostr-sdk 0.45
  upgrade (PR #114).
- **`bob-alice-message-notification-zeikr6`** (1, 2026-08-04). The
  delivery-outage fix landed as `d79ab2a` (AUDIT Q11, resolved 2026-08-04).
  The branch's four residual improvements main's version lacked — the
  one-minute `ensureRunning` watchdog in `RelayConnectionService`, a
  liveness-honest `EventPump.isRunning`, the generic JVM-testable `drainLoop`
  (now `EventDrain.kt`), and `Notifier`'s `runCatching` post wrapper — were
  ported by the 2026-08-14 session (`claude/codebase-analysis-improvements-gfcs03`).
  Delete once that lands.
- **`voice-video-call-ux-5up39x`** — 0 unique commits now; the letterbox
  work the 2026-07-31 pass called "active" merged.

Also still at zero and safe to delete: everything in the 2026-07-31 pass's
fully-merged table, plus `together-two-peer-ci-lane`, `together-mode-music-player-0zb618`,
`together-mode-media-controls-6f0b97`, `handoff-*`, `telegram-style-profile-5w9v8c`,
`in-chat-actions-commands-backgk`, `filesync-integration-analysis-ff8gro`,
`cognitive-restoration-analysis-e2acrt`, `claude-docs-agent-setup-9jpgs6`,
`flutter-ui-design-system-jvrska`, `together-players-handoff-docs-b3hsmv`,
`together-mode-analysis-{plan-qew434,fixes-v89y5l}`, `comms-features`' sibling
`bitchat` (above), `voice-model-download-prompt-l5cdjw`, `uniffi-ffi-bindings-ml0cuj`,
`voice-video-calls-frontend-pq1psw`, `media-sharing-screenshots-w651hd`,
`chat-screen-ux-improvements-u6huzl`, `in-app-updates-notifications-s93x92`,
`calling-bugs-fixes-ew9q4t`, `call-signaling-idempotent-bzggsv`,
`comrade-webrtc-signaling-fixes-51cev9`, `bob-alice-message-notification-zeikr6`
(after the port lands), `desktop-layout-couple-ledger-havvuz`,
`app-startup-performance-m0md3s` (below), `ci-apk-generation-testing-fnkq3n`,
`fervent-carson-ty53zj`, `repo-audit-analysis-y1mevs`,
`pixel-9-assistant-response-dh96h6` (after §2 below is re-implemented).

## Genuinely unlanded (2, plus two carry-forwards)

### 1. `comms-features-architecture-gqtb1y` — the two big items are STILL open

78+ commits behind, predates the Flutter frontend; port by hand, never merge.
Re-verified against `729845f`:

- **`CallService` FGS hardening is still missing.** `SHORT_SERVICE`,
  `promotePlaceholder` and `onTimeout` have zero hits under `android/…/call/`.
  The rules are now written down in `.claude/rules/android.md` (read it before
  touching this), and the branch's `f276621`+`8b58342`+`277d024` remain the
  reference implementation, with `CallServiceContractTest` (139 lines) as the
  missing test. This is the highest-severity known gap on the priority
  frontend: three unguarded promotion surfaces, each able to take the process
  down with `ForegroundServiceDidNotStartInTimeException`.
- **Desktop capture is still unbounded.** `media_params|maxBitrate|setParameters`
  remain zero hits under `desktop/`; `getDisplayMedia({video:true})` still
  captures at full resolution with no `frameRate`/`contentHint` and no
  `setParameters` cap after `replaceTrack`. Port `media_params.mjs` (96 lines
  + 16 `node:test` vectors) from `a6dcce0`; CI already globs
  `desktop/ui/*.test.mjs`.
- The `startScreenShare` ordering race (comment claims the `mediaProjection`
  re-announce lands before capture starts; the code does not enforce it) is
  also still present — the async `startForegroundService` gap described in
  `.claude/rules/android.md` applies.
- Its AUDIT items (A3/O5 strikes) landed independently — done on main as of
  2026-08-14.
- `399d2a1` (numeric passcode keypad) remains a UX decision nobody has made;
  both frontends still use `KeyboardType.Password` with length-only validation.

### 2. `tara-llm-therapist-tile-19wzyj` — new work since the last pass, needs a decision

The voice crisis hand-off the 2026-07-31 pass recommended porting **landed**
(`VoiceCommand.Tara`, `TARA_PREFIXES` in `voice/VoiceCommand.kt`). But the
branch then grew two new commits (2026-07-31/08-01): a full reply-engine
rewrite — "hear the person, not the keyword" — that is 1,178 insertions in
`crates/comrade_core/src/tara.rs` plus surfaces in all three frontends and
`docs/TARA.md`. Genuinely unlanded, conflicts with main, no PR. This is a
product-behavior rewrite of the companion's language engine; it needs an owner
decision (adopt / rebase / drop), not a mechanical port. Until then: keep.

### Carry-forwards from resolved branches (small, concrete)

- **From `pixel-9-assistant-response-dh96h6`:** journal streaks
  (`current_streak_days`, `entries_this_week`, ~100 lines incl. tz-offset
  helper and tests — ports directly against `created_at`), and four helplines
  main still lacks (iCall 9152987821, US 988, UK Samaritans 116 123,
  Befrienders Worldwide; main's only non-India route is a findahelpline.com
  link). `git grep -iE 'streak|entries_this_week' crates/` is still empty on
  main (the `attention.rs` hits are prose saying attention deliberately has
  no streaks). Mood-trend and top-tags need redesign (main's `mood` is
  `Option<String>`, no `tags` field) — do not port those.
- **From `voice-notes-media-pipeline-e992r3` (delete stands):** of the two
  PR #61 follow-ups, *"test the successful-fallback path"* was closed
  2026-08-14 (`media.rs::a_later_hosts_success_is_returned_not_just_reported`,
  loopback stub, runs under `--features media-http`). *"Probe a second live
  Blossom host"* (`blossom_probe` against `https://blossom.primal.net`) is
  still open — it needs the network, so it cannot be done from a sandbox.

## What changed since 2026-07-31

- **Main advanced ~90 commits** (PRs #62–#114): together mode (music player,
  search, downloads, two-peer CI lane), BLE mesh ("route 2b"), file transfer
  hardening (P8/Q18/Q19), presence gating, quiet-hours channels, the EventPump
  outage fix (Q11–Q14), nostr-sdk 0.45.
- **Verdict flips:** `bitchat`, `contact-comrade-notifications`, `bob-alice`
  and `voice-video-call-ux` all landed (three with rewritten history — see the
  method note). The tara branch flipped the other way: its recommended port
  landed, then it grew a bigger unlanded rewrite.
- **Resolved from the old follow-up list:** #1 voice crisis hand-off (landed),
  #3 successful-fallback test (this pass), #7 AUDIT A3/O5 strikes (this pass).
  Still open: #2 FGS hardening, #4 desktop capture bounds, #5 `startScreenShare`
  ordering, #6 journal streaks + helplines, #8 unprefixed-voice-distress
  decision — plus the tara rewrite decision (new).

## Suggested follow-up PRs, in order

1. `CallService` FGS hardening + `CallServiceContractTest` (crash risk, zero
   coverage, three unguarded surfaces; Android is the priority frontend).
2. Desktop capture bounds + bitrate cap, covering `getDisplayMedia`.
3. Journal streaks + weekly count + the four missing helplines.
4. Fix the `startScreenShare` ordering race, or correct its comment.
5. Owner decision on the tara reply-engine rewrite.
6. Decide the unprefixed-voice-distress question (still open from last pass).
