# What Comrade can take from FileSync — and what it deliberately will not

_Analysis date: 2026-08-08. Source: [polius/FileSync](https://github.com/polius/FileSync)
at `main`, ~8.8k lines across `web/js/` (vanilla ES modules), `api/` (FastAPI),
`e2e/` (Playwright) and `deploy/` (Docker + coturn + Caddy)._

FileSync is a self-hosted, browser-only, one-to-many file sender: a room link, a
drag-and-drop, and bytes over a WebRTC data channel while a small FastAPI
WebSocket brokers the handshake. Comrade already moves files over a WebRTC data
channel twice — `comrade_core::share` behind a `together` handover, and
`comrade_core::handoff` behind an attachment handoff — so the overlap is real
and narrow at the same time.

**Narrow, because most of FileSync is answering questions Comrade has already
answered differently and on purpose.** It runs a signaling server; Comrade
signals inside a Nostr envelope and has no server to add one to. It is a
browser, so half its cleverness is about getting bytes past the browser's own
download machinery; Comrade's priority frontend is Android with a filesystem and
its shipping desktop is Tauri with Rust underneath. It has no integrity check on
the wire at all; Comrade hashes.

**Real, because FileSync has run this exact transfer in front of real users, on
three engines, across NATs, and the scars are in the code as comments.** Four of
those scars name a gap Comrade has today. This document is the ledger: every
mechanism examined, the verdict, and the citation on both sides.

Verdicts are `ADOPT` (a real gap here — filed as an `AUDIT.md` finding, not
fixed in this change), `ALREADY HAVE`, `BETTER HERE` (Comrade's shape makes the
bug structurally impossible — do not regress toward FileSync's), or `REJECTED`
(examined and deliberately not wanted).

**Status, updated 2026-08-08.** This document was written as analysis only. Three
of the four `ADOPT` rows have since been implemented — `AUDIT.md` Q18, P8 and Q19
are struck there, with the citations kept current in the ledger rather than here.
T5 is half closed: the byte-identity gate exists, the forced-ICE half does not.
The quoted Comrade code below is therefore **the code as it was when the gap was
found**, kept because the point of the document is the comparison that produced
the finding. Read `AUDIT.md` for what the code does now, and §6 for what the
implementing round itself turned up.

---

## 1. The summary table

| FileSync mechanism | Verdict | Where it lands |
|---|---|---|
| Guard the transfer against a re-delivered signal (`user.js:384`, `peer.js:534`) | **ADOPTED** | `AUDIT.md` Q18 — struck |
| One held file handle for the whole receive (`sink.js:171-187`) | **ADOPTED** (receiver only) | `AUDIT.md` P8 — struck; §4.6 |
| Detect a stalled transfer; tell the other side you gave up (`file.js:335-352`, `:420`) | **ADAPTED** | `AUDIT.md` Q19 — struck; the "tell the other side" half is Q20 |
| Forced-ICE dev override + end-to-end byte-identity harness (`mode.js`, `e2e/run.mjs`) | **HALF ADOPTED** | `AUDIT.md` T5 — byte gate landed, override did not |
| 1 MiB / 256 KiB data-channel watermarks with hysteresis | **ALREADY HAVE** | `share/transport.rs:233-245` |
| 16 KiB chunks, chosen for the SCTP message ceiling | **ALREADY HAVE** | `share.rs:59` |
| Receiver-driven progress, resume, seek | **ALREADY HAVE** (better) | `share.rs:273` `next_request` |
| Time-limited TURN credentials, not static shared ones | **ALREADY HAVE** | COMMS-02, `deploy/coturn/turnserver.conf:24` |
| Event-driven pump instead of `await drain` in the send loop | **BETTER HERE** | `transport.rs:252` / `share_transfer.mjs:399` |
| Per-chunk index + length validation before the write | **BETTER HERE** | `share.rs:519-537` |
| Whole-file hash verified on arrival | **BETTER HERE** | `share.rs:544`, `FileTransfer.kt:564-575` |
| Never relay bulk through the operator's own TURN | **BETTER HERE** | `share/transport.rs` (no FileSync equivalent) |
| Three-tier sink: FS Access → Service Worker → Blob | **REJECTED** | §4.1 |
| WebSocket signaling server with peer registry | **REJECTED** | §4.2 |
| Rewriting private relay-candidate IPs at the signaling layer | **REJECTED** | §4.3 |
| Five-way outbound concurrency cap with a wait queue | **REJECTED** (for now) | §4.4 |

---

## 2. The four things worth taking

### 2.1 A re-delivered signal must not rebuild a live transfer — `AUDIT.md` Q18

FileSync's `downloadFile` opens with a guard and a comment naming both races it
survived (`web/js/modules/webrtc/user.js:384-386`):

```js
if (file.in_progress || this._downloadAll?.active) {
  if (window.showToast) window.showToast('This file is already downloading.', 'warning');
  return;
}
```

and its signaling client refuses a second offer for a connection id it already
has (`web/js/modules/webrtc/peer.js:531-534`), with the reason stated: *"a signal
queued during a WS reconnect gets replayed"*.

Comrade replays signals **by design**. `inbox_since` deliberately widens the
subscription floor back to the persisted watermark on every reconnect
(`crates/comrade_core/src/vault.rs:411-424`), precisely so a device that was
offline does not lose anything — which means recent gift-wraps arrive again.
Every other control path defends itself:

- call signals — an explicit event-id `SeenSet` (`runtime.rs:8998`);
- chat control envelopes — `get_message(&msg.event_id)` plus
  `is_cross_transport_duplicate` (`runtime.rs:7611`, `:8820`);
- `TogetherSignal::Start` — `starts_seen` (`runtime.rs:7951`);
- `TogetherSignal::Join` — idempotent on `session.joined` (`runtime.rs:8018`);
- `TogetherSignal::State` — a Lamport stamp, `wins_over` (`runtime.rs:8056`).

`TogetherSignal::Share` has none, and its comment says so without noticing:
*"Straight through to the frontend"* (`runtime.rs:8036-8048`). The dispatcher
above it claims *"Everything else about replay safety is inside
`handle_together_envelope`"* (`runtime.rs:9016`) — true of all four other
variants and false of this one.

That would be harmless if the frontend were idempotent. On the priority frontend
it was the opposite of idempotent (this is the code as found; it now consults
`ShareDecisions.decideArm` first):

```kotlin
fun armSend(offer: ShareOffer, openSource: () -> Source) {
    session = Session(Role.SENDER, offer, openSource, path = null)
}
```

`android/.../transfer/FileTransfer.kt:235-237`, and `armReceive` at `:246-264`
the same way — the live `Session` is dropped on the floor with its
`PeerConnection`, `DataChannel` and `PartialFileDataSource` still open. So a
`ShareSignal.Ask` re-delivered mid-transfer routes through
`ShareTransfer.onSignal` (`together/ShareTransfer.kt:115`) to `offerOurCopy` to
`armSend`, and **kills the transfer that was working** while leaking the native
objects behind it. `end()` exists and does exactly the right thing
(`FileTransfer.kt:350-364`); nothing calls it here.

The desktop implementation of the same protocol already gets this right —
`endShare(); clearHandoffCard();` before re-arming (`desktop/ui/main.js:5947-5948`, in `startHandoffSend`),
and `acceptHandoffOffer` refuses unless `card.phase === "offered"` (`:5874`).
The asymmetry is the finding: the frontend that must not be worse is.

Two independent fixes, and both are worth having rather than either alone —
a guard in core stops it for every frontend, a guard in `armSend`/`armReceive`
stops it for the one that ships:

1. give `Share` the same event-id `SeenSet` treatment call signals get;
2. make `armSend`/`armReceive` refuse, or `end()` first, when `session != null`.

### 2.2 One file handle for the receive, not one per 16 KiB — `AUDIT.md` P8

FileSync opens the destination once and holds the writable for the life of the
transfer (`web/js/modules/sink.js:171-187`), and serializes writes onto a
`_writeChain` because the handle locks (`file.js:50-51`).

Comrade's Android receiver reopened and re-truncated per chunk (as found):

```kotlin
RandomAccessFile(path, "rw").use {
    it.setLength(s.offer.totalBytes.toLong())
    it.seek(range.first)
    it.write(payload)
}
```

At `SHARE_CHUNK_BYTES = 16 KiB` a 700 MB film is ~44,800 open/`ftruncate`/`lseek`/
`close` cycles. (An earlier draft of this sentence said each close forced a flush.
That was wrong — `RandomAccessFile` has no user-space buffer and `close()` performs
no flush and no `fsync`. The syscalls were reason enough.) The correctness is
fine — the write-then-`accept` ordering that `:525-535` argues for at length is
right and must survive any change here — but the per-chunk cost is pure waste on
the one path that is by definition large.

Hold one `RandomAccessFile` on the `Session`, `setLength` once in `armReceive`
where the staging file is already created (`:246-264`), and close it in `end()`
next to the `streaming?.close()` that is already there.

### 2.3 A stalled transfer must end, not hang — `AUDIT.md` Q19

FileSync polls the ICE state every 500 ms per receiver, tolerates a transient
`disconnected` for 4 s, then marks the peer aborted and updates the UI
(`file.js:335-352`); and every teardown path sends `{type:'abort', reason}` to
the far side before closing (`file.js:420`), so nobody is left watching a bar
that will not move.

Comrade ends a transfer on exactly one condition —
`PeerConnectionState.FAILED` (`FileTransfer.kt:685-688`). That covers a dead
connection and nothing else. The sender's pump has five silent abandon paths on
a *live* connection (`FileTransfer.kt:437-452`): no source, `open()` threw,
`chunkRange` returned null, `read` threw — each `?: return`, no status, no
`ShareSignal.Refuse`, no log. And the receiver only re-asks from inside
`onChunk` (`:546`), so once chunks stop arriving nothing ever asks again.

A revoked `content://` permission on `ContentUriSource`
(`handoff/AttachmentHandoffManager.kt:255`), or removable storage disappearing
under `PathSource`, therefore produces two frozen progress bars and no error on
either device — the failure mode `AUDIT.md` Q1/Q5 already flag elsewhere in this
codebase, in a third place.

The cheap version is two changes: make the pump's abandon paths set `status` and
send `Refuse` instead of returning bare, and give the receiver a watchdog that
re-asks once and then gives up with a reason, on the model of `judgePath`'s
existing `RETRY_WHILE_UNSETTLED` loop (`:719-758`).

### 2.4 Force the path, then prove the bytes — `AUDIT.md` T5

FileSync's `mode.js` is 93 lines that let any deployment be loaded with
`?ice=stun` (drop TURN entries) or `?ice=turn`
(`iceTransportPolicy: 'relay'`) — with a comment recording which of the two
takes both sides to be meaningful, which is the sort of thing you only learn by
getting it wrong. Its `e2e/run.mjs` then drives a sender page and a receiver page
through a `{engine × sink × ice}` matrix and compares SHA-256 of source against
received.

Comrade has the decision layers tested well — `ShareDecisionsTest`,
`ShareReadPolicyTest`, `share_transfer.test.mjs`, `handoff_transfer.test.mjs`,
and `share/transport.rs`'s own 15 tests. What it has nowhere is a test that
**moves a byte**: nothing under `crates/*/tests/` so much as names `ShareSignal`
or `ShareOffer`, and `two_peer_integration.rs` covers the call path only.

The gap that matters most is a corollary. `RelayPolicy::DirectOnly` can be
proven by construction (`ice_servers_allowed` returns false, so no TURN is
offered), but `UnderBytes`, `AskEachTime` and `Always` all describe a transfer
*through* a relay — and there is no way to make ICE pick a relay when a direct
path exists, so **those three branches have almost certainly never carried a
byte.** `CallManager.testTurnConnectivity` already builds a RELAY-only
`PeerConnection` for calls (COMMS-02); the transfer path has no equivalent.

FileSync's harness itself is not the adoptable part — it is Playwright against
a web app. The adoptable parts are the two ideas underneath: a debug switch that
forces the path you want to exercise, and a gate that ends in "the hash of what
arrived equals the hash of what was sent".

---

## 3. Where Comrade is already ahead — do not regress toward FileSync

Worth writing down, because three of these look like features when read in
FileSync's source and are absences when read against this one.

**No integrity check on the wire, at all.** FileSync's `_onChunk` writes
whatever arrived at whatever length (`file.js:442-482`) and `_onEnd` closes the
sink. There is no chunk index, no length check, and no hash: a truncated or
reordered transfer produces a corrupt file that looks successful. Only the e2e
harness ever compares hashes, and only against a fixture it generated itself.
Comrade validates index-and-length per chunk before the write
(`share.rs:519-537`, enforced at `FileTransfer.kt:497-505`) and verifies the
whole-file SHA-256 on completion (`share.rs:544`, `FileTransfer.kt:564-575`),
with the comment that says exactly why both exist.

**`await` in the send loop.** FileSync's sender awaits `_awaitDrain(dc)` between
chunks (`file.js:243`), which is why that function had to grow `close` and
`error` listeners: without them, a receiver dropping mid-transfer with a full
buffer hangs the loop forever (`file.js:260-280` — the comment is the bug
report). Comrade's pump never awaits: it returns when the budget is zero and is
re-entered from `onBufferedAmountChange` (`FileTransfer.kt:392-397`,
`desktop/ui/share_transfer.mjs:399-427`). That class of hang is not reachable.

**Relay policy.** FileSync relays bulk through the operator's coturn without
comment; the README treats it as a feature (*"automatic STUN/TURN relay
fallback"*). Comrade refuses by default and enforces it twice — structurally, by
withholding TURN from the transfer connection, and again after connection by
reading the selected candidate pair (`share/transport.rs`, `FileTransfer.kt:716-759`).
`AUDIT.md` §8.2 is why, and the cost is stated rather than hidden.

**Ordering under a live reader.** `FileTransfer.kt:525-535` records a chunk only
*after* the bytes are on disk, because a player may be reading the partial file
on the decoder's thread. FileSync has no analogue because nothing reads a
FileSync download until it finishes.

---

## 4. Examined and deliberately not taken

### 4.1 The three-tier sink

`sink.js` is 358 lines choosing between the File System Access API, a Service
Worker streaming into an intercepted `/__download/{id}` fetch, and an in-memory
Blob — with `sw.js` and a hidden iframe to make the middle one work. It is
genuinely good, and it solves a problem Comrade's shipping frontends do not
have: Android writes with `RandomAccessFile`, and Tauri has Rust underneath the
webview.

It is tempting anyway, because **desktop does have the symptom**. `desktop/ui/`
buffers the whole file in `state.share.parts` and documents a 256 MiB ceiling
(`handoff_transfer.mjs:54-64`). But the cause is not the download mechanism — it
is that `crypto.subtle.digest` takes a buffer rather than a stream, so both ends
touch the whole file in memory once, and `AUDIT.md` S-4 forbids spooling the
plaintext to disk to get around it. A Service Worker would stream the *download*
and change nothing about the digest.

`handoff_transfer.mjs:35-37` already names the right exit condition — a
Rust-side streaming digest writing to a destination the person picked — and
that lifts the sender's half too, which no sink can. Importing a Service Worker
into a Tauri webview would add a second download path to work around a limit the
Rust side does not have. **Rejected**; the existing exit condition stands.

One thing there *is* worth borrowing if desktop's ceiling is ever lifted:
FileSync treats an `AbortError` from the save picker as "the user said no" and
propagates it rather than silently falling back to a different sink
(`sink.js:313-318`), and wires a browser-side download cancel back into a
transfer abort so the sender stops pumping into a dead stream
(`user.js:405-408`). Both are consent handling, not plumbing.

### 4.2 The signaling server

`api/signaling.py` is a careful 388 lines: peer registry with atomic takeover,
32 KiB frame cap, 100 msg/s per connection, a 50-per-10s per-(source,target)
sliding window against offer spam, a 10 s register timeout and a 30 s idle reap
against a 10 s client ping.

Comrade signals inside the `together` / `handoff` envelope over Nostr, which
already gives it the acceptance gate, the age gate and session scoping — the
argument is made at `share.rs:442-465` and it is the right one. Adding a
WebSocket broker would be adding a server to an architecture whose first
sentence is that there is not one. **Rejected.**

The per-pair rate limit is worth remembering as a *shape* if the mesh ever
carries signaling: "how many signals may one peer aim at one other peer in ten
seconds" is a question `saathi` will eventually have to answer.

### 4.3 Rewriting private relay-candidate IPs

`signaling.py:116-243` rewrites `typ relay` candidates whose address is private
or loopback to the host the *receiving* client used to reach the server —
per-recipient, so a LAN client and an Internet client each get an address that
works for them. Clever, and the comment correctly claims it beats a static
`--external-ip`.

It exists because FileSync runs coturn in a Docker bridge network, where it
advertises `172.x`. `deploy/coturn/docker-compose.yml:20` uses
`network_mode: host` — *"TURN needs the real relay port range reachable
directly"* — so the address coturn advertises is the real one and there is
nothing to rewrite. **Rejected**, and the reason is worth keeping next to that
line: changing it to bridge networking would silently reintroduce the problem
FileSync needed 130 lines to solve at the signaling layer, which Comrade has no
signaling layer to solve it at.

### 4.4 The outbound concurrency cap

`_OUTBOUND_CONCURRENCY_CAP = 5` with a queue and "you're waiting" feedback
(`user.js:20-43`), because FileSync is one-to-many and an unbounded fan-out
exhausts the sender's browser.

Comrade's transfer is one-to-one and structurally so: `@Volatile private var
session: Session?` holds exactly one (`FileTransfer.kt:199`). There is nothing
to cap. **Rejected for now** — and noted here because if group handoff is ever
built, the cap, the queue and *telling the queued receiver they are queued* are
the three parts, and the third is the one that gets forgotten.

### 4.5 Smaller things, checked and passed over

- **TURN credential caching with a 10 s safety margin** (`turn.js:34-63`) —
  Comrade mints time-limited REST credentials already (COMMS-02,
  `mint_turn_rest_credentials`); the client-side expiry cache is a browser
  concern.
- **Both UDP and TCP TURN URLs so a UDP-blocking network still relays**
  (`turn.js:21-31`) — worth a look when the coturn template is next touched, but
  it is a deployment note, not code; `turnserver.conf` already listens for both.
- **Suppressing Chromium's `sctp-failure` / cause-code 12 error on a normal
  remote close** (`peer.js:259-282`) — a browser wart. The Android WebRTC SDK
  does not surface it.
- **`client-zip` streaming multi-file download** (`user.js:567-730`) — Comrade
  transfers one file per session; a zip layer would be a feature, not an
  adaptation.

### 4.6 Holding the *sender's* handle open, which P8 also implies

Added 2026-08-08, after implementing P8.

FileSync holds one writable for the life of a receive, and the symmetric move on
the sender looks free — `ContentUriSource`'s own doc argues for it, since one
descriptor serving every positional read is what makes a `content://` handoff
possible without staging a second copy.

It was implemented and then reverted, because the sender has no completion event
to release on. The transfer is receiver-driven by design (`share.rs`'s whole
argument), so the sender never learns the receiver got the last chunk; `pump`
simply runs out of cursor. The only teardown is `end()`, which for a `together`
handover means the session ending and for an attachment handoff means the user
dismissing a card. So holding meant a `ParcelFileDescriptor` on another app's
provider — possibly a cloud or SAF provider — living for minutes after the bytes
finished moving.

The cost of not holding it is one `openFileDescriptor` per *drain event*, roughly
one per sixteen chunks at the high-water mark, not one per chunk. That is a fair
price. **P8 is a receiver finding and is implemented only there**, which is what
it says.

The alternative — a completion frame from receiver to sender — is a wire change,
and a cheap one: the request channel already carries `from:count` text frames and
an old sender parses an unknown frame as `toIntOrNull() ?: return`, so it would be
ignored rather than fatal. Worth doing if a second reason for it ever appears; not
worth a protocol addition on its own.

---

## 5. What this produced

Four `AUDIT.md` findings. Three are now struck; the fourth is half closed.

| ID | Sev | One line | State |
|---|---|---|---|
| Q18 | **H** | A re-delivered `ShareSignal` rebuilt the transfer session on Android, ending a live transfer and leaking its `PeerConnection`. | **fixed** — guarded in core and on Android, which have different reach |
| P8 | M | The Android receiver reopened and re-truncated the destination file once per 16 KiB chunk. | **fixed** on the receiver; the sender half was examined and rejected (§4.6) |
| Q19 | M | The sender's pump had five silent abandon paths and nothing detected a stalled transfer, so both ends froze. | **fixed**; the "tell the other side why" half became Q20 |
| T5 | M | Nothing tested that the bytes arrive; three of the four `RelayPolicy` branches cannot be exercised at all. | **half** — byte gate landed, forced-ICE override deliberately deferred |

## 6. What the implementing round turned up

Worth recording separately, because none of it came from FileSync — it came from
building the fixes and is the more useful half of the exercise.

**The first stall watchdog created the bug it was added to prevent.** One budget,
armed at data-channel open, with no notion of a transfer that has not been allowed
to start. Under `RelayPolicy::AskEachTime` the receiver gave up while the sender's
consent dialog was still on screen — and when it was the receiver's own policy
asking, its watchdog called `end()`, which nulls the consent question, so the
dialog vanished mid-decision. Waiting and holding are now different answers,
because waiting spends the budget and holding restarts it.

**P8 made a pre-existing race expensive.** Arming was check-then-act on an
unsynchronised field, which leaked nothing until the receiver started holding a
file handle — after which the loser of the race opened and sized a 700 MB file and
dropped the handle with no reference.

**The rebase produced a bug neither branch could see.** `docs/TOGETHER.md` §15
routes a transport signal by whether the file engine is armed — correct on its own
terms — and Q19's watchdog enlarged the window where it is not. A file offer
arriving after this side gave up would have started an unexpected audio and video
call. Fixed as a refinement of §15's rule; the mirror case, after a *successful*
transfer, is Q22 and is not fixed.

**And the honest note about the tests.** The decision layers are well covered and
the pure rules were confirmed failing against their predecessors. But nothing in
this repo executes `armSend`, `armReceive`, `claim`, `tearDown`, `endIfCurrent`,
`startStallWatch`, `pump` or `ShareTransfer.onSignal` — which is where every one of
these defects lived. Extracting the decisions into testable files is what made them
*arguable*; it is not what makes them *right*. That distinction was blurred once in
this work and should not be again.
