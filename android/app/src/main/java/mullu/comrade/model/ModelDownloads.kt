package mullu.comrade.model

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where one model's on-demand download has got to. */
sealed class ModelDownloadState {
    /** Nothing in flight — the UI shows the download offer. */
    object Idle : ModelDownloadState()

    /** [totalBytes] falls back to the spec's figure until the server reports one. */
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : ModelDownloadState()

    /** Download finished; checksum + extract + move into place (quick, but not instant). */
    object Installing : ModelDownloadState()

    data class Failed(val message: String) : ModelDownloadState()

    /** Installed where the model's loader looks for it. */
    object Ready : ModelDownloadState()
}

/**
 * Process-wide download state for every model in [ModelCatalog], plus the
 * cancel flag [ModelDownloadService]'s worker polls.
 *
 * State is held here rather than in the service so that a download survives
 * the screen that started it: dismissing the prompt (or backgrounding the app)
 * leaves the transfer running, and any screen that later observes
 * [stateOf] — the voice prompt, Settings, Tara — sees the same progress.
 */
object ModelDownloads {

    private val flows = ConcurrentHashMap<String, MutableStateFlow<ModelDownloadState>>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    private fun flow(id: String): MutableStateFlow<ModelDownloadState> =
        flows.getOrPut(id) { MutableStateFlow(ModelDownloadState.Idle) }

    /** Observable state for the model with this id. */
    fun stateOf(id: String): StateFlow<ModelDownloadState> = flow(id)

    fun current(id: String): ModelDownloadState = flow(id).value

    internal fun update(id: String, state: ModelDownloadState) {
        flow(id).value = state
    }

    /** Ask an in-flight download to abort; partial files are deleted. */
    fun cancel(id: String) {
        cancelled.add(id)
    }

    internal fun isCancelled(id: String): Boolean = cancelled.contains(id)

    internal fun clearCancel(id: String) {
        cancelled.remove(id)
    }

    /**
     * Re-arm the offer when a stale in-memory [ModelDownloadState.Ready] no
     * longer holds (the installed files vanished, e.g. the user cleared app
     * storage) — otherwise a prompt would keep firing its ready-callback into
     * a load that keeps failing.
     */
    fun reofferIfGone(context: Context, spec: ModelSpec) {
        if (current(spec.id) is ModelDownloadState.Ready &&
            !ModelCatalog.isInstalled(context, spec)
        ) {
            update(spec.id, ModelDownloadState.Idle)
        }
    }
}
