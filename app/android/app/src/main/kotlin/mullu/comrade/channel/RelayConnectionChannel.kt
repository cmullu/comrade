package mullu.comrade.channel

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import mullu.comrade.BackgroundConnectivityPreference
import mullu.comrade.ChatEventRouter
import mullu.comrade.ComradeCore
import mullu.comrade.EventBus
import mullu.comrade.MeshStatusMonitor
import mullu.comrade.RelayConnectionService

/**
 * Control + state for `RelayConnectionService` — the foreground `dataSync`
 * service that buys the process a priority floor while nothing is visible.
 *
 * See `../PLATFORM_CHANNELS.md` §8. The load-bearing property of this channel
 * is what it cannot do: **there is no method that drains the event queue.**
 * `EventBus`'s three-tier discipline (critical never-dropped, coalesced
 * latest-per-key, feed bounded-lossy — `ComradeCore.kt:627-739`, AUDIT
 * COMMS-04) is only correct with one consumer, and that consumer routes
 * incoming call signals into `CallManager` and raises the ringing notification
 * (`ChatEventRouter.route`). A second drainer in Dart would silently steal call
 * offers from the code that makes the phone ring.
 *
 * The single consumer is `EventPump`, not this service. That distinction is not
 * pedantry: the service is gated on a user preference, so while it owned the
 * loop, turning the preference off stopped delivery even with the app open.
 * `EventPump` is held by whoever needs it — this service while it runs,
 * `MainActivity` while it is visible — and still guarantees exactly one loop.
 *
 * What crosses instead is what `ChatEventRouter` already publishes: ticks that
 * say "re-read from Rust", not the data itself.
 */
internal class RelayConnectionChannel(
    messenger: BinaryMessenger,
    private val appContext: Context,
) : MethodChannel.MethodCallHandler {

    private val methods = MethodChannel(messenger, ChannelNames.RELAY)
        .also { it.setMethodCallHandler(this) }

    private val state = EventChannelRelay(messenger, ChannelNames.RELAY_STATE) { stateFlow() }

    /**
     * Feed revision counter. The native list stays authoritative for dedup and
     * the 500-item cap (`ChatEventRouter.FEED_CAP`); Dart's copy is a
     * projection of it. Emitting the whole list on every arrival would be
     * 500 maps per new Chitthi, so the relay sends the full list once (on
     * subscribe) and one item per delta thereafter — `revision` lets Dart
     * detect a missed window and re-request.
     */
    private fun stateFlow(): Flow<Any?> = combine(
        ChatEventRouter.chatTick,
        ChatEventRouter.requestTick,
        MeshStatusMonitor.status,
        ComradeCore.eventBusStats,
        ChatEventRouter.feedItems,
    ) { chatTick, requestTick, mesh, stats, feed ->
        mapOf(
            "running" to true,
            "chatTick" to chatTick,
            "requestTick" to requestTick,
            "mesh" to mapOf("active" to mesh.active, "peerCount" to mesh.peerCount),
            "eventBus" to stats.toMap(),
            // Whole list every emission: `feedItems` is a StateFlow of an
            // immutable list, so a delta form would need change tracking the
            // native side does not keep. Bounded at FEED_CAP = 500 and only
            // re-sent when the list actually changes identity, which is once
            // per arriving Chitthi.
            "feed" to mapOf(
                "revision" to feed.size,
                "items" to feed.map { it.toMap() },
            ),
        )
    }

    private fun EventBus.Stats.toMap(): Map<String, Any?> = mapOf(
        "criticalDepth" to criticalDepth,
        "coalescedDepth" to coalescedDepth,
        "feedDepth" to feedDepth,
        "feedDrops" to feedDrops,
        "coalesceSuppressions" to coalesceSuppressions,
        "lastDequeueLagMs" to lastDequeueLagMs,
    )

    private fun ComradeCore.ChitthiInfo.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "author" to author,
        "content" to content,
        "createdAt" to createdAt,
        "replyTo" to replyTo,
    )

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                // Respects BackgroundConnectivityPreference exactly as
                // RelayConnectionService.start does — returns false rather than
                // starting when the user has opted out. The preference stays
                // native because the *service* is what reads it at start time.
                "start" -> {
                    val enabled = BackgroundConnectivityPreference.isEnabled(appContext)
                    if (enabled) RelayConnectionService.start(appContext)
                    result.postSuccess(enabled)
                }
                "stop" -> {
                    RelayConnectionService.stop(appContext)
                    result.postSuccess()
                }
                "isEnabled" -> result.postSuccess(BackgroundConnectivityPreference.isEnabled(appContext))
                "setEnabled" -> {
                    val enabled = call.requireBool("enabled")
                    BackgroundConnectivityPreference.setEnabled(appContext, enabled)
                    if (enabled) RelayConnectionService.start(appContext) else RelayConnectionService.stop(appContext)
                    result.postSuccess()
                }
                // Notification suppression for the thread on screen. Dart must
                // clear this on app detach — a detached engine cannot mean
                // "still reading this thread". See PLATFORM_CHANNELS.md §8.
                "setOpenConversation" -> {
                    ChatEventRouter.setOpenConversation(call.argument<String>("peer"))
                    result.postSuccess()
                }
                "bumpChatTick" -> {
                    ChatEventRouter.bumpChatTick()
                    result.postSuccess()
                }
                "refreshNames" -> {
                    // Single-flight and rate-limited on the native side, and
                    // never awaited — a slow relay cannot stall anything here.
                    ChatEventRouter.maybeRefreshNames()
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
