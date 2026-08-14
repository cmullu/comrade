/*!
 * Handing one journal note to one person — the wire form, and reading it back.
 *
 * The journal is wellbeing pillar #1 and the most private thing in the app:
 * entries are sealed in the encrypted store and nothing about them is
 * synchronised, published or uploaded. That is still true. What was missing is
 * the other half of why people write things down — *"this is what I meant, and
 * I couldn't say it out loud"* — which today means retyping the note into the
 * composer, or screenshotting a private screen.
 *
 * So sharing is a **copy, chosen one entry at a time**, sent as an ordinary
 * end-to-end DM to one peer the user picked. Three properties follow from that
 * sentence and are the whole design:
 *
 * - **The entry is not touched.** Sharing reads it; nothing is written back, no
 *   flag says "shared", and deleting the entry afterwards is unaffected. The
 *   copy in the chat is now a message like any other, with a message's
 *   lifetime, not a window onto the journal.
 * - **Nothing is bulk.** There is no "share my journal", no date range, no
 *   export. One entry, one peer, one deliberate tap — the shape that makes an
 *   accidental disclosure of the *whole* journal impossible rather than
 *   discouraged.
 * - **No new transport.** It is a Vault DM: same sealed envelope, same relays,
 *   same mesh fallback, same delivery receipts. A separate "journal channel"
 *   would be a second thing to secure for no gain.
 *
 * ## The wire form is text, and stays text
 *
 * A shared note travels as a message whose body carries [`JOURNAL_NOTE_PREFIX`]
 * on its first line, optionally followed by the mood marker, with the note text
 * on the lines below:
 *
 * ```text
 * 📓 From my journal · 😕
 * rough morning, but I went for the walk anyway
 * ```
 *
 * This copies [`crate::tara::TARA_CHAT_PREFIX`] deliberately, including why the
 * marker stays on the wire instead of moving into a side-channel field: a
 * NIP-17 DM opened in some *other* Nostr client has no field to read, and the
 * fallback rendering there should still say where the words came from. The
 * alternative — a structured JSON payload — would render in those clients as a
 * blob of braces, which is a worse answer for the person reading it and no
 * safer for the person who sent it.
 *
 * **A match is a label, not authentication.** Anybody can type the prefix, so a
 * journal-note card says *the sending Comrade claims this came from its user's
 * journal* — exactly the standing a Tara bubble has. No frontend may treat a
 * match as proof, and nothing that matters may be gated on it.
 */

/// What a shared journal note starts with, on the wire and in a client that
/// knows nothing about this feature.
pub const JOURNAL_NOTE_PREFIX: &str = "📓 From my journal";

/// What sits between the prefix and the mood marker on the header line.
///
/// A middot rather than a colon or a dash: both of those turn up inside the
/// mood tags people actually write ("low - but ok"), and the separator has to
/// be something [`shared_journal_note`] can split on exactly once.
const MOOD_SEPARATOR: &str = " · ";

/// A journal note read back out of a message body.
///
/// Borrows from the message rather than allocating: every incoming message is
/// tested against [`shared_journal_note`], and the overwhelming majority are not
/// notes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SharedNote<'a> {
    /// The note as it was written, with the marker line removed.
    pub text: &'a str,
    /// The self-reported mood marker the entry carried, if it had one.
    pub mood: Option<&'a str>,
}

/// Render one journal entry as the message body that goes into the chat.
///
/// `mood` is flattened to a single line and dropped when it is blank: the mood
/// lives on the header line, so a newline inside it would silently push part of
/// the marker into the note text and split the message in two on the way back.
pub fn journal_note_line(text: &str, mood: Option<&str>) -> String {
    let mood = mood
        .map(|m| {
            m.split_whitespace()
                .collect::<Vec<_>>()
                .join(" ")
                .replace(MOOD_SEPARATOR.trim(), "-")
        })
        .filter(|m| !m.is_empty());
    match mood {
        Some(mood) => format!(
            "{JOURNAL_NOTE_PREFIX}{MOOD_SEPARATOR}{mood}\n{}",
            text.trim()
        ),
        None => format!("{JOURNAL_NOTE_PREFIX}\n{}", text.trim()),
    }
}

/// The journal note inside `content`, or `None` if `content` is an ordinary
/// message.
///
/// A body carrying the marker and nothing else is **not** a note: an empty card
/// with a "from my journal" header claims a disclosure that never happened, so
/// the honest fallback is to leave the message as the plain text it is. See the
/// module note on what a match does and does not claim.
pub fn shared_journal_note(content: &str) -> Option<SharedNote<'_>> {
    let rest = content.strip_prefix(JOURNAL_NOTE_PREFIX)?;
    let (header, text) = match rest.split_once('\n') {
        Some(split) => split,
        // The marker with no body — a header line on its own.
        None => (rest, ""),
    };
    let mood = match header {
        "" => None,
        _ => Some(header.strip_prefix(MOOD_SEPARATOR)?.trim()).filter(|m| !m.is_empty()),
    };
    let text = text.trim();
    (!text.is_empty()).then_some(SharedNote { text, mood })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_shared_note_round_trips_through_its_marker() {
        let line = journal_note_line("rough morning, but I went for the walk", Some("😕"));
        let note = shared_journal_note(&line).expect("round trip");
        assert_eq!(note.text, "rough morning, but I went for the walk");
        assert_eq!(note.mood, Some("😕"));

        let no_mood = journal_note_line("just thinking", None);
        assert_eq!(
            shared_journal_note(&no_mood),
            Some(SharedNote {
                text: "just thinking",
                mood: None,
            })
        );
    }

    #[test]
    fn the_body_keeps_its_own_line_breaks() {
        // A journal entry is often several lines, and the split is on the
        // *first* newline only — the rest is the note as written.
        let line = journal_note_line("one\n\ntwo\nthree", Some("🙂"));
        let note = shared_journal_note(&line).unwrap();
        assert_eq!(note.text, "one\n\ntwo\nthree");
    }

    #[test]
    fn an_ordinary_message_is_not_a_note() {
        for text in [
            "from my journal: I think we should talk",
            "📓 notes from the meeting",
            "I wrote in my journal today",
            "",
        ] {
            assert_eq!(shared_journal_note(text), None, "{text:?} parsed as a note");
        }
    }

    #[test]
    fn a_header_with_no_note_is_not_a_note() {
        // An empty card headed "from my journal" would claim a disclosure that
        // did not happen; the message stays plain text instead.
        assert_eq!(shared_journal_note(JOURNAL_NOTE_PREFIX), None);
        assert_eq!(
            shared_journal_note(&format!("{JOURNAL_NOTE_PREFIX} · 😕\n   \n")),
            None
        );
    }

    #[test]
    fn a_mood_cannot_smuggle_a_second_line() {
        // The mood is whatever the entry carried, and an entry's mood is user
        // input. Flattened, so the note text cannot be forged by writing a
        // newline into it.
        let line = journal_note_line("the real note", Some("😕\nnot the note"));
        let note = shared_journal_note(&line).unwrap();
        assert_eq!(note.mood, Some("😕 not the note"));
        assert_eq!(note.text, "the real note");
        assert_eq!(line.lines().count(), 2);
    }

    #[test]
    fn a_blank_mood_is_no_mood() {
        for mood in [Some("   "), Some(""), None] {
            let line = journal_note_line("something", mood);
            assert_eq!(shared_journal_note(&line).unwrap().mood, None, "{mood:?}");
        }
    }

    #[test]
    fn the_marker_is_a_label_and_the_test_says_so() {
        // Anybody can type the prefix by hand, and this pins the honest
        // consequence rather than a guarantee the wire does not carry: a typed
        // line matches. A frontend may therefore style a match, never trust it.
        let typed = format!("{JOURNAL_NOTE_PREFIX}\nI made this up");
        assert_eq!(shared_journal_note(&typed).unwrap().text, "I made this up");
    }

    #[test]
    fn a_malformed_header_stays_plain_text() {
        // The prefix followed by something that is not the mood separator is
        // not a note — otherwise "📓 From my journal entries are private" would
        // be drawn as a card with "entries are private" as its mood.
        assert_eq!(
            shared_journal_note("📓 From my journal entries are private\nhi"),
            None
        );
    }
}
