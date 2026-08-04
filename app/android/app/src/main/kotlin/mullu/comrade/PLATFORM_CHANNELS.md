# Comrade — Android platform-channel contract

_Phase 2 of the Flutter migration: the native Android services stay native, and this
document is the whole of what Flutter is allowed to say to them._

> **Verification honesty.** This phase was expected to be uncompilable — every prior
> Android change in this repo was written blind (`AUDIT.md`, the 2026-07-15 entries).
> It turned out **the environment had both toolchains**: Flutter 3.44.8 and Android SDK
> 36.0.0. So this layer is the first Android work here that a compiler has actually seen.
>
> **Verified:** `dart analyze lib/src/platform` → clean; `./gradlew :app:compileDebugKotlin`
> → success, over the channel layer *and* all 6,955 preserved service lines together;
> `./gradlew :app:assembleDebug` → a complete APK, manifest merged and resources linked.
>
> **Not verified: any behaviour whatsoever.** Nothing has been run, on a device or an
> emulator. There is no test for a single line of this. The APK now *does* contain
> `libcomrade_jni.so` for both shipped ABIs — which removes a guaranteed crash at process
> start, and is a strictly weaker claim than "it starts". See §10 for the precise line
> between the two.

---

## 0. Where the services live

The 6,955 LOC of production Kotlin this phase preserves are still at
`android/app/src/main/java/mullu/comrade/**`. This directory adds only the channel layer —
about 1,400 LOC of new Kotlin that references those classes by their existing package
names (`mullu.comrade.call.CallManager`, `mullu.comrade.voice.WakeWordService`, …).

Because the packages are identical, wiring the two together is a build-file change, not an
edit to any preserved file. `app/android/app/build.gradle.kts` does it with a `Sync` task
(`stagePreservedServices`) that copies the legacy source root into `build/preserved/java`
minus the thirteen Compose files Flutter replaces, and adds that staging directory as a
Kotlin source dir alongside the uniffi-generated bindings.

A `Sync` rather than `sourceSets.filter.exclude` for a concrete reason: exclude patterns
are matched relative to *every* source root, and `mullu/comrade/MainActivity.kt` names both
the Compose Activity being dropped and the Flutter one being kept. An exclude would remove
both.

The legacy `res/` is added as a second resource directory — the services reference
`R.string.*`, and the manifest references `@xml/interaction_service`,
`@xml/recognition_service` and `@xml/file_paths`. No resource name collides with Flutter's
own `res/` (`LaunchTheme`/`NormalTheme`, the `mipmap-*dpi` PNGs); verified by
`mergeDebugResources` succeeding.

Physically moving the sources is a mechanical follow-up. Doing it as a separate commit
keeps this one reviewable: a reviewer can diff the channel layer without a 7,000-line
move drowning it.

The Compose files filtered out (the full list is `composeOnlySources` in the build file):
`MainActivity.kt`, `call/CallScreen.kt`, `ui/AppIcons.kt`, `ui/MediaAttachment.kt`,
`ui/VoiceModelDownloadDialog.kt`, `ui/theme/**`, and the seven `ui/*Screen.kt`. Nothing
else under the legacy root imports `androidx.compose` — the 41%/59% UI-versus-services
split `docs/FRONTEND_STRATEGY.md` measured turns out to be a clean file-level partition,
which is why this staging works at all.

**Excluded from the source set** (Flutter replaces these): `ui/**`, `call/CallScreen.kt`,
`MainActivity.kt`, `ComradeApp`. `call/CallUiState.kt` **stays** — the type is what the
state channel serialises. `AppNavigation.kt` stays for its `EXTRA_OPEN_TAB` constant, which
`ModelDownloadService` puts on its notification intent. **`ComradeApplication.kt` stays**:
it owns the native-library warm-up and the `appScope` that `initializeEventBridge()` is
awaited on, neither of which has anything to do with which UI toolkit is on top.

### The one edit to a preserved file

`ComradeApplication` is now `open class` instead of `class` (one keyword,
`android/app/src/main/java/mullu/comrade/ComradeApplication.kt`). `ComradeFlutterApplication`
extends it to add a single line — starting `CallStateReactor` at process start (§4.3) —
while inheriting the warm-up and `appScope` rather than duplicating them and letting the
two drift. Nothing is overridden and the Compose build's behaviour is unchanged.

That is the *only* change to a preserved file in this phase. Everything else is additive.

---

## 1. The one rule

> **Channels carry state and control. They never carry data the Rust core can serve.**

The other half of this migration makes one cdylib export both uniffi (for these Kotlin
services) and `flutter_rust_bridge` (for Dart) over one process-global `ComradeRuntime`.
So Dart reads conversations, messages, the timeline, contacts, call history and profiles
**directly from Rust via FRB** — never over a channel. What a channel carries is:

- **control** — "start the wake-word service", "accept the call", "cancel that download";
- **state** — "the call is Active", "the model is 43% downloaded", "the mesh has 3 peers";
- **invalidation** — "the DM history changed, re-read it" (`chatTick`).

This is not stylistic. `ChatEventRouter` already publishes `chatTick`/`requestTick` rather
than message payloads, precisely so there is one source of truth. Putting DM content on a
channel would create a second copy of the store in a second language.

The corollary the services depend on: **`ComradeCore.pollEvent()` is drained by `EventPump`
and by nothing else, ever.** Dart has no method that reaches it. `EventBus`'s three-tier
priority discipline (critical never-dropped / coalesced latest-per-key / feed bounded-lossy
— `ComradeCore.kt:627-739`, AUDIT COMMS-04) is only correct with a single consumer; a
second drainer in Dart would silently steal call signals from the router that raises the
ringing notification.

`EventPump` rather than `RelayConnectionService`, and the difference is load-bearing. The
service is gated on the user's "stay connected in the background" preference, so while it
owned the loop, switching that off stopped delivery *while the app was open* too. The pump
is held by whoever needs draining — the service while it runs, `MainActivity` while it is
visible (`onStart`/`onStop`) — and still guarantees exactly one loop across all holders.
A frontend whose Activity does not acquire it has no delivery of its own to fall back on.

---

## 2. Channel inventory

Every service gets a **method channel** (control, request/response) and, where it has
observable state, an **event channel** (state, snapshot-then-delta). Names are `const`s in
`channel/ChannelNames.kt` and mirrored in `lib/src/platform/channels.dart`; nothing
outside those two files spells a channel name as a literal.

| Service | Method channel | Event channel | Handler |
|---|---|---|---|
| Calls (`CallManager`, `CallService`, `Ringer`) | `mullu.comrade/call` | `mullu.comrade/call/state` | `CallChannel` |
| Call video (texture renderers) | `mullu.comrade/call/video` | `mullu.comrade/call/video/events` | `CallVideoChannel` |
| Wake word (`WakeWordService`, `VoskModel`) | `mullu.comrade/wakeword` | `mullu.comrade/wakeword/state` | `WakeWordChannel` |
| Relay connection (`RelayConnectionService`, `ChatEventRouter`, `MeshStatusMonitor`) | `mullu.comrade/relay` | `mullu.comrade/relay/state` | `RelayConnectionChannel` |
| Model downloads (`ModelDownloadService`) | `mullu.comrade/models` | `mullu.comrade/models/state` | `ModelDownloadChannel` |
| Voice notes (`VoiceRecorder`) | `mullu.comrade/recorder` | — | `VoiceRecorderChannel` |
| Attachments (pick · capture · play · open out) | `mullu.comrade/media` | — | `MediaChannel` |
| Window security (`FLAG_SECURE`) | `mullu.comrade/screen` | — | `ScreenSecurityChannel` |
| Notifications + runtime permissions + mute (`Notifier`, `MutedChats`) | `mullu.comrade/system` | — | `SystemChannel` |
| App updates (`UpdateChecker`) | `mullu.comrade/updates` | `mullu.comrade/updates/state` | `UpdateChannel` |

All ten are constructed by `ComradePlugin` (`FlutterPlugin`, `ActivityAware`) and
registered from `MainActivity.configureFlutterEngine`.

Two **bridges** are shared rather than per-channel, because their request codes must not
collide: `PermissionBridge` (runtime permissions) and `ActivityResultBridge`
(`startActivityForResult`, used by the picker and the camera).

---

## 3. Threading rules

These are absolute; the handlers enforce them and the helper in
`channel/EventChannelRelay.kt` exists so no handler has to remember them individually.

1. **`MethodChannel.setMethodCallHandler` runs on the platform (main) thread.** Flutter
   guarantees it, and `MethodChannel.Result.success/error` must be invoked on that same
   thread. Every reply in this layer therefore goes through `Result.postSuccess(…)` /
   `Result.postError(…)` extensions that `Handler(Looper.getMainLooper()).post` if they
   are not already on it.

2. **No method handler blocks the main thread.** Anything that can take longer than a
   frame — `testTurnConnectivity` (up to 8 s), `VoskModel.isAvailable` (touches the
   filesystem), any `ComradeCore` call that `runBlocking`s an FFI round-trip — is
   dispatched to `Dispatchers.IO` and replies from the posted continuation. This is not a
   preference: the reason `factoryLock` exists at all (`CallManager.kt:253-264`) is that
   holding `CallManager`'s monitor on the main thread during native init produced an ANR.
   The channel layer must not reintroduce that class of bug from the other side.

3. **`EventChannel.EventSink` is main-thread-only.** `success`/`error`/`endOfStream` must
   be called on the platform thread. `EventChannelRelay` collects its `StateFlow`s on a
   `Dispatchers.Main.immediate` scope, so emission is already there.

4. **A WebRTC callback never enters this layer.** `CallManager`'s `webRtcLane` invariant
   (`CallManager.kt:169-199`) — a callback delivered on a WebRTC signalling thread must
   never take `CallManager`'s monitor inline — is unchanged and unchallenged here: the
   channel layer only ever *observes* `CallManager`'s `StateFlow`s (safe from any thread)
   and *calls* its already-`@Synchronized` public methods from a coroutine, never from a
   WebRTC thread. No channel code runs inside a `PeerConnection.Observer`.

5. **`startForeground()` promptness is never mediated by Dart.** See §5.

---

## 4. Lifecycle: what "the engine is detached" means

This is the point of the whole phase, so it is worth being exact about.

A `FlutterEngine` can be detached while the process lives: the user backgrounds the app,
the Activity is destroyed, the engine is released. Every Android service in this app is
designed to keep running through exactly that. Under Flutter, three things follow.

### 4.1 Nothing native waits on Dart

No service calls `invokeMethod` and awaits a reply as part of a correctness path. The only
`invokeMethod` in this layer at all is `mullu.comrade/system#openTab` (the model-download
"ready" notification's return-to-tab), and it is fire-and-forget: if the engine is not
attached, `SystemChannel` stashes the tab and Dart collects it with `consumePendingTab` at
its next start. A service that blocked on Dart would be a service that stops working when
the screen is off, which is the failure this phase exists to prevent.

### 4.2 Detach is lossless, because every event channel is snapshot-based

Every event channel here publishes a **conflated current value**, not a stream of
deliver-once events:

- `onListen` immediately emits a full snapshot of the current state, then deltas.
- `onCancel` (engine detaching) drops the sink and **cancels the collection job**. Nothing
  is buffered.
- The next `onListen` emits a fresh snapshot.

So a call that rang, connected and ended entirely while the engine was detached leaves the
reattached UI seeing `Idle` — which is correct, and the notification/call-history/ringtone
side of it all happened natively anyway (§4.3). There is deliberately **no** replay queue:
a queue would let a stale "Ringing" arrive seconds after the call was already over.

The one place that needed care is the Sabha feed, which *is* an accumulating list rather
than a scalar. `ChatEventRouter.feedItems` is itself a `StateFlow<List<…>>` capped at 500
(`ChatEventRouter.FEED_CAP`), so the relay sends the whole list on each emission — which
is once per arriving Chitthi, and bounded. The native list stays authoritative for dedup
and cap; Dart's copy is a projection of it, not a second source of truth. A per-item delta
form was considered and rejected: the native side keeps no change log to derive one from,
so it would mean adding change tracking to a preserved file to save a bounded 500-element
list on an event that fires at human speed.

### 4.3 Anything a user can perceive happens natively

Under Compose, `MainActivity`'s `LaunchedEffect(callState)` (`MainActivity.kt:373-411`)
owned four user-visible side effects: `Ringer.start/stop`, `Notifier.clearCall`,
`Notifier.notifyMissedCall`, and the lock-screen bypass. Three of those must fire whether
or not any UI is alive — an incoming call has to ring with the screen off.

Phase 2 therefore moves them out of the UI into **`CallStateReactor`**, an app-scoped
object that collects `CallManager.state` on `ComradeApplication.appScope` and is started
from `Application.onCreate`. It is engine-independent by construction. The fourth effect
(`setShowOverLockScreen`) genuinely needs an Activity, so `CallStateReactor` holds a
`WeakReference<Activity>` that `MainActivity` registers in `onCreate` and clears in
`onDestroy`; when it is null that effect is simply skipped, exactly as it is skipped today
when no Activity is composed.

This is a **behaviour-preserving relocation, not a rewrite** — the `when (state)` arms are
copied verbatim, including the subtle one: a call is "missed" only when
`outcome == "missed" && incoming`, because the caller's own unanswered outgoing call is
not missed on this device.

> If you change nothing else from this document, keep this: **the ringtone must not depend
> on the Flutter engine.** Under Compose it depended on the Activity, which was already
> the weaker of the two guarantees; under Flutter it would be strictly worse.

### 4.4 Foreground services outlive the engine, and start without it

`RelayConnectionService`, `WakeWordService`, `CallService` and `ModelDownloadService` are
all started with `startForegroundService()` and all call `startForeground()` in
`onCreate`/first thing in `onStartCommand`. **No channel call sits between
`startForegroundService()` and `startForeground()`.** The method-channel handlers only
ever call the existing `Companion.start(context)` helpers, which is exactly what the
Compose UI does today.

`CallService.onCreate` (`CallService.kt:32-46`) goes foreground with a placeholder before
`onStartCommand` even runs, because missing that window is a hard process kill
(`ForegroundServiceDidNotStartInTimeException`), not a logged warning. `ModelDownloadService`
re-posts the *in-flight* download's notification at *its* current progress before any early
return (`ModelDownloadService.kt:52-63`). Both behaviours are untouched.

---

## 5. `mullu.comrade/call` — call control

`CallManager` remains a process-global `object`. The channel is a thin, typed facade over
its existing public API; it adds no state of its own.

### Methods

| Method | Arguments | Returns | Notes |
|---|---|---|---|
| `placeCall` | `{peer: String, peerLabel: String, video: bool}` | `null` | Gates mic (+camera) permission first; see §5.3. Maps to `startOutgoingCall`. |
| `accept` | — | `null` | Gates permission. Maps to `accept(context)`. |
| `reject` | — | `null` | |
| `hangup` | — | `null` | |
| `toggleMute` | — | `null` | Fire-and-forget; the result lands on the state channel. |
| `toggleCamera` | — | `null` | |
| `switchCamera` | — | `null` | |
| `cycleAudioRoute` | — | `null` | |
| `setAudioRoute` | `{route: "earpiece"\|"speaker"\|"bluetooth"\|"wired"}` | `null` | Bluetooth path requests `BLUETOOTH_CONNECT`; see §5.4. |
| `testTurnConnectivity` | `{timeoutMs: int?}` | `"noServer"\|"relayAvailable"\|"relayUnavailable"` | Off-main; up to `timeoutMs` (default 8000). |
| `turnServerStatus` | — | `{configured: bool, url: String?}` | Never returns the credential. |
| `setTurnServer` | `{url, username, credential}` | `null` | Write-only. Throws `ILLEGAL_TURN_URL` on a malformed URI. |
| `setVideoCaptureSuspended` | `{suspended: bool}` | `null` | "No surface is showing the local video" — releases/reacquires the camera without touching the user's own camera choice. |

`toggleMute`/`toggleCamera`/`switchCamera`/`cycleAudioRoute` return `null` immediately
rather than the resulting state. The state channel is the only place state is read from;
a method that returned it would give Dart two clocks.

### Error codes

`error(code, message, details)` with a stable `code`:

| Code | Meaning |
|---|---|
| `PERMISSION_DENIED` | Mic or camera refused. `details` = the list of denied permissions. |
| `NO_ACTIVITY` | A permission-gated method was called with no Activity attached (engine running headless). |
| `ILLEGAL_TURN_URL` | `comrade_core::call::validate_turn_url` rejected the URI. |
| `CORE_ERROR` | Any `IllegalStateException` out of `ComradeCore`. `message` is the already-user-safe text `rethrowing()` produced. |

### 5.1 `mullu.comrade/call/state` — the state event channel

One conflated map, emitted whenever *any* of `CallManager`'s seven state flows changes
(they are `combine`d, so Dart gets one coherent snapshot rather than seven interleaved
partial ones):

```jsonc
{
  "phase": "idle" | "ringing" | "connecting" | "active" | "ended",
  "peer": "npub1…",              // absent when phase == "idle"
  "peerLabel": "Asha",           // already alias→@handle→shortNpub resolved, natively
  "video": true,
  "incoming": false,
  "remoteRinging": false,        // ringing only: callee acked ("Ringing…" vs "Calling…")
  "connectedAtMs": 1753800000000,// active only; seeds the duration timer
  "outcome": "missed",           // ended only
  "muted": false,
  "cameraOn": true,
  "quality": "good" | "medium" | "poor" | "unknown",
  "audioRoute": "earpiece" | "speaker" | "bluetooth" | "wired",
  "availableRoutes": ["earpiece", "speaker"],
  "videoSuspended": false,       // capture stopped because nothing displays it
  "remoteVideoPaused": false     // peer's frames stopped arriving ("Video paused")
}
```

`peerLabel` is resolved natively (`CallManager.upgradePeerLabel`) so the ringing screen and
the notification cannot disagree — the same reason `ChatEventRouter` reads it back off
`CallUiState.Ringing` instead of re-deriving it (`RelayConnectionService.kt:352-358`).

`videoSuspended` and `cameraOn` are deliberately two facts, not one: `cameraOn` is the
user's own choice and survives backgrounding; `videoSuspended` is the app saying "no
surface is showing this video". Capture runs only while `cameraOn && !videoSuspended`,
so returning to the foreground never switches a deliberately-off camera back on.

Picture-in-picture rides its own pair — `mullu.comrade/pip` (methods: `isSupported`,
`enter {aspectWidth, aspectHeight}`, `close`) and `mullu.comrade/pip/state` (a bool
stream: in PiP right now). It is a *window* concern, not a media one; the behaviour
lives in the shared `call/PipController.kt`, and auto-enter on leaving the app is
handled natively in `MainActivity` because it must beat the Activity's stop.

### 5.2 What the state channel deliberately does not carry

`CallQuality` is refreshed every 2 s from `PeerConnection.getStats`; that is already the
cheapest useful cadence, and the conflated flow means a burst of changes collapses. There
is no per-frame or per-packet telemetry on this channel. If a stats screen ever wants raw
`RTCStatsReport`, it gets its own channel with its own explicit subscribe/unsubscribe —
not this one.

### 5.3 Permission gating stays native

Under Compose, `MainActivity.withCallPermissions` (`MainActivity.kt:352-364`) collected
`RECORD_AUDIO` (+`CAMERA` for video), then ran the deferred action. That logic moves into
`CallChannel` unchanged, using `ActivityAware`'s Activity and
`ActivityCompat.requestPermissions` with a `PluginRegistry.RequestPermissionsResultListener`.

It is native rather than a Dart `permission_handler` call for a specific reason: the
deferred-action shape matters. `accept` must run *after* the grant, in the same gesture,
while the call is still ringing. Round-tripping the grant through Dart adds two channel
hops to a path that is already racing a 45 s ring timeout, and would leave the pending
action's lifetime owned by a widget that can be disposed mid-dialog.

### 5.4 Bluetooth (AUDIT COMMS-06) is preserved exactly

`setAudioRoute("bluetooth")` on API 31+ requests `BLUETOOTH_CONNECT` at that moment — not
at startup — and **on denial calls `CallManager.onBluetoothPermissionDenied()`**, which
drops Bluetooth from `availableRoutes` for the rest of the call. Dart then sees a shortened
`availableRoutes` on the state channel and renders the speaker fallback. The channel
replies `PERMISSION_DENIED` so the UI can also toast.

The one thing that must not happen: silently doing nothing. That was the pre-COMMS-06 bug.

---

## 6. `mullu.comrade/call/video` — the `VideoTrack` problem

### 6.1 The problem

`org.webrtc.VideoTrack` is a handle to a native object. It cannot be serialised, cannot
cross a `MethodChannel`, and has no meaning in the Dart isolate. `CallScreen.kt:589-628`
renders it by sinking it into an `org.webrtc.SurfaceViewRenderer` inside an `AndroidView`.
`CallManager` publishes two of them as `StateFlow<VideoTrack?>` (`CallManager.kt:205-211`).

`docs/FRONTEND_STRATEGY.md` D3 states the two real options. This section picks one.

### 6.2 Recommendation: **(b)** — keep `CallManager` native, render through a Flutter `Texture`

Not because (a) is impossible. Because (a) breaks the architecture this phase is built on.

**The decisive argument is not risk, it is the event path.** Incoming call signals arrive
as `BridgeEvent.IncomingCallSignal` on `ComradeCore.pollEvent()`, drained by
`RelayConnectionService` — a foreground `dataSync` service that runs specifically when no
UI is alive — and handed to `CallManager.onIncomingSignal`, whose `true` return raises the
ringing notification (`RelayConnectionService.kt:342-363`). Under option (a) the
`PeerConnection` lives in the Dart isolate. An offer arriving with the engine detached
would then have nowhere to go: the service would have to spin up a headless Dart isolate,
keep it warm for the life of the vault unlock, and route every ICE candidate through two
channel hops before `addIceCandidate`. That re-creates precisely the "keep the Flutter
engine alive to receive a call" dependency that keeping the services native is meant to
eliminate. It also contradicts this phase's own stated architecture: services reach Rust
through uniffi and do **not** round-trip through Dart.

Three supporting arguments:

1. **Option (a)'s acceptance checklist is nine hard-won fixes deep** (§6.5), several of
   which were found only by line-by-line re-reads because this environment cannot compile
   Android code. Reproducing them against a different API surface, unverifiable locally,
   is the largest single risk in the migration — and `AUDIT.md`'s own record is that three
   regressions were introduced *by the review pass on those very fixes* and caught only by
   a full manual re-read.

2. **Option (b) is much smaller than D3 implies.** D3 frames it as "leaves the call UI in
   Kotlin and un-unified." With a `Texture` it does not: the only Kotlin left is a leaf
   video *surface* (~200 LOC). Every piece of call chrome — the avatar, the duration
   timer, the SAS emoji row, the quality pill, mute/camera/route/hangup buttons, the
   picture-in-picture layout — is a Flutter widget driven by §5.1. Two `Texture` widgets
   in a `Stack` is not an un-unified screen.

3. **It is the same mechanism `flutter_webrtc` uses internally.** `flutter_webrtc`'s
   Android renderer is a `SurfaceTextureRenderer extends org.webrtc.EglRenderer` drawing
   into a `TextureRegistry.SurfaceTextureEntry`. Option (b) reuses that rendering approach
   with our own `PeerConnection` instead of adopting the package's. So (b) is not the
   exotic branch: it is (a)'s renderer without (a)'s rewrite.

**Cost of (b), stated plainly.** It is Android-only. When iOS arrives, this scaffolding
buys nothing and the video path must be built again against `AVSampleBufferDisplayLayer` or
`flutter_webrtc` — and at that point option (a) becomes the right answer, because iOS is
also the strongest argument for Flutter in the first place (`FRONTEND_STRATEGY.md` §5).
**(b) is the right call for an Android-and-desktop app; it is a bet against imminent iOS.**
If iOS is on the near roadmap, revisit before writing more of this.

### 6.3 Why `Texture`, not `PlatformView`

A `PlatformView` (either virtual-display or hybrid composition) would also work and is a
smaller conceptual jump from the existing `AndroidView`. `Texture` wins on three counts
that matter for a full-screen video call:

- **No composition penalty.** Hybrid composition forces Flutter into a slower raster path
  for the whole frame; a `Texture` is a plain layer the engine composites like any other.
- **Transforms and z-order work.** The current Compose code has an explicit
  `setZOrderMediaOverlay` hack (`CallScreen.kt:612-615`) because the PiP tile's surface
  and the full-screen renderer's surface have undefined z-order otherwise. `Texture`s
  compose in widget order and that whole class of bug disappears.
- **`SurfaceView` and the Flutter surface do not fight** over the window.

`CallVideoPlatformView.kt` keeps the `SurfaceViewRenderer` path available as a registered
`PlatformView` (view type `mullu.comrade/call/video/surface`, creation params
`{source, mirror, overlay}`) so a device where the texture path misbehaves has a one-line
switch rather than a feature gap. It is registered but not used by the default widget.

### 6.4 The video contract

**Methods on `mullu.comrade/call/video`:**

| Method | Arguments | Returns |
|---|---|---|
| `createRenderer` | `{source: "local"\|"remote", mirror: bool}` | `{rendererId: int, textureId: int}` |
| `setMirror` | `{rendererId: int, mirror: bool}` | `null` |
| `disposeRenderer` | `{rendererId: int}` | `null` |

**Events on `mullu.comrade/call/video/events`** — one stream carrying all renderers:

```jsonc
{ "rendererId": 3, "width": 1280, "height": 720, "rotation": 90 }
```

Emitted on the first frame and whenever the rotated frame dimensions change. Dart uses it
to size the `Texture` with the right `AspectRatio`; a renderer that has produced no frame
yet has no entry and the widget shows black, matching today's `egl == null` behaviour.

**Lifecycle.**

- A renderer is created on demand by the Dart widget's `initState` and disposed in
  `dispose`. `CallVideoChannel` observes `CallManager.localVideo`/`remoteVideo` and
  add/removes the sink as the flow emits — Dart never sees the track.
- **EGL is lazy.** `CallManager.eglBaseContext` is null until `ensureFactory` has run
  (`CallManager.kt:266-267`), which happens during call setup. So `createRenderer` returns
  a texture id immediately and defers `EglRenderer.init` until the first non-null track
  arrives — by which point the context is guaranteed, because both flows are only
  populated inside `setupPeer`, after `ensureFactory`.
- **Renderers die with the engine, and that is correct.** `SurfaceTextureEntry` belongs to
  the engine's `TextureRegistry`; `onDetachedFromEngine` releases every renderer. The call
  itself — audio, signalling, `CallService`, the notification — is untouched. There is no
  visible surface to render to while detached, so nothing is lost. On reattach Dart
  re-creates renderers and the sinks re-attach from the still-live flows. **This is the
  only thing in this contract that legitimately dies with the engine.**

### 6.5 If option (a) is chosen anyway — the acceptance checklist

Everything below is a shipped, documented fix in `AUDIT.md`'s 2026-07-15 entries. A
`flutter_webrtc` rewrite is not done until each has an equivalent *and a test*. Several
have existing tests (`CallManagerTest`, `WakeWordServiceTest`, `CallManagerLifecycleTest`,
`CallManagerDeadlockRegressionTest`) that would need porting, not just re-passing.

1. **COMMS-05 — provisional session before the async round-trip.** `startOutgoingCall`
   builds its `Session` *synchronously*, before `placeCallTyped`'s round-trip, so a
   `hangup()` during that window finds a session instead of no-oping and letting the
   delayed continuation send an offer after the UI went idle. `endWith` also cancels the
   in-flight `placingJob`. (`CallManager.kt:415-470`; `CallManagerLifecycleTest`.)
2. **Deterministic glare resolution by npub comparison.** Mutual simultaneous calls
   resolve by `ourNpub < remoteNpub` rather than both sides self-`Busy`ing.
   (`decideGlare`/`isGlareCandidate`, `CallManager.kt:1079-1082`, `1819-1830`; unit-tested.)
3. **Caller-driven STUN→TURN ICE restart.** On post-answer ICE failure the caller re-offers
   with `IceRestart=true` against `IceStrategy.STUN_AND_TURN`; `triedTurn` makes it
   once-only. (`CallManager.kt:1198-1238`.)
4. **15 s / 20 s media-recovery countdowns.** A call that reached `Active` and lost its
   media path (ICE `FAILED`, or `DISCONNECTED` past `DISCONNECT_GRACE_MS` = 15 s) arms a
   `RECOVERY_TIMEOUT_MS` = 20 s countdown and ends honestly instead of sitting "Active"
   with dead audio — the callee has no TURN retry of its own.
   (`armRecoveryTimeout`, `CallManager.kt:122-138`, `1255`, `1999-2005`.)
5. **Audio-focus mute/restore.** An `OnAudioFocusChangeListener` mutes the local track on
   transient focus loss and restores it after, dispatched to `io` rather than handled on
   the main thread it is delivered on. (`CallManager.kt:1497-1573`.)
6. **`ensureFactory` double-init race.** `ensureFactory` takes the monitor *itself*
   (`synchronized(this)`), not relying on callers, because moving native init off the main
   thread put it in `io.launch` before the monitor was taken — two overlapping setups
   could both see `factory == null`. Note the paired constraint: init must **not** hold the
   main-thread monitor (`factoryLock`, `CallManager.kt:253-264`) or hanging up on a
   "Connecting…" screen ANRs. Both halves must survive together.
7. **Bluetooth route permission handling (COMMS-06).** §5.4.
8. **`MicHolderSet` overlap.** §7.
9. **Idempotency triad.** `decideAnswer` (duplicate `Answer` dropped unless
   `signalingState == HAVE_LOCAL_OFFER`), `decideOfferForExistingSession` (same-call
   duplicate `Offer` pre-accept is a no-op, not a busy-reject), and the bounded
   `endedCallIds` ring (cap 32) that drops a redelivered terminal `Offer` instead of
   re-ringing. All three are extracted as pure, WebRTC-free functions with unit tests
   precisely so they survive a renderer change — port the tests first.

Also on the checklist, outside `CallManager`: the deadlock invariant (`webRtcLane`,
`CallManagerDeadlockRegressionTest`), `setLocalDescription` failure ending the call the
same way `setRemoteDescription` failure does, and the busy-reject history row not logging
`startedAt = 0`.

---

## 7. `mullu.comrade/wakeword`

### Methods

| Method | Arguments | Returns |
|---|---|---|
| `start` | — | `null` |
| `stop` | — | `null` |
| `isRunning` | — | `bool` |
| `isModelAvailable` | — | `bool` (off-main: touches assets + filesDir) |
| `listenOnce` | `{timeoutMs: int?}` | `String` (recognised text, possibly empty) |

`start` gates `RECORD_AUDIO` through the same Activity-backed path as §5.3, then calls
`WakeWordService.start(context)`. `isRunning` reads the service's own `@Volatile isRunning`
companion flag — deliberately, because the toggle must re-seed from the *service*, not from
a remembered widget state (`WakeWordService.kt:324-330`).

`listenOnce` wraps `OneShotRecognizer` for tap-to-talk and dictation. It replies on the
main thread from the recogniser's callback, and it does **not** dispatch a
`CommandDispatcher` action itself — AUDIT's 2026-07-15 entry specifically records moving
that off the recogniser's main-looper callback. Dart decides what to do with the text.

### `mullu.comrade/wakeword/state`

```jsonc
{ "running": true, "status": "idle" | "listening" | "goAhead" | "modelMissing" | "micError", "modelAvailable": true }
```

`status` is an enum key, not a localised string: the localised text belongs in Dart's
`.arb` files now, not in `R.string`. The service still sets its own notification text from
`R.string` (it must — it owns the notification), so the two live side by side.

> **Known gap, stated rather than papered over.** The handler currently only emits `idle`
> and `listening`. `WakeWordService`'s internal `State` enum and its error paths (`onError`
> → `voice_mic_error`, the `VoskModel` failure → `voice_model_missing`) are private and
> reach the user only through the service's own notification text. Surfacing `goAhead` /
> `micError` / `modelMissing` needs a small `StateFlow<Status>` added to the preserved
> service — a two-line change, but a change to a preserved file, so it is deliberately not
> bundled with the channel layer. The Dart enum already carries all five values so adding
> them later is a Kotlin-only edit.
>
> Relatedly, `running` is **polled** (500 ms) rather than pushed, because
> `WakeWordService.isRunning` is a `@Volatile` companion boolean set in the service's own
> `onCreate`/`onDestroy`, not a flow. Two boolean reads per tick, `distinctUntilChanged` so
> Dart only sees transitions. The same `StateFlow` would remove the poll.

### `MicHolderSet` is not exposed, and must not be

`WakeWordService.pause(MicHolder)` / `resume(MicHolder)` take a holder token and only
actually restart the recogniser once **every** holder has released
(`WakeWordService.kt:45-55`, `305-317`). The holders are `CALL` and `VOICE_NOTE`, and they
are acquired/released by `CallManager` (`:933`, `:1429`) and `VoiceRecorder`
(`VoiceRecorder.kt:55`, `:124`, `:137`) — both native, both already correct.

**No channel method calls `pause`/`resume`.** If Dart could, the two-touch sequence AUDIT
found reachable (hold record, answer a call, release record) would come back the moment
some widget's `dispose` fired an unbalanced `resume`. The invariant survives by *not*
having an entry point, which is stronger than having a documented one.

Same reasoning applies to `VoskModel`'s refcount: `acquire`/`release` are called only by
the four native recogniser owners (`WakeWordService`, `OneShotRecognizer`, the assist
session, `ComradeRecognitionService`), each of which releases in its own teardown. Dart
gets `isModelAvailable` — a read — and nothing else. A Dart-held reference would be a
reference no `onDestroy` can guarantee returning, and the 30 s `CLOSE_LINGER_MS` reclaim
would silently stop happening.

---

## 8. `mullu.comrade/relay`

### Methods

| Method | Arguments | Returns |
|---|---|---|
| `start` | — | `bool` (false if the user disabled the feature) |
| `stop` | — | `null` |
| `isEnabled` | — | `bool` |
| `setEnabled` | `{enabled: bool}` | `null` |
| `setOpenConversation` | `{peer: String?}` | `null` |
| `bumpChatTick` | — | `null` |
| `refreshNames` | — | `null` |

`start` respects `BackgroundConnectivityPreference` exactly as `RelayConnectionService.start`
does (returns `false` rather than starting when the user opted out) — the preference stays
in `SharedPreferences`, native-side, because the *service* is what reads it at start time.

`setOpenConversation` is the notification-suppression hook: it must be called when a chat
thread becomes visible and cleared when it is not, or the user gets notified for the thread
they are reading. Under Compose this was an Activity-scoped effect; under Flutter it is a
route observer. **Dart must clear it on detach** — the wrapper does this from
`WidgetsBindingObserver.didChangeAppLifecycleState`, because a detached engine cannot mean
"still reading this thread".

### `mullu.comrade/relay/state`

```jsonc
{
  "running": true,
  "chatTick": 41,          // bumped when DM history changed — re-read from Rust
  "requestTick": 3,        // bumped when a message request arrived
  "mesh": { "active": true, "peerCount": 3 },
  "eventBus": {            // EventBus.Stats — for the diagnostics screen
    "criticalDepth": 0, "coalescedDepth": 1, "feedDepth": 12,
    "feedDrops": 0, "coalesceSuppressions": 7, "lastDequeueLagMs": 3
  },
  "feed": {                // whole capped list; see §4.2
    "revision": 118,       // == items.length — lets Dart cheaply detect a change
    "items": [ { "id": "…", "author": "npub1…", "content": "…", "createdAt": 1753…, "replyTo": null } ]
  }
}
```

The tick fields are counters, not payloads, on purpose (§1). `eventBus` is exposed because
`EventBus.Stats` exists specifically to make a stuck drain loop visible, and a diagnostics
screen that can see `criticalDepth` climbing is worth more than one that cannot.

---

## 9. Remaining channels

### `mullu.comrade/models`

| Method | Arguments | Returns |
|---|---|---|
| `startDownload` | `{modelId: "speech"\|"companion"}` | `null` |
| `cancelDownload` | `{modelId}` | `null` |
| `isInstalled` | `{modelId}` | `bool` |
| `catalog` | — | `List<{id, displayName, downloadBytes, configured, returnTab}>` |
| `reofferIfGone` | `{modelId}` | `null` |

State (`mullu.comrade/models/state`), one entry per catalog model:

```jsonc
{ "speech": { "status": "downloading", "bytesRead": 17825792, "totalBytes": 41205931 },
  "companion": { "status": "idle" } }
```

`status` ∈ `idle | downloading | installing | ready | failed`; `failed` carries `message`.
`configured == false` (the companion model is deliberately unpinned —
`ModelCatalog.kt:47-75`) must be surfaced as "not available", never as a download offer.
The sha256 pinning, zip-slip guard and atomic install all stay in `ModelInstaller`; the
channel cannot reach them and cannot weaken them.

### `mullu.comrade/recorder`

| Method | Arguments | Returns |
|---|---|---|
| `start` | — | `bool` (false = could not start; nothing to clean up) |
| `stop` | — | `{path: String, mimeType: "audio/aac"}` or `null` if too short (< 500 ms) |
| `cancel` | — | `null` |
| `isRecording` | — | `bool` |

Deliberately returns a **path**, not bytes: the clip goes straight to
`ComradeCore.sendMediaBytesTyped` via FRB's own file read, and the caller must delete the
file the moment the encrypted send resolves (the plaintext voice note must not outlive the
send — AUDIT S-4). Returning base64 over a channel would put a plaintext copy in the Dart
heap with no deletion discipline at all.

`VoiceRecorder` is *not thread-safe* by design (one gesture drives it), so the handler
holds a single instance and serialises every method onto the main thread.

### `mullu.comrade/media`

| Method | Arguments | Returns |
|---|---|---|
| `capabilities` | — | `{pick, capturePhoto, captureVideo, recordVoice, playAudio, openExternally}` — all `bool` |
| `pick` | — | `{name, mimeType, bytes}` or `null` (cancelled) |
| `capturePhoto` | — | `{name, mimeType, bytes}` or `null` (backed out) |
| `captureVideo` | — | `{name, mimeType, bytes}` or `null` |
| `toggleAudio` | `{eventId, bytes}` | `bool` — playing *after* the call |
| `stopAudio` | — | `null` |
| `openExternally` | `{name, mimeType, bytes}` | `bool` — false when nothing can open it |
| `purge` | — | `null` |

**Bytes, not paths — the opposite of `recorder` above, for the same reason.** The recorder
produces a plaintext *file* someone must delete; there is no file here. A picked document
belongs to another app, and a *received* attachment is decrypted into
`DecryptedMediaCache` in the Dart heap and served onward from memory:

* audio plays through a `MediaDataSource` over the byte array — no temp file;
* anything handed to another app goes through `media/InMemoryMediaProvider`, which serves a
  **seekable** descriptor from memory via `StorageManager.openProxyFileDescriptor`
  (API 26+, matching `minSdk`) rather than a pipe, because PDF readers and video players
  seek;
* `purge` drops the audio buffer and every staged blob, called with the Dart-side cache
  clear when the app backgrounds or the vault locks.

The Compose app could not do this: `MediaPlayer`/`VideoView`/`ACTION_VIEW` all needed a
path, so it wrote decrypted media to `cacheDir/media` and paid for it with an explicit
purge that a missed call site would have turned into plaintext left on disk (AUDIT S-4).

The one unavoidable file is a **camera capture** — a camera app writes full-resolution
output to `EXTRA_OUTPUT`, and the alternative (the thumbnail in the result extras) is a few
hundred pixels wide. It is staged in `cacheDir/media`, read once, and deleted in a
`finally`.

`capabilities` is queried once at startup by the composition root, and the composer gates
its controls on the answer: a device with no camera app never shows a camera button. The
10 MB cap (`comrade_core::media::MAX_MEDIA_BYTES`) is enforced here as well as in Rust, so
an oversized pick is refused with its real size before its bytes are copied anywhere.

### `mullu.comrade/screen`

| Method | Arguments | Returns |
|---|---|---|
| `isBlocked` | — | `bool` — the user's stored preference |
| `setBlocked` | `{blocked}` | `bool` — what was actually stored |
| `setSecureWhileVisible` | `{secure}` | `bool` — whether the window is now secure |

**Screenshots are allowed by default.** The Compose app set `FLAG_SECURE` on its whole
Activity and never cleared it, so nothing in the app could be screenshotted or recorded, to
protect key material no screen renders (what is shown is an npub, which is public). That is
now a user setting — stored by `mullu.comrade.ScreenSecurity`, shared with the Compose
frontend so both frontends read one key, and outside the encrypted store because the flag
must be right for the first frame, before any unlock.

`setSecureWhileVisible` is the screen-scoped alternative, reference counted natively and
taken by Dart's `SecureScreen` while a surface showing something genuinely secret is
mounted. Nothing ships one today; it exists so that adding one is a wrap rather than a
reason to reach for the app-wide flag again.

### `mullu.comrade/system`

| Method | Arguments | Returns |
|---|---|---|
| `ensureNotificationChannels` | — | `null` |
| `hasNotificationPermission` | — | `bool` |
| `requestNotificationPermission` | — | `bool` |
| `clearForPeer` | `{peer}` | `null` |
| `clearCall` | `{peer}` | `null` |
| `consumePendingTab` | — | `String?` |
| `consumePendingPeer` | — | `String?` |
| `areNotificationsEnabled` | — | `bool` |
| `openNotificationSettings` | — | `null` |
| `mutedPeers` | — | `List<String>` |
| `isMuted` | `{peer}` | `bool` |
| `setMuted` | `{peer, muted}` | `null` |
| `unmuteAll` | — | `null` |

Plus two **outbound** calls, Kotlin → Dart: `openTab` with `{tab: String}` and
`openConversation` with `{peer: String}`, fired when the Activity is (re)started from a
notification carrying `AppNavigation.EXTRA_OPEN_TAB` / `EXTRA_OPEN_PEER`. If the engine is
not attached they are stashed and `consumePendingTab`/`consumePendingPeer` return them on
the next start. Both are read on every entry point, because a message notification carries a
peer while a model-ready or update notice carries a destination.

**Mute is native state, and that is the point.** `MutedChats` is a plain SharedPreferences
set outside the vault, consulted by `ChatEventRouter` on the notification path — which runs
with no engine attached, and before any unlock. A Dart-held mute set would be a second answer
that the code actually deciding never reads. It is device-local (Comrade has no server to
sync a preference through) and the settings card says so. Mute never silences a ringing call:
see `NotificationPolicy`, which has no rule for calls at all.

**Notification channel ids are frozen.** `comrade_calls_v2` in particular: the `_v2` suffix
exists because channel settings are sticky once created, so silencing the original id would
never have taken effect for upgrading installs, and it carries `setSound(null, null)` so
`Ringer` is the only thing that rings (`Notifier.kt:31-40`, `:70-82`). Changing the id or
restoring a sound double-rings every incoming call on every existing install. `Notifier` is
untouched by this phase and Dart has no way to create a channel.

---

## 10. `mullu.comrade/updates` — the in-app update check

| Method | Arguments | Returns |
|---|---|---|
| `settings` | — | `{currentVersion, autoCheck, lastCheckedAt, skippedVersion, canInstall}` |
| `check` | `{force}` | `null` (the answer arrives on the state channel) |
| `setAutoCheck` | `{enabled}` | `null` |
| `skip` | `{version}` | `null` |
| `unskip` | — | `null` |
| `download` | — | `null` (the service owns the transfer from here) |
| `cancelDownload` | — | `null` |
| `install` | — | `null` (the outcome arrives on the state channel) |
| `retryInstall` | — | `null` |
| `refreshDownloadState` | — | `null` |
| `openInstallPermissionSettings` | — | `null` |
| `openRelease` | — | `null` |

`mullu.comrade/updates/state` carries both halves in one snapshot —
`{check: {...}, download: {...}}` — because a UI attaching after the fact needs
both, and combining two `StateFlow`s natively is cheaper than two channels.

`check` is `unknown` / `checking` / `upToDate {checkedAt}` /
`failed {message, checkedAt}` / `available {version, tag, notes, pageUrl,
apkBytes, checkedAt}`. `download` is `idle` / `downloading {bytesRead,
totalBytes}` / `verifying` / `ready {version}` /
`installing {version, bytesStaged, totalBytes}` / `failed {message}`.
Snapshot-based like every other state channel, so a finding — or a download that
finished while the engine was detached — is the first event.

`installing` carries progress because it covers two phases, and `totalBytes`
tells them apart the same way it does for `downloading`. Positive means the APK is
still being streamed into the installer session and `bytesStaged` says how far;
zero means the platform has it and what happens next — its dialog, or this process
being replaced — is not ours to report progress on. Dart shows a bar for the first
and the `retryInstall` escape hatch only for the second: offering "nothing
happened?" while a progress bar is moving would be a lie.

**Nothing here takes a URL or a path.** `check` hits the endpoint compiled into
`UpdateCheck.LATEST_RELEASE_URL`; `download` fetches the APK asset that endpoint
reported, read by the service out of `UpdateChecker.status` rather than from an
intent extra; `install` installs the file the service itself wrote; `openRelease`
opens that release's page. An update path is code execution, so every one of
those is a place a caller-supplied string would be a way to run someone else's
APK — and none of them accept one.

**The check also runs with the app closed, and no Dart is involved.**
`UpdateCheckJob` (platform `JobScheduler`, daily with an hour of flex, persisted
across reboot) asks GitHub and posts the notification itself. That is not push and
there is no server: the device asks, on a schedule. It matters to this channel in
one way — a notice can appear, and `notified_version` can advance, without the
engine ever having been attached, so `settings` and the state channel must be read
as *current truth on attach* rather than as a log of what Dart asked for.
`setAutoCheck {enabled: false}` takes the job out of the queue outright rather than
merely stopping the reading of its answer, because the whole point of that setting
is that it stops the requests.

**The install runs in a foreground service, and the confirmation dialog is
started from an Activity.** Both are load-bearing, and the first shipped version
had neither. Copying a release APK into a `PackageInstaller` session is tens of
megabytes of I/O; doing it on the thread the method call arrives on froze the app
long enough for an ANR. Moving it to a bare worker thread fixed the freeze but
left the copy invisible — several seconds behind an Activity that covered the app
without drawing anything, so tapping Install looked like tapping nothing — which
is why it is now `UpdateInstallService` (`dataSync`), reporting progress into the
shade and onto the card, with the Activity finishing the moment it hands over.
And `STATUS_PENDING_USER_ACTION` — the platform handing
back an Intent for its own confirmation dialog, expecting the app to start it —
came back to a `BroadcastReceiver`, which is the one context that may not
reliably start an activity: a notification whose `contentIntent` is a broadcast
that starts an activity is a *notification trampoline*, blocked since Android 12,
and a background activity start from a receiver has been blocked since Android
10. Neither throws. The dialog simply never appeared and the card sat on
"Waiting for Android to install it…" forever. `UpdateInstallActivity` (invisible,
translucent, its own excluded-from-recents task) is now both the entry point and
the callback target, and `retryInstall` exists because the app *still* cannot
distinguish "the dialog is up" from "the platform dropped it" — a dropped
activity start reports nothing at all.

**The confirmation dialog is asked to stand aside.** From API 31 the session
requests `USER_ACTION_NOT_REQUIRED`, so on a device that allows it the update
applies with no dialog: the process is replaced and a notification
(`UpdateInstaller.notifyInstalled`) is the only thing the user sees, offering to
reopen the app. This is an owner decision, taken after the dialog turned out to
be the part that silently failed. **It is a request, not a guarantee** — update
ownership (Android 14+), OEM policy, or an older release all end with the
platform answering `STATUS_PENDING_USER_ACTION` instead, so the dialog path is
the live fallback and no code here may assume which one happened. A Dart caller
sees the consequence in one place: after `install` succeeds, *no further event
may arrive*, because the engine's process may be gone.

**The install is gated twice, and the app owns only one of the gates.**
`UpdateInstall.verify` (pure, host-tested) refuses a download whose package name,
version code or signing certificate disagrees with the running app, deleting it
with a reason rather than handing it to a system dialog that says only "app not
installed"; `canInstall` reports the per-source "install unknown apps" grant,
which cannot be requested in-app — `openInstallPermissionSettings` is the whole
of that flow. Android then applies its own same-signature rule, and that
enforcement, not ours, is what finally protects the upgrade. Note that it is
*enforcement, not a dialog*: skipping the confirmation does not weaken it.

**Where the certificates cannot be read**, `UpdateInstall` allows the install and
records that the signature was *not* checked (`Ok(signatureChecked = false)`)
rather than refusing every update on that device — `getPackageArchiveInfo` does
not surface signers everywhere. The platform's enforcement is what remains, and
the log says so instead of implying a check that did not happen.

The rule (what counts as newer, when to look again, when a finding is worth a
notification, what may be installed) lives in `mullu.comrade.update` in the
preserved tree, shared verbatim with the Compose app and unit-tested on the host
JVM by `UpdateCheckTest`, `UpdateInstallTest` and `UpdateDownloadsTest`.

---

## 11. Status of this code

### What was actually run

| Command | Result |
|---|---|
| `dart analyze lib/src/platform` | **clean**, no issues |
| `./gradlew :app:compileDebugKotlin` | **success** — channel layer + all preserved services |
| `./gradlew :app:assembleDebug` | **success** — `app-debug.apk` produced |

Checked in the build output rather than assumed:

- The merged manifest carries all seven services with the right
  `foregroundServiceType`s (`camera|microphone`, `microphone`, `dataSync` ×2), the
  `CallActionReceiver`, the `FileProvider`, and both assist intent filters
  (`android.service.voice.VoiceInteractionService`, `android.speech.RecognitionService`).
  `android:name` resolves to `mullu.comrade.ComradeFlutterApplication`.
- The dex contains `CallManager`, `CallStateReactor`, `ComradePlugin`, every
  `channel/*Channel`, and `TextureVideoRenderer`. So `TextureVideoRenderer extends
  org.webrtc.EglRenderer` — the one piece of this design that was a genuine API bet —
  compiles and links against `io.github.webrtc-sdk:android:125.6422.07`.
- The APK ships `libjingle_peerconnection_so.so`, `libvosk.so` and `libjnidispatch.so`.

Two real defects were found by compiling, not by reading, and are fixed:
`ComradePlugin` exposed an `internal` type through a public property; and the wake-word
state flow hit the filesystem (`VoskModel.isAvailable`) on the main thread twice a second,
because `EventChannelRelay` collects on `Dispatchers.Main.immediate` — now `flowOn(IO)`.
That is the argument for compiling, in two data points.

### What is *not* verified — the important half

- **No behaviour was exercised. Nothing has been run.** Not on a device, not on an
  emulator, not in a unit test. Compiling proves the types line up; it proves nothing
  about whether a call rings, a texture renders a frame, or a permission dialog resumes
  the right deferred action.
- **There are no tests for this layer.** The preserved services keep their existing suites
  (`CallManagerTest`, `WakeWordServiceTest`, `CallManagerLifecycleTest`,
  `CallManagerDeadlockRegressionTest`, …) in the `android/` module, and those are exactly
  the ones that would catch a channel handler taking `CallManager`'s monitor from the
  wrong thread — but they have not been run against this module, and no channel-layer
  test exists. Wiring the JVM suites into this module and re-running
  `connectedDebugAndroidTest` is the next step, and it is a real gap, not a formality.
- **The APK now contains the Rust core — which is not the same as starting.**
  `app/android/app/build.gradle.kts` cross-compiles `comrade_jni` with `cargo ndk` for
  arm64-v8a and x86_64 and wires the result into each variant's `jniLibs`, so
  `flutter build apk --debug` produces an APK carrying `lib/<abi>/libcomrade_jni.so`
  (verified by unzipping it; `abiFilters` pins the APK to exactly those two ABIs so no
  slice can ship without the core, and CI re-asserts it in `flutter.yml`). The legacy
  module still gets its copy the old way, from a separate `cargo ndk` job in
  `.github/workflows/android-apk.yml`. What this buys is only the removal of a
  *certain* failure: the first `ComradeCore` touch can now find the library. Whether it
  then loads, initialises and returns is untested here — see the first bullet.
- **The video path is the least-proven part.** `TextureVideoRenderer` compiles, but
  whether it renders a correct, correctly-rotated frame into a Flutter `Texture` is
  exactly the thing that needs a device. Same for the `PlatformView` fallback. Neither
  has displayed a pixel.
- **`flutter analyze` was run over `lib/src/platform` only**, not the whole app — the rest
  of `lib/` belongs to other work in flight.

Every "preserved invariant" claim in this document remains a claim about *unchanged native
code plus a channel that cannot reach it* — checkable by reading, and read. It is not a
claim that anything ran.

## 12. Standing tension worth recording

`docs/FRONTEND_STRATEGY.md` (2026-07-29, `AUDIT.md` line 31) recommends **against** this
migration and names D3 — this document's §6 — as the defect the plan does not survive.
That recommendation has not been withdrawn, and this document does not withdraw it: it
resolves D3 for the Android case only, by choosing option (b), and §6.2 states plainly
that the choice is a bet against imminent iOS. If iOS enters the roadmap the analysis in
`FRONTEND_STRATEGY.md` §5 inverts and so does §6.2's conclusion.
