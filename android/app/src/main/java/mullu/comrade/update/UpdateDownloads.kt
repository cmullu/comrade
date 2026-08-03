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

    /**
     * Handed to the system installer — the OS is asking the user to confirm.
     * Carries [path] as well as [version] so this can be walked back to [Ready]
     * if the hand-off never comes back (see [UpdateDownloads.dismissInstalling]).
     */
    data class Installing(val version: String, val path: String) : UpdateDownloadState()

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
     * Bring the state back in line with the cache. Called whenever a settings
     * card resumes, which is the moment all three of these can be true:
     *
     * - a "ready to install" whose APK is no longer there (the OS reaped the
     *   cache, or the install went through and it was deleted) has to stop
     *   offering an Install button that points at nothing;
     * - so does an "installing" whose APK has gone;
     * - and, the other way round, a *fresh process* starts at [Idle] while a
     *   perfectly good verified APK is still sitting in the cache from before it
     *   was killed. Without [cached] the card would cheerfully ask the user to
     *   download the same hundred megabytes again.
     */
    fun reconcile(exists: (String) -> Boolean, cached: UpdateDownloadState.Ready?) {
        _state.value = reconciled(_state.value, exists, cached)
    }

    /** The rule behind [reconcile], pure so `UpdateDownloadsTest` can drive it. */
    internal fun reconciled(
        current: UpdateDownloadState,
        exists: (String) -> Boolean,
        cached: UpdateDownloadState.Ready?,
    ): UpdateDownloadState = when {
        current is UpdateDownloadState.Ready && !exists(current.path) -> UpdateDownloadState.Idle
        current is UpdateDownloadState.Installing && !exists(current.path) -> UpdateDownloadState.Idle
        // Only ever *promotes* a resting state. An in-flight download or a live
        // hand-off to the installer is the newer truth and is left alone.
        cached != null && (current is UpdateDownloadState.Idle || current is UpdateDownloadState.Failed) -> cached
        else -> current
    }

    /**
     * Walk an [UpdateDownloadState.Installing] back to [UpdateDownloadState.Ready].
     *
     * The escape hatch for a hand-off that never came back. The app cannot know
     * that the system installer failed to show its dialog — a dropped activity
     * start reports nothing — so the user gets a "Try again" that returns the
     * card to a state where Install can be tapped, rather than a screen that
     * says "Waiting for Android…" forever.
     */
    fun dismissInstalling(exists: (String) -> Boolean) {
        val current = _state.value as? UpdateDownloadState.Installing ?: return
        _state.value = if (exists(current.path)) {
            UpdateDownloadState.Ready(current.version, current.path)
        } else {
            UpdateDownloadState.Idle
        }
    }
}
