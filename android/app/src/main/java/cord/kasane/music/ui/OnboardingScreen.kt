package cord.kasane.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cord.kasane.music.auth.AccountState
import cord.kasane.music.settings.AppSettings
import cord.kasane.music.ui.theme.*
import cord.kasane.ui.theme.*

/**
 * First-run flow: welcome (sign-in or guest) → essentials (autocache, limit, mobile
 * downloads, metadata, history). Every toggle writes a live setting — nothing is reset,
 * existing users see their current values. All of it stays editable in Settings.
 */
@Composable
fun OnboardingScreen(state: AccountState, settings: AppSettings, signInPhase: cord.kasane.music.auth.SignInPhase? = null, onSignIn: () -> Unit, onDone: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 560.dp).fillMaxHeight()
            .padding(horizontal = 28.dp, vertical = 32.dp)) {
            when (step) {
                0 -> WelcomeStep(state, signInPhase, onSignIn, onContinue = {
                // An explicit guest must not resurrect a stored account session on the next start.
                if (state !is AccountState.SignedIn) settings.setSessionMode(cord.kasane.music.auth.SessionMode.GUEST)
                step = 1
            })
                else -> SetupStep(state, settings, onDone)
            }
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
private fun ColumnScope.SetupStep(state: AccountState, settings: AppSettings, onDone: () -> Unit) {
    Text("Set up Kasane", style = MaterialTheme.typography.headlineSmall)
    Text("Everything here is also in Settings.", style = MaterialTheme.typography.bodySmallMuted,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        val cacheOn by settings.cacheEnabled.collectAsState()
        val cacheLimit by settings.cacheLimitMb.collectAsState()
        val wifiOnly by settings.wifiOnlyDownloads.collectAsState()
        val enrich by settings.enrichMetadata.collectAsState()
        val offlineOnly by settings.offlineOnly.collectAsState()
        val signedIn = state is AccountState.SignedIn
        val historyOn by (if (signedIn) settings.saveListeningHistory else settings.guestSaveHistory).collectAsState()

        SetupToggle("Work offline", "No requests to YouTube Music; saved music and history stay available",
            offlineOnly, settings::setOfflineOnly)
        SetupToggle("Cache songs you play", "Replayed tracks stay available offline",
            cacheOn, settings::setCacheEnabled)
        if (cacheOn) {
            val limits = listOf(512, 1024, 2048, 4096)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                limits.forEach { mb ->
                    FilterChip(selected = cacheLimit == mb, onClick = { settings.setCacheLimitMb(mb) },
                        label = { Text(if (mb >= 1024) "${mb / 1024} GB" else "$mb MB") })
                }
            }
        }
        SetupToggle("Download over Wi-Fi only", "Mobile data is never used for saving music",
            wifiOnly, settings::setWifiOnlyDownloads)
        SetupToggle("Save release details and covers", "Release date, genre and official artwork via MusicBrainz",
            enrich, settings::setEnrichMetadata)
        SetupToggle("Save listening history", if (signedIn) "Feeds History and Home picks"
            else "Guests keep history off unless they opt in",
            historyOn) { if (signedIn) settings.setSaveListeningHistory(it) else settings.setGuestSaveHistory(it) }
    }
    Button(onClick = onDone, shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Start listening") }
}

/** Neutral hold while a stored session is validated — onboarding must not flash for a valid session. */
@Composable
fun OnboardingSplash() {
    Column(Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Kasane", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        CircularProgressIndicator(Modifier.padding(top = 24.dp).size(28.dp), strokeWidth = 2.5.dp)
    }
}

@Composable
private fun SetupToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmallMuted) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(onClickLabel = title) { onChange(!checked) })
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun OnboardingPreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(28.dp)) { WelcomeStep(AccountState.Guest, null, {}, {}) }
        }
    }
}
