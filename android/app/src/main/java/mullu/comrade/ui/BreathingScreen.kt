package mullu.comrade.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * Box breathing — offered before a focus session, and from the feed's gentle
 * stop (`docs/ATTENTION.md` phase 2).
 *
 * Four counts in, four held, four out, four empty, repeated. Deliberately the
 * least ambitious feature in the pillar: it makes no claim, asks for no runtime
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

/**
 * How long a sit can be set to, in minutes, and which is offered first.
 *
 * One minute is the default because the screen is reached for *mid-something* —
 * from the feed's gentle stop, or on the way into a focus session — and the
 * shortest useful pause is the one a person will actually take. The longer
 * options exist because someone who came here deliberately should not have to
 * keep re-opening it.
 */
private val DURATION_MINUTES = listOf(1, 2, 3, 5)
private const val DEFAULT_MINUTES = 1

/**
 * One side of the box.
 *
 * Typed rather than an index into a `% n`, because the arithmetic is exactly
 * where the bug was: this cycle ran `% 3` for its first two releases — in, hold,
 * out, and then straight back into the next inhale with no pause on empty — for
 * the whole time both this file and `docs/ATTENTION.md` called it *box*
 * breathing. Three sides is a triangle, and it felt like one: reported from a
 * handset as "we immediately breathe out and breathe in immediately… it feels
 * forced". An enum cannot be one short without someone noticing.
 */
internal enum class BreathPhase { IN, HOLD_FULL, OUT, HOLD_EMPTY }

@Composable
fun BreathingScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    var elapsed by remember { mutableIntStateOf(0) }
    var minutes by remember { mutableIntStateOf(DEFAULT_MINUTES) }
    val context = LocalContext.current
    val totalSeconds = minutes * 60
    val complete = elapsed >= totalSeconds

    // Keeps counting past the chosen length rather than stopping: the sit being
    // "done" is a thing the button says, not a thing that freezes the circle
    // mid-breath. Someone who keeps going after the minute is up is doing
    // exactly what this screen is for.
    LaunchedEffect(Unit) {
        while (true) {
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

    val phase = breathingPhase(elapsed, PHASE_SECONDS)
    val label = when (phase) {
        BreathPhase.IN -> R.string.breathe_in
        BreathPhase.OUT -> R.string.breathe_out
        // Both holds say "Hold", which is what box breathing calls both. The
        // circle is full for one and small for the other, so the word does not
        // have to carry that difference — and a second word ("Rest"? "Empty"?)
        // would suggest a second thing to do.
        BreathPhase.HOLD_FULL, BreathPhase.HOLD_EMPTY -> R.string.breathe_hold
    }

    // One cue per phase change, and the first fires on entry — which is right:
    // the screen opens on an in-breath. The ramp is handed the phase length so
    // it spans the same four seconds the circle does; buzz and circle are two
    // renderings of one breath, and following either is following both.
    //
    // Both holds are silent. A buzz through a hold makes a pause feel like
    // something to do, and the two are already told apart by what follows:
    // the out-breath opens at full amplitude, the in-breath at a whisper.
    LaunchedEffect(phase) {
        BreathHaptics.play(
            context,
            when (phase) {
                BreathPhase.IN -> BreathHaptics.Phase.IN
                BreathPhase.OUT -> BreathHaptics.Phase.OUT
                BreathPhase.HOLD_FULL, BreathPhase.HOLD_EMPTY -> BreathHaptics.Phase.HOLD
            },
            PHASE_MS,
        )
    }
    // Leaving mid-breath must not leave the phone buzzing in a pocket.
    DisposableEffect(Unit) { onDispose { BreathHaptics.cancel(context) } }

    // The circle grows on the in-breath, holds full, shrinks, holds empty —
    // something to pace against without a number to watch.
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
            BreathPhase.IN -> scale.animateTo(MAX_SCALE, breathTween())
            BreathPhase.OUT -> scale.animateTo(MIN_SCALE, breathTween())
            // A hold is *held*. Snapping absorbs whatever fraction the moving
            // phase had left when the second counter rolled over; easing it
            // instead would spend the whole hold creeping the last half-percent,
            // in the two phases that are meant to be still.
            BreathPhase.HOLD_FULL -> scale.snapTo(MAX_SCALE)
            BreathPhase.HOLD_EMPTY -> scale.snapTo(MIN_SCALE)
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
            // Scrollable, and centred while it fits. The duration chips and the
            // progress line added ~50dp to a column that already held a 200dp
            // circle and a three-line note, and nothing in CI navigates to this
            // screen — the emulator lanes only launch the app — so a short
            // handset overflowing here would ship unseen.
            .verticalScroll(rememberScrollState())
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
            Modifier.padding(vertical = 28.dp),
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

        // How far through the chosen length, with no digits. A five-minute sit
        // with no feedback at all is a sit you cannot tell you are two minutes
        // into; a countdown would be a number to watch, which is the one thing
        // this screen has been avoiding since it shipped. So: a line that fills.
        LinearProgressIndicator(
            progress = { breathingProgress(elapsed, totalSeconds) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .testTag("breathing-progress"),
            trackColor = Color.Transparent,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DURATION_MINUTES.forEach { option ->
                FilterChip(
                    selected = option == minutes,
                    // Changing the length mid-sit keeps `elapsed`, so this
                    // extends or ends the sit in progress rather than
                    // restarting it — nobody asking for more time meant "start
                    // that minute again".
                    onClick = { minutes = option },
                    label = { Text(stringResource(R.string.breathe_minutes, option)) },
                )
            }
        }

        Text(
            stringResource(R.string.breathe_note),
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

/**
 * Linear, not the default ease: the haptic swells at a constant rate, and a
 * circle that raced ahead and coasted would be pacing a different breath from
 * the one in your hand.
 */
private fun breathTween() =
    tween<Float>(durationMillis = PHASE_MS.toInt(), easing = LinearEasing)

/**
 * Which side of the box [elapsedSeconds] falls on, given a phase of
 * [phaseSeconds].
 *
 * All four sides are equal length, which is what makes it a box; the ordering
 * comes from [BreathPhase]'s declaration order, so an inhale can never follow an
 * exhale without the empty hold in between.
 */
internal fun breathingPhase(elapsedSeconds: Int, phaseSeconds: Int): BreathPhase {
    if (phaseSeconds <= 0) return BreathPhase.IN
    val sides = BreathPhase.entries
    return sides[(elapsedSeconds.coerceAtLeast(0) / phaseSeconds) % sides.size]
}

/** How many full cycles one calming line stays on screen for. */
private const val CYCLES_PER_LINE = 2

/**
 * Which of the calming lines to show at [elapsedSeconds], given [lineCount] of
 * them and a phase of [phaseSeconds].
 *
 * Changes every [CYCLES_PER_LINE] **cycles**, not every phase. A line that
 * swapped every four seconds would be one more thing to keep up with, and this
 * is the one screen in the app with nothing to keep up with; half a minute is
 * long enough to read a sentence and then stop reading it. Index 0 is on screen
 * from arrival, so the first thing a person sees is settled rather than
 * mid-change.
 *
 * Pure, and tested, because the failure it guards is an index out of bounds on
 * a screen someone reached for while having a bad minute.
 */
internal fun breathingLineIndex(elapsedSeconds: Int, phaseSeconds: Int, lineCount: Int): Int {
    if (lineCount <= 0) return 0
    val lineSeconds = phaseSeconds * BreathPhase.entries.size * CYCLES_PER_LINE
    if (lineSeconds <= 0) return 0
    return (elapsedSeconds.coerceAtLeast(0) / lineSeconds) % lineCount
}

/**
 * How far through the chosen length, as 0..1.
 *
 * Clamped at both ends because [elapsedSeconds] deliberately keeps counting
 * after the sit completes, and because shortening the duration mid-sit can put
 * elapsed past the new total — a progress bar reporting 3.4 would be a crash on
 * some Compose versions and a wrong drawing on the rest.
 */
internal fun breathingProgress(elapsedSeconds: Int, totalSeconds: Int): Float {
    if (totalSeconds <= 0) return 1f
    return (elapsedSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f)
}
