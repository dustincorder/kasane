package cord.kasane.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cord.kasane.music.auth.*
import cord.kasane.music.ui.components.TrackArtwork
import cord.kasane.music.ui.theme.*

@Composable
fun ProfileScreen(state: AccountState, onSignIn: () -> Unit, onSignOut: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Profile", style = KasaneTypography.titleLarge, color = White)
        val account = (state as? AccountState.SignedIn)?.identity
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (account?.avatar != null) TrackArtwork(account.avatar, Modifier.size(64.dp))
            else Icon(Icons.Default.Person, null, tint = AccentRose, modifier = Modifier.size(48.dp))
            Column {
                Text(account?.name ?: "Listen as a guest", style = KasaneTypography.titleMedium, color = White)
                Text(if (account == null) "Search, explore and play music without an account." else "YouTube Music channel connected", color = Gray400)
            }
        }
        when (state) {
            AccountState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = AccentRose)
            AccountState.SigningIn -> {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = AccentRose)
                Text("Waiting for you to finish sign in on YouTube Music…", color = Gray400)
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            AccountState.Expired -> Text("Your YouTube Music session expired or could not be restored. Sign in again to reconnect this channel.", color = AccentRose)
            is AccountState.Unavailable -> {
                Text(state.message, color = Gray400)
                TextButton(onClick = onRetry) { Text("Check saved session") }
            }
            else -> Unit
        }
        if (account == null) {
            if (state != AccountState.SigningIn) {
                Button(onClick = onSignIn, enabled = state != AccountState.Checking) { Text("Sign in to YouTube Music", color = Gray950) }
            }
        } else {
            OutlinedButton(onClick = onSignIn) { Text("Switch account or channel", color = AccentRose) }
            OutlinedButton(onClick = onSignOut) { Text("Sign out", color = AccentRose) }
        }
        HorizontalDivider(color = Gray800)
        Text("Library sources", style = KasaneTypography.titleMedium)
        Text("Favorites and listening history belong to this device. Signing out does not remove them. YouTube Music collections stay separate from this local library.", color = Gray400)
        HorizontalDivider(color = Gray800)
        Text("About Kasane", style = KasaneTypography.titleMedium)
        Text("Kasane ${cord.kasane.music.BuildConfig.VERSION_NAME} · Android\nOnline music with Media3. Local files and offline downloads are not available.", color = Gray400)
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun ProfilePreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        Surface(color = Gray950) { ProfileScreen(AccountState.Guest, {}, {}, {}, {}) }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun ProfileSignedInPreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        Surface(color = Gray950) {
            ProfileScreen(AccountState.SignedIn(AccountIdentity("Kasane Listener", null, "UCpreview")), {}, {}, {}, {})
        }
    }
}
