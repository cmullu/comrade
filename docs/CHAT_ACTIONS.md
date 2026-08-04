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

`comrade_core::command`. Pure, no I/O, no clock, 43 unit tests.

| Type this | It does |
|---|---|
| `/play`, `/listen`, `/watch`, `/spotify`, `/youtube`, `/apple` | Listen or watch together |
| `@tara …` or `/tara …` | A private aside — only you see it |
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

## 2. Tara in a chat — one guard and one reframe

`@tara <text>` opens a **private aside**: it never sends, the peer never sees it,
and it lands in the existing `tara_companion` thread. The composer looks
different from the moment `@tara ` is typed, before there is anything to parse —
a private thing that looks like a message is how somebody sends one by accident.

**It is seeded only with what the user typed.** Not the chat history, not the
peer's messages. A "reflect on this conversation" feature would quietly turn the
other person's words into input to a companion they never opted into.

**`tara::mentions_third_party` sits in front of the engine**, where
`detect_distress` already sits, and must stay in front of any future model
(`docs/TARA.md`, OQ9). An aside naming another person does not get a
characterisation of them; it gets the askable question back:

> I can't tell you what someone else thinks or feels — I'd only be making it up,
> and it wouldn't be fair to them. What I can stay with is your side of it:
> what's coming up for you about @xyz?

The request's own example — *"@tara what does she @xyz thinking of herself"* — is
a literal test vector. Two reasons, both from `docs/TARA.md`'s non-negotiable
gates. Inferring what a real person thinks of themselves is a psychological
assessment of somebody who has not consented, is not in the room, and cannot
correct it (gate 1, "never diagnoses"). And `ReflectiveCompanion` is a cue-word
template matcher — asked about a third party it would emit a fluent, confident
sentence with no information in it, about a human being the reader knows.

**The rule is blunt on purpose.** Any aside naming a person turns around; there
is no cue list deciding which questions about somebody count as assessments,
because a fuzzy matcher guarding a hard boundary fails on exactly the cases that
matter. Bluntness costs almost nothing, because the redirect is *also* the right
reflective move: "I'm worried about @ana" is legitimate material here, and
"what's coming up for you about ana?" is what a good listener says to it. The
safety property and the product behaviour are the same sentence.

`detect_distress` still runs **first**. Somebody in crisis who names a friend
needs the helplines, not a reflective question.

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

*except a note to self, where one person holds both roles.

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
3. **The count comes back.** A deliberate command that silently did nothing reads
   as a bug, so `offer_action` returns how many were told and every frontend says
   *"they were told recently"* rather than nothing.

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

**No new `BridgeEvent` variant.** A rendered line is surfaced through the
existing `IncomingDirectMessage`, reusing the incoming event's real id so the
plain-chat dedup catches a redelivery. Adding a variant would have meant an
exhaustive Kotlin `when`, two exhaustive Dart switches, and regenerating
`frb_generated.rs` — see `CLAUDE.md`'s traps.

## 7. What is built, and what is not

Built and tested: the grammar, the task engine and its state machine, the Tara
guard, the storage tree, the view-model, the uniffi and Tauri surfaces, the
desktop decision module and composer, and the Android composer. Two-peer
integration tests drive a real assignment → same id on the other device → done
coming back, plus the stranger gate and an offer arriving as a readable line.

**Not built**, and stated here rather than discovered:

- **A dedicated task list.** Tasks appear as chat lines in the thread they were
  named in, and `ComradeCore.tasks()` / the `tasks` Tauri command are live, but
  neither frontend has a list screen. A **note to self therefore has no surface
  at all** — it is stored and reachable over FFI and nothing renders it. This is
  the largest gap and the next thing to build.
- **`/play` does not open a session yet.** The command parses, `play_query`
  resolves links and free text, and Android's `TogetherManager` /
  `LibraryResolver` already exist — the two are not joined up, so the composer
  says where to go instead. Desktop has no player at all (`docs/TOGETHER.md` §9).
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

## 8. Where the code lives

| Layer | What it owns |
|---|---|
| `comrade_core::command` | The grammar, mentions, the catalogue of commands, `AppAction` and the offer wire. Pure; 43 tests. |
| `comrade_core::karya` | Task shape, the state machine, the envelope, the rendered line. Pure; 18 tests. |
| `comrade_core::catalogue` | `CatalogueResolver`, `choose_audio_plan`, the tier ladder, the licence gate, MusicBrainz. 15 tests, 19 under `catalogue-http`. |
| `comrade_core::tara` | `mentions_third_party` and the reframing reply, in front of the engine. |
| `comrade_storage` | The `karya` tree; ciphertext-at-rest and panic-wipe pinned. |
| `comrade_ui::runtime` | `parse_chat_command`, `resolve_mentions`, `play_query`, `assign_task` / `tasks` / `set_task_state`, `offer_action`, `tara_aside`, and two arms in `dispatch_incoming_dm`. |
| `comrade_jni` (uniffi), `desktop/src-tauri` | The same calls. **No `api.rs` change**, so no bridge regeneration. |
| `desktop/ui/chat_commands.mjs` | What the composer does with a parsed command, the `/` picker, and the honest "not here yet" sentences. 26 `node --test` cases. |
| `android/…/ui/ChatCommands.kt` | The same decisions, mirroring the desktop vectors case for case. 22 JVM cases; Compose-free. **Never compiled here** — no Android SDK in the container that wrote it. |
