# Threads and topics — keeping a subject together inside one conversation

_Owner request, 2026-08-14: "implement msg threads in user chat screen similar to
telegram and slack it's a separate sheet with ability to scroll and view the
messages and reply in a specific thread. which can be formed as a topics. A topic
can be created and a thread can be assigned in that. the topics can be marked
referenced or assigned with /assign #topic_name". Status: core, storage, runtime,
FFI and all three frontends landed. The gaps are §8, not left to be discovered._

A conversation is one flat river. "The flat deposit" ends up as forty messages
spread across six months, interleaved with everything else, and the only way back
to it is scrolling. Telegram answered this with forum topics, Slack with threads.
This brings both to a two-person chat.

## 1. Two words, and they mean different things

**A thread** is a message and everything that replied into it. It is derived, not
declared — you make one by replying, and it exists the moment somebody does.

**A topic** is a name you file threads under. It is declared: somebody types
`#deposit` and it exists. Topics are per-conversation, so `#deposit` with your
landlord and `#deposit` with your flatmate are different subjects. Merging them
would leak one conversation's structure into another.

A thread is in **at most one** topic, or none. A topic holds any number of
threads.

## 2. The slug is the id, and that is the whole design

`comrade_core::topic`. Pure, no I/O, no clock, 18 unit tests.

The first design gave each topic a random 128-bit id, the way
`comrade_core::karya` mints a task id. That is wrong here, and the reason only
appears in the field: **both people can type `/assign #deposit` before either
envelope lands.** Two ids for one word means two topics with the same name,
filing into different rows, and *neither side can see the split* — their sheets
both look right and disagree.

So a topic is keyed by its slugified name within the conversation. Creation is
idempotent by construction: the same word reaches the same row on both phones
with no merge, no tie-break, and no Lamport clock. `EncryptedStore::save_topic`
deliberately has **no** timestamp guard, unlike `set_reaction` and
`set_thread_topic`, because there is nothing to arbitrate.

What it costs, stated here rather than left to be discovered:

- **A topic cannot be renamed into a different word.** `#Deposit` and `#deposit`
  are one topic that remembers the first spelling (`Topic::merge_name` — first
  wins, so it does not flicker as the two devices re-announce). `#deposit` →
  `#flat` is a *new* topic.
- **Nothing deletes a topic.** `Topic::set_closed` archives it: still readable,
  out of the picker. Two devices deleting and recreating one name would have an
  argument neither can win.

Same trade `karya` makes by refusing to reopen a task. A terminal rule is what
lets two devices not need a clock.

### The slug rules

Lowercase ASCII letters, digits, `-` and `_`; 2–32 bytes; must start with a
letter or digit. Spaces and punctuation collapse to one `-`. `-deposit` is
trimmed to `deposit` rather than refused, because that is a typo with an obvious
intent. `flat_deposit` and `flat-deposit` stay **different** — they are different
words to whoever typed them, and folding them together makes one unreachable.

**Non-ASCII is refused, not mangled.** That is `AUDIT.md` TOPIC-1 and it is a
real limitation in an app whose own strings ship in Hindi — see §8.

## 3. Which thread a message is in

The reply graph, walked in core so no frontend re-walks it:

```
root_of(id, parents) → the oldest ancestor reachable by following `reply_to`
```

Three properties earn their place:

- **Flat, Slack-style.** A reply to a reply is in the *same* thread. That is what
  makes the sheet's composer able to always address the root.
- **A message whose parent we do not hold is its own root.** Not an error and not
  rare: a reply can arrive before its parent, and a quote can point at something
  older than the loaded window. Filing it under itself keeps it visible.
- **Bounded.** `MAX_THREAD_DEPTH` is 64 and there is a visited set, because
  `reply_to` arrives over the network and a cycle is something a peer can hand
  us. The walk stops at the last id it reached, so a malicious chain costs a
  wrong-looking thread and never a hang.

**Every command that takes a "thread" takes any message in it**, not a root:
`thread`, `assign_thread`, `send_thread_reply`. Core resolves upwards. A frontend
that had to find the root itself could file the wrong thread from a reply, and
that is a destructive-looking action taken on the user's behalf.

## 4. Filing emits no chat bubble

The one place this module departs from `karya`, which renders a line for every
signal. The reason is a product one: a task is something a person is being
*asked*; a topic is where a message already in front of them is *filed*. A
conversation that grew a "moved to #deposit" line every time somebody tidied
would punish tidying.

The consequence is that **the frontend's own confirmation is the only trace the
action leaves**, which is why all three say so out loud — a snackbar on Flutter,
a composer note on Android, a toast on desktop — and why the Flutter widget test
asserts the sentence rather than treating it as decoration.

## 5. The wire

The same NIP-44/NIP-17 gift-wrapped DM channel as receipts, tasks, call signals,
presence beacons and together envelopes, with its own marker key
(`comrade_topic`) so the inbox dispatcher falls through to its other handlers.

| Signal | Says |
|---|---|
| `Create { slug, name }` | this conversation has a topic called this |
| `Assign { root_id, slug }` | that thread belongs here; `slug: None` unfiles it |
| `Close { slug, closed }` | archive it, or bring it back |

Three things the dispatcher does, each for a reason:

- **Gated to accepted conversations**, exactly like a task signal. A stranger who
  can reorganise your conversation has a way to hide messages from you.
- **The slug is re-derived from the name and must match** what the envelope
  carries. A mismatch means the sender's rules are not ours — a newer build, or a
  forged envelope — and taking their slug would file threads under a key this
  device can never produce from any name a user types.
- **No standing check on `Assign`**, unlike `karya`'s state changes. A topic is
  shared filing, not a request made of one person: both people may name topics
  and file threads. The only authorisation that matters is the gate, and a peer
  cannot reach another conversation because every row is keyed by the
  authenticated sender.

A filing whose topic has not arrived yet is **stored anyway** — the relay can
reorder the two envelopes, and refusing would drop the filing that the `Create`
behind it was for. `ThreadIndex` reads an unknown slug as unfiled until the name
lands, which is the recoverable version of the same state.

`BridgeEvent::TopicsChanged` carries the conversation and nothing else; the
sheets re-read. One coarse event rather than a payload per signal kind, because
the counts are derived on read anyway (§6) so a fine payload would be read and
thrown away — and because each variant costs a Kotlin `when` arm and a Dart
`switch` arm in files nobody edits.

## 6. The counts are computed, never stored

`comrade_ui`'s `ThreadIndex` reads the conversation and groups it per call. A
stored count is a second source of truth that drifts the first time a backfill
inserts an old message into the middle of a thread, and this history is bounded
by one conversation, so there is nothing here that scales badly enough to justify
the drift.

`ThreadSummaryDto` hands up `root_missing` and `root_is_media` as booleans beside
an empty `preview`, **and no sentence**. The word for "attachment" and for "the
first message isn't on this device" belongs where a translator can reach it, so
each frontend picks it from a five-case enum (`PreviewKind`). The branch is what
the tests pin; the wording is not. Same split the offer envelopes in
`docs/CHAT_ACTIONS.md` make between the wire and the sentence.

## 7. The surfaces

| Type this / do this | It does |
|---|---|
| `/assign #deposit` while replying | files that thread under `#deposit`, creating it if new |
| `/assign` alone | opens the topic picker — the discoverable way in, not a refusal |
| `/assign #deposit` with nothing selected | says what is missing and keeps the text |
| `/topic`, `/file` | aliases for `/assign` |
| Long-press a message → **Open thread** | reads that thread in a sheet with its own composer |
| Long-press a message → **File under a topic…** | opens the picker as a destination |
| The **Threads and topics** strip | appears once the conversation has a thread or a topic |

The strip exists because the app bar belongs to the shell on both Android and
Flutter, so a per-conversation action would have to be threaded up through it and
back down. A strip that is *absent* until there is something to reach costs a
chat with neither exactly nothing, and is more discoverable than a menu item —
which is what a feature nobody is yet looking for most needs. Desktop has the
room for a `#` button in the conversation header instead, and a drawer beside the
log rather than a sheet over it.

**The shared decisions are in three mirrored copies** —
`android/…/topic/TopicDecisions.kt`, `desktop/ui/topics.mjs`,
`app/lib/src/util/topic_view.dart` — pinned by 11 identical vectors each, so the
three drifting apart is a red test rather than a field bug
(`docs/COMMS_ARCHITECTURE.md` ADR-2). What they decide: ordering (liveliest
first, archived last), which rows are hidden (a thread of one is not listed —
every message is technically a thread, and listing them makes the sheet a worse
copy of the chat), the preview branch, and `/assign`'s three-way answer. What
they do **not** decide: slugs and the reply graph. Those are core's.

## 8. Gaps

- **`AUDIT.md` TOPIC-1 — topic names are Latin ASCII.** `#जमा` is refused. The
  slug is the key, two devices must derive it byte for byte, and Unicode gives
  several encodings of one word that `to_ascii_lowercase` does not fold. Exit
  condition: NFKC plus a confusables check, at which point the length bounds
  become grapheme counts.
- **`AUDIT.md` TOPIC-2 — an attachment can start a thread but not join one.** A
  `MediaRef` carries no `reply_to`, so replying *to* a photo works and sending a
  photo *as* a thread reply does not. Exit condition: `reply_to` on `MediaRef`
  and on the NIP-94 envelope; `ThreadIndex` already includes attachments as items
  and needs no change.
- **No unread watermark per thread.** `ThreadSummaryDto::unread` reads the
  conversation's single `last_read_at`, deliberately — a sheet with its own idea
  of "unread" would disagree with the screen it opens from. The cost is that
  reading one thread marks the conversation read up to that point.
- **Desktop has no `#topic` chip in the composer.** `topic_refs` finds
  references with spans for exactly this, and Android does not draw them either.
  A bare `#deposit` in ordinary message text is currently just text on every
  frontend; only `/assign #deposit` acts.
- **Nothing verifies the Compose sheet.** `ThreadSheet.kt` was written and never
  type-checked locally: `.claude/scripts/android-typecheck.sh` needs androidx
  jars from `dl.google.com`, which the container's network policy blocks. CI is
  its first build.
