package cord.kasane.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import cord.kasane.ui.screens.AppearanceSection
import cord.kasane.ui.settings.AccentSource
import cord.kasane.ui.theme.bodySmallMuted
import kotlin.math.roundToInt
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import cord.kasane.desktop.DesktopApp
import cord.kasane.desktop.dataDir

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
fun SettingsScreen(app: DesktopApp, connectSection: @Composable () -> Unit) {
    val prefs by app.settings.prefs.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle("Settings")
        Column(Modifier.padding(horizontal = 24.dp).widthIn(max = 720.dp)) {
            Column(Modifier.padding(horizontal = 0.dp)) {
                AppearanceSection(prefs.theme, prefs.accentSource, Color(prefs.accent),
                    automaticHint = "Automatic uses Kasane's built-in palette on desktop. Pick an accent to override it.",
                    onMode = { m -> app.settings.update { it.copy(theme = m) } },
                    onAuto = { app.settings.update { it.copy(accentSource = AccentSource.AUTO) } },
                    onAccent = { c -> app.settings.update { it.copy(accentSource = AccentSource.MANUAL, accent = c.toArgb().toLong() and 0xFFFFFFFFL) } },
                    extra = { ScaleControl(app, prefs.uiScale) })
            }
            SettingsSection("Account") {
                Text("Guest", style = MaterialTheme.typography.titleSmall)
                Text("Home, search and playback use YouTube Music without an account. Signing in (your playlists, likes synced with YouTube, " +
                    "age-restricted tracks) is not implemented in this prototype yet; the Tauri client and Android app have it.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsSection("Kasane Connect") { connectSection() }
            SettingsSection("Audio") {
                Text("Decoded by mpv (${app.mpvVersion}). Streams are read from YouTube in bounded ranges, like on Android.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsSection("Storage") {
                Text(dataDir.absolutePath, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Liked songs, history and settings of this prototype. Separate from the Tauri client's data.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


/** Interface scale: a multiplier over the detected display scale, applied to the whole window through the density. */
@Composable
private fun ScaleControl(app: DesktopApp, scale: Float) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var value by remember(scale) { mutableStateOf(scale) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Interface scale", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Slider(value, { value = ((it * 20).roundToInt() / 20f) }, valueRange = 0.75f..2.5f,
                onValueChangeFinished = { app.settings.update { it.copy(uiScale = value) } }, modifier = Modifier.weight(1f))
            Text("${(value * 100).roundToInt()} %", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
            TextButton(onClick = { app.settings.update { it.copy(uiScale = 1f) } }, enabled = scale != 1f) { Text("Reset") }
        }
        Text("Total scale in effect: ${(density.density * 100).roundToInt()} %. Ctrl + / Ctrl - / Ctrl 0 also change it.",
            style = MaterialTheme.typography.bodySmallMuted)
    }
}
