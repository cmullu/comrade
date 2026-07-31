---
paths:
  - "crates/**/*.rs"
  - "src/**/*.rs"
  - "**/Cargo.toml"
  - "deny.toml"
---

# Rust

Formatting is `rustfmt.toml`: edition 2021, `max_width = 100`. CI runs
`cargo fmt --all -- --check`, so unformatted code is a red build, not a nit.

Clippy gates at `-D warnings` across `--workspace --all-targets`. A lint you
genuinely need to keep must carry an `#[allow(...)]` with a comment saying why.

## Errors

Each domain gets its own `thiserror` enum — see `crates/comrade_core/src/error.rs`
(`CoreError`, `CryptoError`, `SabhaError`, `VaultError`, `SaathiError`) and
`UiError` in `crates/comrade_ui/src/lib.rs`. Add a variant to the domain's enum
rather than widening a caller's, and never stringly-type an error that crosses
the FFI boundary — `comrade_jni` mirrors these into `_UiError`, so a new variant
needs the mirror updated or the frontends lose the case.

Prefer a precise variant over `anyhow`-style erasure. Fail fast on missing
config; do not silently default.

## Logging

Use `tracing`, never `println!` — there are currently zero `println!` calls in
`crates/`. Keep that.

## Async and locks

Never hold a lock across an `await`. This has already caused two shipped bugs
(the call-signaling deadlock, and the WebRTC callback deadlock that froze calls
on "Connecting…"), and there is a regression test guarding the second. Take the
value out under the guard, drop it, then await.

## Features

Some guards exist only under a feature and are invisible to a plain
`cargo test`:

- `media-http` — HTTPS-only fetch, size cap, ciphertext-hash verify. Exercise
  with `cargo test -p comrade_core -p comrade_ui --features comrade_ui/media-http`.
- `comrade_py`'s `extension-module` is **off by default** so `cargo test` can
  link a normal executable. Only a maturin build proves the real `.so` loads.

If you add a guard behind a feature, add the lane that runs it or it never runs.

## Tests

Unit tests live in a `#[cfg(test)] mod tests` beside the code. Integration
tests go in `tests/`. A bug fix needs a test that fails before it.

Expensive tests are `#[ignore]`d and promoted to their own CI gate rather than
slowing the default run — `feed_flood_load` is the pattern to copy.

## deny.toml

Every `ignore` entry needs a reason and an exit condition: why the risk is
accepted, and what event would let the entry go. Match the surrounding style.
Adding a bare advisory ID is not acceptable.
