# Attention restoration — the fifth wellbeing pillar

_Analysis + build plan, 2026-07-31. **Status: phases 0, 1, 2 and 4 shipped**
(same day) — see [§7 What shipped](#7-what-shipped) for the as-built map and
what is deliberately still open. Phase 3 (loved-one accountability) remains
blocked on the Sakha pairing UI, as planned. Companion to AUDIT §8 (wellbeing
north star) and [`TARA.md`](TARA.md)._

The ask: help users **reinstate cognitive capabilities — above all a
shortened attention span — eroded by extreme smartphone use, doomscrolling,
and short-video content.**

This document does three things:

1. maps what the codebase already is (and why it is unusually well placed
   for this pillar),
2. states the honesty gates — what an app can and cannot truthfully claim
   about "restoring cognition",
3. lays out a phased plan, each phase mapped to the exact crates, trees,
   runtime commands and screens it touches.

---

## 1. What the codebase is today (analysis)

Comrade is a privacy-first mental-wellbeing companion: a Rust core
(`crates/`) driving Android (Compose), desktop (Tauri) and CLI frontends
through one shared view-model layer (`comrade_ui`). The parts that matter
for this pillar:

| Existing piece | Where | Why it matters here |
|---|---|---|
| **Journal** — encrypted entries with self-reported mood emoji, typed or dictated on-device | `comrade_ui::runtime` (`add_journal_entry` / `journal_entries`), `JournalScreen.kt` | The reflection surface every phase below feeds into. Mood markers are already a minimal, structured signal |
| **Tara** — deterministic on-device reflective companion; `CompanionEngine` trait; opener nudges read journal **mood markers only**, never text | `comrade_core::tara`, `TaraScreen.kt`, [`TARA.md`](TARA.md) | The coaching voice. Its two honesty gates ("not therapy, and it says so"; "on-device or not at all") are the template for this pillar's gates |
| **Encrypted store** — every value sealed (Argon2id + AES-256-GCM), panic-wipe enumerates all tables | `comrade_storage` | Usage data is sensitive behavioural data; it gets the same at-rest guarantee as journal text, and is destroyed by the same wipe |
| **Device-local metrics** — counters with no peer, content or timestamp; no exporter, no endpoint | `comrade_core::metrics` | The established pattern for "measure without surveilling". Attention data follows it |
| **Comrades / presence** — disclosure only to chosen peers, mutual by construction, E2E gift-wrapped, never to a relay | `comrade_core::presence`, [`PRESENCE.md`](PRESENCE.md) | The template for the one social feature proposed below (accountability with a loved one) |
| **Notifications** — per-class channels, per-conversation mute | `Notifier`, Settings | The hygiene layer phase 0 extends |
| **Feed (Sabha/Chitthi)** — public microblogging over Nostr | `comrade_core::sabha`, `FeedScreen.kt` | The one surface that *could* become the thing we're treating — see the tension below |
| **State machine** — pure, no-I/O progressive disclosure | `comrade_state` | The pattern for the focus-session engine: pure Rust state machine, unit-tested, frontends render it |
| **Voice** — on-device Vosk + TTS, wake word, command dispatcher | `android/.../voice/` | Lets wind-down and focus rituals be hands-free ("start a focus session") |

**The structural advantage.** Attention apps usually fail on one of two
axes: they surveil (screen-time dashboards that phone home) or they moralise
(streaks, shame, red graphs). Comrade's constitution — on-device or not at
all, data minimisation, device-local metrics, honesty gates in the UI — rules
out the first failure by construction, and its Tara voice (mirror, don't
lecture) is the antidote to the second. No mainstream screen-time product can
make the claim "your usage data never leaves the device and your partner —
not a server — is your accountability"; Comrade can.

**The tension we must resolve first.** Comrade ships a public feed. Today it
is *accidentally calm*: strictly chronological, no algorithmic ranking, no
autoplay, no like counters, no pull-to-refresh dopamine loop, no infinite
recommendation tail (`FeedScreen.kt` renders exactly what the relays
delivered). A wellbeing app that later "improved engagement" on this surface
would become its own disease. Phase 0 turns today's accident into a tested
contract.

---

## 2. Honesty gates (non-negotiable, mirrors TARA.md)

1. **Not treatment, and it says so.** "Reinstate cognitive capabilities" is
   the user's goal, not a claim we may print. The evidence that generic
   "brain-training" games transfer to real-world attention is weak; the
   evidence that *reducing compulsive phone use, practising sustained
   single-tasking, sleep, and reading longer material* improves attention and
   wellbeing is meaningfully better but still behavioural, not clinical. So
   the pillar is framed as **attention practice and usage change** — never
   "scientifically proven to restore your brain". No gamified cognition
   scores, no IQ/percentile claims. The opt-in explainer says exactly this,
   like Tara's does.
2. **On-device or not at all.** App-usage data (which apps, when, how long)
   is among the most sensitive behavioural data a phone holds. It is read
   locally, stored only in the encrypted store, summarised locally, wiped by
   panic wipe, and **never** leaves the device except as an E2E gift-wrapped
   summary to a peer the user explicitly chose (phase 3) — the presence
   rules, verbatim.
3. **The app must not become the addiction.** No feature of this pillar may
   use variable-reward mechanics, guilt loops, or red "you failed" states.
   Streaks display as gentle continuity ("day 6"), never as loss aversion
   ("don't break your streak!"). A missed day is silence, not a notification.
4. **Comrade's own surfaces stay calm.** The feed contract (phase 0) is a
   permanent regression suite, not a preference.

---

## 3. The plan

Sequenced so every phase ships something complete and useful on its own,
smallest-real-feature first — the same discipline AUDIT §8 used (journal
before companion).

### Phase 0 — Do no harm: the Calm Feed contract *(S, build first)*

Codify what the feed already accidentally is, so it can never regress:

- **Contract** (documented in README + this file, pinned by tests where a
  test can see it): chronological only; no autoplay of any media; no
  engagement counters on cards; no recommendation of content from keys the
  user doesn't already see; composer states publicness (already does).
- **Session-aware gentle stop**: after ~10 minutes of continuous feed
  scrolling in one sitting, one inline card — "Still here with you. Want to
  write instead?" → Journal tab. Device-local timer, no telemetry, dismiss
  remembers for the session. Compose-only change (`FeedScreen.kt`), rule
  extracted into a pure, unit-tested class like `TaraStream`.
- **Quiet hours**: a Settings window (e.g. 22:00–07:00) during which every
  notification channel except calls is silenced. Extends the existing
  per-class channel work in `Notifier`; preference in the encrypted store.
  *(As built: device-local SharedPreferences instead — the notification path
  must decide before the vault is unlocked, and a quiet hour that only works
  once you have opened the app is not a quiet hour. Same reasoning, and same
  trade, as `MutedChats`; what it stores is two integers.)*

*Touches:* `FeedScreen.kt`, `Notifier`, Settings screen, one new pure Kotlin
rule class + tests. No Rust changes.

### Phase 1 — The mirror: see your own usage *(M)*

You cannot change what you cannot see, and the phone already keeps the
ledger. **Opt-in** usage awareness, Android first:

- **Source**: `UsageStatsManager` (needs the special `PACKAGE_USAGE_STATS`
  grant — a Settings deep-link flow, not a runtime dialog; the explainer
  must say what is read and that it never leaves the device → OQ11).
- **What is derived, on-device**: total screen time, unlock/pickup count,
  and time in user-tagged "doom apps" (the user marks which installed apps
  count — we ship *no* hard-coded blacklist of app names, both for honesty
  and because the judgement is theirs).
- **Storage**: daily rollups only (date, minutes, pickups, doom-minutes) in
  a new `attention_days` tree — `comrade_storage` repository methods +
  round-trip and plaintext-leak tests, exactly like the journal's. Raw
  per-app event streams are read, reduced, and dropped — data minimisation.
- **Surface**: a card on the Journal tab (this is deliberate — the mirror
  lives next to reflection, not in a dashboard app of its own): today vs
  your own 7-day median, sparkline, no red, no judgement. The framing is
  always self-relative — "less than your usual Tuesday" — never normative.
- **Tara sees the signal shape, not the substance**: extend
  `JournalSignal`-style minimal input so the opener can say "yesterday was a
  heavy scroll day — want to note how you feel this morning?" It reads the
  rollup numbers only, matching the mood-markers-only precedent.

*Touches:* new `comrade_core::attention` (pure rollup/median logic + tests),
`comrade_storage` tree, `comrade_ui` runtime commands + DTOs
(`attention_days`, `record_attention_day`, `set_doom_apps`), JNI bridge,
Android `UsageStatsReader` + Journal-tab card, opt-in explainer. Desktop:
command surface registered, UI later (same staging as journal/Tara).

### Phase 2 — The practice: focus sessions & long-form reading *(M)*

Attention is trained by doing attention, not by minigames:

- **Focus sessions**: a deliberate single-task timer (25/45/90 min presets).
  Engine is a pure Rust state machine — `comrade_core::focus`:
  `Idle → Running(intent, duration) → Completed | Abandoned` — no I/O,
  fully unit-tested, persisted so an app kill doesn't lie about history
  (`focus_sessions` tree: intent text optional, started/ended, outcome).
  Android surface: start card (type or *dictate* your intent — the Vosk
  pipeline is already there), Do-Not-Disturb offer while running
  (`NotificationManager` DND-access opt-in), a completion moment that hands
  off to a one-line journal note ("what did you actually do?"). Abandoning
  is recorded without ceremony — see gate 3.
- **Progressive duration**: the suggested next duration nudges upward only
  after completions at the current one (simple deterministic rule in the
  engine, tested). This is the "rebuild the span" mechanic, honestly framed
  as practice.
- **Long-read mode**: a distraction-free reader for user-supplied text
  (paste; file import later) that shows *one chapter-sized chunk at a time*
  with no scrollbar-of-infinity, remembers position (`reading_state` tree),
  and offers a journal line at the close. Zero network by construction —
  the user brings the text. (Fetching URLs is explicitly out of scope v1:
  it would drag readability-extraction and network policy into a privacy
  app for marginal gain.)
- **Grounding breaths** (*"Take a deep breath"*): a 60-second box-breathing
  screen offered before a focus session and inside the gentle-stop card.
  Deterministic animation, no audio, no cloud — Tara-v1 philosophy. Two
  additions since, both owner-requested and both disclosed on the screen
  itself rather than left to a changelog:
  - **Haptics** shaped after the Pixel Watch's: the buzz swells through the
    in-breath, fades through the out-breath, and is silent through the hold, so
    the pace can be followed with the eyes closed. A single pulse would say
    "a phase changed" without saying which way it went, which is why the shape
    (not just the timing) is a tested property — `BreathHaptics`, pure and
    JVM-tested for the two things a device would otherwise be the first to
    catch: an amplitude outside the platform's 1..255, and a ramp still
    running when the next phase starts.
  - **It nudges your comrades.** Reaching for a pause raises one *"your
    comrade might need you"* on the phones of the people the user chose —
    the *same* one-bit envelope an abandoned draft sends
    (`docs/PRESENCE.md` §6a), sharing the same 30-minute cooldown. Nobody can
    tell which trigger fired, so this reason disclosed nothing new about
    anyone; and nothing about having used the screen is stored locally, which
    is the promise the pillar made and keeps. The screen says both of these in
    its own note, because a disclosure a person would not expect has to be
    made where they are.
- **Tara prompt families** for attention coaching: implementation-plan
  prompts before a session, reflection prompts after, all behind the
  existing `CompanionEngine` seam so a future on-device LLM (OQ9) inherits
  them. `detect_distress` stays in front, unchanged.

*Touches:* `comrade_core::focus` + `tara` prompt families, two storage
trees, runtime commands/DTOs, JNI, new `FocusScreen.kt` +
`ReaderScreen.kt`, voice command ("start a focus session" →
`CommandDispatcher`).

### Phase 3 — The relationship: accountability with your person *(M, after Sakha pairing UI exists)*

The differentiator no centralised screen-time product can copy. Willpower is
weak; a witnessed commitment is strong:

- **Shared weekly summary**: opt-in, per-peer, the *rollup only* (screen
  hours, doom-minutes trend arrow, focus sessions completed) sent as a
  gift-wrapped envelope over the existing NIP-44/NIP-17 DM channel — the
  exact transport presence beacons ride. Mutual by construction: you see
  theirs only when they also chose you (presence semantics, verbatim,
  including the UI copy "waiting for them to choose you back").
- **Witnessed intention**: "this week I'm keeping evenings off the feed" —
  one line, shared to the chosen comrade, surfaced to both at week's end
  next to what happened. No scores, no winner.
- Granularity of what's shared is a real design question → OQ13. The
  default must be the coarsest useful summary.

*Touches:* a small envelope type beside the presence beacon in
`comrade_core`, `comrade_ui` opt-in/state commands, UI in the loved-one /
Comrades surface. Deliberately sequenced after the Sakha pairing UI (AUDIT
§8 pillar 3) so it lands in a place that already feels like "your person".

### Phase 4 — The long game: trends & honest check-ins *(S–M)*

- **Trends, self-relative**: 8-week local charts of usage, focus minutes,
  mood markers — rendered on-device, exportable nowhere. The point is the
  user noticing "my low-mood weeks are my high-scroll weeks", not us
  telling them.
- **Optional monthly self-check-in**: a few Likert questions on perceived
  attention/compulsion (inspired by, but not presenting as, instruments
  like the Smartphone Addiction Scale — gate 1: we never score against
  clinical cutoffs or say "you are addicted"). Stored like journal entries;
  distress-cue text answers route through `detect_distress` → the existing
  crisis hand-off.
- **Metrics discipline**: feature-usage tallies (sessions started/completed,
  gentle-stops shown/taken) go into `comrade_core::metrics` counters —
  countable, identity-free, wiped by panic wipe, no exporter — so future
  design decisions can be made from something other than vibes, without
  becoming surveillance.

---

## 4. Sequencing & sizing summary

| Order | Phase | Size | Depends on |
|---|---|---|---|
| 1 | 0 — Calm Feed contract + gentle stop + quiet hours | S | nothing |
| 2 | 1 — Usage mirror | M | OQ11 decision |
| 3 | 2 — Focus sessions + long-read + breathing | M | nothing (parallel to 1 if staffed) |
| 4 | 3 — Loved-one accountability | M | Sakha pairing UI (AUDIT §8), OQ13 |
| 5 | 4 — Trends + check-ins | S–M | 1 & 2 shipped |

Every phase follows the house rules: engine logic is pure Rust with tests
first (behaviour, not implementation); storage additions get round-trip +
plaintext-leak + panic-wipe-coverage tests; every bridge command lands in
`comrade_ui` once and is exposed to JNI/Tauri from there; UI copy states its
limits in as many words.

## 5. Open questions (owner decisions, AUDIT-numbered)

- **OQ11 — Usage access.** `PACKAGE_USAGE_STATS` is a special-access grant
  with real optics (it *can* see every app you open). Take it (full mirror),
  or ship a degraded no-permission mode (Comrade-only session data +
  unlock-count via `USER_PRESENT` while running)? Recommendation: offer
  both; the explainer sells nothing.
- **OQ12 — Gentle stop default.** On by default in the feed (with a
  Settings off-switch), or opt-in? Recommendation: on by default — a calm
  product should be calm out of the box; the off-switch keeps it honest.
- **OQ13 — Accountability granularity.** Weekly rollup only, or optional
  daily? Does a doom-minutes number go to the peer, or only a trend arrow?
  Recommendation: start with weekly + trend arrow only.
- **OQ14 — Desktop parity.** The desktop web UI still lacks journal and
  Tara; this pillar widens that gap. Accept Android-first (recommended,
  matches every pillar so far) or block on the web UI?

## 6. What we deliberately do NOT build

- **App blocking / launcher takeover** — needs Accessibility-service
  powers that are both a permissions red flag and an arms race with the OS;
  substitution and friction beat force.
- **Brain-training minigames** — weak transfer evidence; would push us into
  exactly the gamified-claims territory gate 1 forbids.
- **Social comparison / leaderboards** — competitive mechanics on wellbeing
  data are the pathology, not the cure.
- **Cloud analytics of any kind** — gate 2; there is nothing to A/B test on
  a server that is worth the promise it breaks.

---

## 7. What shipped

Built in one pass on 2026-07-31. The plan above is preserved as written; this
section is the as-built record, including where the implementation chose
differently from the sketch and why.

### Engine (`comrade_core::attention`)

Pure, deterministic, no new dependencies, 15 unit tests:

| Piece | What it does |
|---|---|
| `UsageSignal` / `compare_today` | Today's rollup against the user's own median over ≤7 prior days, with `sample_days` so a thin baseline can be *said* rather than faked |
| `usage_opener` | The Tara nudge for a heavy scroll day. Quiet unless yesterday was both ≥60 min in marked apps **and** ≥1.5× the user's own median (≥90 min with no baseline yet) — an ordinary day never earns a comment |
| `FOCUS_PRESETS`, `suggest_focus_minutes` | The progressive ladder: 25 → 45 → 90, each rung unlocked by **two** completions at that length or longer. Abandoned and lapsed sessions cost nothing (gate 3 — no loss aversion) |
| `FocusOutcome`, `resolve_stale`, `remaining_secs` | The session lifecycle, including the grace window past which an unattended session becomes `lapsed` rather than a claimed completion |
| `focus_prompt`, `focus_reflection` | Turn-rotated intention nudges and close-out lines; abandoned/lapsed get plain acknowledgement, never guilt |
| `chunk_reading` | Chapter-sized chunking for the reader, **lossless by test** (`chunks.concat() == text`), preferring paragraph then whitespace boundaries, UTF-8 safe |

`ReflectiveCompanion::opener_with_usage` holds the precedence rule in one
place: **mood outranks usage.** Two low journal days this week is a heavier
signal than yesterday's screen time, so the usage line only ever replaces the
*generic* openers — it can never displace the low-mood invitation. Keeping this
in the engine rather than in a frontend is why two UIs cannot decide it
differently.

### Storage (`comrade_storage`)

Four new trees, all sealed exactly like the journal: `attention_days`
(date-keyed rollups), `focus_sessions`, `reading_state` (one slot),
`attention_meta` (the doom-app list). Tests cover round-trip, ordering,
upsert-in-place, legacy-row deserialisation, **ciphertext-at-rest** for the
focus intent and the saved text, and that the **panic wipe reaches all four** —
a wipe that left a usage ledger behind would betray precisely the users it
exists for.

### View-model (`comrade_ui`) and bridges

16 runtime commands + 4 DTOs, exposed identically over uniffi (Android) and
Tauri (desktop). 12 lifecycle tests, including that every command fails closed
with `VaultLocked`. Three decisions worth naming:

- **The frontend owns the calendar.** `record_attention_day` and
  `attention_summary` take a `YYYY-MM-DD` string, because only the frontend
  knows the device's timezone. The Rust side validates the *shape* and uses a
  UTC `iso_date` only to answer "is the newest row still today's?" for the Tara
  nudge — an approximation whose worst case is delaying one journaling nudge
  near midnight, which is why no timezone database was pulled in for it.
- **Reads may write.** `active_focus_session` resolves and persists a lapse, so
  every caller gets one consistent answer instead of each frontend inventing
  its own staleness rule.
- **A lapsed session ends when its plan did**, not when someone finally looked
  — stamping "now" would credit hours nobody was present for, and the ladder
  reads that history.

### Android

| Surface | Notes |
|---|---|
| **Calm-feed contract** | Documented on `FeedScreen` and now load-bearing: chronological only, no ranking, no autoplay, no counters. Plus the gentle stop — one inline card after 10 unbroken minutes, dismissible for the sitting, quoting **no duration and no count** (a number invites a score). Rule in `attention/ScrollSitting.kt`, 8 tests |
| **Quiet hours** | `attention/QuietHours.kt` + `NotificationPolicy`: silences messages, requests, presence and update notices in a nightly window — **never a ringing call.** 6 tests on the window arithmetic, where the overnight wrap is the normal case and an empty window (`start == end`) silences nothing rather than the whole day |
| **Usage mirror** | Opt-in `PACKAGE_USAGE_STATS` behind an explainer that states what is read and that it cannot leave the device. `attention/UsageMirror.kt` reduces the event stream to three integers **in memory** and drops it (8 tests, including that overlapping stretches count once, so a day can never exceed itself, and that Comrade excludes its own screen time). Card lives on the Journal tab, self-relative, no red |
| **Focus tab** | New 4th nav slot: sessions with a named intention, optional DND (priority filter, so calls still ring), countdown, and a close-out that offers to keep the reflection as a journal line. Plus the chunked reader and the *"Take a deep breath"* screen (haptic-paced, and it tells your comrades you might need them) |
| **Voice** | "start a focus session [for N minutes]" — digits only, because a half-parsed "forty five" would start a session nobody asked for; the bare phrase uses the engine's own suggestion. 5 new grammar/dispatch tests |

### Desktop

The Focus tab (`desktop/ui/index.html` · `main.js` · `focus_view.mjs`) — a
third nav slot beside Sabha and Vault, carrying the two halves of phase 2 that
need nothing from Android: focus sessions and the long read. The decisions are
in `focus_view.mjs` with 12 `node --test` cases, following the
`call_decisions.mjs` / `media_cache.mjs` split, because `main.js` is DOM glue
and cannot be tested.

Three things it deliberately does not do:

- **No usage mirror.** The rollups come from Android's `UsageStatsManager` and
  the store is per-device, so a desktop panel could only ever read zero. A
  panel that always says zero is a worse answer than no panel.
- **No local countdown.** The clock re-reads `active_focus_session` once a
  second rather than counting down in JS, because that call is also what
  resolves a session which outlived its plan into a *lapse*. A local timer
  would count into the negatives on a machine that slept.
- **No opinion about the ladder.** Which durations exist is now
  `ComradeRuntime::focus_presets`, the one attention command that needs no
  vault (the rungs are a constant of the design, not the user's data). Android
  and desktop both read it; neither keeps a list. `chosenPreset` refuses to
  select a length outside it, and an engine test pins that every offered rung
  is one `suggest_focus_minutes` can actually suggest back — the previous
  hardcoded `listOf(25, 45, 90)` on Android was a second copy waiting to drift.

Verified locally against the real CI lanes: `cargo fmt --check`, `clippy
--workspace --all-targets -D warnings`, `cargo test --workspace` (490 tests),
the desktop Tauri clippy lane, `node --test desktop/ui/*.test.mjs` (90 tests),
and `./gradlew test` (247 Android JVM tests) — which also regenerates the
uniffi bindings, so it is what proves `focusPresets()` actually crosses the
FFI. Still unverified anywhere: nobody has *used* the desktop tab against a
real vault; it is exercised only by its unit tests and the browser-preview
mock.

### Open, and honestly so

- **Phase 3 (loved-one accountability)** — unbuilt by design; it depends on the
  Sakha pairing UI, which does not exist yet (AUDIT §8 pillar 3). OQ13 stays
  open with it.
- **OQ11** answered as "offer it, never require it": the whole pillar works
  with usage access refused — only the mirror card is absent.
- **OQ12** answered as "on by default": the gentle stop needs no permission and
  costs nothing, so a calm product is calm out of the box.
- **OQ14** answered as Android-first, then partly closed: the desktop web UI
  now has a **Focus tab** — sessions and the long read, the two halves that are
  platform-neutral. See "Desktop" below. The journal and Tara are still absent
  there, and so is the usage mirror.
- **No on-device trend chart yet.** Phase 4's *storage and API* shipped
  (`attention_days` keeps the full history and `attention_days()` returns it),
  and the monthly self-check-in did not — it is a small addition on top of the
  journal once someone wants it.
- **Not verified on a physical device.** The Android lanes here are JVM unit
  tests and (in CI) emulator smoke tests; `UsageStatsManager` behaviour varies
  across OEM builds, and the pickup count in particular deserves a real-handset
  sanity check before anyone quotes it as precise.
