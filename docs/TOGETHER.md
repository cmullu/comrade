# Together — watching and listening in step

_Added 2026-08-03, from `AUDIT.md` §8.2 (owner request, 2026-07-12)._

You and your person can start something at the same moment and stay there:
play, pause and seek reach the other side, and the two playheads are held
together well enough to laugh at the same joke.

Comrade moves **no media**. Each side plays *their own copy*; all that travels
is a small control envelope saying where the playhead is. This document is the
design record — the wire protocol, what the model can and cannot promise, and
what is deliberately not built.

---

## 1. Why nothing streams

§8.2 put the constraint plainly and it has not softened: re-streaming or
proxying licensed audio or video between two people is a copyright problem and
a bandwidth problem, and DRM'd platform content cannot be frame-synced inside
our app at all — the platforms both prohibit and technically block it.

So the feature is a *clock*, not a pipe. That also happens to be the version
that fits this app: it needs no new infrastructure, no server, and no
relationship with anybody's content.

## 2. The wire protocol

`comrade_core::together` — pure, framework-free, 40 unit tests. A seventh
control envelope on the convention documented in `comrade_core::dm`, riding the
same NIP-44/NIP-17 gift-wrapped DM channel as receipts, profile shares, call
signals, presence beacons and nudges:

```jsonc
{ "comrade_together": 1, "session_id": "9f3c…", "seq": 7,
  "at_ms": 1754160000123,
  "echo": { "your_at_ms": 1754159990031, "my_recv_ms": 1754159990402 },
  "signal": { "kind": "state", "pos_ms": 2520000, "playing": true } }
```

| Signal | Meaning |
|---|---|
| `start` | "Watch this with me, and here is where I am." The invitation and the opening position in one, so a joiner never joins to nowhere. **The only signal that can create a session.** |
| `join` | "I'm in." Carries no position — the joiner adopts the leader's. |
| `state` | Play, pause *and* seek. |
| `heartbeat` | Where I am now, and the last command I had applied when I said so. |
| `end` | "I'm leaving." Also the decline. |

**Play, pause and seek are one signal** because all three reduce to a position
and whether it is running. Which one it *was* is derived by one tested function
(`describe_state_change`) rather than by three frontends each guessing.

**What the envelope deliberately does not carry.** `end` has no reason —
bored, phone rang, app closed is not the other person's to learn, and "they
left" is the whole signal. A local file is identified by **its length and
nothing else**: no filename, no path, no size, no hash. A digest would
fingerprint the exact release someone holds and cost seconds of work on the
device for an answer we do not need; a filename carries release group, language
and sometimes a path fragment, and does not even answer the question (two files
called `movie.mkv` are not the same film). Length is enough to warn *"their
copy runs four seconds longer than yours"*, and deliberately not enough to
claim "same file" — which we cannot know and must not imply. The optional
label is whatever the sender typed, shown in an editable field before it goes,
so naming the film is a deliberate act.

That claim is a test, not a comment: `a_local_file_is_identified_by_its_duration_and_nothing_else`
asserts the exact JSON key set, so adding a field to the envelope fails the
build.

A YouTube id is the asymmetric case and is named rather than hidden: it *is*
fully disclosing, because it is publicly resolvable. It is also validated in
core on send **and** receive, because a peer-supplied string ends up in an
`<iframe src>` and no UI should have to remember that.

## 3. The clock, and why "< 300 ms" is not a target we can hold

To compare two playheads you must know how far apart the two *clocks* are.
Nothing in the transport tells you: a gift wrap's outer timestamp is
deliberately randomised, and the rumor's own timestamp is the sender's claim,
in whole seconds — a full second of noise in a system trying to hold a fraction
of one.

So every heartbeat carries `echo`: the `at_ms` of the last message we heard and
our clock when we heard it. That turns a message we were already sending into
the classic four-timestamp NTP probe at **zero extra messages**.

```
offset_ms = ((t2 - t1) + (t3 - t4)) / 2      // their clock − ours
rtt_ms    = (t4 - t1) - (t3 - t2)            // their turnaround removed
```

`ClockFilter` keeps eight probes in a two-minute window and averages the offsets
of the **best half by round trip** — [beatsync](https://github.com/freeman-jiang/beatsync)'s
filter, and better than a bare minimum for the reason any average is: one lucky
sample stops deciding the answer alone. The *lowest* round trip still sets the
uncertainty, because that is a claim about the best evidence we have rather than
about the mean. A clock *step* on either device shows up as an outlier and ages
out, rather than poisoning the estimate forever.

Two deliberate differences from beatsync, which is the closest prior art and
solves a harder version of the same problem in a browser:

- **Each offset is de-skewed to a common instant before averaging.** Offsets
  taken minutes apart are not samples of one quantity once the two clocks run at
  different rates; averaging them raw would smear the frequency difference back
  into the phase. beatsync has no frequency term to conflict with, so it never
  hits this — here it would be a real error.
- **The probe rides the heartbeat.** beatsync bursts up to 40 dedicated
  measurements at join, against a server. Here there is no server, and every
  message is a persistent gift-wrapped event, so the four timestamps ride traffic
  that was going out anyway.

What beatsync gets right and this design had to adopt is the **burst**: at one
probe per ten-second heartbeat, the first minute of a session runs on a
deliberately pessimistic guess, so the two playheads would be at their furthest
apart exactly when both people are looking. A session now probes every 500 ms
until it has eight of them — about four seconds instead of eighty — and then
settles to the slow tail. Eight rather than forty, because each one here costs a
persistent event rather than a WebSocket frame, and because a paused session
bursts too: the clock has to be converged *before* anyone presses play.

Measuring the offset also measures how wrong it might be, and that gives the
rule the whole module is built around:

> **Never correct by less than your own measurement error.**

The deadband is `max(the player's floor, half the measured round trip)`. Half
the round trip of a public relay routinely exceeds 300 ms, so §8.2's "target
< 300 ms" sits *below the noise floor of the measurement*: we could not tell a
300 ms error from zero, let alone correct it. What this design does hold:

| | typical steady-state drift |
|---|---|
| local file (playback rate can be trimmed) | ±0.3–0.8 s |
| an embedded player that can only seek | ±1.2–2.5 s |
| over a future WebRTC data channel (§8.1) | ±20–60 ms plausible |

`ClockFilter` also tracks **frequency**, not just phase. NTP disciplines both,
and an offset alone is correct only at the instant it was measured — it then
decays at whatever the two crystals differ by. Regressing offset against time
gives that rate in ppm and carries it forward, which is what lets the heartbeat
be slow *and* the sync be tight instead of trading one for the other. It refuses
to guess from fewer than four probes or a baseline under 45 s (over ten seconds,
a millisecond of jitter reads as 100 ppm — five times any real crystal), and
clamps the result, because a clock being *stepped* is a phase event and
extrapolating it as a frequency would push the playhead forever.

The clock estimate is an **input** to `sync_verdict`, not baked into it, so a
lower-latency transport tightens all of this with no policy change and no new
tests.

### Timelines, not positions — the thing everyone else gets wrong

Syncplay and the browser watch-party services trade *positions* — "I am at
42:00" — and act on arrival. That is wrong by exactly the flight time, on every
command, and it is invisible because both sides agree on the number they
exchanged. It is also why none of them beat about a hundred milliseconds.

A position means nothing without the instant it was true at. So a `state` command
carries `effective_at_ms`, and the receiver **evaluates that timeline at its own
now** rather than adopting a stale number: a command that took 400 ms to arrive
lands 400 ms further along. On a transport fast enough to schedule slightly ahead
— the local mesh — the sender instead names an instant a few tens of milliseconds
out and *both* players change state on the same tick, which is how SMPTE and
AES67 do it. `command_apply` returns exactly those two cases and nothing else.

### What a browser cannot do, and what it does better

beatsync schedules through the Web Audio API's `start(when, offset)`, which is
**sample-accurate** — the browser hands the exact frame to the mixer. Android's
`MediaPlayer` has no equivalent: `seekTo` lands on a sample boundary but the
start instant is best-effort, which is a real advantage beatsync holds and an
argument for an `AudioTrack`-based path later.

What a native app can do instead is see the rest of the chain:

### Ear to ear, not decoder to decoder

What a listener hears is the decoder position minus that device's audio output
latency: 20–100 ms on Android, and different between handsets. Two players
agreeing perfectly on decoder position can still be a tenth of a second apart in
the room — the error no browser-based implementation can even see. Both sides
report theirs in the heartbeat, comparison is ear-to-ear, and a seek targets the
decoder position that lands *audibly* in step.

Stated honestly: on Android this figure is currently an **estimate** from the
device's low-latency buffer properties, not a measurement, because `MediaPlayer`
does not hand out its `AudioTrack`. Zero means unmeasured. A true measurement
needs an `AudioTrack` we own — a Media3 migration — and is the honest follow-up.

One more thing that decides what the UI may claim: **listening together is
perceptually much harder than watching together.** Two people in one room half
a second apart is unlistenable; two people in different cities half a second
apart are fine. The goal is reacting to the same moment, not phase-locked
audio.

## 4. Two rules that stop the devices arguing

A shared playhead with no server is a feedback loop waiting to happen. Two
rules close it, and both are tested.

**Only the follower corrects.** The person who sent `start` leads for the life
of the session — which costs zero wire bytes, since it is simply who invited
whom. Only the other side drift-corrects, so the loop provably cannot
oscillate. Both sides still *command* freely; only the automatic correction is
one-sided. The honest cost: a leader with a stuttering connection drags the
follower, bounded by the deadband and the seek cooldown. There is no
serverless fix for that.

**A peer still on an older command is ignored, not answered.** Their position
follows from state we have already superseded, so correcting toward it would
undo our own command. We hold, and our next heartbeat brings them up. This is
`docs/PRESENCE.md`'s `reply: false` rule in another shape, and it is why the
loop converges in one round.

Commands are ordered by a **Lamport counter**, deliberately not a timestamp: a
device whose clock runs a few seconds slow would lose every tie forever, and
its owner would simply experience a pause button that does not work. Ties break
on *pause beats play* — the person reaching for pause has a reason — and then
on the greater npub, which is arbitrary but symmetric and needs no round trip.
The test asserts both devices name the same winner whichever side is asked.

When a command DM is lost entirely, the heartbeat is the repair path: it
carries `applied_seq`, so a peer who is *ahead* of us is adopted wholesale
rather than corrected against.

## 5. Timing

| Constant | Value | Why |
|---|---|---|
| `TOGETHER_BURST_INTERVAL_MS` | 500 ms, for the first 8 probes | The clock must be converged before anyone presses play — see §3. A paused session bursts too, and only the burst does. |
| `TOGETHER_HEARTBEAT_SECS` | 10 s, and **none while paused** | Not a steering knob. Two local players drift by crystal error — tens of ppm, well under a second across a whole film — so this exists to notice a stall or a lost command and to keep the clock filter supplied. |
| `TOGETHER_SESSION_TTL_SECS` | 45 s | More than four heartbeats, so a couple of dropped beacons cannot end a session someone is still watching. A phone that dies mid-film sends no goodbye; this is what that costs. |
| `TOGETHER_SIGNAL_MAX_AGE_SECS` | 60 s | Tighter than the call channel's 90 s, because a replayed call offer produces a ring a human can decline while a replayed playhead moves someone's player with no confirmation step. Wider than the TTL, so the TTL stays the single authority on when a session ends. |
| `TOGETHER_COMMAND_MIN_INTERVAL_MS` | 400 ms | A scrub drag emits ~10 positions a second; sending all of them would be a burst of gift wraps describing somewhere nobody stopped. |

**Transport.** Together signals prefer the **local mesh** when the Saathi engine
is running, falling back to a relay otherwise. This is the single biggest lever
on how tight the sync can be, because the deadband is floored by half the round
trip and a LAN hop is ~1–5 ms against a relay's hundreds. The honest limitation:
the mesh is only up in the off-grid workspace today. Starting it for a session is
engine-lifecycle work (AUDIT A1 / `docs/COMMS_ARCHITECTURE.md` ADR-4) and was
deliberately left out of this change.

**Why the cadence is a real decision, not a default.** Every heartbeat is a
persistent gift-wrapped event, and the vault inbox rewinds two days on *every*
launch (`GIFT_WRAP_TIMESTAMP_SKEW_SECS`). So the cadence decides how much each
later app start re-downloads and re-decrypts — that, not bandwidth, is the
binding cost, and it is why a two-second tick was rejected. **Whether public
relays tolerate even this cadence for two hours is untested**: the in-process
test relay accepts anything. If they push back, the answer is 15–20 s and a
wider deadband, not a cleverer algorithm.

## 6. Replay safety

The worst bug this feature could have is a two-day-old "seek to 42:00" coming
out of the inbox backfill and yanking someone's playhead. It dies three times
over, and each guard is tested on its own:

| Guard | What it alone prevents |
|---|---|
| **Acceptance gate** (accepted conversations only, returning either way) | A stranger driving your player — and a control envelope surfacing as a message request full of JSON. |
| **Age gate** (60 s) | A backfilled `start` re-inviting you. The **only** guard protecting `start`, which is the sole signal that creates state from nothing. |
| **Session scoping** (memory-only, one at a time) | Every other signal, always: after a relaunch this device is in no session, so the entire backfill is inert. |
| **Lamport total order** | A redelivered command being applied twice — exactly and without bound, unlike an LRU. |
| **Invite seen-set** (64 entries) | The one hole the above leaves: a `start` for a session we ended forty seconds ago, redelivered inside the age window. |
| **Session TTL** | A session with a peer whose phone died staying live forever. |

**Heartbeats need no dedup at all** — a statement of state applied twice is the
same state — and commands need none either, because the counter comparison *is*
an exact dedup. That matters practically: at 10 s a two-hour film would
otherwise churn the 512-entry call-signal dedup set several times over and break
call dedup as a side effect. Together adds no pressure to any shared seen-set.

**Nothing is persisted, and that is load-bearing rather than tidy.** The session
lives in memory and `lock_vault` clears it next to the farewell beacons.
Persisting it would reopen the replay hole above: "after a relaunch there is no
session" is one of the three guards. A locked vault is also not watching a film
with anyone, and a command landing after the goodbye would say otherwise.

## 7. How it reads on screen

The failure mode of a sync-play UI is a green tick beside two players eleven
seconds apart, so the vocabulary refuses the words it cannot back:

| State | What it says |
|---|---|
| Invited, no answer yet | `waiting for them to open it` |
| Joined, our copy not open | `open your copy to start` |
| Joined, their copy not open | `waiting for them to open their copy` |
| Both playing, inside the deadband | `together` |
| Correcting | `catching up…` |
| Nothing heard for 90 s | `we've lost track of them` |
| They paused | `Ana paused` |
| Lengths disagree | `their copy runs 4 seconds longer than yours` |

Two rules, both borrowed from `docs/PRESENCE.md` §5 and both pinned by tests:
**never "in sync" and never "synced"** — we do not know that; and when the
heartbeats stop we say we lost track of *them*, because that is what we
observed. We did not observe them diverging.

And a permanent line under the stage:

> Positions travel over the relay, so you'll be within about a second of each
> other — not frame-perfect.

## 8. Where the code lives

| Layer | What it owns |
|---|---|
| `comrade_core::together` | Wire protocol, the clock filter and its NTP arithmetic, `sync_verdict`, the Lamport order, every timing constant and its compile-time invariants. Pure; 40 unit tests. |
| `comrade_ui::runtime` | `together_start` / `together_join` / `together_set_state` / `together_end` (each a `RuntimeHandles` twin, so no bridge holds the lock across a relay round trip), `together_report_position`, `together_session`, the receive arm in `dispatch_incoming_dm`, the session loop, and five `BridgeEvent` variants. |
| `comrade_jni`, `desktop/src-tauri` | The same calls over uniffi / flutter_rust_bridge / Tauri commands. `together_report_position` is the one that is **synchronous and skipped under contention**, because a player calls it several times a second from its UI thread — the trade `note_draft` already makes. |
| `desktop/ui/together_sync.mjs` | Echo suppression, the verdict→player plan, and the status wording. Pure, 20 `node --test` cases. |
| `android/…/together/` | `TogetherDecisions` (pure: echo ledger, scrubber rules, the two `MediaPlayer` footguns — 20 JVM tests mirroring the desktop vectors), `TogetherPlayer` (`MediaPlayer` + `SEEK_CLOSEST`), `TogetherManager` (session, audio focus, service control), `TogetherService` (foreground `mediaPlayback` + framework `MediaSession`). |

Tests worth knowing about: `crates/comrade_ui/tests/two_peer_integration.rs`
drives two real runtimes over one in-process relay and proves both halves —
a full invite → join → pause → leave exchange, and that **a stranger cannot open
a session on someone else's device**. In `runtime.rs`, `a_steady_heartbeat_produces_no_bus_traffic`
pins the claim that a ten-second heartbeat is not a periodic producer on the
critical event bus: the runtime emits only when the verdict is not "hold", so a
session in step says nothing at all.

## 9. What is built, and what is not

Built and tested: the protocol and all of its arithmetic, the view-model, both
FFI bridges, the Tauri commands, the desktop decision module, and the **Android
frontend end to end** — player, screen, entry point in the conversation bar, and
a foreground service so a session keeps playing when the app is backgrounded.

Android specifics worth knowing:

- **`MediaPlayer`, not Media3.** The deciding detail is `seekTo(long,
  SEEK_CLOSEST)`, which needs API 26 and `minSdk` is 26. The plain `seekTo(int)`
  lands on the nearest sync frame — with 5–10 s keyframe spacing that is a sync
  failure dressed up as a working feature. No new dependency.
- **Background playback is real**, via `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and a
  **framework** `MediaSession` (`android.media.session`, API 21) rather than
  `androidx.media3.session` — same media-key routing, no ~2 MB dependency for
  adaptive streaming this feature does not use.
- **Audio focus is honoured**: losing it pauses *and tells the peer*, so what
  they see is "they paused" rather than an unexplained drift.

**Not built**, and stated here rather than discovered:

- **The desktop player surface.** The commands and the decision module are in
  place; the `<video>` element, the file picker and the DOM wiring are not, so
  there is still no way to *start* a session from the desktop UI.
- **YouTube.** The envelope and the id validation support it; no frontend embeds
  one. When it lands on desktop it must be a **bare cross-origin iframe driven by
  `postMessage`**, with the CSP widened by exactly `frame-src
  https://www.youtube-nocookie.com` — and **not** by loading YouTube's
  `iframe_api`, which would need `script-src` and put Google-controlled
  JavaScript inside our own origin, the origin where `withGlobalTauri` exposes
  every registered Tauri command. That distinction is the one line to hold.

  It also needs saying plainly what a YouTube session costs, because it is the
  first time this app would contact a third party during ordinary use: both
  peers' devices reach Google, which learns each IP, the video, and the watch
  timeline — and because sync-play *works*, two IPs hit the same video and pause
  at the same moments. That correlation is the real cost, not the cookie, and it
  is unavoidable by construction. `youtube-nocookie.com` avoids ad cookies and
  watch history; it does not prevent the IP-level record. It should ship off by
  default, behind one disclosure that says this.
- **A measured output latency on Android** — see §3; today it is an estimate.
- **Auto-starting the mesh for a session**, so the millisecond tier is available
  outside the off-grid workspace — see §5.

**On "nanosecond" sync**, since it was asked for directly: it is not reachable by
any software path on two phones, and the reason is three independent floors, each
orders of magnitude above it. The transport (a relay is hundreds of milliseconds;
even the LAN mesh is ~1–5 ms; PTP reaches tens of nanoseconds only with
hardware-timestamping NICs Android does not expose). The player (Android audio
output latency is 20–100 ms, and `AudioTrack.getTimestamp` is accurate to about a
millisecond — you cannot place a playhead more precisely than the player can
report or act on it). And perception (comb filtering becomes audible around
5–30 ms; lip-sync tolerance is ±22 ms). What this design does reach — roughly a
millisecond on a shared network — is below every one of those thresholds, which
is the point at which there is nothing left to win.

## 10. Deliberately out of scope

- **Group watch.** Two-party is what makes the arbitration analysis tractable
  and provable. N-way is a different problem, and nobody asked for it.
- **Streaming anything between peers.** §1. This is the constraint the whole
  design exists inside, not a limitation to be engineered around later.
- **Resuming a session across a restart.** A playhead is a claim about right
  now. "Pick up where we left off" is a media-player feature, and this app does
  not own the player — and persisting a session would reopen §6's replay hole.
- **Reporting buffering.** A stall signalled as a remote pause is the worst
  ping-pong available here: one side stalls, pauses the other, and that pause
  makes the first re-evaluate. A stall is ridden out locally and the next drift
  verdict closes the gap. This will occasionally look worse than it could; it is
  much better than the alternative.
