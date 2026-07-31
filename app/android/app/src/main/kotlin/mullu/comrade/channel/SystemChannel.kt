package mullu.comrade.channel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import mullu.comrade.MutedChats
import mullu.comrade.Notifier

/**
 * Notification channels, notification clearing, the POST_NOTIFICATIONS grant,
 * and the notification → tab navigation hop.
 *
 * See `../PLATFORM_CHANNELS.md` §9. **Notification channel ids are frozen.**
 * `comrade_calls_v2` especially: the `_v2` suffix exists because channel
 * settings are sticky once created, so silencing the original id would never
 * have taken effect for upgrading installs, and it carries `setSound(null, null)`
 * so `Ringer` is the only thing that rings (`Notifier.kt:31-40`, `:70-82`).
 * Changing the id, or restoring a sound to it, double-rings every incoming call
 * on every existing install. `Notifier` is untouched by this phase and this
 * channel exposes no way to create or alter a channel — only to ensure the
 * known set exists.
 */
internal class SystemChannel(
    messenger: BinaryMessenger,
    private val appContext: Context,
    private val permissions: PermissionBridge,
) : MethodChannel.MethodCallHandler {

    private val methods = MethodChannel(messenger, ChannelNames.SYSTEM)
        .also { it.setMethodCallHandler(this) }

    /**
     * A tab request from a notification that arrived while no engine was
     * attached (see `ModelDownloadService`'s "download finished" notification,
     * which carries `AppNavigation.EXTRA_OPEN_TAB`).
     *
     * Stashed rather than dropped, and delivered by `consumePendingTab` on the
     * next start. This is the *only* Kotlin → Dart direction in the whole
     * contract, and it is fire-and-forget by construction: nothing native waits
     * on the reply, so a permanently-absent engine costs nothing.
     */
    @Volatile
    private var pendingTab: String? = null

    /**
     * The same stash for a tapped **message** notification, which names a
     * conversation rather than a tab (WP11). Kept separate from [pendingTab]
     * because both can be pending at once — a cold start from a message
     * notification wants the Chats tab *and* that thread.
     */
    @Volatile
    private var pendingPeer: String? = null

    fun requestTab(tab: String?) {
        if (tab.isNullOrBlank()) return
        pendingTab = tab
        methods.invokeMethod("openTab", mapOf("tab" to tab))
    }

    fun requestPeer(peer: String?) {
        if (peer.isNullOrBlank()) return
        pendingPeer = peer
        methods.invokeMethod("openConversation", mapOf("peer" to peer))
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "ensureNotificationChannels" -> {
                    Notifier.ensureChannels(appContext)
                    result.postSuccess()
                }
                "hasNotificationPermission" -> result.postSuccess(Notifier.hasPermission(appContext))
                "requestNotificationPermission" -> requestNotificationPermission(result)
                "clearForPeer" -> {
                    Notifier.clearForPeer(appContext, call.requireString("peer"))
                    result.postSuccess()
                }
                "clearCall" -> {
                    Notifier.clearCall(appContext, call.requireString("peer"))
                    result.postSuccess()
                }
                "consumePendingTab" -> {
                    val tab = pendingTab
                    pendingTab = null
                    result.postSuccess(tab)
                }
                "consumePendingPeer" -> {
                    val peer = pendingPeer
                    pendingPeer = null
                    result.postSuccess(peer)
                }
                // Per-conversation mute. Device-local by construction (see
                // `MutedChats`), and read by the *native* notification path, so
                // Dart cannot hold this state itself — it has to ask.
                "mutedPeers" -> result.postSuccess(MutedChats.muted(appContext).toList())
                "isMuted" -> result.postSuccess(MutedChats.isMuted(appContext, call.requireString("peer")))
                "setMuted" -> {
                    val peer = call.requireString("peer")
                    val muted = call.requireBool("muted")
                    MutedChats.setMuted(appContext, peer, muted)
                    // Muting with a notice already in the shade would otherwise
                    // leave the buzz it was meant to stop sitting there.
                    if (muted) Notifier.clearForPeer(appContext, peer)
                    result.postSuccess()
                }
                "unmuteAll" -> {
                    MutedChats.unmuteAll(appContext)
                    result.postSuccess()
                }
                "areNotificationsEnabled" ->
                    result.postSuccess(NotificationManagerCompat.from(appContext).areNotificationsEnabled())
                // The OS's own per-app notification screen, where the channels
                // live. Launched with NEW_TASK because this may be called with
                // no Activity attached.
                "openNotificationSettings" -> {
                    appContext.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    result.postSuccess()
                }
                else -> result.postNotImplemented()
            }
        } catch (e: MissingArgument) {
            result.postError(ErrorCodes.BAD_ARGUMENT, e.message)
        }
    }

    private fun requestNotificationPermission(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            result.postSuccess(true)
            return
        }
        if (permissions.activity == null) {
            result.postError(ErrorCodes.NO_ACTIVITY, "no Activity attached to request POST_NOTIFICATIONS")
            return
        }
        permissions.ensure(listOf(Manifest.permission.POST_NOTIFICATIONS)) { granted, _ ->
            result.postSuccess(granted)
        }
    }

    fun dispose() {
        methods.setMethodCallHandler(null)
    }
}
