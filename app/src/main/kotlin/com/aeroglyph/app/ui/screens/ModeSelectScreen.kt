package com.aeroglyph.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeroglyph.app.audio.AcousticBand
import com.aeroglyph.app.audio.ModemConfig
import com.aeroglyph.app.ui.components.AeroIcon
import com.aeroglyph.app.ui.components.DataLabel
import com.aeroglyph.app.ui.components.Glyphs
import com.aeroglyph.app.ui.components.SpectrumVisualizer
import com.aeroglyph.app.ui.theme.DataType
import com.aeroglyph.app.ui.theme.LocalAeroglyphExtras

@Composable
fun ModeSelectScreen(
    binEnergies: DoubleArray,
    listening: Boolean,
    onChooseBroadcaster: () -> Unit,
    onChooseReceiver: () -> Unit,
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extras = LocalAeroglyphExtras.current

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // The idle spectrum sits behind the choice: the first thing you see is
        // the instrument, not a menu.
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 24.dp)
                .padding(top = 96.dp)
                .alpha(0.5f),
        ) {
            SpectrumVisualizer(energies = binEnergies, active = listening, modifier = Modifier.fillMaxSize())
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Wordmark()
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onOpenLog)
                        .padding(10.dp),
                ) {
                    AeroIcon(Glyphs.signalLog, "Signal log", tint = extras.textTertiary)
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "Send a message across a room using nothing but a speaker and a microphone.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(28.dp))
            Row {
                // The receiver watches every band at once, so quote the whole
                // span it listens across rather than just the default one.
                BandStat(
                    "BAND",
                    "${(AcousticBand.entries.minOf { it.lowToneHz } / 1000).toInt()}–" +
                        "${AcousticBand.entries.maxOf { it.highToneHz } / 1000} kHz",
                )
                Spacer(Modifier.width(28.dp))
                BandStat("TONES", "${ModemConfig.TONE_COUNT}-FSK")
                Spacer(Modifier.width(28.dp))
                BandStat("NETWORK", "NONE")
            }

            Spacer(Modifier.height(28.dp))
            ModeCard(
                iconId = Glyphs.radioTower,
                title = "Broadcast",
                description = "Type a message and push it out to every listening device nearby.",
                onClick = onChooseBroadcaster,
            )
            Spacer(Modifier.height(12.dp))
            ModeCard(
                iconId = Glyphs.radioReceiver,
                title = "Listen",
                description = "Wait for a broadcast. No pairing, no accepting — just be open.",
                onClick = onChooseReceiver,
            )
        }
    }
}

@Composable
private fun Wordmark() {
    val extras = LocalAeroglyphExtras.current
    val transition = rememberInfiniteTransition(label = "wordmarkPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse),
        label = "pulse",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(extras.gradient)
                .alpha(pulse),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "Aeroglyph",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "ACOUSTIC MESH",
                style = DataType.micro,
                color = extras.textTertiary,
            )
        }
    }
}

@Composable
private fun BandStat(label: String, value: String) {
    Column {
        DataLabel(label)
        Spacer(Modifier.height(4.dp))
        Text(value, style = DataType.small, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ModeCard(
    iconId: Int,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val extras = LocalAeroglyphExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, extras.hairline, MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconId),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = extras.textTertiary,
            )
        }
    }
}
