package mullu.comrade.channel

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import mullu.comrade.update.UpdateChecker
import mullu.comrade.update.UpdateDownloadState
import mullu.comrade.update.UpdateDownloads
import mullu.comrade.update.UpdateStatus

/**
 * Control + state for the in-app update check — "is there a newer Comrade, and
 * how do I get it".
 *
 * See `../PLATFORM_CHANNELS.md` §10. The rule itself
 * (`mullu.comrade.update.UpdateCheck`) and the network call live in the
 * preserved native tree, shared with the Compose app, so the two frontends
 * cannot disagree about what counts as an upgrade.
 *
 * Nothing here takes a URL. `check` hits the one endpoint compiled into
 * `UpdateCheck.LATEST_RELEASE_URL`, `download` fetches the APK asset that
 * endpoint reported, and `openRelease` opens that release's page — an update
 * path is code execution, so a settable source would be a way to point
 * someone's upgrade at another APK.
 *
 * `download` returns as soon as the foreground service has been started: the
 * transfer outlives the engine, shows progress in the notification shade, and
 * reports through the state channel. `install` hands the verified APK to the
 * system installer, which always shows its own confirmation.
 */
internal class UpdateChannel(
    messenger: BinaryMessenger,
    private val appContext: Context,
) : MethodChannel.MethodCallHandler {

    private val methods = MethodChannel(messenger, ChannelNames.UPDATES)
        .also { it.setMethodCallHandler(this) }

    private val state = EventChannelRelay(messenger, ChannelNames.UPDATES_STATE) { stateFlow() }

    /**
     * `UpdateChecker.status` is a `StateFlow`, so a reattaching UI gets the
     * current answer as its first event — including one found while the engine
     * was detached.
     */
    private fun stateFlow(): Flow<Any?> = combine(
        UpdateChecker.status,
        UpdateDownloads.state,
    ) { check, download ->
        mapOf("check" to check.toMap(), "download" to download.toMap())
    }

    private fun UpdateStatus.toMap(): Map<String, Any?> = when (this) {
        UpdateStatus.Unknown -> mapOf("status" to "unknown")
        UpdateStatus.Checking -> mapOf("status" to "checking")
        is UpdateStatus.UpToDate -> mapOf("status" to "upToDate", "checkedAt" to checkedAt)
        is UpdateStatus.Failed -> mapOf("status" to "failed", "message" to message, "checkedAt" to attemptedAt)
        is UpdateStatus.Available -> mapOf(
            "status" to "available",
            "checkedAt" to checkedAt,
            "version" to release.versionName,
            "tag" to release.tag,
            "notes" to release.notes,
            "pageUrl" to release.pageUrl,
            "apkBytes" to release.apkBytes,
        )
    }

    private fun UpdateDownloadState.toMap(): Map<String, Any?> = when (this) {
        UpdateDownloadState.Idle -> mapOf("state" to "idle")
        is UpdateDownloadState.Downloading -> mapOf(
            "state" to "downloading",
            "bytesRead" to bytesRead,
            "totalBytes" to totalBytes,
        )
        UpdateDownloadState.Verifying -> mapOf("state" to "verifying")
        is UpdateDownloadState.Ready -> mapOf("state" to "ready", "version" to version)
        is UpdateDownloadState.Installing -> mapOf("state" to "installing", "version" to version)
        is UpdateDownloadState.Failed -> mapOf("state" to "failed", "message" to message)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "settings" -> result.postSuccess(
                    mapOf(
                        "currentVersion" to UpdateChecker.currentVersion,
                        "autoCheck" to UpdateChecker.isAutoCheckEnabled(appContext),
                        "lastCheckedAt" to UpdateChecker.lastCheckedAt(appContext),
                        "skippedVersion" to UpdateChecker.skippedVersion(appContext),
                        // Granted outside this app and revocable at any time, so
                        // it is read here rather than cached anywhere in Dart.
                        "canInstall" to UpdateChecker.canInstall(appContext),
                    ),
                )
                // Fire-and-forget: the answer arrives on the state channel, so a
                // Dart caller never waits on a relay round-trip.
                "check" -> {
                    UpdateChecker.maybeCheck(appContext, force = call.argument<Boolean>("force") ?: false)
                    result.postSuccess()
                }
                "setAutoCheck" -> {
                    UpdateChecker.setAutoCheckEnabled(appContext, call.requireBool("enabled"))
                    result.postSuccess()
                }
                "skip" -> {
                    UpdateChecker.skip(appContext, call.requireString("version"))
                    result.postSuccess()
                }
                "unskip" -> {
                    UpdateChecker.unskip(appContext)
                    result.postSuccess()
                }
                // Fire-and-forget: the service owns the transfer from here, and
                // its progress arrives on the state channel.
                "download" -> {
                    UpdateChecker.download(appContext)
                    result.postSuccess()
                }
                "cancelDownload" -> {
                    UpdateChecker.cancelDownload()
                    result.postSuccess()
                }
                "install" -> {
                    UpdateChecker.install(appContext)
                    result.postSuccess()
                }
                "refreshDownloadState" -> {
                    UpdateChecker.refreshDownloadState()
                    result.postSuccess()
                }
                "openInstallPermissionSettings" -> {
                    UpdateChecker.openInstallPermissionSettings(appContext)
                    result.postSuccess()
                }
                "openRelease" -> {
                    // The release currently reported by the checker, not one
                    // named by the caller.
                    val current = UpdateChecker.status.value
                    UpdateChecker.openRelease(appContext, (current as? UpdateStatus.Available)?.release)
                    result.postSuccess()
                }
                else -> result.postNotImplemented()
            }
        } catch (e: MissingArgument) {
            result.postError(ErrorCodes.BAD_ARGUMENT, e.message)
        }
    }

    fun dispose() {
        methods.setMethodCallHandler(null)
        state.dispose()
    }
}
