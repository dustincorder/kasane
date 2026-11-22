package cord.kasane.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cord.kasane.music.KasaneApplication
import cord.kasane.music.offline.OfflinePolicy
import cord.kasane.music.offline.StorageUsage
import cord.kasane.music.settings.StorageLocation
import cord.kasane.music.ui.components.KasaneChoiceChip
import kotlinx.coroutines.delay

@Composable
fun StorageSection(app: KasaneApplication, onManageSaved: () -> Unit) {
    val settings = app.settings
    val offlineOnly by settings.offlineOnly.collectAsState()
    val wifiOnly by settings.wifiOnlyDownloads.collectAsState()
    val cacheOn by settings.cacheEnabled.collectAsState()
    val limitMb by settings.cacheLimitMb.collectAsState()
    val location by settings.cacheLocation.collectAsState()
    val usage by app.offline.usage.collectAsState()
    val locationApplied = remember(location) { app.offline.locationApplied(location) }
    LaunchedEffect(Unit) { while (true) { app.offline.refreshUsage(); delay(2000) } }
    StorageContent(offlineOnly, wifiOnly, cacheOn, limitMb, location, locationApplied, usage,
        settings::setOfflineOnly, settings::setWifiOnlyDownloads, settings::setCacheEnabled, settings::setCacheLimitMb,
        settings::setCacheLocation, app.offline::clearTemporaryCache, onManageSaved)
}

@Composable
fun StorageContent(
    offlineOnly: Boolean, wifiOnly: Boolean, cacheOn: Boolean, limitMb: Int, location: StorageLocation, locationApplied: Boolean,
    usage: StorageUsage,
    onOfflineOnly: (Boolean) -> Unit, onWifiOnly: (Boolean) -> Unit, onCacheOn: (Boolean) -> Unit, onLimit: (Int) -> Unit,
    onLocation: (StorageLocation) -> Unit, onClearTemp: () -> Unit, onManageSaved: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    SettingsSection("Offline & storage") {
        SettingsSwitchRow("Offline Only", "Never use the network. Only saved music and fully cached songs can play.", offlineOnly, onChange = onOfflineOnly)
        SettingsSwitchRow("Download on Wi-Fi only", "Saved music waits for an unmetered connection.", wifiOnly, onChange = onWifiOnly)
        Text("Kasane keeps three kinds of audio apart. Local files are your own files (not supported on Android yet). The temporary cache holds songs you streamed so they replay without the network; it is managed automatically. Saved music is what you chose to keep; it is never removed automatically, and only tracks that YouTube delivers as a plain audio file can be saved.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        ListItem(
            headlineContent = { Text("Saved music", style = MaterialTheme.typography.titleSmall) },
            supportingContent = { Text("${usage.savedCount} tracks · ${OfflinePolicy.formatBytes(usage.savedBytes)}") },
            trailingContent = { TextButton(onClick = onManageSaved) { Text("Manage") } },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
        SettingsSwitchRow("Cache streamed songs", "Keep recently played audio for replay. Removes the least recently used songs first.", cacheOn, onChange = onCacheOn)
        val steps = OfflinePolicy.CACHE_STEPS_MB
        val index = steps.indexOf(OfflinePolicy.nearestCacheStep(limitMb)).coerceAtLeast(0)
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Temporary cache: ${OfflinePolicy.formatBytes(usage.tempBytes)} of ${OfflinePolicy.formatBytes(limitMb * 1024L * 1024L)}",
                style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(progress = { if (usage.tempLimitBytes > 0) (usage.tempBytes.toFloat() / usage.tempLimitBytes).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Slider(value = index.toFloat(), onValueChange = { onLimit(steps[it.toInt().coerceIn(0, steps.lastIndex)]) },
                valueRange = 0f..steps.lastIndex.toFloat(), steps = steps.size - 2, enabled = cacheOn)
            Text("Size limit: ${OfflinePolicy.formatBytes(steps[index] * 1024L * 1024L)}", style = MaterialTheme.typography.labelMedium)
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cache location", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KasaneChoiceChip("Internal storage", location == StorageLocation.INTERNAL, { onLocation(StorageLocation.INTERNAL) })
                KasaneChoiceChip("External app storage", location == StorageLocation.EXTERNAL, { onLocation(StorageLocation.EXTERNAL) })
            }
            Text(if (locationApplied) "Saved music is always kept in Kasane's private internal storage."
                else "Takes effect the next time Kasane starts. Saved music stays in internal storage.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { confirmClear = true }, shape = MaterialTheme.shapes.small) { Text("Clear temporary cache") }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear temporary cache?") },
        text = { Text("Streamed songs will need the network again. Saved music is not affected.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClearTemp() }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } })
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 390, heightDp = 900)
@Composable
fun StoragePreview() {
    cord.kasane.music.ui.theme.KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column {
                StorageContent(false, true, true, 1024, StorageLocation.INTERNAL, true, StorageUsage(84_000_000, 312_000_000, 1L shl 30, 21),
                    {}, {}, {}, {}, {}, {}, {})
            }
        }
    }
}
