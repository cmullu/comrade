//! The core comms paths — voice calls, video calls, and messages — driven end
//! to end between two real identities over one isolated in-process relay.
//!
//! `two_peer_integration.rs` already proves the happy path of that pipe: an
//! offer reaches an accepted peer, a stranger cannot ring anyone, simultaneous
//! offers both land. This file covers what it explicitly declares out of
//! scope — what happens when the pipe is *lossy* — because that is where the
//! shipped bugs have been.
//!
//! Every test here starts from a symptom someone actually reported, and would
//! have failed while that bug was live:
//!
//!  * **"calls are getting stuck at connecting, voice and video"**. Trickled
//!    ICE is the only part of signalling sent as a *burst*: one gift-wrapped
//!    relay write per candidate, ten to thirty within a couple of seconds,
//!    where the offer and answer are one event each seconds apart. A relay
//!    that rate-limits the burst therefore eats exactly the candidates and
//!    nothing else, and both ends sit on "Connecting…" until the connect
//!    timeout. `send_call_signal` had no retry where every other DM has one,
//!    so a refused candidate was gone.
//!
//! What this layer can and cannot prove, stated plainly: this crate carries
//! call *signalling* — offer/answer/ICE as opaque strings over an encrypted
//! DM. There is no `RTCPeerConnection` here, so "ICE actually connected" is a
//! property of the Android/desktop state machines, not of these tests. What
//! these prove is the thing underneath: that every signal a call depends on
//! survives a relay having a bad day.

mod support;

use std::time::Duration;

use comrade_core::call::CallSignal;
use comrade_ui::{BridgeEvent, ComradeRuntime};
use support::TestRelay;
use tempfile::TempDir;

/// Generous for an in-process relay with no real latency, but long enough to
/// absorb CI scheduling jitter. Sized above the retry budget
/// (`call_signal_retry_delay_ms`, ~1.5s) so a test that exercises retries is
/// not racing its own timeout.
const RECV_TIMEOUT: Duration = Duration::from_secs(10);
/// How long "nothing arrived" waits before concluding that.
const ABSENCE_TIMEOUT: Duration = Duration::from_millis(800);
/// The vault loops start on a spawned task; a short yield keeps the first
/// message from racing the subscription's `REQ`.
const SETTLE: Duration = Duration::from_millis(150);

/// Long enough for the handshake's follow-up traffic to finish publishing, so
/// a relay-wide refusal count lands on the events the test is about.
const QUIESCE: Duration = Duration::from_millis(400);

/// A realistic trickle: enough candidates that a burst is a burst, few enough
/// that the suite stays fast.
const CANDIDATE_BURST: usize = 8;

async fn unlocked_runtime(relay_url: &str, dir: &TempDir) -> ComradeRuntime {
    let mut rt = ComradeRuntime::with_relays(vec![relay_url.to_string()]);
    rt.unlock_vault(dir.path(), "pin").await.unwrap();
    rt.spawn_event_loops();
    rt
}

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
            Ok(Err(tokio::sync::broadcast::error::RecvError::Lagged(_))) => continue,
            Ok(Err(_)) | Err(_) => return None,
        }
    }
}

/// Two runtimes on one relay, already mutually accepted — the state every call
/// test needs before it can ring anything.
struct Pair {
    alice: ComradeRuntime,
    bob: ComradeRuntime,
    alice_npub: String,
    bob_npub: String,
    alice_events: tokio::sync::broadcast::Receiver<BridgeEvent>,
    bob_events: tokio::sync::broadcast::Receiver<BridgeEvent>,
}

async fn accepted_pair(relay: &TestRelay, alice_dir: &TempDir, bob_dir: &TempDir) -> Pair {
    let alice = unlocked_runtime(&relay.url, alice_dir).await;
    let bob = unlocked_runtime(&relay.url, bob_dir).await;
    let alice_npub = alice.profile().unwrap().npub;
    let bob_npub = bob.profile().unwrap().npub;
    let alice_events = alice.subscribe_events();
    let mut bob_events = bob.subscribe_events();
    tokio::time::sleep(SETTLE).await;

    // Sending accepts the recipient on the sender's side; bob still has to
    // accept the resulting request before a call may ring him.
    alice.send_dm(&bob_npub, "before I call").await.unwrap();
    wait_for(&mut bob_events, RECV_TIMEOUT, |e| {
        matches!(e, BridgeEvent::IncomingMessageRequest(_))
    })
    .await
    .expect("the first DM lands as a message request");
    bob.accept_request(&alice_npub).unwrap();

    Pair {
        alice,
        bob,
        alice_npub,
        bob_npub,
        alice_events,
        bob_events,
    }
}

fn ice(n: usize) -> String {
    // Shaped like a real candidate line: the parts that distinguish two
    // candidates (port, type) sit late in the string, which is what made an
    // earlier prefix-hash dedup a hazard for exactly this traffic.
    serde_json::to_string(&CallSignal::Ice {
        candidate: format!(
            "candidate:842163049 1 udp 1677729535 192.168.1.5 {} typ srflx raddr 0.0.0.0 rport 0",
            50_000 + n
        ),
        sdp_mid: Some("0".to_string()),
        sdp_m_line_index: Some(0),
    })
    .unwrap()
}

/// Collect every call signal for `call_id` that arrives within the timeout,
/// stopping early once `expected` of them have.
async fn collect_call_signals(
    rx: &mut tokio::sync::broadcast::Receiver<BridgeEvent>,
    call_id: &str,
    expected: usize,
    timeout: Duration,
) -> Vec<CallSignal> {
    let mut out = Vec::new();
    let deadline = tokio::time::Instant::now() + timeout;
    while out.len() < expected {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            break;
        }
        let Some(BridgeEvent::IncomingCallSignal(dto)) = wait_for(
            rx,
            remaining,
            |e| matches!(e, BridgeEvent::IncomingCallSignal(d) if d.call_id == call_id),
        )
        .await
        else {
            break;
        };
        out.push(dto.signal);
    }
    out
}

// ── The regression: a rate-limiting relay must not cost a call its ICE ───────

#[tokio::test]
async fn a_relay_that_refuses_a_burst_does_not_cost_the_call_its_ice_candidates() {
    // Fails without the retry in `send_call_signal`: each refused publish
    // returned an error and the candidate was simply gone, which is how both
    // ends ended up on "Connecting…" while offer and answer had sailed through.
    let relay = TestRelay::start().await;
    let alice_dir = TempDir::new().unwrap();
    let bob_dir = TempDir::new().unwrap();
    let mut p = accepted_pair(&relay, &alice_dir, &bob_dir).await;

    let call_id = p.alice.place_call(&p.bob_npub, "audio").unwrap().call_id;

    // Let the handshake's own follow-up traffic (profile share, receipt)
    // drain first: refusals are counted relay-wide, so arming while that is
    // still in flight would spend them on the wrong events.
    tokio::time::sleep(QUIESCE).await;

    // Now refuse three publishes — the first attempt at each of three
    // candidates. Only a client that re-sends gets them through.
    let refused = 3usize;
    relay.refuse_next_publishes(refused);

    for n in 0..CANDIDATE_BURST {
        p.alice
            .send_call_signal(&p.bob_npub, &call_id, "audio", &ice(n))
            .await
            .expect("a refused candidate must be retried, not dropped");
    }

    let got =
        collect_call_signals(&mut p.bob_events, &call_id, CANDIDATE_BURST, RECV_TIMEOUT).await;
    assert_eq!(
        got.len(),
        CANDIDATE_BURST,
        "every candidate must survive the relay's refusals; got {} of {CANDIDATE_BURST}",
        got.len(),
    );

    // And the client genuinely re-sent rather than the relay quietly relenting:
    // one attempt per candidate plus one extra for each refusal, at minimum.
    assert!(
        relay.publish_attempts() >= CANDIDATE_BURST + refused,
        "expected re-sends for the {refused} refusals, saw only {} attempts",
        relay.publish_attempts(),
    );

    relay.stop().await;
}

#[tokio::test]
async fn a_call_signal_refused_past_the_retry_budget_fails_loudly_rather_than_silently() {
    // The other half of the contract: retrying is bounded. A relay that never
    // relents must surface an error to the caller — a signal that quietly
    // vanishes is what makes a stuck call undiagnosable.
    let relay = TestRelay::start().await;
    let alice_dir = TempDir::new().unwrap();
    let bob_dir = TempDir::new().unwrap();
    let mut p = accepted_pair(&relay, &alice_dir, &bob_dir).await;

    let call_id = p.alice.place_call(&p.bob_npub, "audio").unwrap().call_id;
    relay.refuse_every_publish();

    let err = p
        .alice
        .send_call_signal(&p.bob_npub, &call_id, "audio", &ice(0))
        .await;
    assert!(
        err.is_err(),
        "exhausting the retry budget must report failure, not pretend success",
    );

    assert!(
        collect_call_signals(&mut p.bob_events, &call_id, 1, ABSENCE_TIMEOUT)
            .await
            .is_empty(),
        "nothing should have reached bob",
    );

    relay.stop().await;
}

// ── Voice and video, whole-call round trips ──────────────────────────────────

/// Both call kinds, driven through every signal a real call sends, over a
/// relay that refuses part of the burst on the way. Parameterised because the
/// reported bug hit voice and video identically and a fix for one that missed
/// the other would be invisible in a single-kind test.
async fn call_round_trip_survives_a_lossy_relay(media: &str) {
    let relay = TestRelay::start().await;
    let alice_dir = TempDir::new().unwrap();
    let bob_dir = TempDir::new().unwrap();
    let mut p = accepted_pair(&relay, &alice_dir, &bob_dir).await;

    let session = p.alice.place_call(&p.bob_npub, media).unwrap();
    let call_id = session.call_id.clone();
    assert!(
        session.ice_servers.iter().all(|s| s.username.is_none()),
        "the first attempt is STUN-only — a TURN credential must never go out up front",
    );

    // One or two refusals somewhere in the middle of everything that follows.
    tokio::time::sleep(QUIESCE).await;
    relay.refuse_next_publishes(2);

    // Offer → ringing → answer, then a trickle of candidates from both sides,
    // then hangup: the full shape of a call.
    let offer = serde_json::to_string(&CallSignal::Offer {
        sdp: format!("offer-sdp-{media}"),
    })
    .unwrap();
    p.alice
        .send_call_signal(&p.bob_npub, &call_id, media, &offer)
        .await
        .unwrap();

    let Some(BridgeEvent::IncomingCallSignal(dto)) = wait_for(
        &mut p.bob_events,
        RECV_TIMEOUT,
        |e| matches!(e, BridgeEvent::IncomingCallSignal(d) if d.call_id == call_id),
    )
    .await
    else {
        panic!("bob must receive the offer for a {media} call");
    };
    assert_eq!(
        dto.media, media,
        "the media kind must survive the round trip — a video call that arrives as audio \
         gives the callee no camera",
    );
    assert!(matches!(dto.signal, CallSignal::Offer { .. }));

    let ringing = serde_json::to_string(&CallSignal::Ringing).unwrap();
    p.bob
        .send_call_signal(&p.alice_npub, &call_id, media, &ringing)
        .await
        .unwrap();
    let answer = serde_json::to_string(&CallSignal::Answer {
        sdp: format!("answer-sdp-{media}"),
    })
    .unwrap();
    p.bob
        .send_call_signal(&p.alice_npub, &call_id, media, &answer)
        .await
        .unwrap();

    let alice_got = collect_call_signals(&mut p.alice_events, &call_id, 2, RECV_TIMEOUT).await;
    assert!(
        alice_got
            .iter()
            .any(|s| matches!(s, CallSignal::Answer { .. })),
        "the caller must get the answer; without it the call never leaves Calling…",
    );

    // The burst, both directions.
    for n in 0..CANDIDATE_BURST {
        p.alice
            .send_call_signal(&p.bob_npub, &call_id, media, &ice(n))
            .await
            .unwrap();
        p.bob
            .send_call_signal(&p.alice_npub, &call_id, media, &ice(100 + n))
            .await
            .unwrap();
    }

    let bob_candidates =
        collect_call_signals(&mut p.bob_events, &call_id, CANDIDATE_BURST, RECV_TIMEOUT).await;
    assert_eq!(
        bob_candidates.len(),
        CANDIDATE_BURST,
        "the callee lost candidates on a {media} call",
    );
    assert!(
        bob_candidates
            .iter()
            .all(|s| matches!(s, CallSignal::Ice { .. })),
        "every one should be a candidate",
    );

    // Candidates must arrive whole — a truncated or reordered candidate line is
    // as useless as a missing one.
    let ports: Vec<String> = bob_candidates
        .iter()
        .filter_map(|s| match s {
            CallSignal::Ice { candidate, .. } => {
                candidate.split_whitespace().nth(5).map(str::to_string)
            }
            _ => None,
        })
        .collect();
    let mut unique = ports.clone();
    unique.sort();
    unique.dedup();
    assert_eq!(
        unique.len(),
        CANDIDATE_BURST,
        "candidates must arrive distinct, not collapsed by a dedup that cannot tell them apart",
    );

    relay.stop().await;
}

#[tokio::test]
async fn a_voice_call_survives_a_lossy_relay_end_to_end() {
    call_round_trip_survives_a_lossy_relay("audio").await;
}

#[tokio::test]
async fn a_video_call_survives_a_lossy_relay_end_to_end() {
    call_round_trip_survives_a_lossy_relay("video").await;
}

// ── Messages ─────────────────────────────────────────────────────────────────

#[tokio::test]
async fn a_message_refused_by_the_relay_is_queued_and_still_arrives() {
    // The property calls did not have until now, and messages always did: a
    // refused DM is queued in the outbox and retried rather than lost. Pinned
    // here so the two paths cannot drift apart again.
    let relay = TestRelay::start().await;
    let alice_dir = TempDir::new().unwrap();
    let bob_dir = TempDir::new().unwrap();
    let mut p = accepted_pair(&relay, &alice_dir, &bob_dir).await;

    tokio::time::sleep(QUIESCE).await;
    relay.refuse_next_publishes(1);
    // Whether the immediate send reports success or a queued retry is the
    // outbox's business; what must not happen is the message disappearing.
    let _ = p.alice.send_dm(&p.bob_npub, "did this survive?").await;

    let arrived = wait_for(
        &mut p.bob_events,
        RECV_TIMEOUT,
        |e| matches!(e, BridgeEvent::IncomingDirectMessage(dm) if dm.content == "did this survive?"),
    )
    .await;
    assert!(
        arrived.is_some(),
        "a DM refused once must still reach the peer",
    );

    relay.stop().await;
}

#[tokio::test]
async fn messages_and_call_signals_on_one_conversation_do_not_shadow_each_other() {
    // `dispatch_incoming_dm` is an ordered chain of parsers that each return on
    // a match, and the call branch sits fifth. A new envelope type added above
    // it that accidentally matched a call body would swallow signalling and
    // present as "calls stopped working" with chat still fine — so pin that
    // chat, call signals, and their interleaving all still land as themselves.
    let relay = TestRelay::start().await;
    let alice_dir = TempDir::new().unwrap();
    let bob_dir = TempDir::new().unwrap();
    let mut p = accepted_pair(&relay, &alice_dir, &bob_dir).await;

    let call_id = p.alice.place_call(&p.bob_npub, "video").unwrap().call_id;
    let offer = serde_json::to_string(&CallSignal::Offer {
        sdp: "offer-sdp".to_string(),
    })
    .unwrap();

    p.alice
        .send_dm(&p.bob_npub, "calling you now")
        .await
        .unwrap();
    p.alice
        .send_call_signal(&p.bob_npub, &call_id, "video", &offer)
        .await
        .unwrap();
    p.alice.send_dm(&p.bob_npub, "pick up!").await.unwrap();

    let mut saw_chat = 0;
    let mut saw_signal = false;
    let deadline = tokio::time::Instant::now() + RECV_TIMEOUT;
    while (saw_chat < 2 || !saw_signal) && tokio::time::Instant::now() < deadline {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        match tokio::time::timeout(remaining, p.bob_events.recv()).await {
            Ok(Ok(BridgeEvent::IncomingDirectMessage(dm))) => {
                assert!(
                    !dm.content.contains("comrade_call"),
                    "a call envelope surfaced as chat text: {}",
                    dm.content,
                );
                saw_chat += 1;
            }
            Ok(Ok(BridgeEvent::IncomingCallSignal(dto))) if dto.call_id == call_id => {
                saw_signal = true;
            }
            Ok(Ok(_)) => continue,
            Ok(Err(tokio::sync::broadcast::error::RecvError::Lagged(_))) => continue,
            Ok(Err(_)) | Err(_) => break,
        }
    }
    assert_eq!(saw_chat, 2, "both chat messages must arrive as chat");
    assert!(saw_signal, "the call signal must arrive as a call signal");

    relay.stop().await;
}
