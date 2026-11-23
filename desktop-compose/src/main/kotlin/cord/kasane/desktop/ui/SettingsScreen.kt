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
            SettingsSection("Appearance") {
                SingleChoiceSegmentedButtonRow {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(selected = prefs.theme == mode, onClick = { app.settings.update { it.copy(theme = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) {
                            Text(mode.name.lowercase().replaceFirstChar(Char::uppercase))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Accent", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccentPresets.forEach { (_, color) ->
                        val selected = prefs.accent == (color.toArgb().toLong() and 0xFFFFFFFFL)
                        Box(Modifier.size(32.dp).clip(CircleShape).background(color)
                            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .clickable { app.settings.update { it.copy(accent = (color.toArgb().toLong() and 0xFFFFFFFFL)) } })
                    }
                }
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

