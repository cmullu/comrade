---
name: verify
description: Run the CI lanes that your changes actually touched, and report honestly which ones this sandbox cannot run. Use before committing, or when asked to check/verify/test changes.
argument-hint: "[--all]"
allowed-tools: Bash(cargo *), Bash(node *), Bash(git *), Bash(cd *)
---

# Verify changes

This repo has seven CI lanes and it is easy to run the wrong ones — the Cargo
workspace excludes the Tauri crate, some guards only exist behind a feature, and
the load test is `#[ignore]`d. Pick lanes from what actually changed.

## Changed files

Uncommitted:

```!
git -C "$CLAUDE_PROJECT_DIR" status --porcelain
```

Versus `origin/main`:

```!
git -C "$CLAUDE_PROJECT_DIR" diff --name-only origin/main...HEAD 2>/dev/null | head -60
```

## Before trusting a clippy run

CI resolves `stable` fresh; this container's toolchain is a snapshot and is
often behind. New clippy lints therefore fail in CI on code that was clean here.

```!
rustc --version && cargo clippy --version
```

If those are older than the current stable, run `rustup update stable` first —
a green local clippy otherwise proves nothing about the gate.

## Lane selection

Match the paths above. With `--all`, run every runnable lane regardless.

| Changed path | Run |
| --- | --- |
| `crates/**`, `src/**`, root `Cargo.toml` | `cargo fmt --all -- --check` · `cargo clippy --workspace --all-targets --locked -- -D warnings` · `cargo test --workspace --locked` |
| `crates/comrade_core/src/media.rs`, or anything media/upload/fetch | **also** `cargo test -p comrade_core -p comrade_ui --features comrade_ui/media-http --locked` |
| `crates/comrade_ui/**` feed, subscription or memory behaviour | **also** `cargo test -p comrade_ui --test feed_flood_load --locked -- --ignored --nocapture` |
| `crates/comrade_core/src/together.rs`, or any sync tuning constant | **also** `cargo test -p comrade_core --test together_soak --locked -- --ignored --nocapture` — it is `#[ignore]`d, so `cargo test --workspace` does not run it |
| `android/**/together/**` shared constants | **also** `node --test desktop/ui/together_parity.test.mjs` — it reads the Kotlin source, so an Android-only edit that moves a shared number fails here and nowhere else |
| `desktop/ui/**` | `node --test desktop/ui/*.test.mjs` |
| `desktop/src-tauri/**` | `cd desktop/src-tauri && cargo clippy --all-targets --locked -- -D warnings` (its own lane — `--workspace` excludes it) |
| `crates/comrade_py/**` | `cargo test -p comrade_py` covers the logic only; the wheel needs `maturin`, unavailable here |
| `deny.toml`, dependency bumps | `cargo deny check advisories bans sources licenses` if `cargo-deny` is installed |
| `android/**` | **cannot run here** — no Android SDK |
| `app/**` | **cannot run here** — no `flutter`/`dart` |

Run the cheap lanes first: `fmt` before `clippy` before `test`. A first
`clippy`/`test` on a cold `target/` compiles the whole dependency graph and takes
several minutes — say so before starting rather than appearing to hang.

## Reporting

State per lane: passed, failed, or **not run here and why**. Do not summarise a
skipped lane as if it passed, and do not describe an `android/` or `app/` change
as verified — CI is its first build. If a lane fails, quote the actual error
rather than paraphrasing it.

If everything relevant passed, say which lanes ran and which are still owed to
CI, so the gap is visible before pushing.
