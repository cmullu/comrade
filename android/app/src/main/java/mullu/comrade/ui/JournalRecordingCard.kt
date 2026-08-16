package mullu.comrade.ui

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mullu.comrade.ComradeCore
import mullu.comrade.R
import mullu.comrade.journal.JOURNAL_TITLE_MAX_CHARS
import mullu.comrade.journal.JournalRecordingStore
import mullu.comrade.journal.RecordingKind
import mullu.comrade.journal.formatClipLength
import mullu.comrade.journal.formatClipSize
import mullu.comrade.journal.journalRecordingTitle
import mullu.comrade.journal.recordingKindOf

/**
 * What a journal entry's recording draws on its card.
 *
 * **The two kinds get different controls, and that is the point of the split.**
 * A video is watched, so it gets a poster that opens full screen — a 280 dp
 * inline video is a thumbnail, not a look at it. A voice entry has nothing to
 * look at, so a full-screen player would be a black rectangle with a slider on
 * it; it plays in place instead, with the transport controls right there on the
 * card ([AudioPlayerBar]). Giving audio the video treatment would have been
 * consistency at the cost of the thing actually being used.
 *
 * Neither decodes anything until it is asked to. For video that also means no
 * still frame is extracted for the poster: a `MediaMetadataRetriever` per card
 * on every scroll is the cost, and the first frame of a video someone recorded
 * about their worst day is exactly the image they would least like drawn,
 * unasked, on a list they scroll past in public.
 *
 * [available] is false when the file is missing, and then the card says so
 * instead of offering a control that opens nothing — see
 * [JournalRecordingStore.fileFor] for how a recording can go missing while its
 * entry survives.
 */
@Composable
fun JournalRecordingStrip(
    recording: ComradeCore.JournalRecordingInfo,
    available: Boolean,
    onPlayVideo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val kind = recordingKindOf(recording.mime)
    val detail = listOf(
        formatClipLength(recording.durationMs),
        formatClipSize(recording.sizeBytes),
    ).filter { it.isNotEmpty() }.joinToString(" · ")

    when {
        !available -> MissingRecording(modifier)
        kind == RecordingKind.Audio -> Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .testTag("journal-audio-strip"),
        ) {
            AudioPlayerBar(
                // Resolved on the tap, not on the scroll: a list of voice
                // entries must not touch the filesystem once per row per frame.
                resolve = {
                    withContext(Dispatchers.IO) {
                        JournalRecordingStore.fileFor(context, recording.mime, recording.fileName)
                    }
                },
                key = recording.fileName,
                // The stored length, not the player's: it was measured when the
                // entry was saved and is what the rest of the card already says.
                knownDurationMs = recording.durationMs,
            )
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 58.dp),
                )
            }
        }
        else -> Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onPlayVideo)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("journal-video-strip"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "▶",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.journal_video_play),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (detail.isNotEmpty()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The same strip when the file behind the entry has gone. */
@Composable
private fun MissingRecording(modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("journal-recording-missing"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "⚠",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            stringResource(R.string.journal_video_missing),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * A video entry, full screen, with the platform's own transport controls.
 *
 * Plays straight off the app-private file — no `FileProvider`, no content URI,
 * no copy into the cache — because this process can read its own storage and
 * every other route would put a second, exportable copy of the recording
 * somewhere. Same `VideoView` the chat viewer uses ([MediaViewerDialog]).
 *
 * Audio never comes here: a voice entry plays on its card ([AudioPlayerBar]).
 */
@Composable
fun JournalVideoPlayerDialog(
    fileName: String,
    mime: String,
    heading: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var file by remember(fileName) { mutableStateOf<File?>(null) }
    var missing by remember(fileName) { mutableStateOf(false) }

    LaunchedEffect(fileName) {
        val resolved = withContext(Dispatchers.IO) {
            runCatching { JournalRecordingStore.fileFor(context, mime, fileName) }.getOrNull()
        }
        file = resolved
        missing = resolved == null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("journal-video-player"),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val loaded = file
                when {
                    loaded != null -> {
                        var view by remember(fileName) { mutableStateOf<VideoView?>(null) }
                        // A VideoView left playing behind a dismissed dialog
                        // keeps the decoder and the audio focus — and keeps
                        // playing sound the user cannot see the source of.
                        DisposableEffect(fileName) { onDispose { view?.stopPlayback() } }
                        // Full screen rather than a fixed aspect box: a journal
                        // recording can be portrait or landscape depending on
                        // how the phone was held, and VideoView letterboxes
                        // inside whatever it is given. A 9:16 frame would put
                        // wide bars around every landscape clip.
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoPath(loaded.absolutePath)
                                    setMediaController(
                                        MediaController(ctx).also { it.setAnchorView(this) },
                                    )
                                    start()
                                }.also { view = it }
                            },
                        )
                    }
                    missing -> Text(
                        stringResource(R.string.journal_video_missing_body),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                    else -> CircularProgressIndicator(Modifier.size(32.dp))
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("journal-video-close"),
                ) {
                    Text("✕", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    heading,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

/**
 * Name a recording — shown once, right after one is made, and again from the
 * card's rename action.
 *
 * Saving with the box left empty is a first-class outcome, not a validation
 * error: an untitled recording is headed with the day it was taken
 * ([mullu.comrade.journal.journalRecordingHeading]), which is a real answer.
 * The dialog asks because a title is what makes a clip findable later, and then
 * gets out of the way.
 *
 * [onSave] receives the raw text; the caller normalises it with
 * [journalRecordingTitle], which is also what the character counter here counts
 * against — so the count and what is actually stored cannot disagree.
 */
@Composable
fun JournalRecordingTitleDialog(
    initial: String,
    saving: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    onDiscard: (() -> Unit)? = null,
) {
    // Keyed on [initial] so reopening this over a different entry starts from
    // that entry's title rather than from whatever was last typed here.
    var draft by remember(initial) { mutableStateOf(initial) }
    val normalised = journalRecordingTitle(draft)
    Dialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Column(
            Modifier
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(16.dp),
                )
                .padding(20.dp)
                .testTag("journal-recording-title-dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.journal_recording_title_prompt),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                enabled = !saving,
                placeholder = { Text(stringResource(R.string.journal_video_title_hint)) },
                supportingText = {
                    Text(
                        "${normalised?.length ?: 0}/$JOURNAL_TITLE_MAX_CHARS",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("journal-recording-title-input"),
            )
            Text(
                stringResource(R.string.journal_video_where),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDiscard != null) {
                    TextButton(onClick = onDiscard, enabled = !saving) {
                        Text(
                            stringResource(R.string.journal_video_discard),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                }
                Box(Modifier.weight(1f))
                TextButton(
                    onClick = { onSave(draft) },
                    enabled = !saving,
                    modifier = Modifier.testTag("journal-recording-title-save"),
                ) {
                    Text(
                        if (saving) {
                            stringResource(R.string.journal_video_saving)
                        } else {
                            stringResource(R.string.journal_video_keep)
                        },
                    )
                }
            }
        }
    }
}
