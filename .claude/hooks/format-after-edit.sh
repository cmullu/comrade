#!/bin/bash
# PostToolUse(Edit|Write|NotebookEdit): format the file that was just written.
#
# `cargo fmt --all -- --check` is a required CI gate, so an unformatted edit is a
# red build for a reason nobody wants to spend a round-trip on. Formatting the
# single touched file is ~30ms; running `cargo fmt --all` here would reformat
# unrelated files and muddy the diff, so it deliberately does not.
#
# Dart is not formatted here on purpose: this sandbox has no `dart` binary, and a
# hook that silently does nothing is worse than one that never claimed to.
set -uo pipefail

input=$(cat)
file=$(jq -r '.tool_input.file_path // .tool_input.notebook_path // empty' <<<"$input" 2>/dev/null)

[ -z "$file" ] && exit 0
[ -f "$file" ] || exit 0

case "$file" in
  *.rs)
    command -v rustfmt >/dev/null 2>&1 || exit 0
    before=$(sha1sum "$file" 2>/dev/null | cut -d' ' -f1)
    # --edition matches rustfmt.toml; without it rustfmt assumes 2015 and
    # mangles `async`/`await` and `dyn`.
    if ! err=$(rustfmt --edition 2021 "$file" 2>&1); then
      # A parse error means the file does not compile yet. Surface it — that is
      # useful signal — but never block the edit.
      jq -n --arg e "$err" '{hookSpecificOutput:{hookEventName:"PostToolUse",
        additionalContext:("rustfmt could not parse this file, so it is unformatted (CI gates on `cargo fmt --check`):\n" + $e)}}'
      exit 0
    fi
    after=$(sha1sum "$file" 2>/dev/null | cut -d' ' -f1)
    [ "$before" != "$after" ] && echo '{"suppressOutput":true}'
    ;;
esac
exit 0
