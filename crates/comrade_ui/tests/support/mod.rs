//! A minimal, in-process Nostr relay (a NIP-01 subset) so the two-peer
//! integration suite (`tests/two_peer_integration.rs`) is hermetic — no
//! Docker, no public-internet relay dependency (AUDIT.md COMMS-03: "create a
//! test environment with an isolated Nostr relay").
//!
//! Supports exactly what `nostr-sdk`'s client needs to drive Sabha/Vault
//! traffic: `EVENT` (signature-verified, stored, broadcast to matching live
//! subscriptions, acked with `OK`), `REQ` (replays stored matches, then
//! `EOSE`, then forwards future matching events live), `CLOSE`. NIP-11, AUTH
//! and storage limits are deliberately out of scope: this exists to test *our*
//! code against a real (if tiny) relay implementation, not to be one.
//!
//! One exception to that minimalism, added deliberately: [`TestRelay::refuse_next_publishes`]
//! makes the relay answer `OK false` to the next N events. Real relays
//! rate-limit, and the interesting failure is not a relay that is *down* — the
//! client notices that — but one that stays up and refuses a burst. Trickled
//! ICE is the only signalling that arrives as a burst (one gift-wrapped event
//! per candidate), so it is the only part of a call that a rate limiter
//! silently eats, while the offer and answer sail through. Without a relay
//! that can say no, that whole class of bug is untestable here.

// Three test binaries (`two_peer_integration`, `comms_regression`,
// `feed_flood_load`) each `mod support;` this file, and each uses a different
// subset — so anything only one of them needs is dead code in the other two.
// That is inherent to Cargo's per-binary compilation of integration tests, not
// something to fix by deleting a helper the suite genuinely uses.
#![allow(dead_code)]

use std::borrow::Cow;
use std::collections::HashMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use futures_util::{SinkExt, StreamExt};
use nostr_sdk::prelude::*;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{broadcast, oneshot};
use tokio_tungstenite::tungstenite::Message;

/// Bounded generously above anything one test session publishes; a lagged
/// receiver would only mean a live-forward gets skipped; the initial `REQ`
/// replay is unaffected either way since it reads the persisted `store`, not
/// this channel.
const EVENT_BROADCAST_CAPACITY: usize = 1024;

/// A running in-process relay bound to an OS-assigned local port. Call
/// [`TestRelay::stop`] (or just let it drop — the accept loop is independent
/// of this handle either way, but tests should still call `stop` so a slow
/// test doesn't leak listening sockets across the suite).
pub struct TestRelay {
    pub url: String,
    /// How many further `EVENT`s to answer `OK false`. Shared with every
    /// connection task, so a test can arm it *after* setup traffic has landed.
    refuse_remaining: Arc<AtomicUsize>,
    /// Every `EVENT` this relay has been asked to store, refused or not — so a
    /// test can tell "the client never re-sent it" from "the relay never got it".
    publish_attempts: Arc<AtomicUsize>,
    shutdown_tx: Option<oneshot::Sender<()>>,
    accept_task: Option<tokio::task::JoinHandle<()>>,
}

impl TestRelay {
    /// Bind an ephemeral local port and start serving immediately.
    pub async fn start() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind ephemeral test-relay port");
        let addr = listener.local_addr().expect("listener has a local addr");
        let url = format!("ws://{addr}");

        let store: Arc<Mutex<Vec<Event>>> = Arc::new(Mutex::new(Vec::new()));
        let (events_tx, _) = broadcast::channel::<Event>(EVENT_BROADCAST_CAPACITY);
        let (shutdown_tx, mut shutdown_rx) = oneshot::channel();
        let refuse_remaining = Arc::new(AtomicUsize::new(0));
        let publish_attempts = Arc::new(AtomicUsize::new(0));

        let accept_refuse = refuse_remaining.clone();
        let accept_attempts = publish_attempts.clone();
        let accept_task = tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = &mut shutdown_rx => break,
                    accepted = listener.accept() => {
                        let Ok((stream, _)) = accepted else { continue };
                        tokio::spawn(serve_connection(
                            stream,
                            store.clone(),
                            events_tx.clone(),
                            accept_refuse.clone(),
                            accept_attempts.clone(),
                        ));
                    }
                }
            }
        });

        Self {
            url,
            refuse_remaining,
            publish_attempts,
            shutdown_tx: Some(shutdown_tx),
            accept_task: Some(accept_task),
        }
    }

    /// Answer `OK false` to the next `n` events, then behave normally again —
    /// a rate limiter that lets a retry through once the burst has passed.
    ///
    /// Arm this *after* setup traffic (the DMs that make two peers accepted
    /// contacts), or the refusals land on the handshake instead of the signals
    /// under test.
    pub fn refuse_next_publishes(&self, n: usize) {
        self.refuse_remaining.store(n, Ordering::SeqCst);
    }

    /// Refuse every event from here on — a relay that never relents, for
    /// testing what happens when a retry budget runs out.
    ///
    /// Separate from a count because the two are different questions, and a
    /// count cannot answer this one: background traffic (profile shares,
    /// receipts, presence) publishes on its own schedule and would eat an
    /// arbitrary share of a fixed budget of refusals.
    pub fn refuse_every_publish(&self) {
        self.refuse_remaining.store(usize::MAX, Ordering::SeqCst);
    }

    /// Total `EVENT`s received, including refused ones. A retry shows up here
    /// as a second attempt, which is how a test tells a client that re-sent a
    /// refused signal from one that gave up.
    pub fn publish_attempts(&self) -> usize {
        self.publish_attempts.load(Ordering::SeqCst)
    }

    /// Stop accepting connections and wait for the accept loop to exit.
    pub async fn stop(mut self) {
        if let Some(tx) = self.shutdown_tx.take() {
            let _ = tx.send(());
        }
        if let Some(task) = self.accept_task.take() {
            let _ = task.await;
        }
    }
}

async fn serve_connection(
    stream: TcpStream,
    store: Arc<Mutex<Vec<Event>>>,
    events_tx: broadcast::Sender<Event>,
    refuse_remaining: Arc<AtomicUsize>,
    publish_attempts: Arc<AtomicUsize>,
) {
    let Ok(ws) = tokio_tungstenite::accept_async(stream).await else {
        return;
    };
    let (mut write, mut read) = ws.split();
    let mut live_subs: HashMap<SubscriptionId, Vec<Filter>> = HashMap::new();
    let mut events_rx = events_tx.subscribe();
    let match_opts = MatchEventOptions::new();

    loop {
        tokio::select! {
            // Forward a just-published event to every live subscription it matches.
            broadcasted = events_rx.recv() => {
                let Ok(event) = broadcasted else { continue };
                for (sub_id, filters) in &live_subs {
                    if filters.iter().any(|f| f.match_event(&event, match_opts)) {
                        let msg = RelayMessage::Event {
                            subscription_id: Cow::Borrowed(sub_id),
                            event: Cow::Borrowed(&event),
                        };
                        if write.send(Message::text(msg.as_json())).await.is_err() {
                            return;
                        }
                    }
                }
            }
            incoming = read.next() => {
                let Some(Ok(incoming)) = incoming else { return };
                let text = match incoming {
                    Message::Text(t) => t.to_string(),
                    Message::Close(_) => return,
                    _ => continue,
                };
                let Ok(client_msg) = ClientMessage::from_json(&text) else { continue };
                match client_msg {
                    ClientMessage::Event(event) => {
                        let event = event.into_owned();
                        publish_attempts.fetch_add(1, Ordering::SeqCst);
                        // Refuse before verifying: a rate limiter does not care
                        // whether the event was well-formed, and modelling it
                        // the other way round would let a test pass because the
                        // event happened to be valid.
                        let refused = refuse_remaining
                            .fetch_update(Ordering::SeqCst, Ordering::SeqCst, |n| {
                                n.checked_sub(1)
                            })
                            .is_ok();
                        let verified = !refused && event.verify().is_ok();
                        let reason = if refused {
                            "rate-limited: slow down"
                        } else if verified {
                            ""
                        } else {
                            "invalid: bad signature"
                        };
                        let ok_reply = RelayMessage::Ok {
                            event_id: event.id,
                            status: verified,
                            message: Cow::Borrowed(reason),
                        };
                        if write.send(Message::text(ok_reply.as_json())).await.is_err() {
                            return;
                        }
                        if verified {
                            store.lock().unwrap().push(event.clone());
                            let _ = events_tx.send(event);
                        }
                    }
                    ClientMessage::Req { subscription_id, filters } => {
                        let sub_id = subscription_id.into_owned();
                        let filters: Vec<Filter> = filters.into_iter().map(Cow::into_owned).collect();
                        let backlog: Vec<Event> = {
                            let stored = store.lock().unwrap();
                            stored
                                .iter()
                                .filter(|e| filters.iter().any(|f| f.match_event(e, match_opts)))
                                .cloned()
                                .collect()
                        };
                        for event in &backlog {
                            let msg = RelayMessage::Event {
                                subscription_id: Cow::Borrowed(&sub_id),
                                event: Cow::Borrowed(event),
                            };
                            if write.send(Message::text(msg.as_json())).await.is_err() {
                                return;
                            }
                        }
                        let eose = RelayMessage::EndOfStoredEvents(Cow::Borrowed(&sub_id));
                        if write.send(Message::text(eose.as_json())).await.is_err() {
                            return;
                        }
                        live_subs.insert(sub_id, filters);
                    }
                    ClientMessage::Close(sub_id) => {
                        live_subs.remove(&*sub_id);
                    }
                    // AUTH/COUNT/NEG-*: unsupported, and never sent by our client.
                    _ => {}
                }
            }
        }
    }
}
