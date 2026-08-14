package mullu.comrade.topic

import mullu.comrade.topic.TopicDecisions.AssignPlan
import mullu.comrade.topic.TopicDecisions.PreviewKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The thread-and-topic decision vectors.
 *
 * Deliberately the same cases as `desktop/ui/topics.test.mjs`, with the same
 * inputs, so the two frontends drifting apart is a red test rather than a field
 * bug — the remedy `docs/COMMS_ARCHITECTURE.md` ADR-2 prescribes.
 */
class TopicDecisionsTest {

    private fun topic(
        slug: String,
        closed: Boolean = false,
        lastActivityAt: Long = 100,
    ) = TopicRow(
        slug = slug,
        name = slug,
        closed = closed,
        threadCount = 1,
        messageCount = 3,
        lastActivityAt = lastActivityAt,
    )

    private fun thread(
        rootId: String,
        topicSlug: String? = null,
        preview: String = "the deposit still hasn't come back",
        rootIsMedia: Boolean = false,
        rootMissing: Boolean = false,
        replyCount: Int = 2,
        lastAt: Long = 200,
        unread: Boolean = false,
    ) = ThreadRow(
        rootId = rootId,
        topicSlug = topicSlug,
        preview = preview,
        rootIsMedia = rootIsMedia,
        rootMissing = rootMissing,
        replyCount = replyCount,
        lastAt = lastAt,
        unread = unread,
    )

    @Test
    fun topicsListLiveliestFirstArchivedLast() {
        val rows = TopicDecisions.visibleTopics(
            listOf(
                topic("old", lastActivityAt = 10),
                topic("archived", closed = true, lastActivityAt = 999),
                topic("live", lastActivityAt = 500),
            ),
            includeClosed = true,
        )
        assertEquals(
            "an archive that outranks live topics is not an archive",
            listOf("live", "old", "archived"),
            rows.map { it.slug },
        )
    }

    @Test
    fun archivedTopicsAreOutOfThePickerByDefault() {
        val rows = TopicDecisions.visibleTopics(listOf(topic("live"), topic("gone", closed = true)))
        assertEquals(listOf("live"), rows.map { it.slug })
    }

    @Test
    fun threadsWithNoRepliesAreHiddenUnlessAskedFor() {
        val rows = listOf(thread("a"), thread("b", replyCount = 0))
        assertEquals(
            "listing every message would make the sheet a worse copy of the chat",
            listOf("a"),
            TopicDecisions.threadsFor(rows).map { it.rootId },
        )
        assertEquals(
            "a thread has to be startable from the sheet",
            2,
            TopicDecisions.threadsFor(rows, includeSingletons = true).size,
        )
    }

    @Test
    fun aTopicFilterIsExactAndUnfiledIsAskedForByName() {
        val rows = listOf(
            thread("a", topicSlug = "deposit"),
            thread("b", topicSlug = "repairs"),
            thread("c"),
        )
        assertEquals(
            listOf("a"),
            TopicDecisions.threadsFor(rows, ThreadFilter.InTopic("deposit")).map { it.rootId },
        )
        assertEquals(
            listOf("c"),
            TopicDecisions.threadsFor(rows, ThreadFilter.Unfiled).map { it.rootId },
        )
        assertEquals(3, TopicDecisions.threadsFor(rows, ThreadFilter.All).size)
    }

    @Test
    fun threadsSortByLastActivityNewestFirst() {
        val rows = TopicDecisions.threadsFor(
            listOf(thread("a", lastAt = 100), thread("c", lastAt = 300), thread("b", lastAt = 200)),
        )
        assertEquals(listOf("c", "b", "a"), rows.map { it.rootId })
    }

    @Test
    fun thePreviewNamesWhatCoreDeliberatelyLeftUnworded() {
        assertEquals(PreviewKind.Text, TopicDecisions.previewKind(thread("a")))
        assertEquals(
            PreviewKind.BareMedia,
            TopicDecisions.previewKind(thread("a", preview = "", rootIsMedia = true)),
        )
        assertEquals(
            PreviewKind.CaptionedMedia,
            TopicDecisions.previewKind(thread("a", preview = "sunset.jpg", rootIsMedia = true)),
        )
        assertEquals(
            PreviewKind.RootMissing,
            TopicDecisions.previewKind(thread("a", rootMissing = true)),
        )
        assertEquals(PreviewKind.Empty, TopicDecisions.previewKind(thread("a", preview = "  ")))
    }

    @Test
    fun aMissingRootWinsOverAnEmptyPreview() {
        // Both are true at once for a filing that arrived before its thread, and
        // "(no text)" would read as a bug where "not on this device" is a fact.
        assertEquals(
            PreviewKind.RootMissing,
            TopicDecisions.previewKind(thread("a", preview = "", rootMissing = true)),
        )
    }

    @Test
    fun unreadCountsThreadsNotMessages() {
        val rows = listOf(
            thread("a", topicSlug = "deposit", unread = true),
            thread("b", topicSlug = "deposit"),
            thread("c", topicSlug = "repairs", unread = true),
        )
        assertEquals(1, TopicDecisions.unreadThreadCount(rows, "deposit"))
        assertEquals(2, TopicDecisions.unreadThreadCount(rows))
    }

    @Test
    fun aBareAssignOpensThePickerRatherThanRefusing() {
        assertEquals(AssignPlan.OpenPicker, TopicDecisions.planAssign(emptyList(), "msg1"))
        assertEquals(AssignPlan.OpenPicker, TopicDecisions.planAssign(emptyList(), null))
    }

    @Test
    fun assignWithATopicAndNothingSelectedSaysWhatIsMissing() {
        val plan = TopicDecisions.planAssign(listOf("deposit"), null)
        assertTrue(plan is AssignPlan.NeedsTarget)
        assertEquals(
            "the sentence has to be able to name the topic they typed",
            "deposit",
            (plan as AssignPlan.NeedsTarget).slug,
        )
    }

    @Test
    fun assignWithATopicAndASelectionFilesThatThread() {
        assertEquals(
            AssignPlan.File("deposit", "msg1"),
            TopicDecisions.planAssign(listOf("deposit", "second"), "msg1"),
        )
    }
}
