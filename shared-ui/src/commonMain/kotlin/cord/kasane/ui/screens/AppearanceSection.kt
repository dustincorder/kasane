package cord.kasane.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cord.kasane.ui.settings.AccentSource
import cord.kasane.ui.settings.ThemeMode
import cord.kasane.ui.theme.AccentPresets
import cord.kasane.ui.theme.bodySmallMuted
import kotlin.math.max
import kotlin.math.min

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        content()
    }
}

/**
 * Theme mode, colour source and accent. [automaticHint] says what "Automatic" means on this platform
 * (wallpaper colours on Android 12+, the built-in palette elsewhere); [extra] adds platform-only rows such as
 * the desktop interface scale.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceSection(
    mode: ThemeMode, source: AccentSource, accent: Color, automaticHint: String,
    onMode: (ThemeMode) -> Unit, onAuto: () -> Unit, onAccent: (Color) -> Unit,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
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
                Text(automaticHint, style = MaterialTheme.typography.bodySmallMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = source == AccentSource.AUTO, onClick = onAuto, label = { Text("Automatic") }, shape = MaterialTheme.shapes.small)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccentPresets.forEach { (name, color) ->
                        val selected = source == AccentSource.MANUAL && color.toArgbInt() == accent.toArgbInt()
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(color)
                                .clickable(role = Role.RadioButton, onClickLabel = "Use $name accent") { onAccent(color) }
                                .semantics { contentDescription = name; this.selected = selected },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(Icons.Default.Check, null, tint = if (color.luminance() > 0.5f) Color.Black else Color.White)
                        }
                    }
                }
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
            extra?.invoke(this)
        }
    }
}

private fun Color.toArgbInt(): Int =
    (((alpha * 255).toInt() and 0xFF) shl 24) or (((red * 255).toInt() and 0xFF) shl 16) or (((green * 255).toInt() and 0xFF) shl 8) or ((blue * 255).toInt() and 0xFF)

/** Hue in degrees, from RGB; replaces android.graphics.Color.colorToHSV. */
internal fun hueOf(color: Color): Float {
    val r = color.red; val g = color.green; val b = color.blue
    val hi = max(r, max(g, b)); val lo = min(r, min(g, b)); val d = hi - lo
    if (d == 0f) return 0f
    val h = when (hi) { r -> ((g - b) / d) % 6f; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f } * 60f
    return if (h < 0) h + 360f else h
}
