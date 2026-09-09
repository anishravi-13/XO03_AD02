package com.aeroglyph.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aeroglyph.app.R
import com.aeroglyph.app.ui.theme.DataType
import com.aeroglyph.app.ui.theme.LocalAeroglyphExtras

/** Bundled Lucide vector drawables -- no icon font, no runtime fetch. */
object Glyphs {
    val mic = R.drawable.ic_mic
    val micOff = R.drawable.ic_mic_off
    val radioTower = R.drawable.ic_radio_tower
    val radioReceiver = R.drawable.ic_radio_receiver
    val checkCircle = R.drawable.ic_check_circle
    val signalLog = R.drawable.ic_signal_log
    val sliders = R.drawable.ic_sliders
    val relay = R.drawable.ic_relay
    val vibrate = R.drawable.ic_vibrate
    val info = R.drawable.ic_info
    val chevronDown = R.drawable.ic_chevron_down
    val close = R.drawable.ic_close
    val share = R.drawable.ic_share
    val activity = R.drawable.ic_activity
    val shieldCheck = R.drawable.ic_shield_check
    val copy = R.drawable.ic_copy
    val check = R.drawable.ic_check
}

@Composable
fun AeroIcon(
    id: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Icon(
        painter = painterResource(id),
        contentDescription = contentDescription,
        modifier = modifier.size(20.dp),
        tint = tint,
    )
}

/** Primary action: the gradient is reserved for these, so there's never more than one per screen. */
@Composable
fun GradientButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconId: Int? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "buttonPress",
    )
    val extras = LocalAeroglyphExtras.current

    Box(
        modifier = modifier
            .scale(scale)
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) extras.gradient else Brush.linearGradient(listOf(extras.hairline, extras.hairline)))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (iconId != null) {
                AeroIcon(
                    id = iconId,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimary else extras.textTertiary,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onPrimary else extras.textTertiary,
            )
        }
    }
}

@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconId: Int? = null,
) {
    val extras = LocalAeroglyphExtras.current
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, extras.hairline), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (iconId != null) {
            AeroIcon(iconId, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val extras = LocalAeroglyphExtras.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, extras.hairline), MaterialTheme.shapes.large)
            .padding(20.dp),
        content = content,
    )
}

/** Small uppercase mono label -- the "instrument panel" caption style. */
@Composable
fun DataLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = DataType.micro,
        color = LocalAeroglyphExtras.current.textTertiary,
        modifier = modifier,
    )
}

/** A numeric/technical readout. Always mono, by convention. */
@Composable
fun DataValue(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(text = text, style = DataType.medium, color = color, modifier = modifier)
}

@Composable
fun StatusPill(
    text: String,
    iconId: Int? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconId != null) {
            Icon(
                painter = painterResource(iconId),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = DataType.small, color = tint)
    }
}

/**
 * Collapsed by default so the main screens keep one obvious focal point --
 * everything fiddly (room profile, relay TTL, confirmation mode) lives here.
 */
@Composable
fun AdvancedSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val extras = LocalAeroglyphExtras.current
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "advancedChevron",
    )

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onToggle)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AeroIcon(Glyphs.sliders, null, tint = extras.textTertiary)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Advanced",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            AeroIcon(
                Glyphs.chevronDown,
                null,
                modifier = Modifier.rotate(chevronRotation),
                tint = extras.textTertiary,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut() + shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)),
        ) {
            Column(content = content)
        }
    }
}

/** Segmented control used for Room Profile. Deliberately not a dropdown -- one tap, always visible. */
@Composable
fun SegmentedChoice(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extras = LocalAeroglyphExtras.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    style = DataType.small,
                    textAlign = TextAlign.Center,
                    color = if (selected) MaterialTheme.colorScheme.primary else extras.textTertiary,
                )
            }
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    iconId: Int,
    modifier: Modifier = Modifier,
) {
    val extras = LocalAeroglyphExtras.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AeroIcon(
            iconId,
            null,
            tint = if (checked) MaterialTheme.colorScheme.primary else extras.textTertiary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textTertiary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        MiniSwitch(checked = checked)
    }
}

@Composable
private fun MiniSwitch(checked: Boolean) {
    val extras = LocalAeroglyphExtras.current
    val knobOffset by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "switchKnob",
    )
    Box(
        modifier = Modifier
            .width(44.dp)
            .height(26.dp)
            .clip(CircleShape)
            .background(
                if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .border(BorderStroke(1.dp, extras.hairline), CircleShape),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .offsetFraction(knobOffset, 18.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (checked) MaterialTheme.colorScheme.primary else extras.textTertiary),
        )
    }
}

private fun Modifier.offsetFraction(fraction: Float, maxOffset: androidx.compose.ui.unit.Dp): Modifier =
    this.then(Modifier.padding(start = maxOffset * fraction))

/** Section heading used at the top of each screen's content. */
@Composable
fun ScreenHeading(
    title: String,
    caption: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (caption != null) {
            DataLabel(caption)
            Spacer(Modifier.height(6.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** Inline warning strip, used when the mic can't be opened at all. */
@Composable
fun WarningBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AeroIcon(Glyphs.micOff, null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .clip(CircleShape)
                .clickable(onClick = onDismiss)
                .padding(4.dp),
        ) {
            AeroIcon(Glyphs.close, "Dismiss", tint = MaterialTheme.colorScheme.error)
        }
    }
}
