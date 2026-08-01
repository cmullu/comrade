# Comrade presence — "is my person online right now?"

_Added 2026-07-29._

You can mark a contact as your **comrade**: someone whose company you want to
know about. Your comrades — and nobody else, not even a relay — are told when
you are online, and you are told when they are.

This document is the design record: what the wire protocol is, what the model
guarantees, and (just as important) what it deliberately cannot do.

---

## 1. Why not a public status event

Nostr already has a user-status convention: **NIP-38**, a public,
*replaceable* Kind-30315 event. Publishing one would have been half a day's
work and it is what most clients do.

It is also, for this app, the wrong answer. A replaceable public status is a
minute-by-minute log of when a person is holding their phone, published to
every relay and readable by anyone who cares to watch — including people the
user has never met, forever, with no way to retract what was already
collected. Comrade's entire posture is that metadata is the thing worth
protecting (gift-wrapped DMs exist precisely so a relay can't see who talks to
whom); broadcasting an activity timeline would give away more than the message
contents we go to such lengths to hide.

So presence rides the channel we already trust: a small JSON envelope inside a
**NIP-44 / NIP-17 gift-wrapped DM**, addressed to one chosen peer. What a
relay sees is an event signed by a one-time key, with a timestamp randomized
by up to two days, whose payload it cannot read — indistinguishable from any
other DM. What a non-comrade sees is nothing at all.

## 2. The wire protocol

`comrade_core::presence` — pure, framework-free, unit-tested:

```json
{ "comrade_presence": 1, "state": "online", "ttl_secs": 480, "reply": false }
```

The `comrade_presence` marker is what makes it a presence beacon rather than
chat text, exactly like `comrade_receipt` / `comrade_profile` /
`comrade_call` / `comrade_media` do for the other control envelopes riding
the same channel. Each parser accepts only its own shape, so the inbox
dispatcher can try them in turn and fall through to a plain DM — a beacon
never renders as a chat bubble, and chat text is never mistaken for a beacon.

| Field | Meaning |
|---|---|
| `state` | `online` or `offline`. That is the whole semantic payload — no activity, no location, no "last app used". |
| `ttl_secs` | How long to believe an `online` claim. Clamped by the **receiver** to 30 minutes. |
| `reply` | Set when the beacon exists only to answer someone else's (see §4). A reply is never itself answered, which is what stops two online devices ping-ponging forever. |

## 3. The model, stated honestly

**Presence is mutual by construction.** You announce to the people you
marked; you see the people who marked you. Marking someone does *not*
subscribe you to their presence — and no design change could make it, because
there is no server: nothing can compel someone else's device to report to
you. If you choose Ana and Ana hasn't chosen you, you will never see Ana
online, no matter how long you wait.

That could easily read as a bug, or worse, as "Ana is ignoring me", so the
model is surfaced rather than hidden. Any beacon we receive proves the sender
chose us (`peer_marked_us`), so the UI can say **"waiting for them to choose
you back"** instead of showing an unexplained grey dot. And the moment you
choose someone who had already chosen you, the beacon already on file surfaces
immediately — the reveal happens when the information becomes yours to see,
not on some later transition.

**Presence is soft state with a deadline.** A beacon claims "online, for
`ttl_secs`". Expiry is measured from the beacon's **send** time, not its
arrival, which is what makes the whole thing robust against the parts of Nostr
we don't control:

- a phone that runs out of battery, loses signal, or is force-killed sends no
  goodbye — so the claim ages out on its own rather than leaving a permanent
  green dot;
- relays deliver at-least-once and the vault inbox backfills up to two days on
  every launch — a replayed beacon is already spent and cannot resurrect a
  stale dot;
- a peer-supplied TTL is clamped, so a buggy or hostile peer cannot claim to
  be online for a year.

Every read recomputes "online" against the current clock, so a stored `online`
row can never outlive its own deadline just because nothing swept it yet.

**Presence is gated like every other control envelope.** Only accepted
conversations are processed. A stranger can neither push presence state at you
nor provoke the reply that would disclose yours.

**Only transitions are news.** Heartbeats update state silently; the
`online` edge is the only thing that raises a notification, and going offline
never does (nobody wants to be told their friend closed an app).

## 4. Timing

| Constant | Value | Why |
|---|---|---|
| `PRESENCE_HEARTBEAT_SECS` | 180 s | One gift-wrapped DM per comrade per tick — a battery/traffic knob as much as a freshness one. |
| `PRESENCE_TTL_SECS` | 480 s | More than twice the heartbeat, so one dropped beacon can't flap a comrade offline. (The ratio is asserted at compile time.) |
| `PRESENCE_MAX_TTL_SECS` | 1800 s | The clamp on anything a peer claims. |

Beacons are sent when:

1. the vault unlocks and the event loops start (the first heartbeat tick fires
   immediately);
2. every heartbeat interval thereafter — a tick with no comrades does nothing
   at all, so the feature is invisible and free until someone opts in;
3. a comrade is chosen or un-chosen (`online` / `offline` respectively, so
   neither side waits on a heartbeat to learn about the change);
4. **the app comes to the foreground** (Android) — and an `offline` beacon
   goes out when it leaves it. This is the definition, not a nicety:
   *online means the app is open.* A phone in a pocket with the connection
   service running is still receiving messages, but nobody is at it, and a
   green dot for that is exactly the small lie this feature exists to avoid;
5. **a comrade we thought was offline arrives** — we answer with
   `reply: true`, so they learn we are already here instead of waiting up to a
   heartbeat. Only an *arrival* is answered: a heartbeat from a peer we already
   have online says nothing new, and answering it would double this feature's
   relay traffic for two idle comrades. A reply is never itself answered.

And an `offline` beacon is sent when the vault locks. Process death sends
nothing, by definition — that is what the TTL is for.

The heartbeat refreshes the claim **only while the app is open**
(`ComradeRuntime::presence_active`, set by the frontend through
`announce_presence`). Without that gate the loop would undo every goodbye: a
backgrounded phone would announce itself offline and then, a heartbeat later,
cheerfully claim to be online again. It also makes the feature free while
backgrounded — no beacons, no relay traffic — and it is why choosing a comrade
from a backgrounded app sends `offline` rather than `online`: they still learn
we chose them (that is what any beacon proves), without a claim we are at the
phone.

A video call in picture-in-picture keeps the Activity started, so a call still
reads as online — which is true.

Delivery is a separate question from presence, and stays separate: messages
and calls keep arriving while backgrounded (that is the connection service's
job). What stops is the claim that anyone is there to read them.

## 5. How it reads on screen

Presence is only useful if the words are ones people already know, so the
vocabulary is Telegram's — relative while a sighting is fresh, a wall clock
once it isn't, a date beyond that:

| State | What the UI says |
|---|---|
| Online right now | `online` |
| Seen < 1 min ago | `last seen just now` |
| Seen < 1 hour ago | `last seen 5 minutes ago` |
| Seen earlier today | `last seen at 5:30 PM` (`17:30` if the device is on a 24-hour clock) |
| Seen yesterday | `last seen yesterday at 5:30 PM` |
| Seen earlier this week | `last seen Monday at 5:30 PM` |
| Seen longer ago | `last seen 12 Jul` — with the year once it isn't this one |
| Chosen, reciprocated, never yet caught online | `last seen recently` |
| Chosen, **not** reciprocated | `waiting for them to choose you back` |

Two rules keep this honest. The last row is the one a status UI usually gets
wrong: a grey dot with no explanation reads as "they are ignoring me", when
the truth is that their device has never told us anything and will not until
they choose us back. And the *timestamp* is only ever advanced by a sighting —
never by a claim lapsing, which happens up to a TTL after the peer was
actually there — so "last seen" never overstates.

In the conversation header that line sits directly under the name, with
nothing else on it: the peer's key moved to the ⋮ menu (*Copy key*,
*Encryption info*), so presence never has to compete with it for room.

The rules live in `lastSeenOf`/`presenceLabelOf`
(`android/.../ui/DisplayName.kt`, pure and unit-tested); the wording lives in
`strings.xml` (with a plural for the minutes case), and the clock follows the
device's own 12/24-hour setting. The desktop SPA mirrors the same ladder in
`presenceLabel()`.

A comrade coming online also raises a notification — **"Ana is online" / "Your
comrade is around"** — on its own channel, so it can be silenced without
touching messages or calls. It is dropped when they go offline again, when
their chat is opened, and on its own after the presence TTL, so the shade can
never keep claiming someone is around after everything else in the app has
stopped believing it.

## 6. Where the code lives

| Layer | What it owns |
|---|---|
| `comrade_core::presence` | Wire protocol + freshness arithmetic. Pure; 10 unit tests. |
| `comrade_core::nudge` | The nudge envelope, its freshness arithmetic, every timing rule (`draft_verdict`) and the per-session composer watch (`DraftWatch`). Pure; 23 unit tests. See §6a. |
| `comrade_storage` | Opt-in `Contact.comrade` flag (defaulted for rows written before the feature; preserved across alias edits) and a `peer_presence` tree per peer. |
| `comrade_ui::runtime` | `set_comrade` / `comrades` / `peer_presence` / `announce_presence`, the heartbeat + expiry loop, the farewell beacon on lock, the receive path in `dispatch_incoming_dm`, and the `ComradePresence` bridge event. Plus the nudge's half: `note_draft` / `abandon_draft`, the send sweep (`nudge_abandoned_drafts`, on the same tick), `handle_nudge`, and the `ComradeNudge` event. |
| `comrade_jni`, `desktop/src-tauri` | The same calls over uniffi / Tauri commands — `note_draft` / `abandon_draft` are the only two that are *synchronous*, because a composer calls them on a keystroke. |
| Android | `PresenceMonitor` (live dots + last-seen), `ComradesScreen` (choose + see), dots on chat-list rows, a presence line in the conversation header, the ⋮-menu toggle, and a `comrade_presence` notification channel. The composer reports drafts from `ConversationScreen`'s `editDraft` and its `DisposableEffect(peer)`. |
| Desktop SPA | ★ toggle + presence line in the conversation header, dots in the conversation list, a toast on the online edge (and one for a nudge). Drafts are reported from the `#dm-input` listener and on switching conversations, with the decision itself in the tested `draft_reports.mjs`. |
| Flutter `app/` | Reports drafts from `ConversationScreen`'s controller listener and `dispose`; maps the event to `IncomingComradeNudge`. The notification itself comes from the preserved native `ChatEventRouter`, the same path presence uses. |

Tests worth knowing about: `crates/comrade_ui/tests/two_peer_integration.rs`
drives two real runtimes over one in-process relay and proves both halves of
the claim — comrades seeing each other come and go (including the
answer-on-the-spot path), and a beacon reaching *only* the chosen peer while
another accepted contact learns nothing. Two more do the same for the nudge: a
message Bob writes and never sends reaches Alice, and one he *does* send never
also arrives as a nudge.

## 6a. The nudge — "they nearly wrote to you"

_Added 2026-07-31, on an owner request: tell Alice when Bob opens her chat,
types something, and leaves it unsent._

Someone opens your chat, types a few words, then clears the box or walks away
without sending. Nothing about that reached you before: the message was never
sent, so there was nothing to deliver, and the moment passed silently. It is
often the moment that mattered most.

A **nudge** is the smallest honest signal for it — `comrade_core::nudge`, a
sibling of `presence` riding the same gift-wrapped DM channel:

```json
{ "comrade_nudge": 1, "ttl_secs": 480 }
```

A marker and a deadline. That is the entire payload, and the wire shape is
[asserted by a test](../crates/comrade_core/src/nudge.rs) rather than promised
in a comment: there is no field for the draft, its length, how long it was
typed for, how many times it was rewritten, or which of "cleared it" and
"walked away" happened. None of that is ours to send.

**Why this is not the typing indicator §7 rules out.** The objection to a
typing indicator is that it is a keystroke-resolution feed of a person's
hesitation, on for every chat, disclosed continuously. A nudge inverts every
axis of that:

| | typing indicator | nudge |
|---|---|---|
| when | continuously, while typing | once, after the draft is gone |
| how often | every keystroke burst | at most once per 30 min per comrade |
| to whom | whoever you are chatting with | a comrade you chose, one at a time |
| payload | that you are typing, live | one bit: something was written, and not sent |

**The timing rules, and the case each one exists for.** All five live in
`draft_verdict`, pure and unit-tested, so every frontend inherits one answer:

| Constant | Value | The case it exists for |
|---|---|---|
| `NUDGE_MIN_DWELL_SECS` | 3 s | A chat opened by accident and a thumb on the screen. Not a hesitation; never worth a notification on someone else's phone. |
| `NUDGE_SETTLE_SECS` | 10 s | Clearing the box to rewrite the same sentence — the most common way to abandon a draft, and not what this is about. Nothing is sent until the draft has stayed gone, so the ordinary clear-retype-send discloses nothing at all. |
| `NUDGE_COOLDOWN_SECS` | 1800 s | Someone writing and deleting six times in ten minutes is having a hard time, not sending six signals. A channel that fires on all of them stops being read. |
| `NUDGE_TTL_SECS` | 480 s | Equal to `PRESENCE_TTL_SECS`, deliberately: the notification says they are *around* as well as that they nearly wrote, so it must not outlive the window in which an "online" claim would still be believed. |
| `NUDGE_MAX_TTL_SECS` | 1800 s | The clamp on anything a peer claims, exactly as for a beacon. |

Two more rules that are easy to miss and both load-bearing:

- **Sending anything cancels it.** A delivered message — or an attachment, or
  one queued in the outbox for retry — says everything the nudge would have.
  This is enforced in `RuntimeHandles::send_dm_reply`, not in three frontends,
  so no UI can forget it.
- **A nudge is never sent about something old.** A phone that slept for an hour
  with a pending nudge drops it instead of sending it late. Sending stamps the
  envelope with *now*, so a late nudge would present an hour-old moment as this
  one — and the receiver has no way to catch that.

**What the receiver does.** Gated exactly like a beacon: accepted
conversations only, so a stranger cannot page you before you accept them. Then
three more rules, all in `handle_nudge`: an expired nudge raises nothing
(measured from send time, so the two-day inbox backfill cannot re-announce
Tuesday's hesitation); a redelivered wrapper raises nothing (the same dedup set
the call-signal path uses); and only a comrade *we* chose is announced.

Unlike a beacon, a nudge **writes no presence state** — no dot, no "last seen",
no `peer_marked_us`. A beacon's arrival is *how* the mutual model becomes
discoverable, and that job is already done; letting a second envelope advance a
"last seen" would give those fields two sources of truth for no gain.

The wrinkle that buys, stated rather than hidden: the notification's title
claims they are online, and nothing updates the dot to agree. A fresh nudge is
the same class of evidence as a beacon — it proves the sender was at their device
inside the same window an `online` claim would be believed for, which is why the
TTLs are pinned equal — so the title is honest on its own. But a device that has
somehow received a nudge and no recent beacon will show "Bhaskar is online" in
the shade next to a grey dot. In practice that needs a nudge to overtake a
3-minute heartbeat and the two-day backfill behind it. If it ever shows up in the
field, the fix is to give the dot a second source, not to soften the title into
something less useful.

**How it reads on screen.** On Android, **"Bhaskar is online" / "Your comrade
might need you"**, on the same `comrade_presence` channel (someone who silenced
that did not mean "except when it's urgent") and under the *same notification
id* as the online notice — so it replaces that line rather than stacking a
second one about one person. It cannot silently downgrade back to "Your comrade
is around" either: the online notice only fires on a transition *into* online,
and the only way a peer stops being online routes through `clearComradeOnline`
first. Tapping opens their conversation; mute silences it; it self-expires after
the TTL like everything else here. The desktop SPA shows one toast.

**In memory only, and cleared on lock.** The watch of which composers hold
unsent text is per-session (`DraftWatch`): a draft abandoned before the app was
last killed is a question for whoever opens the app next, not a promise still
owed. Locking the vault clears it, because the goodbye beacon has just said we
are gone and a nudge after it would claim the opposite. The honest consequence:
**typing, clearing, and immediately force-killing the app sends nothing.**
Backgrounding is fine — the connection service keeps the process alive, which is
the common "put the phone down and walk off" case.

## 7. Deliberately out of scope

- **Typing indicators and "last active" timelines.** A live "typing…" is a
  keystroke-resolution feed of a person's hesitation, on for every chat; a
  last-active timeline is a log of their day. Both leak considerably more than
  "around / not around", and neither was asked for. What *was* asked for, and
  shipped instead, is §6a's nudge — one signal after the fact, to one chosen
  comrade, carrying no more than a beacon does. If that line ever needs
  redrawing again, redraw it here rather than quietly widening the envelope.
- **Presence for anyone but a chosen comrade.** No "everyone in your chat
  list" mode. The disclosure has to stay something a user picked, one person
  at a time.
- **Push wakeup.** Presence needs the app process alive, exactly like calls
  and message delivery (see the `RelayConnectionService` security-boundary
  note). A killed process is honestly offline, and its comrades' dots go grey
  when the TTL lapses.
- **Cross-device presence.** One vault per device today, so "online" means
  "this device". A multi-device account model would have to define what
  online means before presence could follow it.
