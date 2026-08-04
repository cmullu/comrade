package mullu.comrade.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
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
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mullu.comrade.ComradeCore
import mullu.comrade.R

/**
 * Paced breathing — offered before a focus session, and from the feed's gentle
 * stop (`docs/ATTENTION.md` phase 2).
 *
 * Four counts in, four held, four out, **two** to settle, repeated. Deliberately
 * the least ambitious feature in the pillar: it makes no claim, asks for no
 * runtime permission (`VIBRATE` is a normal one, granted at install), and cannot
 * fail. Nothing about having used it is stored — there is no count of breathing
 * minutes to accumulate, because a number would turn a pause into a task.
 *
 * It is for **anxiety, panic and stress as much as for practice** (owner, stating
 * the purpose). That is why the copy reaffirms rather than instructs, and why it
 * is careful about what it will not say — see the comment above `breathe_lines_in`
 * in `strings.xml`, which is where the limit belongs because that is where the
 * next person edits the words.
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
 * How long the two pieces of text take to change, and how many rows the calming
 * line reserves.
 *
 * Both fades are deliberately slower than a UI transition would normally be. The
 * shortest phase is two seconds, so there is room, and the point of this screen
 * is that nothing on it arrives abruptly — a 150ms swap would be correct
 * elsewhere in the app and wrong here.
 *
 * [LINE_ROWS] is a layout fix rather than a typographic one: see the comment at
 * the call site. Two, because that is what the longest of the shipped lines wraps
 * to at `titleMedium` on a phone.
 */
private const val LINE_FADE_MS = 900
private const val LABEL_FADE_MS = 450
private const val LINE_ROWS = 2

/**
 * How long a sit can be set to, in minutes, and which is offered first.
 *
 * One minute is the default because the screen is reached for *mid-something* —
 * from the feed's gentle stop, or on the way into a focus session — and the
 * shortest useful pause is the one a person will actually take. The longer
 * options exist because someone who came here deliberately should not have to
 * keep re-opening it.
 *
 * These are what the chips *say*; [breathingRunSeconds] is what actually runs,
 * because a minute is not a whole number of breaths.
 */
private val DURATION_MINUTES = listOf(1, 2, 3, 5)
private const val DEFAULT_MINUTES = 1

/**
 * One phase of the cycle, and how long it lasts.
 *
 * Typed rather than an index into a `% n`, because the arithmetic is exactly
 * where the last bug was: this cycle ran `% 3` for its first two releases — in,
 * hold, out, and then straight back into the next inhale with no pause at all —
 * for the whole time both this file and `docs/ATTENTION.md` called it *box*
 * breathing. Three sides is a triangle, and it felt like one: reported from a
 * handset as "we immediately breathe out and breathe in immediately… it feels
 * forced".
 *
 * ## Why the last phase is two counts and not four
 *
 * The fix for that made it a true 4-4-4-4 box, which is what the copy had been
 * claiming — and then the obvious next question was whether four is actually
 * right, because a hold *after an exhale* is not the same thing as a hold after
 * an inhale even though the box treats them as equals:
 *
 * * **It starts from a smaller reservoir.** Breath-hold tolerance is
 *   substantially shorter at functional residual capacity than at total lung
 *   capacity: less stored gas, so arterial CO₂ reaches the breaking point sooner
 *   (Frontiers in Physiology, *Large Lung Volumes Delay the Onset of the
 *   Physiological Breaking Point*).
 * * **The brake is missing.** Hering–Breuer stretch-receptor inhibition of
 *   inspiratory drive is strongest immediately after an inhale and decays from
 *   there; after an exhale there is none. So the urge to breathe arrives earliest
 *   in precisely this phase.
 * * **The people reaching for this screen are the worst case.** Anxiety and panic
 *   are associated with heightened CO₂ sensitivity and the *shortest* voluntary
 *   breath-hold times. An end-expiratory hold long enough to produce air hunger
 *   is the one thing on this screen that could make a bad minute worse.
 * * **Slower is not automatically calmer.** The vagal/HRV effects of paced
 *   breathing peak around 5.5–6 breaths/min; 4-4-4-4 is 3.75/min, below that
 *   band, and a head-to-head trial found box breathing produced *higher* heart
 *   rate and *higher* perceived exertion than 6/min (PLOS One, 2025). "Higher
 *   perceived exertion" is the measured version of "it feels forced". At 14
 *   seconds this cycle is ~4.3/min — toward the evidenced band rather than away.
 *
 * Shortening the empty hold is also the standard practitioner variation (4-2-4-2
 * is named as such, and beginner guidance is to shrink the holds first), and the
 * one four-arm RCT running box breathing individualises its timing off a CO₂
 * tolerance test rather than fixing it at four. So: four, four, four, two.
 *
 * That makes the sides unequal, which means **this is no longer a box**, and
 * nothing in the code, the copy or the docs may say otherwise. Losing the tidy
 * name is the cost of not making the hardest phase the longest one.
 */
internal enum class BreathPhase(val seconds: Int) {
    IN(4),
    HOLD_FULL(4),
    OUT(4),
    HOLD_EMPTY(2),
}

/** One full in–hold–out–settle cycle. Derived, never written down twice. */
internal val CYCLE_SECONDS: Int = BreathPhase.entries.sumOf { it.seconds }

@Composable
fun BreathingScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    var elapsed by remember { mutableIntStateOf(0) }
    var minutes by remember { mutableIntStateOf(DEFAULT_MINUTES) }
    val context = LocalContext.current
    val totalSeconds = breathingRunSeconds(minutes)
    val complete = elapsed >= totalSeconds

    // Stops when the chosen length is up. It used to count on for ever — the sit
    // being "done" changed the button's word and nothing else — which read on a
    // handset as the screen ignoring the one minute it had just been asked for
    // ("it doesn't seem to be stopping after 1 min"). A duration the screen
    // offers is a promise to stop, and asking someone mid-panic to decide when
    // to leave is precisely the decision they came here to be relieved of.
    //
    // Keyed on `complete` rather than `Unit` so it is the *state* that owns the
    // ticker: reaching the end cancels this effect, and choosing a longer chip
    // afterwards restarts it from wherever `elapsed` got to, which is what makes
    // "give me two more minutes" work on a finished sit without a restart.
    LaunchedEffect(complete) {
        if (complete) return@LaunchedEffect
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

    val phase = breathingPhase(elapsed)
    // A finished sit stops on a cycle boundary, which is the start of an inhale —
    // so without this the circle would go still under the word "Breathe in" and
    // hold there, telling someone to draw a breath it had no intention of
    // finishing. Rest is a state this screen never used to be able to reach.
    val label = if (complete) {
        R.string.breathe_rest
    } else when (phase) {
        BreathPhase.IN -> R.string.breathe_in
        BreathPhase.OUT -> R.string.breathe_out
        // The full hold says "Hold" and the empty one says "Settle". They were
        // one word while both were four counts; now that one is shorter, calling
        // them the same thing would have the screen say "Hold" for four seconds
        // and then "Hold" for two with no way to tell which you were in.
        BreathPhase.HOLD_FULL -> R.string.breathe_hold
        BreathPhase.HOLD_EMPTY -> R.string.breathe_settle
    }

    // One cue per phase change, and the first fires on entry — which is right:
    // the screen opens on an in-breath. The ramp is handed *this phase's* length
    // rather than a shared constant, so it spans exactly the seconds the circle
    // does; buzz and circle are two renderings of one breath, and following
    // either is following both. Only IN and OUT actually ramp, and both are four
    // counts — but reading the length off the phase is what keeps that true if
    // the numbers ever move again.
    //
    // Both holds are silent. A buzz through a hold makes a pause feel like
    // something to do, and the two are already told apart by what follows:
    // the out-breath opens at full amplitude, the in-breath at a whisper.
    //
    // A finished sit cancels rather than falling silent on its own: the last
    // buzz was handed a four-second ramp, so leaving it to expire would keep the
    // phone going in someone's hand for up to four seconds after the screen said
    // the pause was over.
    LaunchedEffect(phase, complete) {
        if (complete) {
            BreathHaptics.cancel(context)
            return@LaunchedEffect
        }
        BreathHaptics.play(
            context,
            when (phase) {
                BreathPhase.IN -> BreathHaptics.Phase.IN
                BreathPhase.OUT -> BreathHaptics.Phase.OUT
                BreathPhase.HOLD_FULL, BreathPhase.HOLD_EMPTY -> BreathHaptics.Phase.HOLD
            },
            phase.seconds * 1_000L,
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
    LaunchedEffect(phase, complete) {
        if (complete) {
            // Already at MIN_SCALE — the sit ends on a cycle boundary, so the
            // out-breath and the settle have both finished. Held explicitly
            // anyway, so that a future change to where a sit can end cannot
            // leave the circle stopped half-inflated.
            scale.snapTo(MIN_SCALE)
            return@LaunchedEffect
        }
        when (phase) {
            BreathPhase.IN -> scale.animateTo(MAX_SCALE, breathTween(phase))
            BreathPhase.OUT -> scale.animateTo(MIN_SCALE, breathTween(phase))
            // A hold is *held*. Snapping absorbs whatever fraction the moving
            // phase had left when the second counter rolled over; easing it
            // instead would spend the whole hold creeping the last half-percent,
            // in the two phases that are meant to be still.
            BreathPhase.HOLD_FULL -> scale.snapTo(MAX_SCALE)
            BreathPhase.HOLD_EMPTY -> scale.snapTo(MIN_SCALE)
        }
    }

    // One line to draw on going in, its partner to put down coming out. The
    // pair is chosen per cycle and the half is chosen by the phase, so the text
    // turns over exactly twice a breath — at the top of the inhale and at the
    // start of the exhale — rather than sitting there through both.
    //
    // `minOf` rather than trusting either length: the two arrays are paired by
    // index, and a translation that dropped one item from one of them would
    // otherwise show an inhale line with someone else's exhale line, or index
    // past the end. `getOrNull` for the same reason one layer down — the pause
    // screen is the last place in the app that should be able to crash.
    val inLines = stringArrayResource(R.array.breathe_lines_in)
    val outLines = stringArrayResource(R.array.breathe_lines_out)
    val pair = breathingPairIndex(elapsed, minOf(inLines.size, outLines.size))
    val line = when {
        // Neither half of a pair fits a sit that is over: both are written to be
        // read *during* a breath, and the inhale line — which is what a boundary
        // stop would otherwise land on — asks for one.
        complete -> stringResource(R.string.breathe_complete_line)
        showsInhaleLine(phase) -> inLines.getOrNull(pair).orEmpty()
        else -> outLines.getOrNull(pair).orEmpty()
    }

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
        // Where the screen used to repeat its own app-bar title. Crossfaded
        // because it now turns over on every inhale and every exhale — twice as
        // often as before — and a hard cut at that rate would be a flicker in
        // the corner of someone's eye rather than a line arriving.
        Crossfade(
            targetState = line,
            animationSpec = tween(durationMillis = LINE_FADE_MS, easing = BREATH_EASING),
            label = "breathe-line",
        ) { current ->
            Text(
                current,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                // The lines are not all the same length, so some wrap to two
                // rows and some do not — and the column is centred, so a line
                // arriving one row shorter than the last used to pull the circle,
                // the progress bar and the chips up by a row and drop them back
                // on the next change. That reflow is what read as the message
                // switch "not being quite smooth": the fade itself was fine, the
                // 200dp circle beside it was hopping. Reserving two rows costs a
                // row of blank space under the short lines and makes the layout
                // hold still.
                minLines = LINE_ROWS,
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
            // Crossfaded, where it used to hard-cut. This word changes four
            // times a cycle and sits dead centre of the one thing the reader is
            // looking at, so a swap with no transition is the most visible edge
            // on the screen — and it lands at exactly the moment the circle is
            // meant to be gliding to a stop. Faster than the calming line's fade
            // because it is two words rather than a sentence: long enough not to
            // cut, short enough that "Breathe out" is fully legible well before
            // the out-breath is under way.
            Crossfade(
                targetState = label,
                animationSpec = tween(durationMillis = LABEL_FADE_MS, easing = BREATH_EASING),
                label = "breathe-phase",
            ) { current ->
                Text(
                    stringResource(current),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
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
                    // that minute again". On a sit that has already stopped, a
                    // longer chip is how you carry on: it puts the total back
                    // above `elapsed`, which restarts the ticker where it left
                    // off. A shorter one ends the sit now.
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
 * The circle's motion, read straight off [BreathHaptics.curve] — the same
 * function that shapes the buzz, so the two cannot drift apart.
 *
 * This was `LinearEasing` for several releases, on the reasoning that the haptic
 * swelled at a constant rate and a circle that "raced ahead and coasted would be
 * pacing a different breath from the one in your hand". The premise was right and
 * the conclusion was wrong: the answer to keeping them together is to give them
 * one curve, not to make both of them straight. A constant rate meant the circle
 * arrived at empty still travelling at full speed and then stopped dead for the
 * settle, which is what "the transition from breath out and settle is not
 * natural" describes. A breath has no sharp edge where it turns around.
 */
private val BREATH_EASING = Easing { fraction -> BreathHaptics.curve(fraction) }

private fun breathTween(phase: BreathPhase) =
    tween<Float>(durationMillis = phase.seconds * 1_000, easing = BREATH_EASING)

/**
 * Which phase [elapsedSeconds] falls in.
 *
 * Walks the cumulative boundaries rather than dividing, because the phases are
 * no longer the same length — a single `/ phaseSeconds` is what the equal-sided
 * version could get away with, and quietly assuming it again is how the last two
 * bugs here happened. The ordering comes from [BreathPhase]'s declaration order,
 * so an inhale can never follow an exhale without the settle in between.
 */
internal fun breathingPhase(elapsedSeconds: Int): BreathPhase {
    val into = elapsedSeconds.coerceAtLeast(0) % CYCLE_SECONDS
    var boundary = 0
    for (phase in BreathPhase.entries) {
        boundary += phase.seconds
        if (into < boundary) return phase
    }
    // Unreachable: `into` is bounded by the sum of exactly these durations.
    return BreathPhase.entries.first()
}

/**
 * Whether the **inhale** half of the current pair is the one to show.
 *
 * A line holds through the pause that follows its own phase: the inhale line
 * appears at the top of the breath and stays for the hold-on-full, then the
 * exhale line takes over as the out-breath starts and stays for the settle. So
 * the text turns over exactly twice a cycle, on the two phases that are actually
 * asking something of the reader, and never mid-pause.
 */
internal fun showsInhaleLine(phase: BreathPhase): Boolean = when (phase) {
    BreathPhase.IN, BreathPhase.HOLD_FULL -> true
    BreathPhase.OUT, BreathPhase.HOLD_EMPTY -> false
}

/**
 * Which **pair** of calming lines is current at [elapsedSeconds], given
 * [pairCount] of them.
 *
 * One pair per cycle, advancing on the inhale, so a pair is never split across a
 * breath — the line someone reads on the way out is the partner of the one they
 * read on the way in, not the next pair's.
 *
 * The set repeating on a long sit is fine, and deliberately so: for someone
 * waiting out a bad few minutes, a line they have already read is closer to a
 * mantra than to the screen running out of things to say. That is the opposite
 * of the call made when this was framed purely as attention practice.
 *
 * Pure, and tested, because the failure it guards is an index out of bounds on
 * a screen someone reached for while having a bad minute.
 */
internal fun breathingPairIndex(elapsedSeconds: Int, pairCount: Int): Int {
    if (pairCount <= 0 || CYCLE_SECONDS <= 0) return 0
    return (elapsedSeconds.coerceAtLeast(0) / CYCLE_SECONDS) % pairCount
}

/**
 * How long a sit of [minutes] actually runs, in seconds: the nearest whole
 * number of cycles, and never fewer than one.
 *
 * A minute is not a whole number of breaths — the cycle is [CYCLE_SECONDS], so
 * sixty seconds is four and a bit. Now that the screen genuinely stops, it has
 * to stop *somewhere*, and stopping on the raw count would cut a breath in half:
 * a one-minute sit would go still four seconds into its fifth inhale, with the
 * circle part-grown and the haptic mid-ramp. Ending on a boundary means the last
 * thing that happens is an out-breath and a settle, which is the right note to
 * leave someone on and also the reason the completed state can assume the circle
 * is already at [MIN_SCALE].
 *
 * Rounds to nearest rather than up, so the chips stay honest in both directions
 * — 1 min runs 56s and 2 min runs 126s, never more than half a cycle from the
 * word on the chip. Up would have made every chip a lie in the same direction,
 * and for the person who picked one minute *because* they only had one minute,
 * overshooting is the worse half of the trade.
 */
internal fun breathingRunSeconds(minutes: Int): Int {
    if (minutes <= 0 || CYCLE_SECONDS <= 0) return CYCLE_SECONDS.coerceAtLeast(1)
    val cycles = ((minutes * 60.0) / CYCLE_SECONDS).roundToInt().coerceAtLeast(1)
    return cycles * CYCLE_SECONDS
}

/**
 * How far through the chosen length, as 0..1.
 *
 * Still clamped at both ends. The counter stops at the total now, so it no
 * longer walks past 1 on its own — but shortening the duration mid-sit puts
 * elapsed above the new total in one step, and a progress bar handed 3.4 is a
 * crash on some Compose versions and a wrong drawing on the rest.
 */
internal fun breathingProgress(elapsedSeconds: Int, totalSeconds: Int): Float {
    if (totalSeconds <= 0) return 1f
    return (elapsedSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f)
}
