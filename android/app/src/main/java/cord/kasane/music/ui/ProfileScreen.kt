package cord.kasane.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cord.kasane.music.auth.AccountIdentity
import cord.kasane.music.auth.AccountState
import cord.kasane.music.ui.components.TrackArtwork
import cord.kasane.music.ui.theme.*
import cord.kasane.ui.theme.*

/** The signed-in identity or the guest mark, drawn at any size. Used by the profile header and the Home bar. */
@Composable
fun AccountAvatar(avatar: String?, size: androidx.compose.ui.unit.Dp) {
    if (avatar != null) TrackArtwork(avatar, Modifier.size(size), shape = CircleShape)
    else Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(size * 0.58f))
        }
    }
}

/** Name and session status, plus the sign-in flow's progress and actions. Shared by Profile and Settings→General. */
@Composable
fun AccountBlock(state: AccountState, onSignIn: () -> Unit, onSignOut: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit,
    avatarSize: androidx.compose.ui.unit.Dp = 48.dp) {
    val account = (state as? AccountState.SignedIn)?.identity
    Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AccountAvatar(account?.avatar, avatarSize)
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
                Text("Finish sign-in in the YouTube Music window…", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            AccountState.Expired -> Text("Your YouTube Music session expired. Sign in again to reconnect this channel.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            is AccountState.Unavailable -> {
                Text(state.message, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry) { Text("Check saved session") }
            }
            else -> Unit
        }
        if (account == null) {
            if (state != AccountState.SigningIn) Button(onClick = onSignIn,
                shape = MaterialTheme.shapes.small) { Text("Sign in to YouTube Music") }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onSignIn, shape = MaterialTheme.shapes.small) { Text("Switch account") }
            OutlinedButton(onClick = onSignOut, shape = MaterialTheme.shapes.small) { Text("Sign out") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    state: AccountState,
    connectOn: Boolean,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenConnect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Profile") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Spacer(Modifier.height(8.dp))
            AccountBlock(state, onSignIn, onSignOut, onRetry, onCancel, avatarSize = 64.dp)
            HorizontalDivider(Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            val row: @Composable (String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit) -> Unit =
                { label, icon, open ->
                    ListItem(
                        headlineContent = { Text(label, style = MaterialTheme.typography.titleSmall) },
                        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.heightIn(min = 56.dp).clickable(onClickLabel = "Open $label", onClick = open))
                }
            row("Downloaded music", Icons.Outlined.DownloadDone, onOpenDownloads)
            row("History", Icons.Outlined.History, onOpenHistory)
            ListItem(
                headlineContent = { Text("Kasane Connect", style = MaterialTheme.typography.titleSmall) },
                supportingContent = { Text(if (connectOn) "On" else "Off", style = MaterialTheme.typography.bodySmallMuted) },
                leadingContent = { Icon(Icons.Outlined.Devices, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier.heightIn(min = 56.dp).clickable(onClickLabel = "Open devices", onClick = onOpenConnect))
            row("Settings", Icons.Outlined.Settings, onOpenSettings)
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun ProfileGuestPreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ProfileScreen(AccountState.Guest, false, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun ProfileSignedInPreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ProfileScreen(AccountState.SignedIn(AccountIdentity("Kasane Listener", null, "UCpreview")), true, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}
