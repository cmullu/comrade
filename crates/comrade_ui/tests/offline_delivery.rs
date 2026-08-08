//! **Does a message actually reach the other person with no internet at all?**
//!
//! This is the question the whole off-grid path exists to answer, and until
//! this file there was no test that asked it. The gap was specific and it cost
//! a shipped bug: `saathi.rs`'s mesh tests prove the *protocol* (two
//! `SaathiEngine`s exchange a sealed frame), and `dak/ble.rs`'s tests prove the
//! *wire format* (fragment, reassemble, dedup) — but nothing exercised the
//! path a person actually uses, which is `send_dm` on one runtime and a
//! `BridgeEvent` on another with no relay in between.
//!
//! Everything between those two proven ends was untested: the routing decision
//! in `SendPlan`, `LocalRadios` sealing once for both radios, the ingress that
//! opens a frame and applies the message-request gate, the receipt that clears
//! the sender's queued status, and the outbox retry that is supposed to carry a
//! message until a radio can take it. A green build meant "the protocol works",
//! never "the feature works".
//!
//! ## Two transports, two levels of determinism
//!
//! The Bluetooth tests are **fully deterministic** and need no radio, no
//! network and no multicast: [`Air`] moves packets between the two runtimes by
//! hand, exactly as two BLE stacks in range of each other would. They belong in
//! the ordinary test lane and must never flake.
//!
//! The WiFi test uses **real mDNS on the host's loopback**, so it is
//! environment-dependent in the same way `saathi.rs`'s mesh tests are, and runs
//! in the same dedicated CI job (`Rust — Saathi mesh`) rather than the main
//! lane. It is marked `#[ignore]` so a plain `cargo test` stays hermetic.

use std::time::Duration;

use comrade_ui::{BridgeEvent, ComradeRuntime};
use tempfile::TempDir;

/// Long enough to absorb CI scheduling jitter on a shared runner, short enough
/// that a genuine failure to deliver does not hold the suite for a minute.
const RECV_TIMEOUT: Duration = Duration::from_secs(10);

/// How long a "nothing arrived" assertion waits before concluding that. A
/// negative assertion only has to outlast the delivery path it is denying, and
/// the BLE path here has no network in it at all.
const ABSENCE_TIMEOUT: Duration = Duration::from_millis(600);

/// How long the WiFi test gives real mDNS plus a gossipsub graft. Matches
/// `saathi::tests::MESH_FORMATION_TIMEOUT` and is generous for the same reason:
/// the wait covers a multicast round trip, a dial, a Noise handshake and a
/// subscription exchange, on whatever loaded runner happens to be free.
const MESH_FORMATION_TIMEOUT: Duration = Duration::from_secs(45);

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// A runtime with **no relays configured at all** — the point of this file.
///
/// `with_relays(vec![])` is not "a relay that happens to be down": there is no
/// URL to dial, so every `send_dm` takes the offline path on its first attempt
/// rather than after a connect timeout. That is airplane mode, or a hotspot
/// with no upstream, reproduced exactly.
async fn offline_runtime(dir: &TempDir) -> ComradeRuntime {
    let mut rt = ComradeRuntime::with_relays(vec![]);
    rt.unlock_vault(dir.path(), "pin").await.unwrap();
    rt.spawn_event_loops();
    rt
}

/// The air between phones: a set of radios and which of them are in range of
/// which.
///
/// Modelled as a graph rather than a pair because one BLE write goes out on
/// **every** link at once. A first draft used one `Air` per pair, and the
/// middle phone in the relay test below silently lost traffic: whichever pair
/// drained first consumed the queue, so the second neighbour got nothing. That
/// is not how a radio behaves, and a harness that gets it wrong tests the
/// harness.
///
/// Deliberately dumb otherwise. Every decision about what to send, what to
/// forward and what to drop stays in the code under test, so a pass here is
/// evidence about Comrade rather than about this file.
struct Air<'a> {
    nodes: Vec<&'a ComradeRuntime>,
    /// Bidirectional, by index into [`Self::nodes`].
    links: Vec<(usize, usize)>,
}

impl<'a> Air<'a> {
    /// Two phones in range of each other.
    fn pair(left: &'a ComradeRuntime, right: &'a ComradeRuntime) -> Self {
        Self {
            nodes: vec![left, right],
            links: vec![(0, 1)],
        }
    }

    /// A line: each phone hears only its neighbours. The topology that makes
    /// relaying the *only* way across.
    fn chain(nodes: Vec<&'a ComradeRuntime>) -> Self {
        let links = (1..nodes.len()).map(|i| (i - 1, i)).collect();
        Self { nodes, links }
    }

    /// Drain every radio once, then hand each packet to everyone in range.
    ///
    /// Draining all nodes *before* delivering any is what keeps a packet from
    /// racing ahead more than one hop per pump, so a multi-hop test measures
    /// hops rather than iteration order.
    fn pump_once(&self) -> usize {
        let now = now_secs();
        let outgoing: Vec<Vec<Vec<u8>>> = self
            .nodes
            .iter()
            .map(|n| n.ble_router().drain_outbound())
            .collect();

        let mut moved = 0;
        for (from, packets) in outgoing.iter().enumerate() {
            for &(a, b) in &self.links {
                let to = match (a, b) {
                    (x, y) if x == from => y,
                    (x, y) if y == from => x,
                    _ => continue,
                };
                for packet in packets {
                    self.nodes[to].ble_router().deliver(packet, now);
                    moved += 1;
                }
            }
        }
        moved
    }
}

/// Drain events until one matches, skipping unrelated traffic exactly as the
/// real frontend event pumps do.
async fn wait_for(
    rx: &mut tokio::sync::broadcast::Receiver<BridgeEvent>,
    timeout: Duration,
    pred: impl Fn(&BridgeEvent) -> bool,
) -> Option<BridgeEvent> {
    let deadline = tokio::time::Instant::now() + timeout;
    loop {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            return None;
        }
        match tokio::time::timeout(remaining, rx.recv()).await {
            Ok(Ok(event)) if pred(&event) => return Some(event),
            Ok(Ok(_)) => continue,
            Ok(Err(_)) | Err(_) => return None,
        }
    }
}

/// Same, but drives the air while it waits — the BLE equivalent of the above,
/// since nothing arrives unless somebody carries the packets.
async fn wait_for_over_air(
    air: &Air<'_>,
    rx: &mut tokio::sync::broadcast::Receiver<BridgeEvent>,
    timeout: Duration,
    pred: impl Fn(&BridgeEvent) -> bool,
) -> Option<BridgeEvent> {
    let deadline = tokio::time::Instant::now() + timeout;
    loop {
        air.pump_once();
        match tokio::time::timeout(Duration::from_millis(20), rx.recv()).await {
            Ok(Ok(event)) if pred(&event) => return Some(event),
            Ok(Ok(_)) => continue,
            Ok(Err(_)) => return None,
            Err(_) => {
                if tokio::time::Instant::now() >= deadline {
                    return None;
                }
            }
        }
    }
}

// ── Bluetooth: two phones in a field, no network of any kind ────────────────

/// The headline case, and the one the field report was about: two devices in
/// Bluetooth range, no relay, no WiFi, no internet — and a message arrives.
///
/// Asserts the *whole* path, because every piece of it was previously untested
/// in combination: the routing decision picks the local radio, `LocalRadios`
/// seals the frame, the fragmenter cuts it up, the reassembler puts it back
/// together, the ingress opens it and applies the stranger gate, and the
/// recipient's frontend sees an event.
#[tokio::test]
async fn a_message_reaches_the_other_phone_over_bluetooth_with_no_network() {
    let alice_dir = TempDir::new().unwrap();
    let bob_dir = TempDir::new().unwrap();
    let alice = offline_runtime(&alice_dir).await;
    let bob = offline_runtime(&bob_dir).await;

    // Both radios up and in range. `set_active(true)` is what the platform BLE
    // service calls when it has a linked peer.
    alice.ble_router().set_active(true);
    bob.ble_router().set_active(true);
    let air = Air::pair(&alice, &bob);

    let bob_npub = bob.profile().unwrap().npub;
    let alice_npub = alice.profile().unwrap().npub;
    let mut bob_events = bob.subscribe_events();

    alice
        .send_dm(&bob_npub, "there is no internet here")
        .await
        .unwrap();

    // A stranger's first message is a request, offline exactly as online. The
    // gate is a property of the message, not of the transport that carried it —
    // if Bluetooth could bypass it, radio range would be an authentication
    // bypass.
    let request = wait_for_over_air(&air, &mut bob_events, RECV_TIMEOUT, |e| {
        matches!(e, BridgeEvent::IncomingMessageRequest(_))
    })
    .await
    .expect("alice's message must reach bob over Bluetooth with no network at all");
    let BridgeEvent::IncomingMessageRequest(req) = request else {
        unreachable!()
    };
    assert_eq!(
        req.peer, alice_npub,
        "the request must be attributed to alice"
    );

    bob.accept_request(&alice_npub).unwrap();
    alice
        .send_dm(&bob_npub, "and yet here we are")
        .await
        .unwrap();

    let delivered = wait_for_over_air(&air, &mut bob_events, RECV_TIMEOUT, |e| {
        matches!(e, BridgeEvent::IncomingDirectMessage(_))
    })
    .await
    .expect("an accepted peer's message must arrive over Bluetooth");
    let BridgeEvent::IncomingDirectMessage(dm) = delivered else {
        unreachable!()
    };
    assert_eq!(dm.content, "and yet here we are");
}

/// The negative control, and the reason the test above is worth anything.
///
/// With the radios down nothing must arrive. Without this, a harness bug that
/// delivered messages by some other route — a shared store, an event bus that
/// spans both runtimes — would make the delivery test pass while the radio did
/// nothing, which is precisely the class of false green that let the original
/// bug ship.
#[tokio::test]
async fn nothing_arrives_when_both_radios_are_off() {
    let alice_dir = TempDir::new().unwrap();
    let bob_dir = TempDir::new().unwrap();
    let alice = offline_runtime(&alice_dir).await;
    let bob = offline_runtime(&bob_dir).await;

    // Deliberately *not* activated.
    let air = Air::pair(&alice, &bob);
    let bob_npub = bob.profile().unwrap().npub;
    let mut bob_events = bob.subscribe_events();

    alice.send_dm(&bob_npub, "into the void").await.unwrap();

    let arrived = wait_for_over_air(&air, &mut bob_events, ABSENCE_TIMEOUT, |e| {
        matches!(
            e,
            BridgeEvent::IncomingMessageRequest(_) | BridgeEvent::IncomingDirectMessage(_)
        )
    })
    .await;
    assert!(
        arrived.is_none(),
        "nothing may reach bob with both radios off — if this fails the delivery \
         test above is proving something other than Bluetooth"
    );
}

/// A message written before the other phone is in range must still arrive once
/// it is.
///
/// This is the outbox's whole job and the case a person actually hits: you type
/// something, put the phone away, and walk into range later. It is also where
/// the attempt-counting bug lived — a flush with no route used to spend one of
/// eight attempts, so a message could exhaust its retries while the radio had
/// never once had somebody to send to.
#[tokio::test]
async fn a_message_written_out_of_range_arrives_once_the_radios_meet() {
    let alice_dir = TempDir::new().unwrap();
    let bob_dir = TempDir::new().unwrap();
    let alice = offline_runtime(&alice_dir).await;
    let bob = offline_runtime(&bob_dir).await;
    let bob_npub = bob.profile().unwrap().npub;
    let alice_npub = alice.profile().unwrap().npub;
    let mut bob_events = bob.subscribe_events();

    // Nobody in range: no relay, no radio. The message has nowhere to go.
    alice
        .send_dm(&bob_npub, "written in a tunnel")
        .await
        .unwrap();

    // Flushing repeatedly with no route must not burn the message. Eight is
    // `MAX_ATTEMPTS`, so a flush that counted an attempt here would mark it
    // failed before the radios ever met.
    for _ in 0..10 {
        let _ = alice.flush_outbox().await;
    }

    // Now they meet.
    alice.ble_router().set_active(true);
    bob.ble_router().set_active(true);
    let air = Air::pair(&alice, &bob);
    let _ = alice.flush_outbox().await;

    let request = wait_for_over_air(&air, &mut bob_events, RECV_TIMEOUT, |e| {
        matches!(e, BridgeEvent::IncomingMessageRequest(_))
    })
    .await
    .expect(
        "a message queued out of range must still arrive once a radio has somebody \
         to send to — if this fails, the retry budget was spent while there was no route",
    );
    let BridgeEvent::IncomingMessageRequest(req) = request else {
        unreachable!()
    };
    assert_eq!(req.peer, alice_npub);
}

/// A frame nobody can open must still be forwarded.
///
/// Relaying is what makes a mesh out of a set of links: the phone in the middle
/// of a field carries traffic for two people it cannot read. Tested here rather
/// than only in `dak::ble` because the decision to forward belongs to the
/// router, and a router that quietly dropped unreadable frames would still pass
/// every direct-delivery test above.
#[tokio::test]
async fn a_phone_in_the_middle_forwards_what_it_cannot_read() {
    let alice_dir = TempDir::new().unwrap();
    let relay_dir = TempDir::new().unwrap();
    let bob_dir = TempDir::new().unwrap();
    let alice = offline_runtime(&alice_dir).await;
    let middle = offline_runtime(&relay_dir).await;
    let bob = offline_runtime(&bob_dir).await;

    for rt in [&alice, &middle, &bob] {
        rt.ble_router().set_active(true);
    }

    // A chain, not a triangle: alice and bob are deliberately out of range of
    // each other, so the only path runs through the middle phone — which is not
    // the addressee and cannot decrypt a thing.
    let air = Air::chain(vec![&alice, &middle, &bob]);

    let bob_npub = bob.profile().unwrap().npub;
    let mut bob_events = bob.subscribe_events();
    alice.send_dm(&bob_npub, "two hops away").await.unwrap();

    let got = wait_for_over_air(&air, &mut bob_events, RECV_TIMEOUT, |e| {
        matches!(e, BridgeEvent::IncomingMessageRequest(_))
    })
    .await;
    assert!(
        got.is_some(),
        "bob must receive a frame relayed by a phone that could not open it — \
         forwarding what you cannot read is what makes a mesh out of two links"
    );
}

/// The regression test for the bug this file was written to find.
///
/// `LocalRadios::send` used to return the moment the WiFi mesh accepted the
/// frame, so Bluetooth was dead code on any device with a mesh peer — and a
/// gossipsub publish only proves *somebody* subscribes to the sealed topic, not
/// that the recipient did. A hotspot with client isolation, or any unrelated
/// third device on the network, was enough to lose the message.
///
/// Asserts on the radio queue rather than on arrival, deliberately. The
/// end-to-end version of this cannot be written honestly in-process: every
/// runtime here starts real mDNS on unlock, so alice and bob may well find each
/// other over WiFi, bob receives the message that way, and the test passes with
/// the bug fully present. What the fix actually guarantees is narrower and
/// checkable — the frame reaches the Bluetooth queue **whatever the mesh did** —
/// so that is what this asserts.
#[tokio::test]
async fn a_send_always_reaches_the_bluetooth_queue_whatever_the_mesh_did() {
    let alice_dir = TempDir::new().unwrap();
    let bob_dir = TempDir::new().unwrap();
    let alice = offline_runtime(&alice_dir).await;
    let bob = offline_runtime(&bob_dir).await;

    alice.ble_router().set_active(true);
    let bob_npub = bob.profile().unwrap().npub;

    assert!(
        alice.ble_router().drain_outbound().is_empty(),
        "nothing should be queued before anything is sent"
    );

    alice
        .send_dm(&bob_npub, "the mesh must not eat this")
        .await
        .unwrap();

    assert!(
        !alice.ble_router().drain_outbound().is_empty(),
        "the sealed frame must reach the Bluetooth queue even when the WiFi mesh \
         accepted it — a gossipsub publish proves somebody subscribes, never that \
         the recipient did"
    );
}

// ── WiFi: one hotspot, no upstream ──────────────────────────────────────────

/// Two devices on one network with **no internet**, which is what a phone
/// hotspot with no upstream is, and what the field report described.
///
/// `#[ignore]`d because it uses real mDNS on the host and so depends on the
/// environment — the same reason `saathi.rs`'s mesh tests are, and it runs in
/// the same dedicated CI job rather than the main lane.
#[tokio::test]
#[ignore = "real mDNS: environment-dependent, runs in the Saathi mesh CI job"]
async fn a_message_reaches_the_other_device_on_one_wifi_with_no_relay() {
    let alice_dir = TempDir::new().unwrap();
    let bob_dir = TempDir::new().unwrap();
    let alice = offline_runtime(&alice_dir).await;
    let bob = offline_runtime(&bob_dir).await;

    // Unlocking is enough to start the mesh — "the person I'm messaging is on
    // this WiFi" must not be a mode anyone has to select. Wait for it to be
    // genuinely deliverable rather than merely discovered, since a send before
    // the gossipsub graft fails with `InsufficientPeers`.
    let deliverable = tokio::time::timeout(MESH_FORMATION_TIMEOUT, async {
        loop {
            if alice.mesh_status().peer_count > 0 && bob.mesh_status().peer_count > 0 {
                return;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    })
    .await;
    assert!(
        deliverable.is_ok(),
        "two runtimes on one host must find each other over mDNS and form a \
         deliverable mesh — peer_count is the deliverable count, not sightings"
    );

    let bob_npub = bob.profile().unwrap().npub;
    let alice_npub = alice.profile().unwrap().npub;
    let mut bob_events = bob.subscribe_events();

    alice
        .send_dm(&bob_npub, "same wifi, no internet")
        .await
        .unwrap();

    let request = wait_for(&mut bob_events, RECV_TIMEOUT, |e| {
        matches!(e, BridgeEvent::IncomingMessageRequest(_))
    })
    .await
    .expect("alice's message must reach bob over the local mesh with no relay");
    let BridgeEvent::IncomingMessageRequest(req) = request else {
        unreachable!()
    };
    assert_eq!(req.peer, alice_npub);

    bob.accept_request(&alice_npub).unwrap();
    alice.send_dm(&bob_npub, "still no internet").await.unwrap();

    let delivered = wait_for(&mut bob_events, RECV_TIMEOUT, |e| {
        matches!(e, BridgeEvent::IncomingDirectMessage(_))
    })
    .await
    .expect("an accepted peer's message must arrive over the local mesh");
    let BridgeEvent::IncomingDirectMessage(dm) = delivered else {
        unreachable!()
    };
    assert_eq!(dm.content, "still no internet");
}
