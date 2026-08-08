# Together — players handoff

_Written 2026-08-08 for the agent (or human) picking this up after PR #101
merged. Read **Verification** before you write anything; it is the part that
changes how you work, not just what you claim._

Design record is `docs/TOGETHER.md` §11–§14. This document is the *work list*
and the traps; that one is the *why*. Do not restate design decisions here —
amend §11–§14 instead and cite them.

---

## Where things stand

PR #101 merged. In `main` now:

| Landed | What it is |
|---|---|
| `direct_path_live` | the direct-channel watchdog (§5a) |
| drift/quality readout | on both frontends, ageing out after two heartbeats (§7) |
| `ServiceAccess` / `PlayheadControl` | what a *device* can drive, not what a link is (§11) |
| `TogetherContent::Service` / `::Stream` | service tracks and public HTTPS URLs on the wire (§11, §11a) |
| `valid_stream_url` / `admissible` | peer-supplied URL guard, exhaustive (§11a) |
| `CoarsePlayhead` / `embedState` | interpolating a once-a-second player (§11b) |
| `MediaSessionDecisions` | following another app's session (§13) |
| `SessionPlayer` / `PlaybackModeDecision` | the seam, and which mode a session gets (§14) |

**Every one of those is a decision layer.** Not one of them is reachable from
the UI. A user's experience today is exactly what it was before PR #101: local
files, and files handed over a peer connection. That is the gap this handoff
closes.

---

## Verification — read this before anything else

**You can compile and run Android decision logic in the sandbox.** This was
believed impossible for most of this repo's life and it is not. The Android
*SDK* is missing; Kotlin is not. Any file with no Android imports compiles and
runs under `kotlinc` + JUnit in about a minute. The recipe is in `CLAUDE.md`
under "What this sandbox cannot run".

Current state: **74 tests**, green, across `TogetherDecisionsTest`,
`MediaSessionDecisionsTest` and `PlaybackModeDecisionTest`.

Run them **before and after** every change to those files. Practically this
decides how you should write the tasks below: put logic in a framework-free file
and it is checked, put it in a file that touches Compose, `MediaPlayer` or a
service and CI is the first thing that ever builds it.

| Lane | Here? |
|---|---|
| Rust (`fmt`, `clippy -D warnings`, `test --workspace`) | yes |
| `node --test desktop/ui/*.test.mjs` | yes |
| Android decision files via `kotlinc` | **yes — use it** |
| Android via Gradle / anything with an Android import | no, CI first |
| Flutter (`analyze`, `test`, `dart format`) | installable, ~10 min |
| `desktop/src-tauri` clippy | no — fails on missing GTK headers, not your bug |

Do not report a blocked lane as checked. Say plainly which half you verified.

---

## Task A — `TogetherManager` holds a `SessionPlayer` (do this first)

**Everything else is blocked on this**, and it is much smaller than it looks.
Most of the manager already touches only interface members, so widening the type
is nearly the whole job.

`android/app/src/main/java/mullu/comrade/together/TogetherManager.kt`:

| Line | Now | Change to |
|---|---|---|
| 93 | `private var player: TogetherPlayer? = null` | `SessionPlayer?` |
| 199 | `applyCommand(p: TogetherPlayer, …)` | `SessionPlayer` |
| 318 | `run(p: TogetherPlayer, …)` | `SessionPlayer` |

Lines 206, 234, 323–326, 398–404, 508 and 526–528 use only `positionMs`,
`isPlaying`, `prepared`, `seekTo`, `setRate`, `play`, `pause` and
`outputLatencyMs` — all on the interface. They need **no** edit once the types
widen. Check that claim rather than trusting it; if one of them has grown a
`TogetherPlayer`-only call since this was written, that is a finding worth a
note in §14.

**The one call that will break is line 499.**

```kotlin
fun attachSurface(surface: android.view.Surface?) {
    player?.attachSurface(surface)     // attachSurface is NOT on SessionPlayer
}
```

That is correct and deliberate: a surface is meaningful only for the player that
decodes into one. An embed draws into its own `WebView` and an external session
draws in another app's window. Guard it rather than lifting it onto the
interface:

```kotlin
(player as? TogetherPlayer)?.attachSurface(surface)
```

Same for `TogetherPlayer.Listener` — `openPlayer` (437–468) is the file path's
construction and stays exactly as it is. Add a sibling per mode rather than
generalising it; the callbacks (`onPrepared`, `onSeekComplete`, `onVideoSize`)
are `MediaPlayer` semantics and do not survive being made abstract.

**Do not** change `stopPlayback`, the audio-focus handling, or anything touching
`startService`/`stopService` in this task. `.claude/rules/android.md` names the
foreground-service contract as the most bug-prone area in the repo, and mixing a
type-widening with a lifecycle change makes a CI failure unattributable.

**Done when**: the manager compiles against `SessionPlayer`, `TogetherPlayer`
still constructs through `openPlayer`, behaviour is unchanged, and you have said
plainly in the commit that CI is the first build.

---

## Task B — the YouTube embed (highest user value)

This is the only route to "play something neither of us has" that needs no
account on either side. Design and constraints: §11b.

1. **Dependency.** `com.pierfrancescosoffritti.androidyoutubeplayer:core:13.0.0`
   — verified present on Maven Central, `WebView` around the official IFrame
   player, minSdk 21, no permissions. Add it **in the same commit as the adapter
   that uses it**, not before.
2. **`YoutubeSessionPlayer : SessionPlayer`**, wrapping `YouTubePlayerView`.
   - `onCurrentSecond` → `CoarsePlayhead.onTick`; `positionMs` reads
     `estimateMs(now)`. This is the whole reason `CoarsePlayhead` exists — do
     not report the raw last tick.
   - `seekTo` **must** call `CoarsePlayhead.onSeek` as well as the player, or
     the ladder corrects twice for one gap. §11b explains why.
   - `onStateChange` → `TogetherDecisions.embedState(…)`; send only when
     `embedStateIsWorthSending`.
   - `setRate` is a **no-op**. The embed takes discrete rates only, and
     `TogetherContent::Youtube.tuning()` already has `can_rate_trim: false`, so
     the ladder never asks. Do not approximate.
   - `outputLatencyMs` returns `0` — honest for a player we do not own.
   - API signatures are in §11b's source; `onStateChange`, not `onStateChanged`.
3. **Compose surface** in `ui/TogetherScreen.kt`: an `AndroidView` holding the
   player view, inside the existing sleeve `Box` so a video keeps one owner of
   the aspect ratio. Register it as a lifecycle observer or call `release()`.
4. **Routing.** `ChatCommands.playNote`'s `PLAY_EMBED` arm currently refuses
   ("Comrade can't play YouTube here"). It becomes a real session. `ChatsScreen`
   resolves the route at line 1090 (`ComradeCore.playRoute(…)`) and branches on
   it from 1111; `START_TOGETHER` and `ASK_FOR_FILE` are handled there and
   `PLAY_EMBED` currently falls through to the note. Grep for `PlayRoute.` rather
   than trusting these numbers — this file moves.

**Constraint that is not negotiable:** keep the standard embed with its controls
and its ads. YouTube's API Services Terms prohibit hiding the player or
stripping ads, and `docs/TOGETHER.md` §11a records why the ReVanced/InnerTube
route is declined. If ad-free is wanted, the answer is §11a's `Stream` sources,
not a modified embed.

**Worth building alongside it:** ad breaks are per-viewer, so one side gets a
pre-roll and the other does not, and the session desyncs by exactly that much
through no fault of the clock. §9a already flags this. Holding the other side
during an ad break — and saying *"they're in an ad break"* rather than
*"catching up…"* — is the thing that makes a watch party feel unbroken, and no
competitor does it well.

---

## Task C — following an external media session

Design, costs and the Play-policy finding: §13.

1. **`NotificationListenerService`** plus the manifest entry, and a settings
   route to `ACTION_NOTIFICATION_LISTENER_SETTINGS`. The permission cannot be
   requested in-app; the user grants it in system settings, so the UI has to
   explain *before* sending them there and detect the result on return.
2. **`ExternalSessionPlayer : SessionPlayer`** over `MediaController`.
   - Build `MediaSessionDecisions.Candidate` per active session and call `pick`.
   - **`pick` must be given Comrade's own package name.** If it is not, the
     session follows the foreground service's own `MediaSession`, syncs Comrade
     to Comrade, and feeds every correction back into the player that produced
     it. From a bug report it reads as "it randomly jumps". This is the single
     worst trap in this feature.
   - `positionMs` → `MediaSessionDecisions.positionAtMs`, which honours
     `PlaybackState.playbackSpeed`.
   - `PlaybackState.ACTION_SEEK_TO` decides `FULL` vs `START_ONLY`; a
     `START_ONLY` session must not run the ladder.
   - Map `PlaybackState.STATE_*` ints to the strings
     `MediaSessionDecisions` takes, at this boundary and nowhere else — that is
     what keeps the decision file testable.
3. **Track identity.** `sameTrack` compares a caller-built key; build it from
   `METADATA_KEY_TITLE` + `METADATA_KEY_ARTIST`. Blank is not a match.
4. **Speed disagreement** (`speedsAgree`) is not fixable by seeking — say so and
   stop claiming, rather than chasing a gap that reopens as fast as it closes.

**Hold this line:** the feature is source-agnostic and must stay that way in the
code *and* in the copy. Do not name ReVanced, Morphe or any patched client in
strings, docs, changelog or store listing. §13 explains why the distinction
between a neutral tool and an induced one matters regardless of what the code
does.

---

## Task D — play a handed-over file before it finishes arriving (independent)

Not blocked by Task A; can run in parallel. §12.

Core has been ready since the transfer landed: `ShareTracker::playable_at` and
`runway_ms` are tested and are dead code. Android wants a `MediaDataSource`
(API 23+, `minSdk` is 26) whose `readAt` blocks on bytes not yet arrived.

**Decide the shared rule first**, because a stall handled two ways on two
devices is a session that argues with itself: §10 says a stall is never
signalled to the peer, so a starved reader pauses locally and the next drift
verdict closes the gap. Write that into §12 before either frontend implements
it.

---

## Decisions not to silently reverse

- **`admissible` matches exhaustively.** A new `TogetherContent` variant must
  decide whether its peer-supplied strings are safe. The two `if let … Youtube`
  arms it replaced are how a variant could have reached a `src` attribute
  unnoticed.
- **Buffering is never reported as a pause** — enforced in two places now
  (`embedState`, `MediaSessionDecisions.stateIsWorthSending`). §10.
- **The mode never changes mid-session** (`mayChangeMidSession`). §14.
- **A file we hold is always ours**, even with an external session available;
  a file we do not hold is *nothing yet*, not external. §14.
- **`direct_ready` is a claim with an expiry**, not a fact. Do not add a
  frontend-side timeout on top; core's watchdog is the single authority.
- **Apple Music is never `Full`.** No precise scheduling in MusicKit. §11.
- **Never "synced" or "in sync"** anywhere in copy. §7, `docs/PRESENCE.md` §5.

---

## Open questions — owner decisions, do not guess

1. **The Spotify route is still unanswered.** App Remote is *not* on Maven
   Central; the options are a vendored `.aar`, JitPack, or the Web API over
   HTTPS. §11 lays them out and recommends the Web API. **Task C may make this
   moot** — following Spotify's own media session needs no client id, no OAuth
   and no SDK at all. Do Task C first and re-ask.
2. **Where a Spotify client id lives**, if the Web API route is taken. Asked;
   answered "no preference". Do not commit a placeholder that looks real.
3. **Play Store notification-listener acceptance.** Confirmed *not* on the
   restricted-permissions list (unlike Accessibility, which is gated), so no
   declaration form. That is not approval. Worth a real submission check before
   Task C ships.

---

## Branch note

This document was written on `claude/together-mode-analysis-fixes-v89y5l` after
PR #101 merged. The branch could not be reset onto the merge commit — the
sandbox's permission classifier blocked both `checkout -B` and `merge --ff-only`
— so it sits one commit behind `origin/main` with identical file content. Start
your work by taking the branch from `origin/main` properly; do not stack on the
pre-merge tip.
