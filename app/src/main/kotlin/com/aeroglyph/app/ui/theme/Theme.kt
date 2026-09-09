package com.aeroglyph.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = SignalCyan,
    onPrimary = Ink,
    secondary = PulseViolet,
    onSecondary = Ink,
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    outline = Hairline,
    error = AlertCoral,
    onError = Ink,
)

private val LightColors = lightColorScheme(
    primary = SignalCyanDeep,
    onPrimary = PaperSurface,
    secondary = PulseVioletDeep,
    onSecondary = PaperSurface,
    background = PaperInk,
    onBackground = PaperTextPrimary,
    surface = PaperSurface,
    onSurface = PaperTextPrimary,
    surfaceVariant = PaperSurfaceRaised,
    onSurfaceVariant = PaperTextSecondary,
    outline = PaperHairline,
    error = AlertCoral,
    onError = PaperSurface,
)

/** Softer, more deliberate corner radii than Material's defaults. */
private val AeroglyphShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Tokens Material3's ColorScheme has no slot for. */
data class AeroglyphExtras(
    val isDark: Boolean,
    val gradient: Brush,
    val textTertiary: androidx.compose.ui.graphics.Color,
    val hairline: androidx.compose.ui.graphics.Color,
)

val LocalAeroglyphExtras = staticCompositionLocalOf {
    AeroglyphExtras(isDark = true, gradient = SignalGradient, textTertiary = TextTertiary, hairline = Hairline)
}

@Composable
fun AeroglyphTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Note: no Material You dynamic color on purpose. Aeroglyph's identity is
    // the cyan/violet band sweep -- recoloring it from the device wallpaper
    // would break the one thing that makes every screen recognizably this app.
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val extras = AeroglyphExtras(
        isDark = darkTheme,
        gradient = if (darkTheme) SignalGradient else SignalGradientDeep,
        textTertiary = if (darkTheme) TextTertiary else PaperTextTertiary,
        hairline = if (darkTheme) Hairline else PaperHairline,
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // The setColor APIs are deprecated in favor of true edge-to-edge
            // (drawing under transparent bars), which would mean adding
            // systemBarsPadding() throughout every screen -- a larger change
            // than this app's bars warrant. Painting the bars to match the
            // background is still fully supported down to minSdk 24.
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalAeroglyphExtras provides extras) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AeroglyphTypography,
            shapes = AeroglyphShapes,
            content = content,
        )
    }
}
