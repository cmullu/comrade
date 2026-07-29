package mullu.comrade

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import mullu.comrade.call.CallManager
import mullu.comrade.call.CallService

/**
 * Handles the Decline and Hang Up actions on the call notifications'
 * `CallStyle` ([Notifier.notifyIncomingCall], [mullu.comrade.call.CallService]'s
 * ongoing-call notification). Accept has no entry here — it needs mic/camera
 * runtime permission a receiver cannot request, so its `PendingIntent` just
 * opens [MainActivity], where the ringing call screen already gates Accept on
 * those permissions.
 *
 * Declining works independent of whether [MainActivity]'s Compose tree is
 * currently active: it both ends the call ([CallManager.reject]) and clears
 * the notification directly, rather than relying on the UI's state-observing
 * side effect (which only runs while an Activity is composed). Hanging up
 * normally needs no such direct clear — [CallManager.hangup] tears the call
 * down, which stops [CallService] and removes its notification with it — but
 * that only works while [CallManager]'s in-memory session survived; if the
 * process was killed under memory pressure and only just restarted to
 * deliver this broadcast, [CallManager.hangup] silently no-ops on a null
 * session, so the service/notification are stopped/cleared directly too.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val peer = intent.getStringExtra(EXTRA_PEER) ?: return
        when (intent.action) {
            ACTION_DECLINE -> {
                CallManager.reject()
                Notifier.clearCall(context, peer)
            }
            ACTION_HANGUP -> {
                CallManager.hangup()
                runCatching { context.stopService(Intent(context, CallService::class.java)) }
                Notifier.clearCall(context, peer)
            }
            // The ongoing-call notification's in-tray controls (Google-Phone
            // style). Both are plain CallManager calls — safe with no UI, no
            // engine, and even from a freshly-restarted process (they no-op on
            // a null session). The notification itself is re-rendered by
            // CallService, which observes the flows these mutate.
            ACTION_TOGGLE_MUTE -> CallManager.toggleMute()
            ACTION_CYCLE_ROUTE -> CallManager.cycleAudioRoute()
        }
    }

    companion object {
        const val ACTION_DECLINE = "mullu.comrade.call.DECLINE"
        const val ACTION_HANGUP = "mullu.comrade.call.HANGUP"
        const val ACTION_TOGGLE_MUTE = "mullu.comrade.call.TOGGLE_MUTE"
        const val ACTION_CYCLE_ROUTE = "mullu.comrade.call.CYCLE_ROUTE"
        const val EXTRA_PEER = "peer"
    }
}
