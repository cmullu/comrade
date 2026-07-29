package mullu.comrade.channel

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import mullu.comrade.model.ModelCatalog
import mullu.comrade.model.ModelDownloadService
import mullu.comrade.model.ModelDownloadState
import mullu.comrade.model.ModelDownloads
import mullu.comrade.model.ModelSpec

/**
 * Control + state for `ModelDownloadService` — the foreground `dataSync`
 * service that fetches the on-device speech/companion models with progress in
 * the notification shade.
 *
 * See `../PLATFORM_CHANNELS.md` §9. Everything security-relevant about a model
 * install — the sha256 pin, the zip-slip guard, the atomic staging swap — lives
 * in `ModelInstaller` and `ModelSpec.configured`, none of which this channel
 * can reach. `startDownload` takes a catalog **id**, never a URL: there is no
 * way to ask this surface to fetch an arbitrary artifact.
 *
 * `ModelSpec.configured == false` (the companion model is deliberately unpinned
 * — `ModelCatalog.kt:47-75`) must be surfaced as "not available", never as a
 * download offer. The service already fails such a request fast and visibly;
 * `catalog` carries the flag so the UI does not have to find out that way.
 */
internal class ModelDownloadChannel(
    messenger: BinaryMessenger,
    private val appContext: Context,
) : MethodChannel.MethodCallHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val methods = MethodChannel(messenger, ChannelNames.MODELS)
        .also { it.setMethodCallHandler(this) }

    private val state = EventChannelRelay(messenger, ChannelNames.MODELS_STATE) { stateFlow() }

    /**
     * One map keyed by model id. `ModelDownloads` holds the state process-wide
     * rather than in the service, precisely so a download survives the screen
     * that started it — so this flow keeps reporting real progress with the
     * engine detached and the app backgrounded, and a reattaching UI picks it
     * up mid-transfer.
     */
    private fun stateFlow(): Flow<Any?> = combine(
        ModelCatalog.all.map { ModelDownloads.stateOf(it.id) },
    ) { states ->
        ModelCatalog.all.mapIndexed { i, spec -> spec.id to states[i].toMap() }.toMap()
    }

    private fun ModelDownloadState.toMap(): Map<String, Any?> = when (this) {
        ModelDownloadState.Idle -> mapOf("status" to "idle")
        is ModelDownloadState.Downloading -> mapOf(
            "status" to "downloading",
            "bytesRead" to bytesRead,
            "totalBytes" to totalBytes,
        )
        ModelDownloadState.Installing -> mapOf("status" to "installing")
        ModelDownloadState.Ready -> mapOf("status" to "ready")
        is ModelDownloadState.Failed -> mapOf("status" to "failed", "message" to message)
    }

    private fun ModelSpec.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "displayName" to displayName,
        "downloadBytes" to downloadBytes,
        "configured" to configured,
        "returnTab" to returnTab,
    )

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "catalog" -> result.postSuccess(ModelCatalog.all.map { it.toMap() })
                "startDownload" -> withSpec(call, result) { spec ->
                    // Idempotent by design: a second request while one is in
                    // flight is reported through ModelDownloads, not silently
                    // dropped and never by tearing the running transfer down.
                    ModelDownloadService.start(appContext, spec.id)
                    result.postSuccess()
                }
                "cancelDownload" -> withSpec(call, result) { spec ->
                    ModelDownloadService.cancel(spec.id)
                    result.postSuccess()
                }
                "isInstalled" -> withSpec(call, result) { spec ->
                    scope.replyOnIo(result) { ModelCatalog.isInstalled(appContext, spec) }
                }
                "reofferIfGone" -> withSpec(call, result) { spec ->
                    scope.replyOnIo(result) {
                        ModelDownloads.reofferIfGone(appContext, spec)
                        null
                    }
                }
                else -> result.postNotImplemented()
            }
        } catch (e: MissingArgument) {
            result.postError(ErrorCodes.BAD_ARGUMENT, e.message)
        }
    }

    private inline fun withSpec(call: MethodCall, result: MethodChannel.Result, block: (ModelSpec) -> Unit) {
        val spec = ModelCatalog.byId(call.requireString("modelId"))
        if (spec == null) result.postError(ErrorCodes.NOT_FOUND, "unknown model id") else block(spec)
    }

    fun dispose() {
        methods.setMethodCallHandler(null)
        state.dispose()
        scope.cancel()
    }
}
