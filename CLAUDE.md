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

So Dart and Android changes cannot be compiled or tested here — CI is the first
place they build. When you touch `app/` or `android/`, say so plainly rather
than implying you verified it. Reason from the code and keep diffs tight.

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
path glob), `skills/verify`, `agents/comrade-reviewer`, `hooks/`.

The `caveman` plugin is enabled repo-wide and pinned to a commit; it compresses
prose while leaving code, commands, errors and paths byte-exact. Declaring a
plugin is not installing it, so the SessionStart hook repairs a cold container's
plugin cache. On a brand-new container that repair lands one session late — run
`/reload-plugins`, or put `claude plugin marketplace update caveman` in the cloud
environment's setup script, which runs before the session boots.

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
