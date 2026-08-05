# In-chat actions — commands, mentions, and four things the composer can do

_Owner request, 2026-08-04: "in chat actions (play something together e.g
/spotify Kun Faya kun), invoke tara (@tara …), assign tasks (/task get some work
done @xyz) and assign in app actions (/assign @breath)". Status: core, view-model
and both shipping frontends landed; the gaps are listed in §7 rather than left to
be discovered._

The composer could only send text. Everything Comrade can *do* — open a
listening session, reach the reflective companion, name a piece of work, open the
breathing screen — sat behind a screen you had to leave the conversation to find.
This brings four of those into the composer.

## 1. The grammar

`comrade_core::command`. Pure, no I/O, no clock, 44 unit tests.

| Type this | It does |
|---|---|
| `/play`, `/listen`, `/watch`, `/spotify`, `/youtube`, `/apple` | Listen or watch together |
| `/tara …` | A private aside — only you see it |
| `@tara …` | Ask Tara in the chat — you both see her answer |
| `/task <what> [@who]` | Name a piece of work; no `@who` is a note to self |
| `/breathe`, `/focus`, `/journal`, `/read` | Open it here |
| `/comrade-breathe @who` (and `-focus`, `-journal`, `-read`, `-tara`) | Offer it to a comrade |
| `/pay <amount> to <vpa>` | Unchanged — recognised so it is never called a typo |
| `/help` | The list |

**`/assign` was relabelled.** You cannot assign somebody a breath. The breathing
screen's own copy says *"you don't have to feel better yet"* and its
`strings.xml` carries a comment forbidding claims the app cannot support, so an
imperative verb was the wrong register. The verb was dropped entirely instead:
the action **is** the command, and `comrade-` in front of it means "for my
comrade". One fewer thing to learn, and it matches `/task`, also a noun.

### What is *not* a command

Getting this wrong is worse than having no commands at all, because a message
swallowed as an unknown command is a message the sender believes they sent. Four
rules, each with its own test:

- No leading `/` → text. `20/80 split` is a message.
- A leading token containing a second `/` → text. `see /Users/me/notes` and a
  pasted `/usr/local/bin` are messages.
- A token that is not command-shaped (lowercase ASCII, digits, `-`) → text.
  `/Notes` is a message.
- Command-shaped but unknown → **reported, never sent**.

### Mentions

`@handle` resolves against the saved contacts only — alias first, then the peer's
published handle, following `ContactDto`'s documented precedence. Two rules:

- **A handle preceded by a word character is not a mention.** Otherwise the `@`
  in `friend@upi` makes `/pay 250 to friend@upi` try to resolve a contact called
  `upi`.
- **Two contacts answering to one handle is a question, not a coin flip.**
  Handles are self-declared and non-unique; picking the first is how a private
  message reaches the wrong person. `MentionMatchDto::candidates` carries the
  ambiguity up for the UI to ask about.

## 2. Tara in a chat — two audiences, one guard and one reframe

**The sigil is the audience.** Same companion, same engine, two different rooms:

| Typed | Who sees it | Where it goes |
|---|---|---|
| `/tara <text>` | only you | the private `tara_companion` thread; no relay is touched |
| `@tara <text>` | both of you | two ordinary DMs into the conversation, `@Meta AI`-style |

`/tara` is the **private aside** and always was: it never sends, the peer never
sees it. `@tara` is Tara **in the room** — the question goes to the peer as your
own message and her answer follows it, so the other person can read both.

The composer says which one is in the box from the moment the sigil is typed,
before there is anything to parse (`ChatCommands.taraDraft` /
`chat_commands.taraDraft`, mirrored and tested on both frontends). That label is
load-bearing in *both* directions: one character decides whether a private
thought gets published, or whether a question the other person was waiting for
was ever sent at all.

**One exception, and it is not the frontend's.** A question that trips
`detect_distress` is answered and **nothing is sent** — `TaraChatDto.kept_private`
says so, and the composer has to repeat it, because someone who asked in the open
would otherwise assume the other person had read it. A crisis hand-off is not
something to publish into somebody else's chat on the asker's behalf, and the
grammar cannot tell that case from any other until the reply exists — so the
check lives in `RuntimeHandles::tara_in_chat`, after the reply and before the
send. The helplines go with the reply in either room (`AUDIT.md` §8's gate).

**Her answer is a third participant on screen, and a marked ordinary message on
the wire.** It carries `tara::TARA_CHAT_PREFIX` (`"Tara: "`) between devices, and
`comrade_ui::split_author` turns that into `MessageDto.author = MessageAuthor::Tara`
with the marker off the text — one function, called from both `MessageDto`
construction sites, so a line cannot read one way as it is sent and another way
after a reload. All three frontends draw her bubble from the field: left-aligned
on *both* devices (her answer is carried by whichever phone asked, so aligning by
`outgoing` would put one line on opposite sides of the two screens), named, in
its own colour, and without delivery ticks — the question directly above carries
the same receipt, and a tick on a third party's line reads as a claim about her.

The prefix deliberately **stays** on the wire rather than being dropped now that
the field exists. A NIP-17 DM opened in some other Nostr client has no author
field to read, and "Tara: …" is a truer fallback there than her words in the
sender's mouth.

What the field does not buy is authentication. Nothing signs the marker, so a
Tara bubble means *the sending Comrade says this came from her* — the standing a
quoted reply has, not proof. `AUDIT.md` Q17 accepts that explicitly: nothing may
gate on the field, and the tests on both sides assert that a hand-typed line
parses rather than hiding it.

The answer also replies to the question by event id — two messages sent in the
same second share a `created_at`, and the receiver's thread sorts on that, so the
`e` tag is what keeps her answer under what it answered.

**She is seeded only with what the user typed**, in both rooms. Not the chat
history, not the peer's messages. A "reflect on this conversation" feature would
quietly turn the other person's words into input to a companion they never opted
into — and in the shared room the peer is a *reader*, never material.

**A shared ask does not touch the private thread.** It is not a turn in the
private session, and merging them would let a shared chat reshape a
journal-adjacent space the peer has no part in. The prompt-rotation seed
therefore counts the Tara lines in *this* thread.

**`tara::mentions_third_party` sits in front of the engine**, where
`detect_distress` already sits, and must stay in front of any future model
(`docs/TARA.md`, OQ9). An aside naming another person does not get a
characterisation of them; it gets the askable question back:

> I can't tell you what someone else thinks or feels — I'd only be making it up,
> and it wouldn't be fair to them. What I can stay with is your side of it:
> what's coming up for you about @xyz?

This matters *more* in the shared room, not less: a characterisation of @xyz would
now be delivered to the person you are talking to. The gate is the same one, and
`naming_a_third_party_still_reframes_when_the_room_can_read_it` pins it there.

The request's own example — *"@tara what does she @xyz thinking of herself"* — is
a literal test vector. Two reasons, both from `docs/TARA.md`'s non-negotiable
gates. Inferring what a real person thinks of themselves is a psychological
assessment of somebody who has not consented, is not in the room, and cannot
correct it (gate 1, "never diagnoses"). And `ReflectiveCompanion` is a cue-word
template matcher — asked about a third party it would emit a fluent, confident
sentence with no information in it, about a human being the reader knows.

**The rule is blunt on purpose.** Any question naming a person turns around; there
is no cue list deciding which questions about somebody count as assessments,
because a fuzzy matcher guarding a hard boundary fails on exactly the cases that
matter. Bluntness costs almost nothing, because the redirect is *also* the right
reflective move: "I'm worried about @ana" is legitimate material here, and
"what's coming up for you about ana?" is what a good listener says to it. The
safety property and the product behaviour are the same sentence.

`detect_distress` still runs **first**. Somebody in crisis who names a friend
needs the helplines, not a reflective question — and in the shared room, needs
them without the exchange being sent anywhere.

## 3. Tasks

`comrade_core::karya` (कार्य, work) + a `karya` tree in the encrypted store.

**A task is a request, not a write to somebody else's list.** That is the
load-bearing decision and it is a product one. An incoming assignment arrives
`Open` and can be **declined** — a first-class outcome, not a failure — and
nothing lets an assigner mark someone else's task done.

| | assigner | assignee |
|---|---|---|
| withdraw | yes | no |
| mark done | no* | yes |
| decline | no | yes |
| reopen | no | no |

*A note to self is the exception to the whole table: one person holds both roles,
so they may do all three, **including withdraw** — deleting your own private note
is not somebody else's business, and refusing it with "that is not yours to
change" would be absurd. `Task::apply` special-cases it and three tests pin it.

**Nothing reopens.** A task that comes back is a new task: "done, no wait, not
done" is an argument two devices would have to arbitrate and neither has the
standing to win. `together` pays for a Lamport clock because playback needs one;
a task list does not, and a terminal state is what lets it not need one.

**Who may send one at all**: an assignment is accepted only from an
already-accepted conversation, exactly like a call signal. A stranger who can
push rows onto your list has been handed a harassment channel, and in an app
about wellbeing that is worse than the feature is good.
`a_stranger_cannot_write_to_someone_elses_task_list` pins it over a real relay.

Ids are 128 random bits minted by the assigner and carried on the wire. Not
derived from the DM's event id, though that was the first idea: the assigner does
not know that id until the relay has accepted the send, so there would be nothing
to put in the envelope — and the two sides see different ids anyway (the
wrapper's, not the rumor's).

## 4. Offers

`/comrade-breathe @ana` sends one action and nothing else. What Ana receives:

> Your comrade asked you to take a deep breath — they are here for you

Two rules hold every offer line, and a sixth action must keep them. **It says
what the sender did, never what the reader is** — the app has no idea whether
anybody is anxious, and the breathing screen's strings already forbid copy
claiming otherwise. And **the tail is the sender's own claim**: "they are here for
you" is a fact about the person who sent it, which is the one reassuring thing
this app is actually in a position to pass on.

Three gates, each a lesson already paid for:

1. **Comrades only** — marking somebody a comrade is the existing "may reach me"
   grant, and an offer is a notification, so it lives inside that grant.
2. **The shared nudge cooldown** (`nudge::nudged_recently`). A floor on
   *notifications*, not on any one reason for them — the reasoning `AUDIT.md`
   records for the breathing screen's own trigger. Being able to send this
   repeatedly would make it a way to needle somebody.
3. **The *reason* comes back.** A deliberate command that silently did nothing
   reads as a bug — but a bare count was worse than nothing, because
   `offer_action` reaches zero three ways (nobody named is a comrade, the
   cooldown is running, every send failed) and a frontend holding only `0` said
   *"they were told recently"* for all three. That named a cause that was not
   real and never suggested the fix. It returns `OfferOutcomeDto` now, and each
   frontend says which applied — *"mark them a comrade first"* when that is the
   truth.

It deliberately does **not** reuse the `comrade_nudge` envelope, whose guarantee
is that it carries *no reason at all* — a key-set test enforces the absence. An
offer names an action by construction, so it has to be its own shape rather than
quietly weakening that one.

## 5. `/play`, and where audio may come from

`comrade_core::catalogue`. Two questions, and conflating them is what makes this
area a minefield.

**Which recording is that?** `CatalogueResolver`, defaulting to **MusicBrainz** —
chosen for a deployment reason, not a technical one: no API key, so there is no
credential to ship in a binary, rotate, or have revoked out from under every
install. Its data is open and it carries ISRCs, the field `match_score` treats as
decisive.

**Where do the bytes come from?** `choose_audio_plan` picks the first of four
tiers that can supply it, cheapest to everybody involved first. There is
deliberately **no single `AudioSource` trait**: the four tiers genuinely live in
different layers, so core decides and each layer carries its own out.

| tier | where | who carries it out |
|---|---|---|
| `Library` | already on this device | the frontend's library resolver |
| `Peer` | the other person's device | `comrade_core::share` |
| `OpenLicence` | an archive whose licence permits it | an HTTPS fetch by the caller |
| `EmbedOnly` | nowhere — playable in place only | the embed player |

Licence is checked **before** the fetch, not after: a tier that downloaded first
and inspected afterwards would already have made the copy it was deciding about.
The filter lives in `choose_audio_plan`, not in the adapter, so a sloppy or
hostile resolver returning an all-rights-reserved URL changes nothing.

### What is deliberately not built

Antra's chain is `own mirrors → Tidal/Qobuz/Amazon/Deezer/Apple adapters →
Soulseek`. **The last two tiers are absent, and there is no seam shaped like
them.** Those services do not serve unencrypted audio to third-party clients, so
obtaining it means defeating a technological protection measure — a liability
*distinct from* infringement (DMCA §1201, EU InfoSoc Art. 6, India's Copyright
Act §65A). Mirror servers and Soulseek are unlicensed redistribution, and a
Comrade running a mirror would also be the backend `share`'s whole design exists
to avoid. `docs/TOGETHER.md` §9 already ruled both out; this records that the
code matches.

**Exit condition** for that decision: a licensing arrangement, or a source that
serves unencrypted audio under terms permitting redistribution. Not a better
downloader.

Worth being concrete about `/spotify Kun Faya Kun`, a commercial 2011 release:
the chain resolves the identity through the catalogue, then finds it in your
library, receives it from the peer, or plays the YouTube embed. The example works
end to end. The one path it does not take is the DRM one.

## 6. The wire, and why the bubble is separate

An assignment and an offer travel as **marker-keyed JSON envelopes**
(`comrade_karya`, `comrade_offer`) on the same gift-wrapped DM channel as
receipts, presence, call signals and together envelopes — one message each, not
two. The receiver **renders its own chat bubble** from the envelope
(`render_task_line`, `render_offer_line`).

That split exists for localisation. If the wire format were the human sentence,
translating it would stop two phones in different languages reading each other's
threads. So the envelope is the wire, the rendered line is stable English that
`parse_task_line` / `parse_offer_line` read back, and each frontend localises
from the parsed `AppAction` — never from the words.

Both envelopes go out through `send_control_envelope`, **not** `send_dm`. That
distinction is load-bearing: `send_dm` is the *chat* path, so it persists a
`StoredMessage`, drives the chat-list preview and queues in the outbox — putting
an envelope through it puts raw JSON in the **sender's own** thread and chat
list, which is exactly the defect `AUDIT.md`'s 2026-07-29 entry records for media
references. The cost is deliberate: no outbox retry, because a "would you do
this?" arriving an hour after the conversation moved on is worse than one the
sender was told to re-send.

**No new `BridgeEvent` variant.** A rendered line is surfaced through the
existing `IncomingDirectMessage`, reusing the incoming event's real id so a
same-transport redelivery is caught by the plain-chat dedup. That is not enough
on its own — the same envelope over the *other* transport carries a different
event id — so both dispatcher arms run `is_cross_transport_duplicate` on the
envelope bytes first. Without it every offer that took both routes raised two
bubbles. Adding a variant instead would have meant an exhaustive Kotlin `when`,
two exhaustive Dart switches, and regenerating `frb_generated.rs` — see
`CLAUDE.md`'s traps.

## 7. What is built, and what is not

Built and tested: the grammar, the task engine and its state machine, the Tara
guard, the storage tree, the view-model, the uniffi and Tauri surfaces, the
desktop decision module and composer, and the Android composer. Two-peer
integration tests drive a real assignment → same id on the other device → done
coming back, plus the stranger gate and an offer arriving as a readable line.

**Not built**, and stated here rather than discovered:

- ~~**A dedicated task list.**~~ **Built 2026-08-04.** Android reaches it from
  the drawer (`ChatNav.Tasks` → `TaskListScreen`), desktop has a fourth tab. The
  decisions are `android/…/ui/TaskList.kt` and `desktop/ui/task_list.mjs`, tested
  on both sides against the same vectors, and the rule they exist to hold is that
  **the buttons a row offers mirror `karya::may_transition`** — a control core
  would refuse with "that is not yours to change" is never drawn. A note to self
  now has a surface, so the composer's "Added to your list." is no longer a claim
  about a list nobody could open.
- ~~**`/play` does not open a session yet.**~~ **Joined 2026-08-04, and blocked
  one step further on.** The chain now runs end to end on Android: `play_query`
  says what the query names, `LibraryResolver` looks for a copy on the phone, and
  `comrade_ui::play_route` turns those two answers into one of five routes —
  only one of which starts a session. `/play kun faya kun` in a conversation
  calls `TogetherManager.start` on the matched file.

  **But the library lookup cannot currently succeed**, and the reason is not in
  this feature: the manifest declares neither `READ_MEDIA_AUDIO` nor
  `READ_EXTERNAL_STORAGE`, so every `MediaStore` audio query returns nothing.
  `LibraryResolver.mayRead` exists so the composer can say *"Comrade can't read
  your music library"* rather than the false *"no copy of that on this phone"*,
  and the file-picker path (which needs no permission — SAF grants per file)
  works either way. Whether Comrade should ask to read someone's music library
  is a decision for the owner, so it is recorded in `AUDIT.md` rather than made
  quietly in a manifest. The same gap silently disables
  `TogetherManager.kt`'s auto-open of an incoming invitation.

  Desktop still has no player at all (`docs/TOGETHER.md` §9), so `/play` there is
  refused by the `DESKTOP_CAN_PLAY` constant as before. `play_route` is exposed
  as a Tauri command already, so the day a player lands the decision is not
  reimplemented in JS.

  Android also cannot *start* a YouTube session: core can carry the invitation,
  but this app has no player for one and cannot join its own, so `PLAY_EMBED`
  refuses rather than inviting someone to something we cannot attend.
- ~~**`@tara` is only ever private.**~~ **Both audiences shipped 2026-08-04.**
  `@tara` now answers in the conversation (`ChatCommand::TaraHere` →
  `RuntimeHandles::tara_in_chat`) and `/tara` stays the private session; §2 has
  the whole shape, including the distress case that sends nothing. Android and
  desktop both label the audience while typing. **Closed the rest of the way
  2026-08-05**: `MessageDto` carries `author: MessageAuthor`, the bindings were
  regenerated rather than hand-edited, and all three frontends — Flutter
  included — draw her line as its own participant. What remains is not a gap but
  an accepted boundary: the marker is unsigned, so the bubble is attribution
  rather than attestation (`AUDIT.md` Q17).
- **`/breathe` and friends do not navigate.** `ConversationScreen` owns no nav
  state, so the composer names the tab rather than opening it. One host
  parameter away.
- **A received offer has no button.** The line renders; tapping it does nothing.
  `offerAffordance` (desktop) and `AppAction` (Android) carry everything needed.
- **`VoiceCommand.kt` still has its own grammar.** It works and is tested;
  folding it into `comrade_core::command` is worth doing but is a refactor of a
  parser this sandbox cannot run tests for, so it was left alone deliberately.
- **`app/` (Flutter) gets none of it.** Recorded as a decision in
  `rust_comrade_repository.dart`'s existing "what the bridge carries that this
  interface does not" section, following the `nudgeComrades` precedent.
- **The catalogue adapter has never made a live request.** `catalogue-http` is
  exercised by CI against a fixture and the parser is tested; nobody has pointed
  it at musicbrainz.org.

### Three things that shipped broken, and what was wrong

Reported from a device on 2026-08-04, all three in the first Android build of
this feature. None had a test that could have caught it, which is the part worth
recording.

1. **The `/` picker could not be scrolled.** A bare `/` matches the whole
   catalogue, each row is two lines, and Android rendered them in a plain
   `Column` with no cap — so the list grew past the screen, the rows below the
   fold were unreachable, and the thread was pushed out of view. Now a
   `LazyColumn` bounded by `PICKER_MAX_HEIGHT`. `pickerRows` was right the whole
   time and its tests still pass, which is exactly why they did not help: **the
   defect was in how many rows fit, and nothing tested that.** Desktop was never
   affected — `.command-picker` has had `max-height` + `overflow-y: auto` since
   it landed.
2. **An ambiguous `@handle` was a dead end.** Two contacts answering to one
   handle produced *"More than one contact answers to @ana — pick which one"* —
   an instruction with nothing to pick, so the command could never be completed.
   The ambiguity itself was right (`MentionMatchDto::candidates` has always
   carried it; picking one for the user is how a private message reaches the
   wrong person). What was missing was the chooser. Now `ComposerPlan.Choose` /
   `CHOOSE` carries the candidates, the composer lists them, and picking one
   records the choice and **re-runs the same draft** through `withChoices`. Every
   row shows the short key beside the name, because the usual reason a handle is
   ambiguous is that both people chose the same name too. A pin is honoured only
   while it still names one of that handle's own candidates — a stale choice must
   not silently retarget a later message.
3. **Opening Tasks from the drawer killed the app.** `TaskListScreen` used two
   early `return@Column`s — the only ones in the whole Android source — so the
   Column emitted a different number of composable groups before and after
   `loaded` flipped, which is the shape that throws on the *recomposition* rather
   than the first frame. Rewritten as a single `when`, the way every other screen
   here branches. **This one is stated with a caveat:** no Android SDK exists in
   the container that wrote the fix, so the diagnosis is from reading the code,
   not from a stack trace. What is *not* a caveat is the regression cover —
   `MainActivityUiTest` now taps `drawer-tasks` on the emulator and waits for the
   list to finish loading, so the failing recomposition is exercised on a device
   in CI. `TaskList`'s decisions were all unit-tested and all passed; the crash
   was in the composition around them.

## 8. Where the code lives

| Layer | What it owns |
|---|---|
| `comrade_core::command` | The grammar, mentions, the catalogue of commands, `AppAction` and the offer wire. Pure; 44 tests. |
| `comrade_core::karya` | Task shape, the state machine, the envelope, the rendered line. Pure; 21 tests. |
| `comrade_core::catalogue` | `CatalogueResolver`, `choose_audio_plan`, the tier ladder, the licence gate, MusicBrainz. 15 tests, 21 under `catalogue-http`. |
| `comrade_core::tara` | `mentions_third_party` and the reframing reply, in front of the engine; `TARA_CHAT_PREFIX` / `tara_chat_line` / `tara_chat_answer` for the shared line, whose tests assert it is a *label* rather than a guarantee. |
| `comrade_storage` | The `karya` tree; ciphertext-at-rest and panic-wipe pinned. |
| `comrade_ui::runtime` | `parse_chat_command`, `resolve_mentions`, `play_query`, `assign_task` / `tasks` / `set_task_state`, `offer_action`, `tara_aside`, `tara_in_chat`, and two arms in `dispatch_incoming_dm`. Nothing was added to the dispatcher for `@tara`: her answer is an ordinary DM, so the receiving side already renders it. |
| `comrade_jni` (uniffi), `desktop/src-tauri` | The same calls. **No `api.rs` change**, so no bridge regeneration. |
| `desktop/ui/chat_commands.mjs` | What the composer does with a parsed command, the `/` picker, the honest "not here yet" sentences, and which Tara audience a draft implies, plus the mirror of `split_author` the live-DM path needs. 35 `node --test` cases. |
| `android/…/ui/ChatCommands.kt` | The same decisions, mirroring the desktop vectors case for case. 35 JVM cases; Compose-free. **Never compiled here** — no Android SDK in the container that wrote it. |
| `desktop/ui/task_list.mjs` · `android/…/ui/TaskList.kt` | Grouping, which buttons a row offers, the subtitle, the empty copy. Mirrored vectors — 15 `node --test` cases, 11 JVM. Note the field names differ on purpose: Tauri sends serde snake_case, uniffi generates camelCase properties — and so is the state a button sends: `wireState()` is lowercase because `TaskState` is `rename_all = "snake_case"`, while Android passes the uniffi enum and has no string to get wrong. |
| `desktop/ui/main.js` (Tasks tab) · `android/…/ui/TaskListScreen.kt` | The rendering, and nothing else — every decision above it. |
| `comrade_ui::play_route` | The five things a frontend may do about a `/play`, from the plan plus its own library answer. Only `StartTogether` opens a session, and only on a library hit. 3 tests. |
| `ChatCommands.playNote` · `LibraryResolver.mayRead` | The sentence for each route, and the one distinction that is not core's to make: "no copy here" versus "not allowed to look". 6 JVM cases. |
| `android/…/together/MediaLibraryAccess.kt` | When Comrade asks to read the music library, and which permission a release actually grants — at most one ask, because Android stops showing the dialog after a refusal. 3 JVM cases; Compose-free. **Never compiled here.** |
