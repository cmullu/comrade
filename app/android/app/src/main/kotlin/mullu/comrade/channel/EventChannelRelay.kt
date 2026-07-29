package mullu.comrade.channel

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Publishes a [Flow] of already-serialised maps onto an [EventChannel],
 * observing the two rules the whole channel layer depends on (see
 * `../PLATFORM_CHANNELS.md` §3–§4):
 *
 *  1. **`EventSink` is main-thread-only.** Collection runs on
 *     [Dispatchers.Main.immediate], so every `success` call is already on the
 *     platform thread. (`immediate` rather than plain `Main` so a value
 *     produced on the main thread is delivered without a needless post — the
 *     call-state flow is combined from `StateFlow`s that the UI also reads.)
 *
 *  2. **Snapshot, then deltas; nothing is buffered while detached.** The source
 *     must be a *conflated, always-has-a-current-value* flow (a `StateFlow`, or
 *     a `combine` of them). `onListen` therefore emits the current value first
 *     simply by collecting; `onCancel` cancels the job and drops the sink.
 *
 * That second rule is what makes engine detach lossless here **and** what stops
 * it being harmful: there is deliberately no replay queue, because a queue
 * would let a stale "Ringing" arrive seconds after the call was already over.
 *
 * @param source called on each `onListen` so the relay can re-derive its flow
 *   (needed because some sources want to know they have a listener; and
 *   because re-subscribing a cold-started `combine` is cheaper than keeping one
 *   collecting forever with nowhere to send).
 */
internal class EventChannelRelay(
    messenger: BinaryMessenger,
    name: String,
    private val source: () -> Flow<Any?>,
) : EventChannel.StreamHandler {

    private val channel = EventChannel(messenger, name).also { it.setStreamHandler(this) }

    /**
     * `Main.immediate`, not IO: see rule 1. `SupervisorJob` so one failing
     * emission cannot tear down a later subscription's job.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        if (events == null) return
        // Defensive: Flutter should not call onListen twice without an
        // intervening onCancel, but a leaked job would double-emit forever.
        job?.cancel()
        job = scope.launch {
            source().collect { value -> events.success(value) }
        }
    }

    override fun onCancel(arguments: Any?) {
        job?.cancel()
        job = null
    }

    /** Called from `ComradePlugin.onDetachedFromEngine`. */
    fun dispose() {
        job?.cancel()
        job = null
        scope.cancel()
        channel.setStreamHandler(null)
    }
}

/** The platform (main) thread — where every channel reply and every sink emission must land. */
private val mainHandler = Handler(Looper.getMainLooper())

private inline fun onMain(crossinline block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
}

/**
 * Reply successfully from any thread.
 *
 * Every long-running method handler in this layer dispatches to
 * [Dispatchers.IO] and replies through here, because `MethodChannel.Result`
 * must be invoked on the platform thread — and because a handler that did the
 * work inline would block the main thread. That second half is not theoretical:
 * `CallManager.factoryLock` exists (`CallManager.kt:253-264`) precisely because
 * holding a lock on the main thread during native WebRTC init produced an ANR.
 */
internal fun MethodChannel.Result.postSuccess(value: Any? = null) = onMain { success(value) }

internal fun MethodChannel.Result.postError(code: String, message: String?, details: Any? = null) =
    onMain { error(code, message, details) }

internal fun MethodChannel.Result.postNotImplemented() = onMain { notImplemented() }

/**
 * Run [block] on IO and reply with its value, mapping a thrown
 * [IllegalStateException] — what `ComradeCore`'s `rethrowing()` wrapper
 * produces, with an already-user-safe message — to [ErrorCodes.CORE_ERROR].
 *
 * Any other throwable is rethrown into the scope rather than swallowed: an
 * unexpected exception in a channel handler is a bug, and a `Result` that
 * silently reports success would hide it.
 */
internal fun CoroutineScope.replyOnIo(
    result: MethodChannel.Result,
    errorCode: String = ErrorCodes.CORE_ERROR,
    block: () -> Any?,
) {
    launch(Dispatchers.IO) {
        try {
            result.postSuccess(block())
        } catch (e: IllegalStateException) {
            result.postError(errorCode, e.message)
        }
    }
}

/** Required-argument accessor that fails loudly rather than defaulting. */
internal fun io.flutter.plugin.common.MethodCall.requireString(key: String): String =
    argument<String>(key) ?: throw MissingArgument(key)

internal fun io.flutter.plugin.common.MethodCall.requireBool(key: String): Boolean =
    argument<Boolean>(key) ?: throw MissingArgument(key)

internal fun io.flutter.plugin.common.MethodCall.requireInt(key: String): Int =
    argument<Int>(key) ?: throw MissingArgument(key)

internal class MissingArgument(val key: String) : IllegalArgumentException("missing argument: $key")
