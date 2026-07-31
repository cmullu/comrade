# Tara — the reflective companion

_Design note for wellbeing pillar #4 (AUDIT §8). Status: v1 shipped 2026-07-22._

Tara is a private, on-device space to think out loud: she mirrors feelings,
asks reflective questions, scaffolds brainstorming, and nudges journaling.
She is deliberately **not** a chatbot pretending to be a person, and above
all **not therapy**.

## The two honesty gates (non-negotiable)

These come from AUDIT §8 and every future change to Tara must keep them true:

1. **Not therapy, and it says so.** Tara never diagnoses, never treats, never
   presents as a clinician. The user opts in through an explainer that says
   exactly this; a persistent footer repeats it; and when a message carries
   distress cues Tara *stops prompting* and hands off to real crisis
   helplines. The hand-off is regression-tested
   (`comrade_core::tara::tests`, `comrade_ui` lifecycle tests).
2. **On-device or not at all.** Raw mental-health disclosures must never be
   routed to a cloud API — that would contradict the product's core promise
   exactly where it matters most. The v1 engine is deterministic Rust with
   zero network access, so the guarantee holds *by construction*, not by
   policy.

## What shipped in v1

| Layer | What |
|---|---|
| Engine | `comrade_core::tara` — `CompanionEngine` trait + `ReflectiveCompanion` impl: greeting/feeling/advice/default reply families, turn-seeded prompt rotation, `detect_distress` cue matcher, `CRISIS_RESOURCES` (Tele-MANAS, KIRAN, AASRA, findahelpline.com) |
| Storage | `tara_companion` tree in the encrypted store (`TaraMessage`: id, text, `from_tara`, `crisis`, `created_at`); oldest-first thread; `clear_tara_messages`; ciphertext-at-rest proven by test, same as the journal |
| View-model | `ComradeRuntime::tara_send / tara_thread / clear_tara_thread / tara_opener / tara_crisis_resources` + `TaraMessageDto` / `CrisisResourceDto` |
| Android | **Tara** bottom-nav tab → `TaraScreen`: opt-in explainer (stored in `tara` prefs), chat bubbles, crisis card under any flagged reply, persistent "not a therapist" footer, clear-conversation dialog |
| Desktop | The five Tauri commands are registered (`desktop/src-tauri`); the vanilla-JS web UI predates the wellbeing pillars and does not render Tara yet (same state as the journal) |

Tara sits **last** in the bottom navigation (Chats · Journal · Feed · Tara):
the messaging surfaces are the daily ones, and the companion is what you reach
for on purpose.

## Streaming replies (the "LLM interface" feel)

Replies arrive progressively rather than as one block: a thinking indicator
while the engine runs, then the text filling in word by word.
`ui/TaraStream.kt` owns the rule and is deliberately tiny and pure —
`chunk(text)` splits on whitespace boundaries into ~8-character pieces, and
`stream(text)` emits growing prefixes as a `Flow<String>`.

Two things are worth being precise about:

* **This paces text that already exists.** The reflective engine computes the
  whole sentence at once, so streaming is a *presentation* choice today, not a
  token stream. It is built as `Flow<String>` of cumulative text precisely so
  that a real generative backend emits into the same shape and the UI needs no
  change. The tests pin losslessness (`chunk(t).joinToString("") == t`) — an
  animation that dropped characters would corrupt what the user is told.
* **Crisis replies never stream.** When `detect_distress` fires, the hand-off
  and its helpline numbers render complete and immediately. Drip-feeding
  someone in distress their emergency numbers would be a cruel animation.

## The shared model-download pipeline

"Hey Comrade" and Tara run through **one** download path — `android/.../model/`:

| Piece | Role |
|---|---|
| `ModelSpec` / `ModelCatalog` | Pure data: pinned URL + sha256 + size, packaging (zip-directory vs single file), install location, and which tab to return to when finished |
| `ModelInstaller` | Download → sha256 verify → extract-or-move → atomic swap, with zip-slip and partial-install guards. Pure JVM, so the whole pipeline is unit-tested against `file://` URLs |
| `ModelDownloads` | Process-wide `StateFlow` per model, so dismissing the prompt (or backgrounding the app) leaves the transfer running and any screen can re-observe it |
| `ModelDownloadService` | Foreground service: determinate progress in the notification bar, then a tappable "ready" notification that deep-links back (via `AppNavigation`) — for the companion model, straight into the Tara conversation, even if the vault needs unlocking first |

**One model cannot serve both features.** The speech model is a Vosk/Kaldi
*recogniser* (audio → text); a companion is a *generative* model (text →
text). Neither can do the other's job, so what is shared is the machinery, not
the weights.

A foreground service (not WorkManager) does the work: it matches
`CallService`/`RelayConnectionService` already in the app and adds no new
dependency. WorkManager would additionally survive process death, which is the
reason to revisit it if these downloads grow much larger.

`ModelCatalog.COMPANION` ships **unpinned** (`configured == false`), so no
companion download is offered yet — see OQ9 below. The plumbing, the
notification UX and the deep-link back to Tara are all live and exercised by
the speech model today; pinning a URL + sha256 is what switches the companion
offer on.

Sequence-numbered store ids (`{timestamp}-{seq}`) keep user/reply pairs in
exact send order even within the same second — random id tails would let
pairs interleave.

## Privacy posture

- The thread exists only inside the encrypted store (Argon2id + AES-256-GCM);
  no relay, no network, no analytics.
- The opener nudge ("two low days this week…") reads journal **mood markers
  and entry age only** — never journal text. Data minimisation is the point:
  the companion doesn't need your words to invite you to reflect.
- "Clear conversation" deletes every turn; there is no other copy to forget.

## Crisis hand-off behaviour

`detect_distress` is a normalising, whole-phrase cue matcher, deliberately
conservative **in favour of showing help**: a false positive costs one extra
card of helpline numbers; a false negative costs the hand-off itself. When it
fires, both the user turn and the reply are stored with `crisis = true`, the
reply is a fixed hand-off message (no reflective prompt), and every frontend
must render the crisis resources with it — the flag is part of the DTO
contract, not a UI nicety.

## OQ9 and the LLM slot

The AUDIT's OQ9 asks: on-device quantised LLM vs. template-only vs. cloud.
v1 ships the **template-only** option so the surface, storage, safety and
FFI plumbing are real while the model/runtime half of OQ9 stays an owner
decision. The `CompanionEngine` trait is the seam:

- An on-device backend (llama.cpp-class or candle-class runtime, small
  quantised weights fetched like the Vosk model — one-time, sha256-verified,
  in-app) implements `reply`/`opener` and slots in behind the same
  `tara_send` path. **The `detect_distress` gate must stay in front of any
  model** — the crisis hand-off is not delegated to model behaviour.
- A cloud backend must never implement the trait (gate 2).

**What is already built for it.** The download half is done and in use: pin
`ModelCatalog.COMPANION`'s `url`/`sha256`/`downloadBytes` and the prompt,
background transfer, notification progress and tap-back-into-Tara all light up
with no further UI work. What remains is genuinely the inference side:

1. the owner's model + runtime choice (OQ9), including licence and the
   size/quality trade-off on low-end phones;
2. a real streaming implementation behind `CompanionEngine` (it should emit
   tokens into the existing `Flow<String>` rather than returning a finished
   string);
3. a verified checksum for whatever artifact is chosen. This was **not**
   guessed: an invented sha256 would fail every install, and an unverified
   download is worse than none.

Open follow-ups: the OQ9 model/runtime decision and its inference backend, and
a desktop web UI surface.

## Talking to Tara by voice

"Hey Comrade, tara I've been anxious all week" reaches the same engine as the
tab. `VoiceCommand.Tara` parses `tara` / `talk to tara` / `ask tara` /
`tell tara` (longest phrasing first, so `talk to tara` isn't shadowed by the
bare `tara`), and it is matched **before** the post prefixes — like the journal,
a thought meant for the companion must never fall through to the public feed.
Addressing her with nothing after it prompts rather than sending an empty
message.

Two details the voice modality forced:

* **`CRISIS_REPLY` is modality-neutral.** It used to say "the helplines shown
  below", which is meaningless read aloud. It now just says to reach a crisis
  helpline, and each surface adds its own affordance — the app renders the card,
  the voice layer reads a number out.
* **Spoken numbers are spelled digit by digit.** `14416` is passed to TTS as
  `1 4 4 1 6`, or it gets read as "fourteen thousand four hundred and sixteen"
  — a number nobody can dial. Tested.

The crisis gate itself is not duplicated for voice: `detect_distress` lives in
the Rust engine, so every surface inherits it by construction.
