package cord.kasane.music.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cord.kasane.music.ui.theme.*

/** A selected choice uses the same Rose accent as Home, not Material's dark container. */
@Composable
fun KasaneChoiceChip(label: String, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource? = null) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, modifier = modifier, interactionSource = interactionSource,
        label = { Text(label, color = if (!enabled) Gray500 else if (selected) Gray950 else Gray200) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Gray900, labelColor = Gray200,
            selectedContainerColor = AccentRose, selectedLabelColor = Gray950,
            disabledContainerColor = Gray850, disabledSelectedContainerColor = Gray850,
            disabledLabelColor = Gray500))
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 390)
@Composable
fun ChoiceStatesPreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        Surface(color = Gray950) {
            androidx.compose.foundation.layout.Column(
                Modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(androidx.compose.ui.unit.Dp(8f))) {
                val pressed = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    pressed.emit(androidx.compose.foundation.interaction.PressInteraction.Press(androidx.compose.ui.geometry.Offset.Zero))
                }
                KasaneChoiceChip("Pressed selected", true, {}, interactionSource = pressed)
                KasaneChoiceChip("Normal", false, {})
                KasaneChoiceChip("Selected · Home Rose", true, {})
                KasaneChoiceChip("Disabled", false, {}, enabled = false)
                KasaneChoiceChip("Disabled selected", true, {}, enabled = false)
                LinearProgressIndicator(color = AccentRose)
            }
        }
    }
}
