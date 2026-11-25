package cord.kasane.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import cord.kasane.ui.components.KasaneChoiceChip
import cord.kasane.ui.components.KasaneSlider
import cord.kasane.ui.settings.AccentSource
import cord.kasane.ui.settings.ThemeMode
import cord.kasane.ui.theme.AccentPresets
import cord.kasane.ui.theme.bodySmallMuted
import kotlin.math.max
import kotlin.math.min

@Composable
fun SettingsSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
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
    mode: ThemeMode,
    source: AccentSource,
    accent: Color,
    automaticHint: String,
    onMode: (ThemeMode) -> Unit,
    onAuto: () -> Unit,
    onAccent: (Color) -> Unit,
    showSectionHeader: Boolean = true,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val body: @Composable ColumnScope.() -> Unit = {
        val isPresetMatch = source == AccentSource.MANUAL && AccentPresets.any { it.second.toArgbInt() == accent.toArgbInt() }
        var customOpen by remember { mutableStateOf(source == AccentSource.MANUAL && !isPresetMatch) }

        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val inline = maxWidth >= 500.dp
                if (inline) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Theme", style = MaterialTheme.typography.titleSmall)
                            Text("Follow system appearance or stay in light or dark mode.", style = MaterialTheme.typography.bodySmallMuted)
                        }
                        ThemeSegmentedControl(mode, onMode, Modifier.width(276.dp))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Theme", style = MaterialTheme.typography.titleSmall)
                        ThemeSegmentedControl(mode, onMode, Modifier.widthIn(max = 340.dp).fillMaxWidth())
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Accent color", style = MaterialTheme.typography.titleSmall)
                    if (automaticHint.isNotBlank()) {
                        Text(automaticHint, style = MaterialTheme.typography.bodySmallMuted)
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KasaneChoiceChip(
                        label = "Automatic",
                        selected = source == AccentSource.AUTO,
                        onClick = {
                            customOpen = false
                            onAuto()
                        },
                        leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp)) },
                    )
                    KasaneChoiceChip(
                        label = "Custom",
                        selected = customOpen || (source == AccentSource.MANUAL && !isPresetMatch),
                        onClick = {
                            val next = !customOpen
                            customOpen = next
                            if (next && source == AccentSource.AUTO) {
                                onAccent(accent)
                            }
                        },
                        leadingIcon = { Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp)) },
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AccentPresets.forEach { (name, color) ->
                        val selected = source == AccentSource.MANUAL && color.toArgbInt() == accent.toArgbInt()
                        Box(
                            Modifier
                                .size(38.dp)
                                .then(
                                    if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape).padding(3.dp)
                                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                )
                                .clip(CircleShape)
                                .background(color)
                                .clickable(role = Role.RadioButton, onClickLabel = "Use $name accent") {
                                    customOpen = false
                                    onAccent(color)
                                }
                                .semantics { contentDescription = name; this.selected = selected },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                                )
                            }
                        }
                    }
                }

                if (customOpen || (source == AccentSource.MANUAL && !isPresetMatch)) {
                    var hue by remember(source) { mutableFloatStateOf(hueOf(accent)) }
                    Row(
                        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color.hsv(hue, 0.62f, 0.86f))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        )
                        KasaneSlider(
                            value = hue,
                            onValueChange = {
                                hue = it
                                onAccent(Color.hsv(it, 0.62f, 0.86f))
                            },
                            valueRange = 0f..359f,
                            activeColor = Color.hsv(hue, 0.62f, 0.86f),
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Accent hue" },
                        )
                    }
                }
            }

            if (extra != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                extra.invoke(this)
            }
        }
    }

    if (showSectionHeader) {
        SettingsSection("Appearance", content = body)
    } else {
        Column(Modifier.fillMaxWidth(), content = body)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSegmentedControl(mode: ThemeMode, onMode: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        ThemeMode.entries.forEachIndexed { index, item ->
            SegmentedButton(
                selected = mode == item,
                onClick = { onMode(item) },
                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
            ) {
                Text(
                    when (item) {
                        ThemeMode.SYSTEM -> "System"
                        ThemeMode.LIGHT -> "Light"
                        ThemeMode.DARK -> "Dark"
                    },
                    maxLines = 1,
                )
            }
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
