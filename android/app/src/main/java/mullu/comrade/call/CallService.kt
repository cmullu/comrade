package mullu.comrade.call

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import mullu.comrade.CallActionReceiver
import mullu.comrade.MainActivity
import mullu.comrade.Notifier

/**
 * Keeps the process alive and visible while a call has live media, so
 * backgrounding the app (checking another app mid-call, letting the screen
 * lock) can't get the process reclaimed and the call dropped — a plain
 * foreground Activity offers no such guarantee once the user navigates away.
 *
 * Holds only the foreground-service contract (the ongoing notification with
 * its hang-up action and tap-to-return); [CallManager] still owns all the
 * actual media/signaling state. [CallManager] is also what starts and stops
 * this service — from the same call-setup/teardown points that already start
 * and stop audio routing — rather than the UI layer, so it doesn't depend on
 * any Activity/Compose tree being alive to fire correctly.
 */
class CallService : Service() {

    /**
     * Drives the in-tray controls: the notification is re-posted whenever the
     * call becomes Active (mute + audio-route buttons appear beside Hang up,
     * Google-Phone style), the mute flips, or the route changes — so the
     * buttons always show the *current* state, not the state at ring time.
     * Main.immediate because the flows are cheap StateFlows and notify() is
     * main-thread-safe; cancelled in [onDestroy].
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var peer: String = ""
    private var peerLabel: String = ""

    /**
     * The single control-observing collector. Held so a second
     * `onStartCommand` — a redelivered intent, or `CallManager` restarting the
     * service for a new call — replaces it instead of stacking a second
     * collector that would fight the first over the same notification.
     */
    private var controlsJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Honor the foreground-service contract *immediately*: this service
        // is always started with startForegroundService(), which demands a
        // startForeground() call before the service stops — including when
        // onStartCommand bails on a blank redelivered intent, and when
        // CallManager's teardown stopService() lands before onStartCommand
        // has even run (a call that failed in the same breath it was
        // placed). Missing that window is not an error state Android logs —
        // it is a hard crash of the whole process
        // (ForegroundServiceDidNotStartInTimeException). Go foreground with
        // a generic placeholder here; onStartCommand replaces it in place
        // (same NOTIFICATION_ID) with the real peer-labeled notification.
        startForegroundNotified(peer = "", peerLabel = getString(mullu.comrade.R.string.call_voice), video = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peer = intent?.getStringExtra(EXTRA_PEER)
        if (peer.isNullOrEmpty()) {
            // A system-triggered restart (e.g. the process was killed under
            // memory pressure) can redeliver a blank/null intent with no
            // guarantee the original extras survived. There is no call to
            // show — stop. Safe contract-wise: onCreate already called
            // startForeground, and stopping removes its notification.
            stopSelf()
            return START_NOT_STICKY
        }
        val peerLabel = intent.getStringExtra(EXTRA_PEER_LABEL)?.ifBlank { null } ?: peer
        val video = intent.getBooleanExtra(EXTRA_VIDEO, false)
        this.peer = peer
        this.peerLabel = peerLabel
        startForegroundNotified(peer, peerLabel, video)
        observeCallControls()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Re-post the notification when what its buttons should say changes.
     *
     * `combine` of three StateFlows is conflated and emits an initial value,
     * so the first collection simply re-renders what onStartCommand already
     * posted — same NOTIFICATION_ID, an in-place update, never a second row.
     * Post-33 a revoked POST_NOTIFICATIONS makes notify() throw; the
     * foreground notification from startForeground still stands in that case,
     * so the update is best-effort by design.
     */
    private fun observeCallControls() {
        controlsJob?.cancel()
        controlsJob = scope.launch {
            combine(
                CallManager.state,
                CallManager.muted,
                CallManager.audioRoute,
            ) { state, muted, route -> Triple(state is CallUiState.Active, muted, route) }
                .collect { (active, muted, route) ->
                    if (peer.isEmpty()) return@collect
                    runCatching {
                        NotificationManagerCompat.from(this@CallService)
                            .notify(NOTIFICATION_ID, buildNotification(peer, peerLabel, active, muted, route))
                    }
                }
        }
    }

    private fun startForegroundNotified(peer: String, peerLabel: String, video: Boolean) {
        Notifier.ensureChannels(this)
        val notification = buildNotification(
            peer,
            peerLabel,
            active = CallManager.state.value is CallUiState.Active,
            muted = CallManager.muted.value,
            route = CallManager.audioRoute.value,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (video) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * [NotificationCompat.CallStyle.forOngoingCall] — Hang up + tap-to-return
     * via [MainActivity], always. Once the call is *answered* ([active]) the
     * tray gains two more buttons, Google-Phone style: **mute/unmute** and the
     * **audio route** (tapping cycles earpiece → speaker → Bluetooth/wired,
     * whichever are present — [CallManager.cycleAudioRoute], labelled with the
     * route audio is on right now). Before answer it is deliberately just the
     * one button: muting a call that hasn't connected is noise, and CallStyle
     * only guarantees space for a couple of custom actions anyway.
     */
    private fun buildNotification(
        peer: String,
        peerLabel: String,
        active: Boolean,
        muted: Boolean,
        route: AudioRoute,
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangUpIntent = PendingIntent.getBroadcast(
            this,
            peer.hashCode(),
            Intent(this, CallActionReceiver::class.java)
                .setAction(CallActionReceiver.ACTION_HANGUP)
                .putExtra(CallActionReceiver.EXTRA_PEER, peer),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val person = Person.Builder().setName(peerLabel).build()
        val style = NotificationCompat.CallStyle.forOngoingCall(person, hangUpIntent)
        val builder = NotificationCompat.Builder(this, Notifier.CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(peerLabel)
            .setStyle(style)
            .addPerson(person)
            .setOngoing(true)
            .setUsesChronometer(true) // a live-ticking duration, no polling needed on our side
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openApp)
        if (active) {
            // Distinct request codes per action: same-receiver PendingIntents
            // with equal codes would collapse into one another.
            builder.addAction(
                android.R.drawable.ic_lock_silent_mode,
                getString(if (muted) mullu.comrade.R.string.call_unmute else mullu.comrade.R.string.call_mute),
                PendingIntent.getBroadcast(
                    this,
                    peer.hashCode() + 1,
                    Intent(this, CallActionReceiver::class.java)
                        .setAction(CallActionReceiver.ACTION_TOGGLE_MUTE)
                        .putExtra(CallActionReceiver.EXTRA_PEER, peer),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            builder.addAction(
                android.R.drawable.ic_btn_speak_now,
                getString(routeLabelRes(route)),
                PendingIntent.getBroadcast(
                    this,
                    peer.hashCode() + 2,
                    Intent(this, CallActionReceiver::class.java)
                        .setAction(CallActionReceiver.ACTION_CYCLE_ROUTE)
                        .putExtra(CallActionReceiver.EXTRA_PEER, peer),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        return builder.build()
    }

    /** The button is labelled with where audio is *now*; tapping cycles onward. */
    private fun routeLabelRes(route: AudioRoute): Int = when (route) {
        AudioRoute.EARPIECE -> mullu.comrade.R.string.call_route_earpiece
        AudioRoute.SPEAKER -> mullu.comrade.R.string.call_route_speaker
        AudioRoute.BLUETOOTH -> mullu.comrade.R.string.call_route_bluetooth
        AudioRoute.WIRED -> mullu.comrade.R.string.call_route_wired
    }

    companion object {
        private const val NOTIFICATION_ID = 0xCA11
        private const val EXTRA_PEER = "peer"
        private const val EXTRA_PEER_LABEL = "peerLabel"
        private const val EXTRA_VIDEO = "video"

        fun start(context: Context, peer: String, peerLabel: String, video: Boolean) {
            val intent = Intent(context, CallService::class.java)
                .putExtra(EXTRA_PEER, peer)
                .putExtra(EXTRA_PEER_LABEL, peerLabel)
                .putExtra(EXTRA_VIDEO, video)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
