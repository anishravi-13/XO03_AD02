package com.aeroglyph.app.ui.screens

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aeroglyph.app.audio.FrameType
import com.aeroglyph.app.playback.ListenState
import com.aeroglyph.app.ui.ReceivedMessage
import com.aeroglyph.app.ui.RecoveryState
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
import kotlin.math.roundToInt

@Composable
fun ReceiverScreen(
    received: ReceivedMessage?,
    receiptId: Int,
    listenState: ListenState,
    recoveryState: RecoveryState,
    recoveredViaRecovery: Boolean,
    relayInFlight: Boolean,
    binEnergies: DoubleArray,
    peakEnergy: Double,
    noiseFloor: Double,
    audioSource: String,
    duplicateSessionId: Int?,
    onClear: () -> Unit,
    onRequestCatchUp: () -> Unit,
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
                listenState == ListenState.LOCKED -> StatusPill("LOCKED", Glyphs.shieldCheck)
                listenState == ListenState.SIGNAL -> StatusPill("SIGNAL", Glyphs.activity)
                else -> StatusPill("ARMED", Glyphs.mic, extras.textTertiary)
            }
        }

        Spacer(Modifier.height(20.dp))

        if (received == null) {
            ListeningState(
                binEnergies = binEnergies,
                listenState = listenState,
                recoveryState = recoveryState,
                peakEnergy = peakEnergy,
                noiseFloor = noiseFloor,
                audioSource = audioSource,
                onRequestCatchUp = onRequestCatchUp,
            )
        } else {
            DecodedState(
                received = received,
                receiptId = receiptId,
                recoveredViaRecovery = recoveredViaRecovery,
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
private fun ListeningState(
    binEnergies: DoubleArray,
    listenState: ListenState,
    recoveryState: RecoveryState,
    peakEnergy: Double,
    noiseFloor: Double,
    audioSource: String,
    onRequestCatchUp: () -> Unit,
) {
    val extras = LocalAeroglyphExtras.current
    val transition = rememberInfiniteTransition(label = "listenPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "pulse",
    )
    val busy = listenState != ListenState.IDLE

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(96.dp)
                .scale(if (busy) 1f else pulse)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (busy) 0.22f else 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            AeroIcon(
                if (busy) Glyphs.activity else Glyphs.mic,
                null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = when {
                listenState == ListenState.LOCKED -> "Locked on — decoding"
                listenState == ListenState.SIGNAL -> "Signal detected"
                recoveryState == RecoveryState.REQUESTING -> "Asking the room for the latest"
                recoveryState == RecoveryState.REPAIRING -> "Damaged frame — requesting repair"
                else -> "Waiting for a broadcast"
            },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (recoveryState) {
                RecoveryState.REQUESTING ->
                    "You joined after the broadcast. Any nearby device holding it will answer automatically."
                RecoveryState.REPAIRING ->
                    "Part of a message arrived intact but the rest was corrupt. Asking for that session by name."
                else ->
                    "No pairing, no accepting. Keep this screen open and stay in earshot."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textTertiary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))
        SecondaryButton(
            label = "Ask for the latest message",
            onClick = onRequestCatchUp,
            iconId = Glyphs.share,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DataLabel("LIVE SPECTRUM")
                Spacer(Modifier.weight(1f))
                DataLabel(audioSource)
            }
            Spacer(Modifier.height(12.dp))
            SpectrumVisualizer(
                energies = binEnergies,
                active = busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
            )
            Spacer(Modifier.height(14.dp))
            SignalMeter(peakEnergy = peakEnergy, noiseFloor = noiseFloor)
        }
    }
}

/**
 * The actual numbers behind the trigger.
 *
 * This is here because "nothing is happening" is otherwise indistinguishable
 * from "the microphone hears nothing at all", and those need very different
 * fixes. Watching SIGNAL climb above FLOOR when another phone transmits tells
 * you in one glance whether the problem is the room, the volume, or the code.
 */
@Composable
private fun SignalMeter(peakEnergy: Double, noiseFloor: Double) {
    val extras = LocalAeroglyphExtras.current
    val above = noiseFloor > 0 && peakEnergy > noiseFloor * 3.0

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            DataLabel("SIGNAL")
            Spacer(Modifier.height(4.dp))
            DataValue(
                peakEnergy.roundToInt().toString(),
                color = if (above) MaterialTheme.colorScheme.primary else extras.textTertiary,
            )
        }
        Column(Modifier.weight(1f)) {
            DataLabel("FLOOR")
            Spacer(Modifier.height(4.dp))
            DataValue(noiseFloor.roundToInt().toString(), color = extras.textTertiary)
        }
        Column(Modifier.weight(1f)) {
            DataLabel("MARGIN")
            Spacer(Modifier.height(4.dp))
            DataValue(
                if (noiseFloor <= 0.0) "—" else "${(peakEnergy / noiseFloor).roundToInt()}x",
                color = if (above) MaterialTheme.colorScheme.primary else extras.textTertiary,
            )
        }
    }
}

@Composable
private fun DecodedState(
    received: ReceivedMessage,
    receiptId: Int,
    recoveredViaRecovery: Boolean,
    binEnergies: DoubleArray,
    onClear: () -> Unit,
) {
    val extras = LocalAeroglyphExtras.current

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

            if (recoveredViaRecovery) {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AeroIcon(Glyphs.zap, null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = "Recovered automatically — a nearby device answered, " +
                            "with no action from the sender.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

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

        Spacer(Modifier.height(18.dp))
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
    when (received.frameType) {
        FrameType.RELAY -> StatusPill("VIA RELAY", Glyphs.relay, MaterialTheme.colorScheme.secondary)
        FrameType.ANSWER -> StatusPill("RECOVERED", Glyphs.zap, MaterialTheme.colorScheme.secondary)
        else -> StatusPill("DIRECT", Glyphs.radioTower, MaterialTheme.colorScheme.primary)
    }
}
