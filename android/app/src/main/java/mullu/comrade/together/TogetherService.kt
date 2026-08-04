package mullu.comrade.together

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mullu.comrade.MainActivity
import mullu.comrade.Notifier
import mullu.comrade.R

/**
 * Keeps playback alive and visible while a watch-together session is running,
 * so putting the phone down or switching apps does not stop the film for both
 * people.
 *
 * Holds only the foreground-service contract — [TogetherManager] still owns the
 * player and every sync decision, and is also what starts and stops this, from
 * the same points it opens and releases the player rather than from any Compose
 * tree. A session must not end because a screen was disposed.
 *
 * ## Why there is a `MediaSession` and why it is the framework one
 * On Android 14 a `mediaPlayback` foreground service is expected to look like
 * media playback, and the platform routes hardware media keys through a session.
 * `androidx.media3.session` would do this too and bring ~2 MB of adaptive
 * streaming this feature does not use — so this is `android.media.session`, in
 * the framework since API 21, costing no dependency at all. The repo declines
 * dependencies for considerably less.
 *
 * ## The promotion contract
 * `startForeground` happens in [onCreate], before any intent is examined. That
 * is not defensive style, it is the rule `.claude/rules/android.md` records in
 * blood: the obligation arms per *call* to `startForegroundService`, a refused
 * promotion does not cancel the pending kill, and `runCatching` at the call site
 * cannot catch a throw that happens later on this service's own looper. The only
 * safe move is to make promotion succeed immediately and refine the notification
 * in place afterwards.
 */
class TogetherService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        session = MediaSession(this, "comrade-together").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = togglePlayback(play = true)
                override fun onPause() = togglePlayback(play = false)
                override fun onStop() = TogetherManager.leave()
            })
            isActive = true
        }
        // Honour the contract immediately with a generic notification; the
        // collector below replaces it in place (same id) as the session's real
        // state arrives.
        promote(title = getString(R.string.together_notification_title), text = "")
        stateJob = scope.launch {
            TogetherManager.state.collect { state ->
                when (state) {
                    is TogetherManager.UiState.Live -> {
                        publishPlaybackState(state.playing, state.positionMs)
                        promote(
                            title = state.title.ifBlank {
                                getString(R.string.together_notification_title)
                            },
                            text = getString(R.string.together_notification_with, state.peerLabel),
                        )
                    }
                    // The session ended while we were still up; TogetherManager
                    // stops us, but do not keep claiming to be playing meanwhile.
                    else -> publishPlaybackState(playing = false, positionMs = 0)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-promote first, always: the obligation arms per *call* to
        // startForegroundService, so a redelivered intent that bailed early
        // would strand this instance and kill the process.
        promote(title = getString(R.string.together_notification_title), text = "")
        if (intent?.action == ACTION_LEAVE) {
            TogetherManager.leave()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        session?.isActive = false
        session?.release()
        session = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun togglePlayback(play: Boolean) {
        val live = TogetherManager.state.value as? TogetherManager.UiState.Live ?: return
        TogetherManager.setState(live.positionMs, play)
    }

    private fun publishPlaybackState(playing: Boolean, positionMs: Long) {
        val state = PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                positionMs,
                1.0f,
            )
            .build()
        runCatching { session?.setPlaybackState(state) }
    }

    private fun promote(title: String, text: String) {
        val notification = build(title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun build(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val leave = PendingIntent.getService(
            this,
            1,
            Intent(this, TogetherService::class.java).setAction(ACTION_LEAVE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Notifier.CHANNEL_CONNECTION)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(0, getString(R.string.together_leave), leave)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 4102
        const val ACTION_LEAVE = "mullu.comrade.together.LEAVE"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TogetherService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TogetherService::class.java))
        }
    }
}
