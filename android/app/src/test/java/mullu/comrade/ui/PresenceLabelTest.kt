package mullu.comrade.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins what a comrade row says about someone who isn't online, since that is
 * where an honest presence UI is won or lost: "waiting for them to choose you
 * back" and "not online yet" look the same to a dot but mean entirely
 * different things to a person.
 */
class PresenceLabelTest {

    @Test
    fun onlineWinsOverEverything() {
        assertEquals(
            PresenceLabel.Online,
            presenceLabelOf(online = true, lastSeenAt = 0L, peerMarkedUs = false),
        )
    }

    @Test
    fun aPeerWeHaveSeenBeforeShowsWhenThatWas() {
        assertEquals(
            PresenceLabel.LastSeen,
            presenceLabelOf(online = false, lastSeenAt = 1_700_000_000L, peerMarkedUs = true),
        )
    }

    @Test
    fun neverSeenAndNotReciprocatedExplainsTheWait() {
        // The case a user would otherwise misread as "they're ignoring me":
        // their device has never told us anything because they haven't chosen
        // us, and it never will until they do.
        assertEquals(
            PresenceLabel.AwaitingReciprocity,
            presenceLabelOf(online = false, lastSeenAt = 0L, peerMarkedUs = false),
        )
    }

    @Test
    fun reciprocatedButNeverSeenIsJustAway() {
        assertEquals(
            PresenceLabel.NeverSeen,
            presenceLabelOf(online = false, lastSeenAt = 0L, peerMarkedUs = true),
        )
    }

    @Test
    fun aSeenPeerKeepsTheLastSeenLabelEvenIfReciprocityLapsed() {
        // We only learn `peerMarkedUs` from a beacon, so "seen at some point,
        // flag now false" is a state a stale row can be in — the timestamp is
        // still the more useful thing to show.
        assertEquals(
            PresenceLabel.LastSeen,
            presenceLabelOf(online = false, lastSeenAt = 42L, peerMarkedUs = false),
        )
    }
}
