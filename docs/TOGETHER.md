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
left" is the whole signal. And a local file is **never** identified by a
filename, a path, a size or a digest: a hash would fingerprint the exact
artefact someone holds and cost seconds of work for an answer we do not need,
and a filename carries release group, language and sometimes a path fragment
while not even answering the question (two files called `movie.mkv` are not the
same film).

**What it does carry: a recording.** `{kind:"local", duration_ms, recording?}`,
where a `recording` is `{isrc?, title, artist, album?}`, read from the file's own
**tags** and shown before it goes out, so naming the thing stays a deliberate
act.

The ISRC-first shape is adapted from [Antra](https://github.com/anandprtp/Antra),
which uses the International Standard Recording Code to guarantee exact-recording
matches with a scored title/artist fallback. (Only that idea — Antra is a
downloader, and acquiring content is not something this app does; see §9.) It is
a better answer than the hash on both axes at once:

- **More useful.** A hash answers "is this the same bytes", which is not the
  question — two people can be perfectly in step on different rips of the same
  recording. An ISRC answers "is this the same recording", which is. And because
  it names a recording rather than a file, the receiver can find their **own**
  copy instead of being sent hunting for one.
- **Less revealing.** A hash fingerprints which rip, which release group, which
  personal copy. An ISRC is public catalogue data about a commercial release and
  says nothing about the file on anyone's disk.

`duration_ms` stays: it is needed to clamp an incoming position anyway, and it is
what separates a radio edit from the album cut.

That claim is a test, not a comment:
`a_local_file_is_identified_by_its_length_and_what_the_sender_chose_to_say`
asserts the exact JSON key set at both levels, so adding a field to the envelope
fails the build.

**Matching** (`match_score`, pure and shared by both frontends): an ISRC
agreement is decisive, and an ISRC *disagreement* is equally decisive the other
way. Without one it is a weighted title/artist comparison with duration as a
tiebreak, using **containment rather than Jaccard** — a symmetric measure scores
"Teardrop" against "Teardrop (Remastered)" the same as a different song sharing
one word, because it charges the extra token to both sides. Words that mean a
different *take* (`live`, `remix`, `acoustic`, `instrumental`, `karaoke`,
`cover`, `demo`) are penalised heavily; "Remastered" or a year is not. A length
disagreeing by more than 15 s is a **veto**, not a deduction. The bar for opening
a file on someone's behalf is set high deliberately: opening the wrong one is
worse than asking.

**Links.** `parse_music_link` recognises Spotify, Apple Music and YouTube URLs
and reduces them to what they identify — offline, metadata-only, no account and
no audio. Only a YouTube link is *playable in place* (`playable_in_place`),
through the embed player; for the other two the honest answer is "this tells you
what to open", and a UI that blurred the two would be promising something the app
cannot do.

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

**A trim is sticky, so arriving back inside the deadband has to say so.** This
is the third rule, and it was missing until a soak found it. A player told to
run at 0.96× keeps running at 0.96× until it is told otherwise — neither
`MediaPlayer` nor a `<video>` element resets itself — and `trim_rate` cannot
return `1.0` for any drift large enough to have provoked a correction. So a
verdict of `Hold` on the way back into the deadband left the trim applied: the
follower ran permanently at least 2.5% off speed, coasted out the far side of
the deadband, and was trimmed the other way. Bounded, never divergent, and
never settled either.

`sync_verdict` therefore returns `Nudge { rate: 1.0 }` rather than `Hold` when
a trim is applied and the gap has closed, which is what `SyncSample::local_rate`
is for. Over two simulated hours (`together_soak`) that is **4 rate changes
instead of 191, and 271 ms of worst-case drift instead of 479 ms**. Worth
naming because of *where* it would have been noticed: a ±4% wobble is invisible
on video and an audible tempo and pitch error on music, so "listen together"
would have been the broken half while "watch together" looked fine.

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
| `android/…/together/LibraryResolver.kt` | Finds the listener's own copy via `MediaStore`, scored by the shared `match_score`; reads a picked file's tags so an invitation can name what it is. |
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

Read "tested" narrowly, because it has already been read too widely once. Every
lane in this repo asserts about *values*; not one of them looks at a pixel. Two
bugs shipped through a green board on exactly that gap — a film that played as
sound because nothing gave the decoder a surface, and a session that drew as
floating text over whatever tab was behind it because the overlay had no
background — and both were obvious within a second of opening the app on a
device. What CI can hold is the decision underneath a rendering bug:
`pictureOf`, `aspectRatioOf` and `keepScreenOn` are pinned on both frontends
against the same vectors. Whether anything reached the screen is still a human
with a phone.

Android specifics worth knowing:

- **`MediaPlayer`, not Media3.** The deciding detail is `seekTo(long,
  SEEK_CLOSEST)`, which needs API 26 and `minSdk` is 26. The plain `seekTo(int)`
  lands on the nearest sync frame — with 5–10 s keyframe spacing that is a sync
  failure dressed up as a working feature. No new dependency.
- **The picture needs a surface, and it is not optional.** A `MediaPlayer` with
  no surface decodes video and discards it, so a film plays as sound with no
  error anywhere — which is exactly how this shipped and how it was reported.
  `TogetherPlayer.attachSurface` and `VideoSurface` in `TogetherScreen.kt` are
  the fix. The surface and the player have **independent lifetimes**: the
  surface is destroyed and recreated on every rotation while the session must
  survive both, so the player holds the last surface it was handed and
  re-attaches on `open`, and the holder callbacks are the only thing that
  decides what exists. Detaching passes `null` before the player is released,
  because a destroyed `Surface` the decoder still holds is a use-after-free in
  the media server rather than a leak.
- **Audio-only draws no surface at all.** `TogetherDecisions.pictureOf` reads
  the decoder's reported dimensions — `0` means no video track — because the
  picked MIME type cannot answer it: an `.mkv` of an album is
  `video/x-matroska` and a `.mp4` podcast is `video/mp4`. Desktop makes the
  same call in `together_sync.mjs` from `videoWidth`, against the same test
  vectors, so a `<video>` element does not show a black rectangle over
  someone's music either. Only a *playing* video holds the screen awake; two
  hours of audio must not.
- **Background playback is real**, via `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and a
  **framework** `MediaSession` (`android.media.session`, API 21) rather than
  `androidx.media3.session` — same media-key routing, no ~2 MB dependency for
  adaptive streaming this feature does not use.
- **Audio focus is honoured**: losing it pauses *and tells the peer*, so what
  they see is "they paused" rather than an unexplained drift.

**Not built**, and stated here rather than discovered:

- **The transports for sharing the file** — see below.

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

**On acquiring content — deliberately not built.** Antra's resolution chain (its
own mirror servers, then Tidal / Qobuz / Amazon / Deezer / Apple Music adapters,
then Soulseek) is a downloader, and none of it is adopted. Tidal, Qobuz and Apple
Music do not serve unencrypted audio to third-party clients, so obtaining it
means defeating a technological protection measure — a separate liability from
infringement (DMCA §1201, EU InfoSoc Art. 6, India's Copyright Act §65A) — and §1
already rules the whole area out. What *is* adopted is the identity half: a link
resolves to a recording, and the recording is looked for in the library already
on the listener's device.

That lookup needs a permission, and until 2026-08-05 the app did not ask for one,
so it never matched anything — `AUDIT.md` Q15. Android now declares
`READ_MEDIA_AUDIO` and asks at the moment a song is actually named, at most once,
and only when a local copy would change the answer (`MediaLibraryAccess`). A
refusal costs the automatic match and nothing else: the file picker needs no
permission at all, so every route below still works, and the composer says
Comrade was not allowed to look rather than that the track is absent.

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

## 9a. When only one of you has it

The feature's original shape assumed both people already had the file, which is
often false. Two answers, and they cover different situations:

**Play it from somewhere you do have.** The invitation names a *recording*
(§2), so a device with no local copy can offer the same recording from a source
it can reach. Through the YouTube embed we control the playhead, so the sync is
exactly as tight as it would have been; a deep link into someone's own streaming
subscription degrades to "we started together", because no app lets us drive
another app's playhead. Nothing is transferred, it works between cities rather
than only on one network, and it costs no bandwidth. **This should never happen
silently** — a different master, an ad break, or a different mix is not what the
other person is hearing, and switching source without saying so would be the app
claiming a thing it cannot see.

**Or send it.** `comrade_core::share` is the protocol: chunked, receiver-driven,
resumable, and playable before it has finished arriving.

Receiver-driven is the load-bearing choice. Because the receiver asks for ranges
rather than the sender pushing them, **resume** costs nothing (ask again for
what is missing), **seek** costs nothing (ask for the chunks under the new
playhead first), and the sender holds no per-receiver state, so a dropped
connection leaves nothing to clean up. Requests anchor at the playhead and only
fall back to the earliest gap once the tail is complete — so seeking forward
costs one request, and the session still ends up with a whole file rather than
one with a hole in the middle.

Playable-early is the other half: playback waits for a few seconds of
*contiguous* runway rather than the first chunk (which would stutter a moment
later) or the whole file (which would be minutes for a film). A gap ends the
runway however much lies beyond it, because audio after a hole is not runway —
it is a stutter waiting to happen. The exception is the tail: the last two
seconds of a track are playable even though two seconds is under the threshold,
because nothing more is coming.

**No server, and that is the difference from beatsync.** beatsync has every
client upload to a room on its own backend, which then serves everyone. Comrade
has no backend and should not grow one for this: a server holding and
redistributing copies is both an architectural reversal and the most exposed
possible version of the copyright question. §8.2 rules out *proxying* media
between users; one person handing something to one other person, both already in
an end-to-end conversation, is the existing encrypted-attachment path in a
different shape.

**The transport is WebRTC.** None of the three already in the app can carry
bulk: relay DMs are gift-wrapped control traffic, the media pipeline caps at
10 MiB *and* uploads to a third-party host (exactly the intermediary this must
avoid), and Saathi is gossipsub — a 16 KiB frame broadcast to every peer on the
network, both too small and far too public. A data channel is already
peer-to-peer, already encrypted (DTLS/SCTP), already solves NAT traversal, and is
already a dependency on both frontends. A second bulk protocol of our own over
libp2p would duplicate all of that and still reach nobody outside the LAN.

### 9b. The relay rule, and why it is the centre of this design

`AUDIT.md` §8.1 measures the problem: STUN alone finds a direct path for perhaps
**60–70%** of real-world pairs, and the rest — CGNAT, very common on Indian
mobile carriers — need TURN. That relay is *our own* server (`deploy/coturn/`),
and pushing a film through it would mean paying for every byte twice, putting the
operator's machine in the path of content it has no business carrying, and doing
the exact thing §8.2 calls proxying media between users.

So **the default is direct-only**, and it is enforced twice:

1. **Structurally.** A transfer connection built under `RelayPolicy::DirectOnly`
   is given **no TURN servers at all** (`ice_servers_allowed`,
   `iceServersFor`). A relay candidate is never gathered, so the rule holds even
   if every later check were deleted. An ICE server entry that mixes STUN and
   TURN urls is dropped whole, so one `turn:` url cannot ride in on a `stun:`
   entry.
2. **After connection.** The selected candidate pair is read from
   `getStats()` and classified (`IcePathKind::classify`). **Either end being a
   relay makes the path relayed** — a pair is direct only if both halves are,
   because a remote relay candidate means our packets reach the peer by way of
   *their* TURN server. A pair that cannot be read is `Unknown`, which is
   **refused, never assumed direct**.

The policy is a value, not a branch: `DirectOnly` · `UnderBytes { limit }` ·
`AskEachTime` · `Always`. The transfer logic never learns which is in force — it
asks `decide(path, bytes, policy)` and does what it is told, which is what lets
the rule change without touching the code that moves bytes.

**What direct-only costs, plainly:** roughly a third of remote pairs will get no
direct path, and for them the transfer simply does not happen. The honest answer
for those pairs is §9a's substitute source — play the same recording from
somewhere each side already has — not a quiet fallback onto the operator's
bandwidth. A refusal says which of the three reasons it was, so the UI can be
specific rather than saying "failed".

### 9c. Flow control, and not degrading a call

**`bufferedAmount` is not optional.** A data channel accepts writes long after it
has stopped sending them; the bytes queue in the SCTP buffer. A naive
`for (chunk of file) send(chunk)` therefore queues a 2 GB film in memory in
milliseconds and either stalls the connection or gets the process killed — and it
*looks fine* on a 50 MB test file, which is how that bug reaches production. So
the pump fills to a 1 MiB high-water mark, stops, and waits for
`bufferedamountlow`. The threshold is set at 256 KiB rather than just under the
ceiling, because waking on every few drained bytes is an event per chunk, which
is the busy loop the threshold exists to prevent. The window is re-checked
*inside* each batch too, since `bufferedAmount` moves as we write and a batch
sized against a stale reading is how the ceiling gets overshot on a slow link.

Chunks are **16 KiB**, not 64: 64 KiB sits at the practical ceiling for a
reliable data channel and is refused outright by some implementations, and the
throughput difference is noise next to the window above.

**A transfer gets its own `RTCPeerConnection`.** Sharing the call's would put
bulk and live media under one congestion controller and one SCTP association,
where a 2 GB push and a voice stream compete and the voice loses. Separate
connections cost one extra ICE negotiation and buy complete isolation — a call
cannot be degraded by a transfer it knows nothing about. It is also what makes
the relay rule enforceable, since the transfer connection has its own ICE server
list: the *call* keeps its TURN fallback, because a relayed call is a few tens of
kilobits and entirely reasonable, while a relayed film is not.

### 9d. How the handover is negotiated

The pump and the policy are attached to a real `RTCPeerConnection` on both
frontends. What connects them is four signals, carried **inside the session
envelope** rather than under a marker of their own:

| Signal | Direction | Means |
| --- | --- | --- |
| `ask` | receiver → sender | "My copy of this is missing." |
| `offer` | sender → receiver | Size, hash and duration — before a single byte. |
| `accept` | receiver → sender | "Go ahead." Negotiation starts here. |
| `transport` | either | One step of the WebRTC negotiation. |
| `refuse` | either | Not happening, and why. |

Riding inside `TogetherSignal::Share` is the whole safety argument. Every guard
the session already has applies unchanged: the acceptance gate, the
sixty-second age gate, the session-id scoping, and the fact that sessions do
not survive a restart. A separate envelope would have needed its own copy of
all four, and **a stranger able to open a peer-to-peer connection to you by
sending one DM is a much worse bug than a stranger able to move your playhead.**
`a_transfer_cannot_be_negotiated_without_a_session_to_negotiate_it_in`
(`crates/comrade_ui/src/runtime.rs`) is that claim as a test.

A share signal is deliberately **not** a command
(`TogetherSignal::is_command`). A transfer trickles ICE candidates at its own
pace; if each counted as a command, a burst of them would outrank the pause
button and the person pressing it would watch it do nothing.

**Four steps rather than two,** because the two obvious shortcuts are both
wrong. Skipping `ask` means the side that *has* the file must guess whether the
other needs it — guess wrong and it is either an unwanted upload prompt or a
session that silently never starts. Skipping `offer` means the receiver learns
the size after the transfer rather than before it, which is exactly backwards
for the one decision they might want to make.

**`refuse` carries a reason and `end` does not**, and that asymmetry is
deliberate. The argument that keeps a reason off `end` is that why someone left
is nobody's business. The reason a transfer did not happen is a fact about the
network, not about the person, and it is the only thing that tells them whether
trying again could work.

**The runtime keeps no transfer state.** It relays signals and answers the
policy question; the peer connection, the data channel and the bytes live in
the frontend, because that is where WebRTC lives. Mirroring the negotiation in
the runtime as well would create two state machines that have to agree about a
connection only one of them can see — the shape of both call bugs this repo has
already fixed.

**Chunks carry their own index** (a four-byte big-endian header). A data
channel is ordered but a *transfer* is not: a receiver that seeks re-asks from
a new anchor while chunks from the old one are still in flight, so "the next
message is the next chunk I asked for" is false exactly when it matters. The
index and the payload length are both checked against the offer on arrival —
the whole-file hash would catch the same corruption, but only after the whole
file.

**The relay question, and who is allowed to answer it.** Under
`AskEachTime` the transfer stops and asks, naming the size; nothing moves and no
refusal is sent until someone answers, because neither has been decided. The
answer goes back to core as an argument rather than being acted on locally, and
core can only ever use it to turn `needs_consent` into `allow`. That asymmetry
is the whole design: `consent_granted` arrives from the least trustworthy caller
the policy has, and a frontend that passed `true` unconditionally — a bug, or a
dismiss wired to the wrong branch — must not be able to defeat `DirectOnly`. The
worst it can do is skip a question.

Consent is per-transfer and is never remembered. A yes that outlived its session
would be a yes to a file nobody was asked about.

**The policy is a stored preference**, inside the vault, seeded into the
in-memory cell on unlock — the cell exists because the WebRTC callbacks read it
and must not touch storage. A stored value this build does not recognise reads
as `DirectOnly`: the only safe reading of "I do not know what this device agreed
to" is the one that carries nobody's bytes.

**Verified, and not.** The framing, the tracker, the policy and the pump are
tested on all three sides — `comrade_core::share` (Rust), `share_transfer.mjs`
(desktop) and `ShareDecisions.kt` (JVM), with the Rust vectors ported verbatim
into both ports so a divergence is a red test rather than a corrupted file.
The consent path adds four Rust cases, including the one that matters — a
frontend claiming consent cannot move a refusal — and the round trip that proves
a chosen policy survives a restart. What has **not** happened is a run between
two real devices: no transfer has
crossed a live `RTCPeerConnection`, and the Kotlin path additionally cannot be
compiled in the development sandbox at all. Treat the connection handling as
reviewed rather than exercised.

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
