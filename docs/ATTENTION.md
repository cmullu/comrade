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
  *(Also as built, after a correction: "silenced" means **posted without a
  sound**, not withheld. The first implementation returned a plain `false` from
  `NotificationPolicy`, which meant a message arriving at 02:00 never reached
  the shade at all and was discoverable only by opening the app —
  indistinguishable, from the user's side, from delivery having failed. The rule
  now returns `NotifyDecision.Silent` for anything that is still true in the
  morning (messages, requests, update notices) and `Suppress` only for what will
  not be — presence and nudges, both of which expire in minutes and would
  otherwise be found at 08:00 asserting something about last night.)*

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
- **Grounding breaths** (*"Take a deep breath"*): a paced-breathing screen —
  **4 in, 4 held, 4 out, 2 to settle** — offered before a focus session and
  inside the gentle-stop card, one minute by default and settable to two, three
  or five. Deterministic animation, no audio, no cloud — Tara-v1 philosophy.
  Several additions since, all owner-requested and all disclosed on the screen
  itself rather than left to a changelog:
  - **There is a pause after the exhale, and it is deliberately the short
    one.** For its first two releases the cycle ran `% 3` — in, hold, out, and
    straight back into the next inhale with no pause at all — while this
    document and the screen's own note both said "four counts in, four held,
    four out". Three sides is a triangle, and it felt like one from a handset:
    *"we immediately breathe out and breathe in immediately… it feels forced."*

    The first fix made it a true 4-4-4-4 box, which is what the copy had been
    claiming. Then the question was whether four is right, and the literature
    says no: a hold after an **exhale** is not the mirror of a hold after an
    inhale. It starts from a smaller gas reservoir, so CO₂ reaches the breaking
    point sooner; Hering–Breuer stretch-receptor inhibition of inspiratory drive
    is strongest right after an inhale and absent after an exhale, so the urge
    to breathe arrives earliest there; and anxiety is associated with heightened
    CO₂ sensitivity and the *shortest* voluntary hold times — meaning the phase
    most likely to produce air hunger is the one the people opening this screen
    tolerate least. Separately, slower is not automatically calmer: paced-
    breathing vagal effects peak near 5.5–6 breaths/min, and a head-to-head
    trial found 4-4-4-4 (3.75/min) produced *higher* heart rate and *higher*
    perceived exertion than 6/min. At 14s the cycle is ~4.3/min.

    So the settle is two counts. That makes the sides unequal, so **it is no
    longer a box** and nothing may call it one; `BreathPhase` carries a
    per-phase duration, `breathingPhase` walks cumulative boundaries instead of
    dividing, and tests pin the four numbers, the empty-hold-is-shorter
    property, the breaths-per-minute band, and that an inhale never follows an
    exhale directly. Citations live in `BreathPhase`'s KDoc. The empty pause has
    its own word on screen — *Settle*, not a second "Hold" — because two phases
    of different length sharing a label leave the reader unable to tell which
    one they are in.
  - **Haptics** shaped after the Pixel Watch's: the buzz swells through the
    in-breath, fades through the out-breath, and is silent through the hold, so
    the pace can be followed with the eyes closed. A single pulse would say
    "a phase changed" without saying which way it went, which is why the shape
    (not just the timing) is a tested property — `BreathHaptics`, pure and
    JVM-tested for the things a device would otherwise be the first to catch:
    an amplitude outside the platform's 1..255, and a ramp that does not land
    exactly on the phase boundary.

    The ramp **spans the whole phase** rather than firing at the top of it, so
    the buzz is faintest when the circle is smallest and strongest when it is
    fullest: they are two renderings of one breath, and following either is
    following both. It was a 540ms burst until an owner sat with it on a
    handset — a burst says "a phase began", which you can only obey after the
    fact, and is useless with your eyes shut, which is when this is for.
  - **The circle grows into the in-breath.** It opens small and fills, because
    that is what an inhale is; it shipped opening *full* for a fortnight,
    because `animateFloatAsState` starts at its first target and the opening
    phase targets full. Now an `Animatable` the screen seeds itself, and both
    holds snap rather than easing so the still phases are still.

    **One curve, two renderings.** The motion was `LinearEasing` for several
    releases so that it kept pace with a haptic that ramped linearly. The premise
    — circle and buzz must not drift — was right; making them both straight was
    the wrong way to honour it. A constant rate meant the circle reached empty
    still travelling at full speed and then stopped dead for the settle, reported
    from a handset as the change from out-breath to settle *"not being natural"*.
    A breath has no sharp edge where it turns around. Both now read the same
    `BreathHaptics.curve` — sinusoidal ease-in-out, zero velocity at each end,
    fastest in the middle, which is very nearly the airflow profile of a real
    breath — so they cannot diverge without that one function returning something
    different to both. It also makes the boundaries forgiving: the circle is
    barely moving as it arrives, so the holds' snap has almost nothing left to
    absorb. Tests pin the curve's endpoints, its symmetry, its monotonicity, and
    that the eased amplitude ramp is still strictly increasing — the flattened
    ends leave increments of exactly 1, and a swell that repeated a value would
    read as a stall.
  - **Paired lines that ride the breath.** Eight pairs: an inhale line to draw
    on, an exhale line to put down. The inhale half appears at the top of the
    breath and holds through the pause after it; the exhale half takes over as
    the out-breath starts and holds through the settle. So the text turns over
    **twice a cycle**, on the two phases that actually ask something of the
    reader, and never mid-pause.

    **The rough edge was layout, not the fade.** A change of line read as a
    stutter, and the crossfade was not the cause: the lines are not all the same
    length, so some wrap to two rows and some to one, and in a centred column a
    shorter line pulled the circle, the progress bar and the chips up by a row and
    dropped them back on the next change. The 200dp circle was hopping, not the
    text. The line now reserves two rows (`minLines`), which costs a row of blank
    space under the short lines and makes the layout hold still. Both fades are
    also deliberately slow for a UI — 900ms for the line, 450ms for the phase word
    — and the phase word is crossfaded at all, where it used to hard-cut: it
    changes four times a cycle in the dead centre of the thing being watched, so
    it was the most abrupt edge on the screen, and it landed at exactly the moment
    the circle is meant to be gliding to a stop.

    This replaced one line every two cycles. That earlier rule — "a sentence
    changing every few seconds is one more thing to keep up with" — was written
    for the attention-practice framing; the owner's stated purpose is anxiety,
    panic and stress *as well as* practice, and for that the line should ride
    the breath rather than sit beside it. **Repetition became acceptable in the
    same move**: five minutes is 21 cycles against eight pairs, so the set comes
    round two and a half times, and for someone waiting out a bad few minutes a
    line they have already read is nearer a mantra than a screen running out of
    things to say. `theSetRepeatingOnALongSitIsAllowed` exists to stop a future
    reader "fixing" that by reference to the rule it replaced.

    The register is **reaffirming, and specifically takes the pressure off** —
    *"you don't have to feel better yet"*, *"you don't have to do this well"* —
    because panicking about not being calm yet is a documented way for panic to
    feed itself, and a screen implying they were doing it wrong would be joining
    in. What the lines may **not** do is reassure about anything the app cannot
    know: no *"you are safe"*, no *"there is nothing wrong with you"*, no *"this
    is just anxiety"*. Comrade has no idea whether these symptoms are a panic
    attack or a cardiac event, and a calming screen that talked someone out of
    getting help would be the worst thing in this repository. What is left is
    true either way: that they are here, that they reached for something, that
    nothing more is being asked of them this minute. Still no claim about what
    breathing achieves, and still nothing the reader can get wrong.
  - **You choose how long, and it stops when you said.** Duration chips
    (1 / 2 / 3 / 5 min, defaulting to one) and a progress line with no digits on
    it. One minute is the default because the screen is reached for
    mid-something and the shortest useful pause is the one a person actually
    takes; the chips exist because someone who came here deliberately should not
    have to keep re-opening it. Changing the length mid-sit extends or ends the
    sit in progress rather than restarting it. The bar carries no number, for
    the same reason nothing else on this screen does — but a five-minute sit
    with no feedback at all is one you cannot tell you are two minutes into.

    For its first releases the clock **kept counting past the chosen length** on
    the stated reasoning that being done was "a thing the button says, not a
    thing that freezes the circle mid-breath". From a handset that read as the
    screen ignoring the minute it had just been given: *"it doesn't seem to be
    stopping after 1 min."* A duration the screen offers is a promise to stop,
    and leaving someone mid-panic to decide when to get out is the decision they
    came here to be relieved of. So the sit ends: the ticker stops, the haptic
    is **cancelled** rather than left to expire (its last ramp had up to four
    seconds still to run), the circle goes still at its smallest, and the screen
    says *Rest* over a line that asks for nothing further. Nothing
    auto-navigates — ending the pace is not the same as closing the screen, and
    a longer chip picks the pace back up from where it stopped.

    A minute is not a whole number of breaths, so a run is rounded to the
    **nearest whole cycle** (`breathingRunSeconds`): 1 min is 56s, 2 min is
    126s. Stopping on the raw count would freeze the circle part-grown four
    seconds into a fifth inhale with the buzz mid-ramp; ending on a boundary
    means the last thing that happens is an out-breath and a settle. Nearest
    rather than up, so the chips are never off by more than half a cycle and
    never in only one direction — for the person who picked one minute *because*
    they had one minute, overshooting is the worse half of that trade.
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
| **Quiet hours** | `attention/QuietHours.kt` + `NotificationPolicy`: in a nightly window, messages / requests / update notices post **silently** (`CHANNEL_MESSAGES_QUIET`, `IMPORTANCE_LOW`) and presence / nudges are dropped as already-stale — **never a ringing call.** 6 tests on the window arithmetic, where the overnight wrap is the normal case and an empty window (`start == end`) silences nothing rather than the whole day |
| **Usage mirror** | Opt-in `PACKAGE_USAGE_STATS` behind an explainer that states what is read and that it cannot leave the device. `attention/UsageMirror.kt` reduces the event stream to three integers **in memory** and drops it (8 tests, including that overlapping stretches count once, so a day can never exceed itself, and that Comrade excludes its own screen time). Card lives on the Journal tab, self-relative, no red |
| **Focus tab** | New 4th nav slot: sessions with a named intention, optional DND (priority filter, so calls still ring), countdown, and a close-out that offers to keep the reflection as a journal line. Plus the chunked reader and the *"Take a deep breath"* screen — paced breathing at 4-4-4-2, 1–5 minutes (rounded to whole breaths, and it stops when the time is up), haptic-paced, and it tells your comrades you might need them |
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

---

## 8. What shipped next: the shelf, and the body (2026-08-14)

Two changes, both to the phase-2 surface, driven by the same complaint from the
owner: **the long read was not useful, because it was fed by copy-paste.**

That was a fair verdict on a real defect, and the diagnosis is not subtle. The
reader was built to be handed text one article at a time. The articles people
mean to read properly are *already saved* — in Instagram's bookmarks, in X's, in
Facebook's saves, in a browser's reading list — so asking for them again asked
someone to do the collecting twice, in the app that is supposed to be reducing
what their phone asks of them.

### 8.1 The reading shelf (`comrade_core::library`)

The reader now reads off a **shelf**, and the shelf fills itself two ways that
need no account, no API key and no network:

| Route | What it is | Where |
|---|---|---|
| **Share sheet** | Share a page — or a text selection — into Comrade from any app. Android's `ACTION_SEND` (`text/plain`), parked by `AppNavigation.requestShare` because the shelf lives inside the vault and a share can arrive locked | `AndroidManifest.xml`, `MainActivity.acceptShare` |
| **Export archive** | The file Instagram, X, Facebook and Reddit are obliged to hand over. Picked with `ACTION_OPEN_DOCUMENT` on Android or a file input on desktop, read in the frontend, parsed in Rust | `library::import_saves` |
| **Paste** | Still there, and now one row among many rather than the only way in | `save_shared` |

**Why export archives and not APIs.** Every one of those platforms can be read
programmatically — with an OAuth app, a client secret, a per-request round trip
to their servers, and their terms of service. Comrade would then be an app that
promises not to phone home and ships four API clients, which is not a trade gate
2 leaves available. A data export is a file, and a file is something a pure
function can read on a plane. The cost is real and the UI says it in as many
words: **an import is a snapshot, not a sync.**

**Why the JSON importer walks the shape instead of matching it.** One struct per
platform with `serde` derives is the obvious implementation and the one that
breaks silently: these schemas are internal formats that change between export
versions, and a mismatched `Deserialize` fails as *"0 items imported"* —
indistinguishable, to the person holding the file, from Comrade being broken. So
`import_saves` walks arbitrary JSON for the *shape* of a save (a URL-valued
field, with a title and a timestamp near it, inherited down the tree so
Instagram's account-name-two-levels-up survives). One tested code path covers
Instagram's `saved_posts.json`, X's `bookmarks.js` (assignment prefix and all),
Facebook's `saved_items.json`, Reddit's and Pocket's CSV, Pocket's HTML, and a
bare list of URLs — and a file it cannot parse at all degrades to "we found the
links in it" **and says so**, because titles are lost on that path.

**The boundary that had to be said out loud.** Nothing fetches a URL. An
imported bookmark is a title and a link, so a row with no text cannot be opened
in the reader — it offers the link and a way to paste the article in. That rule
is `ShelfDecisions.rowActions` on Android and `shelfRow` on desktop, both tested,
because the failure mode of getting it wrong is an empty reader, which reads as a
broken app rather than as a deliberate limit. A library that quietly fetched
every URL it was given would be a crawler wearing a reading app's clothes.

Storage: one new sealed tree (`library_saves`) plus an `OpenRead` pointer in
`attention_meta`. The legacy one-slot `reading_state` is **migrated** on first
read and cleared — onto the shelf, not into the reader, because someone who
updates the app should not be dropped back into chunk 14 of something without
asking.

API note: `clear_reading` is now `close_reading` and keeps the article;
`delete_saved_item` is the one that forgets. Two verbs, because there is more than
one thing on the shelf to delete now.

### 8.2 Stretch breaks (`comrade_core::stretch`)

The pillar had paced breathing for the nervous system and a timer for the mind,
and nothing for the part of a long session that actually hurts: an hour still in
one chair, neck forward, shoulders up. Opera Air's neck exercises are the
reference, and the reason to take the idea is not fashion — a break someone will
*take* has to be short, guided and impossible to fail, and "get up for a bit" is
none of those.

Six routines — neck, shoulders, wrists, back, eyes, stand up — 45 to 130 seconds
each, offered on the half hour of a session and **never in its last eight
minutes** (interrupting the final stretch of work is how this feature gets
switched off). The engine ships step *keys* and durations, never prose, so the
words stay translatable and live in `strings.xml` / `stretch_view.mjs`; it also
owns the walk over the steps, asked once a second by both frontends, so neither
can drift onto its own timeline.

Four gates, two of them enforced by tests rather than by intention:

1. **No claims.** The copy may say what a movement *is*, never what it treats.
   `stretch_view.test.mjs` greps every line for `cure|treat|corrects|prevents|…`
   and for "push through", because those are the two ways stretch copy goes wrong
   and neither is a review anyone remembers to do.
2. **Symmetry is not optional.** A test asserts both sides of every one-sided
   movement get equal time and the same motion. It caught the neck routine on its
   first run — the check had paired a *turn* with a *tilt*'s mirror, which is the
   bug the test exists for, in the test.
3. **No breath-holding or end-range forcing.** Every movement is self-limiting.
4. **Nothing is recorded.** Like breathing: no count of breaks taken, because a
   number would turn a break into a task.

### 8.3 The Focus surface has a mood now

The tab was deliberately the plainest surface in the app, on the reasoning that
gate 3 forbids celebrating a completion and a plain screen cannot celebrate
anything. **The premise is right and the conclusion was too strong.** What gate 3
forbids is *reward mechanics* — a score, a streak, a red state — and none of
those is the same thing as the surface being pleasant to sit in front of. Opera
Air is the reference for the difference: soft light, slow motion, translucency,
nothing to beat.

Two adaptations rather than a copy:

- **Not white.** That browser is light and airy; this app is dark-mode-first, and
  flipping one screen to white would strobe someone's eyes on every tab switch at
  night. So the same idea in this app's own key — three big soft gradients
  drifting behind translucent panels, bloom instead of glare, at the theme's own
  accents (`FocusAmbient` on Android, `.focus-ambient` on desktop, with the same
  three drift periods on both so they breathe at the same rate).
- **The countdown is a ring that empties.** A ring that filled up would invite
  "how much have I earned", which is precisely the scoring this pillar refuses. A
  ring draining is only where the time went.

**Reduced motion stops all of it.** `prefers-reduced-motion` on desktop,
`ANIMATOR_DURATION_SCALE == 0` on Android — the closest thing the platform has.
The rule lives in `ambientBlobs()` and `FocusAmbient.reduceMotion` rather than
only in a stylesheet, and it has a test, because it is the one thing here that
would otherwise be pretty instead of calm: this is the tab someone opens when
they are already scattered.

### 8.4 What is verified, and what is not

Ran here: `cargo fmt --check`, `cargo clippy --workspace --all-targets -D
warnings` **on stable 1.97** (the sandbox image was on 1.94, and 1.97 has a
`collapsible_match` case the older one does not — the trap in `CLAUDE.md` is
real), `cargo test --workspace` (34 new tests), `node --test desktop/ui/*.test.mjs`
(440, 27 new), and the two new Kotlin decision files compiled and run against
JUnit through the `kotlinc` route in `CLAUDE.md` (17 tests).

Not verified here, and not claimed to be: `desktop/src-tauri` clippy (no
GTK/webview headers in this sandbox), `./gradlew test`, the APK, and anything
about how either screen *looks*. In particular **nobody has run an import against
a real export archive** — the parsers are tested against hand-written fixtures
shaped like the real files, which is not the same thing as the real files, and the
first genuine Instagram export is likely to find something. The fallback path
exists precisely because that is expected.

### 8.5 Still open

- **The reader cannot fill a bookmark by itself, and will not.** The honest
  consequence is that an imported Instagram shelf is mostly links. Whether that
  is worth revisiting — with an explicit, per-item, opt-in fetch behind the same
  kind of feature gate as `media-http` — is an owner decision, not a default.
- **Text arriving for a link you already have makes a second row.** Found while
  reviewing this change, not fixed: `save_text` always inserts, so an imported
  bookmark plus a later share of the same page's text is two shelf entries for one
  article — the duplication `dedupe_key` exists to prevent, on the path most likely
  to hit it. The fix is for `save_text` to fill in an existing *text-less* row with
  the same URL (and to leave a row that already has text alone, because two
  selections from one article are two texts). Android's "Add the text" flow deletes
  the bookmark after saving to work around this, so it has to change in the same
  pass or it would delete the row it just filled.
- **No dedupe against what was read *before* an import.** A save deleted from the
  shelf and then re-imported comes back. Deleting a row is not the same as saying
  "never again", and a tombstone list is a second thing to keep in the vault.
- **`app/` (Flutter) has none of this.** It has no Focus surface at all, so this
  is not a regression there; it is a gap that widens.
