#!/bin/bash
# TaskCompleted: refuse to close a task that adds a BridgeEvent variant without
# updating the consumers that match it exhaustively.
#
# This is the repo's most expensive trap and the one a teammate is most likely to
# hit, because the Rust side compiles clean: Kotlin (`when`) and Dart (`switch`)
# match BridgeEvent exhaustively, so a new variant fails the Android lane and
# *four* Flutter jobs — and neither lane lives in ci.yml, so a green ci.yml looks
# like success. Nobody can build those surfaces in this sandbox either, so a
# human reviewing the diff is the only other line of defence.
#
# Deliberately objective: it counts variants rather than grepping task text for
# words like "verified". A word-matching gate blocks honest work and gets
# disabled; a variant count is either true or it is not.
#
# Exit 2 blocks completion and sends stderr back as feedback. Any other failure
# exits 0 — a broken gate must not wedge the task list.
set -uo pipefail

ROOT="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$ROOT" 2>/dev/null || exit 0
cat >/dev/null 2>&1   # drain stdin; the decision needs no field from it

RUNTIME="crates/comrade_ui/src/runtime.rs"
[ -f "$RUNTIME" ] || exit 0
command -v git >/dev/null 2>&1 || exit 0
git rev-parse --verify origin/main >/dev/null 2>&1 || exit 0

# Count variants inside `pub enum BridgeEvent { ... }`: 4-space-indented lines
# starting with a capital. Covers tuple and struct variants; struct bodies are
# more deeply indented and do not match.
count_variants() {
  awk '
    /^pub enum BridgeEvent \{/ { inside = 1; next }
    inside && /^\}/            { exit }
    inside && /^    [A-Z][A-Za-z0-9_]*/ { n++ }
    END { print n + 0 }
  '
}

now=$(count_variants < "$RUNTIME")
was=$(git show origin/main:"$RUNTIME" 2>/dev/null | count_variants)
[ -z "$was" ] && exit 0
[ "$now" -le "$was" ] 2>/dev/null && exit 0

# A variant was added. Did the exhaustive consumers move with it?
changed=$(git diff --name-only origin/main...HEAD 2>/dev/null; git diff --name-only 2>/dev/null; git diff --cached --name-only 2>/dev/null)
kt=$(printf '%s\n' "$changed" | grep -c '^android/.*\.kt$' || true)
dart=$(printf '%s\n' "$changed" | grep -c '^app/.*\.dart$' || true)

[ "${kt:-0}" -gt 0 ] && [ "${dart:-0}" -gt 0 ] && exit 0

missing=""
[ "${kt:-0}" -eq 0 ] && missing="${missing}
  - Kotlin: android/app/src/main/java/mullu/comrade/{RelayConnectionService,ComradeCore}.kt and call/CallManager.kt (exhaustive \`when\` — fails the Android lane)"
[ "${dart:-0}" -eq 0 ] && missing="${missing}
  - Dart: app/lib/src/state/{providers,chat_providers,call_providers,content_providers,settings_providers}.dart (exhaustive \`switch\` — fails analyze, test, APK and Linux bundle)"

cat >&2 <<EOF
BridgeEvent gained $((now - was)) variant(s) ($was -> $now in $RUNTIME) but the
consumers that match it exhaustively were not updated:$missing

The Rust side compiles clean, so nothing here will catch this — and neither lane
is in ci.yml, so a green ci.yml is not evidence. Update the consumers (or hand
that task to the android/flutter owners and keep this one open until they land).
EOF
exit 2
