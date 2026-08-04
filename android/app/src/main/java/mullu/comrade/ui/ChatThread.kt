package mullu.comrade.ui

/**
 * Pure chat-thread rules — day grouping and auto-scroll — kept free of
 * Compose/Android imports so plain JVM unit tests (`ChatThreadTest`) can
 * pin them.
 */

/**
 * Whether a message at [epochSecs] opens a new calendar day relative to the
 * one before it at [prevEpochSecs] — i.e. whether the thread should render a
 * day separator above it. The first message of a thread (null prev) always
 * does.
 */
fun startsNewDay(
    prevEpochSecs: Long?,
    epochSecs: Long,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): Boolean {
    if (prevEpochSecs == null) return true
    fun day(secs: Long) = java.time.Instant.ofEpochSecond(secs).atZone(zone).toLocalDate()
    return day(epochSecs) != day(prevEpochSecs)
}

/**
 * Whether the reader is close enough to the newest message that fresh
 * arrivals should auto-scroll into view. Someone scrolled up reading
 * history (further than [slack] items from the end) must NOT be yanked
 * down — they get a "new messages" affordance instead. An empty or
 * not-yet-laid-out list counts as at the bottom.
 */
fun isNearBottom(lastVisibleIndex: Int, totalCount: Int, slack: Int = 2): Boolean =
    totalCount <= 0 || lastVisibleIndex >= totalCount - 1 - slack

/**
 * Where to open a thread: the index of the first message the user has not seen,
 * or `null` for "open at the newest message".
 *
 * Telegram's rule. [lastReadAt] is the `created_at` of the newest message they
 * had already seen (what `mark_conversation_read` hands back). An item counts as
 * unread only if it is *newer* than that **and** came from the peer — your own
 * messages are read by definition, and a thread whose only new items are things
 * you sent from another device must not claim unread mail.
 *
 * `null` for a first visit ([lastReadAt] of 0) on purpose: with no record of
 * where someone left off there is no honest "where you left off", and dropping
 * them at the top of a long history would be worse than the newest message.
 *
 * [createdAt] must be the same time-ordered merge the thread renders, so the
 * returned index addresses the list the caller is about to scroll.
 */
fun firstUnreadIndex(
    createdAt: List<Long>,
    outgoing: List<Boolean>,
    lastReadAt: Long,
): Int? {
    if (lastReadAt <= 0L) return null
    val index = createdAt.indices.firstOrNull { i ->
        createdAt[i] > lastReadAt && !outgoing.getOrElse(i) { false }
    }
    return index
}

/**
 * Whether an "unread messages" divider belongs above the item at [index] —
 * true only for the boundary itself, so the caller can render it inline while
 * walking the list.
 */
fun startsUnread(index: Int, firstUnread: Int?): Boolean = firstUnread != null && index == firstUnread

/**
 * Where the message with event id [targetId] sits in the rendered thread, or
 * `null` if it is not in it.
 *
 * This is what tapping a reply's quote scrolls to. `null` is the common case,
 * not an edge case: a quote renders from whatever history is cached, and
 * replying to something older than the loaded window leaves a perfectly good
 * quote pointing at an item that is not on screen to scroll to. The caller must
 * treat that as "no destination" and leave the thread where it is — scrolling
 * somewhere arbitrary would be worse than not moving.
 *
 * [eventIds] must be the same time-ordered merge the thread renders, so the
 * returned index addresses the list the caller is about to scroll.
 */
fun indexOfEventId(eventIds: List<String>, targetId: String?): Int? {
    if (targetId.isNullOrEmpty()) return null
    val index = eventIds.indexOf(targetId)
    return if (index >= 0) index else null
}

/**
 * How long the jumped-to message stays highlighted.
 *
 * A scroll on its own does not say *which* message it landed on, and a reply
 * usually quotes something surrounded by other messages — so the flash is what
 * makes the jump legible rather than disorienting. Long enough to catch the eye
 * after the scroll animation settles, short enough not to read as selection.
 */
const val QUOTE_HIGHLIGHT_MS: Long = 1_400L
