package cord.kasane.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Proportional Material 3 slider for media seek, volume, and settings controls.
 * Replaces Material 3 1.3's 16dp-thick track + 44dp vertical bar thumb with a clean
 * pill track and circular thumb while retaining full M3 Slider semantics and keyboard support.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KasaneSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val hovered by interactionSource.collectIsHoveredAsState()
    val dragged by interactionSource.collectIsDraggedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val active = enabled && (hovered || dragged || pressed || focused)

    val trackHeight by animateDpAsState(
        targetValue = if (active) (if (compact) 5.dp else 6.dp) else 4.dp,
        label = "sliderTrackHeight",
    )
    val thumbSize by animateDpAsState(
        targetValue = when {
            !enabled -> if (compact) 8.dp else 10.dp
            dragged || pressed -> if (compact) 14.dp else 16.dp
            hovered || focused -> if (compact) 12.dp else 14.dp
            compact -> 10.dp
            else -> 12.dp
        },
        label = "sliderThumbSize",
    )
    val colors = MaterialTheme.colorScheme
    val resolvedActive = if (enabled) activeColor else colors.onSurface.copy(alpha = 0.38f)
    val resolvedInactive = if (enabled) inactiveColor else colors.onSurface.copy(alpha = 0.12f)

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .hoverable(interactionSource)
            .height(if (compact) 24.dp else 32.dp),
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        thumb = {
            Box(
                modifier = Modifier.size(if (compact) 20.dp else 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (dragged || focused) {
                    Surface(
                        shape = CircleShape,
                        color = resolvedActive.copy(alpha = 0.18f),
                        modifier = Modifier.size(if (compact) 20.dp else 24.dp),
                    ) {}
                }
                Surface(
                    shape = CircleShape,
                    color = resolvedActive,
                    modifier = Modifier.size(thumbSize),
                ) {}
            }
        },
        track = { sliderState ->
            val range = (sliderState.valueRange.endInclusive - sliderState.valueRange.start).coerceAtLeast(0.0001f)
            val fraction = ((sliderState.value - sliderState.valueRange.start) / range).coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxWidth().height(trackHeight)) {
                val h = size.height
                val radius = CornerRadius(h / 2f, h / 2f)
                drawRoundRect(
                    color = resolvedInactive,
                    topLeft = Offset.Zero,
                    size = Size(size.width, h),
                    cornerRadius = radius,
                )
                val activeWidth = size.width * fraction
                if (activeWidth > 0f) {
                    drawRoundRect(
                        color = resolvedActive,
                        topLeft = Offset.Zero,
                        size = Size(activeWidth.coerceAtLeast(h.coerceAtMost(size.width)), h),
                        cornerRadius = radius,
                    )
                }
            }
        },
    )
}

/** Shared Material 3 choice/filter chip: tonal primary container when selected, outlined when idle. */
@Composable
fun KasaneChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        interactionSource = interactionSource,
        leadingIcon = leadingIcon,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            disabledLabelColor = MaterialTheme.colorScheme.outline,
            disabledSelectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            selectedBorderColor = Color.Transparent,
        ),
    )
}

/** Unified circular avatar for signed-in identity or guest state across Android and Desktop. */
@Composable
fun SharedAccountAvatar(
    avatarUrl: String?,
    size: Dp,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ringModifier = if (selected) {
        modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
    } else {
        modifier
    }
    if (!avatarUrl.isNullOrBlank()) {
        TrackArtwork(avatarUrl, ringModifier.size(size), shape = CircleShape)
    } else {
        Surface(
            shape = CircleShape,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            modifier = ringModifier.size(size),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(size * 0.56f),
                )
            }
        }
    }
}
