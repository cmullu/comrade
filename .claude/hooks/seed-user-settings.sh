#!/bin/bash
# SessionStart: install ~/.claude/settings.json from the tracked template when it
# is missing.
#
# Why this exists: a few keys are honoured only at user scope — `statusLine` and
# `teammateMode` among them — so they cannot live in .claude/settings.json. And
# the cloud container wipes ~/.claude on every session, so hand-setting them
# lasts exactly one session. The template is tracked in git; this puts it where
# Claude Code reads it.
#
# Never overwrites. A file that already exists is the user's, whether they wrote
# it or an earlier session seeded it — clobbering personal settings from a repo
# hook would be a genuinely hostile thing for a checked-in script to do.
set -uo pipefail

ROOT="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
TEMPLATE="$ROOT/.claude/user-settings.template.json"
CONFIG_DIR="${CLAUDE_CONFIG_DIR:-$HOME/.claude}"
TARGET="$CONFIG_DIR/settings.json"

[ -f "$TEMPLATE" ] || exit 0
[ -e "$TARGET" ] && exit 0          # includes a symlink — do not follow or replace one
command -v jq >/dev/null 2>&1 || exit 0

mkdir -p "$CONFIG_DIR" 2>/dev/null || exit 0

# Strip the _comment block and substitute the repo root. Written to a temp file in
# the same directory and moved into place, so a crash mid-write cannot leave
# Claude Code reading half a JSON document.
tmp="$CONFIG_DIR/.settings.json.seed.$$"
if jq --arg root "$ROOT" 'del(._comment) | walk(if type == "string" then sub("__PROJECT_DIR__"; $root) else . end)' \
     "$TEMPLATE" > "$tmp" 2>/dev/null && jq -e . "$tmp" >/dev/null 2>&1; then
  mv -f "$tmp" "$TARGET" 2>/dev/null \
    && echo "seeded ${TARGET/#$HOME/~} from the tracked template (status line, teammateMode)" \
    || rm -f "$tmp"
else
  rm -f "$tmp"
fi
exit 0
