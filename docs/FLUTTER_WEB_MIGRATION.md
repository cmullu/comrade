# Comrade — completing the Flutter migration, and adding a web frontend

_Status: adopted plan, authored 2026-08-04 against `main` @ `b1b4330`. Continues
`docs/FRONTEND_STRATEGY.md`, which analysed whether to migrate (§§1–9) and then
recorded what the first build actually found (§10) after the owner overrode the
recommendation. That decision is settled and is **not** relitigated here. This
document answers the two questions §10 left open: what is still between `app/`
and retiring `android/` + `desktop/`, and how a browser signs in to a vault it
cannot hold._

_**Verification honesty.** §1 and §3 are measured — Appendix A gives the command
for every number. §4's two load-bearing claims about WebAssembly were compiled in
this sandbox, and the exact commands and outputs are in §4.1. Everything about
Dart in §6 is **unverified**: this container has no `flutter` and no `dart`
binary, so CI is the first build for every line of it, and nothing in `app/` has
ever run on a device, an emulator or a window manager (`FRONTEND_STRATEGY.md`
§10). Where a claim is reasoned rather than run, it says so._

---

## 1. Where the migration actually stands

`app/` is further along than "scaffolding" and further from parity than "nearly
done". Measured:

| | Lines | Note |
|---|---:|---|
| Dart production (`app/lib`, excluding generated `lib/src/rust/`) | 18,517 | |
| Dart tests (`app/test`) | 7,156 | 24 files, widget tests per screen |
| Compose production UI | 10,400 | `android/.../ui/` + `CallScreen.kt` |
| Native Kotlin services `app/` still depends on | 6,955 | reached over 12 platform channels |
| Desktop JS/CSS/HTML | 11,283 | including 1,946 lines of `node --test` vectors |

Screen-for-screen, Android has 16 top-level screens and `app/` has counterparts
for 11 of them. **Five have no Dart counterpart at all:**

| Missing in `app/` | Compose LOC |
|---|---:|
| `BreathingScreen` (+ `BreathHaptics`) | 423 + 162 |
| `FocusScreen` (+ `MirrorCard`) | 393 + 265 |
| `ComradesScreen` | 300 |
| `ReaderScreen` | 228 |
| `TogetherScreen` | 134 |
| `VoiceModelDownloadDialog` | 119 |
| **Total** | **2,024** |

`app/` also has one screen Android does not — `couple_screen.dart`, the Sakha
sandbox — and it cannot work against the real core: `sakha_status`, `pair_sakha`,
`sakha_add_entry`, `sakha_read_ledger` and `test_turn_connectivity` are on
`ComradeRuntime` and exposed by Tauri but by **neither** FFI ABI, so
`rust_comrade_repository.dart:497-516` throws `_notBridged` for all of them. That
is the pre-existing hole `FRONTEND_STRATEGY.md` §10 surfaced, still open.

So the honest summary of "migrate completely to Flutter" is **three** pieces of
work, not one:

1. **2,024 lines of Compose with no Dart counterpart**, plus the parts §10 lists
   as unported: voice notes, dictation, wake word, and the UPI `/pay` composer
   preview.
2. **Nothing has ever run.** No device, no emulator, no window manager, no
   unlocked vault over the bridge, no call placed from Flutter on any platform.
   The APK contains both ABI slices of the Rust core and `System.loadLibrary` has
   still never executed. *This is the largest item on the list and it is not a
   coding task* — it is a "get it in front of a screen and find out" task, and
   everything else is guesswork until it is done.
3. **Retirement is gated, not scheduled.** `FRONTEND_STRATEGY.md` §7's trigger
   has not fired. `android/` and `desktop/` still ship. Do not delete either
   before §7 fires — §5 below sequences it.

## 2. What the web version has to be, and what it cannot be

The web target is not a fourth port of the UI. `app/` is already the UI; the
question is only what it talks to. There are three candidate answers and two of
them are closed.

**(a) Run the core in the browser.** Closed, measured. `comrade_core` does not
compile for `wasm32-unknown-unknown`: `mio`, reached through tokio's net stack,
fails with 48 errors. Even if it did, `comrade_storage` opens the vault with
`redb::Database::create` (`crates/comrade_storage/src/lib.rs:115`), which wants a
real file and an exclusive lock on it, and `saathi` wants libp2p TCP and mDNS. A
browser tab has none of those.

**(b) Re-implement the protocol in Dart.** Closed on principle. It means a second
implementation of NIP-44, NIP-59, the seal format, and the vault's key schedule —
in a language with no secp256k1 in its standard library, for a product whose
entire claim is that the crypto is worth trusting. Two implementations of a
cipher is not a shortcut; it is two things to get wrong. `AUDIT.md` exists
because one implementation was already hard enough.

**(c) The browser is a screen for a device that holds the key.** This is the
plan. The tab generates its own throwaway keypair, shows it as a QR, and a phone
or laptop with an unlocked vault adopts it. The identity nsec never enters the
browser. What the browser gets is a **capability** — scoped, labelled, expiring,
revocable by id — and every request it makes is executed by the host against the
one `ComradeRuntime` that already exists.

That is the same shape as a linked device in any of the messengers that do this,
and it is the shape [prvc.app](https://prvc.app/) argues for when it says the
private key stays on the recipient's device and there is no account to recover.
(What prvc.app's own web sign-in does specifically, I could not verify: the site
returns 403 to a fetch from here and its public copy describes 64-character
one-time invitation codes for *connecting to people*, not for signing a browser
in. So the design below is built on the well-understood linked-device pattern and
on Comrade's own primitives — not reverse-engineered from theirs.)

### Why this is a better answer here than a remote signer

The obvious Nostr-native alternative is NIP-46: keep the key on the phone, have
the browser be a full client, and ship signing requests to the phone. It is a
worse fit for Comrade specifically, because Comrade is not only a Nostr client.
The vault holds message history, the courier bag, the outbox, the seen-set and
the Sakha ledger; `dak` and `saathi` are not relay traffic at all. A browser that
could sign but not read local state would show an empty app. Proxying whole
operations rather than signatures is what makes the tab useful.

## 3. What the browser must never be able to do

Two lines are drawn in `crates/comrade_core/src/link.rs` rather than in a host's
UI, so that no future approval sheet can offer them and no user can be talked
into them:

* **The journal and Tara are unreachable at every scope.** `ComradeRepository`
  documents both as strictly local; Tara's thread is the most sensitive text in
  the product. There is no `LinkScope` that turns them on — not "not yet", but no
  such value exists — and `LinkScopes::permits` refuses every `journal_*` and
  `tara_*` method regardless of what was granted.
* **Key material and the panic wipe stay with the key-holder.**
  `unlock_vault`, `lock_vault`, `generate_identity`, `generate_keypair`,
  `npub_from_nsec`, `set_turn_server` and `panic_wipe` are all in the same
  never-grantable list. A stolen laptop with a live session must not be able to
  reach the one irreversible button in the product.

An unknown method is refused too. If someone adds an export to `api.rs` and does
not teach `scope_for` about it, a linked session gets `NotPermitted` — the
failure is a support ticket, not a silent widening of the perimeter.

## 4. The measurement that makes this cheap

The browser still needs *some* Rust: it has to seal frames to the host and open
the host's replies. That is `comrade_core::{crypto, pad, link}` and nothing else.

**Measured, in this sandbox, on `rustc 1.97.1`:**

| | Result |
|---|---|
| `comrade_core` for `wasm32-unknown-unknown` | **fails** — `mio` (48 errors), reached via tokio's net stack |
| `crypto.rs` + `pad.rs` + `link.rs` + `error.rs` for the same target | **compiles** |

`link.rs` compiled **unchanged**. `pad.rs` and `error.rs` unchanged. `crypto.rs`
needed exactly one line: `use nostr_sdk::` → `use nostr::`. That is why `link.rs`
is written against `nostr` and not `nostr_sdk` from the start, and why the
workspace now declares `nostr` directly — `nostr-sdk` re-exports the same crate at
the same version, so `nostr::PublicKey` *is* `nostr_sdk::PublicKey` and this adds
a dependency edge rather than a second copy of the type.

One real finding from the probe: `rand` 0.8 reaches the OS RNG through `getrandom`
0.2, which on `wasm32-unknown-unknown` has no OS to reach. The wasm crate must
enable `getrandom`'s `js` feature or the target does not build at all. Without it
`aes256gcm_seal` has no nonce source.

### 4.1 How to reproduce both

```sh
rustup target add wasm32-unknown-unknown

# (a) the whole core: fails on mio
cargo check -p comrade_core --target wasm32-unknown-unknown

# (b) the subset: compiles. Copy the four files into a scratch crate whose
# dependencies are exactly serde, serde_json, thiserror, aes-gcm, sha2, hkdf,
# hmac, hex, base64, rand, tracing,
#   secp256k1 = { version = "0.29", features = ["hashes"] }
#   nostr     = { version = "0.44", default-features = false, features = ["std", "nip44", "nip59"] }
#   getrandom = { version = "0.2", features = ["js"] }
# then, in that crate: rewrite crypto.rs's `use nostr_sdk::` to `use nostr::`,
# stub `call::EMOJI_ALPHABET`, and drop link.rs's `#[cfg(test)] mod tests`
# (it reaches for dak::courier, which is not part of the subset).
cargo check --target wasm32-unknown-unknown
```

## 5. The plan

Phases W1–W3 are the web frontend; P1–P3 finish the migration. They are
independent and can interleave, with one ordering constraint: **P1 comes before
any retirement**, because everything else is written against a frontend that has
never been run.

### Phase W0 — done, in this change

`crates/comrade_core/src/link.rs`: the protocol, as policy and framing with no
I/O, in the style of `dak`. 51 tests, `cargo test -p comrade_core link::`.

* `LinkOffer` — the QR payload, as a strict `comrade://link?…` URI codec. The
  wire shape is pinned by `offer_uri_shape_is_pinned`, so any second
  implementation is checked against one string.
* `pairing_fingerprint` — 4 emoji from the **same alphabet as the call SAS**, so
  the user learns one "compare these four" ritual and not two. Unlike the call
  SAS it is deliberately *not* symmetric: the roles here are fixed, and the
  fingerprint pins which way round the pairing went.
* `LinkScopes` — the capability set, and the never-grantable list from §3.
* `LinkGrant` / `LinkSessionStore` — issue, authorise, revoke, sweep, snapshot.
  Quotas on live sessions and on how often a stranger may put the approval prompt
  in front of the user; the rate limit is in the snapshot, so force-quitting the
  app does not reset it.
* `LinkFrame` / `chunk` / `Reassembler` — request/response framing, split to fit
  a courier seal, with every bound a remote peer can push on.

**No signature scheme was added, on purpose.** Frames travel inside
`dak::courier::seal`, whose inner MAC is keyed by the static-static shared secret,
so `dak::courier::open` returns an *authenticated* sender. That is what lets the
browser learn which npub adopted it without the grant carrying a signature of its
own — pinned by `the_seal_authenticates_which_host_answered_an_offer`.

### Phase W1 — extract the wasm-reachable subset

Split `crypto`, `pad` and `link` out of `comrade_core` into a leaf crate that
depends only on the set in §4.1, and have `comrade_core` re-export them so every
existing `crate::crypto::…` path still resolves. §4 says the code is ready; this
phase is the Cargo and module surgery, plus a CI lane that runs `cargo check
--target wasm32-unknown-unknown` on the new crate so it cannot silently regain a
tokio edge.

Do **not** feature-gate `comrade_core` into a wasm mode instead. It reaches the
same place by making 20-odd modules conditional, and a `--no-default-features`
build that nobody runs rots.

### Phase W2 — the bridge, both halves

`flutter_rust_bridge` 2.12 has a web mode; today's codegen is invoked with
`--no-web` (`.github/workflows/flutter.yml:139`). This phase turns it on for the
new crate only, which yields `frb_generated.web.dart` beside the existing
`.io.dart` and lets `RustComradeRepository`'s import become conditional rather
than fatal.

**Blocked in this sandbox, and it is worth being precise about why.** Adding
anything to `crates/comrade_jni/src/api.rs` requires re-running
`flutter_rust_bridge_codegen`, whose last step is `dart run build_runner` — and
there is no Dart here. CI checks the generated code is not stale
(`flutter.yml:137-141`), so a hand-edited `api.rs` is a guaranteed red build.
That is why **this change does not touch `api.rs`**, and why the `link_*` exports
land in the session that can run codegen.

Also in this phase: the QR *renderer*. `LinkOffer::to_uri` is the part that had
to be specified and shared, and it is done; turning that string into modules is a
rendering concern with a pinned dependency, and the right place to choose one is
where it can be built and looked at.

### Phase W3 — the host side and the transport

The half that makes a grant do something. In `comrade_ui`:

* A method table mapping `LinkFrame::Request { method }` to the runtime, refusing
  anything `LinkSessionStore::authorize` declines. It is the *same* table
  `scope_for` names, and the two must not drift — a test that every arm of one
  appears in the other is cheap and worth having.
* `BridgeEvent` forwarding as `LinkFrame::Event`, so a linked tab updates live.
* Transport over relays, both directions, chunked and sealed. Bounded, because a
  relay round trip per call is the latency floor — batch the initial sync rather
  than making 30 sequential calls to paint the first screen.
* `panic_wipe` calls `revoke_all`, and the store persists in the existing
  Argon2id + AES-GCM store rather than a second at-rest scheme.

And on the host UI: the approval sheet (fingerprint + `LinkScope::describe` lines,
never variant names), a linked-devices list, and revoke.

The camera half is Android-native — ML Kit or ZXing behind a platform channel,
beside the twelve channels `app/lib/src/platform/` already has.

### Phase P1 — run it (this is the gate)

Before anything is retired: launch `app/` on a device and on a desktop, unlock a
vault over the bridge, place a call. Everything in `FRONTEND_STRATEGY.md` §10's
"not true yet" list is here, and none of it is a coding task. Expect this phase
to *find* work rather than complete it.

### Phase P2 — close the 2,024-line gap

The five missing screens from §1, plus voice notes, dictation, wake word and the
UPI `/pay` preview, plus the Sakha FFI hole. Straight view work against a
repository interface that already exists — except Sakha, which needs the five
missing exports first.

### Phase P3 — retire, in this order

1. `desktop/` last, not first. `desktop/ui/call_decisions.mjs` + its 830 lines of
   test vectors are the cross-implementation conformance contract
   (`COMMS_ARCHITECTURE.md` ADR-2, `FRONTEND_STRATEGY.md` D7). Land WP15 — call
   decisions into shared Rust — *before* deleting the lane, or the contract loses
   its only implementation.
2. `android/` when P1 has actually run and P2 has landed. Its 6,955 lines of
   services are **kept**; what retires is the Compose UI and `MainActivity`.
3. `deploy/` is untouched throughout. The backend is genuinely unaffected.

## 6. What this change contains

Rust, verified here (`cargo fmt`, `clippy --workspace --all-targets -D warnings`,
`cargo test --workspace`, all on `rustc 1.97.1` after `rustup update stable`):

* `crates/comrade_core/src/link.rs` — Phase W0 above, 51 tests.
* `LinkError` in `crates/comrade_core/src/error.rs` — fine-grained on the
  refusals, because each one is read by somebody holding a phone wondering why
  the scan did nothing.
* `call.rs`'s `EMOJI_ALPHABET` is now `pub(crate)`, shared with the pairing
  fingerprint.
* `nostr` declared directly at the workspace root, per §4.

Dart, **unverified** — no `flutter` or `dart` in this container, CI is the first
build:

* `app/web/` — the target's entry point, so `flutter build web` has something to
  build.
* The two imports that made a web build impossible are now conditional:
  `dart:ffi` (via `lib/src/rust/frb_generated.io.dart`) and `dart:io` (in
  `lib/src/platform/media_channel.dart`).
* `app/lib/src/link/link_offer.dart` — the offer URI codec, checked against the
  same pinned string as the Rust test. Deliberately **no crypto in Dart**: the
  fingerprint is computed by Rust, and a Dart sha256 would be the first step
  toward the second implementation §2(b) rules out.
* A `flutter build web` CI lane.

**What it does not contain, stated plainly.** No web build has been run. The web
target can only reach `FakeComradeRepository` until Phase W2 lands the wasm
bridge — the pairing screen is real UI over a real protocol with no transport
under it yet. No QR has been rendered and no camera has scanned one. No grant has
crossed a relay. `api.rs` is untouched, so nothing in `link.rs` is reachable from
Dart yet.

---

## Appendix A — how every number in §1 was measured

Run from the repository root at `b1b4330`.

```sh
# Dart production and test lines
find app/lib -name '*.dart' ! -path '*/rust/*' | xargs cat | wc -l   # 18,517
find app/test -name '*.dart' | xargs cat | wc -l                      # 7,156

# Compose UI, and the services app/ still leans on
find android/app/src/main -name '*.kt' -path '*/ui/*' -exec cat {} + | wc -l
wc -l android/app/src/main/java/mullu/comrade/call/CallScreen.kt
wc -l desktop/ui/*                                                    # 11,283

# Screen inventory, both sides
grep -rhoE '^fun [A-Z][A-Za-z]*Screen\(' \
  android/app/src/main/java/mullu/comrade/ui/ \
  android/app/src/main/java/mullu/comrade/call/ | sort -u             # 16
ls app/lib/src/screens app/lib/src/screens/chats

# The Sakha hole
grep -n '_notBridged' app/lib/src/data/rust_comrade_repository.dart
```
