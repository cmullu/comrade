# Comrade — Frontend Strategy: the Flutter migration proposal, tested

_Status: analysis and recommendation, authored 2026-07-29 against `main` @ `0884546`.
This document evaluates an external proposal to consolidate the Android (Compose) and
desktop (Tauri/JS) frontends into a single Flutter codebase. It **recommends against the
migration as specified**, on evidence measured from this repository. `AUDIT.md` remains
the repo-wide decision ledger; `docs/COMMS_ARCHITECTURE.md` remains the adopted comms
plan — this document is scoped to the frontend-framework question only._

> **SUPERSEDED IN PART — owner decision, 2026-07-29.** After reading this analysis the
> owner directed the migration to proceed anyway, explicitly setting cost aside ("if we
> ignore the effort"). That is a legitimate call this document does not overrule: §5
> already concedes that ignoring effort changes the answer, and §7's own reopen trigger
> is iOS, which only a cross-platform toolkit delivers. **§1's "do not migrate now" and
> §7's recommendation are therefore no longer the operative decision.** What survives
> unchanged is everything §2 measured (the premise *was* wrong, and the real problem
> *was* parity debt) and the defect list in §4 — which is exactly what the build was
> steered around. See §10 for what the implementation actually found, including which
> of D1–D8 held up and which turned out sharper than written.

_**Verification honesty.** Every claim about *this repository* below was measured or read
directly, and Appendix A gives the exact command for each. Where a claim is reasoned
rather than measured, it says so._

_Scope note on that, because it changed mid-document. **In §§1–9** — written before the
owner override — no Flutter or Dart toolchain was installed and nothing Flutter-related
was compiled; every claim there about Flutter, Compose Multiplatform,
`flutter_rust_bridge` and `flutter_webrtc` comes from published package metadata and
documentation, and is labelled reasoned, not verified. **§10 is different**: it reports an
actual build on Flutter 3.44.8 / Dart 3.12.2 with Android SDK 36 and NDK 27, and its
claims are compiled, run, and in several cases mutation-tested. §10 is explicit about
which of its own statements are still unverified — chiefly that nothing has run on a
device, an emulator, or a desktop window manager._

---

## 1. Verdict

**Do not migrate to Flutter now.** The proposal's central justification — that every
feature is built twice — is not what this repository's history shows, and the plan as
written contains a defect (§4, D3) that cannot be resolved without either rewriting the
most bug-hardened file in the codebase or abandoning one of its own stated goals.

The pain the proposal is reacting to is real, but it is **not** UI duplication. It is
**desktop parity debt**: the desktop frontend stopped tracking Android on 2026-07-12 and
is now missing four surfaces that Android has shipped. Those are different problems with
different, much cheaper fixes.

Three findings drive the verdict:

1. **The duplication tax is ~2% of commits, not 100%.** Across the entire 149-commit
   history, exactly **3 commits** changed a screen on both platforms. All three were on
   2026-07-11/12, during the initial shell restructuring. In the 16 days since, **zero**.
2. **The proposal's own examples were never built twice.** It names "a new journal view"
   and "tweaks to the Tara voice assistant interface." `desktop/ui/` contains **zero**
   occurrences of `journal` or `tara`. Both were built once, on Android. Their Tauri
   backend commands are registered and have no caller.
3. **The Android app is 59% native services, not UI.** Of 11,793 lines of production
   Kotlin, 4,838 (41%) are Compose UI and 6,955 (59%) are services the plan explicitly
   preserves — which under Flutter must each grow new `MethodChannel`/`EventChannel`
   plumbing that does not exist today. "Preserved" is not "free."

---

## 2. The premise, measured

> "every new feature […] requires building a screen natively for Android in Jetpack
> Compose, and then entirely rebuilding it in HTML/JavaScript for the Tauri desktop
> application."

Tested against all 149 commits (2026-06-17 → 2026-07-28):

| Commits touching… | Count | Share of history |
|---|---:|---:|
| `android/` **and** `desktop/` | 17 | 11% |
| `android/` only | 50 | 34% |
| `desktop/` only | 14 | 9% |
| neither (Rust core, CI, docs) | 68 | 46% |

Of the 17 dual-platform commits, only **3** touched an Android UI file *and* a desktop UI
file — `f048eb3`, `b31bbfa`, `d3c2cc6`, all dated 2026-07-11/12. The other 14 touched one
platform's UI plus the *other platform's bridge* (`desktop/src-tauri/commands.rs`, Gradle,
manifests), or no UI at all — they are Rust-core changes rippling into two thin adapters.

**That second category matters, because Flutter does not remove it.** A DTO added to
`comrade_ui` still has to be threaded through an FFI boundary and a command surface. A
single Flutter frontend removes the *second* adapter; it does not remove adapter work.

### What actually happened instead: divergence

| Surface | Android | Desktop |
|---|---|---|
| Public feed (Sabha) | ✅ | ✅ |
| DMs + requests (Vault) | ✅ | ✅ |
| Calls | ✅ native `org.webrtc` | ✅ webview WebRTC |
| Couple sandbox | — | ✅ |
| **Journal** | ✅ `JournalScreen.kt` | ✗ *(0 refs; `add_journal_entry`/`journal_entries`/`delete_journal_entry`/`share_journal_entry` registered, uncalled)* |
| **Shared journal notes** | ✅ send + render | ⚠ render only — a received note draws as a card (`sharedNoteBody`), but there is no journal screen here to send one *from* |
| **Tara** | ✅ `TaraScreen.kt` (415 LOC) | ✗ *(0 refs; 5 `tara_*` commands registered, uncalled)* |
| **Call history** | ✅ `CallHistoryScreen.kt` (147 LOC) | ✗ *(0 refs; `call_history` registered, uncalled)* |
| **Onboarding / settings** | ✅ (233 / 691 LOC) | ✗ / partial |
| Voice, wake word, model download | ✅ | ✗ (by design — Android-only) |

Last `desktop/ui/` change: **2026-07-16** (`d380688`, WP4 — the call SAS display, a
Wave-1 reliability item). Last Android UI change: **2026-07-28**. In the last 60 days:
68 commits touched `android/`, 30 touched `desktop/`.

**The desktop frontend is not a duplicate that costs double to maintain. It is a subset
that stopped being maintained.** Consolidating onto Flutter would fix that by deleting
both and rewriting one — but so would writing the three missing desktop views, at a small
fraction of the cost, because the backend commands they need are already registered.

### Where the shared layer already is

The proposal frames the split as "Rust core vs. two UIs." The actual split is further up:

```
comrade_ui (6,266 LOC)   ← UiService, ComradeRuntime, all DTOs, the BridgeEvent bus
      ├── comrade_jni    (937 LOC)  → 60 uniffi exports  → Android
      ├── src-tauri      (887 LOC)  → 54 commands        → Desktop
      └── comrade_py     (258 LOC)  → PyO3               → scripting
```

View-model logic, DTOs, and the event bus are **already shared**. What is duplicated is
the view layer only: 4,838 LOC of Compose and 5,228 LOC of HTML/CSS/JS. That is the true
scope of what Flutter would unify — and roughly the scope of what it would discard.

---

## 3. What the plan would actually cost

| Item | Measured | Under the plan |
|---|---:|---|
| Compose UI | 4,838 LOC | **Discarded**, rewritten in Dart |
| Desktop web UI | 5,228 LOC | **Discarded**, rewritten in Dart |
| Native Kotlin services | 6,955 LOC | "Preserved" — but each needs new channel plumbing |
| Android UI tests | 1,956 LOC Kotlin | Compose-semantics tests; not portable to Dart |
| FFI surface | 60 uniffi exports | Replaced by / duplicated in FRB `api.rs` |
| Tauri command surface | 54 commands | Deleted |
| CI workflows | 4 files, 3,300+ lines YAML | Android + desktop + release lanes rewritten |
| Adopted comms roadmap | 18 WPs, 41 desktop references | WP1–WP5, WP8, WP17 invalidated |

Roughly **10,000 lines of working, shipped UI discarded**, against a measured duplication
rate of 3 commits in 149.

*Caveat, stated plainly: LOC is a crude effort proxy, and a Dart rewrite would not be
line-for-line — Flutter would likely need fewer lines than Compose + HTML/CSS/JS
combined. The order of magnitude is the point, not the digits.*

---

## 4. Defects in the plan as written

**D1 — There is no JNI to "retain."** Phase 2 says to "retain any existing direct JNI
bindings." There are none. `crates/comrade_jni` contains no hand-written JNI, no
`external fun`, and no `System.loadLibrary`: it is `uniffi::setup_scaffolding!` with a
callback interface (`comrade_jni/src/lib.rs:133`), and Gradle generates the Kotlin from
the compiled cdylib's own embedded metadata in library mode
(`android/app/build.gradle.kts:36-64`). The crate name is a historical artifact; the plan
read it literally.

**D2 — Two FFI layers over one runtime is a correctness hazard, not just redundancy.**
`ComradeCore` holds exactly one process-wide instance: `private val ffi: Comrade =
Comrade()` (`ComradeCore.kt:53`), shared by the UI, `RelayConnectionService`,
`CommandDispatcher`, and `CallManager`. `flutter_rust_bridge` would construct its own
`ComradeRuntime` inside the Dart isolate's Rust context — the same pattern
`comrade_jni/src/lib.rs:272` uses (`inner: RwLock::new(ComradeRuntime::new())`). Two
runtimes in one process both unlock the same vault directory, and `comrade_storage` opens
the store with `redb::Database::create` (`comrade_storage/src/lib.rs:115`); redb holds an
exclusive lock, so the second open fails outright. The plan's escape — keep the services
on the existing bridge — *is* the two-runtime case. The other escape, routing services
through `MethodChannel` → Dart → FRB → Rust, defeats Phase 2's own stated goal of letting
background services reach Rust "without waking the Flutter UI engine." Neither branch is
addressed.

**D3 — `CallScreen` cannot be rebuilt in Flutter while `CallManager` is preserved.**
This is the defect the plan does not survive as written. Phase 3 lists `CallScreen` among
the screens to rebuild in Flutter widgets. `CallScreen` renders
`org.webrtc.SurfaceViewRenderer` through `AndroidView` (`CallScreen.kt:590-627`), driven
by `CallManager`'s `StateFlow<VideoTrack?>` (`CallManager.kt:205-211`). An
`org.webrtc.VideoTrack` is a handle to a native object; it cannot cross a
`MethodChannel`. So the real options are:

- **(a)** Rewrite `CallManager` onto `flutter_webrtc`. Feasible — the package is real and
  declares Android/iOS/macOS/Windows/Linux — but this is 2,039 lines and the single
  most-repaired file in the repo: COMMS-05 setup races, deterministic glare resolution by
  npub comparison, caller-driven STUN→TURN ICE-restart, 15s/20s media-recovery
  countdowns, audio-focus mute/restore, Bluetooth route permission handling, the
  `ensureFactory` double-init race, and the `MicHolderSet` overlap fix — all documented in
  `AUDIT.md`'s 2026-07-15 entries, several found only by line-by-line re-reads.
- **(b)** Keep `CallManager` and render its video through a hand-written PlatformView —
  which leaves the call UI in Kotlin and un-unified, contradicting Phase 3.

The plan assumes a third option. There isn't one. Option (a) is the largest and riskiest
single item in the migration and the plan does not name it at all.

**D4 — Moving `crates/` under `rust/` breaks six things the plan doesn't budget for.**
Root `Cargo.toml:90-92` (`path = "crates/…"`) and its `[[bin]]` at `src/main.rs`;
`desktop/src-tauri/Cargo.toml:35` (`path = "../../crates/comrade_ui"`);
`crates/comrade_py` + its maturin `pyproject.toml` and the CI wheel job
(`ci.yml:179-215`); `android/app/build.gradle.kts:24-38`, which resolves the workspace
root as `rootProject.projectDir.resolve("..")` and reads
`target/debug/libcomrade_jni.so`; all four workflow files; and `deny.toml`.

**D5 — `flutter_rust_bridge_codegen integrate` does not adopt a 7-crate workspace.** It
scaffolds a single `rust/` crate it expects to own, with its own generated
`rust/src/api.rs`. This workspace has 7 members plus a root binary (`Cargo.toml:2-11`)
plus a deliberately-excluded Tauri crate. And `api.rs` would become a **third**
hand-maintained export surface alongside `comrade_jni`'s 60 exports and `commands.rs`'s
54 commands, unless one is deleted first.

**D6 — The toolchain count does not go down.** Today: Rust, Kotlin/Gradle, Node/JS +
Tauri. After: Rust, Kotlin/Gradle (Phase 2 keeps the services), Dart/Flutter, plus
CMake/Ninja for FRB's desktop build hooks and an FRB codegen step in CI. For a solo
maintainer whose stated goal is minimizing maintenance overhead, that is 3 → 3-plus, not
3 → 2. The one toolchain actually retired is the *smallest* one: a no-build vanilla-JS
SPA with no package.json and no dependency tree.

**D7 — It lands underneath the P0 the owner ranked first.** `docs/COMMS_ARCHITECTURE.md`
is the adopted comms plan, with 41 desktop references across 18 work packages, and the
owner's verbatim priority is "voice calling, messages, having a statelayer and video
calling as priorities as soon as possible." WP1–WP5 are desktop-JS call-reliability work,
and ADR-2's `call_decisions.mjs` (293 LOC) + `call_decisions.test.mjs` (414 LOC) already
shipped with a dedicated `node --test` CI lane (`ci.yml:112-127`), explicitly as the
cross-implementation conformance contract for converging the two call state machines.
Deleting `desktop/ui/` deletes that contract's only implementation; the 414 lines of test
vectors would need re-porting to Dart.

**D8 — It swaps a mature WebRTC engine for a less-proven one, on the highest-priority
feature.** Desktop calls today run on the system webview's WebRTC
(`main.js:929-944` — `getUserMedia` + `RTCPeerConnection`), i.e. Chromium/WebKitGTK's own
implementation. `flutter_webrtc` 1.5.2 does declare `linux`/`macos`/`windows` plugin
classes, so this is not impossible — but it is a swap onto community libwebrtc bindings,
of unquantified maturity on Linux, underneath the one feature the owner named as top
priority and which currently has an active P0 remediation in flight. Sequencing alone
rules this out for now.

---

## 5. The option the proposal never considers

If the goal is one UI codebase across mobile and desktop, **Compose Multiplatform is the
cheaper candidate for *this specific* repository** — and it is not mentioned.

| | Flutter | Compose Multiplatform |
|---|---|---|
| 4,838 LOC of existing Compose UI | discarded | **kept** (CMP *is* Compose) |
| 6,955 LOC of native Kotlin services | need Method/EventChannel plumbing | **zero plumbing** — same language, same process |
| UniFFI bridge (60 exports, panic-guarded, reentrancy-tested) | replaced by FRB | **kept** — uniffi's Kotlin backend is JNA-based and loads the same cdylib on desktop JVM |
| Host cdylib for desktop | new build hooks | **already built in CI** (`build.gradle.kts:31-38` builds a host `libcomrade_jni` for metadata) |
| `CallManager` (2,039 LOC) | rewrite onto `flutter_webrtc` (D3) | **untouched on Android** |
| 1,956 LOC of Kotlin tests | not portable | **kept** |
| Desktop WebRTC | `flutter_webrtc` (declares all 3 desktop OSes) | **open problem** — no webview; needs `webrtc-java` or JCEF |
| Desktop bundle | AOT native, ~20–40 MB | JVM + jpackage, ~40–80 MB |
| Desktop maturity | more proven | behind Flutter |

*Reasoned, not measured: no CMP build was attempted here either.*

The honest read: **CMP dominates Flutter on every axis except desktop WebRTC and bundle
size — and desktop WebRTC is the highest-risk item in either plan.** Neither unification
is cheap. But if unification happens, starting from "keep the Compose we have and keep
UniFFI" is a materially smaller bet than "delete both frontends and replace the FFI."

### The strongest argument for Flutter, which the proposal omits

**iOS.** The current stack has no iOS story at all — Compose is Android-only here, and
Tauri's iOS support is immature. Flutter and CMP both deliver it; Flutter more maturely.
If iOS is on the roadmap, the entire calculus inverts: the cost stops being "unify two
frontends" and becomes "acquire a third platform," which no amount of desktop-parity
patching achieves. **This is the question that should actually drive the decision, and
the proposal never asks it.**

---

## 6. Options compared

| | **A — Close the parity gap** | **B — Flutter (as proposed)** | **C — Compose Multiplatform** |
|---|---|---|---|
| Scope | 3 desktop views in vanilla JS | Full frontend rewrite | Desktop target for existing Compose |
| Est. new code | ~1,200–1,800 LOC | ~10,000 LOC replaced + channel plumbing | Desktop shell + WebRTC binding |
| Code discarded | none | ~10,000 LOC | ~5,200 LOC (desktop web only) |
| Blocks comms roadmap | no | yes (WP1–5, 8, 17) | partially (desktop calls) |
| Gets iOS | no | **yes** | yes (less mature) |
| Reduces future duplication | no | yes | yes |
| Risk to shipped calls | none | high (D3, D8) | medium (desktop only) |

Option A's cost estimate comes from the existing view sizes it mirrors —
`JournalScreen.kt` 330, `TaraScreen.kt` 415, `CallHistoryScreen.kt` 147 — against a
desktop UI where the backing Tauri commands are **already registered and currently
unreachable**. It is close to pure view work.

---

## 7. Recommendation

1. **Do not migrate now.** The measured duplication does not fund it, and D3/D7/D8 put it
   directly underneath the owner's stated top priority.
2. **Take Option A: close the parity gap.** Build the desktop journal, Tara, and
   call-history views against the Tauri commands that already exist. This eliminates the
   actual pain — feature asymmetry — for roughly a tenth of the cost, and it is the same
   work in any future world, since those views define the surface a unified frontend
   would have to reproduce anyway.
3. **Pull WP15 forward.** `docs/COMMS_ARCHITECTURE.md` ADR-2 already plans to move the
   call-session decision layer into shared, framework-free Rust. Doing that *before* any
   frontend decision means the highest-risk logic stops being duplicated at all, and
   survives whichever UI framework wins. It is the single highest-leverage
   de-duplication available and it needs no framework change.
4. **Set an explicit trigger to revisit.** Reopen this decision when either fires:
   - **iOS enters the roadmap** — this alone justifies the migration on its own terms; or
   - dual-UI commits exceed ~15% of frontend commits over two consecutive months
     (currently 3 of 149 all-time, 0 since 2026-07-12).
5. **If unification is chosen anyway, evaluate Compose Multiplatform against Flutter
   first,** with a bounded spike on the one question that decides it: desktop WebRTC.
   Everything else about both plans is knowable from documentation; that is not.

---

## 8. If the owner overrides: the corrected plan

Recorded so the decision, if taken, is taken on a plan that works. The corrections are
D1–D8 applied to the original four phases.

**Phase 0 (new, non-negotiable).** Spike desktop WebRTC on the target OSes before
anything else — a two-peer `flutter_webrtc` audio+video call on Linux and macOS. If that
fails, the migration fails, and it should fail on day 3, not month 3. Land WP15 (call
decisions into shared Rust) first regardless, so the 414 lines of `call_decisions`
test vectors become a Rust conformance suite rather than something to re-port to Dart.

**Phase 1 (corrected).** Do **not** move `crates/`. Add the Flutter app as a sibling
directory (`app/`) and point FRB at the existing crates by relative path. Do **not** run
`integrate` against the workspace root; scaffold in the sibling and hand-merge the CMake
and Gradle hooks. Do **not** introduce a third export surface: pick one bridge. The
cheapest correct choice is to **keep UniFFI on Android** and use FRB **only** for desktop,
which sidesteps D2 entirely — at the cost of two bridges, which is the honest price of
keeping the services native.

**Phase 2 (corrected).** Budget the channel plumbing explicitly: `WakeWordService`,
`RelayConnectionService`, `ModelDownloadService`, `CommandDispatcher`, and the
`VoiceInteractionService`/`RecognitionService` assist-app trio each need an
`EventChannel` for their state and a `MethodChannel` for control — new code, roughly
proportional to the 6,955 LOC they wrap, not free. Resolve D2 in writing before the first
channel is built: exactly one `ComradeRuntime` per process, and name which side owns it.

**Phase 3 (corrected).** Decide D3 explicitly. Either commit to rewriting `CallManager`
onto `flutter_webrtc` — with the `AUDIT.md` 2026-07-15 fix list as an acceptance checklist,
because every one of those bugs is re-introducible — or keep `CallScreen` in Kotlin and
accept a partially-unified frontend. Do not leave it implicit.

**Phase 4 (corrected).** The Tauri crate is excluded from the workspace on purpose
(`Cargo.toml:12-14`, to keep `cargo test --workspace` and CI lean); deleting it changes
CI's shape, not just its steps. Keep the `desktop-js` lane alive until the Dart call
logic passes ported equivalents of the 414 test vectors. Agreed and unchanged: `deploy/`
is untouched — the backend is genuinely unaffected.

---

## 9. On the closing question

> "Do you want to prioritize the migration of the native background audio services or the
> visual UI reconstruction first?"

Neither, and the question embeds the conclusion. It presupposes the migration is
happening and asks only for a sequencing preference — but sequencing is not the open
question, and neither branch is the first thing that would need doing.

If the migration *were* going ahead, the correct first step is Phase 0 above: prove
desktop WebRTC works, because it is the only load-bearing unknown and it can invalidate
the whole plan cheaply. Everything else in the four phases is knowable from documentation
before a single line of Dart is written.

And the question that should be asked instead of either branch: **is iOS on the
roadmap?** That is the one answer that would change this recommendation.

---

## Appendix A — how every number here was measured

Run from the repository root at `0884546`. Reproducible; nothing below is estimated.

```sh
# §2 — commits by platform, whole history (149 commits)
for c in $(git log --format=%H); do
  f=$(git show --name-only --format= "$c")
  a=0; d=0
  echo "$f" | grep -q '^android/' && a=1
  echo "$f" | grep -q '^desktop/' && d=1
  echo "$a$d"
done | sort | uniq -c        # 11=both(17) 10=android(50) 01=desktop(14) 00=neither(68)

# §2 — the 3 true dual-UI commits: both an android ui/ file and a desktop/ui/ file
git log --format=%H | while read c; do
  f=$(git show --name-only --format= "$c")
  echo "$f" | grep -q 'android/app/src/main/java/mullu/comrade/ui/\|CallScreen.kt' \
    && echo "$f" | grep -q '^desktop/ui/' && git log -1 --format='%h %ad %s' --date=short "$c"
done                          # f048eb3, b31bbfa, d3c2cc6 — all 2026-07-11/12

# §2 — desktop feature gaps (all report 0)
grep -ci 'journal\|tara\|call-history' desktop/ui/main.js desktop/ui/index.html

# §2 — Tauri commands registered but never invoked from the web UI
sed -n '/invoke_handler/,/])/p' desktop/src-tauri/src/lib.rs | grep -o 'commands::[a-z_]*'

# §2/§3 — Kotlin split (main 11,793 = ui 4,162 + CallScreen 676 + services 6,955)
find android/app/src/main -name '*.kt'          -exec cat {} + | wc -l
find android/app/src/main -path '*/ui/*' -name '*.kt' -exec cat {} + | wc -l
wc -l android/app/src/main/java/mullu/comrade/call/CallScreen.kt
find android/app/src/test android/app/src/androidTest -name '*.kt' -exec cat {} + | wc -l

# §2/§3 — desktop UI (5,228) and Rust crate sizes
wc -l desktop/ui/*
for c in crates/*/; do echo -n "$c "; find "$c" -name '*.rs' -exec cat {} + | wc -l; done

# §2 — FFI/command surface sizes.
# NB: a bare `grep -c '#\[tauri::command\]'` returns 55 — one of those is a mention
# inside the module doc comment at commands.rs:2. The real figure is 54, and all 54
# are registered (both `comm` lines below print nothing).
grep -A6 '#\[tauri::command\]' desktop/src-tauri/src/commands.rs \
  | grep -oE '(pub async fn|pub fn) [a-z_0-9]+' | sed -E 's/.* //' | sort -u > /tmp/defined
sed -n '/invoke_handler/,/])/p' desktop/src-tauri/src/lib.rs \
  | grep -o 'commands::[a-z_0-9]*' | sed 's/commands:://' | sort -u > /tmp/registered
wc -l /tmp/defined /tmp/registered      # 54 and 54
comm -23 /tmp/defined /tmp/registered   # defined but unregistered: none
comm -13 /tmp/defined /tmp/registered   # registered but undefined:  none

# comrade_jni's FFI surface: production `pub fn`s only, excluding the test module
python3 - <<'EOF'
import re
src = open('crates/comrade_jni/src/lib.rs').read()
m = re.search(r'\n#\[cfg\(test\)\]|\nmod tests\b', src)
prod = src[:m.start()] if m else src
print(len(re.findall(r'^\s*pub (?:async )?fn ', prod, re.M)))   # 60
EOF

# §2 — divergence timeline
git log -1 --format='%h %ad %s' --date=short -- desktop/ui/              # 2026-07-16
git log -1 --format='%h %ad %s' --date=short -- android/app/src/main/java/mullu/comrade/ui/
git log --since=2026-05-30 --oneline -- android/ | wc -l                 # 68
git log --since=2026-05-30 --oneline -- desktop/ | wc -l                 # 30

# §4/§5 — external package metadata (no toolchain installed)
curl -s https://pub.dev/api/packages/flutter_webrtc        # 1.5.2; linux/macos/windows declared
curl -s https://pub.dev/api/packages/flutter_rust_bridge   # 2.12.0
```

---

## 10. What the implementation found (2026-07-29, after the owner override)

The migration was built to the corrected plan in §8, not the original proposal. This
section records what the defect list got right, what it got wrong, and what is actually
true of the tree now. Everything below was verified by building and running, not by
reading — commands and outputs are in the commit messages for `05bb3d4` and `87584dc`.

### Scorecard against §4

| | Held up? | What actually happened |
|---|---|---|
| **D1** no JNI to retain | ✅ as written | It is uniffi. The Kotlin services kept those bindings. |
| **D2** two runtimes / redb lock | ✅ real, **and solvable** | Resolved rather than avoided — see below. The strongest claim in §4 turned out to have a clean fix. |
| **D3** CallScreen vs CallManager | ✅ **sharper than written** | §4 called (a) a large *risk*. It is a functional *blocker*. |
| **D4** moving `crates/` | ✅ avoided entirely | `crates/` never moved; the app is a sibling at `app/`. |
| **D5** `integrate` vs 7-crate workspace | ✅ avoided | Codegen was pointed at the existing crate; `integrate` never run. |
| **D6** toolchain count | ✅ as written | Rust + Kotlin/Gradle + Dart/Flutter. Tauri/JS not yet retired, so today it is *four*. |
| **D7** strands the comms roadmap | ⚠️ deferred, not disproven | Nothing was deleted. `desktop/ui/` and its `node --test` lane are untouched. |
| **D8** desktop WebRTC regression | ⏸️ untested | No desktop call path was built or run. Still the biggest open risk. |

### D2 — the fix, and why it is not a workaround

Two `ComradeRuntime`s in one process fail on `redb`'s exclusive lock. The resolution is
not to pick one bridge but to stop the runtime being per-handle:

```rust
static RUNTIME: OnceLock<Arc<RwLock<ComradeRuntime>>>   // crates/comrade_jni/src/lib.rs
```

**One cdylib exports both ABIs.** `nm -D --defined-only … | sort | uniq -d` is empty; FRB
2.12 routes through a fixed PDE dispatcher by integer id rather than exporting a symbol
per function, so a collision is not merely absent but structurally impossible. Confirmed
on host, `aarch64-linux-android`, `x86_64-linux-android`, and inside the shipped APK (180
uniffi + 15 `frb_` symbols in the same AArch64 `.so`).

This is what lets Phase 2 mean what it says: `WakeWordService` and
`RelayConnectionService` reach Rust through uniffi with no Flutter engine attached, while
Dart uses FRB — no `MethodChannel` round trip, one vault, one relay set. Two tests pin it:
`every_uniffi_handle_shares_one_process_global_runtime`, and one that unlocks through FRB,
observes through uniffi, locks through uniffi, and observes through FRB.

uniffi's library-mode Kotlin codegen is unaffected: generated against the pre-change
commit and diffed, the only delta anywhere is one constructor checksum, moved because
uniffi hashes docstrings into metadata and a doc comment grew.

### D3 — decided: keep `CallManager`, render through a Flutter `Texture`

§4 framed this as rewrite-vs-PlatformView with the rewrite merely risky. The
implementation review found option (a) is **blocked, not just expensive**: call offers
arrive on `pollEvent()` → `RelayConnectionService` → `CallManager.onIncomingSignal`, a
foreground service whose entire purpose is running with no UI. Move the `PeerConnection`
into the Dart isolate and an offer arriving while detached has nowhere to go without a
permanently-warm headless isolate — re-creating the dependency Phase 2 exists to remove.

So `CallManager` stays native and only a ~200-line video *leaf* remains Kotlin
(`TextureVideoRenderer extends org.webrtc.EglRenderer` — the same mechanism
`flutter_webrtc` uses internally). **Cost stated honestly: this is Android-only. If iOS
enters the roadmap, option (a) becomes correct** — which matters, because §5 identifies
iOS as the main reason to migrate at all.

### A related lifecycle bug, found by porting

`MainActivity.kt:373-411` drove `Ringer.start/stop` and missed-call notifications from an
Activity-scoped `LaunchedEffect`. Under Flutter that means **the phone only rings while a
Flutter engine is attached**. Extracted to `CallStateReactor` on the process-lifetime
scope, which also covers a `START_STICKY` restart of `RelayConnectionService` alone.

### A parity gap this surfaced, unrelated to Flutter

`sakha_status`, `pair_sakha`, `sakha_add_entry`, `sakha_read_ledger` exist on
`comrade_ui::ComradeRuntime` and are exposed by Tauri, but by **neither** FFI ABI (only
`sync_ledger` crosses). That is *why* the couple sandbox is desktop-only — a pre-existing
hole the migration merely made visible. `test_turn_connectivity` has no Rust
implementation at all; it is Kotlin-side in `CallManager`.

### State of the tree

Built and verified: `cargo fmt`/`clippy -D warnings`/`test` (244 passed); `flutter
analyze --fatal-infos` clean with no analysis excludes; `flutter test` 104 passed; a
Linux desktop release bundle; a debug APK containing both ABI slices of the Rust core;
and a `dart:ffi` round trip proven **by mutation** — each assertion broken in turn to read
the real value back, so it cannot pass for the wrong reason.

Not true yet, stated plainly:

- **Nothing has run on Android or on a desktop window manager.** No device, no emulator,
  no display here. `System.loadLibrary` never executed; no frame ever rendered. A packaged
  `.so` removes a certain crash — it is not evidence of a launch.
- **The vault has never been unlocked over the bridge.** Only locked-state behaviour is
  exercised, so no relay, media, or call path has been touched.
- **No call has been placed from Flutter on any platform**, and desktop WebRTC (D8) is
  entirely unbuilt.
- The fake repository is still the default; the real one is opt-in behind
  `--dart-define=COMRADE_BACKEND=rust`, with no silent fallback.
- Voice notes, dictation, wake word and the UPI `/pay` composer preview are not ported.
- Stripped release `.so` size is unmeasured, and the root `Cargo.toml` notes this
  library's size sets `System.loadLibrary` startup cost.

### What has NOT been deleted, deliberately

`android/` and `desktop/` are untouched and remain the shipping frontends. Their CI lanes
still run; the new Flutter lanes were added *beside* them. The original Phase 4 called for
replacing the Tauri and Compose build steps — doing that now would leave the artifacts
users actually install untested, on the strength of a frontend that has never been run on
a real device. Retirement is the last step of this migration, gated on parity, not the
first.
