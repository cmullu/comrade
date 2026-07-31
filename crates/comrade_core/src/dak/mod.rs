/*!
 * dak (डाक — *post, mail*) — the store-and-forward layer.
 *
 * "The recipient is not here right now" is the hardest problem in a serverless
 * messenger, and bitchat's answer (whitepaper §6) is the most complete one in
 * the open: a persistent sender outbox, opportunistic couriers with a
 * spray-and-wait copy budget, gossip-synced public history, and relay
 * mailboxes. Comrade had only the last of those — a relay holds a gift-wrapped
 * DM until the recipient reconnects, and if the *sender* is the one who is
 * offline, the message is simply lost.
 *
 * This module adds the other layers, as pure policy types with no I/O:
 *
 * | | what it solves | bitchat source |
 * |---|---|---|
 * | [`outbox`] | our send failed — retry it later, or fail visibly | §6.1 sender outbox |
 * | [`mesh`] | there is no internet, but the recipient is on this WiFi | §4 BLE mesh, on a different radio |
 * | [`courier`] | nobody can reach the recipient — hand sealed mail to someone who might | §6.2 couriers |
 * | [`crate::gcs`] | we were away and missed public history | §6.3 gossip sync |
 *
 * Naming follows the codebase's convention of Hindi terms for
 * product-level concepts (a public post is a *Chitthi*, a letter; the mesh is
 * *Saathi*, a companion): **dak** is the post, and a courier is a *dakiya*.
 *
 * Both stores are `snapshot`/`from_snapshot` serialisable so they persist inside
 * the existing Argon2id + AES-256-GCM store rather than needing a second
 * at-rest scheme, and both have a `clear` that the panic wipe calls.
 */

pub mod courier;
pub mod mesh;
pub mod outbox;

pub use courier::{
    candidate_tags, epoch_day, open, recipient_tag, seal, seal_envelope, CarriedEnvelope,
    CourierSnapshot, CourierStore, DepositTier, Envelope, Opened,
};
pub use mesh::{open_dm, seal_dm, MeshDm, OpenedDm};
pub use outbox::{AttemptOutcome, Outbox, OutboxSnapshot, QueueOutcome, QueuedMessage};
