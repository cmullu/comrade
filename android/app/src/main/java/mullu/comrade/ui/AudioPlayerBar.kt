package mullu.comrade.ui

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mullu.comrade.journal.clipProgress
import mullu.comrade.journal.playbackLabel

/**
 * One audio player, used everywhere this app plays a recording: journal voice
 * entries and chat voice notes alike.
 *
 * ## Why it is shared
 *
 * The two callers resolve their file completely differently — a journal entry
 * is a plain read out of app-private storage, a chat note is a decrypt of an
 * attachment — but everything *after* that point is identical, and the half
 * that was duplicated is the half users complained about. Chat's player was a
 * play button and the words "Voice message": no length, no progress, no way to
 * skip back over the sentence you missed. That is what [resolve] exists to
 * abstract away, so there is one player to improve rather than two to keep in
 * step.
 *
 * ## What it draws
 *
 * A play/pause control, a seek bar, and `elapsed / total` — the transport
 * controls the video viewer gets from the platform's own `MediaController`, in
 * the form audio needs. Deliberately **not** a waveform: drawing one means
 * decoding the whole clip to amplitudes on open, and the shape of a voice note
 * is not information anyone acts on. A slider is the control; a waveform would
 * be a picture of one.
 *
 * ## Durations
 *
 * [knownDurationMs] wins over [MediaPlayer.getDuration] when it is positive.
 * That is not a preference, it is the fix for a real case: an AAC/ADTS stream
 * (what chat notes are) carries no duration header, so the player reports −1 or
 * a bitrate guess, and the counter reads nonsense. The journal stores a real
 * duration alongside the entry and passes it here; chat has none to pass and
 * falls back to whatever the player will say, which for a stream that plays
 * start to finish is good enough.
 *
 * Nothing here is remembered across a scroll: a bar that leaves composition
 * releases its player, so a list of voice entries cannot end up holding a
 * decoder per row.
 */
@Composable
fun AudioPlayerBar(
    /** Resolves the file to play. Runs off the main thread; may be slow. */
    resolve: suspend () -> File?,
    modifier: Modifier = Modifier,
    /** A stable key: changing it tears down the player and starts over. */
    key: Any? = Unit,
    /** The length the caller already knows, or `0` to ask the player. */
    knownDurationMs: Long = 0L,
    /** Drawn to the left of the counter — a title, or "Voice message". */
    label: String? = null,
) {
    val scope = rememberCoroutineScope()
    var player by remember(key) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(key) { mutableStateOf(false) }
    var loading by remember(key) { mutableStateOf(false) }
    var failure by remember(key) { mutableStateOf<String?>(null) }
    var positionMs by remember(key) { mutableLongStateOf(0L) }
    // While a finger is on the slider its value is the finger's, not the
    // player's — otherwise the ticker below drags the thumb back under it.
    var scrubbingTo by remember(key) { mutableStateOf<Float?>(null) }
    var reportedDurationMs by remember(key) { mutableLongStateOf(0L) }

    val durationMs = if (knownDurationMs > 0L) knownDurationMs else reportedDurationMs

    // A player left running behind a scrolled-away row keeps the decoder and
    // the audio focus, and keeps making sound the user cannot see the source of.
    DisposableEffect(key) { onDispose { player?.release() } }

    // Advance the counter while playing. 200 ms is fast enough that the bar
    // moves smoothly and slow enough to be free; it stops when playback does,
    // so an idle screen does no work at all.
    LaunchedEffect(playing, player) {
        val active = player
        while (playing && active != null) {
            positionMs = runCatching { active.currentPosition.toLong() }.getOrDefault(positionMs)
            delay(200)
        }
    }

    fun toggle() {
        val existing = player
        if (existing != null) {
            if (playing) existing.pause() else existing.start()
            playing = !playing
            return
        }
        loading = true
        failure = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val file = resolve()
                        ?: throw IllegalStateException(
                            "This recording is no longer on this phone",
                        )
                    // The synchronous prepare() blocks, so the whole setup —
                    // not just resolving the file — has to run off Main.
                    MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        prepare()
                    }
                }
            }.onSuccess { prepared ->
                prepared.setOnCompletionListener {
                    // Rewind rather than sit at the end, so the same control
                    // that stopped it starts it again from the top.
                    playing = false
                    positionMs = 0L
                    runCatching { prepared.seekTo(0) }
                }
                reportedDurationMs = runCatching { prepared.duration.toLong() }.getOrDefault(0L)
                player = prepared
                prepared.start()
                playing = true
                loading = false
            }.onFailure {
                failure = it.message ?: "Could not play this recording"
                loading = false
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        FilledIconButton(
            onClick = { toggle() },
            enabled = !loading && failure == null,
            modifier = Modifier
                .semantics {
                    contentDescription = if (playing) "Pause" else "Play"
                }
                .testTag("audio-play"),
        ) {
            when {
                loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                playing -> Text("⏸", style = MaterialTheme.typography.titleMedium)
                else -> Text("▶", style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(Modifier.weight(1f)) {
            label?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            // The bar is there from the first frame, at zero, rather than
            // appearing once something has been decoded — a control that
            // materialises on tap reads as the app having been broken until
            // then. It only becomes draggable when there is a length to
            // drag against, since seeking a clip of unknown duration lands
            // somewhere neither the user nor the player can predict.
            Slider(
                value = scrubbingTo ?: clipProgress(positionMs, durationMs),
                onValueChange = { scrubbingTo = it },
                onValueChangeFinished = {
                    val target = scrubbingTo
                    scrubbingTo = null
                    val active = player
                    if (target != null && active != null && durationMs > 0L) {
                        val to = (target * durationMs).toLong()
                        positionMs = to
                        runCatching { active.seekTo(to.toInt()) }
                    }
                },
                enabled = player != null && durationMs > 0L,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("audio-scrubber"),
            )
            Text(
                failure ?: playbackLabel(
                    scrubbingTo?.let { (it * durationMs).toLong() } ?: positionMs,
                    durationMs,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (failure != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .padding(top = 2.dp)
                    .testTag("audio-counter"),
            )
        }
    }
}
