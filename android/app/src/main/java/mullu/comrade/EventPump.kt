package mullu.comrade

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Who currently needs the native event queue drained.
 *
 * Public (not `internal`) because it is a parameter type on
 * [EventPump.acquire]/[EventPump.release], which [MainActivity] and
 * [RelayConnectionService] both name.
 */
enum class PumpHolder {
    /** An Activity is on screen: the user is looking at the app right now. */
    FOREGROUND,

    /** [RelayConnectionService] is running: delivery must survive backgrounding. */
    SERVICE,
}

/**
 * Tracks which [PumpHolder]s need the drain loop running, so it only actually
 * stops once every holder has let go.
 *
 * Pure bookkeeping, no Android dependency, safe to call unevenly: a duplicate
 * [acquire], or a [release] with no matching [acquire], is a harmless no-op —
 * the same shape (and the same reasoning) as
 * [mullu.comrade.voice.MicHolder]'s holder set. See `EventPumpTest`.
 *
 * Note what [acquire]'s answer does and does not mean: it says whether this
 * holder is the *first*, which is the right question for "should a second loop
 * be started?" and the wrong one for "is there a loop at all?". Conflating the
 * two is what [PumpRevival] exists to prevent.
 */
internal class PumpHolders {
    private val holders = mutableSetOf<PumpHolder>()

    /** Returns `true` iff [holder] just became the *first* holder. */
    @Synchronized
    fun acquire(holder: PumpHolder): Boolean = holders.add(holder) && holders.size == 1

    /** Returns `true` iff [holder] just released the *last* holder — the caller should stop the loop. */
    @Synchronized
    fun release(holder: PumpHolder): Boolean = holders.remove(holder) && holders.isEmpty()

    @Synchronized
    fun isHeld(): Boolean = holders.isNotEmpty()
}

/**
 * Whether [EventPump] should start a drain loop right now.
 *
 * A one-line rule with its own name because the rule it replaces was half of a
 * delivery outage. [EventPump.acquire] used to gate this decision behind
 * [PumpHolders.acquire]'s answer — start the loop only if this holder was the
 * first — which silently made "a loop is needed" mean "no loop existed a moment
 * ago". Once a loop had died, every later acquire returned before ever looking
 * at the job, and with [PumpHolder.SERVICE] holding a slot that included
 * *reopening the app*: a second holder is never the first one. Delivery stayed
 * dead until the process did.
 *
 * Only the loop's own liveness may decide whether to start one. The holder set
 * still decides when to stop.
 */
internal object PumpRevival {
    fun shouldStart(loopAlive: Boolean): Boolean = !loopAlive
}

/**
 * The drain loop itself — generic over the event type and free of both Android
 * and the FFI, so `EventPumpTest` can drive it with plain values.
 *
 * ## The try/catch is the whole point
 * This loop is the process's *only* consumer of the native event queue, and
 * [ChatEventRouter.route] reaches straight into notification posting and the
 * WebRTC layer. Both throw under ordinary conditions:
 * `NotificationManagerCompat.notify` throws once `POST_NOTIFICATIONS` has been
 * revoked mid-session — `call/CallService.kt` already wraps its own post in
 * `runCatching` for exactly this reason — and a `CallStyle` notification can be
 * refused outright by the platform.
 *
 * Before this, one such throw ended the loop for the life of the process. That
 * failure was invisible from every angle a user or a developer would look at:
 * `EventPump.isRunning` reported `true` because it read the holder set rather
 * than the job, the foreground service's own notification kept saying
 * "Comrade is staying connected", and reopening the app did not restart
 * anything (see [PumpRevival]). Messages and calls simply stopped arriving
 * until the app was force-stopped. So handling one event badly must cost that
 * event and nothing else.
 *
 * [CancellationException] is re-thrown rather than caught: it is not a failure
 * but [EventPump.release]'s stop signal, and swallowing it here would make the
 * loop unstoppable — the mirror image of the bug above, and the reason this is
 * a `catch (Throwable)` after an explicit cancellation re-throw rather than a
 * `runCatching`.
 */
internal suspend fun <T> drainLoop(
    poll: () -> T?,
    route: (T) -> Unit,
    idle: suspend () -> Unit,
    onError: (Throwable) -> Unit,
) {
    while (currentCoroutineContext().isActive) {
        val event = try {
            poll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A queue read that failed is not a reason to stop reading. Back
            // off first, so a persistently failing read cannot spin hot.
            onError(e)
            idle()
            continue
        }
        if (event == null) {
            idle()
            continue
        }
        try {
            route(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError(e)
        }
    }
}

/**
 * The single drain loop over [ComradeCore.pollEvent], owned by the process
 * rather than by any one component.
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
     * Guarded by this object's monitor, along with [job].
     *
     * One lock for both, and always taken in that order: the alternative
     * (checking the holder set outside the lock) races a concurrent [release]
     * into leaving a loop running that nobody holds, and taking the two locks
     * in opposite orders on different paths is how that race becomes a
     * deadlock instead.
     */
    private val holders = PumpHolders()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var job: Job? = null

    /**
     * Start draining for [holder], reviving a loop that has stopped for any
     * reason. Idempotent, and safe to call for a holder that already holds —
     * "am I registered" and "is a loop alive" are answered independently.
     */
    fun acquire(context: Context, holder: PumpHolder) {
        val appContext = context.applicationContext
        synchronized(this) {
            holders.acquire(holder)
            startIfNeededLocked(appContext)
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
        synchronized(this) { startIfNeededLocked(appContext) }
    }

    /** Caller must hold this object's monitor. */
    private fun startIfNeededLocked(appContext: Context) {
        if (!holders.isHeld()) return
        if (!PumpRevival.shouldStart(job?.isActive == true)) return
        Log.i(TAG, "event drain starting")
        job = scope.launch { drain(appContext) }
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

    /** Whether anyone currently needs the queue drained. */
    fun isHeld(): Boolean = synchronized(this) { holders.isHeld() }

    private suspend fun drain(context: Context) = drainLoop(
        poll = { ComradeCore.pollEvent() },
        route = { ChatEventRouter.route(context, it) },
        idle = { delay(POLL_IDLE_MS) },
        onError = { Log.e(TAG, "event drain step failed; delivery continues", it) },
    )
}
