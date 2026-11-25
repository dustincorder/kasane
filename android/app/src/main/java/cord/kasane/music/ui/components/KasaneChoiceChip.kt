package cord.kasane.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cord.kasane.music.ui.theme.*
import cord.kasane.ui.theme.*

/** Selected = tonal primary container; unselected = outlined. Delegates to shared (:ui) implementation. */
@Composable
fun KasaneChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource? = null,
) {
    cord.kasane.ui.components.KasaneChoiceChip(
        label = label,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
    )
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
