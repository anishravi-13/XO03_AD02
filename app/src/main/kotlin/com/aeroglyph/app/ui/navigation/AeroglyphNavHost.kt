package com.aeroglyph.app.ui.navigation

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aeroglyph.app.ui.AeroglyphViewModel
import com.aeroglyph.app.ui.components.AeroIcon
import com.aeroglyph.app.ui.components.Glyphs
import com.aeroglyph.app.ui.components.MicPermissionGate
import com.aeroglyph.app.ui.components.WarningBanner
import com.aeroglyph.app.ui.screens.BroadcasterScreen
import com.aeroglyph.app.ui.screens.ModeSelectScreen
import com.aeroglyph.app.ui.screens.ReceiverScreen
import com.aeroglyph.app.ui.screens.SignalLogScreen
import com.aeroglyph.app.ui.theme.LocalAeroglyphExtras
import kotlinx.coroutines.delay

enum class Screen { MODE_SELECT, BROADCASTER, RECEIVER, SIGNAL_LOG }

@Composable
fun AeroglyphRoot(
    permissionGranted: Boolean,
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    viewModel: AeroglyphViewModel = viewModel(),
) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.MODE_SELECT) }
    var showConfirmationInfo by remember { mutableStateOf(false) }

    val draft by viewModel.draftMessage.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val isTransmitting by viewModel.isTransmitting.collectAsState()
    val progress by viewModel.transmitProgress.collectAsState()
    val binEnergies by viewModel.spectrumEnergies.collectAsState()
    val listening by viewModel.isListening.collectAsState()
    val signalPresent by viewModel.signalPresent.collectAsState()
    val received by viewModel.lastReceived.collectAsState()
    val duplicate by viewModel.duplicateNotice.collectAsState()
    val listenerError by viewModel.listenerError.collectAsState()
    var dismissedError by remember { mutableStateOf<String?>(null) }
    val confirmed by viewModel.confirmedReceipts.collectAsState()
    val relayInFlight by viewModel.relayInFlight.collectAsState()
    val logEntries by viewModel.signalLog.entries.collectAsState()
    val attempts by viewModel.signalLog.decodeAttempts.collectAsState()
    val successes by viewModel.signalLog.decodeSuccesses.collectAsState()

    // The broadcaster listens too -- it needs the mic for ACKs and to notice
    // relay traffic -- so listening is tied to permission, not to mode.
    LaunchedEffect(permissionGranted, screen) {
        if (permissionGranted && screen != Screen.MODE_SELECT) {
            viewModel.startListening()
        }
    }

    LaunchedEffect(duplicate) {
        if (duplicate != null) {
            delay(2500)
            viewModel.clearDuplicateNotice()
        }
    }

    if (!permissionGranted && screen != Screen.MODE_SELECT) {
        MicPermissionGate(
            permanentlyDenied = permanentlyDenied,
            onRequest = onRequestPermission,
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (screen) {
            Screen.MODE_SELECT -> ModeSelectScreen(
                binEnergies = binEnergies,
                listening = listening,
                onChooseBroadcaster = { screen = Screen.BROADCASTER },
                onChooseReceiver = { screen = Screen.RECEIVER },
                onOpenLog = { screen = Screen.SIGNAL_LOG },
            )

            Screen.BROADCASTER -> Scaffolded(
                onBack = { screen = Screen.MODE_SELECT },
                onOpenLog = { screen = Screen.SIGNAL_LOG },
            ) {
                BroadcasterScreen(
                    draft = draft,
                    byteLength = viewModel.draftByteLength(),
                    settings = settings,
                    isTransmitting = isTransmitting,
                    progress = progress,
                    binEnergies = binEnergies,
                    confirmedCount = confirmed.size,
                    relayInFlight = relayInFlight,
                    onDraftChange = viewModel::onDraftChanged,
                    onBroadcast = viewModel::broadcast,
                    onCancel = viewModel::cancelTransmission,
                    onProfileChange = viewModel::setRoomProfile,
                    onRelayToggle = viewModel::setRelayEnabled,
                    onRelayTtlChange = viewModel::setRelayTtl,
                    onConfirmationToggle = viewModel::setConfirmationMode,
                    onAccessibilityToggle = viewModel::setAccessibilityPulse,
                    onShowConfirmationInfo = { showConfirmationInfo = true },
                )
            }

            Screen.RECEIVER -> Scaffolded(
                onBack = { screen = Screen.MODE_SELECT },
                onOpenLog = { screen = Screen.SIGNAL_LOG },
            ) {
                ReceiverScreen(
                    received = received,
                    receiptId = viewModel.receiptId,
                    listening = listening,
                    signalPresent = signalPresent,
                    relayInFlight = relayInFlight,
                    binEnergies = binEnergies,
                    duplicateSessionId = duplicate,
                    onClear = viewModel::clearReceived,
                )
            }

            Screen.SIGNAL_LOG -> Scaffolded(
                onBack = { screen = Screen.MODE_SELECT },
                onOpenLog = null,
            ) {
                SignalLogScreen(
                    entries = logEntries,
                    receiptId = viewModel.receiptId,
                    attempts = attempts,
                    successes = successes,
                    onClear = viewModel::resetSession,
                )
            }
        }
    }

    if (showConfirmationInfo) {
        ConfirmationInfoDialog(onDismiss = { showConfirmationInfo = false })
    }

    val activeError = listenerError
    if (activeError != null && activeError != dismissedError) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
            WarningBanner(
                message = activeError,
                onDismiss = { dismissedError = activeError },
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopListening() }
    }
}

@Composable
private fun Scaffolded(
    onBack: () -> Unit,
    onOpenLog: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val extras = LocalAeroglyphExtras.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .padding(10.dp),
            ) {
                // Only a down-chevron is bundled; rotating it to point left
                // keeps one icon in the set instead of two near-identical ones.
                AeroIcon(
                    Glyphs.chevronDown,
                    "Back",
                    modifier = Modifier.rotate(90f),
                    tint = extras.textTertiary,
                )
            }
            Spacer(Modifier.weight(1f))
            if (onOpenLog != null) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onOpenLog)
                        .padding(10.dp),
                ) {
                    AeroIcon(Glyphs.signalLog, "Signal log", tint = extras.textTertiary)
                }
            }
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun ConfirmationInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
        },
        title = {
            Text("About confirmation mode", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Text(
                "Each device that decodes your message sends back a short acoustic ACK, and this " +
                    "counter tallies the unique ones it hears.\n\n" +
                    "It is best-effort by nature: if many devices ACK at once their bursts overlap and " +
                    "some are lost, so the count can read low in a busy room. The checkmark and glyph on " +
                    "each receiver's own screen is always the authoritative confirmation.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
