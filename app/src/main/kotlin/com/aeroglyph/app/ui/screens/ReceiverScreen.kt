package com.aeroglyph.app.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aeroglyph.app.audio.FrameType
import com.aeroglyph.app.ui.ReceivedMessage
import com.aeroglyph.app.ui.components.AeroIcon
import com.aeroglyph.app.ui.components.DataLabel
import com.aeroglyph.app.ui.components.DataValue
import com.aeroglyph.app.ui.components.Glyphs
import com.aeroglyph.app.ui.components.Panel
import com.aeroglyph.app.ui.components.ReceiptGlyph
import com.aeroglyph.app.ui.components.ScreenHeading
import com.aeroglyph.app.ui.components.SecondaryButton
import com.aeroglyph.app.ui.components.SpectrumVisualizer
import com.aeroglyph.app.ui.components.StatusPill
import com.aeroglyph.app.ui.components.receiptCode
import com.aeroglyph.app.ui.theme.DataType
import com.aeroglyph.app.ui.theme.LocalAeroglyphExtras

@Composable
fun ReceiverScreen(
    received: ReceivedMessage?,
    receiptId: Int,
    listening: Boolean,
    signalPresent: Boolean,
    relayInFlight: Boolean,
    binEnergies: DoubleArray,
    duplicateSessionId: Int?,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extras = LocalAeroglyphExtras.current

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenHeading(
                title = if (received == null) "Listening" else "Message received",
                caption = "RECEIVER",
                modifier = Modifier.weight(1f),
            )
            when {
                relayInFlight -> StatusPill("RELAYING", Glyphs.relay, MaterialTheme.colorScheme.secondary)
                signalPresent -> StatusPill("SIGNAL", Glyphs.activity)
                listening -> StatusPill("ARMED", Glyphs.mic, extras.textTertiary)
            }
        }

        Spacer(Modifier.height(20.dp))

        if (received == null) {
            ListeningState(binEnergies = binEnergies, signalPresent = signalPresent)
        } else {
            DecodedState(
                received = received,
                receiptId = receiptId,
                binEnergies = binEnergies,
                onClear = onClear,
            )
        }

        AnimatedVisibility(
            visible = duplicateSessionId != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AeroIcon(Glyphs.check, null, tint = extras.textTertiary)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "Already have session ${duplicateSessionId?.let { "%02X".format(it) }} — ignoring the repeat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ListeningState(binEnergies: DoubleArray, signalPresent: Boolean) {
    val extras = LocalAeroglyphExtras.current
    val transition = rememberInfiniteTransition(label = "listenPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "pulse",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(96.dp)
                .scale(if (signalPresent) 1f else pulse)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (signalPresent) 0.22f else 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            AeroIcon(
                if (signalPresent) Glyphs.activity else Glyphs.mic,
                null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = if (signalPresent) "Signal detected — decoding" else "Waiting for a broadcast",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "No pairing, no accepting. Keep this screen open and stay in earshot.",
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textTertiary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))
        Panel {
            DataLabel("LIVE SPECTRUM")
            Spacer(Modifier.height(12.dp))
            SpectrumVisualizer(
                energies = binEnergies,
                active = signalPresent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
            )
        }
    }
}

@Composable
private fun DecodedState(
    received: ReceivedMessage,
    receiptId: Int,
    binEnergies: DoubleArray,
    onClear: () -> Unit,
) {
    val extras = LocalAeroglyphExtras.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ReceiptGlyph(
            sessionId = received.sessionId,
            receiptId = receiptId,
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .aspectRatio(1f),
        )

        Spacer(Modifier.height(10.dp))
        Text(
            text = receiptCode(received.sessionId, receiptId),
            style = DataType.medium,
            color = extras.textTertiary,
        )

        Spacer(Modifier.height(22.dp))
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AeroIcon(Glyphs.checkCircle, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                DataLabel("CRC VERIFIED")
                Spacer(Modifier.weight(1f))
                HopBadge(received)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = received.message,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column {
                    DataLabel("SESSION")
                    Spacer(Modifier.height(4.dp))
                    DataValue("%02X".format(received.sessionId))
                }
                Column {
                    DataLabel("TTL LEFT")
                    Spacer(Modifier.height(4.dp))
                    DataValue(received.hopCount.toString())
                }
                Column {
                    DataLabel("BYTES")
                    Spacer(Modifier.height(4.dp))
                    DataValue(received.message.toByteArray().size.toString())
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Panel {
            DataLabel("LIVE SPECTRUM")
            Spacer(Modifier.height(12.dp))
            SpectrumVisualizer(
                energies = binEnergies,
                active = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(
                label = "Copy",
                onClick = { clipboard.setText(AnnotatedString(received.message)) },
                iconId = Glyphs.copy,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                label = "Share",
                onClick = {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, received.message)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, null))
                },
                iconId = Glyphs.share,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))
        SecondaryButton(
            label = "Keep listening",
            onClick = onClear,
            iconId = Glyphs.mic,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HopBadge(received: ReceivedMessage) {
    if (received.frameType == FrameType.RELAY) {
        StatusPill("VIA RELAY", Glyphs.relay, MaterialTheme.colorScheme.secondary)
    } else {
        StatusPill("DIRECT", Glyphs.radioTower, MaterialTheme.colorScheme.primary)
    }
}
