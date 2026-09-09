package com.aeroglyph.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ── Signal lab palette ───────────────────────────────────────────────────────
// Dark is the designed-for case; light exists so the app is usable for anyone
// who needs it, built from the same token structure rather than as an
// afterthought.

val Ink = Color(0xFF0A0D12)          // background
val Surface = Color(0xFF12151C)      // cards, sheets
val SurfaceRaised = Color(0xFF1B1F29) // elevated / pressed
val Hairline = Color(0xFF262B36)     // 1dp separators, input outlines

val SignalCyan = Color(0xFF4CF2C9)   // primary
val PulseViolet = Color(0xFFA78BFA)  // secondary
val AlertCoral = Color(0xFFFF6B6B)   // error

val TextPrimary = Color(0xFFF2F4F7)
val TextSecondary = Color(0xFF9AA3B2)
val TextTertiary = Color(0xFF636B7A)

// Light-theme counterparts
val PaperInk = Color(0xFFFAFAFB)
val PaperSurface = Color(0xFFFFFFFF)
val PaperSurfaceRaised = Color(0xFFF1F3F6)
val PaperHairline = Color(0xFFE2E6EC)
val PaperTextPrimary = Color(0xFF12151C)
val PaperTextSecondary = Color(0xFF5B6472)
val PaperTextTertiary = Color(0xFF8B94A3)
val SignalCyanDeep = Color(0xFF0E9C7D)   // cyan needs darkening to stay legible on white
val PulseVioletDeep = Color(0xFF6D4FD6)

/**
 * The signature gradient. It reads left-to-right as a sweep from the low end
 * of our band to the high end -- the same 17kHz → 19.5kHz motion the modem
 * actually performs, which is why it belongs on hero elements specifically.
 */
val SignalGradient = Brush.linearGradient(listOf(SignalCyan, PulseViolet))
val SignalGradientDeep = Brush.linearGradient(listOf(SignalCyanDeep, PulseVioletDeep))

/** Per-bin colors for the 16-tone spectrum visualizer: low bins cyan, high bins violet. */
fun binColor(binIndex: Int, binCount: Int, dark: Boolean = true): Color {
    val t = if (binCount <= 1) 0f else binIndex / (binCount - 1).toFloat()
    val from = if (dark) SignalCyan else SignalCyanDeep
    val to = if (dark) PulseViolet else PulseVioletDeep
    return Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = 1f,
    )
}
