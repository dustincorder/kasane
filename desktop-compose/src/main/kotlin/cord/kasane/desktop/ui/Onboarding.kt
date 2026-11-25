package cord.kasane.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cord.kasane.desktop.DesktopApp
import cord.kasane.music.auth.AccountState
import cord.kasane.ui.theme.bodySmallMuted

/**
 * First-run flow, ported from Android: welcome (sign-in or guest) → essentials
 * (autocache, cache limit, metadata enrichment). Every toggle writes a live setting;
 * all of it stays editable in Settings. Desktop has no metered-data toggle.
 */
@Composable
fun OnboardingScreen(app: DesktopApp, onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val accountState by app.account.state.collectAsState()
    val signInPhase by app.account.signInPhase.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 560.dp).fillMaxHeight()
            .padding(horizontal = 28.dp, vertical = 32.dp)) {
            when (step) {
                0 -> WelcomeStep(accountState, signInPhase, onSignIn = { app.account.signIn() },
                    // Signed-in users just continue — only an unsigned guest pins the guest mode.
                    onContinue = { if (accountState !is AccountState.SignedIn) app.account.chooseGuest(); step = 1 })
                else -> SetupStep(app, onDone)
            }
        }
    }
}

/** Neutral hold while a stored session is validated — the first-run flow must not flash for it. */
@Composable
fun OnboardingSplash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Kasane", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(Modifier.padding(top = 24.dp).widthIn(max = 120.dp).fillMaxWidth(0.4f))
        }
    }
}

@Composable
private fun ColumnScope.WelcomeStep(state: AccountState, phase: cord.kasane.music.auth.SignInPhase?, onSignIn: () -> Unit, onContinue: () -> Unit) {
    Spacer(Modifier.weight(1f))
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Kasane", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Text("Your YouTube Music, offline-first.", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
    }
    Spacer(Modifier.weight(1f))
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        when (state) {
            AccountState.SigningIn -> {
                LinearProgressIndicator(Modifier.fillMaxWidth(0.7f))
                Text(when (phase) {
                    cord.kasane.music.auth.SignInPhase.STARTING -> "Preparing the sign-in window…"
                    cord.kasane.music.auth.SignInPhase.VALIDATING -> "Checking your YouTube Music account…"
                    else -> "Finish sign-in in the YouTube Music window…"
                }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
            else -> {
                // A background session check is not a sign-in flow — the button stays live.
                Button(onClick = onSignIn,
                    shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Sign in to YouTube Music")
                }
                Text("Library, playlists and recommendations included. You can also connect later from your profile.",
                    style = MaterialTheme.typography.bodySmallMuted, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp))
                TextButton(onClick = onContinue, modifier = Modifier.padding(top = 4.dp)) {
                    Text(if (state is AccountState.SignedIn) "Continue" else "Continue as a guest")
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SetupStep(app: DesktopApp, onDone: () -> Unit) {
    val prefs by app.settings.prefs.collectAsState()
    Text("Set up Kasane", style = MaterialTheme.typography.headlineSmall)
    Text("Everything here is also in Settings.", style = MaterialTheme.typography.bodySmallMuted,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        SetupToggle("Work offline", "No requests to YouTube Music; saved music and history stay available",
            prefs.offlineOnly) { app.settings.update { p -> p.copy(offlineOnly = it) } }
        SetupToggle("Cache songs you play", "Replayed tracks stay available offline",
            prefs.cacheEnabled) { app.settings.update { p -> p.copy(cacheEnabled = it) } }
        if (prefs.cacheEnabled) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(512, 1024, 2048, 4096).forEach { mb ->
                    FilterChip(selected = prefs.cacheLimitMb == mb,
                        onClick = { app.settings.update { p -> p.copy(cacheLimitMb = mb) } },
                        label = { Text(if (mb >= 1024) "${mb / 1024} GB" else "$mb MB") })
                }
            }
        }
        SetupToggle("Save release details and covers", "Release date, genre and official artwork via MusicBrainz",
            prefs.enrichMetadata) { app.settings.update { p -> p.copy(enrichMetadata = it) } }
    }
    Button(onClick = {
        app.settings.update { it.copy(onboardingDone = true) }
        onDone()
    }, shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Start listening") }
}

@Composable
private fun SetupToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmallMuted) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClickLabel = title) { onChange(!checked) })
}
