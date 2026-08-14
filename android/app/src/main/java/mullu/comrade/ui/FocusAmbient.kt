package mullu.comrade.ui

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * The ambient background behind the Focus surfaces — soft light that moves too
 * slowly to watch.
 *
 * ## Why this exists at all
 *
 * The Focus tab was deliberately the plainest surface in the app, on the
 * reasoning that `docs/ATTENTION.md` gate 3 forbids celebrating a completion and
 * a plain screen cannot celebrate anything. The premise is right and the
 * conclusion was too strong: what gate 3 forbids is *reward mechanics* — a score,
 * a streak, a red state — and none of those is the same thing as the surface
 * being pleasant to sit in front of. Opera Air is the reference for the
 * difference: soft light, slow motion, translucency, and nothing to beat.
 *
 * ## Two adaptations, not a copy
 *
 * That browser is light and airy; this app is dark-mode-first, and flipping one
 * screen to white would strobe someone's eyes on every tab switch at night. So
 * the same idea is rendered in this app's own key — bloom rather than glare, the
 * theme's own accents at low alpha over the theme's own background.
 *
 * And the motion is **off by default for anyone who asked for that**. See
 * [reduceMotion]: this is the screen someone opens when they are already
 * scattered, and a background that ignores the system animation setting is not
 * calm, only pretty.
 *
 * ## Why gradients and not `Modifier.blur`
 *
 * `Modifier.blur` is a no-op below API 31, and this app's `minSdk` is 26 — so a
 * blurred-shape implementation would render as three hard-edged circles on a
 * third of the devices it ships to. A radial gradient with a long falloff is
 * already the shape a blurred circle has, at every API level.
 */
@Composable
fun FocusAmbient(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val still = reduceMotion()
    // Three drifts, deliberately long and mutually prime-ish so the blobs never
    // settle into a shared beat — a repeating pulse is the thing an eye catches,
    // and catching the background is the opposite of the point. The same three
    // periods are in `focus_view.mjs`'s `ambientBlobs`, so the two frontends
    // breathe at the same rate.
    val transition = rememberInfiniteTransition(label = "focus-ambient")
    val phaseOne = ambientPhase(still, transition, DRIFT_MS_ONE, "one")
    val phaseTwo = ambientPhase(still, transition, DRIFT_MS_TWO, "two")
    val phaseThree = ambientPhase(still, transition, DRIFT_MS_THREE, "three")

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val blobs = listOf(
                Triple(Offset(0.18f, 0.22f), 0.62f, scheme.primary),
                Triple(Offset(0.78f, 0.30f), 0.54f, scheme.tertiary),
                Triple(Offset(0.44f, 0.84f), 0.70f, scheme.secondary),
            )
            val phases = listOf(phaseOne, phaseTwo, phaseThree)
            blobs.forEachIndexed { index, (anchor, spread, colour) ->
                // Read in the draw scope on purpose: an animated value read here
                // re-runs the draw and nothing else, where reading it in the
                // composition would recompose this whole subtree sixty times a
                // second for a background nobody is looking at.
                val phase = phases[index].value
                // A small travel at this radius is a slow change in where the
                // light is, not a moving object.
                val drift = Offset(
                    x = cos(phase * Math.PI.toFloat()) * 0.06f,
                    y = sin(phase * Math.PI.toFloat()) * 0.05f,
                )
                val centre = Offset(
                    (anchor.x + drift.x) * size.width,
                    (anchor.y + drift.y) * size.height,
                )
                val radius = spread * maxOf(size.width, size.height) * (0.9f + 0.1f * phase)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            colour.copy(alpha = AMBIENT_ALPHA),
                            colour.copy(alpha = 0f),
                        ),
                        center = centre,
                        radius = radius,
                    ),
                    radius = radius,
                    center = centre,
                )
            }
        }
        content()
    }
}

/**
 * The three drift periods, in milliseconds.
 *
 * Long, and mutually prime-ish so the blobs never settle into a shared beat — a
 * repeating pulse is the thing an eye catches, and catching the background is the
 * opposite of the point. The same three numbers are in `focus_view.mjs`'s
 * `ambientBlobs`, so both frontends breathe at the same rate.
 */
private const val DRIFT_MS_ONE = 47_000
private const val DRIFT_MS_TWO = 61_000
private const val DRIFT_MS_THREE = 73_000

/**
 * One blob's drift phase, or a held `0f` when the device has asked for less
 * motion — a constant rather than a very slow animation, so nothing is
 * invalidating a frame at all.
 */
@Composable
private fun ambientPhase(
    still: Boolean,
    transition: InfiniteTransition,
    periodMs: Int,
    label: String,
): State<Float> {
    if (still) return remember { mutableFloatStateOf(0f) }
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "focus-ambient-$label",
    )
}

/**
 * How strongly the ambient light shows. Low enough that text over it needs no
 * scrim: the panels on top are already surfaces, and a background that forces
 * every card to become opaque to stay legible is a background that has cost the
 * translucency it was added for.
 */
private const val AMBIENT_ALPHA = 0.20f

/**
 * Whether this device has asked for less motion.
 *
 * `ANIMATOR_DURATION_SCALE` at zero is what "Remove animations" in
 * Accessibility sets, and it is the closest thing Android has to
 * `prefers-reduced-motion` — Compose has no first-class API for it at this
 * version. Read once per composition rather than observed: someone who changes
 * this setting mid-session gets the new answer on the next screen, and polling a
 * system setting on a calm screen to catch that would be a strange thing to do.
 *
 * Failing open (to *motion allowed*) would be the wrong default here, but so
 * would failing closed for everyone — so an unreadable setting reads as 1.0,
 * which is what the platform itself defaults to.
 */
@Composable
private fun reduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f) == 0f
    }
}
