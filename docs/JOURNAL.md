# The journal — voice and video entries

Wellbeing pillar #1. What was already here: type or dictate an entry, tag a
mood, hand one entry to one person. What is new: **record one** — spoken or
filmed — give it a title, and have it kept somewhere the phone's gallery and
music player cannot see.

This document is about the recording half. The sharing half is
`comrade_core::note` and the copy rules in `strings.xml` under
`journal_share_*`.

## Dictation is not recording, and that confusion is what this fixed

The journal has had a microphone button since long before any of this. It runs
Vosk and **transcribes** — your voice becomes typed text and is then gone. That
is a useful thing and it stays exactly as it was.

It is not what most people reach for a microphone expecting. Tapping it to
*keep* your voice and getting a paragraph of text instead is the bug this change
closes, and it is why the two now sit side by side with different glyphs and
different words: a mic that says "Dictate — turns what you say into text", and a
level meter that says "Record a voice entry — keeps your voice".

## What shipped, and where

| Layer | File | What it owns |
| --- | --- | --- |
| Store | `crates/comrade_storage/src/repository.rs` | `JournalEntry.title`, `JournalEntry.recording`, `JournalRecording` |
| Runtime | `crates/comrade_ui/src/runtime.rs` | `add_journal_recording`, `set_journal_entry_title`, `JournalRecordingDto` |
| FFI | `crates/comrade_jni/src/{lib,api}.rs` | uniffi (Android) + frb (Flutter) surface |
| Android | `android/.../journal/JournalRecordings.kt` | kinds, naming, titles, formatting, playback arithmetic, the sweep rule — **no Android imports, JVM-testable** |
| Android | `android/.../journal/JournalRecordingStore.kt` | the two folders, the move off a capture, deletion, the sweep |
| Android | `android/.../media/VoiceRecorder.kt` | the recorder, with a profile per caller (chat note vs journal entry) |
| Android | `android/.../ui/AudioPlayerBar.kt` | **the one audio player** — journal voice entries *and* chat voice notes |
| Android | `android/.../ui/JournalRecordingCard.kt` | the strip, the video player, the title dialog |
| Android | `android/.../ui/JournalScreen.kt` | both capture flows, and the card that draws all of it |
| Flutter | `app/lib/src/util/journal_recording.dart` | the *presentation* half, mirrored, same numbers |

## Where a recording lives, and why the gallery never shows it

`filesDir/journal-videos/` and `filesDir/journal-audio/` — app-private
**internal** storage, a directory per kind, holding nothing but journal
recordings.

That is not the usual `.nomedia` answer, and it is deliberately stronger than
one. A `.nomedia` file asks the media scanner to skip a folder it can otherwise
see; it is worth exactly as much as the scanner's cooperation. App-private
internal storage is not part of any shared media volume at all — `MediaStore`
does not index it, no gallery can enumerate it, and no other app on the device
can open a file in it. There is nothing to ask, so no marker file is written.

Two directories rather than one tidier `journal-recordings/`, and that is a
deliberate refusal: renaming the video directory would strand every recording
already on a phone from the version that shipped with `journal-videos`, and a
migration that moves somebody's journal is a worse thing to get wrong than a
folder name is to leave imperfect.

Recording has to happen *somewhere* first. Video is filmed by the camera app —
another app — which is pointed at `cacheDir/journal-capture/` through the
existing `FileProvider` (`res/xml/file_paths.xml`); audio is recorded in this
process straight into the same directory. Either way `JournalRecordingStore.adopt`
moves the finished file into the journal folder, so the only thing another app
is ever granted is the single file it is in the middle of writing — never the
directory holding everything already recorded.

Filenames are `jv-<millis>-<nonce>.mp4` and `ja-<millis>-<nonce>.m4a`. The
prefix differs by kind even though the two live in separate folders; that
redundancy is what stops the audio sweep ever deleting a video, whatever else
goes wrong. Deliberately not derived from the title: a file named after what
somebody called their entry puts those words in a filename, where they are not
encrypted and where a crash reporter or a backup tool could carry them off. The
title belongs in the sealed store and nowhere else.

## Why a voice entry is M4A and a chat voice note is not

Both are AAC and both come out of the same `MediaRecorder`. They differ in the
container, and it is not a detail:

- **Chat voice notes stay ADTS** (`audio/aac`), a raw elementary stream. It
  carries no duration header and cannot be seeked accurately. For a clip that is
  recorded, encrypted, sent and played once, straight through, none of that
  costs anything.
- **Journal voice entries are MPEG-4** (`audio/mp4`, an `.m4a`). A journal entry
  is kept, listed with its length next to it, and scrubbed back and forth — all
  of which need the duration and the seek index that MPEG-4 writes and ADTS does
  not.

Get this backwards and the symptom is a voice entry with no length on its card
and a scrubber that jumps. `VoiceRecorder.Profile` is where the choice lives, so
neither caller can pick the other's by accident.

## The one promise this does not make

**The words are sealed by the passcode. The recording is not.**

The title, the text and the mood are AES-GCM values in the encrypted store,
exactly like every other journal entry, and there is a test that reads the redb
file back and proves the title never hits disk in plaintext. The recording — a
plain `.mp4` or `.m4a` — is protected by the app sandbox and the device's own
encryption.

That gap is real, it is written down as **AUDIT J-1** with the condition that
would close it (a chunked sealed-file primitive in `comrade_storage`), and until
it closes no copy anywhere may describe a recording as sealed or encrypted.
`journal_video_where` in `strings.xml` is the line that has to say both halves
in one breath, and it does:

> Kept in Comrade's own folder on this phone — your gallery never sees it, and
> no other app can open it. What you write stays sealed by your passcode; the
> recording itself is protected by the phone, not the passcode.

## Two writes that cannot be made one

Deleting a recording entry removes a sealed record *and* a file. There is no
transaction across those, so the order is chosen for which leftover is
survivable:

- **Record first, then the file.** A kill in between leaves a recording nothing
  points at — invisible to the user, and swept on the next open of the tab by
  `JournalRecordingStore.sweepOrphans`, which sweeps both folders.
- The other order would leave an entry pointing at a recording that is gone,
  which is the app telling somebody they have something they do not.

The same reasoning runs the other way when saving: `adopt` puts the file in
place *before* the entry is written, so an interrupted save is an orphan and
never a broken entry.

The sweep is the only code in this feature that deletes a user's recording
unasked, so its rule (`orphanedRecordingFiles`) is biased entirely towards
keeping files: only names this app itself minted *for that kind* are candidates,
and it runs once, at the first composition of the screen, when no capture can be
in flight. A sweep *during* a capture would find the fresh recording
unreferenced and delete it — which is why the record buttons stay disabled until
it finishes.

## One player, two callers

`AudioPlayerBar` plays every recording this app has: journal voice entries and
chat voice notes. Its callers differ only in how the file is resolved — a plain
read of app-private storage for the journal, a decrypt for a chat attachment —
so that is the one thing passed in.

Sharing it was the point rather than a tidiness win. The chat bubble used to be
a play button and the words "Voice message": no length, no progress, no way back
over the sentence you missed. There is now one implementation to improve instead
of two to keep in step, and it has a play/pause control, a seek bar and an
`elapsed / total` counter.

Deliberately **not** a waveform. Drawing one means decoding the whole clip to
amplitudes when the card appears, and the shape of a voice note is not something
anyone acts on. A slider is the control; a waveform is a picture of one.

**Video is not given this treatment, on purpose.** A video is watched, so it
keeps its poster and opens full screen with the platform's own controls; a
280 dp inline video is a thumbnail, not a look at it. Audio has nothing to look
at, so a full-screen player would be a black rectangle with a slider on it.
Making them identical would have been consistency at the cost of the thing
actually being used.

## Sharing

A recording has no share path. Nothing in this app uploads a journal recording,
so `share_journal_entry` sends the words as it always did and now refuses
outright when there are none — a recording entry with no text would otherwise
put a note card carrying nothing but a mood marker in somebody's chat. Both frontends hide the
share control on an entry with no words rather than offering one that fails.

## Frontend parity — where this stands

Android has it. Per `docs/FRONTEND_STRATEGY.md` §11 that is the priority
frontend and it goes first; this section is the "say so plainly" half of that
rule.

- **`android/`** — records, titles, plays, renames, deletes, sweeps.
- **`app/` (Flutter)** — *shows* a recording entry of either kind (which it is,
  title, length, size) and does not record or play one. Not an oversight and not
  a small gap to close later by porting a widget: a recording never leaves the
  device that made it, so even a Flutter build on the same phone would need the
  capture path, the folders and the sweep before it had anything to play. The
  presentation rules are mirrored in `app/lib/src/util/journal_recording.dart`
  with identical numbers and identical tests — including `clipProgress` and
  `playbackLabel`, which nothing there calls yet and which exist so that the day
  it grows a player, its counter reads the same as Android's rather than being
  invented again.
- **`app/`'s chat voice-note bubble still has the old minimal control**, and
  that is the one piece of this change that did not land everywhere. Its audio
  goes through a platform channel whose `toggleAudio` returns a single bool —
  no position, no duration, no seek — so a scrubber there means extending the
  channel on both the Dart and the Kotlin sides. That is a real piece of work
  and a separate one; it is named here so it is a known gap rather than an
  assumed parity.
- **`desktop/`** — has no journal UI at all (three `journal_*` Tauri commands
  registered, no caller). Unchanged by this; the extra DTO fields are additive
  JSON that nothing there reads.

## Google Keep-shaped, eventually

The owner's ask names Keep as the destination for this tab. What landed is the
part that had to come first, plus the two pieces the rest of it needs:

- **`title` is on every entry**, not just recordings, and
  `set_journal_entry_title` retitles any of them. So notes get titles without a
  second storage change, and retitling deliberately leaves `created_at` alone —
  renaming is not writing, and it must not move an entry to the top of
  somebody's own history.
- **The card already branches on what an entry is** (heading / recording /
  words), which is the shape a mixed grid of notes needs.

Not done, and not pretended: the grid layout, pinning, labels, colours,
checklists, search, and archive. Those are UI over a store that can now
describe more than one kind of note.
