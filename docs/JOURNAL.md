# The journal — video entries

Wellbeing pillar #1. What was already here: type or dictate an entry, tag a
mood, hand one entry to one person. What is new: **record one**, give it a
title, and have it kept somewhere the phone's gallery cannot see.

This document is about the video half. The sharing half is
`comrade_core::note` and the copy rules in `strings.xml` under
`journal_share_*`.

## What shipped, and where

| Layer | File | What it owns |
| --- | --- | --- |
| Store | `crates/comrade_storage/src/repository.rs` | `JournalEntry.title`, `JournalEntry.video`, `JournalVideo` |
| Runtime | `crates/comrade_ui/src/runtime.rs` | `add_journal_video`, `set_journal_entry_title`, `JournalVideoDto` |
| FFI | `crates/comrade_jni/src/{lib,api}.rs` | uniffi (Android) + frb (Flutter) surface |
| Android | `android/.../journal/JournalVideos.kt` | naming, titles, formatting, the sweep rule — **no Android imports, JVM-testable** |
| Android | `android/.../journal/JournalVideoStore.kt` | the folder, the move off the camera, deletion, the sweep |
| Android | `android/.../ui/JournalVideoCard.kt` | the strip, the player, the title dialog |
| Android | `android/.../ui/JournalScreen.kt` | capture flow, and the card that draws all of it |
| Flutter | `app/lib/src/util/journal_video.dart` | the *presentation* half, mirrored, same numbers |

## Where a recording lives, and why the gallery never shows it

`filesDir/journal-videos/` — app-private **internal** storage, one directory of
its own, holding nothing but journal recordings.

That is not the usual `.nomedia` answer, and it is deliberately stronger than
one. A `.nomedia` file asks the media scanner to skip a folder it can otherwise
see; it is worth exactly as much as the scanner's cooperation. App-private
internal storage is not part of any shared media volume at all — `MediaStore`
does not index it, no gallery can enumerate it, and no other app on the device
can open a file in it. There is nothing to ask, so no marker file is written.

The camera app is another app and it does have to write *somewhere*: it is
pointed at `cacheDir/journal-capture/` through the existing `FileProvider`
(`res/xml/file_paths.xml`), and `JournalVideoStore.adopt` moves the finished
file into the journal folder. The only thing another app is ever granted is the
single file it is in the middle of writing — never the directory holding
everything already recorded.

Filenames are `jv-<millis>-<nonce>.mp4`. Deliberately not derived from the
title: a file named after what somebody called their entry puts those words in
a filename, where they are not encrypted and where a crash reporter or a backup
tool could carry them off. The title belongs in the sealed store and nowhere
else.

## The one promise this does not make

**The words are sealed by the passcode. The footage is not.**

The title, the text and the mood are AES-GCM values in the encrypted store,
exactly like every other journal entry, and there is a test that reads the redb
file back and proves the title never hits disk in plaintext. The recording is a
plain `.mp4` protected by the app sandbox and the device's own encryption.

That gap is real, it is written down as **AUDIT J-1** with the condition that
would close it (a chunked sealed-file primitive in `comrade_storage`), and until
it closes no copy anywhere may describe a recording as sealed or encrypted.
`journal_video_where` in `strings.xml` is the line that has to say both halves
in one breath, and it does:

> Kept in Comrade's own folder on this phone — your gallery never sees it, and
> no other app can open it. What you write stays sealed by your passcode; the
> recording itself is protected by the phone, not the passcode.

## Two writes that cannot be made one

Deleting a video entry removes a sealed record *and* a file. There is no
transaction across those, so the order is chosen for which leftover is
survivable:

- **Record first, then the file.** A kill in between leaves footage nothing
  points at — invisible to the user, and swept on the next open of the tab by
  `JournalVideoStore.sweepOrphans`.
- The other order would leave an entry pointing at a recording that is gone,
  which is the app telling somebody they have something they do not.

The same reasoning runs the other way when saving: `adopt` puts the file in
place *before* the entry is written, so an interrupted save is an orphan and
never a broken entry.

The sweep is the only code in this feature that deletes a user's recording
unasked, so its rule (`orphanedVideoFiles`) is biased entirely towards keeping
files: only names this app itself minted are candidates, and it runs once, at
the first composition of the screen, when no capture can be in flight. A sweep
*during* a capture would find the fresh recording unreferenced and delete it.

## Sharing

A recording has no share path. Nothing in this app uploads journal footage, so
`share_journal_entry` sends the words as it always did and now refuses outright
when there are none — a video entry with no text would otherwise put a note card
carrying nothing but a mood marker in somebody's chat. Both frontends hide the
share control on an entry with no words rather than offering one that fails.

## Frontend parity — where this stands

Android has it. Per `docs/FRONTEND_STRATEGY.md` §11 that is the priority
frontend and it goes first; this section is the "say so plainly" half of that
rule.

- **`android/`** — records, titles, plays, renames, deletes, sweeps.
- **`app/` (Flutter)** — *shows* a video entry (title, length, size) and does
  not record or play one. Not an oversight and not a small gap to close later
  by porting a widget: the footage never leaves the device that recorded it, so
  even a Flutter build on the same phone would need the capture path, the
  folder and the sweep before it had anything to play. The presentation rules
  are mirrored in `app/lib/src/util/journal_video.dart` with the identical
  numbers and identical tests, so when capture is ported the formatting will
  already agree.
- **`desktop/`** — has no journal UI at all (three `journal_*` Tauri commands
  registered, no caller). Unchanged by this; the extra DTO fields are additive
  JSON that nothing there reads.

## Google Keep-shaped, eventually

The owner's ask names Keep as the destination for this tab. What landed is the
part that had to come first, plus the two pieces the rest of it needs:

- **`title` is on every entry**, not just video ones, and
  `set_journal_entry_title` retitles any of them. So notes get titles without a
  second storage change, and retitling deliberately leaves `created_at` alone —
  renaming is not writing, and it must not move an entry to the top of
  somebody's own history.
- **The card already branches on what an entry is** (heading / recording /
  words), which is the shape a mixed grid of notes needs.

Not done, and not pretended: the grid layout, pinning, labels, colours,
checklists, search, and archive. Those are UI over a store that can now
describe more than one kind of note.
