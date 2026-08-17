package mullu.comrade.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/*
 * Fallback brand palette, used when Material You dynamic color is unavailable
 * (below Android 12).
 *
 * These are the desktop shell's design tokens, one for one — the token layer in
 * `desktop/ui/styles.css` follows shadcn/ui's naming (`background`/`foreground`
 * pairs, `card`, `muted`, `accent`, `border`, `input`, `destructive`), and each
 * has an M3 role that means the same thing:
 *
 *   --background      → background            --border    → outlineVariant
 *   --card            → surface               --input     → outline
 *   --accent          → surfaceVariant        --destructive → error
 *   --muted-foreground → onSurfaceVariant     --primary   → primary
 *
 * Keeping the mapping written down is what stops the two drifting: the numbers
 * below are the *same numbers*, and when one side changes the other is a
 * search away.
 *
 * Radii deliberately do **not** mirror the desktop scale — see ComradeShapes.
 */

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF818CF8),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF3730A3),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF022C22),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF2A1B06),
    background = Color(0xFF0A0E1A),
    onBackground = Color(0xFFE6EBF5),
    surface = Color(0xFF131B2E),
    onSurface = Color(0xFFE6EBF5),
    // The hover/selected surface, lifted a step off `surface` so a pressed row
    // is actually visible — the same correction the desktop tokens took.
    surfaceVariant = Color(0xFF1F2A42),
    onSurfaceVariant = Color(0xFF9AA7C2),
    outline = Color(0xFF34425F),
    outlineVariant = Color(0xFF243049),
    error = Color(0xFFF87171),
    onError = Color(0xFF2A0708),
)

/*
 * The light appearance, complete rather than three colours deep. It used to
 * name only primary/secondary/tertiary and inherit M3's own surfaces, which
 * meant Comrade in light mode was a Material demo wearing an indigo button;
 * these are `body.theme-light`'s tokens.
 */
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF059669),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFB45309),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF0F1729),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F1729),
    surfaceVariant = Color(0xFFE7EBF5),
    onSurfaceVariant = Color(0xFF48536B),
    outline = Color(0xFFC3CBDF),
    outlineVariant = Color(0xFFDDE2EE),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
)

/**
 * Soft, generous corner radii — cards, dialogs and sheets read as one
 * rounded, modern surface system instead of the sharper M3 defaults.
 *
 * Deliberately *not* the desktop's radius scale, which the shadcn adaptation
 * tightened to a 10px base. A touch target is bigger than a pointer target and
 * sits closer to the eye, so the same numbers read as sharper on a phone than
 * they do on a monitor; the tokens are shared, the radii are each frontend's
 * own. This is the one place the two systems are meant to disagree.
 */
private val ComradeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Default M3 type with a firmer title hierarchy: names and headings sit
 * semi-bold so lists scan by name first, metadata second.
 */
private val ComradeTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.1.sp,
        ),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.2.sp),
    )
}

@Composable
fun ComradeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Blend the status bar with the top app bar (both sit on surface).
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = ComradeShapes,
        typography = ComradeTypography,
        content = content,
    )
}
