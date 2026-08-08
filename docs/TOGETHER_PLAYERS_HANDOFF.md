# Together — players handoff

_Written 2026-08-08 for the agent (or human) picking this up after PR #101
merged. Read **Verification** before you write anything; it is the part that
changes how you work, not just what you claim._

> **Status, 2026-08-08 (second pass): Tasks A, B, C and D are done.** Everything
> below is kept as written, with the outcome and the corrections marked inline,
> because three of the plan's confident claims turned out to be wrong and *how*
> they were wrong is the reusable part. The short version:
>
> | | Outcome |
> |---|---|
> | **A** — manager holds a `SessionPlayer` | done; two narrowing sites, not one |
> | **B** — YouTube embed | done; **the named library version would have failed CI** |
> | **C** — external media session | done; entry point is narrower than the plan implied |
> | **D** — play a file while it arrives | done on Android + core; desktop has the numbers, not the plumbing |
>
> Android decision tests: **94**, green, up from 74. Desktop JS: **417**, green.
> Everything touching Compose, `MediaPlayer`, `MediaDataSource`, `MediaController`
> or the manifest is **unverified here** — CI is its first build, and this
> document is the wrong place to imply otherwise.

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

Current state: **94 tests**, green, across `TogetherDecisionsTest`,
`MediaSessionDecisionsTest`, `PlaybackModeDecisionTest` and — added in the second
pass — `ShareReadPolicyTest`, which lives under `transfer/` rather than
`together/`.

That last one is worth a sentence, because it is the rule restated as a
constraint. `ShareDecisions` **cannot** run under `kotlinc`: it imports
`uniffi.comrade_core`, so it needs Gradle's generated bindings and therefore the
Android SDK. Its neighbour `ShareReadPolicy` imports *nothing at all* and runs in
a second. The thresholds went in the second file for exactly that reason — "no
Android imports" is not the bar, **no imports** is.

**There is one more thing this sandbox can do for a dependency it cannot compile
against**, and it caught a real failure in Task B: download the `.aar` from Maven
Central and read the API with `javap`. Signatures, enum members, constructor
arity, all of it — checked rather than assumed, against the exact version the
build will resolve. Do this before writing an adapter against a library that only
CI will ever link.

> **Superseded, mostly, and by something better.** The `javap` trick was the
> answer to "I cannot compile against this library". As of 2026-08-08 you can:
>
> ```bash
> .claude/scripts/android-typecheck.sh
> ```
>
> **84 of the 87 sources under `mullu/comrade/` type-check with no Android SDK** —
> against a real `android.jar` (Robolectric's `android-all` for API 34), the real
> generated uniffi bindings (`cargo` makes those; only `sdkmanager` was ever
> missing) and the real AARs for WebRTC, Vosk and the YouTube player. Only the
> files importing `androidx.compose` are left out.
>
> **This exists because the first push of Tasks B–D failed CI on one line.**
> `refreshLive(durationMs = …)` against a function with no such parameter — a
> typo, in a file the JUnit lane cannot see because `TogetherManager` imports
> Android, costing a full push/build round trip to find. The script reproduces
> that exact error, at that exact line, in about two minutes; it was checked by
> putting the bug back and watching it go red, because a check that cannot fail
> is not a check.
>
> It answers one question — does this Kotlin resolve and typecheck. `./gradlew
> test` still needs the SDK, Compose is still CI-first, and `res/`/manifest
> correctness is still CI's.

Run them **before and after** every change to those files. Practically this
decides how you should write the tasks below: put logic in a framework-free file
and it is checked, put it in a file that touches Compose, `MediaPlayer` or a
service and CI is the first thing that ever builds it.

| Lane | Here? |
|---|---|
| Rust (`fmt`, `clippy -D warnings`, `test --workspace`) | yes |
| `node --test desktop/ui/*.test.mjs` | yes |
| Android decision files via `kotlinc` | **yes — use it** |
| Android **type-check**, everything but Compose | **yes** — `.claude/scripts/android-typecheck.sh` |
| Android via Gradle, and any Compose file | no, CI first |
| Flutter (`analyze`, `test`, `dart format`) | installable, ~10 min |
| `desktop/src-tauri` clippy | no — fails on missing GTK headers, not your bug |

Do not report a blocked lane as checked. Say plainly which half you verified.

---

## Task A — `TogetherManager` holds a `SessionPlayer` — **DONE 2026-08-08**

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

`openPlayer` (~437–468) stays the **file path's** construction — add a sibling
per mode rather than generalising it; the callbacks (`onPrepared`,
`onSeekComplete`, `onVideoSize`) are `MediaPlayer` semantics and do not survive
being made abstract.

> **Correction, 2026-08-08.** This section originally said `openPlayer` "stays
> exactly as it is". That was wrong. Its reuse arm — `val p = player ?:
> TogetherPlayer(ctx)` — takes its type from the left operand, so widening the
> field widens the local and `setListener`/`open` stop resolving. It narrows
> with `as?` too. Two narrowing sites, not one. Left visible rather than edited
> away, because "the plan was confident and the compiler disagreed" is the
> useful part.

**Do not** change `stopPlayback`, the audio-focus handling, or anything touching
`startService`/`stopService` in this task. `.claude/rules/android.md` names the
foreground-service contract as the most bug-prone area in the repo, and mixing a
type-widening with a lifecycle change makes a CI failure unattributable.

**Done when**: the manager compiles against `SessionPlayer`, `TogetherPlayer`
still constructs through `openPlayer`, behaviour is unchanged, and you have said
plainly in the commit that CI is the first build.

---

## Task B — the YouTube embed (highest user value) — **DONE 2026-08-08**

This is the only route to "play something neither of us has" that needs no
account on either side. Design and constraints: §11b.

1. **Dependency.** ~~`com.pierfrancescosoffritti.androidyoutubeplayer:core:13.0.0`~~
   — **`12.1.2`.** Verified present on Maven Central, `WebView` around the
   official IFrame player, minSdk 21, no permissions. Add it **in the same commit
   as the adapter that uses it**, not before.

   > **Correction, 2026-08-08.** 13.0.0 *is* on Maven Central, which is what was
   > checked and it was not enough. Its POM pulls
   > `androidx.lifecycle:lifecycle-runtime-ktx:2.9.4` — compiled against API 35,
   > and AGP fails a `compileSdk = 34` build for it outright — and
   > `kotlin-stdlib:2.1.0`, whose metadata the pinned 1.9.22 compiler refuses
   > with "compiled with an incompatible version of Kotlin". Neither shows up in
   > "is it published?". 12.1.2 carries `lifecycle-runtime-ktx:2.6.0` and
   > `kotlin-stdlib-jdk8:1.8.0`, and its API surface for everything used here is
   > identical — checked class by class with `javap` against both AARs.
   >
   > **The general lesson: for a dependency, check the POM and the classes, not
   > the version list.** Both are one `curl` away in this sandbox, and the
   > failure they prevent is a red CI run on the frontend that cannot be built
   > locally.
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

> **Built, and half of that ask had to be refused.** `TogetherDecisions.StallWatch`
> holds the other side: it observes that the player claims to be playing while
> the position it reports stands still, the embed's `isPlaying` goes false, and
> the poll turns that into `together_report_position(pos, false, …)` — a
> heartbeat the peer answers by holding. Ten tests, green here.
>
> **The sentence could not be built honestly.** An ad and an unlabelled stall are
> the same observation from inside the embed, so *"they're in an ad break"* would
> be a claim over an inference — and the peer could not be told either way,
> because the heartbeat carries no reason field. Adding one is a protocol change,
> and it is left as an open question rather than invented. What the peer sees is
> a device that is not playing, which is honest and less informative than the ask.
>
> Two constants ended up related on purpose: `STALL_AFTER_MS` (3 s) is **one tick
> wider** than `COARSE_EXTRAPOLATE_MAX_MS` (2 s), so the estimate has already
> gone flat, and been seen to go flat, before anything is called a stall. There
> is a test asserting the inequality, because it would otherwise survive as a
> coincidence.

---

## Task C — following an external media session — **DONE 2026-08-08**

Design, costs and the Play-policy finding: §13.

> **What the plan did not say, and a reader needs.** It lists the pieces but not
> how a session *starts*, and the answer is narrower than the list implies:
> **Comrade follows, it does not start.** There is no way to tell another app's
> `MediaSession` "play track X" — only to drive what is already loaded — so the
> entry point built is the *invited* side. An invitation to something this device
> cannot play itself offers "follow what's playing here", and
> `PlaybackModeDecision.ownershipFor` decides whether that is available. Both the
> button and the action ask it, so they cannot disagree about when it appears.
>
> Starting a session **from** what this phone is playing would need a
> `TogetherContent` variant for "whatever is on now" — a wire change that ripples
> into the Kotlin and Dart exhaustive matches `CLAUDE.md` warns about — so it is
> not made here. `PlaybackModeDecision` already anticipates the string
> (`now_playing`); the variant behind it does not exist.

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

> **Two traps found in the building that the list does not mention.**
>
> `state.actions and PlaybackState.ACTION_SEEK_TO != 0L` **does not do what it
> reads like**. Kotlin's infix `and` binds looser than `!=`, so it parses as
> `actions and (ACTION_SEEK_TO != 0L)`. It happens not to typecheck, which is the
> lucky outcome; the same shape with an `Int` mask compiles and is always wrong.
> Parenthesise.
>
> `MediaController.registerCallback(callback)` posts to the **calling thread's**
> `Looper` and throws when there is none. It is main-thread today, which is
> exactly the kind of thing that quietly moves to a background coroutine later —
> pass an explicit `Handler(Looper.getMainLooper())`.
>
> And one decision that went into the pure file rather than the boundary:
> `trackKey`. Which fields identify a track is a *decision*, not a read. Title +
> artist, blank title yields a blank key which `sameTrack` refuses — but a
> missing **artist** is a weaker key, not silence, because podcast apps routinely
> publish none and refusing them would switch off sync for a whole category of
> app that works fine.

**Hold this line:** the feature is source-agnostic and must stay that way in the
code *and* in the copy. Do not name ReVanced, Morphe or any patched client in
strings, docs, changelog or store listing. §13 explains why the distinction
between a neutral tool and an induced one matters regardless of what the code
does.

---

## Task D — play a handed-over file before it finishes arriving — **DONE 2026-08-08**

Not blocked by Task A; can run in parallel. §12.

Core has been ready since the transfer landed: `ShareTracker::playable_at` and
`runway_ms` are tested and are dead code. Android wants a `MediaDataSource`
(API 23+, `minSdk` is 26) whose `readAt` blocks on bytes not yet arrived.

~~**Decide the shared rule first**~~ — **done.** `share::read_verdict` and
`ComradeRuntime::share_read_verdict` are the policy; §12 has the numbers and the
reasoning. What remains:

- ~~**FFI**~~ — **done for uniffi, deliberately not for frb.**
  `Comrade::share_read_verdict` is exposed, synchronous, stateless and
  lock-free — which is the point, since the only place Android wants it is inside
  `readAt`, where a lock on the runtime would deadlock against the transfer
  writing the chunk being waited for. `comrade_ui` re-exports the free function
  as `share_read_verdict` so the bridge does not have to go through a
  `&self` method to reach it.

  The `#[frb(mirror(ReadVerdict))]` half is **left undone on purpose, and this is
  the reasoning to argue with rather than a thing forgotten**: `app/` has no
  together or share surface at all, so it would mean regenerating the entire
  bridge for a type no Dart code references, and the regeneration cannot be
  verified here without a ~10-minute Flutter install. It belongs in the commit
  with the first Dart consumer, where it can be checked by something.
- ~~**The numbers**~~ — **done on both frontends.**
  `desktop/ui/share_transfer.mjs` has `STALL_FLOOR_MS`, `readVerdict`,
  `tailCompleteAt` and `readVerdictAt`, with core's vectors ported and a walk
  over all 1024 arrangements of a ten-chunk file asserting the verdict never
  disagrees with `playableAt`. Android has `transfer/ShareReadPolicy.kt`.

  > **"Android has no tracker yet" was wrong.** `ShareDecisions.Tracker` shipped
  > with the transfer and already had `playableAt`, `runwayMs` and `chunkAtMs`.
  > What it lacked was `tailCompleteAt` and the verdict. `playableAt` is now
  > built **on** `tailCompleteAt` rather than beside it, the same construction
  > core uses, so the two answers cannot drift.
- ~~**The trap to hold**~~ — held. `TogetherManager.applyShareVerdict` pauses the
  local player and nothing else; the next line of the poll reports
  `together_report_position(pos, false, latency)`. Resuming is conditional on the
  session wanting to play, because `Start` is permission and not an instruction.

**Two things the wiring turned up that no plan predicted.**

**`FileTransfer.onChunk` recorded the chunk before writing its bytes**, and that
was fine for exactly as long as nothing read the partial file. The moment a
decoder does, the order leaves a window where the bitmap says the bytes are there
and the file still holds zeroes — and a decoder that reads zeroes does not stall,
it produces a corrupt frame or abandons the file, neither of which looks like a
transfer bug from a report. Write first, record second; the `synchronized` block
in `readAt` then publishes the bytes wherever the flag is visible.

**Reopening the finished file reset the playhead to zero.** `MediaPlayer` starts
at the beginning, so the transfer *completing* threw the listener back to the
start of a track they were halfway through — a regression that only exists once
early playback works, which is the kind that ships. The position is carried
across, and the seek arms the echo suppressor, because an unexplained
`onSeekComplete` is re-broadcast as the user having seeked.

**Desktop still has no plumbing**, only the numbers: the Tauri custom protocol
with `Range` support that would feed a `<video>` element is not built.

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

1. ~~**The Spotify route is still unanswered.**~~ **Task C did make it moot for
   the following case, and the question is now smaller and different.** Following
   Spotify's own media session needs no client id, no OAuth and no SDK, and that
   is built — so a listener who plays a track in Spotify can be followed. What
   the media-session route cannot do is *start* a named track, because there is
   no "play track X" on another app's transport controls. So the remaining
   question is only about the **inviting** side: is "open Spotify and press play,
   then tap follow" acceptable, or is starting a track on the listener's own
   subscription worth the App Remote `.aar`/Web API cost after all? §11 lays out
   the options and recommends the Web API.
2. **A `TogetherContent` variant for "whatever this phone is playing".** What
   Task C would need to start a session *from* an external player rather than
   only join one with it. `PlaybackModeDecision` already takes the string
   (`now_playing`); the wire variant does not exist, and adding one ripples into
   the Kotlin and Dart exhaustive matches `CLAUDE.md` warns about — so it is a
   deliberate ask rather than a small follow-up.
3. **A reason on the heartbeat.** Task B holds the peer during an ad break but
   cannot tell them *why*: `together_report_position` carries no reason, so a
   held device is indistinguishable from a paused one. Adding one is a protocol
   change and §10 has strong views about what may be sent, so it wants an
   argument rather than a field.
4. **Where a Spotify client id lives**, if the Web API route is taken. Asked;
   answered "no preference". Do not commit a placeholder that looks real.
5. **Play Store notification-listener acceptance.** Confirmed *not* on the
   restricted-permissions list (unlike Accessibility, which is gated), so no
   declaration form. That is not approval. Worth a real submission check before
   Task C ships. **Still open, and now blocking a shipped feature rather than a
   planned one** — the manifest declares the listener as of 2026-08-08.

---

## What is left, after the second pass

Nothing in Tasks A–D. What the four tasks *revealed* rather than resolved:

- **Desktop early playback** — the numbers are ported and tested, the Tauri
  custom protocol with `Range` support is not built. `desktop/src-tauri` is a
  lane this sandbox cannot compile at all (missing GTK headers), so it wants a
  session that can, or an unusually careful one.
- **The frb mirror for `ReadVerdict`**, with the first Dart consumer.
- **The three open questions above**, all of which are owner decisions.
- **What is still CI-first, now that the boundary has moved.** `TogetherScreen`
  (Compose), the manifest entry and the Gradle dependency resolution have never
  been built here. Everything else added in this pass —
  `YoutubeSessionPlayer`, `ExternalSessionPlayer`, `MediaSessionAccess`,
  `MediaSessionListenerService`, `PartialFileDataSource`, `ShareReadPolicy` and
  the `TogetherManager`/`FileTransfer`/`TogetherPlayer` changes — **type-checks
  locally** via the script above, on top of the 94 JUnit tests. That still is not
  "the feature works": nothing here has run a `MediaPlayer`, followed a real
  `MediaSession` or drawn a frame.

## Branch note

This document was written on `claude/together-mode-analysis-fixes-v89y5l` after
PR #101 merged. The branch could not be reset onto the merge commit — the
sandbox's permission classifier blocked both `checkout -B` and `merge --ff-only`
— so it sits one commit behind `origin/main` with identical file content. Start
your work by taking the branch from `origin/main` properly; do not stack on the
pre-merge tip.

_The second pass ran on `claude/together-players-handoff-docs-b3hsmv`, taken
cleanly from `origin/main` at `57dc677`._
