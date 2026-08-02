# Call UX overhaul — handoff

_Branch: `claude/voice-video-call-ux-5up39x` · written 2026-07-29, for the next
agent (or human) picking this up. Read this top to bottom before touching
anything; the **Verification status** table is the most important part._

## Post-merge fixes (round 2) — read this first

Two bugs were reported from real use after PR #52 merged, and both were design
mistakes in that first pass rather than typos. They are fixed on all three
frontends; the notes below are what not to reintroduce.

**1. "The chat icon minimises the video but doesn't open the chat."** Correct
report, and the cause was choosing the wrong kind of picture-in-picture. The
chat button asked for **OS-level PiP** (Android `enterPictureInPictureMode`;
browser PiP on the desktop `<video>`). An OS PiP window *leaves the app*: on
Android the conversation the shell had just opened sat behind the launcher, and
on desktop the picture moved into its own window while the opaque full-screen
call overlay stayed put, covering the thread. Either way the button looked like
"shrink the video and go nowhere".

Only one window can show a call *and* one of our own screens at the same time —
ours. So the chat button now always shrinks the call into an **in-app floating
tile**:

- Dart: `CallController.openChat()` sets `CallPipMode.inApp` unconditionally and
  never calls `PipChannel.enter()`.
- Compose: `PipController.minimizeInApp()` + a new `MinimizedCallTile`, which is
  deliberately **not** wrapped in `CallOverlay` (that composable's opaque
  full-screen background would hide the conversation and swallow its taps).
- Desktop: `minimizeCall()` adds `.is-minimized` to `#call-active` (CSS shrinks
  it to a corner tile); browser PiP is no longer on this path.

Native/OS PiP still exists and is still right for the case it was built for:
**leaving the app** during a video call (`PipController`'s auto-enter). Don't
wire the chat button back to it.

The tile defaults to the **top**-right on every frontend, which is also a bug
fix: the bottom-right of a conversation is the send button, and a call tile
parked on top of it defeats the whole point of the mode.

**2. "If one person pauses video the other should continue."** Two independent
causes, both fixed:

- *The paused surface was swapped out, not covered.* Replacing the video widget
  with a placeholder unmounts the renderer, and unmounting a renderer detaches
  its sink from the track (Flutter `CallVideoRenderer.dispose`, Compose
  `SurfaceViewRenderer.release`). The frames that arrive when the peer un-pauses
  then have nowhere to land until a brand-new renderer has been built and
  re-attached — which is how a pause on one side could leave the other side's
  picture not coming back. Both now keep the surface mounted and draw an opaque
  cover over it (`_VideoSurface` in Dart, `CallVideoSurface` in Compose), which
  is what the desktop already did with `#call-video-paused`. Resuming is instant
  because nothing was ever torn down.
- *`AppLifecycleState.inactive` was treated as "hidden".* Android reports
  `inactive` for every transient loss of focus — the app switcher, a system
  dialog, the notification shade, and the instant before a PiP transition
  confirms. Suspending capture there told the peer "Video paused" because
  someone glanced at their notifications, and it raced the PiP callback. Only
  `paused`/`hidden`/`detached` count as backgrounded now.

Regression tests pin both: `app/test/call_screen_test.dart` has "never hands the
window to the OS — that would hide the chat", "one side pausing leaves the other
side alone", "a glance at the notification shade does not pause the camera", and
the two surfaces are asserted to stay mounted while covered.

**Verification status of this round-2 commit: CI only.** The sandbox that wrote
it could not run `flutter analyze`/`flutter test` (the tool that executes them
was unavailable for the whole session), so unlike round 1 these changes were
never run locally — the GitHub Actions lanes are the first thing that executes
them. If the Flutter lane is red on this commit, that is why, and the failure is
most likely mechanical (an import, a renamed key) rather than a design problem.
Round 1's lesson applies: check the *Compose* module too, since nothing local
compiles Kotlin either.

## What was asked for

1. Replace the 4-emoji SAS verification with **Telegram-style signal-strength
   bars** on voice/video calls.
2. An in-call **chat button** that opens the conversation and shrinks the call
   out of its way (see the round-2 note above: an in-app tile, *not* OS PiP).
3. PiP when **leaving the app** during a video call.
4. When the video is not displayed anywhere, show **"Video paused"** and **do
   not capture** from the camera.
5. Mute (and camera) buttons animate an explicit **slash across the glyph**.
6. **FaceTime-style chrome**: video call opens full screen; controls fade
   after a few seconds; tap to bring them back.
7. Ongoing-call **notification buttons** (Google-Phone style): just *End call*
   before answer; *End call + mute + audio route (earpiece/speaker/Bluetooth)*
   once active.
8. General "implement features from other apps" polish.

## What landed (this branch)

### Dart / Flutter (`app/`) — VERIFIED (analyze + 124 tests green)

- `lib/src/widgets/signal_bars.dart` — `signalBarsFor/signalLabelFor/
  signalColorFor` + `SignalStrengthBars` + `ConnectionStrengthIndicator`.
  Mapping: good=4, medium=2, poor=1, unknown=0 bars (unknown draws nothing and
  says nothing — never invent a reading).
- `lib/src/widgets/slashed_icon.dart` — animated slash painted *over* the
  glyph (cutout stroke under tint stroke), 220 ms.
- `lib/src/platform/pip_channel.dart` — `isSupported/enter/close` +
  `modeChanges` (bool stream, snapshot-on-subscribe). Every method degrades to
  "no PiP" instead of throwing, so desktop/tests take the in-app-tile path.
- `lib/src/platform/call_channel.dart` — `sasEmojis` dropped from
  `CallSnapshot`; `videoSuspended`/`remoteVideoPaused` added;
  `setVideoCaptureSuspended(bool)` method added.
- `lib/src/state/call_providers.dart` — `CallPipMode {none,native,inApp}`;
  `CallSession.{pip,videoSuspended,remoteVideoPaused,localVideoPaused}`;
  controller gains `openChat()` (native PiP first, in-app tile fallback),
  `onNativePipChanged`, `restoreFromPip`, `onAppLifecycleChanged` (suspends
  capture when backgrounded *unless* in native PiP), `_end` closes a live PiP
  window. `CallEngine` interface gains `setVideoCaptureSuspended`.
- `lib/src/screens/call_screen.dart` — full new UX: self-hiding chrome
  (4 s linger / 320 ms fade, `Key('call-stage')` is the tap target), signal
  bars replace the SAS row, slashed mute/camera, "Video paused" placeholders
  (`Key('video-paused')`), chat button (`Key('call-chat')`),
  `_NativePipContent` (remote picture only while OS-PiP), `FloatingCallTile`
  (draggable in-app tile with mute/hang-up), immersive system UI during video.
- `lib/src/screens/home_shell.dart` — `CallOverlay(onOpenChat: …)` opens the
  conversation (reusing alias/@handle from `conversationsProvider`).
- `test/call_screen_test.dart` — 14 new tests: bar mapping, SAS row absent,
  chrome fade/tap cycle, audio-call chrome persistence, video-paused for both
  sides, capture-suspension lifecycle (incl. "PiP counts as visible" and
  "audio calls never suspend"), chat-button → in-app tile → restore, native
  PiP waits for OS confirmation, PiP closed on hangup, mute keeps `Icons.mic`.

### Desktop SPA (`desktop/ui/`) — VERIFIED (50 node tests green; DOM glue unrun)

- `call_decisions.mjs` — `formatSas` **removed**; added `CALL_QUALITY`,
  `classifyCallQuality` (same RTT/jitter thresholds as `CallManager.kt`:
  ≤150 ms + ≤30 ms jitter = good, ≤400 ms = medium, else poor; unparseable =
  unknown), `signalBarsFor`/`signalLabelFor` (identical mapping to the other
  two frontends), `remoteVideoFramesDecoded`, `decideRemoteVideoPaused`
  (2-stalled-poll hysteresis), `REMOTE_VIDEO_STALL_POLLS`.
- `call_decisions.test.mjs` — the 6 `formatSas` tests replaced by 20 vectors
  covering the thresholds (incl. both inclusive boundaries), the
  candidate-pair fallback, worst-stream-wins, unknown-never-poor, junk-report
  tolerance, the bar/label mapping, and the pause hysteresis + instant clear +
  counter-reset case. **50/50 pass** (`node --test desktop/ui/*.test.mjs`).
- `index.html` — `#call-sas` row replaced by `#call-signal` (four `<i>` bars +
  label), `#call-video-paused` cover, and two new buttons (`#call-camera`,
  `#call-chat`); button glyphs wrapped in `.call-btn-glyph` for the slash.
- `styles.css` — `.call-sas*` replaced by `.call-signal`/`.call-bars`
  (`data-filled` lights the first N, empties stay at 0.25 opacity),
  `.call-video-paused`, and `.call-btn.is-off .call-btn-glyph::after` (the
  animated slash, `call-slash-in` keyframes).
- `main.js` — `updateCallSas`/`renderSasResult`/`resetSasRow` and the
  `call_sas` invoke + preview stub removed; added `startStatsPolling`
  (2 s `getStats`, liveness-guarded before *and* after every await, restarted
  on each connected transition so an ICE restart re-baselines),
  `renderSignal`, `renderVideoPaused`, `toggleCamera`, `openChatDuringCall`
  (`switchTab("vault")` + `selectContact` + browser PiP on the remote
  `<video>`), `enterPictureInPicture`/`exitPictureInPicture`,
  `applyVideoVisibility` (`visibilitychange` + PiP enter/leave → disable the
  local video track when nothing displays it; PiP counts as displayed), and
  the mute button now toggles `.is-off` instead of swapping 🎙 for 🔇.

### Kotlin — WRITTEN BUT NOT COMPILED (no Android SDK in this container)

Shared services (`android/app/src/main/java/mullu/comrade/…`, staged into the
Flutter app by `app/android/app/build.gradle.kts`):

- `call/CallManager.kt` — SAS state/derivation removed (`sasEmojis`,
  `maybeDeriveSas`, `Session.{localSdp,remoteSdp}`); new
  `videoSuspended`/`remoteVideoPaused` StateFlows;
  `setVideoCaptureSuspended(Boolean)`; `applyCaptureState` reconciles
  user-intent (`cameraOn`) × visibility (`videoSuspended`) — capture runs only
  when both allow (`Session.capturing` tracks what was last applied);
  remote-pause detection from `framesDecoded` in the existing 2 s stats poll
  (2 stalled polls ≈ 4 s to set, any growth clears instantly).
- `call/PipController.kt` (new) — toolkit-free singleton: `isSupported/enter/
  close/shouldAutoEnter/applyAutoEnter/onUserLeaving/onPipModeChanged/
  onWindowVisibilityChanged` + `inPip: StateFlow<Boolean>`. Aspect ratio
  clamped to Android's 1:2.39 band. `onWindowVisibilityChanged` is where
  "don't record what nobody displays" is enforced natively (PiP counts as
  visible).
- `call/CallWidgets.kt` (new, Compose) — `SignalStrengthBars`,
  `ConnectionStrengthIndicator`, `SlashedIcon` (Compose twins of the Dart
  widgets, same numbers).
- `call/CallScreen.kt` (Compose) — same new UX as the Flutter screen: chrome
  auto-hide (`CHROME_LINGER_MS = 4_000`), bars instead of SAS, slashed
  mute/camera, "Video paused" drawn *over* the renderer by `CallVideoSurface`,
  chat button → `onOpenChat` + `PipController.minimizeInApp()`,
  `MinimizedCallTile` for that mode, `PipVideoContent` while in OS PiP.
- `call/CallService.kt` — notification actions: always CallStyle *Hang up*;
  when Active also **mute/unmute** and **audio route** (label = current route,
  tap cycles via `CallManager.cycleAudioRoute`). Re-posts on
  `combine(state, muted, audioRoute)` change; `notify` wrapped in
  `runCatching` (POST_NOTIFICATIONS revocation post-33).
- `CallActionReceiver.kt` — `ACTION_TOGGLE_MUTE` / `ACTION_CYCLE_ROUTE`.
- `ComradeCore.kt` — `callSasTyped` removed (Rust `derive_sas` + uniffi
  binding deliberately kept; nothing calls it from Kotlin now).
- `MainActivity.kt` (Compose app) — PiP wiring: attach/detach,
  `onUserLeaveHint`, `onPictureInPictureModeChanged`, visibility →
  `PipController.onWindowVisibilityChanged` in `onStart`/`onStop`; chat
  button handler opens `ChatNav.Open` then enters PiP.
- Manifest (both apps) — `supportsPictureInPicture` + `resizeableActivity` +
  `configChanges` incl. `screenSize|smallestScreenSize|screenLayout|
  orientation` so the PiP resize does NOT recreate the Activity mid-call.
- `strings.xml` — `call_sas_*` and `call_weak_connection` removed; added
  `call_weak_signal`, `call_poor_connection`, `call_signal_strength`,
  `call_signal_measuring`, `call_video_paused`, `call_chat`, `call_open_chat`,
  `call_you`.

Flutter-host Kotlin (`app/android/…/mullu/comrade/`):

- `channel/CallChannel.kt` — snapshot now carries `videoSuspended` +
  `remoteVideoPaused` (nested 5+2+3 `combine`); `setVideoCaptureSuspended`
  method; `callSas` method and `sasEmojis` field removed.
- `channel/PipChannel.kt` (new) — thin facade over `PipController`
  (`mullu.comrade/pip` + `…/pip/state` via `EventChannelRelay`).
- `channel/ChannelNames.kt`, `ComradePlugin.kt`, `MainActivity.kt` — PiP
  channel registered; Activity forwards PiP callbacks to `PipController`;
  auto-enter kept in step with call state via `collectLatest`.
- `PLATFORM_CHANNELS.md` §5 updated to the new wire contract.

## Verification status — be honest about this in the PR

| Layer | Status |
|---|---|
| Dart analyze (`--fatal-infos`) | ✅ clean |
| `dart format --set-exit-if-changed` | ✅ clean |
| `flutter test` (fake repo, no FFI) | ✅ 124 pass (4 FFI tests self-skip; CI `bridge` job runs them) |
| Kotlin — Flutter host module (`app/android`) | ✅ **compiled in CI**: the `Flutter` workflow (analyze/bridge/linux/**apk**) went green on dc05539, and its APK lane builds `app/android` *plus* the staged legacy services — so `CallManager`, `PipController`, `PipChannel` and `CallChannel` all compile |
| Kotlin — Compose module (`android/`) | ⚠️ first run failed on a real bug in my `CallWidgets.kt` (missing `androidx.compose.runtime.getValue` for `by animateFloatAsState`, which cascaded into four confusing "Float.times ambiguity" errors); fixed in 0048079. Re-run pending at the time of writing — **check it** |
| Rust | untouched (`derive_sas` + tests intentionally kept) |
| Desktop JS (`node --test desktop/ui/*.test.mjs`) | ✅ **54 pass** (pure decision layer, incl. `shouldSendLocalVideo`). The remaining DOM/WebRTC glue in `main.js` has no test harness — unrun |
| On-device behaviour | ❌ nothing run on a device/emulator |

## Next steps, in order

1. **Finish getting CI green.** `Flutter` is green. The `CI` workflow's
   "Android — JVM unit tests" job and the `Android APK` workflow both failed
   once on the `CallWidgets.kt` missing-import bug (fixed in 0048079); confirm
   the re-run passes. If the JVM test job fails on something else, note that
   `toggleCamera`'s shape changed (it now records intent and defers to
   `applyCaptureState`) — a test that asserted capturer start/stop calls
   directly may need re-reading against the reconciliation, though none did at
   the time of writing. The two device-test jobs only failed because the APK
   never built; they should recover with it.

   A useful trick learned here: a Kotlin "overload resolution ambiguity on
   Float.times" is often *not* an arithmetic problem — it is a `by` delegate
   that failed to resolve (missing `getValue`), making the delegated value an
   error type at every use site.
2. **Desktop manual pass** (needs the Tauri shell, or a browser preview):
   the pure layer is tested but the DOM glue is not. Check that the bars
   appear on connect and move with the network, that the chat button opens the
   thread *and* the PiP window (a webview that refuses `requestPictureInPicture`
   should leave the call full screen, not break it), that minimising the window
   stops the outbound video and restoring resumes it, and that the mute slash
   animates. Note `enterPictureInPicture` is called from a click handler, so
   the user-gesture requirement is satisfied — if a webview still refuses,
   that is the documented degrade path.
3. **Device pass** (needs hardware/emulator): ring → accept → chrome fades →
   tap reveals; home during video call → OS PiP window; chat button → tile over
   conversation; camera-off vs backgrounding both show "Video paused" on the
   peer; notification shows End (+ Mute/Route once active) and the route
   button cycles; return from PiP resumes capture only if camera was on.
4. **`SlashedIcon` on the Compose side** uses `size * 0.44f` inside a 60 dp
   disc — eyeball the slash endpoints against the Flutter one on device.
5. **Docs**: done — the `README.md` calls row now describes the indicator, PiP,
   video-paused, self-hiding controls and notification controls. `AUDIT.md` was
   grepped and has no SAS references. The Tauri `call_sas` command and
   `ComradeRuntime::call_sas` are deliberately retained with a doc note saying
   no frontend calls them.
6. Consider surfacing `videoSuspended` in the *peer's* UI copy ("Paused — they
   left the app") — the wire only says paused; a `reason` field on the
   heuristic is a natural follow-up.

## Design decisions you should not silently reverse

- **SAS removal is a product decision** taken on the explicit instruction of
  the owner (this task). The crypto (`comrade_core::call::derive_sas`) and its
  tests stay — only the UI surfacing was removed. Rationale recorded in the
  file headers: SDP fingerprints already ride the NIP-44 gift-wrapped,
  key-authenticated DM channel.
- **`cameraOn` × `videoSuspended` are two facts.** Do not merge them: merging
  makes a backgrounded call come back with the camera silently on (or off).
- **Dart never decides PiP entry on leave-app.** That must beat `onStop`, so
  it is native (`setAutoEnterEnabled` / `onUserLeaveHint`). Dart only asks
  (`enter()`) and listens (`modeChanges`).
- **`CallPipMode.native` is set only from the OS callback**, never at request
  time — the OS can refuse, and the user can drag the window back.
- **Unknown quality = empty bars, no label.** Never map unknown to poor.

## Post-merge fixes (round 3): the bar could not hold what was in it

Reported: **"end call button is missing after answering video call"**, plus a
request for Telegram's shape — a ⋮ that opens a dock, and the bar reordered.

### The bug

The in-call bar was a single non-wrapping row of *every* control. On a video
call that was six 60dp buttons — mute, camera, screen share, chat, audio route,
End — needing ~450dp before padding, on a phone that gives ~360dp. Compose's
`Row` does not wrap: it lays children out in order and the ones that no longer
fit simply run off the right edge. The last child was **End call**, so a video
call could not be hung up from the call screen. (Flutter used a `Wrap`, which
does not clip but reflows into a second row that shoves the bar up over the
picture and moves every button.)

Each of the three rounds of features added one more control to that row, which
is why this appeared now and why the fix is structural rather than a width
tweak.

### The fix

`layoutCallControls` — pure, in all three frontends, with the same vectors:

| | `app/lib/src/screens/call_controls.dart` | `android/.../call/CallControls.kt` | `desktop/ui/call_decisions.mjs` |
|---|---|---|---|
| tests | `call_screen_test.dart` | `CallControlsTest.kt` | `call_decisions.test.mjs` |

Bar (Telegram's order, left to right): **camera, mic, output, ⋮, End**. A voice
call drops the camera and nothing else moves, so mute does not shift under your
thumb when a call gains video. Dock: screen share, switch camera, chat.

Two invariants are asserted on every combination, in all three suites:
`primary.last == HANGUP`, and `primary.length <= MAX_PRIMARY_CALL_CONTROLS` (5).
A Flutter widget test additionally pumps a 360×640 phone and asserts End call's
rect is inside the screen — the assertion that would have caught the original.

Capability flags (`hasAudioRoutes`, `hasCameraSwitch`) let one function serve a
desktop honestly: no earpiece to route to, no second camera to flip to, so it
gets a four-control bar rather than two dead buttons.

### Things worth not reversing

- **The bar uses equal-width slots** (`Expanded` / `Modifier.weight(1f)`), not a
  centred row with fixed gaps. That is what makes overflow structurally
  impossible rather than merely unlikely at today's control count.
- **Adding a control means adding it to the dock**, not the bar. If a bar entry
  is genuinely warranted, something else has to leave — the tests will say so.
- **The Flutter scrim no longer takes pointer input.** A `BoxDecoration`
  hit-tests its whole rectangle, so the gradient behind the controls was
  silently swallowing every tap that missed a button; it is now inside an
  `IgnorePointer` with the controls' own band absorbing separately. Re-merging
  them brings back a dock you cannot dismiss by tapping the picture.
- **An open dock suspends the FaceTime auto-hide** and the first stage tap
  closes the dock rather than the whole chrome.
- **The screen-share activity launcher is `remember`ed by the screen, not the
  dock.** A launcher created inside the dock is disposed when the dock closes,
  which for the *stop* direction is the very next frame.

### Still unverified on hardware

Unchanged from round 2: the screen-share media path has never run on a real
device. CI proves it compiles. The two open risks are whether the Tauri webview
implements `getDisplayMedia` at all, and the **voice-call renegotiation** —
adding an m-line mid-call is novel versus the ICE restart that path was built
for.
