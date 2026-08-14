package mullu.comrade

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The single drain loop over [ComradeCore.pollEvent], owned by the process
 * rather than by any one component. The pure logic — holder bookkeeping, the
 * restart rule, failure backoff, and the loop itself — lives in `EventDrain.kt`
 * where `EventPumpTest` can run it; this object only wires it to Android.
 *
 * ## Why this exists
 * The loop used to live inside [RelayConnectionService], which is gated on the
 * user's "stay connected in the background" preference — so turning that
 * preference off stopped *all* event delivery, including while the app was
 * open and on screen: no notifications, no live chat-list updates, and no
 * incoming call ever ringing. The setting's own description promised the
 * opposite ("messages and calls only arrive while Comrade is open").
 *
 * Splitting the two responsibilities makes the promise true:
 *  - **draining** happens whenever anyone needs it — an Activity being visible
 *    is enough ([PumpHolder.FOREGROUND]);
 *  - **the foreground service** is only about surviving backgrounding: a
 *    priority floor plus the ongoing notification Android requires for one,
 *    which is exactly what the preference should govern
 *    ([PumpHolder.SERVICE]).
 *
 * Exactly one loop runs no matter how many holders there are, which preserves
 * the property the service was introduced for: a single consumer of a queue
 * that must not be drained twice (an Activity recreation can never duplicate
 * notifications).
 *
 * Draining is immediate while events are queued — no artificial batching delay
 * a call or DM would sit behind — and only backs off to [POLL_IDLE_MS] once
 * the queue is actually empty. The loop itself is safe to run with a locked
 * vault: nothing produces events then, so it is an idle tick.
 *
 * ## Liveness
 * Two separate questions, kept separate after conflating them cost every
 * notification for the life of a process: [isHeld] is whether anyone wants a
 * loop, [isRunning] is whether one is actually alive. Every [acquire], and
 * every [ensureRunning] tick from the service, revives a loop that is not.
 */
object EventPump {

    private const val TAG = "EventPump"
    private const val POLL_IDLE_MS = 200L

    /**
     * Guarded by this object's monitor, along with [job]. One lock for both:
     * checking the holder set outside it would race a concurrent [release]
     * into leaving a loop running that nobody holds.
     */
    private val holders = PumpHolders()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The live loop, guarded by this object's monitor (not by [holders]). */
    private var job: Job? = null

    /**
     * Register [holder] as needing delivery, and make sure a loop is actually
     * running for it. Idempotent per holder, and — deliberately — *not* gated on
     * [holder] being new: "am I registered" and "is a loop alive" are answered
     * independently. See [shouldStartLoop] for the outage that gate caused.
     */
    fun acquire(context: Context, holder: PumpHolder) {
        val appContext = context.applicationContext
        synchronized(this) {
            val first = holders.acquire(holder)
            if (!startIfNeededLocked(appContext)) return
            if (!first) Log.w(TAG, "event drain was not running for an existing holder — restarting")
            Log.i(TAG, "event drain started (holder=$holder)")
        }
    }

    /**
     * Restart the loop if it is not running and somebody still needs it.
     *
     * Public so [RelayConnectionService] can re-check on a timer. [acquire]
     * already revives a dead loop, but the next acquire may be hours away:
     * nothing acquires while the app stays closed, which is precisely the
     * stretch the service exists to cover. [drainLoop] is written so the loop
     * cannot die of a bad event any more; this is the insurance that if it dies
     * some other way, the stall lasts one interval rather than until the user
     * next opens the app.
     */
    fun ensureRunning(context: Context) {
        val appContext = context.applicationContext
        synchronized(this) {
            if (startIfNeededLocked(appContext)) {
                Log.w(TAG, "event drain found dead on a liveness check — restarted")
            }
        }
    }

    /** Caller must hold this object's monitor. Returns `true` iff a loop was started. */
    private fun startIfNeededLocked(appContext: Context): Boolean {
        if (!shouldStartLoop(held = holders.isHeld(), loopAlive = job?.isActive == true)) return false
        job = scope.launch { drain(appContext) }
        return true
    }

    /** Stop draining once [holder] was the last one needing it. */
    fun release(holder: PumpHolder) {
        synchronized(this) {
            if (!holders.release(holder)) return
            Log.i(TAG, "event drain stopped (last holder=$holder)")
            job?.cancel()
            job = null
        }
    }

    /**
     * Whether a drain loop is **actually alive**.
     *
     * This used to report the holder set instead, which made it useless for the
     * one question worth asking of it: a dead loop with a live holder answered
     * "running" while nothing was being delivered.
     */
    fun isRunning(): Boolean = synchronized(this) { job?.isActive == true }

    /** Whether anything currently needs the queue drained. */
    fun isHeld(): Boolean = holders.isHeld()

    private suspend fun drain(context: Context) = drainLoop(
        poll = { ComradeCore.pollEvent() },
        route = { ChatEventRouter.route(context, it) },
        idle = { delay(POLL_IDLE_MS) },
        onError = { Log.e(TAG, "event drain step failed; the event is dropped and delivery continues", it) },
    )
}
