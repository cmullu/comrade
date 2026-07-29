# Screen inventory — what the two frontends do, and what the unified app does

Phase 3 (UI reconstruction) working document. Every screen the Flutter app has
to carry, read directly out of the two implementations it replaces:

- **Android / Jetpack Compose** — `android/app/src/main/java/mullu/comrade/`
  (4,838 LOC of UI across 13 files)
- **Desktop / vanilla-JS SPA** — `desktop/ui/` (`index.html` 431,
  `main.js` 2,335, `styles.css` 1,755)

Two things this document is for: (1) so nothing is silently dropped in the
port, and (2) so every place the two platforms **disagree** is decided on
purpose, in writing, rather than by whichever file the porter happened to read
first. §3 is the divergence ledger; it is the part worth arguing with.

> **Status.** `flutter analyze` is clean and `flutter test` is green (80 tests)
> against the in-memory fake. Nothing here has been run against the real Rust
> core, on a real device, or on a desktop window manager — the bridge does not
> exist yet. Read §5 before believing anything is finished.

---

## 1. Feature matrix, before and after

| Surface | Android | Desktop | Unified app |
|---|---|---|---|
| Vault door / onboarding | ✅ username + passcode + confirm | ⚠️ passphrase only | ✅ Android's flow + desktop's reveal toggle |
| Chat list | ✅ | ✅ (sidebar list) | ✅ list, two-pane ≥840 px |
| Conversation | ✅ | ✅ | ✅ |
| Message requests | ✅ banner + inbox | ✅ inline in sidebar | ✅ banner + inbox |
| New chat / directory search | ✅ | ✗ | ✅ |
| Encrypted media | ✅ inline image/audio/video | ⚠️ download-then-view | ✅ inline images; audio/video via a platform delegate |
| Voice notes (record) | ✅ hold-to-talk | ✗ | ✗ — see D13 |
| Sabha feed | ✅ | ✅ + char cap | ✅ union |
| Journal | ✅ | ✗ | ✅ |
| Tara | ✅ | ✗ | ✅ |
| Call UI | ✅ full (routes, camera, PiP, SAS, quality) | ⚠️ mute + hangup + SAS | ✅ Android's, engine behind a seam |
| Call history | ✅ | ✗ | ✅ |
| Settings | ✅ | ⚠️ TURN modal only | ✅ Android's superset |
| Couple sandbox / Partner Portal | ✗ | ✅ | ✅ |
| Off-Grid / Travel workspace toggle | ⚠️ voice command only | ✅ switch | ✅ |
| Mesh status | ✅ banner | ✅ sidebar pills | ✅ both |
| Voice / wake word / model download | ✅ | ✗ | ✗ — stays native, see D21 |

---

## 2. Screen by screen

Each entry: **tree · state · interactions · backend · notes.** Backend names are
`ComradeRepository` methods (`app/lib/src/data/comrade_repository.dart`), which
map 1:1 onto `ComradeCore.kt`'s methods and `commands.rs`'s commands.

### 2.1 Onboarding / vault door
`onboarding_screen.dart` ← `ui/OnboardingScreen.kt` (233) + `index.html#screen-vault`

- **Tree**: centred card (max 380 px, desktop's `.vault-card` measure) — brand
  mark, subtitle, `username` field (create/claim only), `passcode`, `confirm`
  (create only), inline error, submit.
- **State**: `vaultExists` (from `AppPhase`), local `_claimOnly`, `_busy`,
  `_reveal`, `_error`.
- **Interactions**: submit; reveal/hide passphrase; a legacy vault that unlocks
  with no username falls into the claim-a-handle step.
- **Backend**: `unlockVault`, `setUsername`.
- **Validation** (client-side, mirrored from Kotlin, re-validated in Rust):
  handle 3–24 chars `[A-Za-z0-9_]`, passcode ≥6, passcode == confirm.

### 2.2 Shell / navigation
`home_shell.dart` ← `MainActivity.kt` (896) + `index.html` sidebar + `styles.css:311-390,1665-1740`

- **Tree, <840 px**: `Scaffold` → app bar (hamburger / back / conversation
  header) · mesh banner · body · `NavigationBar` (4 destinations) · FAB ·
  `Drawer` (profile header + Call history, Partner Portal, Settings).
- **Tree, ≥840 px**: `Row` → sidebar (brand + workspace badge · 4 destinations ·
  MORE group · relay/mesh pills · identity chip) │ divider │ column(mesh banner,
  header, body). Chats becomes list+detail.
- **State**: `_tab`, `_chatNav` (list/newChat/requests), `_secondary`,
  `openConversationProvider`.
- **Interactions**: tab switch; drawer; back chain (secondary → chat sub-screen
  → open conversation → exit); voice/video call from the conversation header;
  alias editor; copy npub from the identity chip.
- **Backend**: none directly; delegates to the screen providers.
- **Detail kept**: the conversation view owns the whole screen on a phone
  (no bottom bar under it) — Telegram-style, as in Compose.

### 2.3 Chat list
`chats/chats_list_screen.dart` ← `ChatsScreen.kt:142-238`

- **Tree**: requests banner (only when count > 0) → rows of
  `PeerAvatar · title / "You: "+last · relativeTime`, divider inset 76 px.
- **State**: `conversationsProvider` (`AsyncValue<List<ConversationInfo>>`),
  `messageRequestCountProvider`; `selectedPeer` highlights the two-pane row.
- **Interactions**: open a conversation; open the requests inbox; new chat
  (FAB / header action); empty-state CTA.
- **Backend**: `conversations`, `messageRequests`.
- **Events**: reloads on `IncomingDirectMessage`, `IncomingMedia`,
  `PeerProfileUpdated`, `MessageStatusChanged`.

### 2.4 Conversation
`chats/conversation_screen.dart` ← `ChatsScreen.kt:412-993` + `main.js:580-717`

- **Tree**: `Stack`[ `ListView` of merged items · jump-to-latest FAB ] → error
  line → reply chip → composer(attach · pill field · send).
- **Item**: optional `DaySeparator` (inside the item, so indices still match
  the merged list) then either a `MediaAttachmentBubble` or a `MessageBubble`
  (quoted preview · text · `clockTime` · status ticks when outgoing).
- **State**: `conversationProvider(peer)` → `{messages, media, items,
  replyingTo, sending, attaching, error}`; local `_loadedOnce`,
  `_knownItemCount`, `_newMessagesBelow`, `_atBottom`.
- **Interactions**: send; long-press or hover → reply; cancel reply; attach;
  jump to latest; scroll.
- **Backend**: `messages`, `media`, `sendDm`, `sendMedia`,
  `markConversationRead`.
- **Load-bearing detail — the scroll rule.** Commit `a76bacf` ("stop yanking
  readers to the bottom"). Auto-scroll happens **only** on first load, or when
  the reader was already near the bottom; otherwise the jump-to-latest button
  lights up. The rule is `isNearBottom` / `isNearBottomByOffset` in
  `util/chat_thread.dart`, unit-tested. The desktop SPA does the opposite —
  `log.scrollTop = log.scrollHeight` on every render (`main.js:624`) — and that
  is the bug the Compose fix was written against.

### 2.5 Message requests
`chats/requests_screen.dart` ← `ChatsScreen.kt:995-1095`

- **Tree**: rows of `PeerAvatar · shortNpub (monospace) · last message` +
  Block / Accept.
- **Backend**: `messageRequests`, `acceptRequest`, `blockConversation`.
- **Detail kept**: a request row shows the **raw key**, never a published
  handle. A stranger's self-declared name is exactly what an impersonation
  attempt would set, and this screen is where the trust decision happens.

### 2.6 New chat
`chats/new_chat_screen.dart` ← `ChatsScreen.kt:240-410`

- **Tree**: query field → (key detected ? "Start chat with npub1…" : Search) →
  the honesty paragraph about directory relays → results → Contacts.
- **Backend**: `searchProfiles`, `addContact`, `contacts`.
- **Detail kept**: starting a chat pins the **key only** (trust-on-first-use);
  the alias stays the user's to set.

### 2.7 Call history
`chats/call_history_screen.dart` ← `ui/CallHistoryScreen.kt` (147)

- **Tree**: rows of `PeerAvatar · title · "Incoming · 3:05 · 2h"` · media icon.
- **State**: `callHistoryProvider`, `contactsByNpubProvider`.
- **Backend**: `callHistory`, `contacts`.
- **Detail kept**: `missed|declined|busy|failed` tint the media icon with the
  error colour, so a missed call reads at a glance. Newest first, no
  client-side sort (the core already returns that order).

### 2.8 Journal
`journal_screen.dart` ← `ui/JournalScreen.kt` (330)

- **Tree**: composer card (multiline field · 5 mood chips · Save · the
  "only on this device" line) → day-grouped entry cards with delete.
- **Backend**: `journal`, `addJournalEntry`, `deleteJournalEntry`.
- **Not ported**: Vosk dictation (D21).

### 2.9 Sabha feed
`feed_screen.dart` ← `ui/FeedScreen.kt` (155) + `main.js:307-407`

- **Tree**: composer (field · "Public — anyone can read this" · counter ·
  Post) → Chitthi cards (`author` / `You` · relative time · body · reply hint).
- **Backend**: `sabhaTimeline`, `broadcastChitthi`.
- **Events**: `IncomingChitthi` is **prepended**, never re-fetched, so a busy
  relay cannot reset the reader's scroll.

### 2.10 Tara
`tara_screen.dart` + `state/tara_providers.dart` ← `ui/TaraScreen.kt` (415) + `ui/TaraStream.kt` (71)

- **Tree**: opt-in explainer, or thread(bubbles · crisis cards · pending user ·
  thinking · streaming) + composer card(field · Send · the "not a therapist"
  footer · Clear).
- **State**: `taraOptInProvider`, `taraProvider` → `{thread, opener, resources,
  pendingUser, thinking, streaming, error}`.
- **Backend**: `taraThread`, `taraSend`, `taraOpener`, `taraCrisisResources`,
  `clearTaraThread`.
- **Load-bearing detail — streaming vs crisis.** An ordinary reply is paced out
  word by word (`streamTaraReply`, cumulative-prefix `Stream<String>`) so the
  companion reads as thinking out loud. A reply the engine flagged `crisis` is
  published **whole, in one state update** — helpline numbers must never be
  half-rendered while an animation catches up. Both branches are covered by
  `test/tara_screen_test.dart`, which asserts a crisis turn publishes exactly
  one *distinct* frame and that no frame is ever a strict prefix.
- The chunker is lossless by construction and property-tested for it
  (`chunkText(t).join() == t` at every chunk size). It iterates **runes**, not
  UTF-16 code units, so an emoji cannot be split in half — a small improvement
  on the Kotlin original.

### 2.11 Settings
`settings_screen.dart` ← `ui/SettingsScreen.kt` (691) + `index.html#modal-turn`

- **Cards**: profile (avatar · @handle · full key · Copy key · the
  "names repeat, keys don't" paragraph) → background connectivity switch →
  TURN relay → lock vault → "In the lab" + `core vX`.
- **Backend**: `setUsername`, `turnServerStatus`, `setTurnServer`,
  `testTurnConnectivity`, `lockVault`, `version`.
- **Load-bearing detail — the TURN card is write-only** (AUDIT COMMS-02). The
  URL round-trips; the username and credential go in and are **never read
  back**, because nothing in the core exposes them. Re-opening the editor shows
  those two fields blank, and the dialog says so. There is no getter to add
  later — that is the property, not a gap.

### 2.12 Call overlay
`call_screen.dart` + `state/call_providers.dart` ← `call/CallScreen.kt` (676) + `call/CallUiState.kt`

- **Phases**: Ringing (accept/decline or cancel) · Connecting/Active (one
  subtree — see below) · Ended (terminal card).
- **Active tree, video**: main renderer · tappable PiP tile (swaps; hosts the
  camera-flip control) · name+timer pill · quality badge · SAS row · control
  bar (mute · route · camera · end) over a scrim.
- **State**: `callProvider` → `{state, muted, cameraOn, audioRoute,
  availableRoutes, quality, sasEmojis}`.
- **Details kept**: one composition subtree for Connecting **and** Active, so
  the video surfaces are not destroyed and recreated exactly as the first
  frames arrive; the local track mirrors wherever it renders and the remote
  never does; the quality dot appears only for MEDIUM/POOR; the SAS row appears
  only while Active and only with a real derived code — `null` renders as
  nothing, never as a fabricated code.
- **Not implemented**: the media engine itself. See D29 / §5.

### 2.13 Couple sandbox
`couple_screen.dart` ← `index.html#screen-couple` + `main.js:1835-2012`

- **Tree**: pairing form (partner npub · Sakha/Sakhi · Pair & enter), or the
  sandbox (header · ledger panel · media note). Two panels side by side above
  1600 px, mirroring `styles.css:1735`.
- **Backend**: `sakhaStatus`, `pairSakha`, `sakhaAddEntry`, `sakhaReadLedger`,
  `syncLedger`; live refresh on `LedgerUpdated`.

---

## 3. Divergence ledger

Every place the two frontends disagreed, and what the unified app does. "Why"
is the part that matters; a divergence resolved without a reason is a
divergence that gets re-litigated.

| # | Divergence | Android | Desktop | Decision | Why |
|---|---|---|---|---|---|
| D1 | **Display-name order** | alias → @handle → key (`peerTitle`) | @handle → key (`displayName`; no alias concept at all) | **Android** | Desktop's is a strict subset. The alias is the only name the *user* chose; a published handle is a self-declared claim by the peer. Dropping the alias would make every name spoofable. |
| D2 | `shortNpub` cut | head 10 + tail 4, above 16 chars | head 11 + tail 5, above 18 | **Android** | Its exact output is pinned by a unit test, and the conversation header renders it beside a 36 dp avatar where 4 tail chars still fit on a phone. |
| D3 | **Conversation header** | avatar + title + npub tail (monospace) | `displayName(peer)` only — no key anywhere | **Android** | The key is the identity; a header showing only a self-declared handle is the exact shape of an impersonation. The unified header **always** shows the tail. Tested. |
| D4 | **Bubble timestamps** | wall clock `HH:mm` under day separators | relative "3m ago" per bubble, no separators | **Android** | Deliberate fix (`a76bacf`). A relative stamp drifts while the screen is open; a clock under a day header does not. |
| D5 | **Auto-scroll on reload** | only if first load or already near the bottom | `scrollTop = scrollHeight` unconditionally | **Android** | The Compose behaviour *is* the fix for the desktop behaviour. Reading history must not be interrupted by someone else's message. |
| D6 | Delivery ticks | glyph from status; **missing status → ✓** | `✓` only for exactly `"sent"`; `STATUS_RANK` stops a late "delivered" undoing "read" | **Union** | Android's glyph rule (a tick-less outgoing bubble reads as "didn't send") **plus** desktop's rank guard, which Android never needed because it re-read the whole thread from the store. Live-event updates need it. Tested. |
| D7 | Reply affordance | long-press | hover button | **Both** | One codebase serves touch and pointer. Long-press has no discoverable equivalent with a mouse; a hover target has none with a finger. |
| D8 | Unknown reply target | render no quote | render "Original message" | **Android** | A placeholder implies we know something about a message we cannot see. |
| D9 | **Message requests** | banner → dedicated inbox screen | inline list in the contacts sidebar, Accept/Block per row | **Android** | Accept shares your @handle with a stranger. Inline buttons in a scrolling sidebar make that one mis-tap away. |
| D10 | Request row identity | `shortNpub` only | `displayName(peer)` (may show a handle) | **Android** | Same reasoning as D3, at the moment it matters most. |
| D11 | **Media plaintext** | images → bounded in-memory LRU; audio/video → `cacheDir/media`, purged on background (AUDIT S-4) | object URLs, never revoked | **Android, improved** | Flutter decodes images from bytes, so the unified app keeps *everything* in a bounded in-memory LRU and writes **nothing** to disk. One whole class of at-rest leak stops existing, and there is no purge to forget to call. |
| D12 | Media auto-load | images auto-load | everything needs "Download & view" | **Android** | Images are the common, low-risk case; a tap-to-load image feed is worse UX for no privacy gain (the fetch is E2E either way). |
| D13 | **Voice notes (record)** | hold-to-talk mic in the composer | none | **Neither, for now** | Press-and-hold is meaningless with a mouse, and `VoiceRecorder` is a `MediaRecorder` wrapper. Received voice notes still render and play. Filed with the platform-channel work. |
| D14 | Attachment picker filter | `*/*` | `image/*,audio/*` | **Android** | The core sends arbitrary MIME types already; the desktop filter is narrower than the backend. |
| D15 | UPI `/pay` preview | none (voice command only) | live debounced `extract_payments` in the composer + chips under bubbles | **Desktop — carried but not yet wired** | `extractPayments` is on the repository interface; the composer preview is **not** re-implemented in this pass. An honest gap, not a decision. |
| D16 | Feed length cap | none | 2,000 chars + live counter | **Desktop** | Without it a post is silently rejected by the relay. |
| D17 | Feed "is this mine?" | compares `author == "you"` — a sentinel it invents when optimistically prepending, which never matches a real npub | compares against `state.identity.npub` | **Desktop** | Android's comparison cannot match a real event; it only ever works on the optimistic local copy. |
| D18 | Feed reply marker | ignored | `↳ reply to abc123…` | **Desktop** | The DTO carries `reply_to`; dropping it loses real information. |
| D19 | TURN card | status + Edit + **Test relay connectivity** diagnostic | modal only, no status, no test | **Android** | Strict superset, and the diagnostic is the difference between "calls fail" and "calls fail *because the relay is unreachable*". |
| D20 | Vault lock | "Lock vault now" | none | **Android** | The deliberate, user-initiated version of what process death does by accident. |
| D21 | **Voice / wake word / model download** | ~1,300 LOC of Android services | none | **Neither — stays native** | No cross-platform on-device recogniser. The Android settings screen's own rule is "no fake switches"; a mic button that cannot listen is worse than no mic button. The "In the lab" copy now says so on every platform. |
| D22 | Journal · Tara · Call history · Onboarding | ✅ | ✗ (commands registered, no caller) | **Ported** | This is the actual parity debt `docs/FRONTEND_STRATEGY.md` §2 identified. |
| D23 | **Couple sandbox** | ✗ ("engine level only, not usable from the app yet") | ✅ working against real commands | **Ported** | Both statements were true *of their own platform*. Desktop proves the engine works end to end, so Android's "in the lab" copy is the stale half — and has been updated. |
| D24 | **Onboarding** | username + passcode + **confirm**, validated | one passphrase field, "any passphrase forges a brand-new vault" | **Android + desktop's reveal toggle** | On desktop a typo'd passphrase silently creates a *second empty vault* rather than reporting a wrong password. Confirm-on-create is the fix. The reveal toggle is desktop's and worth keeping: a long passphrase typed blind on a desktop keyboard is not otherwise verifiable. |
| D25 | **Colour source** | Material You dynamic colour on Android 12+, brand palette as fallback | brand palette always | **Brand palette everywhere** | Otherwise the two platforms render visibly different products. The call, crisis and status colours are load-bearing — a wallpaper-derived "error" container is not guaranteed to read as alarming. Dynamic colour can return later as an explicit opt-in. |
| D26 | Light theme | full light + dark schemes | dark only | **Both, following the system** | Desktop's dark-only look is a stylistic default, not a requirement; a phone in daylight is a real use case. |
| D27 | **Navigation model** | bottom nav (Chats · Journal · Feed · Tara) + drawer (Call history, Settings) | sidebar (Sabha · Vault) + Modes group + status footer | **Both, by width** | Below 840 px (the width `styles.css:1668` already folds at) the Android chrome; at or above it, the desktop sidebar. Same widget tree, same state. |
| D28 | Section naming | Chats / Feed | Vault / Sabha | **Chats / Feed** | Plain-language labels for navigation; the product's own vocabulary ("Chitthi", "Sabha", "Hisab-Kitab") stays in body copy where it teaches rather than gatekeeps. |
| D29 | **Call controls** | mute · audio route menu · camera · flip · PiP swap · SAS · quality · proximity blank | mute · hangup · SAS | **Android's UI** | Desktop's is a subset because a webview has no audio-route API, not because anyone decided a call shouldn't have one. |
| D30 | Ended call | terminal card with the outcome | overlay just disappears | **Android** | "No answer" vs "Declined" vs "Couldn't connect" are different facts. |
| D31 | Error reporting | inline text next to the control | toast for everything (`safeInvoke`) | **Inline, plus SnackBar for background events** | A toast for a failed send disappears before it can be acted on, and does not say *which* send. |
| D32 | Mesh status | persistent banner under the top bar | two pills in the sidebar footer | **Both** | The banner is the mobile-relevant one (it is what you check with no signal); the pills fit the desktop chrome. |
| D33 | Event delivery | `ChatEventRouter` + integer "tick" counters screens re-read on | direct listeners per handler | **Direct listeners** | Riverpod invalidation is the tick counter, minus the global-refresh problem (a tick fired for *any* conversation reloaded *every* open one). |

---

## 4. Architecture as built

```
lib/
  main.dart                     composition root — picks the repository
  src/
    app.dart                    theme + door/app phase switch
    data/       models · ComradeRepository (interface) · FakeComradeRepository
    state/      Riverpod: providers · chat · tara · content · settings · call
    theme/      comrade_theme.dart (Theme.kt + styles.css) · breakpoints.dart
    util/       display_name · chat_thread · tara_stream  ← pure, unit-tested
    widgets/    peer_avatar · message_bubble · media_attachment · app_chrome
    screens/    one file per surface
```

**The backend seam.** Every screen depends on `ComradeRepository`, never on the
generated bridge. `FakeComradeRepository` (in-memory, seeded with two days of
believable history) makes the whole app runnable and every screen widget-
testable with no native library — which the Compose screens never were, since
they called the `ComradeCore` singleton directly and so needed instrumented
tests. When `package:comrade/src/rust/api.dart` lands, add one adapter class
and change one override in `main.dart`.

**Responsive.** `Breakpoints` encodes the widths the existing CSS already uses
(840 fold, 1600 ultrawide, the `clamp()` measures for the sidebar, the
conversation list and the reading column). `ListDetailPane` is the single
primitive that makes Chats a pushed screen on a phone and a two-pane layout on
a desktop window — and it re-evaluates on `MediaQuery`, so dragging a window
across 840 px swaps the chrome live. Tested.

**Platform seams.** Three things pure Dart cannot do are declared as interfaces
with do-nothing defaults that say so out loud rather than failing silently:
`CallEngine` (media capture + video views), `MediaPlaybackDelegate` (audio/video
playback, open-externally), `AttachmentPicker`. A sibling workstream is building
the Kotlin side of these in `app/lib/src/platform/` + `app/android/.../channel/`;
wiring them together is a `main.dart` override each.

---

## 5. What is *not* done

Stated plainly, because a UI that looks finished and isn't is the expensive
kind of wrong.

1. **No real backend.** Everything runs against `FakeComradeRepository`. The
   Rust bridge adapter is deliberately not written (inventing the generated
   API's shape would create a third export surface the real codegen would then
   contradict — `docs/FRONTEND_STRATEGY.md` D5).
2. **No media engine.** `CallEngine`'s default does nothing. The call *UI* is
   complete and driveable; a call is not. This is D3 of the strategy document
   and it is the largest single unresolved item in the migration.
3. **No audio/video playback, no file picker, no file open-out.** Delegates
   with honest "not wired up on this platform yet" messages.
4. **No voice note recording, no dictation, no wake word** (D13, D21).
5. **No UPI `/pay` composer preview** (D15).
6. **Preferences are in-memory.** Tara's opt-in and the background-connectivity
   toggle do not survive a relaunch yet — `AppPreferences` is the seam.
7. **Vault path is a constant.** Android resolved `filesDir/comrade-vault`,
   desktop `appDataDir()/comrade-vault`; that belongs in the bridge, not in the
   UI doing path arithmetic.
8. **Screenshot blocking (`FLAG_SECURE`) is not reimplemented.** It is an
   Android window flag; it belongs on the native side.
9. **Never run.** `flutter analyze` is clean and 80 tests pass in the
   Flutter test harness. No device, no desktop window, no golden tests, no
   real relay.
