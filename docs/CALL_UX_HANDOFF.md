# Call UX overhaul — handoff

_Branch: `claude/voice-video-call-ux-5up39x` · written 2026-07-29, for the next
agent (or human) picking this up. Read this top to bottom before touching
anything; the **Verification status** table is the most important part._

## What was asked for

1. Replace the 4-emoji SAS verification with **Telegram-style signal-strength
   bars** on voice/video calls.
2. An in-call **chat button** that sends the call to **picture-in-picture**.
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
  mute/camera, "Video paused" placeholders, chat button → `onOpenChat` +
  `PipController.enter()`, `PipVideoContent` while in PiP.
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
   tap reveals; home during video call → OS PiP window; chat button → PiP over
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
