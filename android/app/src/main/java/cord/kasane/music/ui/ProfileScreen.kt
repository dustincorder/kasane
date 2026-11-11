package cord.kasane.music.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cord.kasane.music.auth.*
import cord.kasane.music.ui.components.TrackArtwork
import cord.kasane.music.ui.theme.*

@Composable
fun ProfileScreen(state: AccountState, onSignOut: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit) {
    var showSignIn by remember { mutableStateOf(false) }
    val context = LocalContext.current
    if (showSignIn) AlertDialog(onDismissRequest = { showSignIn = false; onCancel() },
        title = { Text("Account access is not available yet") }, text = { Text(SignInAvailability.reason) },
        confirmButton = { TextButton(onClick = { showSignIn = false; onCancel() }) { Text("Continue as guest") } })
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Profile", style = KasaneTypography.titleLarge, color = White)
        val account = (state as? AccountState.SignedIn)?.identity
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (account?.avatar != null) TrackArtwork(account.avatar, Modifier.size(64.dp))
            else Icon(Icons.Default.Person, null, tint = AccentRose, modifier = Modifier.size(48.dp))
            Column {
                Text(account?.name ?: "Listen as a guest", style = KasaneTypography.titleMedium)
                Text(if (account == null) "Search, explore and play music without an account." else "YouTube Music account", color = Gray400)
            }
        }
        when (state) {
            AccountState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = AccentRose)
            AccountState.Expired -> Text("Your session expired. Sign in again when account access is available.", color = AccentRose)
            is AccountState.Unavailable -> {
                Text(state.message, color = Gray400)
                TextButton(onClick = onRetry) { Text("Retry session check") }
            }
            else -> Unit
        }
        if (account == null) {
            Button(onClick = { showSignIn = true }, enabled = state != AccountState.Checking) { Text("Why can't I sign in?", color = Gray950) }
        } else {
            OutlinedButton(onClick = onSignOut) { Text("Sign out", color = AccentRose) }
        }
        HorizontalDivider(color = Gray800)
        Text("Library sources", style = KasaneTypography.titleMedium)
        Text("Favorites and listening history belong to this device. Signing out does not remove them. Account collections are kept separate.", color = Gray400)
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/"))) }
        }) { Text("Open YouTube Music in browser") }
        HorizontalDivider(color = Gray800)
        Text("About Kasane", style = KasaneTypography.titleMedium)
        Text("Kasane ${cord.kasane.music.BuildConfig.VERSION_NAME} · Android\nOnline music with Media3. Local files and offline downloads are not available.", color = Gray400)
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun ProfilePreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        Surface(color = Gray950) { ProfileScreen(AccountState.Guest, {}, {}, {}) }
    }
}
