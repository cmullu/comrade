/*!
 * tara — the reflective-companion engine (wellbeing pillar #4).
 *
 * Tara is a space to think out loud: reflective prompts, feeling-mirroring,
 * brainstorming scaffolds, and journaling nudges. Two product gates from
 * AUDIT §8 are load-bearing here and must survive every future change:
 *
 * 1. **Tara is not therapy and never presents as one.** No diagnosis, no
 *    treatment language, no "as your therapist…" framing. When a message
 *    carries distress cues, Tara stops prompting and hands off to real
 *    crisis resources ([`CRISIS_RESOURCES`], [`detect_distress`]).
 * 2. **On-device or not at all.** This v1 engine is a deterministic template
 *    engine — zero network, zero model weights, so the privacy promise holds
 *    by construction. [`CompanionEngine`] is the seam where an *on-device*
 *    quantised LLM can slot in once OQ9 (model/runtime choice) is decided;
 *    a cloud backend must never implement it.
 *
 * Determinism doubles as testability: the same transcript always produces
 * the same replies, so behaviour is pinned by plain unit tests.
 */

/// One reply from the companion, plus whether the user's message tripped the
/// distress detector (the frontend then surfaces [`CRISIS_RESOURCES`]).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CompanionReply {
    pub text: String,
    pub crisis: bool,
}

/// What the companion may know about one recent journal entry: the
/// self-reported mood marker and how many days ago it was written — never the
/// entry text. Data minimisation is deliberate: the journal holds the most
/// sensitive words a user writes, and the companion doesn't need them to
/// nudge ("you marked two low days this week — want to unpack that?").
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JournalSignal {
    pub mood: Option<String>,
    pub age_days: u64,
}

/// The pluggable companion brain. The shipped implementation is
/// [`ReflectiveCompanion`]; an on-device LLM backend (OQ9) would be a second
/// implementor behind the same two calls. Implementations must be pure
/// on-device computation — see the module gates.
pub trait CompanionEngine: Send + Sync {
    /// Reply to `message`. `prior_user_turns` is how many user messages came
    /// before this one in the stored conversation — it seeds the prompt
    /// rotation so consecutive replies don't repeat.
    fn reply(&self, message: &str, prior_user_turns: u64) -> CompanionReply;

    /// The conversation opener shown when the thread is empty, optionally
    /// shaped by recent journal activity (mood markers only).
    fn opener(&self, recent: &[JournalSignal]) -> String;
}

// ── Crisis hand-off ───────────────────────────────────────────────────────────

/// A real place to turn when Tara must step back.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CrisisResource {
    pub name: &'static str,
    pub contact: &'static str,
    pub note: &'static str,
}

/// Helplines surfaced verbatim whenever [`detect_distress`] fires. India-first
/// (the app's home market) with a worldwide directory as the catch-all.
pub const CRISIS_RESOURCES: &[CrisisResource] = &[
    CrisisResource {
        name: "Tele-MANAS (India)",
        contact: "14416",
        note: "Free, 24×7, government mental-health line in 20 languages",
    },
    CrisisResource {
        name: "KIRAN (India)",
        contact: "1800-599-0019",
        note: "Free, 24×7 mental-health rehabilitation helpline",
    },
    CrisisResource {
        name: "AASRA",
        contact: "+91 98204 66726",
        note: "24×7 suicide-prevention support",
    },
    CrisisResource {
        name: "Find a helpline (anywhere)",
        contact: "findahelpline.com",
        note: "Verified helpline directory for every other country",
    },
];

/// Phrases that indicate the user may be in crisis. Matching is deliberately
/// conservative-in-favour-of-showing-help: a false positive costs one extra
/// card of helpline numbers; a false negative costs the hand-off itself.
const DISTRESS_CUES: &[&str] = &[
    "kill myself",
    "killing myself",
    "suicide",
    "suicidal",
    "want to die",
    "wanna die",
    "wish i was dead",
    "wish i were dead",
    "better off dead",
    "end my life",
    "ending my life",
    "end it all",
    "take my own life",
    "no reason to live",
    "not worth living",
    "dont want to be alive",
    "do not want to be alive",
    "dont want to live",
    "hurt myself",
    "hurting myself",
    "harm myself",
    "harming myself",
    "self harm",
    "cut myself",
    "cutting myself",
    "overdose",
];

/// Lowercase and strip everything that isn't a letter, digit or space, so
/// "self-harm", "self harm" and "Self‑Harm." all normalise to "self harm".
fn normalise(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        if c.is_alphanumeric() {
            out.extend(c.to_lowercase());
        } else if c.is_whitespace() || c == '-' || c == '\'' || c == '’' {
            // Hyphens and apostrophes vanish ("self-harm" → "selfharm" would
            // miss; map them to the joining style of the cue list instead.)
            if c == '\'' || c == '’' {
                continue; // "don't" → "dont"
            }
            if !out.ends_with(' ') {
                out.push(' ');
            }
        } else if !out.ends_with(' ') {
            out.push(' ');
        }
    }
    out.trim().to_string()
}

/// Whether `text` carries a crisis cue. Pure and dependency-free so every
/// frontend (and the voice dispatcher, later) can share one definition.
pub fn detect_distress(text: &str) -> bool {
    let n = normalise(text);
    let padded = format!(" {n} ");
    DISTRESS_CUES.iter().any(|cue| {
        // Whole-phrase containment on normalised text; pad so "suicide"
        // matches at the edges without also matching inside another word.
        padded.contains(&format!(" {cue} "))
    })
}

// ── The v1 engine: deterministic reflective templates ─────────────────────────

/// The template-based reflective companion — OQ9's "no model" option,
/// shipped so the surface, storage and safety plumbing are real while the
/// on-device-LLM question stays open.
#[derive(Debug, Default, Clone, Copy)]
pub struct ReflectiveCompanion;

/// A feeling family Tara can mirror back, with the cue words that map to it.
const FEELINGS: &[(&str, &[&str])] = &[
    (
        "anxious",
        &[
            "anxious",
            "anxiety",
            "worried",
            "worry",
            "nervous",
            "panic",
            "panicking",
            "scared",
            "afraid",
            "overwhelmed",
            "stressed",
            "stress",
        ],
    ),
    (
        "low",
        &[
            "sad",
            "down",
            "low",
            "depressed",
            "unhappy",
            "miserable",
            "hopeless",
            "empty",
            "crying",
            "cried",
        ],
    ),
    (
        "angry",
        &[
            "angry",
            "anger",
            "furious",
            "frustrated",
            "frustrating",
            "annoyed",
            "irritated",
            "resentful",
        ],
    ),
    (
        "worn out",
        &[
            "tired",
            "exhausted",
            "drained",
            "burnt out",
            "burned out",
            "burnout",
            "sleepless",
            "cant sleep",
        ],
    ),
    (
        "lonely",
        &[
            "lonely", "alone", "isolated", "left out", "ignored", "unseen",
        ],
    ),
    (
        "good",
        &[
            "happy",
            "glad",
            "excited",
            "grateful",
            "proud",
            "relieved",
            "hopeful",
            "better today",
        ],
    ),
];

/// Open questions rotated (by turn count) after a mirrored feeling.
const FEELING_FOLLOW_UPS: &[&str] = &[
    "What do you think is underneath that?",
    "When did you first notice it today?",
    "If it could talk, what would it be asking for?",
    "What's one small thing that has helped before?",
];

/// Reflective prompts rotated when no feeling or question is detected.
const REFLECTIVE_PROMPTS: &[&str] = &[
    "What feels most important about that to you?",
    "Say more — what happened just before?",
    "How did that land in your body?",
    "What would you tell a close friend who brought this to you?",
    "Is there a part of this you haven't said out loud yet?",
];

/// Brainstorm scaffolds rotated when the user asks a "what should I do" question.
const BRAINSTORM_PROMPTS: &[&str] = &[
    "Let's lay it out: what options can you see, even bad ones?",
    "What would the version of you five years from now suggest?",
    "What's the smallest next step that you could take today?",
    "If the decision were already made, which outcome would you quietly hope for?",
];

/// The hand-off spoken or shown when [`detect_distress`] fires.
///
/// Deliberately **modality-neutral**: it must not point at "the helplines
/// below", because the same sentence is read aloud by the voice assistant where
/// there is no screen and no "below". Each surface adds its own affordance on
/// top — the app renders [`CRISIS_RESOURCES`] as a card, the voice layer reads
/// a number out. Keep it that way if you reword this.
const CRISIS_REPLY: &str = "I'm really glad you told me, and I'm taking it seriously. \
I'm a reflective companion, not a therapist or crisis service — what you're carrying \
deserves a trained human. Please reach out to a crisis helpline, or to someone you \
trust, right now. I'll still be here afterwards.";

// ── The third-party gate ──────────────────────────────────────────────────────
//
// Tara reached the chat composer (`crate::command::ChatCommand::AskTara`), and
// with it came a question she must not answer: *"what does @xyz think of
// herself?"*. Two of this module's gates rule it out and they point the same way.
//
// **Gate 1, never diagnoses.** Inferring what a named person thinks of
// themselves is a psychological assessment of somebody who has not consented to
// one, is not in the room, and cannot correct it.
//
// **And the engine could not do it anyway.** `ReflectiveCompanion` is a cue-word
// template matcher (see `FEELINGS`); asked about a third party it would emit a
// fluent, confident sentence with no information in it — about a real human being
// the reader knows. That is worse than refusing, and it is worse than a wrong
// answer about a fact, because the reader may act on it.
//
// So the rule is blunt on purpose: **an aside that names another person turns
// the question back to the person asking.** No cue list deciding which questions
// about someone are assessments — that would be a fuzzy matcher guarding a hard
// boundary, and its false negatives are exactly the cases that matter.
//
// Being blunt costs almost nothing, because the redirect is *also the right
// reflective move*. "I'm worried about @ana" is legitimate material for this
// space, and "what's coming up for you about ana?" is what a good listener says
// to it. The safety property and the product behaviour are the same sentence.
//
// Like `detect_distress`, this gate must stay **in front of any future model**
// (`docs/TARA.md`, OQ9). A generative backend is exactly the thing that would
// answer the question fluently.

/// The handle of a third party named in `text`, or `None`.
///
/// Tara herself is not a third party — someone writing "@tara" inside the body
/// is addressing the companion, not asking about a person.
///
/// This deliberately does **not** call [`crate::command::mentions`], which
/// implements the same rule. This module has no `use` statements at all, and that
/// is not an accident of style: "on-device or not at all" is gate 2, and a module
/// that imports nothing is a guarantee an auditor can check in one glance rather
/// than by walking a dependency tree. The duplication is twelve lines, and
/// `the_two_handle_scanners_agree` pins the two against each other so they cannot
/// drift.
pub fn mentions_third_party(text: &str) -> Option<String> {
    let bytes = text.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'@' && (i == 0 || !is_handle_byte(bytes[i - 1])) {
            let start = i + 1;
            let mut end = start;
            while end < bytes.len() && is_handle_byte(bytes[end]) {
                end += 1;
            }
            let handle = text[start..end].to_lowercase();
            // 3..=24 is `normalize_handle`'s range in `comrade_ui`; anything
            // outside it could never resolve to a contact.
            if handle.len() >= 3 && handle.len() <= 24 && handle != "tara" {
                return Some(handle);
            }
            i = end.max(i + 1);
        } else {
            i += 1;
        }
    }
    None
}

/// The charset a handle is made of: ASCII alphanumerics and `_`.
fn is_handle_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_'
}

/// The reply when an aside names somebody else.
///
/// Three things it has to do, and the wording is load-bearing for each. It
/// **declines**, and says why in a way that is literally true of this engine
/// ("I'd only be making it up"). It **names who it is protecting**, because
/// "it wouldn't be fair to them" is the actual reason and hiding it would make
/// the refusal feel arbitrary. And it **hands back the askable question**, so the
/// boundary teaches instead of stonewalling — a reader told only "I can't" learns
/// that Tara is useless here, which is the opposite of true.
fn third_party_reply(handle: &str) -> String {
    format!(
        "I can't tell you what someone else thinks or feels — I'd only be making it up, \
         and it wouldn't be fair to them. What I can stay with is your side of it: \
         what's coming up for you about @{handle}?"
    )
}

// ── Tara in the room ─────────────────────────────────────────────────────────
//
// `@tara …` in a conversation asks her **in front of the other person** — the
// answer is sent to them, like `@Meta AI` in WhatsApp. `/tara …` is the private
// aside and always was. Two spellings, two audiences, and the composer has to
// say which one is in the box before the send button is pressed.
//
// A shared answer travels as an ordinary message whose text carries
// [`TARA_CHAT_PREFIX`]. `comrade_ui::MessageDto` now reads that marker into an
// author field and hands the frontends the answer without it, so a Tara line is
// drawn as hers rather than as a sentence you appear to have typed.
//
// **The prefix stays on the wire on purpose**, and did not go away when the
// field arrived. A NIP-17 DM opened in some other Nostr client has no author
// field to read, and "Tara: …" is a truer fallback there than her words in the
// sender's mouth. It also means a thread written by an older build keeps
// meaning what it meant.
//
// What the marker is **not** is authentication. Anybody can type "Tara: "
// themselves, so a Tara bubble says *the sending Comrade claims this came from
// her* — the same standing a quoted reply has — and no frontend may present a
// match as proof the companion spoke. `AUDIT.md` Q17 records the boundary, and
// [`the_marker_is_a_label_and_the_test_says_so`] pins it.

/// What a shared Tara line starts with, on the wire and on screen.
pub const TARA_CHAT_PREFIX: &str = "Tara: ";

/// Render `answer` as the line that goes into the conversation.
pub fn tara_chat_line(answer: &str) -> String {
    format!("{TARA_CHAT_PREFIX}{}", answer.trim())
}

/// The answer inside a shared Tara line, or `None` if `content` is not one.
///
/// For frontends that want to draw the line as hers rather than as yours. See
/// the section note above: a match is a label, not a claim about who spoke.
pub fn tara_chat_answer(content: &str) -> Option<&str> {
    content.strip_prefix(TARA_CHAT_PREFIX)
}

fn contains_word(haystack_norm: &str, cue: &str) -> bool {
    format!(" {haystack_norm} ").contains(&format!(" {cue} "))
}

impl ReflectiveCompanion {
    fn feeling_in(norm: &str) -> Option<&'static str> {
        FEELINGS.iter().find_map(|(label, cues)| {
            cues.iter()
                .any(|cue| contains_word(norm, cue))
                .then_some(*label)
        })
    }

    fn is_greeting(norm: &str) -> bool {
        const GREETINGS: &[&str] = &[
            "hi",
            "hello",
            "hey",
            "namaste",
            "yo",
            "good morning",
            "good evening",
            "good afternoon",
        ];
        norm.split_whitespace().count() <= 3
            && GREETINGS
                .iter()
                .any(|g| norm == *g || norm.starts_with(&format!("{g} ")))
    }

    fn is_advice_seeking(text: &str, norm: &str) -> bool {
        const ASKS: &[&str] = &[
            "should i",
            "what do i do",
            "what should",
            "how do i",
            "how can i",
            "help me decide",
            "help me figure",
            "what would you do",
        ];
        ASKS.iter().any(|a| norm.contains(a)) || text.trim_end().ends_with('?')
    }

    fn pick(options: &'static [&'static str], turn: u64) -> &'static str {
        options[(turn as usize) % options.len()]
    }
}

impl ReflectiveCompanion {
    /// [`CompanionEngine::opener`], optionally extended with a pre-built
    /// heavy-scroll-day nudge (`comrade_core::attention::usage_opener`).
    ///
    /// Precedence is deliberate and lives here so it can never be decided two
    /// ways by two frontends: **mood outranks usage** — two low journal days
    /// this week are a heavier signal than yesterday's screen time, and the
    /// usage line must never displace the low-mood invitation. The usage
    /// nudge only ever replaces the generic openers.
    ///
    /// Data minimisation still holds: the caller passes a finished sentence
    /// built from rollup *numbers* (see `attention::usage_opener`), so this
    /// method learns nothing about the user's apps or words.
    pub fn opener_with_usage(
        &self,
        recent: &[JournalSignal],
        usage_nudge: Option<String>,
    ) -> String {
        let base = self.opener(recent);
        if base.contains("felt low") {
            return base;
        }
        usage_nudge.unwrap_or(base)
    }
}

impl CompanionEngine for ReflectiveCompanion {
    fn reply(&self, message: &str, prior_user_turns: u64) -> CompanionReply {
        if detect_distress(message) {
            return CompanionReply {
                text: CRISIS_REPLY.to_string(),
                crisis: true,
            };
        }
        // Second, and only second: someone in crisis who happens to name a
        // friend needs the helplines, not a reframe.
        if let Some(handle) = mentions_third_party(message) {
            return CompanionReply {
                text: third_party_reply(&handle),
                crisis: false,
            };
        }
        let norm = normalise(message);
        let text = if Self::is_greeting(&norm) {
            "Hey, I'm Tara — a private space to think out loud. Nothing you say here \
             leaves this device. What's on your mind?"
                .to_string()
        } else if let Some(feeling) = Self::feeling_in(&norm) {
            if feeling == "good" {
                format!(
                    "It sounds like something {feeling} is happening — I'd love to hear it. {}",
                    Self::pick(
                        &[
                            "What made the difference?",
                            "What do you want to remember about today?"
                        ],
                        prior_user_turns
                    )
                )
            } else {
                format!(
                    "That sounds {feeling}, and it makes sense you'd feel that way. {}",
                    Self::pick(FEELING_FOLLOW_UPS, prior_user_turns)
                )
            }
        } else if Self::is_advice_seeking(message, &norm) {
            format!(
                "I can't tell you what to do, but I can help you think it through. {}",
                Self::pick(BRAINSTORM_PROMPTS, prior_user_turns)
            )
        } else {
            Self::pick(REFLECTIVE_PROMPTS, prior_user_turns).to_string()
        };
        CompanionReply {
            text,
            crisis: false,
        }
    }

    fn opener(&self, recent: &[JournalSignal]) -> String {
        // Mood markers are stored as the emoji itself; the two leftmost of the
        // journal's five-step scale read as "low".
        const LOW_MOODS: &[&str] = &["😞", "😕"];
        let last_week = recent.iter().filter(|s| s.age_days <= 7);
        let low_days = last_week
            .clone()
            .filter(|s| s.mood.as_deref().is_some_and(|m| LOW_MOODS.contains(&m)))
            .count();
        let recent_entries = last_week.count();
        if low_days >= 2 {
            format!(
                "I noticed {low_days} of your journal days this week felt low. \
                 No pressure — but if you want to unpack any of it, I'm listening."
            )
        } else if recent_entries > 0 {
            "You've been journaling this week — want to think out loud about \
             anything you wrote, or about something new?"
                .to_string()
        } else {
            "I'm Tara — a private space to reflect, vent, or brainstorm. I'm not a \
             therapist, and nothing here leaves your device. What's on your mind?"
                .to_string()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn distress_cues_are_detected() {
        for msg in [
            "I want to die",
            "i've been thinking about SUICIDE a lot",
            "sometimes I just want to hurt myself.",
            "I don't want to be alive anymore",
            "thinking about self-harm again",
            "maybe everyone would be better off dead without me… I mean me",
        ] {
            assert!(detect_distress(msg), "should flag: {msg}");
        }
    }

    #[test]
    fn ordinary_messages_are_not_flagged() {
        for msg in [
            "this deadline is brutal",
            "I'm so tired of my commute",
            "my phone battery died",
            "the suicide squad movie was bad", // known trade-off: word match wins
        ] {
            // Note: "suicide" as a bare word *does* flag — conservative by
            // design. Everything else here must stay quiet.
            if msg.contains("suicide") {
                assert!(detect_distress(msg));
            } else {
                assert!(!detect_distress(msg), "should not flag: {msg}");
            }
        }
    }

    #[test]
    fn crisis_reply_hands_off_and_flags() {
        let r = ReflectiveCompanion.reply("I want to end it all", 3);
        assert!(r.crisis);
        assert!(r.text.contains("not a therapist"));
        assert!(!CRISIS_RESOURCES.is_empty());
    }

    #[test]
    fn feelings_are_mirrored() {
        let r = ReflectiveCompanion.reply("I've been so anxious about the review", 0);
        assert!(!r.crisis);
        assert!(r.text.contains("anxious"), "got: {}", r.text);
        assert!(r.text.contains(FEELING_FOLLOW_UPS[0]));
    }

    #[test]
    fn prompts_rotate_with_turn_count() {
        let a = ReflectiveCompanion.reply("we repainted the fence", 0).text;
        let b = ReflectiveCompanion.reply("we repainted the fence", 1).text;
        assert_ne!(a, b, "consecutive default prompts must differ");
        // …and the rotation is deterministic.
        assert_eq!(
            a,
            ReflectiveCompanion.reply("we repainted the fence", 0).text
        );
    }

    #[test]
    fn advice_seeking_gets_brainstorm_scaffold_not_advice() {
        let r = ReflectiveCompanion.reply("should i quit my job", 0);
        assert!(r.text.starts_with("I can't tell you what to do"));
    }

    #[test]
    fn greeting_introduces_tara_honestly() {
        let r = ReflectiveCompanion.reply("hey", 0);
        assert!(r.text.contains("think out loud"));
        assert!(r.text.contains("leaves this device"));
    }

    #[test]
    fn opener_reads_only_mood_signals() {
        let low = JournalSignal {
            mood: Some("😞".into()),
            age_days: 1,
        };
        let s = ReflectiveCompanion.opener(&[low.clone(), low]);
        assert!(s.contains("felt low"));

        let fresh = ReflectiveCompanion.opener(&[]);
        assert!(fresh.contains("not a therapist"));

        let neutral = JournalSignal {
            mood: None,
            age_days: 2,
        };
        assert!(ReflectiveCompanion
            .opener(&[neutral])
            .contains("journaling this week"));
    }

    #[test]
    fn low_mood_outranks_the_usage_nudge_and_usage_replaces_only_generic_openers() {
        let low = JournalSignal {
            mood: Some("😞".into()),
            age_days: 1,
        };
        let nudge = Some("Yesterday had about 120 minutes…".to_string());
        // Two low days: the mood invitation wins, whatever usage says.
        let s = ReflectiveCompanion.opener_with_usage(&[low.clone(), low], nudge.clone());
        assert!(s.contains("felt low"));
        // No low days: the usage nudge replaces the generic opener.
        let s = ReflectiveCompanion.opener_with_usage(&[], nudge);
        assert!(s.contains("120 minutes"));
        // No nudge: exactly the plain opener.
        let s = ReflectiveCompanion.opener_with_usage(&[], None);
        assert_eq!(s, ReflectiveCompanion.opener(&[]));
    }

    #[test]
    fn old_low_moods_do_not_trigger_the_low_opener() {
        let stale = JournalSignal {
            mood: Some("😞".into()),
            age_days: 30,
        };
        let s = ReflectiveCompanion.opener(&[stale.clone(), stale]);
        assert!(!s.contains("felt low"));
    }

    // ── The third-party gate ─────────────────────────────────────────────────

    #[test]
    fn asking_what_someone_thinks_of_themselves_is_turned_around() {
        // The question the composer entry point made reachable, verbatim. If
        // this ever answers, gate 1 is gone.
        let r = ReflectiveCompanion.reply("what does she @xyz thinking of herself", 0);
        assert!(!r.crisis);
        assert!(
            r.text.contains("what's coming up for you about @xyz"),
            "expected the reframe, got: {}",
            r.text
        );
        // It must not appear to answer.
        for leaked in [
            "she thinks",
            "she feels",
            "she probably",
            "it sounds like she",
        ] {
            assert!(
                !r.text.to_lowercase().contains(leaked),
                "the reply characterised her: {}",
                r.text
            );
        }
    }

    #[test]
    fn naming_someone_reaches_the_reframe_whatever_the_question_is() {
        // Deliberately not a cue list: any aside naming a person turns around,
        // because a fuzzy matcher guarding a hard boundary fails on the cases
        // that matter. It also happens to be the right reflective move.
        for aside in [
            "what does @ana think of me",
            "is @ana angry with me",
            "i'm worried about @ana",
            "@ana has been distant lately",
        ] {
            let r = ReflectiveCompanion.reply(aside, 0);
            assert!(
                r.text.contains("what's coming up for you about @ana"),
                "{aside:?} did not reframe: {}",
                r.text
            );
        }
    }

    // ── Tara in the room ─────────────────────────────────────────────────────

    #[test]
    fn a_shared_line_round_trips_through_its_marker() {
        let line = tara_chat_line("What's the smallest next step?");
        assert_eq!(
            tara_chat_answer(&line),
            Some("What's the smallest next step?")
        );
        // An ordinary message is not a Tara line, whatever it says about her.
        assert_eq!(tara_chat_answer("Tara said hello"), None);
        assert_eq!(tara_chat_answer("what did tara say"), None);
    }

    #[test]
    fn the_marker_is_a_label_and_the_test_says_so() {
        // Anybody can type the prefix, and this asserts the honest consequence
        // rather than a guarantee the wire does not carry: a hand-typed line
        // matches. A frontend must therefore style a match, never trust it.
        assert_eq!(
            tara_chat_answer("Tara: I made this up"),
            Some("I made this up")
        );
    }

    #[test]
    fn distress_still_wins_over_the_reframe() {
        // Someone in crisis who names a friend needs the helplines, not a
        // reflective question. Ordering is the whole test.
        let r = ReflectiveCompanion.reply("i want to die and @ana knows it", 0);
        assert!(r.crisis);
        assert_eq!(r.text, CRISIS_REPLY);
    }

    #[test]
    fn an_aside_naming_nobody_reaches_the_engine_as_before() {
        let r = ReflectiveCompanion.reply("i keep putting this off", 0);
        assert!(!r.crisis);
        assert!(!r.text.contains("coming up for you about"));
    }

    #[test]
    fn addressing_tara_inside_the_body_is_not_a_third_party() {
        assert!(mentions_third_party("@tara what do you think").is_none());
        assert!(mentions_third_party("thanks @Tara").is_none());
    }

    #[test]
    fn a_vpa_is_not_a_person() {
        // Same rule as the command parser: `friend@upi` names no one.
        assert!(mentions_third_party("send 250 to friend@upi").is_none());
    }

    #[test]
    fn handles_outside_the_resolvers_range_are_not_people() {
        assert!(mentions_third_party("@ab is short").is_none());
        assert!(mentions_third_party("@aaaaaaaaaaaaaaaaaaaaaaaaaaaaa").is_none());
    }

    #[test]
    fn the_two_handle_scanners_agree() {
        // `mentions_third_party` reimplements `command::mentions`' rule so this
        // module can keep zero imports (gate 2 is auditable at a glance). This
        // test is where the duplication is held honest — it may import, because
        // a test is not part of the module's dependency surface.
        for text in [
            "hello @ana",
            "@ana and @bina",
            "send 250 to friend@upi",
            "@ab too short",
            "@aaaaaaaaaaaaaaaaaaaaaaaaaaaaa too long",
            "@Xyz_9 mixed case",
            "no handles here",
            "@tara only",
            "@tara then @ana",
            "email me at a@b.com",
        ] {
            let first_other = crate::command::mentions(text)
                .into_iter()
                .map(|m| m.handle)
                .find(|h| h != "tara");
            assert_eq!(
                mentions_third_party(text),
                first_other,
                "the two scanners disagree on {text:?}"
            );
        }
    }
}
