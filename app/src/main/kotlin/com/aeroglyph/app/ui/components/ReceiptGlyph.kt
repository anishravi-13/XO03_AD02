package com.aeroglyph.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.aeroglyph.app.ui.theme.PulseViolet
import com.aeroglyph.app.ui.theme.SignalCyan
import com.aeroglyph.app.ui.theme.binColor
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The receipt a device keeps after decoding a message: a generated mark,
 * not a checkmark.
 *
 * Two seeds, on purpose:
 *  - [sessionId] drives the *structure* (how many rings, how they're swept),
 *    so every device that decoded the same broadcast shows a recognizably
 *    related mark -- you can see at a glance that the room got the same thing.
 *  - [receiptId] drives the *accents* (spoke and dot placement), so each
 *    device's copy is still individually its own.
 *
 * That's what makes a room of phones read as "unique, but clearly a family."
 */
@Composable
fun ReceiptGlyph(
    sessionId: Int,
    receiptId: Int,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    var revealed by remember(sessionId, receiptId) { mutableStateOf(!animated) }
    LaunchedEffect(sessionId, receiptId) { revealed = true }

    val progress by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "glyphReveal",
    )

    val spec = remember(sessionId, receiptId) { GlyphSpec.from(sessionId, receiptId) }

    Canvas(modifier = modifier) {
        drawGlyph(spec, progress)
    }
}

/** Deterministic description of one glyph, derived entirely from its two seeds. */
private data class GlyphSpec(
    val rings: List<Ring>,
    val spokes: List<Spoke>,
    val dots: List<Dot>,
    val rotation: Float,
) {
    data class Ring(val radiusFraction: Float, val startAngle: Float, val sweep: Float, val strokeFraction: Float, val tone: Float)
    data class Spoke(val angle: Float, val innerFraction: Float, val outerFraction: Float, val tone: Float)
    data class Dot(val angle: Float, val radiusFraction: Float, val sizeFraction: Float, val tone: Float)

    companion object {
        fun from(sessionId: Int, receiptId: Int): GlyphSpec {
            // Structure: session only. Every receiver of this broadcast agrees on this part.
            val structure = Random(sessionId.toLong() * 2654435761L)
            val ringCount = 3 + structure.nextInt(3)
            val rings = (0 until ringCount).map { i ->
                val base = 0.30f + 0.17f * i
                Ring(
                    radiusFraction = base + structure.nextFloat() * 0.05f,
                    startAngle = structure.nextFloat() * 360f,
                    sweep = 70f + structure.nextFloat() * 230f,
                    strokeFraction = 0.022f + structure.nextFloat() * 0.028f,
                    tone = i / (ringCount - 1).coerceAtLeast(1).toFloat(),
                )
            }

            // Accents: session + this device, so no two devices draw quite the same.
            val accent = Random((sessionId.toLong() shl 16) xor (receiptId.toLong() * 40503L) xor 0x5DEECE66DL)
            val spokeCount = 4 + accent.nextInt(5)
            val spokes = (0 until spokeCount).map {
                val angle = accent.nextFloat() * 360f
                val inner = 0.18f + accent.nextFloat() * 0.30f
                Spoke(
                    angle = angle,
                    innerFraction = inner,
                    outerFraction = inner + 0.10f + accent.nextFloat() * 0.22f,
                    tone = accent.nextFloat(),
                )
            }
            val dots = (0 until (2 + accent.nextInt(3))).map {
                Dot(
                    angle = accent.nextFloat() * 360f,
                    radiusFraction = 0.55f + accent.nextFloat() * 0.40f,
                    sizeFraction = 0.020f + accent.nextFloat() * 0.022f,
                    tone = accent.nextFloat(),
                )
            }

            return GlyphSpec(rings, spokes, dots, rotation = structure.nextFloat() * 360f)
        }
    }
}

private fun toneColor(tone: Float): Color = binColor((tone * 15).toInt().coerceIn(0, 15), 16)

private fun DrawScope.drawGlyph(spec: GlyphSpec, progress: Float) {
    val extent = min(size.width, size.height)
    val center = Offset(size.width / 2f, size.height / 2f)
    val unit = extent / 2f

    rotate(degrees = spec.rotation + (1f - progress) * 40f, pivot = center) {

        spec.rings.forEach { ring ->
            val radius = unit * ring.radiusFraction
            val stroke = (unit * ring.strokeFraction).coerceAtLeast(1f)
            drawArc(
                color = toneColor(ring.tone).copy(alpha = 0.20f + 0.75f * progress),
                startAngle = ring.startAngle,
                sweepAngle = ring.sweep * progress,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }

        spec.spokes.forEach { spoke ->
            val radians = Math.toRadians(spoke.angle.toDouble())
            val inner = unit * spoke.innerFraction
            val outer = unit * (spoke.innerFraction + (spoke.outerFraction - spoke.innerFraction) * progress)
            drawLine(
                color = toneColor(spoke.tone).copy(alpha = 0.85f * progress),
                start = Offset(center.x + (cos(radians) * inner).toFloat(), center.y + (sin(radians) * inner).toFloat()),
                end = Offset(center.x + (cos(radians) * outer).toFloat(), center.y + (sin(radians) * outer).toFloat()),
                strokeWidth = unit * 0.020f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }

        spec.dots.forEach { dot ->
            val radians = Math.toRadians(dot.angle.toDouble())
            val distance = unit * dot.radiusFraction
            drawCircle(
                color = toneColor(dot.tone).copy(alpha = 0.9f * progress),
                radius = unit * dot.sizeFraction * progress,
                center = Offset(
                    center.x + (cos(radians) * distance).toFloat(),
                    center.y + (sin(radians) * distance).toFloat(),
                ),
            )
        }
    }

    // Core: the one part every glyph shares, so the family always has a center.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(SignalCyan, PulseViolet),
            center = center,
            radius = unit * 0.18f,
        ),
        radius = unit * 0.13f * (0.6f + 0.4f * progress),
        center = center,
    )
}

/** The short human-readable code shown under the glyph and carried in ACK bursts. */
fun receiptCode(sessionId: Int, receiptId: Int): String =
    "%02X-%02X".format(sessionId and 0xFF, receiptId and 0xFF)
