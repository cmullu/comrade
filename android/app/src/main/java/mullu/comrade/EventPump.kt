package mullu.comrade

import android.content.Context
import android.util.Log
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

    /** Start draining for [holder] if nobody was already. Idempotent per holder. */
    fun acquire(context: Context, holder: PumpHolder) {
        if (!holders.acquire(holder)) return
        val appContext = context.applicationContext
        synchronized(this) {
            if (job?.isActive == true) return
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

    private suspend fun drain(context: Context) {
        while (currentCoroutineContext().isActive) {
            val event = ComradeCore.pollEvent()
            if (event == null) {
                delay(POLL_IDLE_MS)
                continue
            }
            ChatEventRouter.route(context, event)
        }
    }
}
