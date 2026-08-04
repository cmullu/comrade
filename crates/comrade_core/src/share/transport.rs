/*!
 * Transport policy for bulk transfer — which paths may carry a file, and how
 * fast to push into one.
 *
 * Kept separate from [`super`]'s chunking and assembly on purpose: the transfer
 * logic must not know whether it is running direct-only or relay-permitted, so
 * changing the policy never touches the code that moves bytes. Everything here
 * is pure and decided from values a frontend can read off an
 * `RTCPeerConnection`, so both frontends inherit one answer instead of
 * implementing two.
 *
 * ## Why WebRTC and not a transport of our own
 * A file has to reach someone in another city, which rules out the mDNS mesh,
 * and it must not pass through a third party, which rules out the Blossom media
 * path. WebRTC is the only transport in the app that is already peer-to-peer,
 * already encrypted (DTLS/SCTP), already has NAT traversal, and is already a
 * dependency on both frontends. Building a second bulk protocol over libpeer
 * plumbing would duplicate all of that and still not solve NAT.
 *
 * ## The relay problem, which is the whole reason this module exists
 * `AUDIT.md` §8.1 measures it: STUN alone finds a direct path for perhaps
 * 60-70% of real-world pairs, and the rest — CGNAT, very common on Indian
 * mobile carriers — need a TURN relay. That relay is *our own* server
 * (`deploy/coturn/`), and pushing a film through it would mean:
 *
 * - **cost**: every byte billed twice, in and out, unbounded per session;
 * - **privacy**: the operator's machine sits in the path of content it has no
 *   business carrying, and sees the IP pair while doing it;
 * - **§8.2**: "do not proxy media between users" is precisely what a relayed
 *   bulk transfer is, however encrypted the bytes are.
 *
 * So the default is [`RelayPolicy::DirectOnly`], and it is enforced twice.
 * First structurally: a transfer connection built under that policy is given
 * **no TURN servers at all** ([`ice_servers_allowed`]), so a relay candidate
 * cannot be gathered, let alone selected. Then again after connection, by
 * inspecting the selected candidate pair — because a policy that is only
 * enforced at configuration time is one nobody can verify from a log.
 *
 * ## What that costs, stated plainly
 * Roughly a third of remote pairs will not get a direct path, and for them a
 * direct-only transfer simply does not happen. That is a real product
 * limitation and the honest answer for those pairs is the substitute-source
 * route (`docs/TOGETHER.md` §9a) — play the same recording from a source each
 * side already has — not a quiet fallback onto the operator's bandwidth.
 */

use serde::{Deserialize, Serialize};

/// The kind of path ICE actually selected, from the candidate pair.
///
/// `prflx` (peer-reflexive) is folded into [`Self::ServerReflexive`]: both are
/// direct paths discovered through a NAT, and the distinction matters to ICE
/// but not to the question this module asks, which is only "is anyone else
/// carrying these bytes".
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "snake_case")]
pub enum IcePathKind {
    /// Same network — the two devices are talking directly over the LAN.
    Host,
    /// Direct across NAT, discovered via STUN. Still nobody in the middle.
    ServerReflexive,
    /// Through a TURN relay. Someone else is carrying every byte.
    Relay,
    /// Not connected yet, or the candidate types could not be read.
    Unknown,
}

impl IcePathKind {
    /// Classify a selected candidate pair from the two candidate types as ICE
    /// spells them (`host`, `srflx`, `prflx`, `relay`).
    ///
    /// **Either end being a relay makes the path relayed** — a pair is only
    /// direct if both halves are. This is the conservative reading and it is
    /// the correct one: if the remote is a relay candidate, our packets reach
    /// the peer by way of their TURN server, which is exactly the case this
    /// policy exists to catch.
    pub fn classify(local_type: &str, remote_type: &str) -> Self {
        let kind = |t: &str| match t.trim().to_ascii_lowercase().as_str() {
            "host" => Some(Self::Host),
            "srflx" | "prflx" => Some(Self::ServerReflexive),
            "relay" => Some(Self::Relay),
            _ => None,
        };
        match (kind(local_type), kind(remote_type)) {
            (Some(Self::Relay), _) | (_, Some(Self::Relay)) => Self::Relay,
            (Some(a), Some(b)) => {
                // Host only when both are; otherwise the more general of the two.
                if a == Self::Host && b == Self::Host {
                    Self::Host
                } else {
                    Self::ServerReflexive
                }
            }
            _ => Self::Unknown,
        }
    }

    /// Whether the bytes travel with nobody in between.
    pub fn is_direct(self) -> bool {
        matches!(self, Self::Host | Self::ServerReflexive)
    }
}

/// What a device is willing to do when the only path is a relay.
///
/// Configurable so the *policy* can change without touching the code that moves
/// bytes — the transfer logic asks [`decide`] and does what it is told.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize, uniffi::Enum)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum RelayPolicy {
    /// The default. Never carry bulk over a relay, and do not even offer TURN
    /// to the transfer connection, so the case cannot arise by accident.
    #[default]
    DirectOnly,
    /// Relay small things — a track, a photo — but never a film. The threshold
    /// is what someone is willing to pay for on someone else's behalf.
    UnderBytes { limit: u64 },
    /// Relay anything, but only after the person has been told it is happening
    /// and said yes to this transfer.
    AskEachTime,
    /// Relay anything, silently. Present for completeness; not a default, and
    /// the UI should not make it a one-tap choice.
    Always,
}

/// Why a transfer will not proceed as-is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum RefusalReason {
    /// The only path is a relay and this device does not relay bulk.
    RelayForbidden,
    /// The only path is a relay and this is bigger than the relay allowance.
    TooLargeForRelay { limit: u64 },
    /// ICE has not settled, so there is nothing to judge yet.
    PathUnknown,
}

/// What to do about a transfer, given the path it would take.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum TransferVerdict {
    /// Go ahead.
    Allow,
    /// The path is a relay and the policy wants a human to agree first. The
    /// size is carried so the question can name it.
    NeedsConsent { relayed_bytes: u64 },
    /// Not happening; tell the person why.
    Refuse { reason: RefusalReason },
}

/// The whole policy, in one pure function so both frontends inherit one answer.
///
/// A direct path is always allowed regardless of policy — the policy is about
/// *relays*, and there is nothing to protect anyone from when the bytes go
/// straight from one device to the other.
pub fn decide(path: IcePathKind, total_bytes: u64, policy: RelayPolicy) -> TransferVerdict {
    if path.is_direct() {
        return TransferVerdict::Allow;
    }
    if path == IcePathKind::Unknown {
        // Refusing rather than assuming: "we could not tell" must never be
        // read as "it was fine". The caller retries once ICE settles.
        return TransferVerdict::Refuse {
            reason: RefusalReason::PathUnknown,
        };
    }
    match policy {
        RelayPolicy::DirectOnly => TransferVerdict::Refuse {
            reason: RefusalReason::RelayForbidden,
        },
        RelayPolicy::UnderBytes { limit } => {
            if total_bytes <= limit {
                TransferVerdict::Allow
            } else {
                TransferVerdict::Refuse {
                    reason: RefusalReason::TooLargeForRelay { limit },
                }
            }
        }
        RelayPolicy::AskEachTime => TransferVerdict::NeedsConsent {
            relayed_bytes: total_bytes,
        },
        RelayPolicy::Always => TransferVerdict::Allow,
    }
}

/// [`decide`], plus the answer to the question it may have asked.
///
/// Consent can do exactly one thing: turn [`TransferVerdict::NeedsConsent`]
/// into [`TransferVerdict::Allow`]. It can never move a [`TransferVerdict::Refuse`],
/// and that asymmetry is the point — `consent_granted` arrives from a frontend,
/// which is the least trustworthy input this module takes, and a frontend that
/// passed `true` unconditionally (through a bug, or because someone wired the
/// dialog's dismiss to the wrong branch) would otherwise defeat
/// [`RelayPolicy::DirectOnly`] entirely. Under this shape the worst such a bug
/// can do is skip a question the policy chose to ask.
///
/// Consent is per-call rather than remembered here because this module holds no
/// state: "they said yes" belongs to one transfer, and a yes that outlived its
/// session would be a yes to a file nobody was asked about.
pub fn decide_with_consent(
    path: IcePathKind,
    total_bytes: u64,
    policy: RelayPolicy,
    consent_granted: bool,
) -> TransferVerdict {
    match decide(path, total_bytes, policy) {
        TransferVerdict::NeedsConsent { .. } if consent_granted => TransferVerdict::Allow,
        other => other,
    }
}

/// Whether a transfer connection built under `policy` may be offered TURN.
///
/// This is the *structural* half of the enforcement. Under
/// [`RelayPolicy::DirectOnly`] the transfer `RTCPeerConnection` is configured
/// with STUN only, so a relay candidate is never gathered and the policy holds
/// even if every later check were removed. [`decide`] is then the second line,
/// so the outcome is visible rather than merely true.
pub fn ice_servers_allowed(policy: RelayPolicy) -> bool {
    !matches!(policy, RelayPolicy::DirectOnly)
}

// ── Flow control ─────────────────────────────────────────────────────────────

/// Stop feeding the data channel once this much is queued in it.
///
/// A data channel accepts writes long after it has stopped sending them; the
/// bytes pile up in the SCTP send buffer, and a naive loop over a 2 GB file
/// queues the whole thing in memory and either stalls the connection or is
/// killed for it. One mebibyte is enough to keep the pipe full across a
/// round trip and small enough to be an unnoticeable amount of RAM.
pub const SHARE_BUFFER_HIGH_WATER: u64 = 1024 * 1024;

/// Resume feeding once it has drained to here.
///
/// Set well below the high-water mark rather than just under it: waking on
/// every few bytes drained would mean an event per chunk and a busy loop, which
/// is what `bufferedAmountLowThreshold` exists to avoid. A quarter of the
/// ceiling gives the sender a useful burst each time it wakes.
pub const SHARE_BUFFER_LOW_WATER: u64 = 256 * 1024;

// The hysteresis has to be real, or the sender oscillates between the two marks
// once per chunk and the pause/resume machinery costs more than it saves.
const _: () = assert!(SHARE_BUFFER_LOW_WATER * 2 <= SHARE_BUFFER_HIGH_WATER);

/// How many more chunks may be pushed into a channel holding `buffered` bytes.
///
/// Returns zero when the channel is at or over its ceiling — the caller then
/// waits for the low-water event rather than polling, which is the whole point
/// of `bufferedAmountLowThreshold`.
pub fn chunks_to_send(buffered: u64, chunk_bytes: u32, high_water: u64) -> u32 {
    if chunk_bytes == 0 || buffered >= high_water {
        return 0;
    }
    ((high_water - buffered) / chunk_bytes as u64) as u32
}

/// Whether the sender should pause and wait for the drain event.
pub fn should_pause(buffered: u64, high_water: u64) -> bool {
    buffered >= high_water
}

#[cfg(test)]
mod tests {
    use super::*;

    // ── Classifying the selected pair ───────────────────────────────────────

    #[test]
    fn a_pair_is_only_direct_when_neither_end_is_a_relay() {
        assert_eq!(IcePathKind::classify("host", "host"), IcePathKind::Host);
        assert_eq!(
            IcePathKind::classify("srflx", "host"),
            IcePathKind::ServerReflexive
        );
        assert_eq!(
            IcePathKind::classify("prflx", "srflx"),
            IcePathKind::ServerReflexive,
            "peer-reflexive is still a direct path"
        );
        // Either end being a relay is enough — our packets reach them by way of
        // a server whichever side put it there.
        assert_eq!(IcePathKind::classify("host", "relay"), IcePathKind::Relay);
        assert_eq!(IcePathKind::classify("relay", "srflx"), IcePathKind::Relay);
        assert_eq!(IcePathKind::classify("relay", "relay"), IcePathKind::Relay);
    }

    #[test]
    fn an_unreadable_pair_is_unknown_rather_than_assumed_direct() {
        assert_eq!(IcePathKind::classify("", ""), IcePathKind::Unknown);
        assert_eq!(IcePathKind::classify("host", "wat"), IcePathKind::Unknown);
        assert!(!IcePathKind::Unknown.is_direct());
    }

    #[test]
    fn candidate_types_are_read_case_and_space_insensitively() {
        assert_eq!(IcePathKind::classify(" Host ", "HOST"), IcePathKind::Host);
        assert_eq!(IcePathKind::classify("Relay", "host"), IcePathKind::Relay);
    }

    // ── The policy ──────────────────────────────────────────────────────────

    const FILM: u64 = 2 * 1024 * 1024 * 1024;
    const TRACK: u64 = 8 * 1024 * 1024;

    #[test]
    fn a_direct_path_is_allowed_under_every_policy() {
        for policy in [
            RelayPolicy::DirectOnly,
            RelayPolicy::UnderBytes { limit: 0 },
            RelayPolicy::AskEachTime,
            RelayPolicy::Always,
        ] {
            for path in [IcePathKind::Host, IcePathKind::ServerReflexive] {
                assert_eq!(
                    decide(path, FILM, policy),
                    TransferVerdict::Allow,
                    "nothing to protect anyone from on a direct path: {path:?} {policy:?}"
                );
            }
        }
    }

    #[test]
    fn the_default_refuses_to_put_bulk_through_our_own_relay() {
        assert_eq!(RelayPolicy::default(), RelayPolicy::DirectOnly);
        assert_eq!(
            decide(IcePathKind::Relay, TRACK, RelayPolicy::default()),
            TransferVerdict::Refuse {
                reason: RefusalReason::RelayForbidden
            }
        );
    }

    #[test]
    fn a_size_threshold_lets_a_track_through_and_stops_a_film() {
        let policy = RelayPolicy::UnderBytes {
            limit: 64 * 1024 * 1024,
        };
        assert_eq!(
            decide(IcePathKind::Relay, TRACK, policy),
            TransferVerdict::Allow
        );
        assert_eq!(
            decide(IcePathKind::Relay, FILM, policy),
            TransferVerdict::Refuse {
                reason: RefusalReason::TooLargeForRelay {
                    limit: 64 * 1024 * 1024
                }
            }
        );
        // Exactly at the limit is allowed — a threshold nobody can reach is a
        // worse specification than one that is inclusive.
        assert_eq!(
            decide(IcePathKind::Relay, 64 * 1024 * 1024, policy),
            TransferVerdict::Allow
        );
    }

    #[test]
    fn asking_names_the_size_so_the_question_can_be_specific() {
        assert_eq!(
            decide(IcePathKind::Relay, FILM, RelayPolicy::AskEachTime),
            TransferVerdict::NeedsConsent {
                relayed_bytes: FILM
            }
        );
    }

    #[test]
    fn saying_yes_answers_the_question_that_was_actually_asked() {
        assert_eq!(
            decide_with_consent(IcePathKind::Relay, FILM, RelayPolicy::AskEachTime, true),
            TransferVerdict::Allow
        );
        assert_eq!(
            decide_with_consent(IcePathKind::Relay, FILM, RelayPolicy::AskEachTime, false),
            TransferVerdict::NeedsConsent {
                relayed_bytes: FILM
            },
            "not answering is not the same as saying yes"
        );
    }

    /// The property the whole shape of [`decide_with_consent`] exists to hold:
    /// `consent_granted` comes from a frontend, and a frontend that passed
    /// `true` unconditionally must not be able to defeat the policy. The worst
    /// it can do is skip a question that was going to be asked.
    #[test]
    fn consent_can_never_move_a_refusal() {
        let refusing = [
            (IcePathKind::Relay, RelayPolicy::DirectOnly),
            (IcePathKind::Relay, RelayPolicy::UnderBytes { limit: TRACK }),
            (IcePathKind::Unknown, RelayPolicy::AskEachTime),
            (IcePathKind::Unknown, RelayPolicy::Always),
        ];
        for (path, policy) in refusing {
            let without = decide(path, FILM, policy);
            let with = decide_with_consent(path, FILM, policy, true);
            assert!(
                matches!(without, TransferVerdict::Refuse { .. }),
                "{path:?}/{policy:?} should refuse before consent is considered"
            );
            assert_eq!(
                with, without,
                "{path:?}/{policy:?} changed its answer because a frontend claimed consent"
            );
        }
    }

    /// Consent is not a way to ask for something the policy never questioned.
    #[test]
    fn consent_on_an_already_allowed_transfer_changes_nothing() {
        for policy in [RelayPolicy::DirectOnly, RelayPolicy::AskEachTime] {
            assert_eq!(
                decide_with_consent(IcePathKind::Host, FILM, policy, true),
                TransferVerdict::Allow,
                "a direct path was never the policy's business"
            );
        }
    }

    /// "We could not tell" must never be read as "it was fine".
    #[test]
    fn an_unknown_path_is_refused_rather_than_waved_through() {
        for policy in [
            RelayPolicy::DirectOnly,
            RelayPolicy::Always,
            RelayPolicy::AskEachTime,
        ] {
            assert_eq!(
                decide(IcePathKind::Unknown, TRACK, policy),
                TransferVerdict::Refuse {
                    reason: RefusalReason::PathUnknown
                },
                "policy {policy:?} must not assume an unread path is direct"
            );
        }
    }

    /// The structural half: under the default the connection is never even
    /// offered a relay, so the policy holds without the check above.
    #[test]
    fn direct_only_connections_are_not_given_turn_at_all() {
        assert!(!ice_servers_allowed(RelayPolicy::DirectOnly));
        assert!(ice_servers_allowed(RelayPolicy::UnderBytes { limit: 1 }));
        assert!(ice_servers_allowed(RelayPolicy::AskEachTime));
        assert!(ice_servers_allowed(RelayPolicy::Always));
    }

    // ── Flow control ────────────────────────────────────────────────────────

    #[test]
    fn a_drained_channel_takes_a_full_windows_worth_of_chunks() {
        let chunk = 16 * 1024;
        assert_eq!(
            chunks_to_send(0, chunk, SHARE_BUFFER_HIGH_WATER),
            (SHARE_BUFFER_HIGH_WATER / chunk as u64) as u32
        );
    }

    #[test]
    fn a_full_channel_takes_nothing_and_says_to_wait() {
        let chunk = 16 * 1024;
        assert_eq!(
            chunks_to_send(SHARE_BUFFER_HIGH_WATER, chunk, SHARE_BUFFER_HIGH_WATER),
            0
        );
        assert_eq!(
            chunks_to_send(SHARE_BUFFER_HIGH_WATER + 1, chunk, SHARE_BUFFER_HIGH_WATER),
            0,
            "over the ceiling is not negative room"
        );
        assert!(should_pause(
            SHARE_BUFFER_HIGH_WATER,
            SHARE_BUFFER_HIGH_WATER
        ));
        assert!(!should_pause(
            SHARE_BUFFER_LOW_WATER,
            SHARE_BUFFER_HIGH_WATER
        ));
    }

    #[test]
    fn a_partly_full_channel_takes_only_what_fits() {
        let chunk = 16 * 1024;
        let buffered = SHARE_BUFFER_HIGH_WATER - 3 * chunk as u64;
        assert_eq!(chunks_to_send(buffered, chunk, SHARE_BUFFER_HIGH_WATER), 3);
    }

    #[test]
    fn a_zero_chunk_size_cannot_divide_by_zero() {
        assert_eq!(chunks_to_send(0, 0, SHARE_BUFFER_HIGH_WATER), 0);
    }

    /// The hysteresis has to be wide enough that the sender is not woken once
    /// per chunk — that busy loop is exactly what the low-water event avoids.
    #[test]
    fn the_two_watermarks_leave_room_for_a_real_burst() {
        let burst = chunks_to_send(SHARE_BUFFER_LOW_WATER, 16 * 1024, SHARE_BUFFER_HIGH_WATER);
        assert!(burst >= 8, "waking for {burst} chunks is a busy loop");
    }
}
