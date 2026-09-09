package com.aeroglyph.app.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aeroglyph.app.session.LogDirection
import com.aeroglyph.app.session.SignalLogEntry
import com.aeroglyph.app.ui.components.AeroIcon
import com.aeroglyph.app.ui.components.DataLabel
import com.aeroglyph.app.ui.components.DataValue
import com.aeroglyph.app.ui.components.Glyphs
import com.aeroglyph.app.ui.components.Panel
import com.aeroglyph.app.ui.components.ReceiptGlyph
import com.aeroglyph.app.ui.components.ScreenHeading
import com.aeroglyph.app.ui.components.SecondaryButton
import com.aeroglyph.app.ui.theme.DataType
import com.aeroglyph.app.ui.theme.LocalAeroglyphExtras
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SignalLogScreen(
    entries: List<SignalLogEntry>,
    receiptId: Int,
    attempts: Int,
    successes: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extras = LocalAeroglyphExtras.current

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp),
    ) {
        ScreenHeading(title = "Signal log", caption = "THIS SESSION")

        Spacer(Modifier.height(18.dp))
        Panel {
            Row {
                Stat("DECODED", successes.toString(), Modifier.weight(1f))
                Stat("ATTEMPTS", attempts.toString(), Modifier.weight(1f))
                Stat(
                    "SUCCESS",
                    if (attempts == 0) "—" else "${(successes * 100 / attempts)}%",
                    Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AeroIcon(Glyphs.signalLog, null, tint = extras.textTertiary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Nothing yet. Broadcasts you send and messages you receive land here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.textTertiary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(entries, key = { it.id }) { entry ->
                    LogRow(entry = entry, receiptId = receiptId)
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton(
                        label = "Clear log",
                        onClick = onClear,
                        iconId = Glyphs.close,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        DataLabel(label)
        Spacer(Modifier.height(6.dp))
        Text(value, style = DataType.large, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LogRow(entry: SignalLogEntry, receiptId: Int) {
    val extras = LocalAeroglyphExtras.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, extras.hairline, MaterialTheme.shapes.medium)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReceiptGlyph(
            sessionId = entry.sessionId,
            receiptId = receiptId,
            animated = false,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.message.ifBlank { "(empty payload)" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            Spacer(Modifier.height(5.dp))
            Row {
                DataValue(timeFormat.format(Date(entry.timestampMs)), color = extras.textTertiary)
                Spacer(Modifier.size(10.dp))
                DataValue(entry.direction.label, color = directionColor(entry))
                Spacer(Modifier.size(10.dp))
                DataValue("%02X".format(entry.sessionId), color = extras.textTertiary)
            }
        }
    }
}

/**
 * Colour carries the category: what we sent, what the mesh carried, and what
 * the recovery layer had to go and fetch.
 */
@Composable
private fun directionColor(entry: SignalLogEntry) = when (entry.direction) {
    LogDirection.SENT -> MaterialTheme.colorScheme.primary
    LogDirection.RELAYED -> MaterialTheme.colorScheme.secondary
    LogDirection.RECEIVED -> LocalAeroglyphExtras.current.textTertiary
    LogDirection.REPAIR_REQUESTED,
    LogDirection.CATCH_UP_REQUESTED -> MaterialTheme.colorScheme.error
    LogDirection.REPAIR_ANSWERED,
    LogDirection.CATCH_UP_ANSWERED,
    LogDirection.RECOVERED -> MaterialTheme.colorScheme.secondary
}
