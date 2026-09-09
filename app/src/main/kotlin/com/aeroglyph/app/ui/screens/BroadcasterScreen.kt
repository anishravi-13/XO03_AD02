package com.aeroglyph.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.aeroglyph.app.audio.ModemConfig
import com.aeroglyph.app.audio.RoomProfile
import com.aeroglyph.app.ui.BroadcastSettings
import com.aeroglyph.app.ui.components.AdvancedSection
import com.aeroglyph.app.ui.components.DataLabel
import com.aeroglyph.app.ui.components.DataValue
import com.aeroglyph.app.ui.components.Glyphs
import com.aeroglyph.app.ui.components.GradientButton
import com.aeroglyph.app.ui.components.Panel
import com.aeroglyph.app.ui.components.ScreenHeading
import com.aeroglyph.app.ui.components.SecondaryButton
import com.aeroglyph.app.ui.components.SegmentedChoice
import com.aeroglyph.app.ui.components.SpectrumVisualizer
import com.aeroglyph.app.ui.components.StatusPill
import com.aeroglyph.app.ui.components.ToggleRow
import com.aeroglyph.app.ui.theme.DataType
import com.aeroglyph.app.ui.theme.LocalAeroglyphExtras

@Composable
fun BroadcasterScreen(
    draft: String,
    byteLength: Int,
    settings: BroadcastSettings,
    isTransmitting: Boolean,
    progress: Float,
    binEnergies: DoubleArray,
    confirmedCount: Int,
    relayInFlight: Boolean,
    requestsAnswered: Int,
    onDraftChange: (String) -> Unit,
    onBroadcast: () -> Unit,
    onCancel: () -> Unit,
    onProfileChange: (RoomProfile) -> Unit,
    onRelayToggle: (Boolean) -> Unit,
    onRelayTtlChange: (Int) -> Unit,
    onConfirmationToggle: (Boolean) -> Unit,
    onAccessibilityToggle: (Boolean) -> Unit,
    onAutoRecoveryToggle: (Boolean) -> Unit,
    onShowConfirmationInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extras = LocalAeroglyphExtras.current
    var advancedExpanded by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenHeading(title = "Broadcast", caption = "TRANSMITTER", modifier = Modifier.weight(1f))
            if (relayInFlight) {
                StatusPill("RELAYING", Glyphs.relay, MaterialTheme.colorScheme.secondary)
            } else if (isTransmitting) {
                StatusPill("ON AIR", Glyphs.radioTower)
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Message input ───────────────────────────────────────────────────
        Panel {
            DataLabel("MESSAGE OR URL")
            Spacer(Modifier.height(10.dp))
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                enabled = !isTransmitting,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(
                                "Welcome to the demo — aeroglyph.dev",
                                style = MaterialTheme.typography.bodyLarge,
                                color = extras.textTertiary,
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DataValue(
                    text = "$byteLength/${ModemConfig.MAX_PAYLOAD_BYTES} B",
                    color = if (byteLength >= ModemConfig.MAX_PAYLOAD_BYTES) MaterialTheme.colorScheme.error else extras.textTertiary,
                )
                Spacer(Modifier.weight(1f))
                DataValue(
                    text = "~${estimateSeconds(byteLength, settings.roomProfile)}s AIR TIME",
                    color = extras.textTertiary,
                )
            }
            if (byteLength >= ModemConfig.MAX_PAYLOAD_BYTES) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Trimmed to fit one frame — Aeroglyph sends short messages and URLs, not documents.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Live spectrum ───────────────────────────────────────────────────
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DataLabel("SPECTRUM · ${ModemConfig.TONE_COUNT} BINS")
                Spacer(Modifier.weight(1f))
                DataValue(
                    "${(ModemConfig.LOW_TONE_HZ / 1000).toInt()}–${ModemConfig.HIGH_TONE_HZ / 1000}kHz",
                    color = extras.textTertiary,
                )
            }
            Spacer(Modifier.height(14.dp))
            SpectrumVisualizer(
                energies = binEnergies,
                active = isTransmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
            )
            AnimatedVisibility(visible = isTransmitting, enter = fadeIn(), exit = fadeOut()) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    TransmitProgress(progress)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Confirmation tally ──────────────────────────────────────────────
        AnimatedVisibility(visible = settings.confirmationMode, enter = fadeIn(), exit = fadeOut()) {
            Column {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            DataLabel("DEVICES CONFIRMED")
                            Spacer(Modifier.height(6.dp))
                            Text(
                                confirmedCount.toString().padStart(2, '0'),
                                style = DataType.large,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        SecondaryButton(
                            label = "How this works",
                            onClick = onShowConfirmationInfo,
                            iconId = Glyphs.info,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // ── Recovery served ────────────────────────────────────────────────
        // Proof the repair/catch-up layer is doing something: this device has
        // re-sent the message to phones that missed it or arrived late, with
        // nobody pressing anything.
        AnimatedVisibility(visible = requestsAnswered > 0, enter = fadeIn(), exit = fadeOut()) {
            Column {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            DataLabel("REQUESTS ANSWERED")
                            Spacer(Modifier.height(6.dp))
                            Text(
                                requestsAnswered.toString().padStart(2, '0'),
                                style = DataType.large,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        Text(
                            "Devices that missed the broadcast or joined late " +
                                "were served automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = extras.textTertiary,
                            modifier = Modifier.weight(1.4f),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // ── Primary action ──────────────────────────────────────────────────
        if (isTransmitting) {
            SecondaryButton(
                label = "Stop transmitting",
                onClick = onCancel,
                iconId = Glyphs.close,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            GradientButton(
                label = "Broadcast",
                onClick = onBroadcast,
                enabled = draft.isNotBlank(),
                iconId = Glyphs.radioTower,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(8.dp))

        AdvancedSection(expanded = advancedExpanded, onToggle = { advancedExpanded = !advancedExpanded }) {
            Spacer(Modifier.height(4.dp))
            DataLabel("ROOM PROFILE")
            Spacer(Modifier.height(8.dp))
            SegmentedChoice(
                options = RoomProfile.entries.map { it.label },
                selectedIndex = RoomProfile.entries.indexOf(settings.roomProfile),
                onSelect = { onProfileChange(RoomProfile.entries[it]) },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${settings.roomProfile.symbolRateHz.toInt()} symbols/sec · " +
                    "${settings.roomProfile.repeatCount}× repetition",
                style = DataType.small,
                color = extras.textTertiary,
            )

            Spacer(Modifier.height(6.dp))
            ToggleRow(
                title = "Echo Relay",
                subtitle = "Receivers rebroadcast once, extending range past this device",
                checked = settings.relayEnabled,
                onCheckedChange = onRelayToggle,
                iconId = Glyphs.relay,
            )
            AnimatedVisibility(visible = settings.relayEnabled) {
                Column {
                    DataLabel("HOP LIMIT (TTL)")
                    Spacer(Modifier.height(8.dp))
                    SegmentedChoice(
                        options = listOf("1", "2", "3"),
                        selectedIndex = (settings.relayTtl - 1).coerceIn(0, 2),
                        onSelect = { onRelayTtlChange(it + 1) },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            ToggleRow(
                title = "Auto repair & catch-up",
                subtitle = "Re-send on request, and answer devices that join late",
                checked = settings.autoRecovery,
                onCheckedChange = onAutoRecoveryToggle,
                iconId = Glyphs.zap,
            )
            ToggleRow(
                title = "Confirmation mode",
                subtitle = "Receivers send a short acoustic ACK back",
                checked = settings.confirmationMode,
                onCheckedChange = onConfirmationToggle,
                iconId = Glyphs.shieldCheck,
            )
            ToggleRow(
                title = "Accessibility pulse",
                subtitle = "Vibrate a felt pattern when a message lands",
                checked = settings.accessibilityPulse,
                onCheckedChange = onAccessibilityToggle,
                iconId = Glyphs.vibrate,
            )
        }
    }
}

@Composable
private fun TransmitProgress(progress: Float) {
    val extras = LocalAeroglyphExtras.current
    val animated by animateFloatAsState(
        targetValue = progress,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "transmitProgress",
    )
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animated.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(extras.gradient),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            DataValue("${(animated * 100).toInt()}%", color = extras.textTertiary)
            DataValue("REPEATING FRAME", color = extras.textTertiary)
        }
    }
}

/** Rough air time so an organizer knows how long to hold the room quiet. */
private fun estimateSeconds(byteLength: Int, profile: RoomProfile): Int {
    val symbols = (ModemConfig.HEADER_BYTES + byteLength + ModemConfig.CRC_BYTES) * 4
    val bodySeconds = symbols / profile.symbolRateHz
    val chirpSeconds = 2 * ModemConfig.CHIRP_DURATION_MS / 1000.0
    val one = bodySeconds + chirpSeconds
    val total = one * profile.repeatCount + (profile.repeatCount - 1) * (ModemConfig.REPEAT_GAP_MS / 1000.0)
    return total.toInt().coerceAtLeast(1)
}
