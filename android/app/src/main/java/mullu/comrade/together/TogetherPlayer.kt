package mullu.comrade.together

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.util.Log

/**
 * The player half of watch-together, behind a small interface so swapping the
 * implementation later is one file.
 *
 * **`MediaPlayer`, not ExoPlayer/Media3**, and the deciding detail is
 * [MediaPlayer.SEEK_CLOSEST]: the plain `seekTo(int)` seeks to the nearest sync
 * frame, so on a video with 5-10 s keyframe spacing "seek to 42.0" lands at 38.2
 * — a sync failure that looks like a working feature. The precise overload needs
 * API 26 and `minSdk` is 26, so it is simply available. Media3 would add ~2 MB
 * for adaptive streaming this feature does not use, in a repo that inlines icons
 * rather than take `material-icons-extended`.
 *
 * ## Output latency, stated honestly
 * What a listener hears is the decoder position minus this device's audio output
 * latency — 20-100 ms on Android, and different between handsets. Two players
 * agreeing perfectly on decoder position can still be a tenth of a second apart
 * in the room, which is the error no browser-based watch-party can even see.
 *
 * `AudioTrack.getTimestamp()` measures it properly, but `MediaPlayer` does not
 * hand out its `AudioTrack`, so [outputLatencyMs] here is an **estimate** from
 * the device's own low-latency buffer properties, not a measurement, and it is
 * documented as such rather than dressed up. Two things make it useful anyway:
 * it is a real per-device number rather than a constant, and what the sync
 * arithmetic actually uses is the *difference* between the two sides, which is
 * more accurate than either absolute figure. A true measurement needs an
 * `AudioTrack` we own — i.e. a Media3 migration — and is the honest follow-up.
 */
class TogetherPlayer(private val context: Context) {

    interface Listener {
        fun onPrepared(durationMs: Long)
        fun onSeekComplete(posMs: Long)
        fun onCompletion(posMs: Long)
        fun onError(message: String)
    }

    private var player: MediaPlayer? = null
    private var listener: Listener? = null

    /** `seekTo` before `prepare()` throws — every caller checks this first. */
    var prepared: Boolean = false
        private set

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    val positionMs: Long
        get() = if (prepared) (player?.currentPosition ?: 0).toLong() else 0L

    val durationMs: Long
        get() = if (prepared) (player?.duration ?: 0).toLong() else 0L

    val isPlaying: Boolean
        get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    fun open(uri: Uri) {
        release()
        prepared = false
        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            setOnPreparedListener {
                this@TogetherPlayer.prepared = true
                listener?.onPrepared(it.duration.toLong())
            }
            setOnSeekCompleteListener { listener?.onSeekComplete(it.currentPosition.toLong()) }
            setOnCompletionListener { listener?.onCompletion(it.currentPosition.toLong()) }
            setOnErrorListener { _, what, extra ->
                listener?.onError("player error $what/$extra")
                true
            }
        }
        player = mp
        runCatching {
            mp.setDataSource(context, uri)
            // Asynchronous: prepare() blocks, and a large local file on slow
            // storage would block whatever thread opened it.
            mp.prepareAsync()
        }.onFailure {
            Log.w(TAG, "could not open media", it)
            listener?.onError(it.message ?: "could not open this file")
        }
    }

    fun play() {
        if (!prepared) return
        runCatching { player?.start() }.onFailure { Log.w(TAG, "start failed", it) }
    }

    fun pause() {
        if (!prepared) return
        runCatching { player?.pause() }.onFailure { Log.w(TAG, "pause failed", it) }
    }

    /**
     * Seek to an exact position, not to the nearest keyframe — see the class
     * comment. This is the single call that decides whether the feature works.
     */
    fun seekTo(posMs: Long) {
        if (!prepared) return
        runCatching {
            player?.seekTo(posMs, MediaPlayer.SEEK_CLOSEST)
        }.onFailure { Log.w(TAG, "seek failed", it) }
    }

    /**
     * Trim the playback rate to close a small gap.
     *
     * Only ever called while playing: `setPlaybackParams` on a paused player
     * *starts* it on several Android versions, which would turn a drift
     * correction into an unasked-for resume. The guard is in
     * [TogetherDecisions.planCorrection] and tested there; this is the second
     * line of it.
     */
    fun setRate(rate: Float) {
        if (!prepared || !isPlaying) return
        runCatching {
            val mp = player ?: return
            mp.playbackParams = PlaybackParams().setSpeed(rate.coerceIn(TogetherDecisions.RATE_MIN, TogetherDecisions.RATE_MAX))
        }.onFailure { Log.w(TAG, "rate trim failed", it) }
    }

    fun release() {
        runCatching { player?.release() }
        player = null
        prepared = false
    }

    /**
     * An **estimate** of how far behind [positionMs] the sound actually leaves
     * the speaker — see the class comment for why this is not a measurement.
     *
     * Two output buffers' worth is the usual rule of thumb for the mixer path;
     * a device that reports nothing gets 0, which reads as "unmeasured" on the
     * wire and costs only the accuracy it cannot supply. It never guesses a
     * constant, because a wrong number applied confidently is worse than an
     * absent one the arithmetic knows to ignore.
     */
    val outputLatencyMs: Long
        get() {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0
            val frames = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: return 0
            val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: return 0
            if (frames <= 0 || rate <= 0) return 0
            return (2L * frames * 1000L) / rate
        }

    private companion object {
        const val TAG = "TogetherPlayer"
    }
}
