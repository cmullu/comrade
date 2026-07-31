package mullu.comrade

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins when a notification is warranted. These rules used to be `if`
 * conditions inlined at three call sites in `ChatEventRouter.route`, where the
 * DM branch and the attachment branch could — and did, before mute existed —
 * disagree about what suppresses a notice.
 */
class NotificationPolicyTest {

    private val ana = "npub1ana"
    private val bo = "npub1bo"

    @Test
    fun anOpenConversationDoesNotNotifyItself() {
        assertFalse(NotificationPolicy.shouldNotifyMessage(ana, openConversationPeer = ana, muted = false))
        assertTrue(NotificationPolicy.shouldNotifyMessage(ana, openConversationPeer = bo, muted = false))
        assertTrue(NotificationPolicy.shouldNotifyMessage(ana, openConversationPeer = null, muted = false))
    }

    @Test
    fun muteSuppressesEvenWhenTheThreadIsNotOnScreen() {
        assertFalse(NotificationPolicy.shouldNotifyMessage(ana, openConversationPeer = null, muted = true))
        assertFalse(NotificationPolicy.shouldNotifyMessage(ana, openConversationPeer = bo, muted = true))
    }

    @Test
    fun muteAlsoSilencesAComradeComingOnline() {
        assertTrue(
            NotificationPolicy.shouldNotifyPresence(ana, openConversationPeer = null, muted = false, becameOnline = true),
        )
        assertFalse(
            NotificationPolicy.shouldNotifyPresence(ana, openConversationPeer = null, muted = true, becameOnline = true),
        )
    }

    @Test
    fun presenceNotifiesOnlyOnTheOnlineEdge() {
        // The router calls this for every presence event; only the transition
        // into "online" is news. Repeated heartbeats are not.
        assertFalse(
            NotificationPolicy.shouldNotifyPresence(ana, openConversationPeer = null, muted = false, becameOnline = false),
        )
    }

    @Test
    fun presenceIsSuppressedForTheConversationOnScreen() {
        assertFalse(
            NotificationPolicy.shouldNotifyPresence(ana, openConversationPeer = ana, muted = false, becameOnline = true),
        )
    }

    @Test
    fun aNudgeFollowsTheSameTwoSuppressorsAsEverythingElseAboutAPerson() {
        assertTrue(NotificationPolicy.shouldNotifyNudge(ana, openConversationPeer = null, muted = false))
        assertTrue(NotificationPolicy.shouldNotifyNudge(ana, openConversationPeer = bo, muted = false))
        // Muting a conversation means the person, not one class of event.
        assertFalse(NotificationPolicy.shouldNotifyNudge(ana, openConversationPeer = null, muted = true))
        // Already looking at the thread the nudge would send them to.
        assertFalse(NotificationPolicy.shouldNotifyNudge(ana, openConversationPeer = ana, muted = false))
    }

    @Test
    fun theMessageSummaryGoesWhenItsLastChildDoes() {
        assertTrue(NotificationPolicy.summaryStale(0))
        assertFalse(NotificationPolicy.summaryStale(1))
        // Defensive: a negative count (an OS that reported nothing useful) must
        // read as "no children", not as "keep the orphan".
        assertTrue(NotificationPolicy.summaryStale(-1))
    }
}
