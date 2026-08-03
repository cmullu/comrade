package mullu.comrade.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mullu.comrade.ComradeCore
import mullu.comrade.R

/**
 * Sixty seconds of box breathing — offered before a focus session, and from the
 * feed's gentle stop (`docs/ATTENTION.md` phase 2).
 *
 * Four counts in, four held, four out, repeated. Deliberately the least
 * ambitious feature in the pillar: it makes no claim, asks for no runtime
 * permission (`VIBRATE` is a normal one, granted at install), and cannot fail.
 * Nothing about having used it is stored — there is no count of breathing
 * minutes to accumulate, because a number would turn a pause into a task.
 *
 * Two things it does that a person should know about, and which the screen says
 * in as many words rather than leaving to this comment:
 *
 * * **It vibrates**, rising through the in-breath and fading through the out —
 *   see [BreathHaptics], which exists so the pace can be followed with the
 *   screen off.
 * * **It tells your comrades, once, that you might need them** (owner request).
 *   The same one-bit envelope an abandoned draft sends (`comrade_core::nudge`),
 *   so nobody can tell which happened, and the same cooldown, so a hard
 *   half-hour is one notification rather than several. Only people the user
 *   deliberately chose are told, and nothing about *this screen* travels — the
 *   local "no record kept" promise above is unchanged.
 */
private const val PHASE_SECONDS = 4
private const val PHASE_MS = PHASE_SECONDS * 1_000L
private const val TOTAL_SECONDS = 60

/** Diameter of the circle at the top of an in-breath. */
private const val CIRCLE_DP = 200

/**
 * How small the circle gets at the end of an out-breath, and how big at the top
 * of an in-breath, as fractions of [CIRCLE_DP].
 *
 * The screen opens at [MIN_SCALE] and grows, which is the whole signal: an
 * inhale is the lungs filling, so a circle that is already full when the word
 * "breathe in" appears is telling you the opposite of what it means.
 */
private const val MIN_SCALE = 0.55f
private const val MAX_SCALE = 1f

@Composable
fun BreathingScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    var elapsed by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        while (elapsed < TOTAL_SECONDS) {
            delay(1_000)
            elapsed += 1
        }
    }

    // Reaching for a pause is the moment worth telling a comrade about, so it
    // fires on arrival rather than on finishing: someone who breathes for ten
    // seconds and leaves needed them just as much. Fire-and-forget on IO —
    // the count is not shown, and a relay that refuses is not this screen's
    // problem. Keyed on Unit so a recomposition cannot re-send.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { ComradeCore.nudgeComrades() } }
    }

    // in → hold → out, on a 12-second cycle.
    val phase = (elapsed / PHASE_SECONDS) % 3
    val label = when (phase) {
        0 -> R.string.breathe_in
        1 -> R.string.breathe_hold
        else -> R.string.breathe_out
    }

    // One cue per phase change, and the first fires on entry — which is right:
    // the screen opens on an in-breath. The ramp is handed the phase length so
    // it spans the same four seconds the circle does; buzz and circle are two
    // renderings of one breath, and following either is following both.
    LaunchedEffect(phase) {
        BreathHaptics.play(
            context,
            when (phase) {
                0 -> BreathHaptics.Phase.IN
                1 -> BreathHaptics.Phase.HOLD
                else -> BreathHaptics.Phase.OUT
            },
            PHASE_MS,
        )
    }
    // Leaving mid-breath must not leave the phone buzzing in a pocket.
    DisposableEffect(Unit) { onDispose { BreathHaptics.cancel(context) } }

    // The circle grows on the in-breath, holds, then shrinks — something to
    // pace against without a number to watch.
    //
    // An `Animatable` seeded at MIN_SCALE rather than `animateFloatAsState`,
    // because that helper starts *at* its first target: with the opening phase
    // targeting MAX_SCALE, the screen used to appear with the circle already
    // full and the word "Breathe in" under it, and the first thing it ever
    // animated was the shrink. There is no "from" to give it — the starting
    // value has to be state the screen owns.
    val scale = remember { Animatable(MIN_SCALE) }
    LaunchedEffect(phase) {
        when (phase) {
            // The hold is *held*. Snapping absorbs whatever fraction the
            // in-breath had left when the second counter rolled over; easing it
            // instead would spend the whole hold creeping the last half-percent,
            // in the one phase that is meant to be still.
            1 -> scale.snapTo(MAX_SCALE)
            else -> scale.animateTo(
                targetValue = if (phase == 2) MIN_SCALE else MAX_SCALE,
                // Linear, not the default ease: the haptic swells at a constant
                // rate, and a circle that raced ahead and coasted would be
                // pacing a different breath from the one in your hand.
                animationSpec = tween(durationMillis = PHASE_MS.toInt(), easing = LinearEasing),
            )
        }
    }

    val lines = stringArrayResource(R.array.breathe_lines)
    // `getOrNull` rather than `[]`: the count comes from a resource a
    // translation could ship empty, and the pause screen is the last place in
    // the app that should be able to crash.
    val line = lines.getOrNull(breathingLineIndex(elapsed, PHASE_SECONDS, lines.size)).orEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("breathing"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Where the screen used to repeat its own app-bar title. A line the
        // reader has already read is not worth the most prominent slot on a
        // screen whose whole job is to stop asking things of them.
        Crossfade(targetState = line, label = "breathe-line") { current ->
            Text(
                current,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("breathing-line"),
            )
        }
        Box(
            Modifier.padding(vertical = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size((CIRCLE_DP * scale.value).dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            )
            Text(
                stringResource(label),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            stringResource(R.string.breathe_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.padding(top = 24.dp),
        ) { Text(stringResource(if (elapsed >= TOTAL_SECONDS) R.string.breathe_done else R.string.close)) }
    }
}

/**
 * Which of the calming lines to show at [elapsedSeconds], given [lineCount] of
 * them and a phase of [phaseSeconds].
 *
 * Changes **once per full cycle** (in → hold → out), not once per phase. A line
 * that swapped every four seconds would be one more thing to keep up with, and
 * this is the one screen in the app with nothing to keep up with; twelve
 * seconds is long enough to read a sentence and then stop reading it. Index 0
 * is on screen from arrival, so the first thing a person sees is settled rather
 * than mid-change.
 *
 * Pure, and tested, because the failure it guards is an index out of bounds on
 * a screen someone reached for while having a bad minute.
 */
internal fun breathingLineIndex(elapsedSeconds: Int, phaseSeconds: Int, lineCount: Int): Int {
    if (lineCount <= 0) return 0
    val cycleSeconds = phaseSeconds * 3
    if (cycleSeconds <= 0) return 0
    return (elapsedSeconds.coerceAtLeast(0) / cycleSeconds) % lineCount
}
