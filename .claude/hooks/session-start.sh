#!/bin/bash
# SessionStart: warm the Cargo cache and tell the session which lanes it can
# actually run here.
#
# The second job matters as much as the first. This repo has four toolchains and
# the cloud sandbox ships two of them, so a session that assumes it can run
# `flutter test` wastes a turn discovering otherwise — or worse, reports a Dart
# change as verified. Probing is cheap and the answer goes straight into context.
#
# Sync, not async: `cargo fetch` is ~15s cold and the container image is cached
# afterwards, so later sessions pay nothing. Async would let the model start
# editing before the registry is there.
set -uo pipefail

ROOT="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$ROOT" || exit 0

notes=()
have() { command -v "$1" >/dev/null 2>&1; }

# ── Dependencies ─────────────────────────────────────────────────────────────
# Only in the cloud sandbox; a local checkout is already set up and a surprise
# network fetch on every session start would be rude.
if [ "${CLAUDE_CODE_REMOTE:-}" = "true" ] && have cargo; then
  if cargo fetch --locked >/dev/null 2>&1; then
    notes+=("cargo registry primed (\`--locked\`, Cargo.lock unchanged)")
  elif cargo fetch >/dev/null 2>&1; then
    notes+=("cargo fetch succeeded only WITHOUT \`--locked\` — Cargo.lock is likely stale vs Cargo.toml; check before committing")
  else
    notes+=("\`cargo fetch\` failed — the Rust lanes will not build until that is resolved")
  fi
fi

# ── Which lanes are runnable ─────────────────────────────────────────────────
runnable=()
blocked=()

have cargo && runnable+=("rust: \`cargo fmt --all -- --check\`, \`cargo clippy --workspace --all-targets --locked -- -D warnings\`, \`cargo test --workspace --locked\`") \
           || blocked+=("rust (no cargo)")

have node && runnable+=("desktop-js: \`node --test desktop/ui/*.test.mjs\`") \
          || blocked+=("desktop-js (no node)")

if have flutter && have dart; then
  runnable+=("flutter: \`cd app && flutter analyze --fatal-infos && flutter test\`")
else
  blocked+=("flutter/dart — \`app/\` CANNOT be analyzed, formatted or tested here; CI is its first build")
fi

if have gradle || [ -x android/gradlew ]; then
  if have sdkmanager || [ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]; then
    runnable+=("android: \`cd android && ./gradlew test\`")
  else
    blocked+=("android SDK — \`android/\` CANNOT be compiled or tested here; CI is its first build")
  fi
else
  blocked+=("android (no gradle wrapper)")
fi

have maturin || blocked+=("maturin — the comrade_py wheel cannot be built here (\`cargo test -p comrade_py\` still works)")

# ── Repo state ───────────────────────────────────────────────────────────────
# `main` moves under you in this repo: several sessions run concurrently, and a
# stale ref has already caused a wrong call once. Surface it up front.
if have git && git rev-parse --git-dir >/dev/null 2>&1; then
  git fetch origin main --quiet 2>/dev/null || true
  branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "?")
  notes+=("branch \`${branch}\`")
  if main_sha=$(git rev-parse --short origin/main 2>/dev/null); then
    behind=$(git rev-list --count "HEAD..origin/main" 2>/dev/null || echo 0)
    if [ "${behind:-0}" -gt 0 ]; then
      notes+=("origin/main is \`${main_sha}\`, **${behind} commit(s) ahead of this branch** — re-fetch before reasoning about branch state")
    else
      notes+=("up to date with origin/main (\`${main_sha}\`)")
    fi
  fi
  if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
    notes+=("working tree has uncommitted changes")
  fi
fi

# ── Emit ─────────────────────────────────────────────────────────────────────
{
  printf '## Environment\n\n### Runnable here\n'
  printf -- '- %s\n' "${runnable[@]}"
  if [ ${#blocked[@]} -gt 0 ]; then
    printf '\n### Not available in this sandbox\n'
    printf -- '- %s\n' "${blocked[@]}"
    printf '\nDo not claim a change in a blocked area was verified. Say it is unverified and leave it to CI.\n'
  fi
  if [ ${#notes[@]} -gt 0 ]; then
    printf '\n### State\n'
    printf -- '- %s\n' "${notes[@]}"
  fi
  printf '\nRun `/verify` to execute only the lanes your changes touched.\n'
} > /tmp/.claude-session-start.$$ 2>/dev/null

jq -Rs '{hookSpecificOutput:{hookEventName:"SessionStart",additionalContext:.}}' \
  < /tmp/.claude-session-start.$$
rm -f /tmp/.claude-session-start.$$
exit 0
