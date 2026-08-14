package mullu.comrade.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mullu.comrade.ComradeCore
import mullu.comrade.R
import mullu.comrade.attention.StretchGuide

/**
 * A stretch break — the body attached to the attention.
 *
 * The neighbour of `BreathingScreen`, and built on the same three decisions:
 * **it makes no claim** (movement that feels better than not moving, not
 * treatment for anything), **it cannot fail**, and **nothing about having taken
 * one is stored** — there is no count of stretch breaks, because a number would
 * turn a break into a task (`docs/ATTENTION.md` gate 3).
 *
 * What is choreography and what is screen:
 *
 * * `comrade_core::stretch` owns the movements, their order, their durations, and
 *   the walk over them ([ComradeCore.stretchStepAtTyped], asked once a second).
 *   A test in the engine pins that both sides of every one-sided movement get
 *   equal time — the single easiest thing to get wrong by editing a table.
 * * `strings.xml` owns the words, keyed by the engine's step keys, so they can be
 *   translated.
 * * [StretchGuide] owns the figure's pose, in a file with no Android imports so
 *   the JVM lane can run it — and so it can be compared line-for-line with
 *   `desktop/ui/stretch_view.mjs`, which holds the same maths for the same figure.
 *
 * What is left here is the drawing.
 */
@Composable
fun StretchScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** Pre-selected when arriving from a session's break offer. */
    initialRoutine: String? = null,
) {
    var routines by remember { mutableStateOf<List<ComradeCore.StretchRoutineInfo>>(emptyList()) }
    var chosen by remember { mutableStateOf(initialRoutine) }
    var elapsed by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var cursor by remember { mutableStateOf<ComradeCore.StretchCursorInfo?>(null) }
    var complete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) {
            runCatching { ComradeCore.stretchRoutines() }.getOrDefault(emptyList())
        }
        routines = list
        if (chosen == null) {
            // The engine's own rotation, so the second break of a day is not the
            // first one again. Its suggestion needs the vault; the routines do
            // not, so a locked vault still draws the chips.
            chosen = withContext(Dispatchers.IO) {
                runCatching { ComradeCore.suggestedStretchRoutineTyped() }.getOrNull()
            } ?: list.firstOrNull()?.key
        }
    }

    // The ticker. One second at a time, asking the engine what that second means
    // — the same division the focus countdown draws, and for the same reason: a
    // timeline stepped locally is a second copy of the choreography.
    LaunchedEffect(running, chosen) {
        val key = chosen
        if (!running || key == null) return@LaunchedEffect
        while (true) {
            val current = withContext(Dispatchers.IO) {
                runCatching { ComradeCore.stretchStepAtTyped(key, elapsed) }.getOrNull()
            }
            if (current == null) {
                // Over. `null` rather than a clamp to the last step is what stops
                // the guide holding a hamstring reach for ever.
                running = false
                complete = true
                cursor = null
                break
            }
            cursor = current
            delay(1_000)
            elapsed += 1
        }
    }

    // Leaving mid-routine ends it. There is nothing to resume: no record of a
    // break is kept anywhere, which is the point.
    DisposableEffect(Unit) { onDispose { } }

    val routine = routines.firstOrNull { it.key == chosen }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val live = cursor
        if (live != null) {
            StretchFigure(
                pose = StretchGuide.pose(
                    part = live.step.part,
                    side = live.step.side,
                    motion = live.step.motion,
                    secsIntoStep = live.secsIntoStep,
                    stepSeconds = live.step.seconds,
                    stepKey = live.step.key,
                ),
            )
            Text(
                stringResource(stepInstruction(live.step.key)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                // Two rows reserved: the instructions are not all one line, and a
                // centred column that reflows on every step reads as jitter. The
                // breathing screen's calming line reserves rows for the same
                // reason, after the same complaint.
                minLines = INSTRUCTION_ROWS,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                live.secsLeftInStep.coerceAtLeast(0).toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                stringResource(
                    R.string.stretch_step_of,
                    live.index + 1,
                    routine?.steps?.size ?: 0,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            LinearProgressIndicator(
                progress = { live.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                trackColor = Color.Transparent,
            )
            OutlinedButton(
                onClick = {
                    running = false
                    cursor = null
                    elapsed = 0
                },
                modifier = Modifier.padding(top = 16.dp),
            ) { Text(stringResource(R.string.stretch_stop)) }
        } else {
            Text(
                stringResource(if (complete) R.string.stretch_complete else R.string.stretch_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            chosen?.let { key ->
                Text(
                    stringResource(routineBlurb(key)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                // One chip per routine the *engine* offers. No list here: a
                // hardcoded one is the second copy that drifted last time
                // (`focus_presets`, and the note in `FocusScreen`).
                routines.take(CHIPS_PER_ROW).forEach { r ->
                    FilterChip(
                        selected = r.key == chosen,
                        onClick = {
                            chosen = r.key
                            complete = false
                        },
                        label = { Text(stringResource(routineName(r.key))) },
                    )
                }
            }
            if (routines.size > CHIPS_PER_ROW) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    routines.drop(CHIPS_PER_ROW).forEach { r ->
                        FilterChip(
                            selected = r.key == chosen,
                            onClick = {
                                chosen = r.key
                                complete = false
                            },
                            label = { Text(stringResource(routineName(r.key))) },
                        )
                    }
                }
            }
            Button(
                onClick = {
                    elapsed = 0
                    complete = false
                    running = true
                },
                enabled = chosen != null,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(
                    stringResource(
                        R.string.stretch_start,
                        (routine?.totalSeconds ?: 0),
                    ),
                )
            }
            Text(
                stringResource(R.string.stretch_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            OutlinedButton(
                onClick = onDone,
                modifier = Modifier.padding(top = 16.dp),
            ) { Text(stringResource(if (complete) R.string.breathe_done else R.string.close)) }
        }
    }
}

/** How many chips fit across a phone before the row has to wrap. */
private const val CHIPS_PER_ROW = 3

/** Rows reserved for the instruction, so the column does not reflow per step. */
private const val INSTRUCTION_ROWS = 2

/**
 * The guide: a head, a spine, two shoulders, two arms.
 *
 * A diagram and not a person, deliberately — a figure detailed enough to have a
 * body type is a figure someone can fail to look like, on the screen that exists
 * to ask nothing of anybody. The pose is [StretchGuide.pose]'s, animated between
 * steps so the figure travels rather than teleports.
 */
@Composable
private fun StretchFigure(pose: StretchGuide.Pose) {
    val rotation by animateFloatAsState(
        targetValue = pose.rotation,
        animationSpec = tween(durationMillis = POSE_MS),
        label = "stretch-rotation",
    )
    val offsetX by animateFloatAsState(
        targetValue = pose.offsetX,
        animationSpec = tween(durationMillis = POSE_MS),
        label = "stretch-offset-x",
    )
    val offsetY by animateFloatAsState(
        targetValue = pose.offsetY,
        animationSpec = tween(durationMillis = POSE_MS),
        label = "stretch-offset-y",
    )
    val outline = MaterialTheme.colorScheme.primary
    val limb = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        Modifier
            .size(width = FIGURE_W.dp, height = FIGURE_H.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // The pivot is the base of the spine: a figure that leans from its
            // centre swings its hips out from under itself, which is not what any
            // of these movements looks like.
            val pivot = Offset(w / 2f, h * 0.86f)
            val dx = offsetX / 100f * w
            val dy = offsetY / 100f * h
            rotate(degrees = rotation, pivot = pivot) {
                val cx = w / 2f + dx
                val headY = h * 0.16f + dy
                val headR = w * 0.14f
                drawCircle(
                    color = outline,
                    radius = headR,
                    center = Offset(cx, headY),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.025f),
                )
                val neckTop = headY + headR
                val shoulderY = h * 0.42f + dy
                drawLine(
                    color = limb,
                    start = Offset(cx, neckTop),
                    end = Offset(cx, shoulderY),
                    strokeWidth = w * 0.035f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = limb,
                    start = Offset(cx - w * 0.24f, shoulderY),
                    end = Offset(cx + w * 0.24f, shoulderY),
                    strokeWidth = w * 0.035f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = limb,
                    start = Offset(cx, shoulderY),
                    end = Offset(w / 2f, pivot.y),
                    strokeWidth = w * 0.035f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = limb,
                    start = Offset(cx - w * 0.24f, shoulderY),
                    end = Offset(cx - w * 0.30f, h * 0.70f),
                    strokeWidth = w * 0.03f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = limb,
                    start = Offset(cx + w * 0.24f, shoulderY),
                    end = Offset(cx + w * 0.30f, h * 0.70f),
                    strokeWidth = w * 0.03f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private const val FIGURE_W = 160
private const val FIGURE_H = 190

/**
 * How long the figure takes to travel to a new pose.
 *
 * Slower than a UI transition and faster than a second: the pose is recomputed
 * once a second, so anything longer would still be moving when the next one
 * arrives, and anything much shorter would snap.
 */
private const val POSE_MS = 700

/** A routine's name. Unknown keys fall back to the generic title. */
internal fun routineName(key: String): Int = when (key) {
    "neck" -> R.string.stretch_routine_neck
    "shoulders" -> R.string.stretch_routine_shoulders
    "wrists" -> R.string.stretch_routine_wrists
    "back" -> R.string.stretch_routine_back
    "eyes" -> R.string.stretch_routine_eyes
    "stand" -> R.string.stretch_routine_stand
    else -> R.string.stretch_title
}

private fun routineBlurb(key: String): Int = when (key) {
    "neck" -> R.string.stretch_blurb_neck
    "shoulders" -> R.string.stretch_blurb_shoulders
    "wrists" -> R.string.stretch_blurb_wrists
    "back" -> R.string.stretch_blurb_back
    "eyes" -> R.string.stretch_blurb_eyes
    "stand" -> R.string.stretch_blurb_stand
    else -> R.string.stretch_note
}

/**
 * The instruction for one step, keyed exactly as `comrade_core::stretch` names it.
 *
 * An unknown key falls back to the routine's own note rather than showing the raw
 * key: `hamstring_reach_left` painted into the UI is worse than no instruction,
 * because the figure and the countdown still say what to do. A new step in the
 * engine should therefore be added here in the same change — and `strings.xml`
 * carries the note about that above `stretch_neck_release`.
 */
private fun stepInstruction(key: String): Int = when (key) {
    "neck_release" -> R.string.stretch_neck_release
    "neck_tilt_left" -> R.string.stretch_neck_tilt_left
    "neck_tilt_right" -> R.string.stretch_neck_tilt_right
    "neck_turn_left" -> R.string.stretch_neck_turn_left
    "neck_turn_right" -> R.string.stretch_neck_turn_right
    "chin_tuck" -> R.string.stretch_chin_tuck
    "shoulder_roll_back" -> R.string.stretch_shoulder_roll_back
    "shoulder_shrug" -> R.string.stretch_shoulder_shrug
    "shoulder_blade_squeeze" -> R.string.stretch_shoulder_blade_squeeze
    "cross_body_left" -> R.string.stretch_cross_body_left
    "cross_body_right" -> R.string.stretch_cross_body_right
    "wrist_circles" -> R.string.stretch_wrist_circles
    "wrist_extend_left" -> R.string.stretch_wrist_extend_left
    "wrist_extend_right" -> R.string.stretch_wrist_extend_right
    "finger_spread" -> R.string.stretch_finger_spread
    "hands_shake_out" -> R.string.stretch_hands_shake_out
    "seated_twist_left" -> R.string.stretch_seated_twist_left
    "seated_twist_right" -> R.string.stretch_seated_twist_right
    "cat_curl" -> R.string.stretch_cat_curl
    "side_reach_left" -> R.string.stretch_side_reach_left
    "side_reach_right" -> R.string.stretch_side_reach_right
    "eyes_far_focus" -> R.string.stretch_eyes_far_focus
    "eyes_slow_blink" -> R.string.stretch_eyes_slow_blink
    "eyes_palm_rest" -> R.string.stretch_eyes_palm_rest
    "stand_tall" -> R.string.stretch_stand_tall
    "calf_raises" -> R.string.stretch_calf_raises
    "hamstring_reach_left" -> R.string.stretch_hamstring_reach_left
    "hamstring_reach_right" -> R.string.stretch_hamstring_reach_right
    "hip_open_left" -> R.string.stretch_hip_open_left
    "hip_open_right" -> R.string.stretch_hip_open_right
    "shake_out" -> R.string.stretch_shake_out
    else -> R.string.stretch_note
}
