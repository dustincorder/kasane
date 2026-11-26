package cord.kasane.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cord.kasane.music.KasaneApplication
import cord.kasane.music.auth.AccountIdentity
import cord.kasane.music.auth.AccountState
import cord.kasane.music.offline.OfflinePolicy
import cord.kasane.music.settings.AccentSource
import cord.kasane.music.settings.ThemeMode
import androidx.compose.ui.graphics.toArgb
import cord.kasane.music.ui.theme.*
import cord.kasane.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        content()
    }
}

@Composable
fun SettingsSwitchRow(title: String, summary: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = { Text(summary, style = MaterialTheme.typography.bodySmall) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}

internal enum class SettingsDestination(val title: String, val icon: ImageVector) {
    GENERAL("General", Icons.Outlined.Tune),
    APPEARANCE("Appearance", Icons.Outlined.Palette),
    PLAYBACK("Playback", Icons.Outlined.PlayCircleOutline),
    STORAGE("Downloads & storage", Icons.Outlined.Download),
    PRIVACY("Data & privacy", Icons.Outlined.Lock),
    ABOUT("About", Icons.Outlined.Info),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSubScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: KasaneApplication,
    state: AccountState,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onExportLocalData: () -> Unit,
    onManageSaved: () -> Unit,
    onOpenConnect: () -> Unit = {},
) {
    var destination by remember { mutableStateOf<SettingsDestination?>(null) }
    val scope = rememberCoroutineScope()
    when (destination) {
        SettingsDestination.GENERAL -> SettingsSubScreen("General", { destination = null }) {
            SettingsSection("Account") { AccountBlock(state, onSignIn, onSignOut, onRetry, onCancel) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsSection("Devices") {
                val connectOn by app.settings.connectEnabled.collectAsState()
                ListItem(
                    headlineContent = { Text("Kasane Connect", style = MaterialTheme.typography.titleSmall) },
                    supportingContent = { Text(if (connectOn) "On" else "Off", style = MaterialTheme.typography.bodySmallMuted) },
                    leadingContent = { Icon(Icons.Outlined.Devices, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable(onClickLabel = "Open devices", onClick = onOpenConnect),
                )
            }
        }
        SettingsDestination.APPEARANCE -> SettingsSubScreen("Appearance", { destination = null }) {
            val mode by app.settings.themeMode.collectAsState()
            val source by app.settings.accentSource.collectAsState()
            val accent by app.settings.accentColor.collectAsState()
            AppearanceSection(mode, source, androidx.compose.ui.graphics.Color(accent),
                onMode = app.settings::setThemeMode, onAuto = { app.settings.setAccentSource(AccentSource.AUTO) },
                onAccent = { app.settings.setAccentColor(it.toArgb()) })
        }
        SettingsDestination.PLAYBACK -> SettingsSubScreen("Playback", { destination = null }) {
            val autoplay by app.settings.autoplayFlow.collectAsState()
            SettingsSection("Playback") {
                SettingsSwitchRow("Autoplay", "Build radio for single songs and continue after collections.", autoplay) {
                    app.settings.setAutoplay(it)
                }
            }
        }
        SettingsDestination.STORAGE -> SettingsSubScreen("Downloads & storage", { destination = null }) {
            StorageSection(app, onManageSaved)
        }
        SettingsDestination.PRIVACY -> SettingsSubScreen("Data & privacy", { destination = null }) {
            var confirmClear by remember { mutableStateOf(false) }
            val signedIn = state is AccountState.SignedIn
            SettingsSection("Personalization") {
                val historyOn by (if (signedIn) app.settings.saveListeningHistory else app.settings.guestSaveHistory).collectAsState()
                ListItem(
                    headlineContent = { Text("Save listening history", style = MaterialTheme.typography.titleSmall) },
                    supportingContent = { Text(if (signedIn) "Feeds History and Home picks"
                        else "Off for guests by default", style = MaterialTheme.typography.bodySmallMuted) },
                    trailingContent = { Switch(checked = historyOn, onCheckedChange = {
                        if (signedIn) app.settings.setSaveListeningHistory(it) else app.settings.setGuestSaveHistory(it) }) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable(onClickLabel = "Save listening history") {
                        if (signedIn) app.settings.setSaveListeningHistory(!historyOn)
                        else app.settings.setGuestSaveHistory(!historyOn) })
            }
            SettingsSection("Local data") {
                ListItem(
                    headlineContent = { Text("Export local data", style = MaterialTheme.typography.titleSmall) },
                    supportingContent = { Text("Favorites, history and settings as JSON", style = MaterialTheme.typography.bodySmallMuted) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable(onClickLabel = "Export local data", onClick = onExportLocalData))
                ListItem(
                    headlineContent = { Text("Clear listening history", style = MaterialTheme.typography.titleSmall) },
                    supportingContent = { Text("Also resets Home picks built from your plays", style = MaterialTheme.typography.bodySmallMuted) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable(onClickLabel = "Clear listening history") { confirmClear = true })
            }
            if (confirmClear) AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("Clear listening history?") },
                text = { Text("History and the picks Home built from it are removed. Favorites and saved music are not affected.") },
                confirmButton = { TextButton(onClick = { confirmClear = false; scope.launch {
                    app.database.trackDao().clearHistory()
                    app.cachedCatalog.evict(cord.kasane.music.data.CatalogCache.accountScope(
                        app.accountRepository.session(), (state as? AccountState.SignedIn)?.identity),
                        "browse:FEmusic_history")
                } }) { Text("Clear") } },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } })
        }
        SettingsDestination.ABOUT -> SettingsSubScreen("About", { destination = null }) {
            SettingsSection("Kasane") { UpdateSection(app.updates) }
        }
        null -> SettingsRoot(
            account = (state as? AccountState.SignedIn)?.identity,
            appearanceSummary = run {
                val mode by app.settings.themeMode.collectAsState()
                val source by app.settings.accentSource.collectAsState()
                "${mode.name.lowercase().replaceFirstChar { it.uppercase() }} · ${if (source == AccentSource.AUTO) "automatic accent" else "custom accent"}"
            },
            storageSummary = run {
                val usage by app.offline.usage.collectAsState()
                "${plural(usage.savedCount, "song")} saved · ${OfflinePolicy.formatBytes(usage.savedBytes + usage.tempBytes)}"
            },
            onBack = onBack,
            onOpen = { destination = it })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsRoot(account: AccountIdentity?, appearanceSummary: String, storageSummary: String,
    onBack: () -> Unit, onOpen: (SettingsDestination) -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            val summaries = mapOf(
                SettingsDestination.GENERAL to (account?.name ?: "Guest"),
                SettingsDestination.APPEARANCE to appearanceSummary,
                SettingsDestination.PLAYBACK to "Autoplay and radio",
                SettingsDestination.STORAGE to storageSummary,
                SettingsDestination.PRIVACY to "Export data and listening history",
                SettingsDestination.ABOUT to cord.kasane.music.BuildConfig.VERSION_NAME)
            SettingsDestination.entries.forEach { dest ->
                val summary = summaries.getValue(dest)
                ListItem(
                    headlineContent = { Text(dest.title, style = MaterialTheme.typography.titleSmall) },
                    supportingContent = if (summary.isNotBlank()) ({ Text(summary, style = MaterialTheme.typography.bodySmallMuted) }) else null,
                    leadingContent = { Icon(dest.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.heightIn(min = 56.dp).clickable(onClickLabel = "Open ${dest.title}") { onOpen(dest) })
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun SettingsRootGuestPreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            SettingsRoot(null, "Dark · automatic accent", "21 songs saved · 340 MB", {}, {})
        }
    }
}
