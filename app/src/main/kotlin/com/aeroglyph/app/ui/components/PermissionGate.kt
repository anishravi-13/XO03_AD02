package com.aeroglyph.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aeroglyph.app.ui.theme.LocalAeroglyphExtras

/**
 * Shown in place of a screen's content when the microphone permission isn't
 * granted. Styled like the rest of the app rather than left as a bare system
 * dialog aftermath -- a denied permission is a state of the product, not an
 * error page.
 */
@Composable
fun MicPermissionGate(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extras = LocalAeroglyphExtras.current

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(Glyphs.micOff),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(22.dp))
            Text(
                "Aeroglyph needs the microphone",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Sound is the only channel this app has. The microphone is how it hears " +
                    "broadcasts and relay traffic — nothing is recorded, stored, or sent anywhere.",
                style = MaterialTheme.typography.bodyMedium,
                color = extras.textTertiary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(26.dp))
            if (permanentlyDenied) {
                GradientButton(
                    label = "Open app settings",
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                    iconId = Glyphs.sliders,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Permission was denied more than once, so Android won't show the prompt again — it has to be enabled in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textTertiary,
                    textAlign = TextAlign.Center,
                )
            } else {
                GradientButton(
                    label = "Allow microphone",
                    onClick = onRequest,
                    modifier = Modifier.fillMaxWidth(),
                    iconId = Glyphs.mic,
                )
            }
        }
    }
}
