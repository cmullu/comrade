package mullu.comrade.channel

import android.Manifest
import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mullu.comrade.voice.OneShotRecognizer
import mullu.comrade.voice.VoskModel
import mullu.comrade.voice.WakeWordService

/**
 * Control + state for the always-on "Hey Comrade" wake-word service.
 *
 * See `../PLATFORM_CHANNELS.md` §7. Two things are deliberately **absent** from
 * this surface, and their absence is the invariant:
 *
 *  - **No `pause`/`resume`.** `WakeWordService.pause(MicHolder)` /
 *    `resume(MicHolder)` are refcounted through `MicHolderSet`
 *    (`WakeWordService.kt:45-55`) so the recogniser only restarts once *every*
 *    holder has let go. The only two holders are `CALL` and `VOICE_NOTE`, and
 *    both are acquired and released by native code that already gets it right
 *    (`CallManager.kt:933`/`:1429`, `VoiceRecorder.kt:55`/`:124`/`:137`). If
 *    Dart could call `resume`, the two-touch sequence AUDIT found reachable
 *    (hold record → answer a call → release record) would come straight back
 *    the first time some widget's `dispose` fired an unbalanced release. The
 *    invariant survives by there being no entry point, which is stronger than
 *    a documented one.
 *
 *  - **No `VoskModel.acquire`/`release`.** The refcount's 30 s
 *    `CLOSE_LINGER_MS` reclaim only works if every reference is returned by an
 *    owner with a real teardown callback. A Dart-held reference is one no
 *    `onDestroy` can guarantee returning. Dart gets `isModelAvailable` — a
 *    read — and nothing else.
 */
internal class WakeWordChannel(
    messenger: BinaryMessenger,
    private val appContext: Context,
    private val permissions: PermissionBridge,
) : MethodChannel.MethodCallHandler {

    private companion object {
        /**
         * `WakeWordService.isRunning` is a plain `@Volatile` companion flag, not
         * a flow — it is set in the service's own `onCreate`/`onDestroy`. Poll
         * it at roughly a UI heartbeat rather than adding a broadcast the
         * preserved service would have to be edited to send. Cheap: two boolean
         * reads, and the relay collects `distinctUntilChanged` so Dart only
         * sees real transitions.
         */
        const val POLL_MS = 500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val methods = MethodChannel(messenger, ChannelNames.WAKE_WORD)
        .also { it.setMethodCallHandler(this) }

    private val state = EventChannelRelay(messenger, ChannelNames.WAKE_WORD_STATE) { stateFlow() }

    /**
     * **Known gap** (see `../PLATFORM_CHANNELS.md` §7): only `idle` and
     * `listening` are emitted. `WakeWordService`'s internal `State` enum and
     * its error paths (mic failure, missing model) are private and reach the
     * user only through the service's own notification text. Surfacing
     * `goAhead`/`micError`/`modelMissing` needs a small `StateFlow<Status>`
     * added to the preserved service — deliberately not bundled here, because
     * it is a change to a preserved file. The Dart enum already carries all
     * five values, so adding them later is a Kotlin-only edit.
     */
    private fun stateFlow(): Flow<Any?> = flow {
        while (true) {
            emit(
                mapOf(
                    "running" to WakeWordService.isRunning,
                    // An enum key, not a localised string: the wording lives in
                    // Dart's .arb files now. The service still sets its own
                    // notification text from R.string, because it owns that
                    // notification — the two live side by side.
                    "status" to if (WakeWordService.isRunning) "listening" else "idle",
                    "modelAvailable" to VoskModel.isAvailable(appContext),
                ),
            )
            delay(POLL_MS)
        }
        // flowOn(IO) is load-bearing, not tidiness: EventChannelRelay collects
        // on Dispatchers.Main.immediate (an EventSink is main-thread-only), and
        // VoskModel.isAvailable touches the assets and filesDir. Without this
        // the poll would do a filesystem hit on the main thread twice a second.
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "start" -> start(result)
            "stop" -> {
                WakeWordService.stop(appContext)
                result.postSuccess()
            }
            // Read from the service's own flag, not from remembered UI state:
            // the toggle has to re-seed from the service, because the screen is
            // disposed on every tab switch (WakeWordService.kt:324-330).
            "isRunning" -> result.postSuccess(WakeWordService.isRunning)
            "isModelAvailable" -> scope.replyOnIo(result) { VoskModel.isAvailable(appContext) }
            "listenOnce" -> listenOnce(call, result)
            else -> result.postNotImplemented()
        }
    }

    private fun start(result: MethodChannel.Result) {
        if (permissions.activity == null) {
            result.postError(ErrorCodes.NO_ACTIVITY, "no Activity attached to request RECORD_AUDIO")
            return
        }
        permissions.ensure(listOf(Manifest.permission.RECORD_AUDIO)) { granted, denied ->
            if (!granted) {
                result.postError(ErrorCodes.PERMISSION_DENIED, "microphone permission denied", denied)
                return@ensure
            }
            WakeWordService.start(appContext)
            result.postSuccess()
        }
    }

    /**
     * One utterance, for tap-to-talk and dictation.
     *
     * Note what this does **not** do: dispatch a `CommandDispatcher` action.
     * AUDIT's 2026-07-15 entry specifically records moving that off the
     * recogniser's main-looper callback, and re-adding it here would put it
     * straight back. Dart gets the text and decides.
     *
     * `OneShotRecognizer` posts its callbacks on the main thread and releases
     * its own `VoskModel` reference as it finishes, so there is nothing for
     * this channel to own or clean up.
     */
    private fun listenOnce(call: MethodCall, result: MethodChannel.Result) {
        val timeoutMs = call.argument<Number>("timeoutMs")?.toLong() ?: 7_000L
        if (permissions.activity == null) {
            result.postError(ErrorCodes.NO_ACTIVITY, "no Activity attached to request RECORD_AUDIO")
            return
        }
        permissions.ensure(listOf(Manifest.permission.RECORD_AUDIO)) { granted, denied ->
            if (!granted) {
                result.postError(ErrorCodes.PERMISSION_DENIED, "microphone permission denied", denied)
                return@ensure
            }
            scope.launch {
                withContext(Dispatchers.Main) {
                    OneShotRecognizer(appContext).listen(
                        timeoutMs = timeoutMs,
                        onText = { text -> result.postSuccess(text) },
                        onError = { e -> result.postError(ErrorCodes.CORE_ERROR, e.message) },
                    )
                }
            }
        }
    }

    fun dispose() {
        methods.setMethodCallHandler(null)
        state.dispose()
        scope.cancel()
    }
}
