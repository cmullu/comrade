package mullu.comrade.attention

/**
 * What a reading-shelf row shows and what it can do — pure, with no Android
 * imports, so the JVM lane can pin it.
 *
 * The *collecting* is not here: which archive formats can be imported, how a
 * share payload becomes a link or an article, and how URLs are de-duplicated all
 * live in `comrade_core::library` and are reached over the FFI. What is here is
 * the handful of decisions a list row has to make, and they are here rather than
 * inside the composable for the reason `TogetherDecisions` exists: a decision in
 * a `@Composable` is a decision no lane in this sandbox can run.
 *
 * Two of them are worth the file on their own:
 *
 * **A bookmark is not a read.** An imported Instagram save is a title and a
 * link. Comrade does not fetch the page — that is the whole boundary the shelf is
 * built to respect — so [rowActions] must never offer *Read* for a row with no
 * text. The failure mode of getting this wrong is an empty reader, which reads to
 * the user as a broken app rather than as a deliberate limit.
 *
 * **A row with no title still has to say something.** Half of what an export
 * archive hands over is a bare link. Falling back to the host is the difference
 * between a shelf someone can scan and a column of `https://www.instagram.com/p/`.
 */
object ShelfDecisions {

    /** What a row can offer. Ordered as the row draws them, left to right. */
    enum class RowAction {
        /** Open in the distraction-free reader. Only ever for a row with text. */
        READ,

        /** Same as [READ], for a row already part-way through. */
        RESUME,

        /** Hand the link to the system browser. Comrade never renders it itself. */
        OPEN_LINK,

        /** Put the article's text in by hand, for a bookmark that has none. */
        ADD_TEXT,

        REMOVE,
    }

    /**
     * The host of an `http(s)` URL, lowercased, without `www.` — or null.
     *
     * Null for a credentials-shaped authority (`https://a@b/`) rather than a
     * best guess: the part after the `@` is what a browser resolves, and a
     * frontend that displayed the part before it would be labelling a row with
     * the one component of the URL that does not decide where it goes. The Rust
     * side refuses the same shape, in `library::host_of`.
     */
    fun hostOf(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        val rest = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(7)
            else -> return null
        }
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.isEmpty() || authority.contains('@')) return null
        val host = authority.substringBefore(':').removePrefix("www.").lowercase()
        return host.takeIf { it.isNotEmpty() && it.contains('.') }
    }

    /**
     * What to put on the row's first line.
     *
     * Title, else host, else the raw URL, else empty — and the caller shows its
     * own "Untitled" string for the last case, because that is a word and words
     * belong in `strings.xml`.
     */
    fun rowTitle(title: String, url: String?): String {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty()) return trimmed
        hostOf(url)?.let { return it }
        return url?.trim().orEmpty()
    }

    /**
     * What the row offers, in order.
     *
     * `Remove` is always last and always present. `Read`/`Resume` appear only for
     * a row with text — see the class docs — and `Resume` replaces `Read` once
     * there is a position to come back to, but never for something already
     * finished: "Resume" on a piece you read to the end is an offer to continue
     * something that has no more of itself left.
     */
    fun rowActions(
        hasText: Boolean,
        hasUrl: Boolean,
        position: Int,
        finished: Boolean,
    ): List<RowAction> {
        val actions = mutableListOf<RowAction>()
        if (hasText) {
            actions += if (position > 0 && !finished) RowAction.RESUME else RowAction.READ
        }
        if (hasUrl) {
            actions += RowAction.OPEN_LINK
            if (!hasText) actions += RowAction.ADD_TEXT
        }
        actions += RowAction.REMOVE
        return actions
    }

    /**
     * Which facts a row's second line states, in order.
     *
     * Keys, not sentences — the screen maps each to a string resource. The order
     * is fixed here so that the same shelf reads the same way on every row, and
     * so that the *set* is testable: the reading time is omitted for a row with
     * no text (an estimate of zero minutes is not a fact about a bookmark), and
     * `FINISHED` and `OPEN` are mutually exclusive because a row cannot usefully
     * be both and "finished" is the one a reader deciding what to open wants.
     */
    fun rowFacts(
        hasText: Boolean,
        estimatedMinutes: Int,
        finished: Boolean,
        isOpen: Boolean,
    ): List<RowFact> {
        val facts = mutableListOf(RowFact.SOURCE)
        when {
            !hasText -> facts += RowFact.LINK_ONLY
            estimatedMinutes > 0 -> facts += RowFact.READING_TIME
        }
        when {
            finished -> facts += RowFact.FINISHED
            isOpen -> facts += RowFact.OPEN
        }
        return facts
    }

    /** One fact on a row's second line. */
    enum class RowFact { SOURCE, READING_TIME, LINK_ONLY, OPEN, FINISHED }

    /**
     * Whether a pasted or shared string is worth sending to the engine at all.
     *
     * Only a blank check. What the string *is* — a link, an article, a scrap —
     * is `library::parse_shared`'s call, and second-guessing it here is how the
     * two frontends would end up disagreeing about what counts as a save.
     */
    fun worthSaving(input: String): Boolean = input.isNotBlank()
}
