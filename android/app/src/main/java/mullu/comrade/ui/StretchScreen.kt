package mullu.comrade.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mullu.comrade.ComradeCore
import mullu.comrade.R
import mullu.comrade.attention.StretchPacing

/**
 * The stretch break — the body's half of the pause `BreathingScreen` gives
 * the head, and its stylistic sibling: a slow guided animation sets the pace,
 * the words stay reaffirming, the progress bar carries no digits, and nothing
 * about having taken a break is recorded anywhere.
 *
 * The routine (which stretches, how long, which have sides) is the engine's —
 * `comrade_core::attention::STRETCH_ROUTINE` over the FFI — so this screen
 * and the desktop player can never disagree about what a break is. The pacing
 * arithmetic is [StretchPacing], pure and JVM-tested; this file is the
 * rendering.
 */
@Composable
fun StretchScreen(
    modifier: Modifier = Modifier,
) {
    var routine by remember { mutableStateOf<List<StretchPacing.Step>>(emptyList()) }
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var elapsed by remember { mutableDoubleStateOf(0.0) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        routine = withContext(Dispatchers.IO) {
            runCatching {
                ComradeCore.stretchRoutine().map {
                    StretchPacing.Step(it.key, it.name, it.cue, it.seconds, it.mirrored)
                }
            }.getOrDefault(emptyList())
        }
    }
    val segments = remember(routine) { StretchPacing.segments(routine) }

    // The local wall clock paces the break. Unlike the focus countdown there
    // is no engine state a re-read would keep honest — nothing persists and
    // nothing lapses — so a device that sleeps mid-break simply ends it.
    LaunchedEffect(startedAt) {
        val started = startedAt ?: return@LaunchedEffect
        while (true) {
            elapsed = (System.currentTimeMillis() - started) / 1000.0
            val moment = StretchPacing.at(segments, elapsed)
            if (moment == null || moment.done) {
                // Ran its course: back to rest with a closing line. (Ending
                // early via the button clears `startedAt` without one —
                // leaving is not an outcome here.)
                finished = moment != null
                startedAt = null
                break
            }
            delay(100)
        }
    }

    val running = startedAt != null
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        if (!running) {
            Text(
                stringResource(R.string.stretch_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (finished) {
                Text(
                    stringResource(R.string.stretch_done),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            Button(
                onClick = {
                    finished = false
                    startedAt = System.currentTimeMillis()
                },
                // The routine crosses a bridge; if it failed to arrive there
                // is no player, not an empty one.
                enabled = segments.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stretch-start"),
            ) { Text(stringResource(R.string.stretch_start)) }
        } else {
            val moment = StretchPacing.at(segments, elapsed)
            if (moment != null) {
                StretchFigure(
                    key = moment.segment.key,
                    mirroredRight = moment.segment.side == StretchPacing.Side.RIGHT,
                )
                Text(
                    moment.segment.name,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    when (moment.segment.side) {
                        StretchPacing.Side.LEFT -> stringResource(R.string.stretch_side_left)
                        StretchPacing.Side.RIGHT -> stringResource(R.string.stretch_side_right)
                        null -> ""
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    moment.segment.cue,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    // Cues wrap to one or two rows; reserving two keeps the
                    // figure from hopping on each change — the same layout
                    // lesson the breathing lines learned (`minLines`).
                    minLines = 2,
                    modifier = Modifier.testTag("stretch-cue"),
                )
                // No digits, same reason the breathing bar has none: a
                // stretch with a countdown on it becomes a race.
                LinearProgressIndicator(
                    progress = { StretchPacing.progress(segments, elapsed) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { startedAt = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.stretch_stop)) }
            }
        }
        Text(
            stringResource(R.string.stretch_title),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * The demonstrating figure: a simple seated bust whose head, shoulders and
 * torso move with the named stretch. The animation *is* the metronome — slow
 * ease-in-out, reversing — the way Opera Air's break exercises pace you with
 * motion rather than a count. A key this build does not know draws the figure
 * at rest rather than nothing: the cue text still teaches the stretch.
 */
@Composable
private fun StretchFigure(
    key: String,
    mirroredRight: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "stretch-figure")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "stretch-phase",
    )
    val line = MaterialTheme.colorScheme.primary
    val fill = line.copy(alpha = 0.18f)
    val halo = line.copy(alpha = 0.10f + 0.06f * phase)

    Canvas(modifier.size(210.dp)) {
        drawCircle(color = halo, radius = size.minDimension * (0.40f + 0.05f * phase))
        // Design space is 120×120 (shared with the desktop figure); mirror
        // for the right side of a two-sided stretch, then scale up.
        withTransform({
            if (mirroredRight) scale(-1f, 1f, pivot = center)
            scale(size.minDimension / 120f, size.minDimension / 120f, pivot = Offset.Zero)
        }) {
            drawFigure(key, phase, line, fill)
        }
    }
}

private fun DrawScope.drawFigure(key: String, p: Float, line: Color, fill: Color) {
    when (key) {
        "neck-tilt" -> {
            drawBody(line, fill)
            rotate(-22f * p, pivot = Offset(60f, 57f)) { drawHead(line, fill) }
        }
        "neck-turn" -> {
            drawBody(line, fill)
            withTransform({
                translate(left = -9f * p)
                scale(1f - 0.2f * p, 1f, pivot = Offset(60f, 34f))
            }) { drawHead(line, fill) }
        }
        "chin-tuck" -> {
            drawBody(line, fill)
            withTransform({
                translate(top = 5f * p)
                scale(1f - 0.06f * p, 1f - 0.06f * p, pivot = Offset(60f, 34f))
            }) { drawHead(line, fill) }
        }
        "shoulder-roll" -> {
            drawTorso(line, fill)
            translate(top = -6f * p) {
                drawShoulders(line)
                drawArms(line)
            }
            drawHead(line, fill)
        }
        "side-bend" -> rotate(-14f * p, pivot = Offset(60f, 106f)) {
            drawTorso(line, fill)
            drawShoulders(line)
            drawArmLeft(line)
            // The reaching arm swings overhead as the body leans away.
            rotate(-150f * p, pivot = Offset(78f, 62f)) { drawArmRight(line) }
            drawHead(line, fill)
        }
        "seated-twist" -> withTransform({
            scale(1f - 0.28f * p, 1f, pivot = Offset(60f, 84f))
            rotate(-6f * p, pivot = Offset(60f, 84f))
        }) {
            drawBody(line, fill)
            drawHead(line, fill)
        }
        else -> {
            drawBody(line, fill)
            drawHead(line, fill)
        }
    }
}

private val strokeWide = Stroke(width = 5f, cap = StrokeCap.Round)
private val strokeSlim = Stroke(width = 4f, cap = StrokeCap.Round)

private fun DrawScope.drawBody(line: Color, fill: Color) {
    drawTorso(line, fill)
    drawShoulders(line)
    drawArms(line)
}

private fun DrawScope.drawTorso(line: Color, fill: Color) {
    val torso = Path().apply {
        moveTo(46f, 64f)
        cubicTo(48f, 84f, 48f, 96f, 47f, 106f)
        lineTo(73f, 106f)
        cubicTo(72f, 96f, 72f, 84f, 74f, 64f)
        close()
    }
    drawPath(torso, color = fill, style = Fill)
    drawPath(torso, color = line, style = strokeSlim)
}

private fun DrawScope.drawShoulders(line: Color) {
    val shoulders = Path().apply {
        moveTo(40f, 62f)
        cubicTo(48f, 55f, 72f, 55f, 80f, 62f)
    }
    drawPath(shoulders, color = line, style = strokeWide)
}

private fun DrawScope.drawArms(line: Color) {
    drawArmLeft(line)
    drawArmRight(line)
}

private fun DrawScope.drawArmLeft(line: Color) {
    val arm = Path().apply {
        moveTo(42f, 62f)
        cubicTo(37f, 74f, 35f, 82f, 36f, 94f)
    }
    drawPath(arm, color = line, style = strokeWide)
}

private fun DrawScope.drawArmRight(line: Color) {
    val arm = Path().apply {
        moveTo(78f, 62f)
        cubicTo(83f, 74f, 85f, 82f, 84f, 94f)
    }
    drawPath(arm, color = line, style = strokeWide)
}

private fun DrawScope.drawHead(line: Color, fill: Color) {
    drawLine(
        color = line,
        start = Offset(60f, 46f),
        end = Offset(60f, 57f),
        strokeWidth = 5f,
        cap = StrokeCap.Round,
    )
    drawCircle(color = fill, radius = 13f, center = Offset(60f, 34f))
    drawCircle(color = line, radius = 13f, center = Offset(60f, 34f), style = strokeSlim)
}
