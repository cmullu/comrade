# Comrade

Rust core with three frontends over one FFI surface. Sovereign, offline-first
comms on Nostr + libp2p.

## Layout

| Path | What it is |
| --- | --- |
| `crates/comrade_core` | Engines: nostr, media, calls, sakha, tara, saathi. No UI, no FFI. |
| `crates/comrade_ui` | Cross-platform view-model + runtime. The real logic behind every frontend. |
| `crates/comrade_storage` | Encrypted-at-rest persistence (redb; Argon2id + AES-GCM). |
| `crates/comrade_state` | Startup/session state machine. |
| `crates/comrade_jni` | uniffi + flutter_rust_bridge FFI. Consumed by `android/` and `app/`. |
| `crates/comrade_py` | PyO3/maturin bindings. |
| `android/` | Kotlin/Compose app — **shipping** frontend. |
| `app/` | Flutter app — replacement in progress, not yet at parity. |
| `desktop/` | Tauri shell (`src-tauri/`) + vanilla-JS UI (`ui/`) — **shipping** frontend. |
| `src/` | Root binary (CLI). |

`android/` and `desktop/` are both still shipping; `app/` does not replace them
until parity. See `docs/FRONTEND_STRATEGY.md` §7 for the retirement trigger —
do not delete their code or CI lanes before it fires.

**`android/` is the priority frontend** (owner, 2026-08-08). When a feature
cannot land everywhere at once, Android goes first and the others follow; when
a design choice suits one frontend and costs another, Android's is the one that
wins. This is a standing instruction, not a note about one feature — it is why
the YouTube embed went to Android before desktop despite desktop being the only
frontend that builds in this sandbox, and it should decide the next such call
the same way without asking again.

The awkward consequence is worth stating plainly rather than rediscovering: the
priority frontend is the one this sandbox **cannot compile**. So Android work
means more of the reasoning has to be done in pure, JVM-testable Kotlin
(`together/TogetherDecisions.kt` is the pattern) and less of it in the code that
touches the framework, because the tested half is the only half that gets
checked before CI.

## Commands

`desktop/src-tauri` is **excluded from the workspace** (needs system webview
libs), so `--workspace` never covers it. It has its own lane.

```bash
cargo fmt --all -- --check                                  # CI gate
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
cargo test -p comrade_core -p comrade_ui --features comrade_ui/media-http --locked
node --test desktop/ui/*.test.mjs                           # no npm deps needed
cd desktop/src-tauri && cargo clippy --all-targets --locked -- -D warnings
cd android && ./gradlew test                                # JVM unit tests only
cd app && flutter analyze --fatal-infos && flutter test && dart format --set-exit-if-changed .
```

Run `/verify` to execute only the lanes your changes actually touched.

## What this sandbox cannot run

`flutter`, `dart`, `maturin`, and the Android SDK (`sdkmanager`, `adb`) are
**not installed**. `cargo`, `node`, `python3`, `java`, and `gradle` are.

So Dart changes cannot be compiled or tested here — CI is the first place they
build. Android is **no longer** in that category except for Compose: see
`.claude/scripts/android-typecheck.sh` below. When you touch `app/`, or a
Compose file under `android/`, say so plainly rather than implying you verified
it. Reason from the code and keep diffs tight.

**Flutter is the one of these you can install**, and for a change to the frb
bridge you should: `.claude/rules/flutter.md` has the commands, the pinned
version, and the `--no-web` flag whose absence fails CI in a way that reads like
a different bug. Budget ~10 minutes and a `cargo clean` for the disk. Nothing
equivalent exists for the Android SDK or `maturin`.

**But "Android cannot be verified here" is too strong, and it was costing the
priority frontend.** The Android SDK is what is missing, not Kotlin — and the
files worth testing are deliberately the ones with **no Android imports**
(`together/TogetherDecisions.kt` and its test import only the Kotlin stdlib and
JUnit). Those compile and *run* here in about a minute:

```bash
curl -sSL -o /tmp/kc.zip \
  https://github.com/JetBrains/kotlin/releases/download/v1.9.22/kotlin-compiler-1.9.22.zip
unzip -q /tmp/kc.zip -d /tmp            # version pinned in android/build.gradle.kts
cd /tmp && curl -sSLO https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar
curl -sSLO https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar

CP=/tmp/junit-4.13.2.jar:/tmp/hamcrest-core-1.3.jar
/tmp/kotlinc/bin/kotlinc \
  android/app/src/main/java/mullu/comrade/together/TogetherDecisions.kt \
  android/app/src/test/java/mullu/comrade/together/TogetherDecisionsTest.kt \
  -cp "$CP" -d /tmp/kout
java -cp "/tmp/kout:$CP:/tmp/kotlinc/lib/kotlin-stdlib.jar" \
  org.junit.runner.JUnitCore mullu.comrade.together.TogetherDecisionsTest
```

This is the whole argument for keeping decision logic in files with no
framework imports, restated as a capability rather than a style preference: that
half of Android is checkable before CI. Put new logic on the checkable side. It
does **not** need `gradle`, and Gradle's own `test` task still cannot run here —
Kotlin compile depends on `generateUniffiBindings`, which needs the Android SDK.

**And the boundary moved again on 2026-08-08: everything that is not Compose now
type-checks here too.**

```bash
.claude/scripts/android-typecheck.sh      # ~4 min cold, ~2 min warm
```

84 of the 87 sources under `mullu/comrade/` — `together/`, `transfer/`, `call/`,
`handoff/`, `update/`, `voice/`, `RelayConnectionService`, `ComradeCore` — compile
against a real `android.jar` (Robolectric's `android-all` for API 34), the **real**
generated uniffi bindings (`cargo` produces those; only `sdkmanager` was ever
missing), and the real AARs for WebRTC, Vosk and the YouTube player. Only the
files importing `androidx.compose` are left out, because the Compose compiler
plugin is the part not worth reproducing.

It was written *after* CI caught `refreshLive(durationMs = …)` on a function
with no such parameter — a one-line typo that cost a full push/build round trip,
in a file the JUnit lane cannot see because `TogetherManager` imports Android.
**Run it before pushing anything under `android/`.** It is not a build, a test
run or a lint: it answers "does this Kotlin resolve", which is what CI was being
asked at several minutes a go. `res/` correctness and `./gradlew test` are still
CI's — `R` is generated here from the resource files, so a missing string id
still fails, but a malformed manifest does not.

**`desktop/src-tauri` is a fourth blocked lane, and it fails in a way that looks
like your bug.** `cargo clippy` there exits **101** before compiling a single
crate, because the shell needs GTK/webview development headers and `gdk-3.0.pc`
is absent (`pkg-config --exists gdk-3.0` fails). The error names `glib-sys` or
`gdk-sys`, not your code. CI's `Desktop — Tauri shell clippy` job is the only
place this lane has ever run, so treat `desktop/src-tauri` exactly like
`android/` and `app/`: reason from the code, and never report it as checked.

## Lint bar

Every lane gates at maximum strictness, deliberately and consistently:
`clippy -D warnings`, `flutter analyze --fatal-infos`, `dart format
--set-exit-if-changed`. Do not relax one because it is inconvenient; the point
is that no lane accumulates lint debt while the others hold.

## Traps

- **The sandbox's Rust is older than CI's.** CI pins `dtolnay/rust-toolchain@stable`,
  which is whatever stable is *today*; the container image is a snapshot and can
  be several releases behind. Clippy gains lints in every release, so a clean
  `-D warnings` locally is **not** proof of a clean one in CI — this has already
  turned a branch red on `manual_checked_ops`, a lint that did not exist in the
  image. Run `rustup update stable` before trusting a clippy run.
- **`app/` breaks on a new `BridgeEvent` variant, and so does `android/`.** Both
  match it exhaustively — the Kotlin `when` in `RelayConnectionService.kt` and
  the Dart `switch` in `rust_comrade_repository.dart`. The Kotlin one fails the
  Android lane; the Dart one fails *four* Flutter jobs at once (analyze, test,
  APK, Linux bundle). Neither is in `ci.yml`, so checking only that workflow
  will miss it.
- **Gradle needs Rust.** Kotlin compile depends on `generateUniffiBindings`,
  which builds `comrade_jni` for the host — so even JVM-only unit tests need a
  Rust toolchain.
- **`comrade_py`'s `extension-module` feature is off by default** so `cargo
  test` links a normal executable. The real wheel needs it on; only the maturin
  build proves the `.so` loads.
- **`media-http` guards only run under that feature.** HTTPS-only fetch, size
  cap, and ciphertext-hash verification are invisible to a plain `cargo test`.
- **The COMMS-04 load test is `#[ignore]`d** and runs as its own required gate:
  `cargo test -p comrade_ui --test feed_flood_load --locked -- --ignored`.
- **`deny.toml` ignores need a reason and an exit condition.** Match the
  existing entries' style — say why it is accepted and what would let it go.
- **CI is push-only on every branch**, no `pull_request` trigger, so a push
  builds even with no PR open. Don't add that trigger: it would double every run.

## Conventions

- **Tests are not optional.** Bug fixes need a regression test that fails
  before the fix. Never skip or `#[ignore]` a test to get green without saying so.
- **Fail fast on missing config** rather than silently defaulting.
- **`AUDIT.md` is a live ledger.** Findings are cited by ID (A3, O5, S-4,
  COMMS-04, WP-numbers) throughout the code and docs. When you resolve one,
  strike it there with a fresh code citation — stale line numbers are worse
  than none. When you find a new gap, add it rather than fixing silently.
- **Comments explain why, not what.** The existing ones record the decision and
  the exit condition; match that. A comment asserting a guarantee the code does
  not provide is a bug.

### Commit messages

Lowercase `scope: what changed for the person using it`. Describe the effect,
not the mechanics, and use the body to record the reasoning and what you
verified. Real examples:

```
media: the upload host was one dead machine, and it took media with it
chat: open a thread where you left off, not at the newest message
saathi: carry sealed DMs, so a message reaches someone on the same WiFi
```

Not `fix(media): add fallback array`.

## Claude Code setup

Committed under `.claude/`, so every session — terminal or cloud — gets it:
`settings.json` (permissions, hooks, plugins), `rules/` (per-language, loaded by
path glob), `skills/verify`, `agents/comrade-reviewer`, `hooks/`, `scripts/`.

`.gitignore` excludes `.claude/*` and re-includes those by name, so **a new
shared directory under `.claude/` is invisible until it is added to that
allow-list** — which looks exactly like having forgotten to `git add` it.

The `caveman` plugin is enabled repo-wide and pinned to a commit; it compresses
prose while leaving code, commands, errors and paths byte-exact. Declaring a
plugin is not installing it, so the SessionStart hook repairs a cold container's
plugin cache. On a brand-new container that repair lands one session late — run
`/reload-plugins`, or put `claude plugin marketplace update caveman` in the cloud
environment's setup script, which runs before the session boots.

### User-scope settings

`statusLine` and `teammateMode` are honoured only at user scope, so they cannot
live in `.claude/settings.json` — and the cloud container wipes `~/.claude` every
session, so setting them by hand lasts one session.
`.claude/user-settings.template.json` is tracked instead, and
`hooks/seed-user-settings.sh` installs it to `~/.claude/settings.json` on
SessionStart **when that file is absent**. It never overwrites, so anything you
set by hand wins.

The status line shows model · branch · `↓N` commits behind `origin/main` · `*` for
a dirty tree, then delegates the caveman badge to the plugin's own hardened
script. It renders on a keystroke cadence, so it never fetches and caches the
drift count for 30s.

### Agent teams

Enabled via `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1` in `.claude/settings.json`
(experimental, off by default upstream). Run `/team` for the formation playbook —
`feature`, `review`, or `hunt`.

Teams fit this repo because the five file sets are near-disjoint, so each owner
edits files nobody else touches: `comrade-core` (engines + runtime), `comrade-ffi`
(`comrade_jni`), `comrade-android`, `comrade-flutter`, `comrade-desktop`. Those
are ordinary subagent definitions in `.claude/agents/`, usable either as
teammates or as plain subagents. **The lead owns `AUDIT.md` and `docs/`** — five
teammates editing one ledger is the guaranteed conflict.

There is no project-level team config file to write: teams are runtime state
under `~/.claude/teams/<session>/`, generated per session and not to be
pre-authored. Roles and spawn prompts are the reusable parts.

**Five agents is the ceiling** — for teams and for dynamic workflows alike.
`workflowSizeGuideline` is `small` (fewer than 5 agents), which also drops the
large-run warning threshold from 25 to 5. Five is the natural cap because there
are exactly five owned file sets; a sixth agent owns nothing and only adds
conflict risk. Prefer three.

A `TaskCompleted` hook blocks closing a task that adds a `BridgeEvent` variant
without updating the Kotlin and Dart matches, since the Rust side compiles clean
and neither lane is in `ci.yml`.

The cloud sandbox is **not** a devcontainer; there is no `.devcontainer/` here.
It is an Anthropic-managed VM configured per *cloud environment* (network policy,
env vars, setup script) at claude.ai. Repo-committed settings and hooks are the
parts that travel with the code.

## Working across sessions

Each session starts fresh in a throwaway container, so **anything not committed
and pushed is lost**. Push before you stop.

Push access is scoped to the session's own branch — you cannot delete remote
branches or push tags from here. If cleanup is needed, hand the commands over
rather than reporting failure.

`main` moves under you: several sessions run concurrently. Re-fetch immediately
before acting on branch state, and never trust an ahead/behind count you read
more than a few minutes ago. `docs/BRANCH_TRIAGE.md` records the branch
inventory and the two traps that cost the most time.
