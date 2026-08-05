package mullu.comrade.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import mullu.comrade.R
import mullu.comrade.together.LibraryResolver
import mullu.comrade.together.MediaLibraryAccess
import mullu.comrade.together.ShareTransfer
import mullu.comrade.together.TogetherManager

/**
 * The watch-together surface.
 *
 * Deliberately plain: this screen owns no sync logic at all. Every decision
 * about what the player does lives in `TogetherDecisions` (pure, unit-tested)
 * and `comrade_core::together` (shared with desktop), and the session outlives
 * this composition because [TogetherManager] and its foreground service own it.
 * Disposing this screen must not stop the film.
 *
 * The two rules in the copy are the ones `docs/PRESENCE.md` §5 sets and
 * `docs/TOGETHER.md` §7 repeats: it never says "synced" or "in sync", because we
 * do not know that; and when the heartbeats stop it says we lost track of
 * *them*, because that is what we observed.
 */
@Composable
fun TogetherScreen(
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by TogetherManager.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // An invitation is the one moment reading the library is obviously worth
    // something, so it is the one place besides `/play` that asks. Two separate
    // flags rather than one tri-state, because "refused" and "looked and it
    // isn't here" are different answers and only the second one has anything to
    // say — after a refusal the Join button already offers the route that needs
    // no permission. Hoisted out of the `when` so the launcher is created on
    // every composition rather than only while an invitation is showing.
    var libraryAsked by remember { mutableStateOf(false) }
    var libraryMissed by remember { mutableStateOf(false) }
    val askToReadLibrary = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        runCatching { MediaLibraryAccess.rememberAsked(context) }
        libraryAsked = true
        // A match starts the session, so this only ever reads true on the
        // "allowed to look, and it is genuinely not here" path.
        libraryMissed = granted && !TogetherManager.lookAgain(context)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val s = state) {
            is TogetherManager.UiState.Idle -> {
                Text(stringResource(R.string.together_title), style = MaterialTheme.typography.titleLarge)
                Button(onClick = onPickFile) { Text(stringResource(R.string.together_pick_file)) }
            }

            is TogetherManager.UiState.Invited -> {
                Text(
                    stringResource(R.string.together_invited, s.peerLabel, s.title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPickFile) { Text(stringResource(R.string.together_join)) }
                    // The case `together` otherwise assumes away: you do not
                    // have it. Their copy comes straight from their device —
                    // never through a server of ours.
                    TextButton(onClick = { TogetherManager.askForTheirCopy(context) }) {
                        Text(stringResource(R.string.together_ask_for_copy))
                    }
                    TextButton(onClick = { TogetherManager.leave() }) {
                        Text(stringResource(R.string.together_not_now))
                    }
                }
                // Only when a lookup could find anything: a YouTube invitation
                // names no recording, and a blank title means none was carried.
                val couldLook = !s.youtube && s.title.isNotBlank()
                // The same one-ask rule `/play` follows, and for the same
                // reason: someone who has already refused gets no dialog from
                // Android, so offering the button again would be offering a
                // button that does nothing. `libraryAsked` is the local half —
                // the preference is what persists, this is what recomposes.
                val step = MediaLibraryAccess.next(
                    granted = runCatching { LibraryResolver.mayRead(context) }
                        .getOrDefault(false),
                    askedBefore = libraryAsked ||
                        runCatching { MediaLibraryAccess.asked(context) }.getOrDefault(true),
                )
                if (couldLook && step == MediaLibraryAccess.Step.Ask) {
                    TextButton(onClick = {
                        askToReadLibrary.launch(
                            MediaLibraryAccess.permissionFor(Build.VERSION.SDK_INT),
                        )
                    }) {
                        Text(stringResource(R.string.together_look_in_library))
                    }
                }
                if (libraryMissed) {
                    Text(
                        stringResource(R.string.together_library_missed),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            is TogetherManager.UiState.Live -> LiveSession(s)
        }

        // Outside the `when` on purpose: the relay question can arrive while a
        // handover is running in any of these states, and it must not be
        // possible to leave it unanswered by whatever the session does next.
        ShareRelayConsent()
    }
}

/**
 * The one question this feature asks before spending someone else's bandwidth.
 *
 * Modal because it is genuinely blocking — the transfer sits still until it is
 * answered — and dismissible only into "no", since there is no third outcome
 * and a silently dropped question would be the stall this was built to fix.
 */
@Composable
private fun ShareRelayConsent() {
    val question by ShareTransfer.consentQuestion.collectAsState()
    val text = question ?: return
    AlertDialog(
        onDismissRequest = { ShareTransfer.refuseShareConsent() },
        title = { Text(stringResource(R.string.together_relay_title)) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { ShareTransfer.grantShareConsent() }) {
                Text(stringResource(R.string.together_relay_yes))
            }
        },
        dismissButton = {
            TextButton(onClick = { ShareTransfer.refuseShareConsent() }) {
                Text(stringResource(R.string.together_relay_no))
            }
        },
    )
}

@Composable
private fun LiveSession(s: TogetherManager.UiState.Live) {
    Text(s.title.ifBlank { s.peerLabel }, style = MaterialTheme.typography.titleLarge)
    Text(statusLabel(s), style = MaterialTheme.typography.bodyMedium)

    // While a finger is on the slider the poll must not move it — the decision
    // is TogetherDecisions.pollMayMoveSlider, and the manager honours it; this
    // only has to report the drag boundaries.
    var dragging by remember { mutableFloatStateOf(-1f) }
    val max = s.durationMs.coerceAtLeast(1L).toFloat()
    Slider(
        value = if (dragging >= 0f) dragging else s.positionMs.toFloat().coerceIn(0f, max),
        onValueChange = {
            if (dragging < 0f) TogetherManager.onScrubStart()
            dragging = it
        },
        onValueChangeFinished = {
            val target = dragging.toLong()
            dragging = -1f
            TogetherManager.onScrubRelease(target)
        },
        valueRange = 0f..max,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = { TogetherManager.setState(s.positionMs, !s.playing) }) {
            Text(if (s.playing) "Pause" else "Play")
        }
        TextButton(onClick = { TogetherManager.leave() }) {
            Text(stringResource(R.string.together_leave))
        }
    }

    // The honest limits, on screen rather than in a doc nobody reads.
    Text(stringResource(R.string.together_accuracy_note), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.together_background_note), style = MaterialTheme.typography.bodySmall)
}

/** The status vocabulary, mirroring `sessionStatusLabel` in the desktop module. */
@Composable
private fun statusLabel(s: TogetherManager.UiState.Live): String = when (s.status) {
    TogetherManager.Status.WaitingForThem -> stringResource(R.string.together_waiting_for_them)
    TogetherManager.Status.OpenYourCopy -> stringResource(R.string.together_open_your_copy)
    TogetherManager.Status.Together -> stringResource(R.string.together_together)
    TogetherManager.Status.CatchingUp -> stringResource(R.string.together_catching_up)
    TogetherManager.Status.LostTrack -> stringResource(R.string.together_lost_track)
    TogetherManager.Status.TheyPaused -> stringResource(R.string.together_they_paused, s.peerLabel)
}
