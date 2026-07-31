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
 *    crisis resources ([`CRISIS_RESOURCES`], [`detect_distress`]). Asked
 *    directly whether she is a therapist or a person, she answers no,
 *    honestly ([`META_THERAPIST_LINES`], [`META_AI_LINES`]).
 * 2. **On-device or not at all.** This v1 engine is a deterministic template
 *    engine — zero network, zero model weights, so the privacy promise holds
 *    by construction. [`CompanionEngine`] is the seam where an *on-device*
 *    quantised LLM can slot in once OQ9 (model/runtime choice) is decided;
 *    a cloud backend must never implement it.
 *
 * # How replies avoid sounding canned (while staying deterministic)
 *
 * A template engine reads as robotic when every disclosure gets the same
 * sentence with one word swapped, every reply ends in a question, and the
 * pools are small enough to repeat within a handful of turns. This engine
 * counters each of those *without* randomness:
 *
 * - **Intent routing before templates.** Thanks, goodbyes, greetings, bare
 *   "yes"/"idk" replies, "are you an AI?"-style meta questions, decision
 *   questions and generic questions each have their own pool, so "thank you"
 *   is never answered with "Say more — what happened just before?".
 * - **Whole sentences per feeling, not fill-in-the-label templates.** Each
 *   feeling family carries 8 complete lines mixing shapes: some validate
 *   without asking anything, some end with an open question, some just
 *   invite. Follow-ups can no longer mismatch their feeling.
 * - **Seeded variety.** Line choice is `(fnv1a(message) + turn) % len`:
 *   deterministic for a given (message, turn) — so behaviour stays pinned by
 *   tests — but different messages start at different pool offsets and the
 *   same message never gets the same line twice in a row.
 * - **Safe topic echo.** When a message says "… about the review tomorrow",
 *   the fragment is echoed back ("It makes sense that the review tomorrow is
 *   weighing on you") — but only when it is short, contains no first-person
 *   tokens and no distress cues. Echoing their words is the single biggest
 *   difference between "listening" and "form letter".
 * - **Negation awareness.** "I'm not happy" must never be celebrated.
 *
 * Determinism doubles as testability: the same (message, turn) always
 * produces the same reply, so behaviour is pinned by plain unit tests.
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
    /// before this one in the stored conversation — it seeds the line
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
/// frontend (and the voice dispatcher) can share one definition.
pub fn detect_distress(text: &str) -> bool {
    let n = normalise(text);
    // Speech-to-text often renders reflexives split ("kill my self"); collapse
    // the split so the voice path is caught by the same cues as typed text.
    let padded = format!(" {n} ").replace(" my self ", " myself ");
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

/// A feeling family Tara can mirror back: the adjective used when naming it
/// out loud, and the cue words/phrases that map to it. Cues are matched on
/// whole normalised tokens with a negation window (see [`detect_feelings`]).
///
/// Deliberately absent: bare "down", "low", "empty" — they dominate
/// non-emotional usage ("the wifi has been down", "my battery is low"), so
/// they only count inside a feeling phrase.
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
            "depressed",
            "unhappy",
            "miserable",
            "hopeless",
            "crying",
            "cried",
            // "feel"-anchored phrasings only: "been down"/"been low" match
            // the wifi and the battery far too often to be trusted.
            "feeling down",
            "feel down",
            "felt down",
            "so down",
            "feeling low",
            "feel low",
            "felt low",
            "feeling empty",
            "feel empty",
            "felt empty",
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

/// Index of the "low" family in [`FEELINGS`] — a negated positive ("I'm not
/// happy") routes here rather than being celebrated or ignored.
const LOW_FAMILY: usize = 1;

/// Tokens that flip the meaning of a feeling cue when they appear just before
/// it ("not happy", "don't feel lonely", "never been this tired" is fine —
/// "never" negates a state claim rarely; conservatively included anyway).
const NEGATORS: &[&str] = &[
    "not", "no", "never", "dont", "didnt", "doesnt", "isnt", "wasnt", "aint", "cant", "wont",
    "stopped", "without", "barely", "hardly", "less",
];

/// First-person tokens that must never appear inside an echoed topic — a
/// grammatical person flip ("about my boss and me") is exactly the ELIZA
/// failure mode this guard exists to avoid.
const FIRST_PERSON: &[&str] = &[
    "i", "im", "ive", "id", "ill", "me", "my", "mine", "myself", "we", "us", "our", "ours",
];

// ── Reply pools ───────────────────────────────────────────────────────────────
//
// Copy rules, in force for every pool below:
// - warm, plain, honest; no clinical language, no diagnosis, no promises;
// - shapes vary: some lines validate without asking anything, some end in an
//   open question, some just invite — so Tara is not a perpetual interviewer;
// - "{topic}" marks an optional echoed-fragment slot; a line carrying it is
//   only eligible when a safe fragment exists (see `extract_topic`);
// - 6–8 lines per pool so rotation doesn't repeat within a short session.

/// First contact only (`prior_user_turns == 0`): the one place Tara fully
/// introduces herself. Later greetings rotate [`GREETING_LINES`] instead —
/// re-delivering the privacy pitch on every "hi" is peak robot.
const FIRST_GREETING: &str = "Hey, I'm Tara — a private space to think out loud. Nothing you \
say here leaves this device. What's on your mind?";

const GREETING_LINES: &[&str] = &[
    "Hi. I'm here, and I'm listening. What's on your mind?",
    "Hello again. Take your time — start wherever is easiest.",
    "Hey. No rush at all. What would you like to think about today?",
    "Hi there. Big or small, whatever's on your mind is welcome here.",
    "Hello. If it helps, just start in the middle — we can untangle it as we go.",
    "Hey, good to hear from you. How has today been treating you?",
    "Hi. We can pick up where we left off, or start somewhere new — up to you.",
    "Hello. I'm all ears whenever you're ready.",
];

const ANXIOUS_LINES: &[&str] = &[
    "That sounds like a lot to be carrying, and it's no wonder you feel wound up.",
    "Worry has a way of taking up the whole room. What's it circling around most?",
    "It makes sense that {topic} is weighing on you. If you want to say more about it, I'm listening.",
    "Feeling on edge is exhausting in itself. Has this been building for a while, or did something set it off today?",
    "That knotted-up feeling is horrible, and you don't have to untangle it all at once here.",
    "When everything feels urgent it's hard to tell what actually is. What's the loudest worry right now?",
    "Anyone would feel stretched with all that going on. Which part feels most out of your hands?",
    "You've named it, and that's a start. If it helps, walk me through what your head says happens next.",
];

const LOW_LINES: &[&str] = &[
    "I'm sorry it's been heavy. Say it however it comes — you don't have to dress it up for me.",
    "Some days just sit on your chest. I'm glad you said something instead of carrying it alone.",
    "That sounds really hard. Do you know what's dragging at you most, or is it more of a fog?",
    "It takes something to put a low day into words at all. What's today been like, start to finish?",
    "When everything feels grey it's hard to care about any of it. No need to perform being fine here.",
    "It sounds like {topic} has really taken it out of you. If you want to unpack it, we can go slowly.",
    "You don't need a good reason for a low day to count. It counts.",
    "That's a heavy thing to sit with. Would it help to say what set it off, or would you rather just have some company for a bit?",
];

const ANGRY_LINES: &[&str] = &[
    "That would get under anyone's skin. You're allowed to be angry about it.",
    "Something about that clearly crossed a line for you. What was the moment it tipped over?",
    "It sounds like {topic} really got to you. Say it as bluntly as you like — nothing here goes anywhere.",
    "Anger usually shows up when something matters. What's the thing that matters underneath this one?",
    "Being that wound up is exhausting. Get it out of your head and into words, if that helps.",
    "Fair enough — that sounds genuinely infuriating.",
    "If you said exactly what you think, no filter, what would come out?",
    "That kind of frustration tends to have a history. Is this a one-off, or the latest in a pattern?",
];

const WORN_OUT_LINES: &[&str] = &[
    "That sounds like real tiredness — the kind one night's sleep doesn't fix.",
    "Running on empty makes everything harder than it should be. What's been taking the most out of you?",
    "You've clearly been carrying a lot. When did you last get a proper pause?",
    "It sounds like {topic} has been draining you for a while. If you want to talk it through, there's no hurry.",
    "Being that drained isn't a personal failing — it's a signal. I'm listening if you want to say what it's pointing at.",
    "That level of tired deserves acknowledging. Even saying it takes effort.",
    "If nothing were expected of you for one evening, what would you actually do with it?",
    "That kind of exhaustion builds quietly. What would 'a bit less' look like this week?",
];

const LONELY_LINES: &[&str] = &[
    "Feeling on your own with things is one of the hardest feelings there is. I'm glad you said it out loud.",
    "That sounds isolating. Is it being around too few people, or being unseen by the ones you're around?",
    "Loneliness can happen in a crowded room. Where does it bite hardest for you?",
    "It sounds like {topic} has left you feeling cut off. If you want to say more, I'm here.",
    "Wanting to feel connected isn't neediness — it's just human.",
    "Is there someone you've drifted from that you find yourself missing?",
    "That's a quiet kind of pain, and it's real. You don't have to explain it perfectly.",
    "If someone did reach out tomorrow, what would you want that to look like?",
];

const GOOD_LINES: &[&str] = &[
    "That's genuinely lovely to hear. Go on — tell me the good bit properly.",
    "I'm glad. Days like that deserve a moment of noticing.",
    "That sounds like a bright spot. What made the difference, do you think?",
    "Good news is welcome here too. What do you want to remember about this?",
    "It sounds like {topic} went well. Savour it a little — what was the best part?",
    "That's worth marking. Was it luck, or something you did that you could do again?",
    "Nice one. How does it feel now, saying it out loud?",
    "I like hearing this side of things. Anything you'd want your future self to know about today?",
];

/// Two feelings named in one message — acknowledge both instead of letting
/// pool order decide. `{a}`/`{b}` take the family adjectives.
const COMBINED_FEELING_LINES: &[&str] = &[
    "{a} and {b} at the same time — that's a lot for one day to hold. Which of the two is louder right now?",
    "That's {a} and {b} tangled together, and both sound real. Start with whichever is easier to talk about.",
    "Carrying {a} and {b} at once is heavy. I'm listening — take it in any order.",
    "No wonder it's a lot: {a} and {b} tend to feed each other. What set them off today?",
];

const DEFAULT_LINES: &[&str] = &[
    "I'm with you. What feels most important about that, to you?",
    "Thanks for telling me. No need to tidy it up — keep going if there's more.",
    "Okay, I'm following. What happened just before that?",
    "That sounds worth sitting with for a minute. What's your gut read on it?",
    "If a close friend told you the same thing, what would you say back to them?",
    "It sounds like there's more underneath {topic}. Say more, if you want to.",
    "I'm listening. Is there a part of this you haven't said out loud yet?",
    "Noted, and no judgement here. Where does your mind go next?",
];

const ADVICE_LINES: &[&str] = &[
    "I can't make the call for you, but thinking it through together might help. What are the options as you see them — bad ones included?",
    "That's a real decision, not a small one. What would you do if you knew nobody would judge the choice?",
    "Let's not rush it. What's the smallest step you could take today that keeps both doors open?",
    "I won't pretend to know your life better than you do. But say the choice out loud both ways — which one sounds like relief?",
    "What would the you of five years from now want you to weigh most heavily?",
    "Sometimes the question hides the answer. If the decision were already made, which outcome would you quietly be hoping for?",
    "You know this situation better than anyone. What's the fear that's making it hard to choose?",
    "We can lay it out plainly: what happens if you do, and what happens if you don't?",
];

/// A long outpouring deserves acknowledgement first, not an instant quiz —
/// interrogating someone right after they've emptied their pockets reads as
/// dismissive exactly when they've invested the most.
const VENT_LINES: &[&str] = &[
    "That's a lot, and you've been holding all of it. Thank you for putting it into words.",
    "I'm taking all of that in. You don't have to solve any of it right now — getting it out counts for something.",
    "No wonder you needed to let that out. That's a lot pressing on you at once.",
    "That took some saying. Take a breath — I'm not going anywhere.",
    "There's a whole tangle in there, and all of it sounds real. If one thread matters most, we can start there — only if you want to.",
    "You've been carrying this a while, by the sound of it. Which part has been the heaviest to carry alone?",
    "That's heavy, and it's there every day — I understand why it spills over. Nothing here needs an answer tonight.",
    "However messy that felt to say, it made sense. Keep going, or stop here — both are fine.",
];

const THANKS_LINES: &[&str] = &[
    "You're welcome. I'm glad it helped, even a little.",
    "Any time. This space is yours whenever you need it.",
    "No thanks needed — but I'll take it. Look after yourself.",
    "I'm glad you talked it through. That was you doing the work, not me.",
    "You're welcome. Come back whenever — for something big or nothing much.",
    "That's kind. I'm here whenever you want to think out loud again.",
];

const GOODBYE_LINES: &[&str] = &[
    "Take care of yourself. I'll be here when you want to talk again.",
    "Bye for now. Whatever's unfinished will keep — and so will this space.",
    "Take care, and be a bit kind to yourself today if you can.",
    "See you soon. Thanks for thinking out loud with me.",
    "Go well. Nothing you said goes anywhere, and you can pick it up again any time.",
    "Alright, bye for now. I hope the rest of the day treats you gently.",
];

/// Honesty gate 1, meta edition: asked whether she's a person/AI, Tara never
/// pretends. Every line names itself software and restates the privacy fact.
const META_AI_LINES: &[&str] = &[
    "Honest answer: I'm not a person. I'm a small program that runs entirely on your phone — nothing you say ever leaves it. The listening is simple, but the privacy is real.",
    "No, I'm not a real person. I'm a simple piece of software that lives on this device. I can't understand the way a friend can — but this space is completely private, and I'll always be straight with you about what I am.",
    "I'm a program, not a person — and a fairly simple one. I run only on your phone, with no internet behind me. What I can offer is a private place to think out loud.",
    "Fair question. I'm software — small, private, and entirely on your device. No one else can read this, and I don't pretend to be anything more than I am.",
    "I'm not human, and I won't pretend to be. I'm an on-device program: simple responses, real privacy. If you're wanting a human ear, that's a good instinct — this can be where you work out what you'd say to them.",
    "Straight answer: no. I'm a small private program on your phone. I don't feel things, but everything you say here stays yours.",
];

/// Honesty gate 1, direct edition: asked whether she's a therapist, the
/// answer is an unhedged no, every time.
const META_THERAPIST_LINES: &[&str] = &[
    "No — I'm not a therapist, and I never will be. I'm a private space to think out loud. If therapy is something you're weighing up, that could be a genuinely good step, and a real professional is the right person for it.",
    "No, I'm not, and I'd never want you to mistake me for one. I don't diagnose or treat anything. What I can do is listen while you sort through your own thoughts.",
    "Straight answer: no. I'm a simple program, not a clinician, and nothing here is medical advice. If you think talking to a professional might help, trust that instinct.",
    "I'm not a therapist or a doctor — just a private place to reflect. Some things deserve a trained human, and if this feels like one of them, please do reach for that.",
    "No. Therapy is done by trained people, and I'm neither trained nor a person. I can keep you company while you think — that's the honest extent of it.",
    "Not a therapist, no — and it matters that you know that. If what you're carrying feels bigger than thinking out loud, a professional or someone you trust is the right next step.",
];

const SHORT_REPLY_LINES: &[&str] = &[
    "Okay. Take whatever time you need — there's no clock on this.",
    "Alright. Want to stay with that, or move to something else?",
    "Got it. Even one more sentence might loosen it up, if you feel like it.",
    "That's fine. Sometimes a short answer is the whole answer.",
    "Okay — I won't push. I'm here if more words turn up.",
    "Fair enough. What's behind that, if anything?",
    "Right. We can leave it there, or you can say the next bit — your call.",
];

const DONT_KNOW_LINES: &[&str] = &[
    "That's an honest place to start. Not knowing is allowed here.",
    "Fair enough — some things are foggy from the inside. Have a guess anyway, even a bad one?",
    "You don't have to know. Sometimes saying 'I don't know' out loud is the first clear thing.",
    "Okay. If you did know, what might the answer be? First thing that comes to mind.",
    "Not knowing is fine. What does the not-knowing feel like — heavy, blank, tangled?",
    "That's alright. We can circle it instead: what's nearby that you do know?",
    "No pressure to have an answer. Sit with it — or change the subject entirely, that's allowed too.",
];

/// Questions that aren't decisions ("why is everything so hard?", "how are
/// you?") get an honest I-don't-know-things redirect — never the advice
/// scaffold, and never a made-up answer.
const QUESTION_LINES: &[&str] = &[
    "I'll be honest — I don't have knowledge of the world to answer that properly. But I'm curious why it's on your mind.",
    "That's beyond what a simple program like me actually knows. What made you think of it just now?",
    "I can't answer that well, and I'd rather say so than make something up. What's your own take?",
    "Good question — and not one I'm equipped for. Ask me to help you think something through, though, and I'm all yours.",
    "Honestly, that one's outside what I can do. If there's a feeling or a decision behind the question, that part I can sit with.",
    "I don't know things the way people do — I'm a small program on your phone. But questions usually come from somewhere. Where did that one come from?",
    "Kind of you to ask — though I don't really have days of my own. Yours, on the other hand: how has it actually been?",
];

/// The hand-off spoken or shown when [`detect_distress`] fires.
///
/// Deliberately **modality-neutral**: it must not point at "the helplines
/// below", because the same sentence is read aloud by the voice assistant where
/// there is no screen and no "below". Each surface adds its own affordance on
/// top — the app renders [`CRISIS_RESOURCES`] as a card, the voice layer reads
/// a number out. Keep it that way if you reword this.
///
/// This string is FIXED: never rotated, never topic-echoed, never varied.
/// Safety copy is vetted once and repeated verbatim.
const CRISIS_REPLY: &str = "I'm really glad you told me, and I'm taking it seriously. \
I'm a reflective companion, not a therapist or crisis service — what you're carrying \
deserves a trained human. Please reach out to a crisis helpline, or to someone you \
trust, right now. I'll still be here afterwards.";

// ── Selection machinery ───────────────────────────────────────────────────────

/// FNV-1a — a tiny, stable string hash. Not cryptographic and doesn't need to
/// be: it only spreads different messages across different pool offsets.
fn fnv1a(s: &str) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in s.as_bytes() {
        h ^= u64::from(*b);
        h = h.wrapping_mul(0x0000_0100_0000_01b3);
    }
    h
}

/// Deterministically choose a line: the message hash picks the starting
/// offset, the turn count steps through the pool — so the same (message,
/// turn) is stable, consecutive turns never repeat a line, and different
/// messages don't all start at index 0. Lines carrying `{topic}` are only
/// eligible when a topic is available.
fn choose(lines: &'static [&'static str], norm: &str, turn: u64, topic: Option<&str>) -> String {
    let usable: Vec<&'static str> = lines
        .iter()
        .copied()
        .filter(|l| topic.is_some() || !l.contains("{topic}"))
        .collect();
    let idx = ((fnv1a(norm) as usize % usable.len()) + turn as usize) % usable.len();
    let line = usable[idx];
    match topic {
        Some(t) if line.contains("{topic}") => line.replace("{topic}", t),
        _ => line.to_string(),
    }
}

/// Feeling families present in `norm`, ordered by where they first occur in
/// the message (the user's own emphasis, not pool order), plus whether a
/// *positive* cue appeared negated ("I'm not happy") — which reads as low.
fn detect_feelings(norm: &str) -> (Vec<usize>, bool) {
    let tokens: Vec<&str> = norm.split_whitespace().collect();
    let mut found: Vec<(usize, usize)> = Vec::new(); // (first token pos, family idx)
    let mut negated_good = false;
    for (family, (label, cues)) in FEELINGS.iter().enumerate() {
        let mut earliest: Option<usize> = None;
        for cue in *cues {
            let cue_tokens: Vec<&str> = cue.split_whitespace().collect();
            if cue_tokens.len() > tokens.len() {
                continue;
            }
            for start in 0..=(tokens.len() - cue_tokens.len()) {
                if tokens[start..start + cue_tokens.len()] != cue_tokens[..] {
                    continue;
                }
                let window_start = start.saturating_sub(3);
                let negated_before = tokens[window_start..start]
                    .iter()
                    .any(|t| NEGATORS.contains(t));
                let after = start + cue_tokens.len();
                let negated_after =
                    tokens[after..(after + 2).min(tokens.len())].contains(&"anymore");
                if negated_before || negated_after {
                    if *label == "good" {
                        negated_good = true;
                    }
                    continue;
                }
                earliest = Some(earliest.map_or(start, |e| e.min(start)));
            }
        }
        if let Some(pos) = earliest {
            found.push((pos, family));
        }
    }
    found.sort_unstable();
    (found.into_iter().map(|(_, f)| f).collect(), negated_good)
}

/// A short fragment of the user's own words, safe to echo back — or nothing.
/// "safe" is strict: no first-person tokens (person-flipping their grammar is
/// the classic ELIZA failure), no negators, no distress content, 3–30 chars,
/// at most four words. Better no echo than a wrong one.
fn extract_topic(norm: &str) -> Option<String> {
    const LEAD_INS: &[&str] = &["about", "over"];
    const STOPPERS: &[&str] = &[
        "and", "but", "because", "so", "which", "that", "when", "if", "or", "since",
    ];
    let tokens: Vec<&str> = norm.split_whitespace().collect();
    // A one-word fragment that names nothing ("about everything…") reads as
    // parody when echoed; better no echo at all.
    const VAGUE: &[&str] = &[
        "everything",
        "anything",
        "nothing",
        "something",
        "it",
        "this",
        "that",
        "stuff",
        "things",
        "them",
        "him",
        "her",
        "you",
    ];
    let lead = tokens.iter().position(|t| LEAD_INS.contains(t))?;
    let fragment: Vec<&str> = tokens[lead + 1..]
        .iter()
        .take_while(|t| !STOPPERS.contains(*t))
        .take(4)
        .copied()
        .collect();
    if fragment.is_empty()
        || (fragment.len() == 1 && VAGUE.contains(&fragment[0]))
        || fragment
            .iter()
            .any(|t| FIRST_PERSON.contains(t) || NEGATORS.contains(t))
    {
        return None;
    }
    let joined = fragment.join(" ");
    if joined.len() < 3 || joined.len() > 30 || detect_distress(&joined) {
        return None;
    }
    Some(joined)
}

impl ReflectiveCompanion {
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

    fn is_goodbye(norm: &str) -> bool {
        const GOODBYES: &[&str] = &[
            "bye",
            "goodbye",
            "good bye",
            "bye bye",
            "good night",
            "goodnight",
            "gotta go",
            "got to go",
            "see you",
            "see ya",
            "talk later",
            "talk to you later",
            "im off",
        ];
        norm.split_whitespace().count() <= 5
            && GOODBYES
                .iter()
                .any(|g| norm == *g || norm.starts_with(&format!("{g} ")))
    }

    fn is_thanks(norm: &str) -> bool {
        norm.split_whitespace().count() <= 8
            && (norm.contains("thank you") || format!(" {norm} ").contains(" thanks "))
    }

    fn is_dont_know(norm: &str) -> bool {
        const DONT_KNOWS: &[&str] = &[
            "i dont know",
            "dont know",
            "idk",
            "i dunno",
            "dunno",
            "not sure",
            "im not sure",
            "no idea",
            "i have no idea",
        ];
        norm.split_whitespace().count() <= 5 && DONT_KNOWS.contains(&norm)
    }

    fn is_short_reply(norm: &str) -> bool {
        const SHORTS: &[&str] = &[
            "yes", "yeah", "yep", "ya", "no", "nope", "nah", "ok", "okay", "k", "sure", "maybe",
            "i guess", "fine", "mm", "hmm", "hm", "right", "true",
        ];
        SHORTS.contains(&norm)
    }

    fn is_meta_therapist(norm: &str) -> bool {
        const CUES: &[&str] = &[
            "are you a therapist",
            "are you my therapist",
            "are you a real therapist",
            "youre a therapist",
            "are you a doctor",
            "are you a counsellor",
            "are you a counselor",
            "are you a psychologist",
            "is this therapy",
        ];
        CUES.iter().any(|c| norm.contains(c))
    }

    fn is_meta_ai(norm: &str) -> bool {
        const CUES: &[&str] = &[
            "are you an ai",
            "are you ai",
            "are you a bot",
            "are you a robot",
            "are you real",
            "are you a real person",
            "are you human",
            "are you a person",
            "are you alive",
            "who are you",
            "what are you",
            "am i talking to a robot",
            "am i talking to a person",
            // Questions about Tara's inner life are about HER, not the user —
            // without these, "are you happy?" would hit the good-feelings
            // pool and Tara would celebrate herself. The honest answer is the
            // same: she doesn't have feelings, and says so.
            "are you happy",
            "are you sad",
            "are you okay",
            "are you ok",
            "do you feel",
            "do you have feelings",
            "can you feel",
            "do you get lonely",
            "do you get tired",
        ];
        CUES.iter().any(|c| norm.contains(c))
    }

    fn is_advice_seeking(norm: &str) -> bool {
        // Decision-shaped phrases ONLY — a bare trailing "?" is a question,
        // not a request for a decision scaffold, and gets QUESTION_LINES.
        const ASKS: &[&str] = &[
            "should i",
            "should we",
            "what do i do",
            "what should",
            "how do i",
            "how can i",
            "help me decide",
            "help me figure",
            "help me choose",
            "what would you do",
            "is it worth",
            "or should",
            "cant decide",
            "cant choose",
            "torn between",
        ];
        ASKS.iter().any(|a| norm.contains(a))
    }

    fn is_generic_question(message: &str, norm: &str) -> bool {
        message.trim_end().ends_with('?')
            || norm.contains("how are you")
            || norm.contains("how was your")
    }
}

/// A message at least this long (in normalised words) is an outpouring, not a
/// remark — it routes to [`VENT_LINES`].
const VENT_WORDS: usize = 60;

impl CompanionEngine for ReflectiveCompanion {
    fn reply(&self, message: &str, prior_user_turns: u64) -> CompanionReply {
        if detect_distress(message) {
            return CompanionReply {
                text: CRISIS_REPLY.to_string(),
                crisis: true,
            };
        }
        let norm = normalise(message);
        let turn = prior_user_turns;
        let topic = extract_topic(&norm);
        let (mut feelings, negated_good) = detect_feelings(&norm);
        if feelings.is_empty() && negated_good {
            feelings.push(LOW_FAMILY);
        }

        // Intent routing, most specific first. Feelings outrank the social
        // niceties on BOTH sides: "hey, I'm sad" is heard as sad rather than
        // answered with a hello, and "thanks for nothing, I'm furious" is
        // heard as furious rather than answered with "you're welcome".
        let text = if Self::is_meta_therapist(&norm) {
            choose(META_THERAPIST_LINES, &norm, turn, None)
        } else if Self::is_meta_ai(&norm) {
            choose(META_AI_LINES, &norm, turn, None)
        } else if feelings.is_empty() && Self::is_thanks(&norm) {
            choose(THANKS_LINES, &norm, turn, None)
        } else if feelings.is_empty() && Self::is_goodbye(&norm) {
            choose(GOODBYE_LINES, &norm, turn, None)
        } else if Self::is_dont_know(&norm) {
            choose(DONT_KNOW_LINES, &norm, turn, None)
        } else if Self::is_short_reply(&norm) {
            choose(SHORT_REPLY_LINES, &norm, turn, None)
        } else if feelings.len() >= 2 {
            let a = FEELINGS[feelings[0]].0;
            let b = FEELINGS[feelings[1]].0;
            choose(COMBINED_FEELING_LINES, &norm, turn, None)
                .replace("{a}", a)
                .replace("{b}", b)
        } else if let Some(&family) = feelings.first() {
            let pool = match FEELINGS[family].0 {
                "anxious" => ANXIOUS_LINES,
                "low" => LOW_LINES,
                "angry" => ANGRY_LINES,
                "worn out" => WORN_OUT_LINES,
                "lonely" => LONELY_LINES,
                _ => GOOD_LINES,
            };
            choose(pool, &norm, turn, topic.as_deref())
        } else if Self::is_greeting(&norm) {
            if turn == 0 {
                FIRST_GREETING.to_string()
            } else {
                choose(GREETING_LINES, &norm, turn, None)
            }
        } else if Self::is_advice_seeking(&norm) {
            choose(ADVICE_LINES, &norm, turn, None)
        } else if Self::is_generic_question(message, &norm) {
            choose(QUESTION_LINES, &norm, turn, None)
        } else if norm.split_whitespace().count() >= VENT_WORDS {
            choose(VENT_LINES, &norm, turn, None)
        } else {
            choose(DEFAULT_LINES, &norm, turn, topic.as_deref())
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
        let last_week: Vec<&JournalSignal> = recent.iter().filter(|s| s.age_days <= 7).collect();
        // Distinct DAYS, not entries: two low entries on one rough Monday are
        // one low day, and the nudge must not overclaim.
        let mut low_days: Vec<u64> = last_week
            .iter()
            .filter(|s| s.mood.as_deref().is_some_and(|m| LOW_MOODS.contains(&m)))
            .map(|s| s.age_days)
            .collect();
        low_days.sort_unstable();
        low_days.dedup();
        let low = low_days.len();
        let seed = recent.len() as u64;

        if low >= 2 {
            let line = choose(LOW_OPENERS, "opener", seed, None);
            line.replace("{n}", &low.to_string())
        } else if !last_week.is_empty() {
            choose(JOURNAL_OPENERS, "opener", seed, None)
        } else {
            // First contact: the one full self-introduction, honesty included.
            "I'm Tara — a private space to reflect, vent, or brainstorm. I'm not a \
             therapist, and nothing here leaves your device. What's on your mind?"
                .to_string()
        }
    }
}

/// Openers when at least two distinct recent journal days were marked low.
/// Every line keeps the literal "felt low" (pinned by tests end-to-end).
const LOW_OPENERS: &[&str] = &[
    "I noticed {n} of your journal days this week felt low. No pressure — but if you want to unpack any of it, I'm listening.",
    "Your journal says {n} days this week felt low. We can sit with that, or talk about anything else — your call.",
    "It looks like {n} days felt low this week. If any of it wants saying out loud, I'm here.",
];

/// Openers when there are recent journal entries but no low pattern.
const JOURNAL_OPENERS: &[&str] = &[
    "You've been journaling this week — want to think out loud about anything you wrote, or about something new?",
    "I saw you've been writing this week. Anything in there worth unpacking together?",
    "You've kept up the journal this week. Want to pull a thread from it, or start somewhere fresh?",
];

#[cfg(test)]
mod tests {
    use super::*;

    /// Whether `reply` is one of `pool`'s lines, allowing a `{topic}`
    /// substitution of any content.
    fn from_pool(reply: &str, pool: &[&str]) -> bool {
        pool.iter().any(|line| {
            if let Some(at) = line.find("{topic}") {
                let (pre, post) = (&line[..at], &line[at + "{topic}".len()..]);
                reply.starts_with(pre)
                    && reply.ends_with(post)
                    && reply.len() > pre.len() + post.len()
            } else {
                *line == reply
            }
        })
    }

    fn text(msg: &str, turn: u64) -> String {
        ReflectiveCompanion.reply(msg, turn).text
    }

    // ── crisis gate ─────────────────────────────────────────────────────────

    #[test]
    fn distress_cues_are_detected() {
        for msg in [
            "I want to die",
            "i've been thinking about SUICIDE a lot",
            "sometimes I just want to hurt myself.",
            "I don't want to be alive anymore",
            "thinking about self-harm again",
            "maybe everyone would be better off dead without me… I mean me",
            // Speech-to-text loves splitting reflexives — the voice path must
            // be caught by the same net.
            "i want to kill my self",
            "i keep wanting to hurt my self",
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
    fn crisis_reply_hands_off_flags_and_never_varies() {
        let first = ReflectiveCompanion.reply("I want to end it all", 3);
        assert!(first.crisis);
        assert!(first.text.contains("not a therapist"));
        assert!(!CRISIS_RESOURCES.is_empty());
        // Safety copy is vetted once and repeated verbatim — no rotation.
        let again = ReflectiveCompanion.reply("I want to end it all", 7);
        assert_eq!(first.text, again.text);
    }

    // ── feelings ────────────────────────────────────────────────────────────

    #[test]
    fn feelings_are_mirrored_from_their_own_pool() {
        assert!(from_pool(
            &text("I've been so anxious about the review", 0),
            ANXIOUS_LINES
        ));
        assert!(from_pool(&text("feeling pretty sad tonight", 1), LOW_LINES));
        assert!(from_pool(
            &text("i am so frustrated with this", 2),
            ANGRY_LINES
        ));
        assert!(from_pool(
            &text("completely exhausted lately", 3),
            WORN_OUT_LINES
        ));
        assert!(from_pool(&text("been very lonely here", 4), LONELY_LINES));
        assert!(from_pool(&text("i'm really proud of today", 5), GOOD_LINES));
    }

    #[test]
    fn negated_positives_are_never_celebrated() {
        // The engine's worst possible failure short of a missed crisis cue:
        // "i'm not happy" answered with a celebration.
        let reply = text("i'm not happy about the move", 0);
        assert!(
            !from_pool(&reply, GOOD_LINES),
            "celebrated a negation: {reply}"
        );
        assert!(
            from_pool(&reply, LOW_LINES),
            "a negated positive reads as low: {reply}"
        );
    }

    #[test]
    fn negated_negatives_are_not_mirrored() {
        let reply = text("i dont feel lonely these days", 0);
        assert!(
            !from_pool(&reply, LONELY_LINES),
            "mirrored a denied feeling: {reply}"
        );
    }

    #[test]
    fn feelings_gone_anymore_are_not_mirrored() {
        let reply = text("im not anxious anymore", 0);
        assert!(
            !from_pool(&reply, ANXIOUS_LINES),
            "mirrored a past feeling: {reply}"
        );
    }

    #[test]
    fn two_feelings_are_both_acknowledged() {
        let reply = text("I'm exhausted and scared about tomorrow", 0);
        assert!(reply.contains("worn out"), "missing first feeling: {reply}");
        assert!(reply.contains("anxious"), "missing second feeling: {reply}");
        // The user's own order wins, not pool order: exhausted came first.
        assert!(
            reply.find("worn out") < reply.find("anxious"),
            "user emphasis lost: {reply}"
        );
    }

    #[test]
    fn ordinary_uses_of_down_low_empty_are_not_feelings() {
        for msg in [
            "the wifi has been down all day",
            "my phone battery is low again",
            "the fridge is empty",
        ] {
            let reply = text(msg, 0);
            assert!(
                !from_pool(&reply, LOW_LINES),
                "mis-mirrored '{msg}': {reply}"
            );
        }
        // …while the feeling phrasings still register.
        assert!(from_pool(
            &text("ive been feeling down all week", 0),
            LOW_LINES
        ));
    }

    // ── intent routing ──────────────────────────────────────────────────────

    #[test]
    fn first_greeting_introduces_tara_honestly_and_only_once() {
        let first = text("hey", 0);
        assert!(first.contains("think out loud"));
        assert!(first.contains("leaves this device"));
        // Later greetings do not replay the pitch.
        let later = text("hey", 6);
        assert_ne!(first, later);
        assert!(from_pool(&later, GREETING_LINES));
    }

    #[test]
    fn a_greeting_carrying_a_feeling_is_heard_as_the_feeling() {
        let reply = text("hey im sad", 0);
        assert!(
            from_pool(&reply, LOW_LINES),
            "sadness swallowed by hello: {reply}"
        );
    }

    #[test]
    fn decisions_get_the_scaffold_but_ordinary_questions_do_not() {
        assert!(from_pool(&text("should i quit my job", 0), ADVICE_LINES));
        assert!(from_pool(
            &text("i cant decide between the two flats", 0),
            ADVICE_LINES
        ));
        // A bare question is a question, not a decision.
        assert!(from_pool(&text("why is the sky blue?", 0), QUESTION_LINES));
        assert!(from_pool(&text("how are you", 0), QUESTION_LINES));
    }

    #[test]
    fn thanks_goodbye_and_short_replies_are_heard() {
        assert!(from_pool(&text("thank you tara", 1), THANKS_LINES));
        assert!(from_pool(&text("thanks", 2), THANKS_LINES));
        assert!(from_pool(&text("goodnight", 1), GOODBYE_LINES));
        assert!(from_pool(&text("bye for now", 3), GOODBYE_LINES));
        assert!(from_pool(&text("yes", 1), SHORT_REPLY_LINES));
        assert!(from_pool(&text("i guess", 2), SHORT_REPLY_LINES));
        assert!(from_pool(&text("i don't know", 1), DONT_KNOW_LINES));
        assert!(from_pool(&text("idk", 4), DONT_KNOW_LINES));
    }

    #[test]
    fn meta_questions_get_honest_answers() {
        let therapist = text("are you a therapist?", 0);
        assert!(from_pool(&therapist, META_THERAPIST_LINES));
        let ai = text("are you an ai", 2);
        assert!(from_pool(&ai, META_AI_LINES));
        // Property: every meta line is honest — no personhood, no therapy.
        for line in META_THERAPIST_LINES {
            let l = line.to_lowercase();
            assert!(l.contains("no") || l.contains("not"), "hedged line: {line}");
        }
        for line in META_AI_LINES {
            let l = line.to_lowercase();
            assert!(
                l.contains("program") || l.contains("software"),
                "line hides what tara is: {line}"
            );
        }
    }

    #[test]
    fn sarcastic_thanks_carrying_a_feeling_is_heard_as_the_feeling() {
        let reply = text("thanks for nothing im furious", 0);
        assert!(
            !from_pool(&reply, THANKS_LINES),
            "answered fury with 'you're welcome': {reply}"
        );
        assert!(from_pool(&reply, ANGRY_LINES), "fury unheard: {reply}");
    }

    #[test]
    fn questions_about_taras_feelings_get_honesty_not_celebration() {
        for msg in ["are you happy?", "are you okay", "do you feel things"] {
            let reply = text(msg, 0);
            assert!(
                from_pool(&reply, META_AI_LINES),
                "'{msg}' was not answered honestly: {reply}"
            );
            assert!(
                !from_pool(&reply, GOOD_LINES),
                "'{msg}' made tara celebrate herself: {reply}"
            );
        }
    }

    #[test]
    fn every_pool_can_serve_a_reply_without_a_topic() {
        // choose() filters out {topic} lines when no fragment is available —
        // a pool made only of {topic} lines would make it panic on an empty
        // slice. Guarantee at the data level that this cannot happen.
        for (name, pool) in [
            ("greeting", GREETING_LINES),
            ("anxious", ANXIOUS_LINES),
            ("low", LOW_LINES),
            ("angry", ANGRY_LINES),
            ("worn_out", WORN_OUT_LINES),
            ("lonely", LONELY_LINES),
            ("good", GOOD_LINES),
            ("combined", COMBINED_FEELING_LINES),
            ("default", DEFAULT_LINES),
            ("advice", ADVICE_LINES),
            ("vent", VENT_LINES),
            ("thanks", THANKS_LINES),
            ("goodbye", GOODBYE_LINES),
            ("meta_ai", META_AI_LINES),
            ("meta_therapist", META_THERAPIST_LINES),
            ("short", SHORT_REPLY_LINES),
            ("dont_know", DONT_KNOW_LINES),
            ("question", QUESTION_LINES),
            ("low_openers", LOW_OPENERS),
            ("journal_openers", JOURNAL_OPENERS),
        ] {
            assert!(
                pool.iter().any(|l| !l.contains("{topic}")),
                "pool {name} has no topic-free line"
            );
            assert!(pool.len() >= 2, "pool {name} cannot rotate");
        }
    }

    #[test]
    fn long_vents_are_validated_not_interrogated() {
        let vent = "today was one of those days where everything went wrong from the \
            first minute the alarm did not go off and then the bus was late and my \
            manager decided the deadline moved up two days without asking anyone and \
            then my mum called about the house and the money situation again and i had \
            to pretend everything was fine on the team call while typing apologies to \
            three different people and by the evening i just sat in the car park for \
            half an hour because walking in the door felt like too much";
        let reply = text(vent, 0);
        assert!(
            from_pool(&reply, VENT_LINES),
            "vent got interrogated: {reply}"
        );
    }

    // ── selection machinery ─────────────────────────────────────────────────

    #[test]
    fn replies_are_deterministic_and_rotate_by_turn() {
        let a0 = text("we repainted the fence", 0);
        let a0_again = text("we repainted the fence", 0);
        assert_eq!(a0, a0_again, "same (message, turn) must be stable");
        let a1 = text("we repainted the fence", 1);
        assert_ne!(a0, a1, "consecutive turns must not repeat a line");
        // Different messages need not share a starting offset.
        assert!(from_pool(&a0, DEFAULT_LINES));
        assert!(from_pool(&a1, DEFAULT_LINES));
    }

    #[test]
    fn topics_are_echoed_only_when_safe() {
        assert_eq!(
            extract_topic("im anxious about the review tomorrow"),
            Some("the review tomorrow".to_string())
        );
        // First-person fragments are never echoed — no ELIZA person-flips.
        assert_eq!(extract_topic("im worried about my mum and me"), None);
        // Distress content is never echoed back at the user.
        assert_eq!(extract_topic("i think about self harm"), None);
        // Over-long fragments are dropped rather than truncated mid-thought.
        assert_eq!(
            extract_topic("angry about everything that has ever happened in this entire building since last november"),
            None
        );
    }

    #[test]
    fn no_reply_ever_leaks_a_template_slot() {
        let samples = [
            "hey",
            "thanks",
            "i'm anxious about the deadline",
            "sad about my dog",
            "should i move out",
            "are you real?",
            "we repainted the fence",
            "angry about the meeting and everything after",
            "i don't know",
            "bye",
        ];
        for msg in samples {
            for turn in 0..8 {
                let reply = text(msg, turn);
                assert!(!reply.is_empty(), "empty reply for {msg}");
                assert!(
                    !reply.contains('{') && !reply.contains('}'),
                    "template slot leaked for {msg} at turn {turn}: {reply}"
                );
            }
        }
    }

    // ── opener ──────────────────────────────────────────────────────────────

    #[test]
    fn opener_counts_low_days_not_low_entries() {
        let low_day = |age: u64| JournalSignal {
            mood: Some("😞".into()),
            age_days: age,
        };
        // Two low entries on the SAME rough Monday are one low day.
        let same_day = ReflectiveCompanion.opener(&[low_day(1), low_day(1)]);
        assert!(
            !same_day.contains("felt low"),
            "overclaimed low days: {same_day}"
        );
        // Two distinct low days trigger the nudge, with the right count.
        let two_days = ReflectiveCompanion.opener(&[low_day(1), low_day(2), low_day(2)]);
        assert!(
            two_days.contains("felt low"),
            "missed the pattern: {two_days}"
        );
        assert!(two_days.contains('2'), "wrong day count: {two_days}");
    }

    #[test]
    fn opener_reads_only_mood_signals() {
        let fresh = ReflectiveCompanion.opener(&[]);
        assert!(fresh.contains("not a therapist"));

        let neutral = JournalSignal {
            mood: None,
            age_days: 2,
        };
        let journal = ReflectiveCompanion.opener(&[neutral]);
        assert!(from_pool(&journal, JOURNAL_OPENERS));
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
}
