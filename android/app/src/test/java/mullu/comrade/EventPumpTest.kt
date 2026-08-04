package mullu.comrade

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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

    // ── The delivery outage: a throwing event used to end the loop ────────────

    /** The test's own stop signal, so a script can end without a timing race. */
    private class ScriptExhausted : CancellationException("no more scripted events")

    /**
     * Run [events] through [drainLoop], returning what got routed and what
     * failed. The script ends by cancelling, which is also the production stop
     * signal — so every test here exercises the real exit path too.
     */
    private fun runScript(
        events: List<String>,
        route: (String) -> Unit,
    ): Pair<List<String>, List<Throwable>> {
        val routed = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        val queue = ArrayDeque(events)
        runBlocking {
            try {
                drainLoop(
                    poll = { if (queue.isEmpty()) throw ScriptExhausted() else queue.removeFirst() },
                    route = { event ->
                        routed += event
                        route(event)
                    },
                    idle = {},
                    onError = { errors += it },
                )
            } catch (_: ScriptExhausted) {
                // The loop propagated cancellation, as it must.
            }
        }
        return routed to errors
    }

    @Test
    fun `an event whose notification throws does not stop the ones behind it`() {
        // The outage this guards: ChatEventRouter.route posts notifications, and
        // NotificationManagerCompat.notify throws for real reasons — a revoked
        // POST_NOTIFICATIONS, a CallStyle the platform refuses. The loop is the
        // process's only consumer of the event queue, so before this the first
        // such throw ended message and call delivery until the app was
        // force-stopped.
        val (routed, errors) = runScript(listOf("dm", "call", "dm2")) { event ->
            if (event == "call") throw IllegalArgumentException("notify() refused the CallStyle")
        }

        assertEquals("every event must still be offered", listOf("dm", "call", "dm2"), routed)
        assertEquals("the one failure is reported, not swallowed silently", 1, errors.size)
        assertTrue(errors.single() is IllegalArgumentException)
    }

    @Test
    fun `a failing queue read does not stop the loop either`() {
        // pollEvent crosses the FFI; a throw there is no more fatal than one in
        // routing, and must not be the end of delivery.
        val routed = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        var polls = 0
        runBlocking {
            try {
                drainLoop(
                    poll = {
                        when (++polls) {
                            1 -> throw IllegalStateException("FFI read failed")
                            2 -> "dm"
                            else -> throw ScriptExhausted()
                        }
                    },
                    route = { routed += it },
                    idle = {},
                    onError = { errors += it },
                )
            } catch (_: ScriptExhausted) {
            }
        }

        assertEquals("the event after the failed read still arrives", listOf("dm"), routed)
        assertEquals(1, errors.size)
    }

    @Test
    fun `cancellation still stops the loop rather than being swallowed`() {
        // The mirror-image bug: catching Throwable around routing would also
        // catch the CancellationException that EventPump.release uses to stop
        // the loop, leaving an unstoppable one behind. Cancelling from inside
        // routing must propagate.
        val routed = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        var stopped = false
        runBlocking {
            try {
                drainLoop(
                    poll = { "dm" },
                    route = {
                        routed += it
                        throw ScriptExhausted()
                    },
                    idle = {},
                    onError = { errors += it },
                )
            } catch (_: ScriptExhausted) {
                stopped = true
            }
        }

        assertTrue("cancellation must leave the loop", stopped)
        assertEquals("it stopped on the first event, not after spinning", 1, routed.size)
        assertTrue("cancellation is not a failure to report", errors.isEmpty())
    }

    @Test
    fun `reopening the app revives a loop that died while the service held it`() {
        // Named after the trap, in the style of UpdateCheck.shouldScheduleCheckJob.
        //
        // Background delivery is on, so the service holds the pump and a loop
        // runs. Something kills that loop.
        val holders = PumpHolders()
        holders.acquire(PumpHolder.SERVICE)
        val loopAlive = false

        // The user opens the app. `acquire` answers "not the first holder" —
        // which is the correct answer to "start a second loop?" and was, until
        // this fix, also what decided whether to start *any*.
        assertFalse(
            "a second holder is never the first one",
            holders.acquire(PumpHolder.FOREGROUND),
        )

        // The rule that ships asks about the loop instead, so opening the app
        // brings delivery back.
        assertTrue("a dead loop must be revived", PumpRevival.shouldStart(loopAlive))
        assertFalse("a live one is left alone", PumpRevival.shouldStart(loopAlive = true))
    }
}
