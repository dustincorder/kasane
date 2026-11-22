package cord.kasane.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cord.kasane.music.KasaneApplication
import cord.kasane.music.offline.OfflinePolicy
import cord.kasane.music.offline.StorageUsage
import cord.kasane.music.settings.StorageLocation
import cord.kasane.music.ui.components.KasaneChoiceChip
import cord.kasane.music.ui.theme.*
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StorageContent(
    offlineOnly: Boolean, wifiOnly: Boolean, cacheOn: Boolean, limitMb: Int, location: StorageLocation, locationApplied: Boolean,
    usage: StorageUsage,
    onOfflineOnly: (Boolean) -> Unit, onWifiOnly: (Boolean) -> Unit, onCacheOn: (Boolean) -> Unit, onLimit: (Int) -> Unit,
    onLocation: (StorageLocation) -> Unit, onClearTemp: () -> Unit, onManageSaved: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    SettingsSection("Storage") {
        ListItem(
            headlineContent = { Text("Saved music", style = MaterialTheme.typography.titleSmall) },
            supportingContent = { Text("${usage.savedCount} ${if (usage.savedCount == 1) "song" else "songs"} · ${OfflinePolicy.formatBytes(usage.savedBytes)}") },
            trailingContent = { FilledTonalButton(onClick = onManageSaved, shape = MaterialTheme.shapes.small) { Text("Manage") } },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Temporary cache", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${OfflinePolicy.formatBytes(usage.tempBytes)} of ${OfflinePolicy.limitLabel(limitMb)}", style = MaterialTheme.typography.bodySmallMuted)
            }
            LinearProgressIndicator(progress = { if (usage.tempLimitBytes > 0) (usage.tempBytes.toFloat() / usage.tempLimitBytes).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(), gapSize = 0.dp, drawStopIndicator = {})
            Text("Maximum size", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OfflinePolicy.CACHE_PRESETS_MB.forEach { mb -> KasaneChoiceChip(OfflinePolicy.limitLabel(mb), limitMb == mb, { onLimit(mb) }, enabled = cacheOn) }
                val isCustom = limitMb !in OfflinePolicy.CACHE_PRESETS_MB
                KasaneChoiceChip(if (isCustom) "Custom · ${OfflinePolicy.limitLabel(limitMb)}" else "Custom…", isCustom, { custom = true }, enabled = cacheOn)
            }
            OutlinedButton(onClick = { confirmClear = true }, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(top = 4.dp)) { Text("Clear temporary cache") }
        }
        SettingsSwitchRow("Cache streamed songs", "Replay recent songs without the network", cacheOn, onChange = onCacheOn)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cache location", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(location == StorageLocation.INTERNAL, { onLocation(StorageLocation.INTERNAL) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Internal") }
                SegmentedButton(location == StorageLocation.EXTERNAL, { onLocation(StorageLocation.EXTERNAL) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("External app storage") }
            }
            if (!locationApplied) Text("Applies the next time Kasane starts.", style = MaterialTheme.typography.bodySmallMuted)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SettingsSection("Offline") {
        SettingsSwitchRow("Offline Only", "Play only saved and cached music", offlineOnly, onChange = onOfflineOnly)
        SettingsSwitchRow("Download on Wi-Fi only", "Wait for an unmetered connection", wifiOnly, onChange = onWifiOnly)
        TextButton(onClick = { help = !help }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(if (help) "Hide details" else "How offline music works") }
        AnimatedVisibility(help) {
            Text("Saved music is what you chose to keep. It stays until you remove it, and only tracks YouTube delivers as a plain audio file can be saved. " +
                "The temporary cache holds songs you streamed and removes the least recently played first when it is full; clearing it never touches saved music. " +
                "Saved music always lives in Kasane's private internal storage. Local files are not supported on Android yet.",
                style = MaterialTheme.typography.bodySmallMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear temporary cache?") },
        text = { Text("Streamed songs will need the network again. Saved music is not affected.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClearTemp() }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } })
    if (custom) CustomLimitDialog(limitMb, { custom = false }) { onLimit(it); custom = false }
}

@Composable
private fun CustomLimitDialog(currentMb: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var gigabytes by remember { mutableStateOf(currentMb >= 1024) }
    var text by remember { mutableStateOf(if (gigabytes) "%.1f".format(currentMb / 1024.0).removeSuffix(".0") else currentMb.toString()) }
    val parsed = OfflinePolicy.customLimitMb(text, gigabytes)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom cache limit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(text, { text = it }, singleLine = true, isError = parsed == null, label = { Text("Size") },
                    supportingText = { Text(if (parsed == null) "Enter 128 MB to 64 GB" else "Limit: ${OfflinePolicy.limitLabel(parsed)}") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(!gigabytes, { gigabytes = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("MB") }
                    SegmentedButton(gigabytes, { gigabytes = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("GB") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text("Set limit") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Preview(showBackground = true, widthDp = 412, heightDp = 900)
@Composable
fun StoragePreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column {
                StorageContent(false, true, true, 2048, StorageLocation.INTERNAL, true, StorageUsage(84_000_000, 312_000_000, 2L shl 30, 21),
                    {}, {}, {}, {}, {}, {}, {})
            }
        }
    }
}
