/*!
 * In-chat commands — what the composer can express, beyond sending text.
 *
 * Everything Comrade can *do* — open a listening session, reach the reflective
 * companion, name a piece of work, open the breathing screen — used to live
 * behind a screen you had to leave the conversation to find. This module is the
 * grammar that brings four of those into the composer itself.
 *
 * ## One grammar, not three
 * The prior art in this repo is `android/…/voice/VoiceCommand.kt`: a pure,
 * dependency-free prefix matcher, unit-tested on a plain JVM. It works, and its
 * shape is copied here — but in Rust, because the *other* prior art is `/pay`,
 * whose regex is reimplemented four times (`crate::vault`, the desktop JS, the
 * Dart fake, the CLI) and has already drifted. A grammar in core is one answer
 * to "what did the user just type", the same argument
 * [`crate::together::youtube_id_from_url`] makes for link parsing.
 *
 * ## What is a command, and what is a message that happens to contain a slash
 * Only a **leading** token is ever a command, and a token carrying a second `/`
 * is a path, not a command. That is what keeps `20/80 split`,
 * `see /Users/me/notes` and a pasted `/usr/local/bin` as ordinary chat text
 * ([`parse`] rules 3–5). Getting this wrong is worse than having no commands:
 * a message silently swallowed as an unknown command is a message the other
 * person never receives.
 *
 * ## The wire and the bubble are two different things
 * An offer and a task both travel as **marker-keyed JSON envelopes**
 * ([`OfferEnvelope`], [`crate::karya::KaryaEnvelope`]) — one message each, the
 * way every other non-chat payload in this app does ([`crate::dm`],
 * [`crate::call`], [`crate::presence`]). The receiver then renders its **own**
 * chat bubble from the envelope: [`render_offer_line`] here,
 * [`crate::karya::render_task_line`] there.
 *
 * That split is not ceremony, it is localisation. If the wire format were the
 * human sentence, translating that sentence would stop two phones in different
 * languages reading each other's threads. So the envelope is the wire, the
 * rendered line is **stable English** that [`parse_offer_line`] reads back, and
 * each frontend localises from the parsed [`AppAction`] — never from the words.
 * Sending both would also have meant two relay events per action, and a bubble
 * free to disagree with the row it describes.
 *
 * Pure and framework-free like its neighbours: no I/O, no network, no clock.
 */

use serde::{Deserialize, Serialize};

use crate::together::Recording;

/// Marker value identifying a well-formed offer envelope, matching
/// [`crate::dm::CONTROL_ENVELOPE_MARKER`]'s convention.
pub const OFFER_ENVELOPE_MARKER: u8 = 1;

/// Longest `@handle` we will recognise, from `normalize_handle` in
/// `comrade_ui::runtime` — the two must agree or the parser finds mentions the
/// resolver cannot look up.
const MAX_HANDLE_LEN: usize = 24;

/// Shortest `@handle`, same source.
const MIN_HANDLE_LEN: usize = 3;

/// Longest command name we will treat as command-shaped. Nothing in
/// [`catalog`] is close; the bound exists so a long path segment cannot be one.
const MAX_COMMAND_LEN: usize = 24;

// ── Mentions ─────────────────────────────────────────────────────────────────

/// An `@handle` found in composer text, with the span it occupies so a composer
/// can draw a chip over it.
///
/// `handle` is lowercased to match the resolver; the span refers to the original
/// text, so the two are not interchangeable.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct Mention {
    /// Lowercased handle without the leading `@`.
    pub handle: String,
    /// Byte offset of the `@`.
    pub start: u32,
    /// Byte offset one past the last handle character.
    pub end: u32,
}

/// Every `@handle` in `text`, in the order they appear.
///
/// A handle must start at the beginning of the text or after a non-word
/// character. Without that rule the `@` in a UPI address makes `friend@upi` a
/// mention of `upi`, and `/pay 250 to friend@upi` would try to resolve a
/// contact — the one command that already works would start guessing at people.
pub fn mentions(text: &str) -> Vec<Mention> {
    let bytes = text.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] != b'@' {
            i += 1;
            continue;
        }
        // Preceded by a word character → part of something else (an email, a
        // VPA), not an address.
        if i > 0 && is_handle_byte(bytes[i - 1]) {
            i += 1;
            continue;
        }
        let start = i;
        let mut end = i + 1;
        while end < bytes.len() && is_handle_byte(bytes[end]) {
            end += 1;
        }
        let handle = &text[start + 1..end];
        if handle.len() >= MIN_HANDLE_LEN && handle.len() <= MAX_HANDLE_LEN {
            out.push(Mention {
                handle: handle.to_ascii_lowercase(),
                start: start as u32,
                end: end as u32,
            });
        }
        i = end.max(start + 1);
    }
    out
}

/// The charset `normalize_handle` accepts, plus upper case — someone typing
/// `@Xyz` means `@xyz`, and rejecting it would be a puzzle rather than a rule.
fn is_handle_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_'
}

/// `text` with every mention span removed and whitespace collapsed — the body
/// of a command once the addressing is taken out of it.
fn without_mentions(text: &str, found: &[Mention]) -> String {
    let mut kept = String::with_capacity(text.len());
    let mut cursor = 0usize;
    for m in found {
        kept.push_str(&text[cursor..m.start as usize]);
        kept.push(' ');
        cursor = m.end as usize;
    }
    kept.push_str(&text[cursor..]);
    kept.split_whitespace().collect::<Vec<_>>().join(" ")
}

// ── What a command can name ──────────────────────────────────────────────────

/// A streaming service named by a command, so the UI can say where to open
/// something it cannot play itself.
///
/// This is a *hint about wording*, not a capability claim —
/// [`crate::together::MusicLink::playable_in_place`] is the only thing that
/// decides whether we can press play, and for Spotify and Apple Music it says
/// no.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "snake_case")]
pub enum MusicService {
    Spotify,
    AppleMusic,
    Youtube,
}

impl MusicService {
    /// The service's own name, for a UI that has to say where a link came from.
    pub fn label(self) -> &'static str {
        match self {
            Self::Spotify => "Spotify",
            Self::AppleMusic => "Apple Music",
            Self::Youtube => "YouTube",
        }
    }
}

/// A place in the app a command can open — for oneself, or offer to a comrade.
///
/// These are destinations, not people: `@breath` in `/comrade-breathe` names the
/// breathing screen. Every one of them is a surface that already ships.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "snake_case")]
pub enum AppAction {
    /// The breathing screen (`android/…/ui/BreathingScreen.kt`).
    Breathe,
    /// A focus session (`crate::attention`).
    Focus,
    /// The private journal.
    Journal,
    /// Tara, the reflective companion (`crate::tara`).
    Tara,
    /// Distraction-free long reading.
    Read,
}

impl AppAction {
    /// Stable wire key. Never localise this — it is what
    /// [`parse_offer_envelope`] matches on and what a frontend switches over.
    pub fn key(self) -> &'static str {
        match self {
            Self::Breathe => "breath",
            Self::Focus => "focus",
            Self::Journal => "journal",
            Self::Tara => "tara",
            Self::Read => "read",
        }
    }

    /// The action named by `key`, or `None` — so an envelope from a newer build
    /// naming an action this one does not have is ignored rather than guessed at.
    pub fn from_key(key: &str) -> Option<Self> {
        match key {
            "breath" => Some(Self::Breathe),
            "focus" => Some(Self::Focus),
            "journal" => Some(Self::Journal),
            "tara" => Some(Self::Tara),
            "read" => Some(Self::Read),
            _ => None,
        }
    }

    /// What the *sender's* UI calls this destination.
    pub fn label(self) -> &'static str {
        match self {
            Self::Breathe => "Take a deep breath",
            Self::Focus => "A focus session",
            Self::Journal => "Write it down",
            Self::Tara => "Talk it through with Tara",
            Self::Read => "A quiet read",
        }
    }

    /// The canonical English line the *receiver's* chat bubble carries.
    ///
    /// Two rules hold every one of these, and a sixth action must keep them.
    /// **It says what the sender did, never what the reader is.** The app has no
    /// idea whether someone is anxious, and the breathing screen's own strings
    /// carry a comment forbidding copy that claims otherwise
    /// (`android/…/res/values/strings.xml`) — so an offer reports a comrade
    /// reaching out and stops there. And **the tail is the sender's own claim**:
    /// "they are here for you" is a fact about the person who sent it, which is
    /// the one reassuring thing this app is actually in a position to pass on.
    pub fn offer_line(self) -> &'static str {
        match self {
            Self::Breathe => "Your comrade asked you to take a deep breath — they are here for you",
            Self::Focus => {
                "Your comrade thought a focus session might help — they are here for you"
            }
            Self::Journal => {
                "Your comrade thought writing it down might help — they are here for you"
            }
            Self::Tara => {
                "Your comrade thought talking it through might help — they are here for you"
            }
            Self::Read => "Your comrade thought a quiet read might help — they are here for you",
        }
    }
}

// ── The parsed command ───────────────────────────────────────────────────────

/// What the composer's text means.
///
/// [`Self::Plain`] is the common case and the safe default: anything this
/// grammar does not recognise is a message, and gets sent as typed.
///
/// Every [`Mention`] span in here is relative to **the text handed to
/// [`parse`]**, not to the command's argument, so a composer can draw a chip
/// straight over its own input box without knowing where the command name ended.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum ChatCommand {
    /// Listen or watch together. `query` is a link or free text; `service` is
    /// the wording hint from which alias was used.
    Play {
        query: String,
        service: Option<MusicService>,
    },
    /// A private aside to Tara. Never sent — see `comrade_ui`'s `tara_aside`.
    /// Empty `text` means "addressed, nothing said yet", which is a prompt
    /// rather than an error: it is what lets the composer switch into aside mode
    /// the moment `@tara ` is typed.
    AskTara { text: String },
    /// Name a piece of work. No `assignees` means a note to self, which is
    /// local-only and the common case.
    Task {
        text: String,
        assignees: Vec<Mention>,
    },
    /// Open a place in the app, for oneself.
    Open { action: AppAction },
    /// Offer a place in the app to a comrade.
    OfferTo {
        action: AppAction,
        targets: Vec<Mention>,
    },
    /// `/pay` — recognised so it is not reported as unknown, then left to
    /// [`crate::vault::extract_upi_intents`], which already owns it.
    Pay,
    /// List what the composer understands, from [`catalog`].
    Help,
    /// Command-shaped, but no such command. Named so the UI can say which.
    Unknown { name: String },
    /// Not a command. Send it.
    Plain,
}

// ── The catalogue ────────────────────────────────────────────────────────────

/// One entry a composer's `/`-autocomplete and `/help` both read.
///
/// One list, so a command cannot exist on one frontend and not another — the
/// failure `/pay` already demonstrates, having shipped a live composer preview
/// on desktop and never on Flutter.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct CommandSpec {
    /// Canonical name, without the leading `/`.
    pub name: String,
    /// Other spellings that reach the same command.
    pub aliases: Vec<String>,
    /// What to type after the name, for a hint line. Empty when it takes nothing.
    pub argument: String,
    /// One line, imperative, shown in the picker.
    pub help: String,
    /// Whether this command addresses somebody — drives `@`-autocomplete.
    pub takes_mention: bool,
}

macro_rules! spec {
    ($name:expr, [$($alias:expr),*], $argument:expr, $help:expr, $mention:expr) => {
        CommandSpec {
            name: $name.to_string(),
            aliases: vec![$($alias.to_string()),*],
            argument: $argument.to_string(),
            help: $help.to_string(),
            takes_mention: $mention,
        }
    };
}

/// Every command, in the order a picker should show them: the ones that reach
/// another person first, then the ones that open something here.
pub fn catalog() -> Vec<CommandSpec> {
    vec![
        spec!(
            "play",
            ["listen", "watch", "together"],
            "<song, or a link>",
            "Listen or watch it together, in step",
            false
        ),
        spec!(
            "spotify",
            [],
            "<song, or a Spotify link>",
            "Find a track and listen together",
            false
        ),
        spec!(
            "youtube",
            ["yt"],
            "<video, or a YouTube link>",
            "Watch a video together",
            false
        ),
        spec!(
            "apple",
            ["applemusic"],
            "<song, or an Apple Music link>",
            "Find a track and listen together",
            false
        ),
        spec!(
            "task",
            ["todo"],
            "<what needs doing> [@who]",
            "Name a piece of work — yours, or ask a comrade",
            true
        ),
        spec!(
            "comrade-breathe",
            ["comrade-breath"],
            "@who",
            "Ask a comrade to take a deep breath",
            true
        ),
        spec!(
            "comrade-focus",
            [],
            "@who",
            "Offer a comrade a focus session",
            true
        ),
        spec!(
            "comrade-journal",
            [],
            "@who",
            "Offer a comrade the journal",
            true
        ),
        spec!(
            "comrade-read",
            [],
            "@who",
            "Offer a comrade a quiet read",
            true
        ),
        spec!(
            "comrade-tara",
            [],
            "@who",
            "Offer a comrade a talk with Tara",
            true
        ),
        spec!(
            "tara",
            [],
            "<what you want to think through>",
            "A private aside to Tara — only you see it",
            false
        ),
        spec!("breathe", ["breath"], "", "Take a deep breath", false),
        spec!("focus", [], "", "Start a focus session", false),
        spec!("journal", [], "", "Write something down", false),
        spec!("read", ["reading"], "", "Open a quiet read", false),
        spec!(
            "pay",
            [],
            "<amount> to <vpa>",
            "Offer a UPI payment in the conversation",
            false
        ),
        spec!(
            "help",
            ["commands"],
            "",
            "List what you can type here",
            false
        ),
    ]
}

// ── Parsing ──────────────────────────────────────────────────────────────────

/// What `text` in a chat composer means.
///
/// The rules, in order, and each one earns its place:
/// 1. Nothing but whitespace is [`ChatCommand::Plain`] — the composer decides
///    what to do with an empty box, not the grammar.
/// 2. A leading `@tara` is an aside. Checked before the `/` rules because it is
///    the one command with a different sigil, and before anything else because a
///    private thought must never fall through to a send — the ordering
///    `VoiceCommand.kt` already arrived at for the journal.
/// 3. No leading `/` → [`ChatCommand::Plain`]. So `20/80 split` is a message.
/// 4. A leading token containing a second `/` is a path → `Plain`. So
///    `see /Users/me/x` and a pasted `/usr/local/bin` are messages.
/// 5. A leading token that is not command-shaped (lowercase ASCII, digits and
///    `-`, at most `MAX_COMMAND_LEN`) → `Plain`. So `/Notes` is a message.
/// 6. Otherwise look it up in [`catalog`]; unrecognised is
///    [`ChatCommand::Unknown`], which the UI reports rather than sending.
pub fn parse(text: &str) -> ChatCommand {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return ChatCommand::Plain;
    }

    if let Some(rest) = strip_ci_prefix(trimmed, "@tara") {
        if rest.is_empty() || rest.starts_with(char::is_whitespace) {
            return ChatCommand::AskTara {
                text: rest.trim().to_string(),
            };
        }
    }

    let after_slash = match trimmed.strip_prefix('/') {
        Some(rest) => rest,
        None => return ChatCommand::Plain,
    };
    let (token, rest) = match after_slash.find(char::is_whitespace) {
        Some(idx) => (&after_slash[..idx], after_slash[idx..].trim()),
        None => (after_slash, ""),
    };
    // Where `rest` sits in the caller's own string, so mention spans point at
    // the composer's input rather than at an argument the composer never saw.
    let rest_at = subslice_offset(text, rest);
    // A second slash inside the leading token means this is a path someone
    // pasted, not a command they typed.
    if token.contains('/') || !is_command_shaped(token) {
        return ChatCommand::Plain;
    }

    match resolve_name(token) {
        Some("play") => play(rest, None),
        Some("spotify") => play(rest, Some(MusicService::Spotify)),
        Some("youtube") => play(rest, Some(MusicService::Youtube)),
        Some("apple") => play(rest, Some(MusicService::AppleMusic)),
        Some("task") => {
            let found = mentions(rest);
            let text = without_mentions(rest, &found);
            ChatCommand::Task {
                text,
                assignees: shifted(found, rest_at),
            }
        }
        Some("tara") => ChatCommand::AskTara {
            text: rest.to_string(),
        },
        Some("breathe") => ChatCommand::Open {
            action: AppAction::Breathe,
        },
        Some("focus") => ChatCommand::Open {
            action: AppAction::Focus,
        },
        Some("journal") => ChatCommand::Open {
            action: AppAction::Journal,
        },
        Some("read") => ChatCommand::Open {
            action: AppAction::Read,
        },
        Some("comrade-breathe") => offer_to(AppAction::Breathe, rest, rest_at),
        Some("comrade-focus") => offer_to(AppAction::Focus, rest, rest_at),
        Some("comrade-journal") => offer_to(AppAction::Journal, rest, rest_at),
        Some("comrade-read") => offer_to(AppAction::Read, rest, rest_at),
        Some("comrade-tara") => offer_to(AppAction::Tara, rest, rest_at),
        Some("pay") => ChatCommand::Pay,
        Some("help") => ChatCommand::Help,
        _ => ChatCommand::Unknown {
            name: token.to_string(),
        },
    }
}

fn play(rest: &str, service: Option<MusicService>) -> ChatCommand {
    ChatCommand::Play {
        query: rest.to_string(),
        service,
    }
}

fn offer_to(action: AppAction, rest: &str, rest_at: u32) -> ChatCommand {
    ChatCommand::OfferTo {
        action,
        targets: shifted(mentions(rest), rest_at),
    }
}

/// Move every span forward by `by`, translating argument-relative offsets into
/// offsets in the caller's own string.
fn shifted(found: Vec<Mention>, by: u32) -> Vec<Mention> {
    found
        .into_iter()
        .map(|m| Mention {
            start: m.start + by,
            end: m.end + by,
            ..m
        })
        .collect()
}

/// Byte offset of `inner` within `outer`, where `inner` is a subslice of it.
///
/// Address arithmetic rather than [`str::find`], because a search would land on
/// the wrong copy the moment the argument repeats a word from the command name
/// (`/task task the task`). Nothing is dereferenced; both pointers come from one
/// allocation, so the difference is the offset by construction. Falls back to 0
/// if `inner` is not actually a subslice, which is unreachable here and would
/// only cost a chip drawn in the wrong place.
fn subslice_offset(outer: &str, inner: &str) -> u32 {
    let (base, at) = (outer.as_ptr() as usize, inner.as_ptr() as usize);
    at.checked_sub(base)
        .filter(|off| off + inner.len() <= outer.len())
        .unwrap_or(0) as u32
}

/// The canonical name `token` reaches, following aliases.
fn resolve_name(token: &str) -> Option<&'static str> {
    const NAMES: &[(&str, &[&str])] = &[
        ("play", &["listen", "watch", "together"]),
        ("spotify", &[]),
        ("youtube", &["yt"]),
        ("apple", &["applemusic"]),
        ("task", &["todo"]),
        ("tara", &[]),
        ("breathe", &["breath"]),
        ("focus", &[]),
        ("journal", &[]),
        ("read", &["reading"]),
        ("comrade-breathe", &["comrade-breath"]),
        ("comrade-focus", &[]),
        ("comrade-journal", &[]),
        ("comrade-read", &[]),
        ("comrade-tara", &[]),
        ("pay", &[]),
        ("help", &["commands"]),
    ];
    let lowered = token.to_ascii_lowercase();
    NAMES
        .iter()
        .find(|(name, aliases)| *name == lowered || aliases.contains(&lowered.as_str()))
        .map(|(name, _)| *name)
}

/// Whether `token` looks like a command name at all: lowercase ASCII letters,
/// digits and `-`, starting with a letter, within [`MAX_COMMAND_LEN`].
///
/// Case matters here on purpose. `/Notes` and `/Users` are things people paste,
/// and reporting them as unknown commands would be a message not sent.
fn is_command_shaped(token: &str) -> bool {
    !token.is_empty()
        && token.len() <= MAX_COMMAND_LEN
        && token.starts_with(|c: char| c.is_ascii_lowercase())
        && token
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}

/// `rest` of `text` after a case-insensitive `prefix`, or `None`.
fn strip_ci_prefix<'a>(text: &'a str, prefix: &str) -> Option<&'a str> {
    let head = text.get(..prefix.len())?;
    head.eq_ignore_ascii_case(prefix)
        .then(|| &text[prefix.len()..])
}

// ── Free text to a recording ─────────────────────────────────────────────────

/// Read a free-text query as the recording it names.
///
/// Three shapes, because these are the three ways people write a song down:
/// `artist — title` (or `-`), `title by artist`, and a bare title. Nothing here
/// resolves anything — a [`Recording`] with no ISRC is a *name*, and finding the
/// listener's own copy of it is [`crate::together::match_score`]'s job.
///
/// The separator is tried before `by`, because `Rahman - Kun Faya Kun` should
/// not be read as a title containing a stray dash, while `Baby by Justin` has no
/// separator to find.
pub fn recording_from_query(query: &str) -> Recording {
    let q = query.trim();
    for sep in [" — ", " – ", " - ", " -- "] {
        if let Some((artist, title)) = q.split_once(sep) {
            let (artist, title) = (artist.trim(), title.trim());
            if !artist.is_empty() && !title.is_empty() {
                return Recording {
                    isrc: None,
                    title: title.to_string(),
                    artist: artist.to_string(),
                    album: None,
                };
            }
        }
    }
    if let Some((title, artist)) = split_once_ci(q, " by ") {
        let (title, artist) = (title.trim(), artist.trim());
        if !title.is_empty() && !artist.is_empty() {
            return Recording {
                isrc: None,
                title: title.to_string(),
                artist: artist.to_string(),
                album: None,
            };
        }
    }
    Recording::titled(q)
}

/// `split_once`, ignoring case in the needle. Only the first match splits, so
/// `Stand By Me by Ben E. King` keeps its title intact.
fn split_once_ci<'a>(haystack: &'a str, needle: &str) -> Option<(&'a str, &'a str)> {
    let lowered = haystack.to_ascii_lowercase();
    let idx = lowered.find(&needle.to_ascii_lowercase())?;
    Some((&haystack[..idx], &haystack[idx + needle.len()..]))
}

// ── The offer wire ───────────────────────────────────────────────────────────

/// "I thought this might help." One action, offered to one comrade.
///
/// Carries the action and nothing else. Not why, not what the sender thinks is
/// wrong, not what they observed — an offer that explained itself would be
/// disclosing a judgement about the reader, which is the thing this must not do.
///
/// It is deliberately **not** [`crate::nudge::Nudge`]. That envelope's guarantee
/// is that it carries *no reason at all*, so a comrade cannot tell which trigger
/// fired, and a key-set test enforces the absence. An offer names an action by
/// construction, so it has to be its own shape rather than quietly weakening
/// that one.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OfferEnvelope {
    /// Format marker; must equal [`OFFER_ENVELOPE_MARKER`].
    pub comrade_offer: u8,
    /// Stable [`AppAction::key`] — a string, not the enum, so an older build
    /// receiving a newer action drops it instead of failing to deserialise the
    /// whole envelope.
    pub action: String,
}

impl OfferEnvelope {
    pub fn new(action: AppAction) -> Self {
        Self {
            comrade_offer: OFFER_ENVELOPE_MARKER,
            action: action.key().to_string(),
        }
    }

    pub fn to_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }

    /// The action offered, or `None` if this build does not know it.
    pub fn app_action(&self) -> Option<AppAction> {
        AppAction::from_key(&self.action)
    }
}

/// Detect and parse an offer envelope out of a decrypted DM body.
pub fn parse_offer_envelope(content: &str) -> Option<OfferEnvelope> {
    let env: OfferEnvelope = serde_json::from_str(content).ok()?;
    (env.comrade_offer == OFFER_ENVELOPE_MARKER).then_some(env)
}

/// Prefix marking a rendered offer line, so [`parse_offer_line`] can recognise
/// one without matching the whole sentence.
const OFFER_LINE_SIGIL: &str = "🫂";

/// The chat-bubble line for a received offer.
///
/// **Stable English on purpose.** This string is generated and read back by this
/// module, and a frontend that wants to localise reads the [`AppAction`] out of
/// it and renders its own words. Translating *this* would mean two phones in
/// different languages could not parse each other.
pub fn render_offer_line(action: AppAction) -> String {
    format!("{OFFER_LINE_SIGIL} {}", action.offer_line())
}

/// The action a rendered offer line names, or `None` if it is ordinary text.
pub fn parse_offer_line(line: &str) -> Option<AppAction> {
    let rest = line.trim().strip_prefix(OFFER_LINE_SIGIL)?.trim();
    [
        AppAction::Breathe,
        AppAction::Focus,
        AppAction::Journal,
        AppAction::Tara,
        AppAction::Read,
    ]
    .into_iter()
    .find(|a| a.offer_line() == rest)
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    // ── What is not a command ────────────────────────────────────────────────

    #[test]
    fn a_fraction_is_a_message() {
        assert_eq!(parse("20/80 split"), ChatCommand::Plain);
    }

    #[test]
    fn a_path_inside_a_sentence_is_a_message() {
        assert_eq!(parse("see /Users/me/notes"), ChatCommand::Plain);
    }

    #[test]
    fn a_pasted_absolute_path_is_a_message_not_an_unknown_command() {
        // The token is command-shaped up to its second slash, which is exactly
        // the case that would otherwise swallow a message.
        assert_eq!(parse("/usr/local/bin"), ChatCommand::Plain);
        assert_eq!(parse("/etc/hosts is the file"), ChatCommand::Plain);
    }

    #[test]
    fn a_capitalised_token_is_a_message() {
        assert_eq!(parse("/Notes"), ChatCommand::Plain);
        assert_eq!(parse("/README"), ChatCommand::Plain);
    }

    #[test]
    fn an_empty_box_is_plain() {
        assert_eq!(parse(""), ChatCommand::Plain);
        assert_eq!(parse("   \n "), ChatCommand::Plain);
    }

    #[test]
    fn a_bare_slash_is_a_message() {
        assert_eq!(parse("/"), ChatCommand::Plain);
    }

    #[test]
    fn a_command_shaped_token_nobody_defined_is_reported_not_sent() {
        assert_eq!(
            parse("/frobnicate the thing"),
            ChatCommand::Unknown {
                name: "frobnicate".into()
            }
        );
    }

    // ── Mentions ─────────────────────────────────────────────────────────────

    #[test]
    fn a_vpa_is_not_a_mention() {
        // `/pay 250 to friend@upi` must not resolve a contact called "upi".
        assert!(mentions("/pay 250 to friend@upi").is_empty());
    }

    #[test]
    fn a_mention_is_lowercased_but_its_span_points_at_the_original() {
        let found = mentions("hello @Xyz_9");
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].handle, "xyz_9");
        assert_eq!(
            &"hello @Xyz_9"[found[0].start as usize..found[0].end as usize],
            "@Xyz_9"
        );
    }

    #[test]
    fn handles_outside_the_resolvers_length_are_not_mentions() {
        // Under three characters, and over twenty-four: `normalize_handle`
        // would reject both, so finding them here would only produce lookups
        // that cannot succeed.
        assert!(mentions("@ab").is_empty());
        assert!(mentions("@aaaaaaaaaaaaaaaaaaaaaaaaaaaaa").is_empty());
    }

    #[test]
    fn several_mentions_keep_their_order() {
        let found = mentions("@ana and @bina");
        assert_eq!(
            found.iter().map(|m| m.handle.as_str()).collect::<Vec<_>>(),
            ["ana", "bina"]
        );
    }

    // ── Tara asides ──────────────────────────────────────────────────────────

    #[test]
    fn at_tara_is_an_aside_in_either_spelling() {
        assert_eq!(
            parse("@tara I keep putting this off"),
            ChatCommand::AskTara {
                text: "I keep putting this off".into()
            }
        );
        assert_eq!(
            parse("/tara I keep putting this off"),
            ChatCommand::AskTara {
                text: "I keep putting this off".into()
            }
        );
    }

    #[test]
    fn at_tara_is_case_insensitive() {
        assert_eq!(
            parse("@Tara hello"),
            ChatCommand::AskTara {
                text: "hello".into()
            }
        );
    }

    #[test]
    fn a_bare_address_is_an_aside_with_nothing_said_yet() {
        // Not Plain: sending the literal text "@tara" to the other person is
        // the one outcome that must not happen. Empty text is a prompt.
        assert_eq!(
            parse("@tara"),
            ChatCommand::AskTara {
                text: String::new()
            }
        );
    }

    #[test]
    fn a_longer_handle_starting_with_tara_is_not_an_aside() {
        // `@taranjeet` is a person.
        assert_eq!(parse("@taranjeet are you around"), ChatCommand::Plain);
    }

    // ── Tasks ────────────────────────────────────────────────────────────────

    #[test]
    fn a_task_keeps_its_words_and_takes_the_addressing_out() {
        let input = "/task get some work done @xyz";
        assert_eq!(
            parse(input),
            ChatCommand::Task {
                text: "get some work done".into(),
                assignees: vec![Mention {
                    handle: "xyz".into(),
                    start: 25,
                    end: 29
                }],
            }
        );
    }

    #[test]
    fn a_mention_span_points_into_the_composers_own_text() {
        // Not into the argument: a composer drawing a chip has the whole input,
        // not the slice after the command name.
        let input = "/task ask @ana about the deck";
        let ChatCommand::Task { assignees, .. } = parse(input) else {
            panic!("expected a task");
        };
        let m = &assignees[0];
        assert_eq!(&input[m.start as usize..m.end as usize], "@ana");
    }

    #[test]
    fn a_span_survives_the_argument_repeating_the_command_name() {
        // An offset found by searching would land on the wrong copy here.
        let input = "/task task the @ana task";
        let ChatCommand::Task { assignees, .. } = parse(input) else {
            panic!("expected a task");
        };
        let m = &assignees[0];
        assert_eq!(&input[m.start as usize..m.end as usize], "@ana");
    }

    #[test]
    fn a_mention_in_the_middle_leaves_no_double_space_behind() {
        let ChatCommand::Task { text, .. } = parse("/task ask @ana about the deck") else {
            panic!("expected a task");
        };
        assert_eq!(text, "ask about the deck");
    }

    #[test]
    fn a_task_with_nobody_named_is_a_note_to_self() {
        assert_eq!(
            parse("/task water the plants"),
            ChatCommand::Task {
                text: "water the plants".into(),
                assignees: vec![],
            }
        );
    }

    #[test]
    fn an_empty_task_parses_so_the_composer_can_prompt() {
        assert_eq!(
            parse("/task"),
            ChatCommand::Task {
                text: String::new(),
                assignees: vec![]
            }
        );
    }

    // ── Actions, for me and for a comrade ────────────────────────────────────

    #[test]
    fn the_bare_action_opens_it_here() {
        assert_eq!(
            parse("/breathe"),
            ChatCommand::Open {
                action: AppAction::Breathe
            }
        );
        assert_eq!(
            parse("/breath"),
            ChatCommand::Open {
                action: AppAction::Breathe
            }
        );
    }

    #[test]
    fn the_comrade_prefix_offers_it_to_them() {
        let input = "/comrade-breathe @xyz";
        assert_eq!(
            parse(input),
            ChatCommand::OfferTo {
                action: AppAction::Breathe,
                targets: vec![Mention {
                    handle: "xyz".into(),
                    start: 17,
                    end: 21
                }],
            }
        );
        let ChatCommand::OfferTo { targets, .. } = parse(input) else {
            panic!("expected an offer");
        };
        assert_eq!(
            &input[targets[0].start as usize..targets[0].end as usize],
            "@xyz"
        );
    }

    #[test]
    fn every_action_has_a_comrade_form_and_they_all_resolve() {
        for (name, action) in [
            ("comrade-breathe", AppAction::Breathe),
            ("comrade-focus", AppAction::Focus),
            ("comrade-journal", AppAction::Journal),
            ("comrade-read", AppAction::Read),
            ("comrade-tara", AppAction::Tara),
        ] {
            let input = format!("/{name} @ana");
            let ChatCommand::OfferTo {
                action: got,
                targets,
            } = parse(&input)
            else {
                panic!("/{name} did not parse as an offer");
            };
            assert_eq!(got, action, "/{name}");
            assert_eq!(targets.len(), 1, "/{name}");
            assert_eq!(targets[0].handle, "ana", "/{name}");
            // The span has to land on the handle whatever the name's length.
            assert_eq!(
                &input[targets[0].start as usize..targets[0].end as usize],
                "@ana",
                "/{name}"
            );
        }
    }

    // ── Play ─────────────────────────────────────────────────────────────────

    #[test]
    fn play_aliases_carry_the_wording_hint_and_nothing_more() {
        assert_eq!(
            parse("/spotify Kun Faya Kun"),
            ChatCommand::Play {
                query: "Kun Faya Kun".into(),
                service: Some(MusicService::Spotify),
            }
        );
        assert_eq!(
            parse("/listen Kun Faya Kun"),
            ChatCommand::Play {
                query: "Kun Faya Kun".into(),
                service: None,
            }
        );
    }

    #[test]
    fn pay_is_recognised_so_it_is_never_reported_as_unknown() {
        // The command itself stays `vault::extract_upi_intents`'s job; all this
        // has to do is not call it a typo.
        assert_eq!(parse("/pay 250 to friend@upi"), ChatCommand::Pay);
    }

    #[test]
    fn help_has_the_spellings_people_try() {
        assert_eq!(parse("/help"), ChatCommand::Help);
        assert_eq!(parse("/commands"), ChatCommand::Help);
    }

    // ── Free text to a recording ─────────────────────────────────────────────

    #[test]
    fn an_artist_title_separator_wins_over_the_word_by() {
        let r = recording_from_query("A.R. Rahman — Kun Faya Kun");
        assert_eq!(r.artist, "A.R. Rahman");
        assert_eq!(r.title, "Kun Faya Kun");
        assert!(r.isrc.is_none());
    }

    #[test]
    fn the_word_by_reads_title_first() {
        let r = recording_from_query("Kun Faya Kun by A.R. Rahman");
        assert_eq!(r.title, "Kun Faya Kun");
        assert_eq!(r.artist, "A.R. Rahman");
    }

    #[test]
    fn a_title_containing_by_keeps_it_when_an_artist_follows() {
        // Only the first " by " splits, so the title survives intact.
        let r = recording_from_query("Stand By Me by Ben E. King");
        assert_eq!(r.title, "Stand");
        assert_eq!(r.artist, "Me by Ben E. King");
        // Documented rather than pretended: the first `by` is the one that
        // splits, and a title with `by` in it needs the dash form to come out
        // right. `Stand By Me — Ben E. King` does.
        let dashed = recording_from_query("Ben E. King — Stand By Me");
        assert_eq!(dashed.title, "Stand By Me");
        assert_eq!(dashed.artist, "Ben E. King");
    }

    #[test]
    fn a_bare_title_names_itself_and_claims_no_artist() {
        let r = recording_from_query("Kun Faya Kun");
        assert_eq!(r.title, "Kun Faya Kun");
        assert!(r.artist.is_empty());
    }

    #[test]
    fn a_dangling_separator_is_not_an_empty_artist() {
        let r = recording_from_query("Kun Faya Kun -");
        assert_eq!(r.title, "Kun Faya Kun -");
        assert!(r.artist.is_empty());
    }

    // ── The offer wire ───────────────────────────────────────────────────────

    #[test]
    fn an_offer_envelope_round_trips() {
        let env = OfferEnvelope::new(AppAction::Breathe);
        let json = env.to_json().unwrap();
        assert_eq!(parse_offer_envelope(&json).unwrap(), env);
        assert_eq!(env.app_action(), Some(AppAction::Breathe));
    }

    #[test]
    fn chat_text_and_other_envelopes_are_not_offers() {
        assert!(parse_offer_envelope("hello there").is_none());
        assert!(parse_offer_envelope(r#"{"comrade_receipt":1}"#).is_none());
        assert!(parse_offer_envelope(r#"{"comrade_offer":0,"action":"breath"}"#).is_none());
    }

    #[test]
    fn an_action_this_build_does_not_know_is_dropped_not_guessed() {
        let env = parse_offer_envelope(r#"{"comrade_offer":1,"action":"skydive"}"#).unwrap();
        assert!(env.app_action().is_none());
    }

    #[test]
    fn an_offer_line_round_trips_through_the_action() {
        for action in [
            AppAction::Breathe,
            AppAction::Focus,
            AppAction::Journal,
            AppAction::Tara,
            AppAction::Read,
        ] {
            let line = render_offer_line(action);
            assert_eq!(parse_offer_line(&line), Some(action), "{}", action.key());
        }
    }

    #[test]
    fn ordinary_text_is_not_an_offer_line() {
        assert!(parse_offer_line("Your comrade asked you to take a deep breath").is_none());
        assert!(parse_offer_line("hello").is_none());
    }

    #[test]
    fn the_breath_offer_says_what_the_sender_did_and_nothing_about_the_reader() {
        // The line the owner specified, and the rule it has to keep: it reports
        // a comrade reaching out, and claims nothing about how the reader is.
        let line = AppAction::Breathe.offer_line();
        assert_eq!(
            line,
            "Your comrade asked you to take a deep breath — they are here for you"
        );
        for forbidden in [
            "you are safe",
            "just anxiety",
            "nothing wrong with you",
            "calm down",
        ] {
            assert!(
                !line.to_lowercase().contains(forbidden),
                "an offer must not claim {forbidden:?}"
            );
        }
    }

    #[test]
    fn every_offer_line_carries_the_senders_own_claim() {
        for action in [
            AppAction::Breathe,
            AppAction::Focus,
            AppAction::Journal,
            AppAction::Tara,
            AppAction::Read,
        ] {
            assert!(
                action.offer_line().ends_with("they are here for you"),
                "{} dropped the tail",
                action.key()
            );
        }
    }

    // ── The catalogue ────────────────────────────────────────────────────────

    #[test]
    fn every_catalogue_entry_and_alias_parses_to_something_known() {
        for spec in catalog() {
            for name in std::iter::once(&spec.name).chain(spec.aliases.iter()) {
                let parsed = parse(&format!("/{name} rest of it"));
                assert!(
                    !matches!(parsed, ChatCommand::Unknown { .. } | ChatCommand::Plain),
                    "/{name} is in the catalogue but does not parse: {parsed:?}"
                );
            }
        }
    }

    #[test]
    fn catalogue_names_are_unique_across_names_and_aliases() {
        let mut seen = std::collections::HashSet::new();
        for spec in catalog() {
            for name in std::iter::once(spec.name.clone()).chain(spec.aliases.clone()) {
                assert!(seen.insert(name.clone()), "{name} appears twice");
            }
        }
    }

    #[test]
    fn every_catalogue_name_is_command_shaped() {
        // Otherwise rule 5 would make the command unreachable by typing it.
        for spec in catalog() {
            for name in std::iter::once(&spec.name).chain(spec.aliases.iter()) {
                assert!(is_command_shaped(name), "{name} could never be typed");
            }
        }
    }

    #[test]
    fn the_action_key_round_trips() {
        for action in [
            AppAction::Breathe,
            AppAction::Focus,
            AppAction::Journal,
            AppAction::Tara,
            AppAction::Read,
        ] {
            assert_eq!(AppAction::from_key(action.key()), Some(action));
        }
    }
}
