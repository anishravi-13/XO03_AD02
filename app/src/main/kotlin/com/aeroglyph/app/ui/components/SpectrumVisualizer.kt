package com.aeroglyph.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.aeroglyph.app.audio.ModemConfig
import com.aeroglyph.app.ui.theme.LocalAeroglyphExtras
import com.aeroglyph.app.ui.theme.binColor
import kotlin.math.log10
import kotlin.math.max

/**
 * The 16 tone bins of the modem, drawn from live [com.aeroglyph.app.audio.DspUtil]
 * magnitudes.
 *
 * This is a real instrument readout, not decoration: each bar is one of the
 * frequencies the decoder is actually listening at, so during a transmission
 * you watch the symbols land one bin at a time. It is the most direct way to
 * make an invisible acoustic channel legible to someone watching a demo.
 */
@Composable
fun SpectrumVisualizer(
    energies: DoubleArray,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val extras = LocalAeroglyphExtras.current
    val idleAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0.35f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "spectrumIdle",
    )

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val binCount = ModemConfig.TONE_COUNT
            val gap = 4.dp.toPx()
            val barWidth = ((size.width - gap * (binCount - 1)) / binCount).coerceAtLeast(1f)
            val corner = CornerRadius(barWidth / 2f, barWidth / 2f)
            val floorHeight = barWidth.coerceAtMost(size.height * 0.06f)

            for (bin in 0 until binCount) {
                val magnitude = energies.getOrElse(bin) { 0.0 }
                val level = normalizeToDisplay(magnitude)
                val height = max(floorHeight, (size.height * level).toFloat())
                val x = bin * (barWidth + gap)
                val color = binColor(bin, binCount, dark = extras.isDark)

                // Track behind each bar keeps the 16-bin structure visible even in silence.
                drawRoundRect(
                    color = color.copy(alpha = 0.10f * idleAlpha),
                    topLeft = Offset(x, 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = color.copy(alpha = idleAlpha),
                    topLeft = Offset(x, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = corner,
                )
            }
        }
    }
}

/**
 * Goertzel magnitudes span several orders of magnitude, so a linear bar would
 * sit flat at zero and then slam to full. Map through dB and clamp to a
 * display window that matches what a phone mic actually sees in a room.
 */
private fun normalizeToDisplay(magnitude: Double): Double {
    if (magnitude <= 0.0) return 0.0
    val db = 20.0 * log10(magnitude)
    val floorDb = -20.0
    val ceilingDb = 45.0
    return ((db - floorDb) / (ceilingDb - floorDb)).coerceIn(0.0, 1.0)
}
