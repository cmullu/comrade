package mullu.comrade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the refcount behind [EventPump]: exactly one drain loop, running for as
 * long as *anyone* needs it.
 *
 * This is the bookkeeping that makes "turning off background connectivity must
 * not stop delivery while the app is open" true — a visible Activity and the
 * foreground service are independent holders, and the loop only stops once
 * both have let go. Uneven calls are ordinary here (a service destroyed
 * without ever having started, an Activity stopped twice), so they must be
 * harmless rather than corrupt the count.
 */
class EventPumpTest {

    @Test
    fun `the first holder starts the loop and later holders don't restart it`() {
        val holders = PumpHolders()
        assertTrue("first holder starts it", holders.acquire(PumpHolder.FOREGROUND))
        assertFalse("a second holder must not start a second loop", holders.acquire(PumpHolder.SERVICE))
        assertTrue(holders.isHeld())
    }

    @Test
    fun `the loop survives one holder leaving and stops only on the last`() {
        val holders = PumpHolders()
        holders.acquire(PumpHolder.FOREGROUND)
        holders.acquire(PumpHolder.SERVICE)

        // The user backgrounds the app: the service still needs delivery.
        assertFalse("the service still needs it", holders.release(PumpHolder.FOREGROUND))
        assertTrue(holders.isHeld())

        // …and the service stopping (e.g. the preference turned off) is what
        // finally ends it.
        assertTrue("last one out stops the loop", holders.release(PumpHolder.SERVICE))
        assertFalse(holders.isHeld())
    }

    @Test
    fun `an Activity alone keeps delivery alive with no service at all`() {
        // The whole point of the split: background connectivity off means no
        // SERVICE holder ever appears, and the app must still work on screen.
        val holders = PumpHolders()
        assertTrue(holders.acquire(PumpHolder.FOREGROUND))
        assertTrue(holders.isHeld())
        assertTrue(holders.release(PumpHolder.FOREGROUND))
        assertFalse(holders.isHeld())
    }

    @Test
    fun `duplicate acquires and unmatched releases are harmless`() {
        val holders = PumpHolders()
        assertTrue(holders.acquire(PumpHolder.FOREGROUND))
        assertFalse("re-acquiring the same holder is a no-op", holders.acquire(PumpHolder.FOREGROUND))

        // An Activity recreation can release twice; a service can be destroyed
        // having never acquired. Neither may drop the loop out from under the
        // holder that is still there.
        assertTrue(holders.release(PumpHolder.FOREGROUND))
        assertFalse("releasing again is a no-op", holders.release(PumpHolder.FOREGROUND))
        assertFalse("releasing a holder that never acquired is a no-op", holders.release(PumpHolder.SERVICE))
        assertFalse(holders.isHeld())
    }

    @Test
    fun `a stop-start cycle restarts the loop rather than leaving it dead`() {
        val holders = PumpHolders()
        holders.acquire(PumpHolder.FOREGROUND)
        holders.release(PumpHolder.FOREGROUND)
        assertTrue("coming back to the app must start it again", holders.acquire(PumpHolder.FOREGROUND))
    }

    // ── The loop must be restartable independently of the holder set ──────────

    @Test
    fun `a dead loop is restarted even though its holder is not new`() {
        // The regression. `acquire` used to return early whenever the holder set
        // did not change, which is exactly the state a returning Activity is in
        // when the loop died under it — so delivery stayed dead for the life of
        // the process.
        val holders = PumpHolders()
        holders.acquire(PumpHolder.FOREGROUND)
        assertTrue(
            "a holder that is already registered must still revive a dead loop",
            shouldStartLoop(held = holders.isHeld(), loopAlive = false),
        )
    }

    @Test
    fun `a live loop is never started twice and an unheld one is never started at all`() {
        assertFalse("one queue, one consumer", shouldStartLoop(held = true, loopAlive = true))
        assertFalse("nobody needs it", shouldStartLoop(held = false, loopAlive = false))
        assertFalse(shouldStartLoop(held = false, loopAlive = true))
    }

    // ── Failure backoff ──────────────────────────────────────────────────────

    @Test
    fun `a healthy queue drains with no added delay`() {
        assertEquals(0L, failureBackoffMs(0))
        assertEquals("a negative streak is nonsense, not a delay", 0L, failureBackoffMs(-1))
    }

    @Test
    fun `repeated failures back off, bounded`() {
        assertEquals(100L, failureBackoffMs(1))
        assertEquals(200L, failureBackoffMs(2))
        assertEquals(400L, failureBackoffMs(3))
        // A systemic failure must not spin a core, and must not overflow into a
        // negative delay however long the streak runs.
        assertEquals(MAX_FAILURE_BACKOFF_MS, failureBackoffMs(30))
        assertEquals(MAX_FAILURE_BACKOFF_MS, failureBackoffMs(Int.MAX_VALUE))
        for (n in 1..64) {
            assertTrue("backoff must stay a sane delay at n=$n", failureBackoffMs(n) in 0..MAX_FAILURE_BACKOFF_MS)
        }
    }
}
