package mullu.comrade

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The pure half of [EventPump]: who needs draining, when a loop should
 * (re)start, how failure backs off, and the drain loop itself. Deliberately
 * free of Android and the FFI so `EventPumpTest` can drive all of it with
 * plain values — the same split as `together/TogetherDecisions.kt`.
 */

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
 * two is what [shouldStartLoop] exists to prevent.
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
 * The drain loop itself. **Nothing one event does may end it.**
 *
 * Generic over the event type and free of both Android and the FFI, so the
 * property that once failed in production — a throwing event ending delivery
 * for the life of the process — is pinned by a JVM test rather than asserted
 * by a comment. [EventPump] instantiates it over [ComradeCore.pollEvent] and
 * [ChatEventRouter.route], either of which can throw for ordinary reasons —
 * a `CallStyle` notification the platform refuses outright, a
 * SharedPreferences or WebRTC edge inside routing.
 *
 * So one event's failure costs exactly that event: it is reported to [onError]
 * and dropped — not retried, because a poisoned event that fails once will
 * fail again, and re-queueing it would wedge everything behind it — and the
 * next one is drained after [backoff]. [CancellationException] is re-thrown,
 * because that is the one "failure" that genuinely means stop — a real
 * [EventPump.release] — and swallowing it would make the loop unstoppable: the
 * mirror image of the bug above, and the reason this is a `catch (Throwable)`
 * after an explicit cancellation re-throw rather than a `runCatching`.
 */
internal suspend fun <T> drainLoop(
    poll: () -> T?,
    route: (T) -> Unit,
    idle: suspend () -> Unit,
    onError: (Throwable) -> Unit,
    backoff: suspend (consecutiveFailures: Int) -> Unit = { delay(failureBackoffMs(it)) },
) {
    var consecutiveFailures = 0
    while (currentCoroutineContext().isActive) {
        val event = try {
            poll()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // A queue read that failed is not a reason to stop reading — but it
            // is a reason to back off, so a persistently failing read cannot
            // spin a core.
            consecutiveFailures++
            onError(t)
            backoff(consecutiveFailures)
            continue
        }
        if (event == null) {
            idle()
            continue
        }
        try {
            route(event)
            consecutiveFailures = 0
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            consecutiveFailures++
            onError(t)
            backoff(consecutiveFailures)
        }
    }
}
