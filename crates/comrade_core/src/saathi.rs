/*!
 * Milestone 4 — Saathi: Off-Grid Libp2p Mesh Engine
 *
 * Spins up a local peer-to-peer Swarm using:
 *  • TCP transport with Noise handshake + Yamux multiplexing
 *  • mDNS for automatic local-network peer discovery
 *  • Gossipsub for message propagation across all discovered peers
 *
 * When `AppWorkspace::OffGridTravel` is active this engine replaces the
 * Nostr relay connection. Outbound messages issued while no peers are
 * reachable are cached locally and broadcast as soon as peers join.
 */

use std::{
    collections::{HashSet, VecDeque},
    hash::{DefaultHasher, Hash, Hasher},
    sync::Arc,
    time::Duration,
};

use libp2p::{
    futures::StreamExt,
    gossipsub::{self, IdentTopic, MessageId},
    mdns,
    swarm::{
        dial_opts::{DialOpts, PeerCondition},
        NetworkBehaviour, SwarmEvent,
    },
    PeerId, Swarm,
};
use serde::{Deserialize, Serialize};
use tokio::sync::{mpsc, watch, Mutex};
use tracing::{debug, info, warn};

use crate::{dak::Envelope, error::SaathiError};

// ── Gossipsub topic ──────────────────────────────────────────────────────────

const TOPIC_NAME: &str = "comrade/saathi/v1";
/// Second topic, carrying **sealed** mail: a [`crate::dak::Envelope`] whose
/// only routing datum is a day-rotating recipient tag. Every peer on the
/// network receives every frame and only the addressee can open one, which is
/// how a shared WiFi becomes a delivery path without trusting anyone on it.
/// Separate from [`TOPIC_NAME`] so a peer that only wants public mesh chatter
/// is not obliged to relay private mail, and so the two never mix on decode.
const DAK_TOPIC_NAME: &str = "comrade/saathi/dak/v1";
const MAX_CACHE: usize = 256;

// ── Wire message format ──────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MeshMessage {
    pub sender: String,
    pub content: String,
    pub timestamp: u64,
}

impl MeshMessage {
    pub fn new(sender: impl Into<String>, content: impl Into<String>) -> Self {
        let timestamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        Self {
            sender: sender.into(),
            content: content.into(),
            timestamp,
        }
    }
}

// ── Combined NetworkBehaviour ────────────────────────────────────────────────

#[derive(NetworkBehaviour)]
struct ComradeBehaviour {
    gossipsub: gossipsub::Behaviour,
    mdns: mdns::tokio::Behaviour,
}

// ── Saathi engine ────────────────────────────────────────────────────────────

/// How far along the local network actually is, in the two numbers that mean
/// different things to a person waiting for a message to send.
///
/// The distinction is not pedantry — it is the difference between a message
/// going out and a message sitting under a clock icon. mDNS announces a peer
/// the moment it hears the multicast packet, but a sealed frame cannot be
/// published until that peer has been dialled, completed a Noise handshake, and
/// told us it subscribes to [`DAK_TOPIC_NAME`]. Between those two moments
/// `gossipsub::publish` fails with `InsufficientPeers`.
///
/// The indicator used to be driven by [`Self::discovered`] while sending
/// depended on [`Self::deliverable`], so the app could truthfully say "1 device
/// nearby" and still refuse to send. Keeping both, and being explicit about
/// which one a caller wants, is how that class of lie is prevented.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct MeshReach {
    /// Peers mDNS has announced on this network. Someone is *there*.
    pub discovered: usize,
    /// Peers that have subscribed to the sealed-mail topic — i.e. peers a
    /// [`SaathiEngine::publish_sealed`] would actually reach. This is the
    /// number that decides whether a send can succeed.
    pub deliverable: usize,
}

impl MeshReach {
    /// Whether sealed mail can go out right now.
    pub fn can_deliver(&self) -> bool {
        self.deliverable > 0
    }

    /// Somebody is on this network but the mesh has not finished forming.
    /// A transient state, and the one worth showing as "connecting" rather
    /// than as either "nobody here" or a green light.
    pub fn is_connecting(&self) -> bool {
        self.deliverable == 0 && self.discovered > 0
    }
}

/// Shared state accessed from both the swarm driver task and callers.
struct SaathiShared {
    #[allow(dead_code)]
    peer_id: PeerId,
    outbox_cache: VecDeque<MeshMessage>,
    received: Vec<MeshMessage>,
    /// Peers mDNS currently announces (inserted on `Discovered`, removed on
    /// `Expired`). A set, not a running counter, so a peer mDNS reports more
    /// than once (it advertises once per address) never inflates the count.
    discovered_peers: HashSet<PeerId>,
    /// Peers subscribed to [`DAK_TOPIC_NAME`] — the ones a sealed publish
    /// reaches. Driven by gossipsub's own subscription events and by
    /// connections closing, never by mDNS: a peer whose mDNS record lapses
    /// while the TCP connection is still up is still deliverable.
    deliverable_peers: HashSet<PeerId>,
}

impl SaathiShared {
    fn reach(&self) -> MeshReach {
        MeshReach {
            discovered: self.discovered_peers.len(),
            deliverable: self.deliverable_peers.len(),
        }
    }
}

pub struct SaathiEngine {
    /// Sender half of the command channel into the swarm driver task.
    cmd_tx: mpsc::Sender<SaathiCmd>,
    /// Received messages stream.
    msg_rx: Arc<Mutex<mpsc::Receiver<MeshMessage>>>,
    /// Received sealed-envelope stream (see [`DAK_TOPIC_NAME`]).
    sealed_rx: Arc<Mutex<mpsc::Receiver<Envelope>>>,
    shared: Arc<Mutex<SaathiShared>>,
    local_id: String,
    local_peer_id: PeerId,
    /// Live local-network reach — the signal a connectivity indicator watches,
    /// and the one a sender checks before deciding a route is available.
    reach_rx: watch::Receiver<MeshReach>,
}

enum SaathiCmd {
    Broadcast(MeshMessage),
    /// Publish a sealed envelope onto [`DAK_TOPIC_NAME`]. The result channel
    /// reports whether gossipsub accepted it, because — unlike a public
    /// broadcast, which the engine caches — sealed mail is already retained by
    /// the caller's [`crate::dak::Outbox`]. Caching it here as well would give
    /// one message two independent retry loops.
    PublishSealed {
        bytes: Vec<u8>,
        ack: tokio::sync::oneshot::Sender<Result<(), String>>,
    },
    Shutdown,
}

impl SaathiEngine {
    /// Initialise the Saathi engine — builds the Swarm and spawns the driver.
    pub async fn new(local_sender_label: impl Into<String>) -> Result<Self, SaathiError> {
        let sender_label = local_sender_label.into();

        // Message-ID function: hash the raw gossipsub payload for deduplication
        let message_id_fn = |msg: &gossipsub::Message| {
            let mut h = DefaultHasher::new();
            msg.data.hash(&mut h);
            MessageId::from(h.finish().to_string())
        };

        let gossipsub_config = gossipsub::ConfigBuilder::default()
            // One second, not the ten this used to run. The heartbeat is what
            // grafts a newly-subscribed peer into the mesh, so on a two-phone
            // WiFi it is the whole latency between "the other phone appeared"
            // and "a message will actually send". Ten seconds of that gap is
            // long enough for a person to conclude the feature is broken, and
            // the cost at this scale — a handful of LAN peers — is negligible.
            .heartbeat_interval(Duration::from_secs(1))
            .validation_mode(gossipsub::ValidationMode::Strict)
            .message_id_fn(message_id_fn)
            .build()
            .map_err(|e| SaathiError::SwarmInit(e.to_string()))?;

        let mut swarm: Swarm<ComradeBehaviour> = libp2p::SwarmBuilder::with_new_identity()
            .with_tokio()
            .with_tcp(
                libp2p::tcp::Config::default(),
                libp2p::noise::Config::new,
                libp2p::yamux::Config::default,
            )
            .map_err(|e| SaathiError::SwarmInit(e.to_string()))?
            .with_behaviour(|key| {
                let gossipsub = gossipsub::Behaviour::new(
                    gossipsub::MessageAuthenticity::Signed(key.clone()),
                    gossipsub_config,
                )
                .map_err(std::io::Error::other)?;

                // Twenty seconds, not the default five minutes. mDNS announces
                // once on startup and then only re-queries on this interval, so
                // with the default a phone that joined the WiFi a moment after
                // the other one — or whose single announcement was dropped, which
                // is ordinary for multicast — stays invisible for up to five
                // minutes. Nobody testing "can I message you across the room"
                // waits that long before concluding it does not work.
                //
                // The cost is one small multicast query per interval per device,
                // which is nothing next to being undiscoverable. `ttl` stays at
                // its default: it governs how long a *seen* peer is remembered,
                // and shortening it would expire peers that are still there.
                let mdns_config = mdns::Config {
                    query_interval: Duration::from_secs(20),
                    ..Default::default()
                };
                let mdns = mdns::tokio::Behaviour::new(mdns_config, key.public().to_peer_id())?;

                Ok(ComradeBehaviour { gossipsub, mdns })
            })
            .map_err(|e| SaathiError::SwarmInit(e.to_string()))?
            .with_swarm_config(|c| c.with_idle_connection_timeout(Duration::from_secs(60)))
            .build();

        let local_peer_id = *swarm.local_peer_id();
        info!(peer_id = %local_peer_id, "Saathi swarm identity");

        // Subscribe to both mesh topics: public chatter and sealed mail.
        for name in [TOPIC_NAME, DAK_TOPIC_NAME] {
            swarm
                .behaviour_mut()
                .gossipsub
                .subscribe(&IdentTopic::new(name))
                .map_err(|e| SaathiError::SwarmInit(e.to_string()))?;
        }

        // Listen on a random TCP port
        swarm
            .listen_on("/ip4/0.0.0.0/tcp/0".parse().expect("valid multiaddr"))
            .map_err(|e| SaathiError::TransportError(e.to_string()))?;

        // Channel for incoming messages surfaced to callers
        let (msg_tx, msg_rx) = mpsc::channel::<MeshMessage>(128);
        // Channel for incoming sealed envelopes surfaced to callers.
        let (sealed_tx, sealed_rx) = mpsc::channel::<Envelope>(128);
        // Channel for commands from callers into the swarm loop
        let (cmd_tx, mut cmd_rx) = mpsc::channel::<SaathiCmd>(64);

        let shared = Arc::new(Mutex::new(SaathiShared {
            peer_id: local_peer_id,
            outbox_cache: VecDeque::new(),
            received: Vec::new(),
            discovered_peers: HashSet::new(),
            deliverable_peers: HashSet::new(),
        }));

        let (reach_tx, reach_rx) = watch::channel(MeshReach::default());
        let dak_topic_hash = IdentTopic::new(DAK_TOPIC_NAME).hash();

        let shared_clone = shared.clone();
        let _label_clone = sender_label.clone();

        // ── Swarm driver task ──────────────────────────────────────────────
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    // Process commands from callers
                    Some(cmd) = cmd_rx.recv() => {
                        match cmd {
                            SaathiCmd::Broadcast(msg) => {
                                let bytes = match serde_json::to_vec(&msg) {
                                    Ok(b)  => b,
                                    Err(e) => {
                                        warn!("Saathi: failed to serialise message: {e}");
                                        continue;
                                    }
                                };
                                let topic = IdentTopic::new(TOPIC_NAME);
                                if let Err(e) = swarm.behaviour_mut().gossipsub.publish(topic, bytes) {
                                    warn!("Saathi: gossipsub publish failed: {e} — caching message");
                                    let mut guard = shared_clone.lock().await;
                                    if guard.outbox_cache.len() < MAX_CACHE {
                                        guard.outbox_cache.push_back(msg);
                                    } else {
                                        warn!("Saathi: outbox cache full, dropping message");
                                    }
                                }
                            }
                            SaathiCmd::PublishSealed { bytes, ack } => {
                                let topic = IdentTopic::new(DAK_TOPIC_NAME);
                                let result = swarm
                                    .behaviour_mut()
                                    .gossipsub
                                    .publish(topic, bytes)
                                    .map(|_| ())
                                    .map_err(|e| e.to_string());
                                if let Err(e) = &result {
                                    debug!("Saathi: sealed publish rejected: {e}");
                                }
                                // A dropped receiver just means the caller gave
                                // up waiting; the publish already happened.
                                let _ = ack.send(result);
                            }
                            SaathiCmd::Shutdown => {
                                info!("Saathi swarm shutting down");
                                break;
                            }
                        }
                    }

                    // Process swarm events
                    event = swarm.next() => {
                        let Some(event) = event else { break };
                        match event {
                            SwarmEvent::Behaviour(ComradeBehaviourEvent::Mdns(
                                mdns::Event::Discovered(peers)
                            )) => {
                                for (peer_id, addr) in peers {
                                    info!(peer = %peer_id, %addr, "Saathi: peer discovered via mDNS");
                                    swarm.behaviour_mut()
                                         .gossipsub
                                         .add_explicit_peer(&peer_id);

                                    // Dial with the address mDNS just handed us
                                    // rather than leaving it to gossipsub's
                                    // implicit peer-id dial. That path depends
                                    // on the mDNS behaviour still holding an
                                    // address for the peer when the swarm asks
                                    // for one; dialling the announced address
                                    // directly does not. `DisconnectedAndNotDialing`
                                    // makes a repeat announcement — mDNS
                                    // re-advertises, and once per address —
                                    // free rather than a redundant dial.
                                    let opts = DialOpts::peer_id(peer_id)
                                        .addresses(vec![addr])
                                        .condition(PeerCondition::DisconnectedAndNotDialing)
                                        .build();
                                    if let Err(e) = swarm.dial(opts) {
                                        debug!(peer = %peer_id, "Saathi: dial not started: {e}");
                                    }

                                    let mut guard = shared_clone.lock().await;
                                    guard.discovered_peers.insert(peer_id);
                                    let reach = guard.reach();
                                    drop(guard);
                                    let _ = reach_tx.send(reach);
                                }
                            }

                            SwarmEvent::Behaviour(ComradeBehaviourEvent::Mdns(
                                mdns::Event::Expired(peers)
                            )) => {
                                for (peer_id, _) in peers {
                                    debug!(peer = %peer_id, "Saathi: peer expired from mDNS");
                                    swarm.behaviour_mut()
                                         .gossipsub
                                         .remove_explicit_peer(&peer_id);

                                    // Only the *sighting* lapses here. A peer
                                    // whose mDNS record expired while the TCP
                                    // connection is still up is still somewhere
                                    // to send to, and dropping it from
                                    // `deliverable` would report a route as
                                    // gone while it still works.
                                    let mut guard = shared_clone.lock().await;
                                    guard.discovered_peers.remove(&peer_id);
                                    let reach = guard.reach();
                                    drop(guard);
                                    let _ = reach_tx.send(reach);
                                }
                            }

                            // A peer telling us it subscribes to the sealed-mail
                            // topic is the *only* event that means "a message
                            // can go out now" — gossipsub publishes to peers it
                            // knows are subscribed, and to nobody else.
                            SwarmEvent::Behaviour(ComradeBehaviourEvent::Gossipsub(
                                gossipsub::Event::Subscribed { peer_id, topic }
                            )) if topic == dak_topic_hash => {
                                info!(peer = %peer_id, "Saathi: peer can now receive sealed mail");
                                let mut guard = shared_clone.lock().await;
                                guard.deliverable_peers.insert(peer_id);
                                // Public chatter cached while nobody was around
                                // goes out now, for the same reason: this is the
                                // first moment it can land.
                                while let Some(cached_msg) = guard.outbox_cache.pop_front() {
                                    let bytes = match serde_json::to_vec(&cached_msg) {
                                        Ok(b)  => b,
                                        Err(e) => {
                                            warn!("Saathi: cache drain serialise fail: {e}");
                                            continue;
                                        }
                                    };
                                    let topic = IdentTopic::new(TOPIC_NAME);
                                    if let Err(e) = swarm.behaviour_mut().gossipsub.publish(topic, bytes) {
                                        warn!("Saathi: cache drain publish fail: {e}");
                                    } else {
                                        debug!("Saathi: cached message drained to network");
                                    }
                                }
                                let reach = guard.reach();
                                drop(guard);
                                let _ = reach_tx.send(reach);
                            }

                            SwarmEvent::Behaviour(ComradeBehaviourEvent::Gossipsub(
                                gossipsub::Event::Unsubscribed { peer_id, topic }
                            )) if topic == dak_topic_hash => {
                                debug!(peer = %peer_id, "Saathi: peer stopped taking sealed mail");
                                let mut guard = shared_clone.lock().await;
                                guard.deliverable_peers.remove(&peer_id);
                                let reach = guard.reach();
                                drop(guard);
                                let _ = reach_tx.send(reach);
                            }

                            // The connection going is the one thing gossipsub
                            // will not send an Unsubscribed for, so without this
                            // a peer that walked out of range would stay counted
                            // as deliverable until it came back.
                            SwarmEvent::ConnectionClosed { peer_id, num_established: 0, .. } => {
                                debug!(peer = %peer_id, "Saathi: peer connection closed");
                                let mut guard = shared_clone.lock().await;
                                guard.deliverable_peers.remove(&peer_id);
                                let reach = guard.reach();
                                drop(guard);
                                let _ = reach_tx.send(reach);
                            }

                            SwarmEvent::Behaviour(ComradeBehaviourEvent::Gossipsub(
                                gossipsub::Event::Message { message, .. }
                            )) if message.topic == IdentTopic::new(DAK_TOPIC_NAME).hash() => {
                                // Sealed mail. The engine deliberately does not
                                // try to open it: it has no keys and no business
                                // knowing whether a frame is ours. It hands every
                                // frame up, and the dak layer decides.
                                match Envelope::decode(&message.data) {
                                    Ok(envelope) => {
                                        if sealed_tx.send(envelope).await.is_err() {
                                            warn!("Saathi: sealed receiver dropped, stopping driver");
                                            break;
                                        }
                                    }
                                    Err(e) => {
                                        debug!("Saathi: undecodable sealed frame: {e}");
                                    }
                                }
                            }

                            SwarmEvent::Behaviour(ComradeBehaviourEvent::Gossipsub(
                                gossipsub::Event::Message { message, .. }
                            )) => {
                                match serde_json::from_slice::<MeshMessage>(&message.data) {
                                    Ok(msg) => {
                                        debug!(
                                            sender    = %msg.sender,
                                            content   = %msg.content,
                                            "Saathi mesh message received"
                                        );
                                        let mut guard = shared_clone.lock().await;
                                        guard.received.push(msg.clone());
                                        drop(guard);
                                        if msg_tx.send(msg).await.is_err() {
                                            warn!("Saathi: receiver dropped, stopping driver");
                                            break;
                                        }
                                    }
                                    Err(e) => {
                                        warn!("Saathi: failed to deserialise mesh message: {e}");
                                    }
                                }
                            }

                            SwarmEvent::NewListenAddr { address, .. } => {
                                info!(addr = %address, "Saathi: listening on");
                            }

                            _ => {}
                        }
                    }
                }
            }
        });

        Ok(Self {
            cmd_tx,
            msg_rx: Arc::new(Mutex::new(msg_rx)),
            sealed_rx: Arc::new(Mutex::new(sealed_rx)),
            shared,
            local_id: sender_label,
            local_peer_id,
            reach_rx,
        })
    }

    /// Broadcast a plaintext message to all mesh peers.
    /// If no peers are currently reachable the message is cached locally.
    pub async fn broadcast(&self, content: impl Into<String>) -> Result<(), SaathiError> {
        let msg = MeshMessage::new(self.local_id.clone(), content);
        self.cmd_tx
            .send(SaathiCmd::Broadcast(msg))
            .await
            .map_err(|_| SaathiError::BroadcastFailed("swarm task terminated".into()))
    }

    /// Publish a **sealed** envelope to every peer on the local network.
    ///
    /// The frame is opaque: peers see a rotating tag and ciphertext, and only
    /// the addressee can open it. Returns an error when gossipsub has nobody to
    /// publish to (no mesh peers yet) or rejects the frame — the caller's
    /// outbox is the retry mechanism, so this reports rather than caches.
    ///
    /// # Errors
    /// [`SaathiError::BroadcastFailed`] if the swarm task is gone or gossipsub
    /// refused the publish (most often `InsufficientPeers`: nobody else is on
    /// the network yet).
    pub async fn publish_sealed(&self, envelope: &Envelope) -> Result<(), SaathiError> {
        let (ack, wait) = tokio::sync::oneshot::channel();
        self.cmd_tx
            .send(SaathiCmd::PublishSealed {
                bytes: envelope.encode(),
                ack,
            })
            .await
            .map_err(|_| SaathiError::BroadcastFailed("swarm task terminated".into()))?;
        match wait.await {
            Ok(Ok(())) => Ok(()),
            Ok(Err(e)) => Err(SaathiError::BroadcastFailed(e)),
            Err(_) => Err(SaathiError::BroadcastFailed(
                "swarm task dropped the publish".into(),
            )),
        }
    }

    /// Receive the next sealed envelope seen on the network (blocks until one
    /// arrives). Every frame is surfaced, addressed to us or not — deciding
    /// that requires keys the engine does not have. Pass each one to
    /// [`crate::dak::open_dm`].
    pub async fn recv_sealed(&self) -> Option<Envelope> {
        self.sealed_rx.lock().await.recv().await
    }

    /// Receive the next incoming mesh message (blocks until one arrives).
    pub async fn recv_message(&self) -> Option<MeshMessage> {
        self.msg_rx.lock().await.recv().await
    }

    /// Snapshot of all messages received so far.
    pub async fn all_received(&self) -> Vec<MeshMessage> {
        self.shared.lock().await.received.clone()
    }

    /// Number of messages still sitting in the offline outbox cache.
    pub async fn cached_count(&self) -> usize {
        self.shared.lock().await.outbox_cache.len()
    }

    /// Gracefully shut down the swarm driver.
    pub async fn shutdown(&self) {
        let _ = self.cmd_tx.send(SaathiCmd::Shutdown).await;
    }

    pub fn local_peer_label(&self) -> &str {
        &self.local_id
    }

    /// This node's libp2p identity, as seen by mesh peers.
    pub fn local_peer_id(&self) -> PeerId {
        self.local_peer_id
    }

    /// How many peers a sealed publish would actually reach right now — a
    /// cheap, non-blocking snapshot. Use [`Self::reach_stream`] to react to
    /// changes instead of polling.
    ///
    /// This counts peers subscribed to the sealed-mail topic, **not** peers
    /// mDNS has merely announced. The two differ for the seconds it takes to
    /// dial and handshake, and a UI that shows the larger number promises a
    /// delivery the transport cannot yet make — see [`MeshReach`].
    pub fn peer_count(&self) -> usize {
        self.reach_rx.borrow().deliverable
    }

    /// The full local-network picture: who has been seen, and who can be
    /// delivered to. [`Self::peer_count`] is the second of those.
    pub fn reach(&self) -> MeshReach {
        *self.reach_rx.borrow()
    }

    /// Subscribe to live changes in local-network reach. The returned receiver
    /// immediately yields the current state, then the new one each time a peer
    /// is discovered, becomes deliverable, or drops off.
    ///
    /// Watch this rather than polling: a rise in `deliverable` is the moment
    /// queued mail becomes sendable, which is what the outbox flush hangs off.
    pub fn reach_stream(&self) -> watch::Receiver<MeshReach> {
        self.reach_rx.clone()
    }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn peer_count_starts_at_zero() {
        let engine = SaathiEngine::new("test-peer")
            .await
            .expect("engine should initialise");
        assert_eq!(engine.peer_count(), 0);
        assert_eq!(engine.reach(), MeshReach::default());
        assert!(!engine.reach_stream().borrow().can_deliver());
        engine.shutdown().await;
    }

    /// The distinction the send path depends on, as a table.
    ///
    /// This is the shape of the bug two phones on one WiFi actually hit: mDNS
    /// announced the other device, the indicator lit up, and every send still
    /// failed with `InsufficientPeers` because the gossipsub subscription had
    /// not landed yet. "Seen" and "can be sent to" are different questions and
    /// the type refuses to conflate them.
    #[test]
    fn being_seen_on_the_network_is_not_the_same_as_being_reachable() {
        let nobody = MeshReach::default();
        assert!(!nobody.can_deliver());
        assert!(
            !nobody.is_connecting(),
            "an empty network is not connecting"
        );

        let mid_handshake = MeshReach {
            discovered: 1,
            deliverable: 0,
        };
        assert!(!mid_handshake.can_deliver(), "a send here would fail");
        assert!(mid_handshake.is_connecting(), "and this is why — say so");

        let ready = MeshReach {
            discovered: 1,
            deliverable: 1,
        };
        assert!(ready.can_deliver());
        assert!(!ready.is_connecting());

        // A peer whose mDNS record lapsed while the connection stayed up is
        // still somewhere to send to, so this is a legitimate state, not a
        // contradiction to assert against.
        let stale_record = MeshReach {
            discovered: 0,
            deliverable: 1,
        };
        assert!(stale_record.can_deliver());
        assert!(!stale_record.is_connecting());
    }

    /// Two engines started in-process must discover each other over real mDNS
    /// multicast, proving the `Discovered`/`Expired` swarm-event handlers
    /// actually drive `peer_count` end to end (not just the plumbing).
    #[tokio::test]
    #[ignore = "needs real mDNS multicast and a Gossipsub graft; \
        environment-dependent, so it runs in its own CI gate — see \
        the saathi-mesh job in ci.yml"]
    async fn two_engines_discover_each_other_via_mdns() {
        let a = SaathiEngine::new("test-peer-a")
            .await
            .expect("engine a should initialise");
        let b = SaathiEngine::new("test-peer-b")
            .await
            .expect("engine b should initialise");

        async fn wait_until_deliverable(mut rx: watch::Receiver<MeshReach>) {
            while !rx.borrow().can_deliver() {
                rx.changed()
                    .await
                    .expect("swarm driver task should stay alive");
            }
        }

        tokio::time::timeout(Duration::from_secs(20), async {
            tokio::join!(
                wait_until_deliverable(a.reach_stream()),
                wait_until_deliverable(b.reach_stream()),
            )
        })
        .await
        .expect("engines should reach each other over mDNS + gossipsub within 20s");

        // `>= 1`, not `== 1`: mDNS discovery is machine-wide, so any other
        // engine alive on this host — another test running concurrently, a real
        // Comrade on the same laptop — is a legitimate extra peer. The exact
        // form used to pass only because this was the process's only mesh
        // test; it asserted a property of the test harness, not of the code
        // under test.
        //
        // What matters is that this is now the *deliverable* count: reaching it
        // means the dial, the Noise handshake and the topic subscription all
        // completed, so a send would succeed. Waiting on the old discovery
        // count proved only that a multicast packet arrived.
        assert!(a.peer_count() >= 1, "a cannot deliver to anyone");
        assert!(b.peer_count() >= 1, "b cannot deliver to anyone");
        assert!(a.reach().discovered >= a.reach().deliverable);

        a.shutdown().await;
        b.shutdown().await;
    }

    /// The whole point of the sealed topic: two devices on one WiFi exchange a
    /// private message with **no relay and no internet**, and a third device on
    /// the same network cannot read it.
    ///
    /// Real mDNS discovery + real Gossipsub, in-process. This is the closest a
    /// unit test gets to two phones on a café network.
    #[tokio::test]
    #[ignore = "needs real mDNS multicast and a Gossipsub graft; \
        environment-dependent, so it runs in its own CI gate — see \
        the saathi-mesh job in ci.yml"]
    async fn two_devices_on_one_network_exchange_a_sealed_dm() {
        use crate::{crypto::KeyProfile, dak};

        let alice_keys = KeyProfile::generate().unwrap().keys;
        let bob_keys = KeyProfile::generate().unwrap().keys;
        let eve_keys = KeyProfile::generate().unwrap().keys;

        let alice = SaathiEngine::new("alice").await.expect("alice engine");
        let bob = SaathiEngine::new("bob").await.expect("bob engine");
        let eve = SaathiEngine::new("eve").await.expect("eve engine");

        // Wait for the gossipsub mesh to form. Peer discovery is mDNS, so this
        // is genuinely asynchronous; publishing before a peer is subscribed
        // fails with InsufficientPeers, which is what the retry below rides out.
        async fn wait_for_peers(engine: &SaathiEngine, want: usize) {
            let mut rx = engine.reach_stream();
            while rx.borrow().deliverable < want {
                rx.changed().await.expect("driver alive");
            }
        }
        tokio::time::timeout(Duration::from_secs(30), async {
            tokio::join!(
                wait_for_peers(&alice, 2),
                wait_for_peers(&bob, 2),
                wait_for_peers(&eve, 2)
            )
        })
        .await
        .expect("three engines should find each other over mDNS");

        let now = 1_700_000_000;
        let dm = dak::MeshDm::new("queued:lan", "no signal here, but you are", None, now);
        let frame = dak::seal_dm(&bob_keys.public_key(), &alice_keys, &dm, now).expect("seal");

        // Gossipsub needs its mesh grafted before a publish lands; retry
        // briefly rather than asserting on the first attempt.
        tokio::time::timeout(Duration::from_secs(30), async {
            loop {
                if alice.publish_sealed(&frame).await.is_ok() {
                    return;
                }
                tokio::time::sleep(Duration::from_millis(250)).await;
            }
        })
        .await
        .expect("alice should get the frame onto the mesh");

        let received = tokio::time::timeout(Duration::from_secs(30), bob.recv_sealed())
            .await
            .expect("bob should see the frame")
            .expect("stream open");

        let opened = dak::open_dm(&bob_keys, &received, now)
            .expect("well-formed")
            .expect("addressed to bob");
        assert_eq!(opened.dm.content, "no signal here, but you are");
        assert_eq!(
            opened.sender,
            alice_keys.public_key(),
            "the inner MAC must identify the real sender"
        );

        // Eve is on the same network and sees the same frame — and cannot read
        // it. (She may or may not have received it yet; what matters is that
        // opening is impossible, so check the frame she would see.)
        assert_eq!(
            dak::open_dm(&eve_keys, &received, now).expect("not an error"),
            None,
            "a bystander on the LAN must not be able to open it"
        );

        alice.shutdown().await;
        bob.shutdown().await;
        eve.shutdown().await;
    }

    #[tokio::test]
    async fn publishing_sealed_mail_with_nobody_around_fails_rather_than_silently_vanishing() {
        use crate::{crypto::KeyProfile, dak};

        let lonely = SaathiEngine::new("lonely").await.expect("engine");
        let now = 1_700_000_000;
        let frame = dak::seal_dm(
            &KeyProfile::generate().unwrap().keys.public_key(),
            &KeyProfile::generate().unwrap().keys,
            &dak::MeshDm::new("queued:x", "anyone?", None, now),
            now,
        )
        .expect("seal");

        // No peers subscribed yet, so gossipsub has nowhere to send it. The
        // caller must learn that, because its outbox is what retries.
        assert!(
            lonely.publish_sealed(&frame).await.is_err(),
            "a publish into an empty mesh must report failure, not pretend to send"
        );
        lonely.shutdown().await;
    }
}
