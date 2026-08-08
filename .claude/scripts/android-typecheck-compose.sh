#!/usr/bin/env bash
#
# Type-check the **Compose** half of android/ with no Android SDK.
#
# ## Why this exists, given the script next to it says it is not worth doing
#
# `android-typecheck.sh` covers the 84 sources that do not import
# `androidx.compose`, and its header says the Compose compiler plugin "is the
# part that is genuinely not worth reproducing here". That was half right, and
# the half it got wrong was costing the priority frontend the most: the plugin
# is a *codegen* pass, and everything a round trip to CI usually catches — a
# renamed parameter, a composable that does not exist at this Compose version, a
# string id that was never added — is caught by the **frontend**, which needs
# only the jars.
#
# So this compiles the whole app, Compose included, and reads the frontend's
# answer. Codegen then crashes on the first `@Composable`, because the Compose
# compiler jar registers through the newer `CompilerPluginRegistrar` and a plain
# `kotlinc 1.9.22` loads it as the legacy `ComponentRegistrar` and throws
# `AbstractMethodError`. **That crash is the expected end of a clean run**:
# kotlinc reports every resolution error before codegen begins, so a run that
# reaches codegen at all has a frontend with nothing to say.
#
# It found the first thing it was pointed at: `TogetherScreen.kt` had been
# rewritten from 750 lines to 1,700, against a Material3 API this sandbox had
# never once resolved.
#
# ## What it does not check
#
# - **The Compose compiler plugin's own rules.** `@Composable` call-context, the
#   `remember` restrictions, stability inference. Those are the plugin's, and the
#   plugin does not run here.
# - **Anything about how it looks.** This is a type-checker. A layout that
#   resolves perfectly can still be ugly, and no lane in this repo can tell you
#   otherwise — only a device can.
# - **`res/` beyond the ids.** Same as the sibling script: `R` is generated from
#   the real resource files, so a missing string fails and a malformed manifest
#   does not.
#
# Slower than the sibling (the whole app, plus ~20 more AARs to fetch once), so
# it is the one to run before pushing a Compose change rather than every time.
#
# Usage:  .claude/scripts/android-typecheck-compose.sh
# Cache:  shared with android-typecheck.sh — run that one first, or this fetches
#         the same base jars itself.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CACHE="${ANDROID_TYPECHECK_CACHE:-$HOME/.cache/comrade-android-typecheck}"
J="$CACHE/jars"
LOG="$CACHE/compose.log"
GM=https://dl.google.com/dl/android/maven2

# Everything the non-Compose lane already sets up: the toolchain, android.jar,
# the uniffi bindings and the R stub. Delegated rather than duplicated — one
# place pins the Kotlin version and the API level.
echo "== base (android-typecheck.sh) =="
"$REPO/.claude/scripts/android-typecheck.sh" >/dev/null

fetch() { [ -f "$2" ] || { echo "  fetching $(basename "$2")"; curl -sSL -o "$2" "$1"; }; }
aar_classes() { # url dest-jar
  local tmp="$CACHE/tmp-aar-compose"
  [ -f "$2" ] && return 0
  echo "  fetching $(basename "$2")"
  rm -rf "$tmp"; mkdir -p "$tmp"
  curl -sSL -o "$tmp/a.aar" "$1"
  unzip -o -q "$tmp/a.aar" classes.jar -d "$tmp"
  mv "$tmp/classes.jar" "$2"
  rm -rf "$tmp"
}

echo "== compose dependencies =="
# What compose-bom 2024.02.00 resolves to; keep these three numbers and
# `android/app/build.gradle.kts` in step.
C=1.6.1
M3=1.2.0
# Compose has been multiplatform since 1.6, so the Android classes live in a
# sibling `-android` module. The plain coordinate is metadata only — its `.aar`
# contains an `AndroidManifest.xml` and nothing else, which fails as "filename
# not matched: classes.jar" rather than as anything about Compose.
mp() { # group-path artifact version cache-name
  aar_classes "$GM/androidx/$1/$2-android/$3/$2-android-$3.aar" "$J/c-$4.jar"
}
mp compose/runtime    runtime               "$C"  runtime
mp compose/runtime    runtime-saveable      "$C"  runtime-saveable
mp compose/ui         ui                    "$C"  ui
mp compose/ui         ui-graphics           "$C"  ui-graphics
mp compose/ui         ui-text               "$C"  ui-text
mp compose/ui         ui-unit               "$C"  ui-unit
mp compose/ui         ui-geometry           "$C"  ui-geometry
mp compose/ui         ui-util               "$C"  ui-util
mp compose/foundation foundation            "$C"  foundation
mp compose/foundation foundation-layout     "$C"  foundation-layout
mp compose/animation  animation             "$C"  animation
mp compose/animation  animation-core        "$C"  animation-core
mp compose/material3  material3             "$M3" material3
mp compose/material   material-icons-core   "$C"  icons
mp compose/material   material-ripple       "$C"  ripple
aar_classes "$GM/androidx/activity/activity-compose/1.8.2/activity-compose-1.8.2.aar" "$J/c-activity-compose.jar"
aar_classes "$GM/androidx/lifecycle/lifecycle-viewmodel/2.6.2/lifecycle-viewmodel-2.6.2.aar" "$J/c-lifecycle-viewmodel.jar"
aar_classes "$GM/androidx/lifecycle/lifecycle-runtime-ktx/2.6.2/lifecycle-runtime-ktx-2.6.2.aar" "$J/c-lifecycle-runtime-ktx.jar"
aar_classes "$GM/androidx/savedstate/savedstate/1.2.1/savedstate-1.2.1.aar" "$J/c-savedstate.jar"
aar_classes "$GM/androidx/emoji2/emoji2/1.3.0/emoji2-1.3.0.aar" "$J/c-emoji2.jar"
aar_classes "$GM/androidx/autofill/autofill/1.0.0/autofill-1.0.0.aar" "$J/c-autofill.jar"
fetch "$GM/androidx/collection/collection/1.4.0/collection-1.4.0.jar" "$J/c-collection.jar"

echo "== compiling everything =="
FILES=()
while IFS= read -r f; do FILES+=("$f"); done \
  < <(find "$REPO/android/app/src/main/java/mullu/comrade" -name '*.kt' | sort)
while IFS= read -r f; do FILES+=("$f"); done < <(find "$CACHE/uniffi" -name '*.kt')
# The sibling's stub declares a placeholder `MainActivity` because the plain
# lane compiles the notification Intents that name its type without compiling
# the real class. Here the real one is in the list, so the placeholder has to go
# or they collide.
grep -v "^class MainActivity" "$CACHE/stub/Generated.kt" > "$CACHE/stub/GeneratedNoActivity.kt"
FILES+=("$CACHE/stub/GeneratedNoActivity.kt")

CP="$(find "$J" -name '*.jar' | tr '\n' ':')"
echo "  ${#FILES[@]} files"
"$CACHE/kotlinc/bin/kotlinc" -J-Xmx8g -nowarn "${FILES[@]}" \
  -cp "$CP" -d "$CACHE/out-compose" >"$LOG" 2>&1 || true

echo "== resolution errors =="
if grep -qE "\.kt:[0-9]+:[0-9]+: error:" "$LOG"; then
  grep -E "\.kt:[0-9]+:[0-9]+: error:" "$LOG"
  exit 1
fi
# Distinguish "clean frontend, expected codegen crash" from "did not get that
# far" — a run that fails for any other reason must not read as a pass.
if grep -qE "Exception while generating code" "$LOG"; then
  echo "  none — frontend clean (codegen ICE below is expected, see the header)"
else
  echo "  the compile ended some other way; the log is $LOG"
  tail -20 "$LOG"
  exit 1
fi
