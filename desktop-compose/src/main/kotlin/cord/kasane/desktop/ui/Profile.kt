package cord.kasane.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cord.kasane.desktop.DesktopApp
import cord.kasane.music.auth.AccountState
import cord.kasane.ui.components.SharedAccountAvatar
import kotlinx.coroutines.launch

@Composable
internal fun AccountAvatar(state: AccountState, size: Dp, selected: Boolean = false) {
    val avatar = (state as? AccountState.SignedIn)?.identity?.avatar?.takeIf(String::isNotBlank)
    SharedAccountAvatar(avatarUrl = avatar, size = size, selected = selected)
}

@Composable
private fun ProfileMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        colors = MenuDefaults.itemColors(
            textColor = colors.onSurface,
            leadingIconColor = colors.onSurfaceVariant,
        ),
        onClick = onClick,
    )
}

/**
 * Rail header avatar with a Material 3 profile menu.
 * Header avatar center (10 + 16 = 26dp) and label edge (10 + 32 + 8 = 50dp) align with
 * DropdownMenuItem icon centers (14 + 12 = 26dp) and labels (14 + 24 + 12 = 50dp).
 */
@Composable
fun ProfileMenu(
    app: DesktopApp,
    selected: Boolean = false,
    forceOpen: Boolean = false,
    onAccount: () -> Unit,
    onHistory: () -> Unit,
    onDownloads: () -> Unit,
    onConnect: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by app.account.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    val expanded = open || forceOpen
    Box(Modifier.padding(top = 8.dp, bottom = 2.dp)) {
        IconButton(
            onClick = { open = true },
            modifier = Modifier
                .size(44.dp)
                .semantics { contentDescription = "Account menu" },
        ) {
            AccountAvatar(state, 32.dp, selected = selected)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            modifier = Modifier.width(252.dp),
        ) {
            val signedIn = state as? AccountState.SignedIn
            val name = signedIn?.identity?.name
                ?: if (app.account.session() != null) "Account" else "Guest"
            val subtitle = if (signedIn != null) "YouTube Music" else "Not signed in"
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { open = false; onAccount() }
                    .padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccountAvatar(state, 32.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ProfileMenuItem("Account", Icons.Outlined.AccountCircle) { open = false; onAccount() }
            ProfileMenuItem("History", Icons.Outlined.History) { open = false; onHistory() }
            ProfileMenuItem("Downloads", Icons.Outlined.Download) { open = false; onDownloads() }
            if (app.connect != null) ProfileMenuItem("Kasane Connect", Icons.Outlined.Devices) { open = false; onConnect() }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ProfileMenuItem("Settings", Icons.Outlined.Settings) { open = false; onSettings() }
        }
    }
}

@Composable
fun ProfileScreen(
    app: DesktopApp,
    onBack: (() -> Unit)? = null,
    onOpenLikes: (() -> Unit)? = null,
    onOpenHistory: (() -> Unit)? = null,
    onOpenDownloads: (() -> Unit)? = null,
    onOpenConnect: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    val state by app.account.state.collectAsState()
    val prefs by app.settings.prefs.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
                Spacer(Modifier.width(4.dp))
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Text("Account", style = MaterialTheme.typography.headlineSmall)
        }

        Column(
            Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccountAvatar(state, 60.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                (state as? AccountState.SignedIn)?.identity?.name
                                    ?: if (app.account.session() != null) "Account" else "Listening as a guest",
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val detail = when (state) {
                                is AccountState.SignedIn ->
                                    if (!app.account.persisted) "Connected for this session · sign in again next launch."
                                    else "YouTube Music channel connected"
                                AccountState.Guest -> "Sign in to sync your liked songs, playlists, albums, and history."
                                AccountState.Checking -> "Checking your account…"
                                AccountState.SigningIn -> "Finish signing in in the window that opened."
                                AccountState.Expired -> "Your session expired. Sign in again to reconnect."
                                is AccountState.Unavailable -> "Couldn't sign in. Check your connection and try again."
                            }
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (state is AccountState.Expired) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    when (state) {
                        AccountState.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Verifying session…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AccountState.SigningIn -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            TextButton(onClick = app.account::cancelSignIn) { Text("Cancel") }
                        }
                        is AccountState.SignedIn -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(onClick = { app.account.signIn() }, shape = MaterialTheme.shapes.small) {
                                Text("Switch account")
                            }
                            OutlinedButton(onClick = { app.scope.launch { app.account.signOut() } }, shape = MaterialTheme.shapes.small) {
                                Text("Sign out")
                            }
                        }
                        else -> Row {
                            Button(onClick = { app.account.signIn() }, shape = MaterialTheme.shapes.small) {
                                Text(if (state is AccountState.Expired) "Sign in again" else "Sign in to YouTube Music")
                            }
                        }
                    }
                }
            }

            if (onOpenLikes != null || onOpenHistory != null || onOpenDownloads != null || onOpenConnect != null || onOpenSettings != null) {
                Column {
                    onOpenLikes?.let { open ->
                        ProfileQuickRow("Liked songs", "Your YouTube Music likes", Icons.Outlined.FavoriteBorder, open)
                    }
                    onOpenHistory?.let { open ->
                        ProfileQuickRow("History", "Recently played songs and mixes", Icons.Outlined.History, open)
                    }
                    onOpenDownloads?.let { open ->
                        ProfileQuickRow("Downloads", "Music saved for offline listening", Icons.Outlined.Download, open)
                    }
                    if (app.connect != null && onOpenConnect != null) {
                        ProfileQuickRow(
                            "Kasane Connect",
                            if (prefs.connectEnabled) "On · ${app.connect.identity.deviceName}" else "Off",
                            Icons.Outlined.Devices,
                            onOpenConnect,
                        )
                    }
                    onOpenSettings?.let { open ->
                        ProfileQuickRow("Settings", "Appearance, interface scale, and devices", Icons.Outlined.Settings, open)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProfileQuickRow(title: String, supporting: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = { Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick),
    )
}
