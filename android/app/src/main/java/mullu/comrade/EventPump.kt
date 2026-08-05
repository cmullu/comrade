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
 */
internal class PumpHolders {
    private val holders = mutableSetOf<PumpHolder>()

    /** Returns `true` iff [holder] just became the *first* holder — the caller should start the loop. */
    @Synchronized
    fun acquire(holder: PumpHolder): Boolean = holders.add(holder) && holders.size == 1

    /** Returns `true` iff [holder] just released the *last* holder — the caller should stop the loop. */
    @Synchronized
    fun release(holder: PumpHolder): Boolean = holders.remove(holder) && holders.isEmpty()

    @Synchronized
    fun isHeld(): Boolean = holders.isNotEmpty()
}

/**
 * Whether the drain loop should be (re)started right now: somebody needs it and
 * no live loop is serving them.
 *
 * Split out from [EventPump.acquire] because the two conditions used to be
 * conflated. `acquire` returned early whenever [PumpHolders.acquire] said "not
 * the first holder" — which is also what it says when the holder set never
 * changed. So a loop that had *died* while a holder was still registered could
 * never be restarted: every later `acquire` saw an unchanged holder set and
 * returned, and delivery stayed dead until the process did. That is the state
 * this predicate exists to make impossible to express.
 */
internal fun shouldStartLoop(held: Boolean, loopAlive: Boolean): Boolean = held && !loopAlive

/**
 * How long to wait after [consecutiveFailures] events in a row failed to be
 * handled, before draining the next one.
 *
 * Zero while things are healthy, because a working queue must drain at full
 * speed — a DM should never sit behind an artificial delay. The backoff only
 * exists for the systemic case: if whatever broke is broken for *every* event
 * (a notification manager refusing to post, say), an unthrottled loop would
 * burn a core failing on each queued event in turn. Doubling from 100ms and
 * capped at [MAX_FAILURE_BACKOFF_MS] keeps a single bad event nearly free while
 * bounding that spin.
 */
internal fun failureBackoffMs(consecutiveFailures: Int): Long = when {
    consecutiveFailures <= 0 -> 0L
    else -> {
        val shifted = 100L shl (consecutiveFailures - 1).coerceAtMost(SAFE_SHIFT_LIMIT)
        shifted.coerceAtMost(MAX_FAILURE_BACKOFF_MS)
    }
}

/** Ceiling on the failure backoff — long enough to stop a spin, short enough that recovery is prompt. */
internal const val MAX_FAILURE_BACKOFF_MS = 5_000L

/** Keeps `100L shl n` from overflowing into a negative delay on a long failure streak. */
private const val SAFE_SHIFT_LIMIT = 20

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
 */
object EventPump {

    private const val TAG = "EventPump"
    private const val POLL_IDLE_MS = 200L

    private val holders = PumpHolders()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The live loop, guarded by this object's monitor (not by [holders]). */
    private var job: Job? = null

    /**
     * Register [holder] as needing delivery, and make sure a loop is actually
     * running for it. Idempotent per holder, and — deliberately — *not* gated on
     * [holder] being new.
     *
     * The gate used to be `if (!holders.acquire(holder)) return`, which conflated
     * "somebody else already needs this" with "a loop is already serving them".
     * Those come apart the moment a loop dies, and then every subsequent
     * acquire — a returning Activity, the service restarting — saw an unchanged
     * holder set and returned without looking at the corpse. See
     * [shouldStartLoop].
     */
    fun acquire(context: Context, holder: PumpHolder) {
        val first = holders.acquire(holder)
        val appContext = context.applicationContext
        synchronized(this) {
            if (!shouldStartLoop(held = holders.isHeld(), loopAlive = job?.isActive == true)) return
            if (!first) Log.w(TAG, "event drain was not running for an existing holder — restarting")
            Log.i(TAG, "event drain started (holder=$holder)")
            job = scope.launch { drain(appContext) }
        }
    }

    /** Stop draining once [holder] was the last one needing it. */
    fun release(holder: PumpHolder) {
        if (!holders.release(holder)) return
        synchronized(this) {
            Log.i(TAG, "event drain stopped (last holder=$holder)")
            job?.cancel()
            job = null
        }
    }

    /** Whether anything currently needs the queue drained — for diagnostics and tests. */
    fun isRunning(): Boolean = holders.isHeld()

    /**
     * The drain loop. **Nothing one event does may end it.**
     *
     * It used to have no handler at all, and that was the sharpest edge in the
     * notification path: [ChatEventRouter.route] reaches WebRTC, SharedPreferences
     * and `NotificationManager`, any of which can throw. A single throw completed
     * this coroutine, and because [acquire] would not restart a loop for a holder
     * it already knew about, delivery stopped *silently and permanently* — no
     * notifications, no chat-list updates, no calls ringing — until the process
     * was killed and relaunched. Critical events (DMs, calls, requests) are never
     * dropped by [EventBus], so they queued up behind a consumer that was gone.
     *
     * So one event's failure costs exactly that event: it is logged and dropped,
     * and the next one is drained. [CancellationException] is re-thrown, because
     * that is the one "failure" that genuinely means stop — a real [release].
     */
    private suspend fun drain(context: Context) {
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            val event = try {
                ComradeCore.pollEvent()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "polling the event queue threw", t)
                consecutiveFailures++
                delay(failureBackoffMs(consecutiveFailures))
                continue
            }
            if (event == null) {
                delay(POLL_IDLE_MS)
                continue
            }
            try {
                ChatEventRouter.route(context, event)
                consecutiveFailures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Dropped, not retried: a poisoned event that fails once will
                // fail again, and re-queueing it would wedge everything behind
                // it — which is the outcome this whole handler exists to avoid.
                consecutiveFailures++
                Log.e(TAG, "dropping an event whose handling threw (failures=$consecutiveFailures)", t)
                delay(failureBackoffMs(consecutiveFailures))
            }
        }
    }
}
