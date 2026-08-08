# Delivering a message with no internet

_Written 2026-07-29, when local-network delivery landed._

Comrade has four ways to get a DM to someone:

| # | Route | Reaches | Needs |
|---|---|---|---|
| 1 | **Nostr relay** (NIP-17/59 gift wrap) | anyone, anywhere | internet |
| 2 | **Local mesh** (`saathi` + sealed `dak` frame) | someone on the same WiFi | a shared network, no internet |
| 2b | **Bluetooth mesh** (`dak::ble` + the same sealed frame) | someone in radio range | nothing at all |
| 3 | **Sender outbox** (`dak::outbox`) | later, by any route above | nothing — it waits |

Routes 1 and 2/2b are tried in the order the user chose — see
[Precedence](#precedence) below; the default is relay first. Route 1 was all
there was until the outbox landed, and route 2 is what this document is about.
Route 3 is not a transport: it is the reason a message survives long enough for
1 or 2 to work.

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
select.

## Precedence

Which route is tried first has **two** inputs, in this order of authority.

**1. What is actually reachable.** A route that is down is not a route.
`SendPlan::for_attempt` takes a `TransportReach` — one live probe per transport
(`VaultEngine::has_connected_relay`, `MeshReach::can_deliver`) — and if exactly
one route is up, that one leads no matter what the user picked. This is not a
nicety: `send_dm_reply` waits up to `CONNECT_WAIT` (5s) for a relay before
publishing, so leading with a relay that is not there costs five seconds before
the local network is even tried. On a phone in airplane mode that is the whole
difference between a message arriving and a message sitting under a clock icon.

**2. What the user asked for**, from the app bar — two glyphs opposite the
navigation menu, the preferred route at full size and the fallback dimmed behind
it. Stored as the `OffGridTravel` workspace, because that is the API the
frontends already have. This decides only when *both* routes are up, which is
the only time it is a real choice rather than a way to make the app slower.

| Reachable | Leads | Then |
|---|---|---|
| relay only | relay | mesh, if no relay took it |
| local network only | mesh | relay, once patience runs out |
| both | the app-bar setting | the other one |
| neither | the app-bar setting | (it queues; nothing is reachable anyway) |

Three properties matter more than the ordering itself:

- **Precedence is an order, not an exclusion.** Whichever route leads, a message
  the preferred one cannot carry still takes the other. A dead mesh under local
  precedence falls straight through to a relay, and vice versa. Nothing the user
  can pick from the app bar is able to strand a message — which is why the
  switch needs no warning copy and no confirmation.
- **Whichever route led, patience runs out on it.** A relay `OK` means a relay
  has *stored* the message; a mesh publish only means *some* peer on the network
  took the frame, which may not be the recipient. So after
  `LOCAL_FIRST_PATIENCE` unacknowledged flush rounds (2, at roughly a minute
  each) a local-first message goes out over a relay as well, instead of waiting
  forever for someone to walk back into WiFi range. Note this arms off the route
  that *actually* led, not off the setting — otherwise a relay-preferring user
  whose relays are down would sit on the mesh indefinitely.
- **An attempt is a delivery that failed, not a minute that passed.** A flush
  that finds no route at all returns without spending one. `MAX_ATTEMPTS` is 8
  and the cadence is a minute, so before this an off-grid message was marked
  failed after about eight minutes — silently overriding the 24-hour `TTL_SECS`
  that is supposed to decide how long off-grid mail waits.

`runtime::tests::precedence_orders_the_transports_and_stops_waiting_after_two_rounds`,
`…::a_route_that_is_down_never_goes_first_whatever_the_user_picked`,
`…::patience_runs_out_on_whichever_route_led` and
`…::mail_with_nowhere_to_go_waits_for_the_ttl_instead_of_burning_the_attempt_cap`
are that policy as a table.

The user-facing ordering is duplicated in the two frontends
(`android/…/ui/TransportPrecedence.kt` and
`app/lib/src/util/transport_precedence.dart`) with matching tests on both sides,
so an inverted order fails a build rather than quietly routing a message down
the wrong radio.

## Why two phones on one WiFi did not work

Reported from hardware, and none of it was visible to the in-process test —
which is the lesson worth keeping. Four separate causes, each sufficient on its
own:

1. **No WiFi multicast lock on Android.** The manifest had carried
   `CHANGE_WIFI_MULTICAST_STATE` since the mesh landed and nothing ever took the
   lock it grants. Android's WiFi driver silently drops multicast frames not
   addressed to the device unless a `MulticastLock` is held — so mDNS announced
   outwards and *never heard anyone announce back*. A Linux CI host has no such
   filter, which is exactly why this survived a green build. `MeshRadio` now
   holds the lock while the mesh runs (and only while it runs — a held lock
   wakes the WiFi chip for every multicast frame on the network).
2. **The indicator counted sightings, not reachability.** `peer_count` was the
   set of mDNS-discovered peers, while sending depended on peers *subscribed to
   the sealed-mail topic*. Between those two moments — a dial, a Noise
   handshake, a subscription exchange — `gossipsub::publish` fails with
   `InsufficientPeers`. So the app could truthfully say "1 device nearby" and
   refuse every send, which is precisely what was reported. `MeshReach` now
   keeps `discovered` and `deliverable` apart, and the badge shows the second.
3. **mDNS re-queried every 5 minutes.** libp2p's default. A phone that joined
   the network a moment after the other one, or whose single startup
   announcement was dropped (ordinary for multicast), stayed invisible for up to
   five minutes. Now 20 seconds.
4. **Gossipsub's heartbeat was 10 seconds.** The heartbeat is what grafts a
   newly-subscribed peer into the mesh, so it was the floor on "the other phone
   appeared" → "a message will send". Now 1 second.

Two things also changed so the recovery is not something the user has to wait
out: the outbox is woken the instant the local network becomes deliverable
(rather than up to a minute later on the next tick), and presence beacons a
relay will not take are sealed onto the mesh — so the green dot means "reachable
now" rather than "was reachable within the last eight minutes", which is what
made the network look alive while nothing could be sent.

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

## Route 2b: Bluetooth, with no router at all

bitchat's actual mesh is **BLE** — phone-to-phone with no infrastructure of any
kind, which is the case that matters at a protest, in a blackout, or on a
mountain. Route 2 above cannot do that: it needs a shared IP network, so two
phones in a field are out of luck. Route 2b is BLE, and it is the reason the
sealed-frame abstraction was kept transport-agnostic while only one radio used
it.

Nothing in the `dak` layer changed to add it. `seal_dm`/`open_dm`, the envelope
and the outbox never knew what carried a frame, so BLE slotted in underneath
them exactly as designed.

### The split

| | owns | tested |
|---|---|---|
| `comrade_core::dak::ble` | framing, fragmentation, reassembly | yes, no radio needed |
| `comrade_ui`'s `BleRouter` | dedup, TTL, relaying, the two queues | yes |
| `android/…/ble/BleMeshService.kt` | GATT roles, advertising, scanning, MTU | **no — needs hardware** |

The seam between them is four calls (`ble_set_active`, `ble_set_mtu`,
`ble_deliver`, `ble_drain_outbound`). Everything that decides what goes on the
wire, what is forwarded, and what a stranger in radio range can make the process
allocate is on the Rust side, where it runs under `cargo test`. The radio half
is the part no CI can reach, so it is kept as thin as it can be.

### Fragmentation

A negotiated BLE ATT MTU gives roughly 180–500 usable bytes per write; a sealed
envelope runs to 16 KiB. Every envelope is therefore split across numbered
packets with a 14-byte header — `version ‖ packet_id ‖ ttl ‖ index ‖ count` —
and rebuilt on arrival. Gossipsub did this for us on WiFi; BLE has no such
layer.

The header carries **no identity**: a listener with a radio learns that *a*
device sent *some* frame in *n* pieces. Everything else is inside the sealed
envelope.

### Controlled flooding

Every device relays what it hears — that forwarding *is* the mesh, and a device
relays frames it can never open. Unmanaged, it is also a broadcast storm. Three
bounds, all bitchat's:

- **TTL** (7 hops), decremented on relay, dropped at zero.
- **Dedup** on `packet_id`, so a cycle in the peer graph cannot echo.
- **Duty-cycled scanning** rather than continuous, because a mesh that flattens
  the battery is a mesh nobody leaves on.

### Reassembly is the attack surface

It is the one place anybody in radio range can make this process allocate: send
a first fragment claiming a large `count` and never send the rest. So
`Reassembler` is bounded in both directions — concurrent partial packets, and
fragments per packet — and evicts by age. A rebuilt envelope is still
unauthenticated at that layer; the MAC inside `open_dm` is what decides whether
it is genuine, exactly as on WiFi.

### Where BLE sits in precedence

It is a *local* route, alongside the WiFi mesh — the user's app-bar choice is
"nearby before the internet", and that stays true whether nearby means this WiFi
or Bluetooth range. `LocalRadios::send` seals once and puts the same envelope on
**both** radios. One seal, two radios: sealing per-radio would put two different
ciphertexts for one message on the air and defeat the receiver's cross-transport
dedup.

Both, not WiFi-then-Bluetooth-if-that-fails. That was the original shape and it
was wrong in the way that matters, because `MeshLink::publish` returning `true`
does not mean the message arrived — it means gossipsub accepted the frame, which
requires only that *somebody* subscribes to the sealed topic, not the recipient.
So a phone with any mesh peer at all returned early and never touched Bluetooth.

Two ordinary situations made that fatal. **A hotspot with client isolation** —
the default on many Android hotspots — lets mDNS cross the access point while
blocking phone-to-phone traffic: peers are discovered, a publish is accepted, and
nothing is carried. And **any unrelated third device** on the network is enough
to make the publish succeed while the recipient is not there at all. In both,
Bluetooth would have worked and was never asked.

This is the same error the `peer_count` indicator made — treating an intermediate
success as delivery. The rule is: **only a receipt proves arrival.** Both radios
carry every frame, and the message stays queued until the recipient says
otherwise.

### Not done

iOS, and background execution beyond an unlocked foreground app. Android
throttles background BLE scans hard, and going further means a foreground
service with its own notification — deliberately not added here.

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

### The gate that matters: `tests/offline_delivery.rs`

Everything above tests an *end* — the protocol, or the wire format. This file
tests the **feature**: two whole `ComradeRuntime`s with no relay configured at
all, and a message that has to arrive. It exists because every test above was
green while a user reported that off-grid delivery did not work at all, twice
over, and it reproduced both failures on its first run.

Its own required CI job (**Offline delivery — no relay, no internet**) rather
than a line in the general `cargo test` lane, so a regression in a shipped
promise names itself instead of reading as "some Rust test broke".

- `a_message_reaches_the_other_phone_over_bluetooth_with_no_network` — the field
  report, start to finish: routing decision, seal, fragment, reassemble, ingress,
  stranger gate, event.
- `nothing_arrives_when_both_radios_are_off` — the negative control, and the
  reason the rest is worth anything. Without it, a harness that delivered by some
  other path would make every test above pass while the radio did nothing.
- `a_mesh_peer_who_is_not_the_recipient_does_not_swallow_the_message` — the
  regression test for the WiFi-first bug described under *Where BLE sits in
  precedence*.
- `a_message_written_out_of_range_arrives_once_the_radios_meet` — the outbox's
  actual job, and the case where a flush with no route used to spend one of eight
  attempts.
- `a_phone_in_the_middle_forwards_what_it_cannot_read` — relaying, which is what
  makes a mesh out of two links. This one found a second bug: the flood filter
  was keyed on `packet_id`, which every fragment of one envelope shares, so a
  relay forwarded fragment 0 and dropped the rest as echoes. One-hop delivery was
  unaffected — which is exactly why it survived — while anything crossing a
  middle device arrived permanently incomplete.
- `a_message_reaches_the_other_device_on_one_wifi_with_no_relay` — `#[ignore]`d,
  real mDNS, runs in the environment-dependent Saathi mesh job.

The Bluetooth tests need no radio, no socket and no multicast: an `Air` harness
hands packets between runtimes in-process, modelled as a graph because one BLE
write goes out on every link at once. So they gate, and a red build there is a
real regression rather than the runner's network.

- `saathi::tests::being_seen_on_the_network_is_not_the_same_as_being_reachable`
  — the `discovered` / `deliverable` table, which is the distinction the send
  path lives or dies on.
- `MeshRadioTest` (Android JVM) — the multicast lock must be a no-op rather than
  a crash when there is no context, because it hangs off the same path that
  carries every peer-count update the UI draws.
- `dak::ble::tests` — the packet wire format, fragmentation at every MTU,
  out-of-order and duplicate arrival, TTL exhaustion, and the hostile cases: a
  packet claiming more fragments than the cap, an index outside its count, and
  an attacker opening reassemblies faster than they finish.
- `runtime::tests::a_sealed_envelope_survives_fragmentation_and_reassembly`,
  `…::a_frame_for_someone_else_is_relayed_but_never_relayed_twice`,
  `…::with_only_bluetooth_a_dm_still_goes_out_and_stays_queued`, and
  `…::bluetooth_alone_makes_the_local_route_available`.

**Not covered by automated tests, and this is where the hardware bugs lived:**
two physical devices on one WiFi. The in-process test exercises the real
protocol stack over the loopback/LAN interface, and it passed happily through
every one of the four causes listed above — a Linux host does not filter
multicast, so the missing `MulticastLock` was invisible, and the test's own
retry loop rode out the `InsufficientPeers` window that on a phone showed up as
"it just doesn't send". A green mesh test is evidence about the protocol, not
about the platform.

Still unproven here: Android doze behaviour, and a given router's client
isolation. Client isolation (common on guest and hotel WiFi) blocks
peer-to-peer traffic outright and will defeat route 2 — the message stays
queued, which is the correct behaviour, but the user sees no delivery.

One deliberate limitation worth naming: **airplane mode with WiFi off is not a
network.** Route 2 needs a shared IP network, so two phones in airplane mode
must have WiFi (or a hotspot) re-enabled — which airplane mode allows, and which
Android does not do for you. With no IP network at all there is nothing for mDNS
to multicast onto, and mail correctly waits in the outbox.
