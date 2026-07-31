# Attention restoration — the fifth wellbeing pillar

_Analysis + build plan, 2026-07-31. Status: proposal — nothing in this
document is wired yet. Companion to AUDIT §8 (wellbeing north star) and
[`TARA.md`](TARA.md)._

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
- **Grounding breaths**: a 60-second box-breathing screen offered before a
  focus session and inside the gentle-stop card. Deterministic animation,
  no audio, no cloud — Tara-v1 philosophy.
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
