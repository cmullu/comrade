package mullu.comrade.ui

/**
 * Pure rules for handing a journal note to somebody, and for drawing one in a
 * chat — kept free of Compose/Android imports so plain JVM unit tests
 * (`JournalNoteTest`) can pin them.
 *
 * The wire form and the parse are `comrade_core::note`'s, reached through
 * `MessageInfo.sharedNote`; nothing here re-reads the marker. What is here is
 * the part that is genuinely the frontend's: who may be offered the note, and
 * how much of it a bubble shows before it stops being a bubble.
 *
 * Mirrored by `desktop/ui/journal_note.mjs` and `app/lib/src/util/journal_note.dart`,
 * with the same numbers and the same cases — the same discipline `ChatThread.kt`
 * and `chat_thread.mjs` already keep.
 */

/**
 * How many lines of a shared note a bubble draws before offering "show more".
 *
 * A journal entry is written to be read alone, at whatever length the day
 * needed; a chat bubble is read in a scroll of other people's messages. Six
 * lines is enough for the short entries that are most of them to arrive whole,
 * and short enough that a long one does not push the rest of the conversation
 * off screen.
 */
const val NOTE_PREVIEW_LINES = 6

/**
 * …and how many characters, for the entry that is one very long paragraph.
 * Without this, [NOTE_PREVIEW_LINES] alone would let a single unbroken line of
 * a thousand characters through.
 */
const val NOTE_PREVIEW_CHARS = 280

/** What a collapsed note card shows, and whether anything is being withheld. */
data class NotePreview(val text: String, val truncated: Boolean)

/**
 * The collapsed form of a shared note.
 *
 * Cuts on a word boundary when one is near the limit, because a note ending
 * mid-word reads as data loss rather than as a fold. [truncated] is what drives
 * the "show more" control — a note that fits must not offer one, since a
 * control that expands nothing is a control that says the sender wrote more
 * than they did.
 */
fun notePreview(
    text: String,
    maxLines: Int = NOTE_PREVIEW_LINES,
    maxChars: Int = NOTE_PREVIEW_CHARS,
): NotePreview {
    val lines = text.lines()
    var cut = if (lines.size > maxLines) lines.take(maxLines).joinToString("\n") else text
    var truncated = cut.length < text.length
    if (cut.length > maxChars) {
        val hard = cut.take(maxChars)
        val space = hard.lastIndexOf(' ')
        // Only honour a word boundary in the last quarter; a space at
        // character 3 would leave one word standing in for a paragraph.
        cut = if (space > maxChars * 3 / 4) hard.take(space) else hard
        truncated = true
    }
    return NotePreview(cut.trimEnd(), truncated)
}

/** Someone a note may be handed to, as the picker lists them. */
data class ShareTarget(val npub: String, val label: String, val comrade: Boolean)

/** A saved contact, in the shape [shareTargets] needs — no FFI types here. */
data class ShareCandidate(
    val npub: String,
    val alias: String?,
    val name: String?,
    val comrade: Boolean,
)

/**
 * Who the journal's share sheet offers, comrades first and each group ordered
 * by the name on screen.
 *
 * **Saved contacts only, and that is the point.** Every other send path in the
 * app can reach whoever's thread is open, including a stranger whose request
 * was never accepted. A journal note is the most private thing this app holds,
 * so the list is drawn from people the user deliberately added — the accident
 * this design refuses is the one where the note goes to the wrong name in a
 * long list of strangers.
 *
 * Comrades lead because marking someone a comrade is already the "this person
 * may reach me" disclosure (`ComradeCore.setComradeTyped`), so they are the
 * people most likely to be meant — not because a comrade is entitled to
 * anything here. Labels follow [peerTitle]'s trust order, exactly as the chat
 * list does, so a note is addressed to the same name everywhere else in the app.
 */
fun shareTargets(candidates: List<ShareCandidate>): List<ShareTarget> =
    candidates
        .map { ShareTarget(it.npub, peerTitle(it.npub, it.alias, it.name), it.comrade) }
        .sortedWith(compareByDescending<ShareTarget> { it.comrade }.thenBy { it.label.lowercase() })
