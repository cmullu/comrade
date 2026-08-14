/*!
 * Comrade ride — a driver and a pillion on one motorcycle, saying the few
 * things a helmet and an engine make unsayable.
 *
 * Two people on one bike are the closest two Comrade users ever get, and the
 * hardest pair to connect: wind and engine noise bury speech, the driver's
 * hands and eyes are spoken for, and a phone call between two helmets is a
 * worse intercom than a tap on the shoulder. What actually needs to travel is
 * tiny — *slow down*, *fuel soon*, *left at the next junction* — and it needs
 * to land as a glance, not a conversation.
 *
 * A [`RideSignal`] is that vocabulary: a [`RideEnvelope`] riding the same
 * NIP-44/NIP-17 gift-wrapped DM channel as receipts, call signals, presence
 * beacons, nudges and together envelopes ([`crate::dm`], [`crate::together`]),
 * addressed to the one person on the seat behind (or in front of) you.
 *
 * ## One-shot, not a session — deliberately
 * The together feature (`crate::together`) is a session because it defends a
 * *shared state* (one playhead) against feedback. A ride signal has no shared
 * state to defend: each one is complete in itself, acted on once, and stale a
 * minute later. Modelling it as a session would buy Lamport arbitration nobody
 * needs at the cost of a state machine that must be torn down, TTL'd and
 * replay-guarded. The nudge ([`crate::nudge`]) is the shape that fits, and
 * this module mirrors it: marker, TTL from send time, receiver clamp, nothing
 * persisted. Music on a ride needs no wire work at all — a together session
 * already holds two players in step, and the ride surface only decides which
 * of its controls are safe at speed.
 *
 * ## The rules, stated honestly
 * - **A ride signal is a claim about now.** "Left in 400 m" is wrong in the
 *   exact proportion that it is late, and the failure mode of this feature is
 *   a two-day-old backfilled instruction rendered huge on a moving driver's
 *   screen. So a signal expires [`RIDE_TTL_SECS`] after it was *sent* — the
 *   same arithmetic, for the same reasons, as a presence beacon or a nudge:
 *   relays redeliver at-least-once and the vault inbox rewinds two days on
 *   every launch, so a replayed instruction must not raise anything.
 * - **The phrases are a catalog, not free text**, because the reader is doing
 *   80 km/h. A fixed [`RidePhrase`] renders in one glance-sized word pair,
 *   translates once per frontend rather than once per message, and carries its
 *   own [`RideUrgency`] so every frontend buzzes the same way for the same
 *   thing. The one free-text field is the route note, capped at
 *   [`RIDE_NOTE_MAX_CHARS`] — a landmark ("after the petrol pump"), not a
 *   paragraph.
 * - **No coordinates travel.** A route suggestion is what the pillion chose to
 *   say, not where either phone is. GPS positions, headings and speeds are
 *   all things nobody decided to send, and none of them are needed to say
 *   "left at the next junction". This is [`crate::together`]'s
 *   filename argument in another shape, and it is why there is no `lat`/`lon`
 *   in the envelope and no dependency on [`crate::geo`].
 * - **Symmetric on the wire, asymmetric on the screen.** Either seat may send
 *   any signal — who is driving is a fact about the motorcycle, not the
 *   protocol, and a wire role would have to be claimed, verified and torn
 *   down. Each device picks its own presentation (glance layout vs composer)
 *   locally, the way each side of a together session picks its own player.
 *
 * Pure and framework-free like its neighbours: the wire shape, the catalog,
 * the admissibility rules and the TTL arithmetic live here and are
 * unit-tested. Who renders what, haptics, speech and staleness-on-screen live
 * in the frontends' decision modules.
 */

use serde::{Deserialize, Serialize};

/// Envelope marker for a ride signal riding inside a DM body — the same
/// detection pattern [`crate::nudge::NUDGE_ENVELOPE_MARKER`] and
/// [`crate::dm::ProfileShare`] use, so [`parse_ride_envelope`] accepts only
/// its own shape and the inbox dispatcher can fall through to its other
/// handlers.
pub const RIDE_ENVELOPE_MARKER: u8 = 1;

/// How long a ride signal stays believable, measured from its send time.
///
/// One minute, and deliberately tighter than a nudge's: a nudge says "they
/// might need you", which is still true an hour later, while "left in 400 m"
/// is falsified by the road itself in under a minute at riding speed. Tighter
/// than the together channel's 60 s would be pointless precision — this *is*
/// 60 s, aligned with [`crate::together::TOGETHER_SIGNAL_MAX_AGE_SECS`] for
/// the same reason that constant is tight: a replayed signal here is acted on
/// with no confirmation step.
pub const RIDE_TTL_SECS: u64 = 60;

/// Hard ceiling on any peer-supplied TTL. A ride signal is a claim by someone
/// else's device, and without a clamp a buggy or hostile peer could pin
/// "slow down" on a driver's screen for a year. Five minutes is generous —
/// nothing in this vocabulary survives a fuel stop.
pub const RIDE_MAX_TTL_SECS: u64 = 300;

/// Longest route note that may travel or be accepted, in characters.
///
/// The note is a landmark, and the reader is on a moving motorcycle: anything
/// longer than a short phrase is unreadable at a glance *and* a growing
/// temptation to turn the route card into a chat. Cap it at both ends so the
/// limit is a property of the protocol, not a UI truncation.
pub const RIDE_NOTE_MAX_CHARS: usize = 80;

/// Largest distance a route suggestion may carry, in metres.
///
/// 50 km is beyond any "next maneuver" on a ride; a bigger number is either a
/// typo or an itinerary, and an itinerary is a conversation for the next stop,
/// not a glance card.
pub const RIDE_DISTANCE_MAX_M: u32 = 50_000;

// A signal must be able to expire while still fresh by the receiver's clamp,
// or the sender-side staleness rule and the receiver's would disagree about
// who drops it.
const _: () = assert!(RIDE_TTL_SECS <= RIDE_MAX_TTL_SECS);

/// How hard a signal should interrupt whoever is reading it.
///
/// Decided here, once, rather than per frontend: two phones disagreeing about
/// whether "pull over" is worth a buzz is two riders disagreeing about what
/// was said.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RideUrgency {
    /// Act now — worth a strong haptic and being spoken aloud.
    Urgent,
    /// Plan for it in the next few minutes.
    Notice,
    /// Reassurance; interrupt nothing.
    Info,
}

impl RideUrgency {
    /// The name frontends receive over the bridge. Not a serde encoding —
    /// urgency never travels between devices, it is derived on arrival — but
    /// pinned to these three strings so every frontend's decision table keys
    /// on the same words.
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Urgent => "urgent",
            Self::Notice => "notice",
            Self::Info => "info",
        }
    }
}

/// The fixed vocabulary two riders need between stops.
///
/// Small on purpose. Every phrase earns its place by being something a tap on
/// the shoulder cannot say precisely, and the catalog grows by adding a
/// variant here — which updates every frontend's exhaustive match — rather
/// than by free text, which would put a paragraph in front of someone at
/// 80 km/h.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RidePhrase {
    /// You're going too fast for the seat behind you.
    SlowDown,
    /// Stop the bike as soon as it is safe.
    PullOver,
    /// The tank (or a bladder) has a horizon.
    FuelSoon,
    /// A rest is being asked for, not an emergency.
    BreakPlease,
    /// The answer to a glance in the mirror.
    AllGood,
}

impl RidePhrase {
    /// The wire name — identical to the serde encoding, pinned by a test, so
    /// frontends that speak in strings (FFI, decision modules) and the JSON on
    /// the wire can never disagree.
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::SlowDown => "slow_down",
            Self::PullOver => "pull_over",
            Self::FuelSoon => "fuel_soon",
            Self::BreakPlease => "break_please",
            Self::AllGood => "all_good",
        }
    }

    /// Parse a wire name back to the phrase. `None` for anything else — an
    /// unknown phrase from a newer build is dropped whole rather than shown
    /// blank, because a glance card with nothing on it still interrupts.
    pub fn parse(raw: &str) -> Option<Self> {
        Some(match raw {
            "slow_down" => Self::SlowDown,
            "pull_over" => Self::PullOver,
            "fuel_soon" => Self::FuelSoon,
            "break_please" => Self::BreakPlease,
            "all_good" => Self::AllGood,
            _ => return None,
        })
    }

    /// How hard this phrase interrupts. Exhaustive so a new phrase cannot be
    /// added without deciding.
    pub const fn urgency(self) -> RideUrgency {
        match self {
            Self::SlowDown | Self::PullOver => RideUrgency::Urgent,
            Self::FuelSoon | Self::BreakPlease => RideUrgency::Notice,
            Self::AllGood => RideUrgency::Info,
        }
    }
}

/// What the road asks next. The vocabulary of a pillion navigating from a map
/// on their own phone — a *suggestion* to the person steering, never an
/// instruction from a routing engine, because there is no routing engine and
/// claiming one's authority would be borrowing a confidence nobody measured.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RideManeuver {
    Left,
    Right,
    Straight,
    UTurn,
    /// Stop at the named place — the destination, or the landmark in the note.
    Stop,
    /// Something on the road surface or ahead: pothole, cattle, police check.
    /// The note says which; the urgency does not depend on it.
    Hazard,
}

impl RideManeuver {
    /// The wire name — identical to the serde encoding, pinned by a test.
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Left => "left",
            Self::Right => "right",
            Self::Straight => "straight",
            Self::UTurn => "u_turn",
            Self::Stop => "stop",
            Self::Hazard => "hazard",
        }
    }

    /// Parse a wire name back to the maneuver. `None` for anything else.
    pub fn parse(raw: &str) -> Option<Self> {
        Some(match raw {
            "left" => Self::Left,
            "right" => Self::Right,
            "straight" => Self::Straight,
            "u_turn" => Self::UTurn,
            "stop" => Self::Stop,
            "hazard" => Self::Hazard,
            _ => return None,
        })
    }

    /// A hazard interrupts like "slow down" does, because it is one; every
    /// other maneuver is a plan, not an interruption.
    pub const fn urgency(self) -> RideUrgency {
        match self {
            Self::Hazard => RideUrgency::Urgent,
            _ => RideUrgency::Notice,
        }
    }
}

/// One thing one rider says to the other.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum RideSignal {
    /// A catalog phrase — see [`RidePhrase`] for why there is no free text.
    Quick { phrase: RidePhrase },
    /// A route suggestion: what to do, optionally how far away, optionally at
    /// which landmark.
    Route {
        maneuver: RideManeuver,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        distance_m: Option<u32>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        note: Option<String>,
    },
}

impl RideSignal {
    /// Whether this signal may travel — checked on send *and* receive, the
    /// [`crate::together::TogetherContent::admissible`] pattern: matching
    /// exhaustively so a new variant carrying a peer-chosen string cannot be
    /// wired through three frontends without this function noticing.
    ///
    /// The note cap counts characters rather than bytes so the limit means the
    /// same thing in Devanagari as in ASCII.
    pub fn admissible(&self) -> bool {
        match self {
            Self::Quick { .. } => true,
            Self::Route {
                distance_m, note, ..
            } => {
                distance_m.is_none_or(|d| d <= RIDE_DISTANCE_MAX_M)
                    && note.as_deref().is_none_or(|n| {
                        !n.trim().is_empty() && n.chars().count() <= RIDE_NOTE_MAX_CHARS
                    })
            }
        }
    }

    /// How hard this signal interrupts, wherever it came from.
    pub const fn urgency(&self) -> RideUrgency {
        match self {
            Self::Quick { phrase } => phrase.urgency(),
            Self::Route { maneuver, .. } => maneuver.urgency(),
        }
    }
}

/// The JSON envelope carried inside an E2E DM between two riders.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RideEnvelope {
    /// Format marker / version; must equal [`RIDE_ENVELOPE_MARKER`].
    pub comrade_ride: u8,
    /// How many seconds this signal stays worth showing, clamped by the
    /// receiver to [`RIDE_MAX_TTL_SECS`].
    pub ttl_secs: u64,
    /// What is being said.
    pub signal: RideSignal,
}

impl RideEnvelope {
    /// Wrap one signal for the wire with the standard TTL.
    pub fn new(signal: RideSignal) -> Self {
        Self {
            comrade_ride: RIDE_ENVELOPE_MARKER,
            ttl_secs: RIDE_TTL_SECS,
            signal,
        }
    }

    /// Serialise to the JSON string that becomes the DM body.
    pub fn to_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }
}

/// Detect and parse a ride envelope out of a decrypted DM body. Returns `None`
/// for chat text, every other control envelope, malformed JSON, an unknown
/// phrase or maneuver (release skew — see [`RidePhrase::parse`]), and any
/// signal [`RideSignal::admissible`] refuses, so a peer-supplied string can
/// never reach a screen this module did not size it for.
pub fn parse_ride_envelope(content: &str) -> Option<RideEnvelope> {
    let env: RideEnvelope = serde_json::from_str(content).ok()?;
    (env.comrade_ride == RIDE_ENVELOPE_MARKER && env.signal.admissible()).then_some(env)
}

/// When a ride signal sent at `created_at` stops being worth showing.
///
/// Measured from the *send* time, not arrival, exactly like
/// [`crate::nudge::nudge_expires_at`] — a signal delayed by a slow relay is
/// already partly spent, and one replayed out of a days-old backfill is
/// entirely spent. Freshness reads go through
/// [`crate::nudge::is_fresh_at`], the same single rule nudges use.
pub fn ride_expires_at(created_at: u64, ttl_secs: u64) -> u64 {
    created_at.saturating_add(ttl_secs.min(RIDE_MAX_TTL_SECS))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::nudge::is_fresh_at;

    #[test]
    fn a_quick_signal_round_trips_and_carries_exactly_its_stated_fields() {
        let env = RideEnvelope::new(RideSignal::Quick {
            phrase: RidePhrase::SlowDown,
        });
        let json = env.to_json().unwrap();
        // The exact key set is the privacy claim: no position, no speed, no
        // heading, no identity beyond the DM that carries it. Adding a field
        // fails this test, which is the point — see the together envelope's
        // twin of this assertion.
        let value: serde_json::Value = serde_json::from_str(&json).unwrap();
        let mut keys: Vec<&str> = value
            .as_object()
            .unwrap()
            .keys()
            .map(|k| k.as_str())
            .collect();
        keys.sort_unstable();
        assert_eq!(keys, ["comrade_ride", "signal", "ttl_secs"]);
        let mut signal_keys: Vec<&str> = value["signal"]
            .as_object()
            .unwrap()
            .keys()
            .map(|k| k.as_str())
            .collect();
        signal_keys.sort_unstable();
        assert_eq!(signal_keys, ["kind", "phrase"]);
        assert_eq!(parse_ride_envelope(&json).unwrap(), env);
    }

    #[test]
    fn a_route_signal_round_trips_with_and_without_its_optional_fields() {
        let bare = RideEnvelope::new(RideSignal::Route {
            maneuver: RideManeuver::Left,
            distance_m: None,
            note: None,
        });
        let json = bare.to_json().unwrap();
        // Absent options are absent keys, not nulls — a receiver must not
        // learn "they chose to say nothing" as a distinct value.
        assert!(!json.contains("distance_m") && !json.contains("note"));
        assert_eq!(parse_ride_envelope(&json).unwrap(), bare);

        let full = RideEnvelope::new(RideSignal::Route {
            maneuver: RideManeuver::Stop,
            distance_m: Some(400),
            note: Some("after the petrol pump".into()),
        });
        assert_eq!(parse_ride_envelope(&full.to_json().unwrap()).unwrap(), full);
    }

    #[test]
    fn the_wire_names_and_the_string_names_are_the_same_names() {
        // FFI callers speak `as_str` strings and the wire speaks serde: if the
        // two ever diverge, a phrase picked on one phone is an unknown phrase
        // on the other. Round-trip every variant through both.
        for phrase in [
            RidePhrase::SlowDown,
            RidePhrase::PullOver,
            RidePhrase::FuelSoon,
            RidePhrase::BreakPlease,
            RidePhrase::AllGood,
        ] {
            let serde_name = serde_json::to_value(phrase).unwrap();
            assert_eq!(serde_name, phrase.as_str());
            assert_eq!(RidePhrase::parse(phrase.as_str()), Some(phrase));
        }
        for maneuver in [
            RideManeuver::Left,
            RideManeuver::Right,
            RideManeuver::Straight,
            RideManeuver::UTurn,
            RideManeuver::Stop,
            RideManeuver::Hazard,
        ] {
            let serde_name = serde_json::to_value(maneuver).unwrap();
            assert_eq!(serde_name, maneuver.as_str());
            assert_eq!(RideManeuver::parse(maneuver.as_str()), Some(maneuver));
        }
        assert_eq!(RidePhrase::parse("faster"), None);
        assert_eq!(RideManeuver::parse("north"), None);
    }

    #[test]
    fn other_envelopes_and_chat_text_are_not_ride_signals() {
        assert!(parse_ride_envelope("left at the junction").is_none());
        assert!(parse_ride_envelope(r#"{"comrade_nudge":1,"ttl_secs":480}"#).is_none());
        assert!(parse_ride_envelope(
            r#"{"comrade_together":1,"session_id":"s","seq":1,"at_ms":0,"signal":{"kind":"end"}}"#
        )
        .is_none());
        // Wrong marker version: refused, not best-effort.
        assert!(parse_ride_envelope(
            r#"{"comrade_ride":2,"ttl_secs":60,"signal":{"kind":"quick","phrase":"all_good"}}"#
        )
        .is_none());
    }

    #[test]
    fn an_unknown_phrase_or_maneuver_is_dropped_whole_not_shown_blank() {
        // Release skew: a newer build's phrase reaching an older one.
        assert!(parse_ride_envelope(
            r#"{"comrade_ride":1,"ttl_secs":60,"signal":{"kind":"quick","phrase":"warp_speed"}}"#
        )
        .is_none());
        assert!(parse_ride_envelope(
            r#"{"comrade_ride":1,"ttl_secs":60,"signal":{"kind":"route","maneuver":"ascend"}}"#
        )
        .is_none());
    }

    #[test]
    fn a_note_is_a_landmark_not_a_paragraph_and_the_cap_counts_characters() {
        let over = "x".repeat(RIDE_NOTE_MAX_CHARS + 1);
        assert!(!RideSignal::Route {
            maneuver: RideManeuver::Left,
            distance_m: None,
            note: Some(over),
        }
        .admissible());
        // 80 Devanagari characters are 80 characters, not 240 bytes.
        let devanagari = "म".repeat(RIDE_NOTE_MAX_CHARS);
        assert!(RideSignal::Route {
            maneuver: RideManeuver::Left,
            distance_m: None,
            note: Some(devanagari),
        }
        .admissible());
        // A whitespace-only note is a note somebody sent by accident.
        assert!(!RideSignal::Route {
            maneuver: RideManeuver::Left,
            distance_m: None,
            note: Some("   ".into()),
        }
        .admissible());
        // And the parse gate applies it, so an inadmissible signal cannot
        // arrive by being sent from a build without the send-side check.
        let oversized = format!(
            r#"{{"comrade_ride":1,"ttl_secs":60,"signal":{{"kind":"route","maneuver":"left","note":"{}"}}}}"#,
            "y".repeat(RIDE_NOTE_MAX_CHARS + 1)
        );
        assert!(parse_ride_envelope(&oversized).is_none());
    }

    #[test]
    fn a_distance_beyond_the_next_maneuver_is_an_itinerary_and_is_refused() {
        assert!(RideSignal::Route {
            maneuver: RideManeuver::Straight,
            distance_m: Some(RIDE_DISTANCE_MAX_M),
            note: None,
        }
        .admissible());
        assert!(!RideSignal::Route {
            maneuver: RideManeuver::Straight,
            distance_m: Some(RIDE_DISTANCE_MAX_M + 1),
            note: None,
        }
        .admissible());
    }

    #[test]
    fn a_signal_expires_from_its_send_time_and_a_peer_ttl_is_clamped() {
        let sent = 1_754_160_000;
        assert!(is_fresh_at(ride_expires_at(sent, RIDE_TTL_SECS), sent + 59));
        assert!(!is_fresh_at(
            ride_expires_at(sent, RIDE_TTL_SECS),
            sent + 60
        ));
        // A hostile TTL of a year pins nothing: the receiver clamps it.
        assert_eq!(ride_expires_at(sent, 31_536_000), sent + RIDE_MAX_TTL_SECS);
        assert_eq!(ride_expires_at(u64::MAX, RIDE_TTL_SECS), u64::MAX);
    }

    #[test]
    fn every_signal_names_how_hard_it_interrupts_and_the_urgent_ones_are_the_safety_ones() {
        assert_eq!(RidePhrase::SlowDown.urgency(), RideUrgency::Urgent);
        assert_eq!(RidePhrase::PullOver.urgency(), RideUrgency::Urgent);
        assert_eq!(RidePhrase::FuelSoon.urgency(), RideUrgency::Notice);
        assert_eq!(RidePhrase::BreakPlease.urgency(), RideUrgency::Notice);
        assert_eq!(RidePhrase::AllGood.urgency(), RideUrgency::Info);
        assert_eq!(RideManeuver::Hazard.urgency(), RideUrgency::Urgent);
        assert_eq!(RideManeuver::Left.urgency(), RideUrgency::Notice);
        assert_eq!(
            RideSignal::Quick {
                phrase: RidePhrase::PullOver
            }
            .urgency(),
            RideUrgency::Urgent
        );
    }
}
