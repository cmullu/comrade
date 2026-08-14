package mullu.comrade.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mullu.comrade.ComradeCore
import mullu.comrade.R

/**
 * Focus sessions — the *practice* half of `docs/ATTENTION.md` phase 2.
 *
 * A deliberate single-task timer with a named intention. The whole point is
 * that attention is rebuilt by spending it, not by playing a game about
 * spending it, so this screen is almost aggressively plain: name one thing,
 * pick a length, and the app gets out of the way.
 *
 * Two honesty rules are visible in the UI itself:
 * - **Stopping early is not failure.** It records `abandoned` and says as much;
 *   there is no streak, no score, and no red state (gate 3).
 * - **A session nobody was present for cannot claim a completion.** The Rust
 *   side resolves those to `lapsed` regardless of what this screen asks for —
 *   see `ComradeRuntime::finish_focus_session`. The progressive-duration ladder
 *   reads that history, so letting it be flattered would make the practice it
 *   measures fictional.
 */
@Composable
fun FocusScreen(
    onOpenShelf: () -> Unit,
    onOpenBreathing: () -> Unit,
    onOpenStretch: (String?) -> Unit,
    onJournalNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var active by remember { mutableStateOf<ComradeCore.FocusSessionInfo?>(null) }
    var history by remember { mutableStateOf<List<ComradeCore.FocusSessionInfo>>(emptyList()) }
    var suggested by remember { mutableIntStateOf(25) }
    // Empty until the engine answers, deliberately: a placeholder row would be
    // this screen's own opinion about the ladder, and the ladder is the
    // engine's (`comrade_core::attention::FOCUS_PRESETS`).
    var presets by remember { mutableStateOf<List<Int>>(emptyList()) }
    var prompt by remember { mutableStateOf("") }
    var intent by remember { mutableStateOf("") }
    var chosenMinutes by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var reflection by remember { mutableStateOf<String?>(null) }
    var silenceWhileRunning by remember { mutableStateOf(false) }
    // Drives the countdown text; the authoritative remaining time comes from
    // the engine, this only re-reads it.
    var tick by remember { mutableLongStateOf(0L) }
    // A stretch break, offered on the half hour of a long session. `offeredMark`
    // is what stops the same offer reappearing every second — the engine decides
    // *whether* one is due (`stretch_break_due`), including that the last minutes
    // of a session are left alone, and this remembers what it has already said.
    var stretchOffer by remember { mutableStateOf<Int?>(null) }
    var offeredMark by remember { mutableStateOf<Int?>(null) }
    var offeredRoutine by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        withContext(Dispatchers.IO) {
            runCatching {
                active = ComradeCore.activeFocusSessionTyped()
                history = ComradeCore.focusSessions().filter { it.outcome != null }
                suggested = ComradeCore.suggestedFocusMinutesTyped()
                presets = ComradeCore.focusPresets()
                prompt = ComradeCore.focusPrompt()
            }.onFailure { error = it.message }
        }
        if (chosenMinutes == 0) chosenMinutes = suggested
    }
    LaunchedEffect(Unit) { reload() }

    // While a session runs, re-read once a second so the countdown moves and a
    // session that lapses while the screen is open resolves itself.
    LaunchedEffect(active?.id) {
        while (active != null) {
            delay(1_000)
            tick = System.currentTimeMillis()
            val current = withContext(Dispatchers.IO) {
                runCatching { ComradeCore.activeFocusSessionTyped() }.getOrNull()
            }
            if (current == null) {
                // It ended on its own (lapsed) — refresh the history so the
                // outcome is visible rather than the session just vanishing.
                reload()
                break
            }
            active = current
            // Whether a break is due is the engine's answer, not this screen's.
            // Nothing starts on its own: a routine that began by itself would be
            // an interruption rather than an offer, and the session keeps running
            // either way.
            val elapsedMinutes =
                ((current.plannedMinutes * 60 - current.remainingSecs).coerceAtLeast(0) / 60).toInt()
            val due = withContext(Dispatchers.IO) {
                runCatching {
                    ComradeCore.stretchBreakDueTyped(
                        current.plannedMinutes,
                        elapsedMinutes,
                        offeredMark,
                    )
                }.getOrNull()
            }
            if (due != null) {
                offeredMark = due
                stretchOffer = due
                offeredRoutine = withContext(Dispatchers.IO) {
                    runCatching { ComradeCore.suggestedStretchRoutineTyped() }.getOrNull()
                }
            }
        }
    }

    fun start() {
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    ComradeCore.startFocusSessionTyped(intent.trim(), chosenMinutes)
                }
            }.onSuccess {
                active = it
                intent = ""
                // A new session has its own marks; carrying the last one's over
                // would silence this session's first offer.
                offeredMark = null
                stretchOffer = null
                if (silenceWhileRunning) requestSilence(context)
            }.onFailure { error = it.message ?: "Could not start." }
        }
    }

    fun finish(completed: Boolean) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { ComradeCore.finishFocusSessionTyped(completed) }.getOrNull()
            }
            active = null
            if (silenceWhileRunning) releaseSilence(context)
            outcome?.outcome?.let { name ->
                reflection = withContext(Dispatchers.IO) {
                    runCatching { ComradeCore.focusReflectionTyped(name) }.getOrNull()
                }
            }
            reload()
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            val running = active
            ElevatedCard(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    // Translucent, so the ambient layer behind it shows through —
                    // see `FocusAmbient`. Not fully transparent: text over a
                    // moving gradient with no surface under it is the version of
                    // this that is pretty and unreadable.
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.focus_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (running == null) {
                        Text(
                            prompt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = intent,
                            onValueChange = { intent = it },
                            label = { Text(stringResource(R.string.focus_intent_label)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("focus-intent"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            presets.forEach { minutes ->
                                FilterChip(
                                    selected = chosenMinutes == minutes,
                                    onClick = { chosenMinutes = minutes },
                                    label = { Text("${minutes}m") },
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.focus_dnd_offer),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    stringResource(R.string.focus_dnd_explainer),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = silenceWhileRunning,
                                onCheckedChange = { want ->
                                    silenceWhileRunning = want
                                    if (want && !hasDndAccess(context)) {
                                        runCatching { context.startActivity(dndSettingsIntent()) }
                                    }
                                },
                            )
                        }
                        Button(
                            onClick = { start() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("focus-start"),
                        ) { Text(stringResource(R.string.focus_start, chosenMinutes)) }
                    } else {
                        // `tick` is read so the countdown recomposes each second.
                        @Suppress("UNUSED_EXPRESSION") tick
                        // The ring *empties* as the session runs. One that filled
                        // up would invite "how much have I earned", which is the
                        // scoring gate 3 refuses; a ring draining is only where
                        // the time went. Desktop draws the same thing from
                        // `focus_view.mjs`'s `ringGeometry`.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CountdownRing(
                                remainingSecs = running.remainingSecs,
                                plannedMinutes = running.plannedMinutes,
                            )
                            Text(
                                formatCountdown(running.remainingSecs),
                                style = MaterialTheme.typography.headlineMedium,
                            )
                        }
                        Text(
                            stringResource(R.string.focus_running, formatCountdown(running.remainingSecs)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (running.intent.isNotBlank()) {
                            Text(running.intent, style = MaterialTheme.typography.bodyLarge)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { finish(completed = true) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("focus-done"),
                            ) { Text(stringResource(R.string.focus_done)) }
                            OutlinedButton(
                                onClick = { finish(completed = false) },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.focus_stop)) }
                        }
                    }
                    error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        stringResource(R.string.focus_practice_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        stretchOffer?.let { mark ->
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.stretch_offer, mark),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    stretchOffer = null
                                    onOpenStretch(offeredRoutine)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.stretch_offer_accept)) }
                            OutlinedButton(
                                onClick = { stretchOffer = null },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.stretch_offer_later)) }
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onOpenShelf, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.library_title))
                }
                OutlinedButton(onClick = onOpenBreathing, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.breathe_title))
                }
                OutlinedButton(
                    onClick = { onOpenStretch(offeredRoutine) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.stretch_title))
                }
            }
        }

        item {
            Text(
                stringResource(R.string.focus_history_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (history.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.focus_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(history, key = { it.id }) { session ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(outcomeLabel(session.outcome)),
                            style = MaterialTheme.typography.labelMedium,
                            // Deliberately one colour for every outcome: an
                            // "abandoned" row in red would be the scold this
                            // pillar refuses to be.
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${session.plannedMinutes}m · ${relativeTime(session.startedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (session.intent.isNotBlank()) {
                        Text(
                            session.intent,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }

    // The close-out moment: the engine's own line, with the option to keep it.
    reflection?.let { line ->
        AlertDialog(
            onDismissRequest = { reflection = null },
            title = { Text(stringResource(R.string.focus_title)) },
            text = { Text(line) },
            confirmButton = {
                TextButton(
                    onClick = {
                        reflection = null
                        onJournalNote(line)
                    },
                ) { Text(stringResource(R.string.focus_save_note)) }
            },
            dismissButton = {
                TextButton(onClick = { reflection = null }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

/**
 * The session clock as a ring that empties.
 *
 * Fed the engine's own remaining seconds — the ring is a second *rendering* of
 * that number, never a second source for it. The total comes from the plan rather
 * than from the largest remaining value this screen has seen, so a session the
 * screen joined halfway through (app reopened, phone woken) draws a ring that is
 * already part-empty instead of one that starts full and lies about it.
 *
 * `animateFloatAsState` rather than a redraw per tick: the value arrives once a
 * second and the sweep has to cross the gap smoothly, which is the same reason
 * the desktop ring has a 700ms linear transition. Under "Remove animations" the
 * platform collapses the duration itself, so there is nothing extra to check
 * here — unlike the ambient background, whose motion is a loop the platform
 * cannot shorten (see `FocusAmbient.reduceMotion`).
 */
@Composable
private fun CountdownRing(remainingSecs: Long, plannedMinutes: Int) {
    val total = (plannedMinutes.coerceAtLeast(0) * 60).toFloat()
    val fraction = if (total <= 0f) 1f else (remainingSecs.toFloat() / total).coerceIn(0f, 1f)
    val swept by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = RING_MS),
        label = "focus-ring",
    )
    val track = MaterialTheme.colorScheme.surfaceVariant
    val arc = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(RING_DP.dp)) {
        val stroke = Stroke(width = RING_STROKE_DP.dp.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2f
        val box = Size(size.width - stroke.width, size.height - stroke.width)
        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = box,
            style = stroke,
        )
        drawArc(
            color = arc,
            // -90 so it drains from the top, which is where an eye starts.
            startAngle = -90f,
            sweepAngle = 360f * swept,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = box,
            style = stroke,
        )
    }
}

private const val RING_DP = 168
private const val RING_STROKE_DP = 6
private const val RING_MS = 700

private fun outcomeLabel(outcome: String?): Int = when (outcome) {
    "completed" -> R.string.focus_outcome_completed
    "abandoned" -> R.string.focus_outcome_abandoned
    else -> R.string.focus_outcome_lapsed
}

/** `24:05` — minutes and seconds, which is what a countdown needs. */
internal fun formatCountdown(remainingSecs: Long): String {
    val total = remainingSecs.coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * Whether the app may change Do Not Disturb. Like usage access this is a
 * special-access grant with no runtime dialog, so it is asked for only when the
 * user turns the switch on, and a refusal simply leaves the session unsilenced.
 */
private fun hasDndAccess(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java)
        ?.isNotificationPolicyAccessGranted == true

private fun dndSettingsIntent(): Intent =
    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * Silence interruptions for the session.
 *
 * `INTERRUPTION_FILTER_PRIORITY`, not `_NONE`: priority mode still lets calls
 * through, which is the same line drawn everywhere else in this pillar — a
 * focus session must never be the reason someone missed a call.
 */
private fun requestSilence(context: Context) {
    if (!hasDndAccess(context)) return
    runCatching {
        context.getSystemService(NotificationManager::class.java)
            ?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    }
}

private fun releaseSilence(context: Context) {
    if (!hasDndAccess(context)) return
    runCatching {
        context.getSystemService(NotificationManager::class.java)
            ?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }
}
