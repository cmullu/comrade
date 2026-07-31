# Delivering a message with no internet

_Written 2026-07-29, when local-network delivery landed._

Comrade has three ways to get a DM to someone, tried in this order:

| # | Route | Reaches | Needs |
|---|---|---|---|
| 1 | **Nostr relay** (NIP-17/59 gift wrap) | anyone, anywhere | internet |
| 2 | **Local mesh** (`saathi` + sealed `dak` frame) | someone on the same WiFi | a shared network, no internet |
| 3 | **Sender outbox** (`dak::outbox`) | later, by route 1 or 2 | nothing — it waits |

Route 1 was all there was until the outbox landed, and route 2 is what this
document is about. Route 3 is not a transport: it is the reason a message
survives long enough for 1 or 2 to work.

## What was broken

The mesh engine (`comrade_core::saathi`: libp2p mDNS discovery + Gossipsub) had
existed for a long time and carried **nothing**. Concretely, before this change:

- it only ran when the user switched to the `OffGridTravel` workspace, and
- nothing anywhere called `broadcast()` or `recv_message()` — a workspace-wide
  grep found zero callers outside the engine's own tests. The runtime used the
  engine purely as a **peer counter** for the connectivity indicator.

So "offline messages over the same network" had never worked. It was not a
regression; the wire was never connected. The README said as much (🧪 *engine +
tests only, not started by any frontend*), which is easy to miss when the app
shows a mesh indicator with a live peer count.

## How route 2 works

The payoff of the courier work: a `dak::Envelope` is already a self-contained
sealed unit whose only routing datum is a day-rotating tag. Putting one inside a
Gossipsub frame gives bitchat's mesh property on a different radio.

```text
sender                                             recipient
  │  seal_dm(recipient_pubkey, our_keys, MeshDm)        │
  │      → Envelope { tag = HMAC(their key, day),       │
  │                   ciphertext = sealed MeshDm }      │
  │                                                     │
  ├── gossipsub publish ── comrade/saathi/dak/v1 ───────┤  every LAN peer
  │                                                     │  receives it
  │                                              open_dm(our_keys, frame)
  │                                                 ├─ tag not ours → skip
  │                                                 └─ tag ours → decrypt,
  │                                                    verify inner MAC,
  │                                                    then the *same*
  │                                                    ingress as a relay DM
  │                                                     │
  │◄──────────── delivered receipt (relay, else mesh) ──┤
```

Properties, and where each comes from:

- **Everyone sees it, only one person can read it.** Gossipsub floods; the seal
  restricts. A bystander on the café WiFi gets an opaque blob
  (`dak::mesh` tests assert this, including that neither party's public key nor
  the message text appears in the frame).
- **No sender identity on the wire.** The frame is sealed to an ephemeral key;
  the sender is named *inside* the ciphertext and proven by an inner MAC keyed
  on the static–static shared secret.
- **Length reveals only a bucket** — the payload is padded before sealing.
- **One ingress path.** An opened frame is turned into the same `VaultMessage`
  a relay would have produced and fed through `dispatch_incoming_dm`, so
  message-request gating, persistence, dedup, receipts, and `/pay` detection
  behave identically however the message arrived. This is the single most
  important design choice here: a second ingress path would have meant a second
  set of privacy rules to keep in sync.
- **Receipts can come back the same way.** A delivered receipt tries the relay
  first and falls back to the mesh. Without that, a message delivered over the
  mesh would keep being retried until it hit the outbox attempt cap, because a
  receipt is what clears the queue.

The mesh now runs whenever the vault is unlocked, not only in `OffGridTravel`.
"The person I'm messaging is on this WiFi" is not a mode a user should have to
select — and `OffGridTravel` keeps its own meaning (the mesh *replaces* relays).

## Cross-transport duplicates

A message can legitimately arrive twice: sealed over the mesh now, and over a
relay when the internet returns. The two copies carry **different ids** — the
mesh copy is keyed by the sender's locally minted id, the relay copy by the
event id a relay assigned — so id-based dedup cannot catch the pair.

The fix is content-based dedup (bitchat's `ContentNormalizer`, ported as
`seen::content_key`) with one refinement: the key includes the **transport**.

- Second copy over the *other* route within 2 minutes → dropped.
- Second copy over the *same* route → kept.

That distinction matters, because "ok" typed twice in a minute is two messages,
and both copies of it come over the same route. A transport-blind content cache
would eat the repeat. The window is deliberately short for the same reason.

## What this is not: Bluetooth

bitchat's actual mesh is **BLE** — phone-to-phone with no infrastructure at
all, which is the case that matters at a protest, in a blackout, or on a
mountain. This change does **not** provide that. It needs a shared IP network:
a WiFi access point, a hotspot, or an ad-hoc network. Two phones in a field with
no router cannot use it.

Building the BLE path means, roughly:

1. a Kotlin/Android BLE layer where each device is simultaneously GATT central
   and peripheral (bitchat's `BLEService.swift` is ~8k lines for the iOS side),
2. a framed binary packet format with fragmentation to ~500-byte MTUs,
3. controlled flooding — TTL, deduplication, relay jitter, fanout subsetting —
   which is exactly the part `docs/BITCHAT_ADOPTION.md` declined on the grounds
   that Gossipsub already did it *for the LAN case*, and
4. platform permission and background-execution work on both Android and iOS.

The `dak` layer above it would not change: `seal_dm`/`open_dm` and the outbox
are transport-agnostic on purpose, so BLE would be a new route 2b rather than a
new messaging stack. That is the reason to keep the sealed-frame abstraction
even though only one radio uses it today.

## Testing

- `saathi::tests::two_devices_on_one_network_exchange_a_sealed_dm` — three real
  engines, real mDNS discovery, real Gossipsub, in one process: Alice seals to
  Bob, Bob opens it, Eve cannot. The closest a unit test gets to two phones on a
  café network.
- `saathi::tests::publishing_sealed_mail_with_nobody_around_fails_rather_than_silently_vanishing`
  — an empty mesh must report failure, so the outbox keeps the message.
- `dak::mesh::tests` — seal/open round trip, bystander rejection, no plaintext
  or keys on the wire, midnight tag rollover, a control envelope (receipt)
  riding the mesh, and a forged sender failing authentication.
- `runtime::tests::the_mesh_comes_up_on_unlock_without_choosing_a_workspace`,
  `a_dm_with_no_relay_is_sealed_onto_the_mesh_and_stays_queued`,
  `one_message_delivered_by_both_routes_appears_once`, and
  `the_same_text_sent_twice_over_one_route_is_two_messages`.

**Not covered by automated tests:** two physical devices on one WiFi. The
in-process test exercises the real protocol stack over the loopback/LAN
interface, but it cannot prove that Android's multicast behaviour, doze mode, or
a given router's client isolation will cooperate. Client isolation (common on
guest and hotel WiFi) blocks peer-to-peer traffic outright and will defeat route
2 — the message stays queued, which is the correct behaviour, but the user sees
no delivery. That needs a real two-phone test.
