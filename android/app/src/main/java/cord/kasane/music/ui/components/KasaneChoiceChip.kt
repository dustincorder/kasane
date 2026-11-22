package cord.kasane.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cord.kasane.music.ui.theme.*

/** Selected = tonal primary container; unselected = outlined. Uses the active color scheme (incl. Material You). */
@Composable
fun KasaneChoiceChip(label: String, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource? = null) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, modifier = modifier, interactionSource = interactionSource,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            disabledLabelColor = MaterialTheme.colorScheme.outline,
            disabledSelectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = FilterChipDefaults.filterChipBorder(enabled, selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            selectedBorderColor = androidx.compose.ui.graphics.Color.Transparent))
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 390)
@Composable
fun ChoiceStatesPreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KasaneChoiceChip("Normal", false, {})
                KasaneChoiceChip("Selected", true, {})
                KasaneChoiceChip("Disabled", false, {}, enabled = false)
                KasaneChoiceChip("Disabled selected", true, {}, enabled = false)
                LinearProgressIndicator()
            }
        }
    }
}
