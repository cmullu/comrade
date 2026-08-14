package mullu.comrade.topic

/**
 * The decisions behind the thread sheet and the topic sheet — pure, with no
 * Android imports, so the JVM lane can pin them.
 *
 * Its twin is `desktop/ui/topics.mjs` and the two are pinned by mirrored test
 * vectors, the way [mullu.comrade.together.TogetherDecisions] and
 * `together_sync.mjs` already are. Where they diverge on purpose, each says so.
 *
 * ## What is *not* here
 *
 * **The slug rules, the reply graph, and which topic a thread is in.** Those
 * are `comrade_core::topic`'s answers, reached over uniffi (`topics`,
 * `threads`, `thread`, `assignThread`). Nothing in this file re-derives a slug
 * or walks a reply chain — a second implementation of either is exactly the
 * drift `/pay` already suffered across four frontends.
 *
 * What *is* here is presentation: what to show, in what order, and which words
 * to use where core deliberately hands up none.
 *
 * ## The shapes
 *
 * Declared as plain data classes rather than taken as the uniffi records
 * directly, so this file keeps its "no framework imports" property and the JVM
 * lane can run without the generated bindings — which need the Android SDK to
 * exist. The caller maps `TopicDto`/`ThreadSummaryDto` into these at the sheet's
 * edge; the fields are named identically so a mapping error is visible.
 */

/** One topic as a row needs it. Mirrors `comrade_ui::TopicDto`. */
data class TopicRow(
    val slug: String,
    val name: String,
    val closed: Boolean,
    val threadCount: Int,
    val messageCount: Int,
    val lastActivityAt: Long,
    val mine: Boolean = true,
)

/** One thread as a row needs it. Mirrors `comrade_ui::ThreadSummaryDto`. */
data class ThreadRow(
    val rootId: String,
    val topicSlug: String?,
    val preview: String,
    val rootIsMedia: Boolean,
    val rootMissing: Boolean,
    val replyCount: Int,
    val lastAt: Long,
    val unread: Boolean,
)

/**
 * Which slice of threads a sheet is showing.
 *
 * [Unfiled] is a real destination rather than the absence of a filter: "what
 * have I not put anywhere" is a question people ask of a filing system, and
 * expressing it as `topicSlug == null` would make it indistinguishable from
 * "everything".
 */
sealed interface ThreadFilter {
    data object All : ThreadFilter
    data object Unfiled : ThreadFilter
    data class InTopic(val slug: String) : ThreadFilter
}

object TopicDecisions {

    /**
     * Topics in the order a list should show them: liveliest first, archived
     * last.
     *
     * Not creation order, which is what the store returns. A conversation
     * accumulates topics and the one that just moved is the one being worked
     * on; sorting by name would pin `#a-thing-from-march` above it forever.
     *
     * Archived topics sink rather than vanish when [includeClosed] is set, so
     * the archive reads as an archive rather than as an empty list.
     */
    fun visibleTopics(topics: List<TopicRow>, includeClosed: Boolean = false): List<TopicRow> =
        topics
            .filter { includeClosed || !it.closed }
            .sortedWith(compareBy<TopicRow> { it.closed }.thenByDescending { it.lastActivityAt })

    /**
     * The threads a sheet should list, most recently active first.
     *
     * [includeSingletons] defaults to false because every message is
     * technically a thread of one, and listing them would make the sheet a
     * second copy of the conversation — the same screen with worse ordering.
     * Slack draws the same line. It is a parameter rather than a constant
     * because a thread has to be *startable*, and a thread that does not exist
     * yet has no replies.
     */
    fun threadsFor(
        threads: List<ThreadRow>,
        filter: ThreadFilter = ThreadFilter.All,
        includeSingletons: Boolean = false,
    ): List<ThreadRow> =
        threads
            .filter { row ->
                when (filter) {
                    is ThreadFilter.All -> true
                    is ThreadFilter.Unfiled -> row.topicSlug == null
                    is ThreadFilter.InTopic -> row.topicSlug == filter.slug
                }
            }
            .filter { includeSingletons || it.replyCount > 0 }
            .sortedWith(compareByDescending<ThreadRow> { it.lastAt }.thenBy { it.rootId })

    /**
     * How many of a topic's threads have something unread in them.
     *
     * Reads the threads rather than the topic row because unread is per thread
     * and a topic's own counts are about size, not attention — forty read
     * messages and one new reply is a 1.
     */
    fun unreadThreadCount(threads: List<ThreadRow>, topicSlug: String? = null): Int =
        threads.count { it.unread && (topicSlug == null || it.topicSlug == topicSlug) }

    /**
     * Which of four things the row has to say, so the *words* stay in
     * `strings.xml` where they can be translated.
     *
     * Core hands up an empty `preview` for an uncaptioned attachment and for a
     * root that is not in this device's history, plus two booleans saying
     * which. Returning a sentence from here would push English up out of a
     * decision function — the same split `comrade_core::command`'s offer
     * envelopes make between the wire and the sentence.
     */
    enum class PreviewKind {
        /** Show [ThreadRow.preview] as it is. */
        Text,

        /** An attachment with a caption: the caption, marked as an attachment. */
        CaptionedMedia,

        /** An attachment with no caption: the frontend's own word for one. */
        BareMedia,

        /**
         * The root is not on this device. A normal state, not an error: a
         * thread can be filed before its root arrives, and a reply can quote
         * something older than the loaded window. Saying so beats an empty row
         * that reads as a bug.
         */
        RootMissing,

        /** A text message with nothing renderable in it. */
        Empty,
    }

    /**
     * [PreviewKind.RootMissing] wins over an empty preview, because both are
     * true at once for a filing that outran its thread — and "not on this
     * device" is a fact where "(no text)" reads as a bug.
     */
    fun previewKind(row: ThreadRow): PreviewKind = when {
        row.rootMissing -> PreviewKind.RootMissing
        row.preview.isBlank() -> if (row.rootIsMedia) PreviewKind.BareMedia else PreviewKind.Empty
        row.rootIsMedia -> PreviewKind.CaptionedMedia
        else -> PreviewKind.Text
    }

    /**
     * What `/assign #topic` should do, given what the composer has selected.
     *
     * Three answers, and the middle one is why this is a function:
     *
     * - No topic named → open the picker. A bare `/assign` is the discoverable
     *   way in, not a refusal.
     * - A topic named but nothing selected → say what is missing and keep the
     *   text. The grammar cannot know which thread was meant, and guessing
     *   would file the wrong conversation — a destructive-looking action taken
     *   on the user's behalf.
     * - Both → file it.
     *
     * [replyTarget] is the event id of whatever the composer is replying to (or
     * the message a menu was opened on). Any message in the thread will do —
     * core walks up to the root.
     */
    sealed interface AssignPlan {
        /** Show the topic list so one can be chosen or named. */
        data object OpenPicker : AssignPlan

        /** Nothing is selected. [slug] is what they typed, for the sentence. */
        data class NeedsTarget(val slug: String) : AssignPlan

        /** File the thread containing [messageId] under [slug]. */
        data class File(val slug: String, val messageId: String) : AssignPlan
    }

    fun planAssign(slugs: List<String>, replyTarget: String?): AssignPlan {
        val slug = slugs.firstOrNull { it.isNotBlank() } ?: return AssignPlan.OpenPicker
        return if (replyTarget.isNullOrEmpty()) {
            AssignPlan.NeedsTarget(slug)
        } else {
            AssignPlan.File(slug, replyTarget)
        }
    }
}
