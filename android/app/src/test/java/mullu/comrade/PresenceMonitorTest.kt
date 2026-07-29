package mullu.comrade

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android half of comrade presence: which changes are worth a
 * notification, and that the dots never outlive the vault.
 *
 * The presence *rules* (expiry, replay, mutual consent) live in the Rust core
 * and are tested there; what this pins is the phone-side behaviour a user
 * would notice — being told once when someone arrives, and never being told
 * they left.
 */
class PresenceMonitorTest {

    @After
    fun tearDown() {
        // A process-global object shared with the app: leave it clean so test
        // order can never matter.
        PresenceMonitor.clear()
    }

    @Test
    fun `only the arrival is worth a notification`() {
        assertTrue("coming online is the whole point", PresenceMonitor.shouldNotify(wasOnline = false, isOnline = true))
        assertFalse(
            "a repeat of a state we already showed is not news",
            PresenceMonitor.shouldNotify(wasOnline = true, isOnline = true),
        )
        assertFalse(
            "nobody wants to be told their friend closed an app",
            PresenceMonitor.shouldNotify(wasOnline = true, isOnline = false),
        )
        assertFalse(PresenceMonitor.shouldNotify(wasOnline = false, isOnline = false))
    }

    @Test
    fun `update tracks the dot and reports the arrival exactly once`() {
        assertFalse(PresenceMonitor.isOnline("npub1ana"))

        assertTrue("first beacon is an arrival", PresenceMonitor.update("npub1ana", online = true))
        assertTrue(PresenceMonitor.isOnline("npub1ana"))
        assertEquals(true, PresenceMonitor.online.value["npub1ana"])

        assertFalse("a heartbeat is not a second arrival", PresenceMonitor.update("npub1ana", online = true))

        assertFalse("leaving is silent", PresenceMonitor.update("npub1ana", online = false))
        assertFalse(PresenceMonitor.isOnline("npub1ana"))

        assertTrue("coming back is an arrival again", PresenceMonitor.update("npub1ana", online = true))
    }

    @Test
    fun `peers are tracked independently`() {
        PresenceMonitor.update("npub1ana", online = true)
        assertTrue(PresenceMonitor.update("npub1bo", online = true))
        assertTrue(PresenceMonitor.isOnline("npub1ana"))
        assertTrue(PresenceMonitor.isOnline("npub1bo"))
        PresenceMonitor.update("npub1ana", online = false)
        assertFalse(PresenceMonitor.isOnline("npub1ana"))
        assertTrue("one peer going offline must not clear another", PresenceMonitor.isOnline("npub1bo"))
    }

    @Test
    fun `seeding a snapshot notifies about nobody`() {
        // A screen opening (or the connection service starting) replays what
        // the store already knew — that must not fire notifications for people
        // who came online while the app was closed and are still around.
        PresenceMonitor.seed(
            listOf(
                comrade("npub1ana", online = true),
                comrade("npub1bo", online = false),
            ),
        )
        assertTrue(PresenceMonitor.isOnline("npub1ana"))
        assertFalse(PresenceMonitor.isOnline("npub1bo"))
        // …and a real beacon for someone already shown as online is still not
        // a fresh arrival afterwards.
        assertFalse(PresenceMonitor.update("npub1ana", online = true))
    }

    @Test
    fun `clearing drops every dot so none outlives a vault lock`() {
        PresenceMonitor.update("npub1ana", online = true)
        PresenceMonitor.clear()
        assertTrue(PresenceMonitor.online.value.isEmpty())
        assertFalse(PresenceMonitor.isOnline("npub1ana"))
        // After a re-unlock the first beacon is an arrival again, so the user
        // is told their comrade is around rather than silently missing it.
        assertTrue(PresenceMonitor.update("npub1ana", online = true))
    }

    private fun comrade(npub: String, online: Boolean) = ComradeCore.ComradeInfo(
        npub = npub,
        alias = "",
        name = null,
        online = online,
        lastSeenAt = if (online) 1_700_000_000L else 0L,
        peerMarkedUs = true,
    )
}
