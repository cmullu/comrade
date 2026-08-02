# What Comrade took from bitchat — and what it deliberately did not

_Analysis date: 2026-07-28. Source: [permissionlesstech/bitchat](https://github.com/permissionlesstech/bitchat)
at `main` (whitepaper v2.0, 6 July 2026), ~75k lines of Swift across `bitchat/`,
`localPackages/BitFoundation`, and `docs/`._

bitchat is a decentralised messenger with two transports: a Bluetooth LE mesh
between nearby devices, and Nostr relays for distant peers. Comrade is a
wellbeing companion with a Nostr feed, E2E DMs, an encrypted journal, and an
experimental libp2p mesh. Different products — but bitchat has spent a long time
on one problem Comrade had barely started: **what happens to a message when
nobody is reachable**, and it has thought harder than almost any open codebase
about **what the radio and the disk give away**.

This document is the honest ledger: every mechanism examined, whether it landed,
and why. Verdicts are `ADOPTED` (implemented here), `ADAPTED` (same idea,
different mechanics because Comrade's stack differs), `DEFERRED` (worth having,
not in this change), or `REJECTED` (deliberately not wanted).

---

## 1. Store and forward — the headline adoption

bitchat's whitepaper §6 describes four layers for "the recipient is not here".
Comrade had exactly one of them (a relay holds a gift-wrapped DM until the
recipient reconnects) and none for the reverse case: **the sender** being
offline. Before this change, `send_dm` turned a relay publish failure into an
`Err` and the user's text was gone.

| bitchat mechanism | Verdict | Comrade implementation |
|---|---|---|
| Sender outbox: 100 msgs/peer, 24 h TTL, 8 attempts, cleared by delivery/read ack, sealed on disk (§6.1) | **ADOPTED** | `comrade_core::dak::outbox` + wiring in `comrade_ui::runtime` |
| Courier envelopes sealed to the recipient, carried by third parties (§6.2) | **ADOPTED** (engine) | `comrade_core::dak::courier` |
| Day-rotating recipient tags — `HMAC(recipient key, ctx‖UTC day)`, ±1 day candidates | **ADOPTED** | `dak::courier::recipient_tag` / `candidate_tags` |
| Trust tiers + quotas (favorites 5, verified 2, sub-pool 20 of 40, 16 KiB, 24 h) | **ADOPTED** | `dak::courier::CourierStore` |
| Spray-and-wait copy budget with binary split, non-replenishable on replay | **ADOPTED** | `CourierStore::spray_to` |
| Speculative multi-hop handover with a 10-minute cooldown | **ADOPTED** | `CourierStore::remote_handover` |
| Gossip sync of public history via GCS filters (§6.3) | **ADOPTED** (primitive) | `comrade_core::gcs` |
| Nostr relay mailboxes with 24 h reconnect lookback (§6.4) | **ALREADY HAD** | Vault backfill watermark |
| One-time prekeys for forward-secret courier seals | **DEFERRED** | See §6 |

**Where Comrade improves on the original.** bitchat needs a separate
ChaChaPoly seal and its own Keychain key for the outbox, because nothing else it
stores holds plaintext. Comrade's entire store is already Argon2id +
AES-256-GCM behind the passphrase, so the outbox snapshot is just another sealed
row — one at-rest scheme, one key, one wipe path.

**Sealing, translated.** bitchat seals courier mail with the one-way Noise `X`
pattern. Comrade has no Noise stack, so `dak::courier::seal` builds the same
shape from what `comrade_core::crypto` already has:

```text
sealed    := 0x01 ‖ ephemeral_xonly(32) ‖ AES-256-GCM(nonce ‖ ct+tag)
AEAD key  := HKDF(ECDH(ephemeral_sk, recipient_pk), "comrade-dak-seal-v1")
plaintext := sender_xonly(32) ‖ auth_mac(32) ‖ pad(payload)
auth_mac  := HMAC(HKDF(ECDH(sender_sk, recipient_pk), "comrade-dak-auth-v1"),
                  ephemeral_xonly ‖ payload)
```

The ephemeral key keeps the sender's identity off the wire entirely; the inner
MAC — keyed by the static-static shared secret — proves who wrote it. It is a
MAC rather than a signature on purpose: the recipient cannot use it to prove to
anyone else that you wrote something. Regression-tested for the wrong-recipient,
tampered-ciphertext, and forged-sender-claim cases.

**Same limitation, stated the same way.** No forward secrecy. Compromise of a
recipient's identity key exposes sealed-but-undelivered mail addressed to it —
identical to bitchat §5.2, and called out in the module docs rather than glossed.

---

## 2. Privacy engineering

| bitchat mechanism | Verdict | Notes |
|---|---|---|
| Panic wipe covering every persistent store | **ADOPTED** | `EncryptedStore::panic_wipe` + `ComradeRuntime::panic_wipe` |
| "New persistent stores must add a wipe hook and a regression test" (release checklist) | **ADAPTED — improved** | The wipe enumerates the database's *actual* tables, so a tree added later is covered by construction, not by remembering. The test asserts that property against an invented tree name. |
| Length padding to 256/512/1024/2048 buckets | **ADAPTED — improved** | `comrade_core::pad`. bitchat's single-byte pad length means any frame needing >255 bytes of padding ships **unpadded** (its own §9 lists this as future work). A two-byte trailer closes that; payloads above the top bucket round up to the next 2 KiB. |
| Padding applied only to Noise frames | **N/A** | Comrade's DMs are NIP-44, which already pads its plaintext. Padding here applies to courier envelopes and mesh frames — everything Comrade seals itself. Double-padding DMs would only add bytes. |
| Randomised envelope timestamps (±15 min) | **ADAPTED** | Anonymous Chitthis carry an hour-truncated `created_at` (`sabha::coarse_timestamp`). Truncation over jitter: every post in an hour shares one value, and it can never land in the future, which relays reject. Comrade's DMs already get NIP-59's ±2-day randomisation. |
| Privacy-safe local counters — no ids, peers, content, or timestamps | **ADOPTED** | `comrade_core::metrics`. A closed enum of static keys, so a caller *cannot* attach a label by accident. Satisfies the observability standard without building a diary of who talked to whom. |
| `SecureLogger` with OSLog privacy markers | **PARTIALLY ADOPTED** | No logging-layer redaction pass here yet, but the new types refuse to leak: `Debug` for `Outbox` prints shape only, `DeviceSeed` prints `<redacted>`, and `seen::content_key` returns a hash. Regression-tested. A repo-wide `tracing` audit is a separate change. |
| Hidden notification previews, app-switcher snapshot cover, screenshot detection | **DEFERRED** | Platform work in `android/`, not the Rust core. Genuinely valuable for the same threat model. |
| Honest "no duress mechanism" stance | **ADOPTED as policy** | `panic_wipe`'s docs say plainly that it wipes rather than hides and needs the app open. bitchat's reasoning is worth repeating: in some jurisdictions destroying data on demand is itself an offence, so a mode that hides may protect someone better than one that destroys. |
| Documented privacy assessment as a living artifact | **ADOPTED in spirit** | This document plus the module-level honesty notes. A full `docs/PRIVACY.md` in bitchat's shape is a good follow-up. |

---

## 3. Anonymity — the gap this closes

`AUDIT.md` §8 has said for months that "anonymous thoughts" is a false promise
today: a public Chitthi is signed by the identity key, so it is *pseudonymous*.
The fix it asked for — per-post ephemeral keys — is precisely bitchat's
per-geohash identity derivation, generalised.

| bitchat mechanism | Verdict | Comrade implementation |
|---|---|---|
| Device seed in the Keychain; `HMAC-SHA256(seed, label)` → per-scope secp256k1 key, rehashed until valid | **ADOPTED** | `comrade_core::anon::{DeviceSeed, derive_scoped}` |
| Distinct label prefixes so two kinds of scope cannot collide (`"bridge\|"`) | **ADOPTED** | `SCOPE_GEOHASH` / `SCOPE_CHITTHI` |
| Derived-identity cache cleared on panic wipe, so post-wipe posts are not signed with pre-wipe keys | **ADOPTED** | Seed lives in the wiped store; `DeviceSeed` is `ZeroizeOnDrop` |
| — (bitchat has no per-post key) | **NEW** | `anon::ephemeral()` for a throwaway key per post: two anonymous Chitthis are unlinkable *to each other*, which a per-scope key by design is not |

Both shapes are exposed as `ComradeRuntime::broadcast_anonymous_chitthi(content,
scope)`: `None` for a throwaway key, `Some(label)` for a stable persona whose
replies reach the same pseudonym.

**What this does not buy**, stated as bitchat states it: the signing key is
unlinkable, the network is not. Timing, relay choice, and IP address still
identify the device unless traffic goes through a proxy.

---

## 4. Location channels

bitchat's most-used social feature is geohash-scoped public rooms — "who else is
around here" — with a privacy rulebook (`docs/GeohashPresenceSpec.md`) that is
the interesting part.

| bitchat mechanism | Verdict | Comrade implementation |
|---|---|---|
| Base32 geohash encode/decode, 1–12 chars | **ADOPTED** | `comrade_core::geo` |
| Named precisions (region/province/city/neighborhood/block/building) | **ADOPTED** | `geo::Precision` |
| Presence heartbeats **only** at coarse precisions (≤ city) | **ADOPTED** | `Precision::presence_allowed`, enforced in `SabhaEngine::publish_geohash_presence` |
| Kind 20000 chat / 20001 presence, `g` tag, 5-minute online window | **ADOPTED** | `sabha::KIND_GEOHASH_*`, `geo::PresenceTracker` |
| "`? people`" for a fine-precision channel with zero sightings | **ADOPTED** | `geo::Presence::Unknown` — a zero count where nobody announces is a lie, and the type makes it unrepresentable |
| Randomised 40–80 s heartbeat with 2–5 s per-channel decorrelation delays | **DEFERRED** | Needs the scheduler a UI surface will bring; the publish/subscribe primitives are in place |
| CoreLocation sampling, reverse geocoding for friendly names | **REJECTED** | Platform-specific, and reverse geocoding sends coordinates to a third party — bitchat's own assessment admits it is "not accurately described as wholly on-device" |

---

## 5. Ingress hygiene

| bitchat mechanism | Verdict | Notes |
|---|---|---|
| LRU dedup cache with TTL expiry (1000 entries, 5 min on the mesh) | **ADOPTED** | `comrade_core::seen::SeenSet`, which replaced the hand-rolled call-signal dedup in `comrade_ui` — one implementation, tested once |
| Content normaliser for near-duplicates (case-fold, strip URL query/fragment, collapse whitespace, prefix, hash) | **ADOPTED** | `seen::content_key`, for the same message arriving over two transports with different ids |
| Cross-launch persistence of processed event ids | **ADOPTED as capability** | `SeenSet::snapshot`/`restore`; Comrade's DM path already dedups against the store by event id, so this is for the mesh and feed paths |
| Timestamp validation (±2 min) with a solicited-sync exemption (`RequestSyncManager`) | **DEFERRED** | Belongs with the mesh sync protocol that `gcs` enables |
| Per-peer sync response rate limiting (8 per 30 s) | **DEFERRED** | Same |

---

## 6. Examined and deliberately not taken

**Update (2026-07-29): the LAN half of the mesh is now wired.** The §1 table's
courier row said the sealed envelope had no transport driving it. It has one now
— `saathi` carries `dak` frames over mDNS + Gossipsub, so two devices on one
WiFi exchange DMs with no relay and no internet. `docs/OFFLINE_DELIVERY.md` is
the design. This does *not* change the verdict below on the BLE radio itself:
what landed reuses Gossipsub for flooding on an IP network, and phone-to-phone
Bluetooth with no router remains unbuilt.

**BLE mesh transport, and its flood-control tuning** (TTL 7 with degree-based
clamping, 10–220 ms relay jitter, message-ID-seeded fanout subsetting, split
horizon, source routing with confirmed-edge BFS, ~469-byte fragmentation).
`REJECTED` — not because it is bad; it is the most carefully tuned part of
bitchat. But Comrade's mesh is libp2p Gossipsub over mDNS/TCP, which already
does mesh construction, dedup, and fanout control at the protocol layer.
Porting these constants would mean reimplementing a transport Comrade does not
have. The pieces that transcend the transport — dedup, store-and-forward,
padding, set reconciliation — are the ones taken.

**Noise XX live sessions.** `REJECTED` for now. Noise XX gives forward secrecy
per session, which NIP-44 does not. That is a real gap, but swapping Comrade's
DM crypto is a protocol migration (interop with every other Nostr client), not
an adoption. The honest note is that Comrade's DMs have the same
no-forward-secrecy property as bitchat's Nostr path.

**One-time prekeys** (bitchat's `LocalPrekeyStore` / `PrekeyBundleStore`,
gossip-synced bundles, `prekeyID` in the envelope). `DEFERRED` — the right fix
for courier forward secrecy, and a self-contained follow-up now that the
envelope carries a version byte and the courier store exists.

**Proprietary Nostr private envelopes** (kinds 13/14/1059 with a `v2:`
XChaCha20-Poly1305 format that reuses NIP-17/59 kind numbers but is
deliberately not NIP-44/59 compatible). `REJECTED`. Comrade went the other way
on purpose — real NIP-44 + NIP-17/59 gift wrap, so it interoperates with other
Nostr clients. bitchat's own docs say theirs "interoperates only with BitChat
clients"; adopting it would trade interop for nothing Comrade needs.

**Bridge gateways, board posts, groups, Cashu tokens, Tor/Arti integration,
push-to-talk voice bursts.** `DEFERRED`/out of scope — each is a feature in its
own right rather than a mechanism to borrow. Tor is the most interesting for
Comrade's threat model (it is the missing half of the anonymity story in §3) and
is tracked as its own question.

**8-byte peer IDs derived from a never-rotating static key.** `REJECTED`, and
worth noting as the thing bitchat itself flags hardest: its whitepaper §8 says
"metadata is the weakest part of this design, and the peer ID does not help",
because a passive listener can enumerate participants and follow a device
between places. Comrade should not import that shape into its mesh. The
anonymity primitives in §3 are the alternative direction — scoped keys per
context rather than one stable on-air identity.

---

## 7. What landed in this change

| Module | Lines of intent |
|---|---|
| `comrade_core::dak::outbox` | Sender outbox policy: per-peer caps, TTL, attempt cap, peer-scoped acks, id re-keying, snapshot/restore |
| `comrade_core::dak::courier` | Sealed envelopes, rotating tags, trust-tier quotas, spray-and-wait, handover cooldown, snapshot/restore |
| `comrade_core::gcs` | Golomb-coded set filters (`build`/`decode`/`missing_from`), byte-budget trimming with an exact newest-prefix guarantee |
| `comrade_core::anon` | Device seed, scoped personas, per-post ephemeral keys |
| `comrade_core::geo` | Geohash arithmetic, precision policy, presence tracking with honest unknown counts |
| `comrade_core::pad` | Bucket padding with a two-byte trailer |
| `comrade_core::seen` | Bounded dedup set with optional TTL + content normaliser |
| `comrade_core::metrics` | Device-local, identity-free counters |
| `comrade_core::sabha` | Anonymous Chitthi publishing, geohash channel publish/subscribe/presence |
| `comrade_storage` | `panic_wipe`, `destroy`, `tree_names`, `remove_message`, `queued`/`failed` message states |
| `comrade_ui::runtime` | Outbox wiring (queue on failure, periodic flush, receipt acks, status events), `panic_wipe`, `metrics_snapshot`, anonymous Chitthi |

**Not yet wired to a screen.** The courier store, the GCS filters, the geohash
channels, and the metrics snapshot are engine-level with unit tests; no Android
or desktop surface drives them yet. Per this repo's Theme 1 discipline, they get
🧪 in the README, not a checkmark. The outbox, the panic wipe, and anonymous
posting are reachable through `ComradeRuntime` today.
