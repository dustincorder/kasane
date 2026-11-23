package cord.kasane.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cord.kasane.music.KasaneApplication
import cord.kasane.music.auth.AccountIdentity
import cord.kasane.music.auth.AccountState
import cord.kasane.music.ui.components.TrackArtwork
import cord.kasane.music.settings.AccentSource
import cord.kasane.music.settings.ThemeMode
import androidx.compose.ui.graphics.toArgb
import cord.kasane.music.ui.theme.*
import cord.kasane.ui.theme.*

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
    val mode by app.settings.themeMode.collectAsState()
    val source by app.settings.accentSource.collectAsState()
    val accent by app.settings.accentColor.collectAsState()
    SettingsContent(state, onBack, onSignIn, onSignOut, onRetry, onCancel, onExportLocalData,
        mode = mode, source = source, accent = androidx.compose.ui.graphics.Color(accent),
        onMode = app.settings::setThemeMode, onAuto = { app.settings.setAccentSource(AccentSource.AUTO) },
        onAccent = { app.settings.setAccentColor(it.toArgb()) },
        extraSections = {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsSection("Devices") {
                val connectOn by app.settings.connectEnabled.collectAsState()
                ListItem(
                    headlineContent = { Text("Kasane Connect", style = MaterialTheme.typography.titleSmall) },
                    supportingContent = { Text(if (connectOn) "On · pair and manage your devices" else "Off",
                        style = MaterialTheme.typography.bodySmall) },
                    trailingContent = { Switch(checked = connectOn, onCheckedChange = app.settings::setConnectEnabled) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable(onClick = onOpenConnect),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StorageSection(app, onManageSaved)
        })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: AccountState,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onExportLocalData: () -> Unit,
    mode: ThemeMode, source: AccentSource, accent: androidx.compose.ui.graphics.Color,
    onMode: (ThemeMode) -> Unit, onAuto: () -> Unit, onAccent: (androidx.compose.ui.graphics.Color) -> Unit,
    extraSections: @Composable ColumnScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SettingsSection("Account") {
                val account = (state as? AccountState.SignedIn)?.identity
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (account?.avatar != null) TrackArtwork(account.avatar, Modifier.size(48.dp), shape = androidx.compose.foundation.shape.CircleShape)
                    else Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(48.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                    }
                    Column {
                        Text(account?.name ?: "Listening as a guest", style = MaterialTheme.typography.titleMedium)
                        Text(if (account == null) "Search, explore and play without an account." else "YouTube Music channel connected",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (state) {
                        AccountState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        AccountState.SigningIn -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Waiting for you to finish sign in on YouTube Music…", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onCancel) { Text("Cancel") }
                        }
                        AccountState.Expired -> Text("Your YouTube Music session expired or could not be restored. Sign in again to reconnect this channel.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        is AccountState.Unavailable -> {
                            Text(state.message, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onRetry) { Text("Check saved session") }
                        }
                        else -> Unit
                    }
                    if (account == null) {
                        if (state != AccountState.SigningIn) Button(onClick = onSignIn, enabled = state != AccountState.Checking,
                            shape = MaterialTheme.shapes.small) { Text("Sign in to YouTube Music") }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onSignIn, shape = MaterialTheme.shapes.small) { Text("Switch account") }
                        OutlinedButton(onClick = onSignOut, shape = MaterialTheme.shapes.small) { Text("Sign out") }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AppearanceSection(mode, source, accent, onMode, onAuto, onAccent)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsSection("Playback") {
                val context = androidx.compose.ui.platform.LocalContext.current
                val preferences = remember(context) { context.getSharedPreferences("playback", android.content.Context.MODE_PRIVATE) }
                var autoplay by remember { mutableStateOf(preferences.getBoolean("autoplay", true)) }
                SettingsSwitchRow("Autoplay", "Build radio for single songs and continue after collections.", autoplay) {
                    autoplay = it; preferences.edit().putBoolean("autoplay", it).apply()
                }
            }
            extraSections()
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsSection("Your data") {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Favorites and listening history belong to this device. Signing out does not remove them. YouTube Music collections stay separate from this local library.",
                        style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = onExportLocalData, shape = MaterialTheme.shapes.small) { Text("Export local data") }
                    Text("Exports local favorites, listening history and playback settings as JSON. Account cookies and session keys are never included.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider(Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SettingsSection("About") {
                Text("Kasane ${cord.kasane.music.BuildConfig.VERSION_NAME} · Android", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun SettingsGuestPreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            SettingsContent(AccountState.Guest, {}, {}, {}, {}, {}, {}, ThemeMode.DARK, AccentSource.AUTO, cord.kasane.ui.theme.FallbackSeed, {}, {}, {})
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun SettingsSignedInPreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            SettingsContent(AccountState.SignedIn(AccountIdentity("Kasane Listener", null, "UCpreview")), {}, {}, {}, {}, {}, {}, ThemeMode.LIGHT, AccentSource.MANUAL, cord.kasane.ui.theme.FallbackSeed, {}, {}, {})
        }
    }
}
