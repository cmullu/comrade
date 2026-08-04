#!/bin/bash
# Status line: branch · drift from origin/main · dirty · caveman badge.
#
# `main` moves under this repo constantly (several sessions run at once), and
# acting on a stale ahead/behind count has already produced a wrong call. Putting
# the drift on screen makes the staleness visible instead of remembered.
#
# Renders on a keystroke cadence, so it must stay cheap: no `git fetch`, and the
# commit count is cached for 30s. Everything is best-effort — a status line must
# never be the reason a session looks broken.
set -uo pipefail

input=$(cat 2>/dev/null || true)
cwd=$(jq -r '.workspace.current_dir // .cwd // empty' <<<"$input" 2>/dev/null)
model=$(jq -r '.model.display_name // empty' <<<"$input" 2>/dev/null)
cd "${cwd:-.}" 2>/dev/null || true

out=""
[ -n "$model" ] && out="\033[2m${model}\033[0m"

if git rev-parse --git-dir >/dev/null 2>&1; then
  branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null)
  [ -n "$out" ] && out="${out} \033[2m·\033[0m "
  out="${out}\033[38;5;39m${branch}\033[0m"

  # Cache the drift count: `rev-list --count` on every render is wasteful.
  cache="${TMPDIR:-/tmp}/.cc-statusline-drift.$(id -u)"
  if [ -f "$cache" ] && [ $(( $(date +%s) - $(stat -c %Y "$cache" 2>/dev/null || echo 0) )) -lt 30 ]; then
    behind=$(cat "$cache" 2>/dev/null)
  else
    behind=$(git rev-list --count HEAD..origin/main 2>/dev/null || echo "")
    printf '%s' "$behind" > "$cache" 2>/dev/null
  fi
  # Amber, not red: being behind is normal here, not an error.
  [ -n "${behind:-}" ] && [ "${behind:-0}" != "0" ] 2>/dev/null \
    && out="${out} \033[38;5;172m↓${behind}\033[0m"

  [ -n "$(git status --porcelain 2>/dev/null | head -1)" ] && out="${out} \033[38;5;220m*\033[0m"
fi

printf "%b" "$out"

# Delegate the caveman badge rather than reimplementing it — that script is
# hardened (refuses symlinks on its flag file, caps the read, whitelists the
# mode) and reimplementing it here would drop those checks. Prefer the
# marketplace clone: the cache path carries the pinned version and moves when
# the pin is bumped.
for c in "${CLAUDE_CONFIG_DIR:-$HOME/.claude}/plugins/marketplaces/caveman/src/hooks/caveman-statusline.sh" \
         "${CLAUDE_CONFIG_DIR:-$HOME/.claude}"/plugins/cache/caveman/caveman/*/src/hooks/caveman-statusline.sh; do
  if [ -f "$c" ]; then
    printf ' '
    bash "$c" 2>/dev/null
    break
  fi
done
exit 0
