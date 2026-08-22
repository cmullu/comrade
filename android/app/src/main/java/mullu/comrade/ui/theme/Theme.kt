package mullu.comrade.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/*
 * The token layer lives across this package:
 *  - ColorTokens.kt   raw ARGB hex, framework-free (JUnit-testable)
 *  - Color.kt         those tokens as M3 ColorSchemes + CallPalette/AvatarPalette
 *  - Surfaces.kt       ComradeSurfaces: the shadcn-named ramp M3 has no slot for
 *  - Shape.kt          §3.2's derived radius scale
 *  - Type.kt           the type hierarchy
 *  - StateLayer.kt      §3.3's fixed state-layer opacities
 *  - Motion.kt          §3.5's durations/easing
 *  - Glass.kt           §3.4's glass tier (§5: no backdrop blur on Android)
 *  - Focus.kt            §4's focus ring
 *
 * This file only wires them together into one [ComradeTheme] composable —
 * see `docs/DESIGN_SYSTEM.md` for the contract itself.
 */

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
        darkTheme -> ComradeDarkColorScheme
        else -> ComradeLightColorScheme
    }
    // Material You dynamic colour has no source for the shadcn-named ramp
    // (there is no wallpaper-derived "card" or "ring"), so ComradeSurfaces
    // always follows the *brand* ramp for the given brightness rather than
    // trying to derive one from `colorScheme` — the same reasoning
    // `comrade_theme.dart`'s header comment gives for going brand-coloured
    // everywhere on the unified app.
    val surfaces = if (darkTheme) ComradeSurfaces.Dark else ComradeSurfaces.Light

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Blend the status bar with the top app bar (both sit on surface).
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalComradeSurfaces provides surfaces) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = ComradeShapes,
            typography = ComradeTypography,
            content = content,
        )
    }
}
