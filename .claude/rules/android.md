---
paths:
  - "android/**/*.kt"
  - "android/**/*.kts"
  - "android/**/AndroidManifest.xml"
---

# Android (Kotlin / Compose)

The shipping frontend. `app/` does not replace it yet — see
`docs/FRONTEND_STRATEGY.md` §7.

**This sandbox has no Android SDK** (`sdkmanager`/`adb` absent), so nothing here
compiles locally. CI is the first build. Keep diffs tight and say plainly that a
change is unverified rather than implying otherwise.

Gradle's Kotlin compile depends on `generateUniffiBindings`, which builds
`comrade_jni` for the host — so even `./gradlew test` needs a Rust toolchain.
If bindings look stale after changing `comrade_jni`, that task is why.

## Layout

- `android/app/src/main/java/mullu/comrade/` — package is `mullu.comrade`
  (renamed from `global.auros.comrade`; old paths in history are not typos).
- `src/test/` — JVM unit tests, the cheap lane that CI runs on every push.
- `src/androidTest/` — instrumented, emulator-only, in `android-apk.yml`.

## Foreground services — read before touching `call/CallService.kt`

The foreground-service contract is the single most bug-prone area in this
codebase, and the rules are counterintuitive:

- The `startForegroundService()` → `startForeground()` obligation arms **per
  call**, not per service instance. A second delivery to a live instance arms a
  fresh deadline; failing to promote strands it and the process is killed with
  `ForegroundServiceDidNotStartInTimeException`.
- Wrapping `CallService.start(...)` in `runCatching` at the **call site does not
  protect the promotion**. `startForegroundService()` is asynchronous, so a
  throw inside `onCreate`/`startForeground` happens later on the service's own
  looper and cannot be caught from there.
- A **refused** `startForeground` does not cancel the pending
  did-not-start-in-time kill. The fix is always to make promotion *succeed*
  (`SHORT_SERVICE` is manifest-exempt), never to swallow the throw.
- `mediaProjection` type transitions must land **before** capture starts, and
  because the re-announce is async that ordering is not free — do not write a
  comment claiming a guarantee the code does not enforce.

Any change here needs a test that actually starts the service. Note that
`CallManagerLifecycleTest` sets `CallManager.disableCallServiceForTest = true`,
so it deliberately never does — keep that line, and add a separate test rather
than flipping it.

## Compose

Give every data-driven `LazyColumn` a stable `key` — existing screens all do
(`ui/ChatsScreen.kt`, `ui/FeedScreen.kt`, `ui/CallHistoryScreen.kt`). Without
one, list state reattaches to the wrong item on reorder.

Mirror UI behaviour with `app/` where both implement the same feature, and keep
the shared numbers and their tests identical on both sides.
