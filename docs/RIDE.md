# Ride — two seats, one engine, and the few things worth saying

_Added 2026-08-14, from an owner request: "the right mode for people who are
riding together — a driver and a pillion, connected on the Comrade app. They
might want to listen to music, have a chat since the engine noise is higher.
So a subset of the music player's features along with navigation or route
suggestions."_

Two people on one motorcycle are the closest two Comrade users ever get, and
the hardest pair to connect. Wind and engine noise bury speech; the driver's
hands and eyes are spoken for; and a phone call between two helmets is a worse
intercom than a tap on the shoulder. What actually needs to travel is tiny —
*slow down*, *fuel soon*, *left at the next junction* — and it needs to land as
a glance, not a conversation.

This document is the design record: what travels, what deliberately does not,
and which half of it is checked before CI.

---

## 1. What this is not

**Not a satnav.** There is no routing engine behind any of this, and the
vocabulary refuses to borrow one's authority. A pillion looking at their own
map taps *left*, *400 m*, *after the petrol pump*; the driver sees a
suggestion from a person, not an instruction from a machine. The word
"navigation" appears in no string on the screen.

**Not a second music player.** `docs/TOGETHER.md` already holds two playheads
in step, and the argument there — that the feature is a clock, not a pipe —
is unchanged and inherited whole. Ride mode adds **no wire protocol for
music at all**. It decides one thing the Together tab cannot: which of its
controls a person steering a motorcycle may be offered.

**Not a location feature.** No coordinates travel, ever. See §3.

## 2. The wire protocol

`comrade_core::ride` — pure, framework-free, 9 unit tests. An eighth control
envelope on the convention documented in `comrade_core::dm`, riding the same
NIP-44/NIP-17 gift-wrapped DM channel as receipts, profile shares, call
signals, presence beacons, nudges and together envelopes:

```jsonc
{ "comrade_ride": 1, "ttl_secs": 60,
  "signal": { "kind": "route", "maneuver": "left",
              "distance_m": 400, "note": "after the petrol pump" } }
```

Two signal kinds, and that is the whole vocabulary:

| Signal | Carries |
|---|---|
| `quick` | One phrase from a fixed catalog — `slow_down`, `pull_over`, `fuel_soon`, `break_please`, `all_good`. |
| `route` | A `maneuver` (`left`, `right`, `straight`, `u_turn`, `stop`, `hazard`), optionally `distance_m`, optionally a short `note`. |

**A one-shot signal, not a session — and that is a decision, not an
economy.** Together is a session because it defends *shared state* (one
playhead) against feedback between two devices. A ride signal has no shared
state to defend: each one is complete in itself, acted on once, and false a
minute later. Modelling it as a session would buy Lamport arbitration nobody
needs, at the cost of a state machine to establish, TTL and tear down.
`comrade_core::nudge` is the shape that fits, and this module mirrors it:
marker, TTL measured from send time, receiver-side clamp, nothing persisted.

**The phrases are a catalog, not free text**, because the reader is doing
80 km/h. A fixed set renders in one glance-sized line, is translated once per
frontend rather than once per message, and carries its own urgency (§4) so
every frontend buzzes the same way for the same thing. The catalog grows by
adding a variant in core — which updates every frontend's tables through a
test — not by opening a text field.

The one free-text field is the route note, capped at `RIDE_NOTE_MAX_CHARS`
(80 characters, counted as **characters** so the limit means the same thing in
Devanagari as in ASCII). Over-long is **refused, not truncated**: silently
delivering a shortened landmark is worse than delivering none, because the
sender believes they said the whole thing.

## 3. What deliberately does not travel

**No coordinates. No heading. No speed.** A route suggestion is what the
pillion *chose to say*, not where either phone is. This is
`docs/TOGETHER.md` §2's filename argument in another shape: a position is
something nobody decided to send, it is the most sensitive thing either device
holds, and it is **not needed** to say "left at the next junction". The module
has no dependency on `comrade_core::geo` and no permission to ask for.

The claim is a test, not a comment:
`a_quick_signal_round_trips_and_carries_exactly_its_stated_fields` asserts the
exact JSON key set at both levels, so adding a field to the envelope fails the
build.

Absent options are **absent keys, not nulls** — a receiver must not learn
"they chose to say nothing" as a distinct value from "they said nothing".

## 4. Urgency is decided once, in core

Every signal carries an urgency derived in `comrade_core::ride`:

| | Urgent | Notice | Info |
|---|---|---|---|
| Phrases | `slow_down`, `pull_over` | `fuel_soon`, `break_please` | `all_good` |
| Maneuvers | `hazard` | everything else | — |

It is derived on arrival rather than carried on the wire — a sender cannot
claim its own message is an emergency — and it is decided in core rather than
per frontend for the reason ADR-2 exists: two phones disagreeing about whether
"pull over" is worth a buzz is two riders disagreeing about what was said.

What the frontends do with it, and where those rules are pinned
(`RideDecisions`, 14 JVM tests):

- **Urgent** — spoken aloud, two long pulses a glove can feel.
- **Notice** — one short tap, no speech.
- **Info** — nothing. "All good" is an answer, not an alert.

**Only the driver's phone speaks.** The pillion is looking at their screen by
definition, and two phones announcing the same thing is an echo inside one
helmet-to-helmet distance.

## 5. Replay safety

The worst bug this feature could have is a two-day-old backfilled "left in
400 m" rendered huge on a moving driver's screen. Three guards, each tested on
its own:

| Guard | What it alone prevents |
|---|---|
| **Acceptance gate** (accepted conversations only, returning either way) | A stranger putting "pull over" on your screen — and a control envelope surfacing as a message request full of JSON. |
| **Age gate** (60 s from *send* time, peer TTL clamped to 5 min) | The entire two-day inbox backfill, which is inert by construction. |
| **Event-id dedup** | A relay's at-least-once redelivery buzzing twice for one tap. |

`RIDE_TTL_SECS` is 60 s, matching `TOGETHER_SIGNAL_MAX_AGE_SECS` and for the
same stated reason: a replayed signal here is acted on with **no confirmation
step**.

**Nothing is persisted**, and that is load-bearing rather than tidy. The board
lives in memory. A relaunch starts empty the way it starts with no together
session — and "pull over", stored, is only somewhere for it to be wrong later.

The screen enforces the same deadline a second time: a card older than
`CARD_STALE_MS` (60 s, deliberately equal to the wire TTL) is **gone, not
dimmed or crossed out**. Stale here is not old news; it is false.

## 6. One card, because a driver gets one glance

`RideDecisions.card` ranks rather than lists. A fresh quick phrase owns the
card for `QUICK_SHOW_MS` (8 s) because it is about the riders, now; the route
suggestion is what the card falls back to; and an unknown wire name from a
newer build is **skipped, never blank-carded** — including when skipping it
reveals a perfectly good route suggestion underneath, which is a test.

## 7. The music, and which half of it a driver gets

No new wire protocol — a Together session is already running or it is not.
What ride mode decides is the control surface:

| | Play/pause | Next | Previous | Scrubber |
|---|---|---|---|---|
| Driver | ✅ | ✅ | ❌ | ❌ |
| Pillion | ✅ | ✅ | ✅ | ✅ |

**No scrubber for the driver** — a drag target under a thumb at speed is a
swerve. **No previous, either**, and that one is worth stating because it looks
arbitrary: nobody rewinds a song at 80 km/h on purpose, and a mis-tap does not
just restart the track here, it restarts it on the other seat's phone too.

The ride screen **cannot start a session**. The Together tab owns choosing what
plays (`docs/TOGETHER.md` §16 made that deliberately one way in), and a second
entry point here would undo it.

Talking over the music needs nothing new either: `TogetherManager.toggleMic`
already opens a voice-only connection in every mode (§16, "Talking over it"),
which is the actual answer to "have a chat since the engine noise is higher"
whenever both hands are free enough to press one button.

## 8. Where the code lives

| Layer | What it owns |
|---|---|
| `comrade_core::ride` | Wire protocol, the catalog, the caps, the urgency verdicts, the TTL arithmetic. Pure; 9 unit tests. |
| `comrade_ui::runtime` | `ride_send_quick` / `ride_send_route` (each a `RuntimeHandles` twin), the receive arm in `dispatch_incoming_dm`, and **one** `BridgeEvent` variant. |
| `comrade_jni` | The two send calls over **uniffi only**. The frb surface gets the DTO and the event variant, because the `BridgeEvent` enum must still compile there — but not the sends: `app/` has no ride screen to call them from, and a bridge function nothing calls still costs a regeneration (the rule `together_stream_content` already states). |
| `android/…/ride/RideDecisions.kt` | Pure: the card ranking, staleness, distance words, speech and haptic rules, the per-seat control set, the composer tables. 14 JVM tests. |
| `android/…/ride/RideSignals.kt` | The live board; speech and haptics fire here, on arrival, not on recomposition. |
| `android/…/ui/RideScreen.kt` | Widgets and nothing more. |

**One `BridgeEvent` variant for the whole vocabulary**, not one per phrase —
the tax `BridgeEvent::TogetherShare`'s doc comment warns about, avoided the
same way. Adding a phrase to the catalog touches core and the two decision
tables, and no bridge.

## 9. What is built, and what is not

**Built and verified in the development sandbox:** the protocol and its
arithmetic, the runtime commands and the gated receive path (including a
two-peer test over a real in-process relay, and the stranger-gate and
stale-signal cases), both FFI bridges with the frb bindings regenerated, the
Flutter lane brought back to green, and `RideDecisions` compiled and run
against JUnit.

**Built but *not* verified here, and this needs saying plainly:** everything
under `android/` that touches the framework — `RideScreen.kt`, `RideSignals.kt`,
the `RelayConnectionService` arm, the `ComradeCore` wrappers and the
`MainActivity` drawer entry. `.claude/scripts/android-typecheck.sh` could not
run in the session that wrote them: it fetches androidx from
`dl.google.com`, which that container's network policy refused (403 at the
proxy), and Maven Central does not mirror androidx. So CI is the first
compiler these files meet. Reason from the code; do not read "tested" wider
than the list above.

**Not built:**

- **Any Flutter or desktop surface.** `app/` maps the event to `null` with a
  comment, exactly as it does the Together events; desktop has no ride screen.
  Android is the priority frontend (`CLAUDE.md`), and a mode for a motorcycle
  is the clearest case yet of a phone-only feature.
- **A foreground service.** Ride mode holds no player of its own — the
  Together session's service already keeps music alive when the screen is
  off — but *speech and haptics stop when the screen closes*, because
  `RideSignals` arms them on `setActive`. A ride with the phone in a pocket
  therefore delivers nothing audible. Closing that is a real follow-up and it
  is a foreground service, not a flag.
- **A "we have arrived" or any notion of a trip.** There is no trip object,
  no start, no end, and no history. Each signal stands alone.
- **Mesh transport.** Ride signals take whatever `send_control_envelope`
  takes, which is the relay. Two phones on one motorcycle are the best
  possible case for the Saathi mesh (§5a's millisecond tier, one metre apart)
  and the worst possible case for a relay round trip through a patchy mobile
  signal on a highway. This is the single most valuable follow-up, and it is
  the same engine-lifecycle work `docs/TOGETHER.md` §5 already defers.

## 10. Deliberately out of scope

- **Free-text messaging.** That is the chat, and it already exists. The
  catalog is small because the reader is moving.
- **More than two people.** Two-party is what the seat model *is*. A convoy
  is a different feature with a different arbitration story, and nobody asked
  for one.
- **Reading the driver's phone aloud in general.** Ride mode speaks ride
  signals. A mode that read incoming chat into a helmet would be a different
  and much larger promise about attention.
