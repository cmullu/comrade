---
paths:
  - "desktop/**/*.js"
  - "desktop/**/*.mjs"
  - "desktop/**/*.html"
  - "desktop/**/*.css"
  - "desktop/src-tauri/**/*.rs"
  - "desktop/src-tauri/Cargo.toml"
---

# Desktop (Tauri + vanilla JS)

A shipping frontend. `desktop/src-tauri` is **excluded from the Cargo
workspace** (needs system webview libs), so `cargo clippy --workspace` never
covers it. Its own lane:

```bash
cd desktop/src-tauri && cargo clippy --all-targets --locked -- -D warnings
```

Without that, this crate is built by nothing (audit T2/O1).

## The testability pattern — follow it

`desktop/ui/main.js` talks to the DOM and cannot be unit tested. So pure logic
is extracted into sibling ES modules with colocated tests:

```
desktop/ui/call_decisions.mjs   +  call_decisions.test.mjs
desktop/ui/media_cache.mjs      +  media_cache.test.mjs
```

`node --test desktop/ui/*.test.mjs` runs them with **no `package.json` and no
npm dependencies** — plain `node:test`. CI already globs `desktop/ui/*.test.mjs`,
so a new `*.test.mjs` is picked up with no workflow change.

When you add decision logic to `main.js`, extract the decision into a pure
`.mjs` function and test it there. "It's just a couple of lines in the event
handler" is how this UI became untestable in the first place. See
`docs/COMMS_ARCHITECTURE.md` §ADR-3.

## Media capture

Constrain what you capture and cap what you send. `getUserMedia`/
`getDisplayMedia` with a bare `video: true` takes whatever the device offers,
and an uncapped sender pushes it — a shared desktop at full resolution is not
what the other end wants. Set explicit constraints, and apply a bitrate cap via
`getParameters`/`setParameters` after `replaceTrack`.

Screen capture and camera capture both need this; they are separate call sites
and it is easy to fix one and leave the other.

## Style

No build step, no bundler, no framework — keep it that way. ES modules, no
transpile. Match the existing plain-DOM idiom in `main.js` rather than
introducing a helper layer.
