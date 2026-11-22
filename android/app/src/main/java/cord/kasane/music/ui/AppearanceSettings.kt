package cord.kasane.music.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cord.kasane.music.settings.AccentSource
import cord.kasane.music.settings.ThemeMode
import cord.kasane.music.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSection(
    mode: ThemeMode, source: AccentSource, accent: Color,
    onMode: (ThemeMode) -> Unit, onAuto: () -> Unit, onAccent: (Color) -> Unit,
) {
    val wallpaperColors = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    SettingsSection("Appearance") {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, item ->
                        SegmentedButton(selected = mode == item, onClick = { onMode(item) },
                            shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)) {
                            Text(when (item) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" })
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Colors", style = MaterialTheme.typography.titleSmall)
                Text(if (wallpaperColors) "Automatic follows your wallpaper (Material You). Pick an accent to override it."
                    else "Automatic uses Kasane's built-in palette on this Android version. Pick an accent to override it.",
                    style = MaterialTheme.typography.bodySmallMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = source == AccentSource.AUTO, onClick = onAuto, label = { Text("Automatic") },
                        shape = MaterialTheme.shapes.small)
                }
                LazyRowSwatches(source, accent, onAccent)
                var custom by remember { mutableStateOf(false) }
                TextButton(onClick = { custom = !custom }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (custom) "Hide custom accent" else "Custom accent…")
                }
                if (custom) {
                    var hue by remember { mutableFloatStateOf(hueOf(accent)) }
                    Slider(value = hue, onValueChange = { hue = it; onAccent(Color.hsv(it, 0.62f, 0.86f)) }, valueRange = 0f..359f,
                        modifier = Modifier.semantics { contentDescription = "Accent hue" })
                }
            }
        }
    }
}

private fun hueOf(color: Color): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    return hsv[0]
}

@Composable
private fun LazyRowSwatches(source: AccentSource, accent: Color, onAccent: (Color) -> Unit) {
    FlowRowCompat {
        AccentPresets.forEach { (name, color) ->
            val selected = source == AccentSource.MANUAL && color.toArgb() == accent.toArgb()
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(color)
                    .clickable(role = Role.RadioButton, onClickLabel = "Use $name accent") { onAccent(color) }
                    .semantics { contentDescription = name; this.selected = selected },
                contentAlignment = Alignment.Center
            ) {
                if (selected) Icon(Icons.Default.Check, null, tint = if (color.luminance() > 0.5f) Color.Black else Color.White)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Preview(showBackground = true, widthDp = 390)
@Composable
fun AppearancePreview() {
    KasaneTheme(ThemeMode.DARK, AccentSource.MANUAL, AccentPresets[1].second) {
        Surface(color = MaterialTheme.colorScheme.background) { AppearanceSection(ThemeMode.DARK, AccentSource.MANUAL, AccentPresets[1].second, {}, {}, {}) }
    }
}

@Preview(showBackground = true, widthDp = 390)
@Composable
fun AppearanceLightPreview() {
    KasaneTheme(ThemeMode.LIGHT, AccentSource.MANUAL, AccentPresets[4].second) {
        Surface(color = MaterialTheme.colorScheme.background) { AppearanceSection(ThemeMode.LIGHT, AccentSource.MANUAL, AccentPresets[4].second, {}, {}, {}) }
    }
}
