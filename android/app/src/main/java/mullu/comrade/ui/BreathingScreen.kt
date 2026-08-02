package mullu.comrade.ui

import androidx.compose.animation.core.animateFloatAsState
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
private const val TOTAL_SECONDS = 60

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
    // the screen opens on an in-breath.
    LaunchedEffect(phase) {
        BreathHaptics.play(
            context,
            when (phase) {
                0 -> BreathHaptics.Phase.IN
                1 -> BreathHaptics.Phase.HOLD
                else -> BreathHaptics.Phase.OUT
            },
        )
    }
    // Leaving mid-breath must not leave the phone buzzing in a pocket.
    DisposableEffect(Unit) { onDispose { BreathHaptics.cancel(context) } }
    // The circle grows on the in-breath, holds, then shrinks — something to
    // pace against without a number to watch.
    val target = when (phase) {
        0 -> 1f
        1 -> 1f
        else -> 0.55f
    }
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = PHASE_SECONDS * 1000),
        label = "breath",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("breathing"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.breathe_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Box(
            Modifier.padding(vertical = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size((200 * scale).dp)
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
