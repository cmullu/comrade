package mullu.comrade.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the update APK's download-and-install has got to. */
sealed class UpdateDownloadState {
    /** Nothing in flight — the card shows the download offer. */
    object Idle : UpdateDownloadState()

    /** [totalBytes] is 0 until the server (or the release asset) says. */
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : UpdateDownloadState()

    /** Downloaded; checking size, package, version and signer before offering it. */
    object Verifying : UpdateDownloadState()

    /** Verified and sitting in the cache, ready for the installer. */
    data class Ready(val version: String, val path: String) : UpdateDownloadState()

    /** Handed to the system installer — the OS is asking the user to confirm. */
    data class Installing(val version: String) : UpdateDownloadState()

    data class Failed(val message: String) : UpdateDownloadState()
}

/**
 * Process-wide state for the update download, and the cancel flag the service's
 * worker polls.
 *
 * Held here rather than in [UpdateDownloadService] for the same reason
 * `ModelDownloads` is: the transfer has to survive the screen that started it.
 * Backgrounding the app, or leaving Settings, leaves the download running with
 * its progress in the notification shade, and any screen that later observes
 * [state] — either frontend's settings card — picks it up mid-transfer.
 *
 * One download at a time: there is only ever one newest release.
 */
object UpdateDownloads {

    private val _state = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val state: StateFlow<UpdateDownloadState> = _state.asStateFlow()

    @Volatile
    private var cancelled = false

    internal fun update(next: UpdateDownloadState) {
        _state.value = next
    }

    /** Ask an in-flight download to abort; the partial file is deleted. */
    fun cancel() {
        cancelled = true
    }

    internal fun isCancelled(): Boolean = cancelled

    internal fun clearCancel() {
        cancelled = false
    }

    /**
     * Forget a downloaded-and-ready APK that is no longer there (the OS reaped
     * the cache, or the install succeeded and it was deleted) so the card offers
     * the download again instead of an "Install" button pointing at nothing.
     */
    fun forgetIfGone(exists: (String) -> Boolean) {
        val current = _state.value
        if (current is UpdateDownloadState.Ready && !exists(current.path)) {
            _state.value = UpdateDownloadState.Idle
        }
    }
}
