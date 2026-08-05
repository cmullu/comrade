package mullu.comrade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        assertEquals(
            NotifyDecision.Suppress,
            NotificationPolicy.messageDecision(ana, openConversationPeer = ana, appVisible = true, muted = false),
        )
        assertEquals(
            NotifyDecision.Alert,
            NotificationPolicy.messageDecision(ana, openConversationPeer = bo, appVisible = true, muted = false),
        )
        assertEquals(
            NotifyDecision.Alert,
            NotificationPolicy.messageDecision(ana, openConversationPeer = null, appVisible = true, muted = false),
        )
    }

    @Test
    fun aBackgroundedAppNotifiesEvenForTheChatItLeftOpen() {
        // The regression, and the shape users would have reported as
        // "notifications stop sometimes": the open-conversation peer is set on
        // navigation and cleared on navigation, and pressing Home is neither. So
        // the thread the user was in the middle of reading stayed flagged as
        // on-screen for the rest of the process's life, and the person most
        // likely to message them was the one person who could not.
        assertEquals(
            NotifyDecision.Alert,
            NotificationPolicy.messageDecision(ana, openConversationPeer = ana, appVisible = false, muted = false),
        )
        // Same for the gentler classes about a person.
        assertNotEquals(
            NotifyDecision.Suppress,
            NotificationPolicy.presenceDecision(
                ana,
                openConversationPeer = ana,
                appVisible = false,
                muted = false,
                becameOnline = true,
            ),
        )
        assertNotEquals(
            NotifyDecision.Suppress,
            NotificationPolicy.nudgeDecision(ana, openConversationPeer = ana, appVisible = false, muted = false),
        )
    }

    @Test
    fun muteSuppressesEvenWhenTheThreadIsNotOnScreen() {
        assertEquals(
            NotifyDecision.Suppress,
            NotificationPolicy.messageDecision(ana, openConversationPeer = null, appVisible = false, muted = true),
        )
        assertEquals(
            NotifyDecision.Suppress,
            NotificationPolicy.messageDecision(ana, openConversationPeer = bo, appVisible = true, muted = true),
        )
        // Mute outranks quiet hours: it is "never", not "not right now", so it
        // must not soften into a silent post.
        assertEquals(
            NotifyDecision.Suppress,
            NotificationPolicy.messageDecision(
                ana,
                openConversationPeer = null,
                appVisible = false,
                muted = true,
                quietHours = true,
            ),
        )
    }

    @Test
    fun muteAlsoSilencesAComradeComingOnline() {
        assertEquals(
            NotifyDecision.Alert,
            NotificationPolicy.presenceDecision(
                ana,
                openConversationPeer = null,
                appVisible = false,
                muted = false,
                becameOnline = true,
            ),
        )
        assertEquals(
            NotifyDecision.Suppress,
            NotificationPolicy.presenceDecision(
                ana,
                openConversationPeer = null,
                appVisible = false,
                muted = true,
                becameOnline = true,
            ),
        )
    }

    @Test
    fun presenceNotifiesOnlyOnTheOnlineEdge() {
        // The router calls this for every presence event; only the transition
        // into "online" is news. Repeated heartbeats are not.
        assertEquals(
            NotifyDecision.Suppress,
            NotificationPolicy.presenceDecision(
                ana,
                openConversationPeer = null,
                appVisible = false,
                muted = false,
                becameOnline = false,
            ),
        )
    }

    @Test
    fun presenceIsSuppressedForTheConversationOnScreen() {
        assertEquals(
            NotifyDecision.Suppress,
            NotificationPolicy.presenceDecision(
                ana,
                openConversationPeer = ana,
                appVisible = true,
                muted = false,
                becameOnline = true,
            ),
        )
    }

    @Test
    fun quietHoursSilenceAMessageRatherThanLosingIt() {
        // The distinction this type exists for. A message that arrives at 02:00
        // keeps until morning, so the quiet window must post it without a sound
        // — not decline to post it, which is what a Boolean rule forced and what
        // made an overnight message invisible until the app was next opened.
        assertEquals(
            NotifyDecision.Silent,
            NotificationPolicy.messageDecision(
                ana,
                openConversationPeer = null,
                appVisible = false,
                muted = false,
                quietHours = true,
            ),
        )
        assertEquals(NotifyDecision.Silent, NotificationPolicy.requestDecision(quietHours = true))
        assertEquals(NotifyDecision.Silent, NotificationPolicy.updateDecision(quietHours = true))
    }

    @Test
    fun quietHoursDropTheThingsThatWouldBeStaleByMorning() {
        // Presence and a nudge both describe someone being around *now* and both
        // expire in minutes. A silent one found at 08:00 would be a claim about
        // last night, so there is nothing to keep.
        assertEquals(
            NotifyDecision.Suppress,
            NotificationPolicy.presenceDecision(
                ana,
                openConversationPeer = null,
                appVisible = false,
                muted = false,
                becameOnline = true,
                quietHours = true,
            ),
        )
        assertEquals(
            NotifyDecision.Suppress,
            NotificationPolicy.nudgeDecision(
                ana,
                openConversationPeer = null,
                appVisible = false,
                muted = false,
                quietHours = true,
            ),
        )
    }

    @Test
    fun outsideQuietHoursEverythingBehavesExactlyAsBefore() {
        // The parameter defaults to false, so every existing call site — and
        // every user who never turns the window on — is unaffected.
        assertEquals(
            NotifyDecision.Alert,
            NotificationPolicy.messageDecision(ana, openConversationPeer = null, appVisible = false, muted = false),
        )
        assertEquals(NotifyDecision.Alert, NotificationPolicy.requestDecision())
        assertEquals(NotifyDecision.Alert, NotificationPolicy.updateDecision())
        assertEquals(
            NotifyDecision.Alert,
            NotificationPolicy.messageDecision(
                ana,
                openConversationPeer = null,
                appVisible = false,
                muted = false,
                quietHours = false,
            ),
        )
    }

    @Test
    fun aNudgeFollowsTheSameTwoSuppressorsAsEverythingElseAboutAPerson() {
        assertEquals(
            NotifyDecision.Alert,
            NotificationPolicy.nudgeDecision(ana, openConversationPeer = null, appVisible = true, muted = false),
        )
        assertEquals(
            NotifyDecision.Alert,
            NotificationPolicy.nudgeDecision(ana, openConversationPeer = bo, appVisible = true, muted = false),
        )
        // Muting a conversation means the person, not one class of event.
        assertEquals(
            NotifyDecision.Suppress,
            NotificationPolicy.nudgeDecision(ana, openConversationPeer = null, appVisible = true, muted = true),
        )
        // Already looking at the thread the nudge would send them to.
        assertEquals(
            NotifyDecision.Suppress,
            NotificationPolicy.nudgeDecision(ana, openConversationPeer = ana, appVisible = true, muted = false),
        )
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
