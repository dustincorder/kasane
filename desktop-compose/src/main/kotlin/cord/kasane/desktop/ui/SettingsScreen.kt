package cord.kasane.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cord.kasane.desktop.DesktopApp
import cord.kasane.desktop.offline.DesktopOffline
import cord.kasane.music.auth.AccountState
import cord.kasane.ui.components.KasaneChoiceChip
import cord.kasane.ui.components.KasaneSlider
import cord.kasane.ui.screens.AppearanceSection
import cord.kasane.ui.screens.SettingsSection
import cord.kasane.ui.settings.AccentSource
import kotlin.math.roundToInt

private val CachePresetsMb = listOf(512, 1024, 2048, 4096, 8192)

private fun formatCachePreset(mb: Int): String =
    if (mb >= 1024 && mb % 1024 == 0) "${mb / 1024} GB" else "$mb MB"

/**
 * Flat, grouped Material 3 settings column (max 720dp) per UX_GUIDELINES.md §5 & SCREEN_SPEC.md §8:
 * Account -> Appearance -> Library & storage -> Devices -> About.
 * No card walls, no empty stub categories, no duplicate volume control.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    app: DesktopApp,
    onConnect: () -> Unit,
    onAccount: () -> Unit,
    onManageSaved: (() -> Unit)? = null,
) {
    val prefs by app.settings.prefs.collectAsState()
    val account by app.account.state.collectAsState()
    val usage by app.offline.usage.collectAsState()
    val records by app.offline.records.collectAsState()
    val activeDownloads = records.count { it.state == "downloading" || it.state == "queued" }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionTitle("Settings")

        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = 4.dp, end = 24.dp, bottom = 32.dp)) {
            SettingsSection("Account") {
                val signedIn = account as? AccountState.SignedIn
                ListItem(
                    headlineContent = {
                        Text(
                            signedIn?.identity?.name ?: "Listening as a guest",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    },
                    supportingContent = {
                        Text(
                            if (signedIn != null) "YouTube Music channel connected" else "Sign in to sync your liked songs, playlists, and history",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = { AccountAvatar(account, 40.dp) },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onAccount),
                )
            }

            SectionRule()

            AppearanceSection(
                mode = prefs.theme,
                source = prefs.accentSource,
                accent = Color(prefs.accent),
                automaticHint = "Automatic uses Kasane Blue. Choose a preset or custom hue to personalize.",
                onMode = { m -> app.settings.update { it.copy(theme = m) } },
                onAuto = { app.settings.update { it.copy(accentSource = AccentSource.AUTO) } },
                onAccent = { c -> app.settings.update { it.copy(accentSource = AccentSource.MANUAL, accent = c.toArgb().toLong() and 0xFFFFFFFFL) } },
                extra = { ScaleControl(app, prefs.uiScale) },
            )

            SectionRule()

            SettingsSection("Library & storage") {
                val fmt = DesktopOffline::formatBytes
                ListItem(
                    headlineContent = { Text("Saved music", style = MaterialTheme.typography.titleSmall) },
                    supportingContent = {
                        Text(
                            buildString {
                                append("${usage.savedCount} saved · ${fmt(usage.savedBytes)}")
                                if (activeDownloads > 0) append(" · $activeDownloads downloading")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.DownloadDone, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingContent = if (onManageSaved != null) {
                        {
                            FilledTonalButton(onClick = onManageSaved, shape = MaterialTheme.shapes.small) {
                                Text("Manage")
                            }
                        }
                    } else null,
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )

                SettingSwitch(
                    title = "Offline Only",
                    supporting = "Block network requests and play only saved and cached music",
                    checked = prefs.offlineOnly,
                ) { app.settings.update { it.copy(offlineOnly = !prefs.offlineOnly) } }

                SettingSwitch(
                    title = "Cache streamed music",
                    supporting = "Keep recently played songs so they replay without the network",
                    checked = prefs.cacheEnabled,
                ) { app.settings.update { it.copy(cacheEnabled = !prefs.cacheEnabled) } }

                SettingSwitch(
                    title = "Save release details and covers",
                    supporting = "Release date, genre and official artwork via MusicBrainz for saved songs",
                    checked = prefs.enrichMetadata,
                ) { app.settings.update { it.copy(enrichMetadata = !prefs.enrichMetadata) } }

                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Temporary cache", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(
                            "${fmt(usage.tempBytes)} of ${fmt(usage.tempLimitBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(
                        progress = {
                            if (usage.tempLimitBytes > 0) (usage.tempBytes.toFloat() / usage.tempLimitBytes).coerceIn(0f, 1f) else 0f
                        },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(MaterialTheme.shapes.extraSmall),
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                    if (prefs.cacheEnabled) {
                        Text(
                            "Maximum cache size",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CachePresetsMb.forEach { mb ->
                                KasaneChoiceChip(
                                    label = formatCachePreset(mb),
                                    selected = prefs.cacheLimitMb == mb,
                                    onClick = { app.settings.update { it.copy(cacheLimitMb = mb) } },
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { app.offline.clearTemporaryCache(); app.offline.refreshUsage() },
                            shape = MaterialTheme.shapes.small,
                        ) { Text("Clear cache") }
                        if (activeDownloads > 0) {
                            OutlinedButton(
                                onClick = { app.offline.cancelAll() },
                                shape = MaterialTheme.shapes.small,
                            ) { Text("Cancel downloads") }
                        }
                    }
                }
            }

            if (app.connect != null || app.connectError != null) {
                SectionRule()
                SettingsSection("Devices") {
                    ListItem(
                        headlineContent = { Text("Kasane Connect", style = MaterialTheme.typography.titleSmall) },
                        supportingContent = {
                            Text(
                                when {
                                    app.connect == null -> "Unavailable"
                                    prefs.connectEnabled -> "On · ${app.connect.identity.deviceName}"
                                    else -> "Off"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Outlined.Devices, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onConnect),
                    )
                }
            }

            SectionRule()

            SettingsSection("About") {
                ListItem(
                    headlineContent = { Text("Kasane", style = MaterialTheme.typography.titleSmall) },
                    supportingContent = {
                        Text(
                            "0.1.0 · Desktop",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun SectionRule() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SettingSwitch(title: String, supporting: String, checked: Boolean, onToggle: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = {
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { Switch(checked, onCheckedChange = { onToggle() }) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onToggle),
    )
}

/** Interface scale: a multiplier over the detected display scale, applied to the whole window through the density. */
@Composable
private fun ScaleControl(app: DesktopApp, scale: Float) {
    var value by remember(scale) { mutableStateOf(scale) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Interface scale", style = MaterialTheme.typography.titleSmall)
            Text(
                "Ctrl + / Ctrl − to adjust · Ctrl + 0 to reset",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            KasaneSlider(
                value = value,
                onValueChange = { value = ((it * 20).roundToInt() / 20f) },
                valueRange = 0.75f..2.5f,
                onValueChangeFinished = { app.settings.update { it.copy(uiScale = value) } },
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.End,
                modifier = Modifier.width(48.dp),
            )
            TextButton(
                onClick = { app.settings.update { it.copy(uiScale = 1f) } },
                enabled = scale != 1f,
            ) { Text("Reset") }
        }
    }
}
