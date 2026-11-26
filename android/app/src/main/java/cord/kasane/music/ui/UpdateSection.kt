package cord.kasane.music.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cord.kasane.music.update.AndroidUpdates
import cord.kasane.ui.theme.bodySmallMuted

/**
 * OTA surface inside Settings → About: version/channel, a manual check row and the
 * update card (changelog, download progress, install). Functional-minimum
 * implementation in existing components — visual polish is @designer's.
 */
@Composable
fun UpdateSection(updates: AndroidUpdates) {
    val state by updates.state.collectAsState()
    Column {
        ListItem(
            headlineContent = { Text("Version", style = MaterialTheme.typography.titleSmall) },
            supportingContent = { Text(
                "${cord.kasane.music.BuildConfig.VERSION_NAME} · ${cord.kasane.music.BuildConfig.UPDATE_CHANNEL} · Android",
                style = MaterialTheme.typography.bodySmallMuted) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
        ListItem(
            headlineContent = { Text("Check for updates", style = MaterialTheme.typography.titleSmall) },
            supportingContent = { Text(when (val s = state) {
                AndroidUpdates.State.Idle -> "GitHub releases"
                AndroidUpdates.State.Checking -> "Checking…"
                AndroidUpdates.State.UpToDate -> "Up to date"
                is AndroidUpdates.State.Failed -> s.reason
                is AndroidUpdates.State.Available -> "Update available: ${s.release.name}"
                is AndroidUpdates.State.Downloading ->
                    if (s.totalBytes > 0) "Downloading… ${(s.doneBytes * 100 / s.totalBytes).toInt()}%" else "Downloading…"
                is AndroidUpdates.State.ReadyToInstall -> "Ready to install: ${s.release.name}"
            }, style = MaterialTheme.typography.bodySmallMuted) },
            trailingContent = {
                when (val s = state) {
                    is AndroidUpdates.State.Downloading ->
                        LinearProgressIndicator(
                            progress = { if (s.totalBytes > 0) s.doneBytes.toFloat() / s.totalBytes else 0f },
                            modifier = Modifier.padding(start = 12.dp))
                    is AndroidUpdates.State.ReadyToInstall ->
                        TextButton(onClick = { updates.installNow() }) { Text("Install") }
                    else -> TextButton(
                        onClick = { if (state is AndroidUpdates.State.Failed) updates.dismiss() else updates.checkNow() },
                        enabled = state !is AndroidUpdates.State.Checking) {
                        Text(if (state is AndroidUpdates.State.Failed) "Dismiss" else "Check") }
                }
            },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            modifier = Modifier.fillMaxWidth())
    }

    val available = state as? AndroidUpdates.State.Available
    if (available != null) AlertDialog(
        onDismissRequest = { updates.dismiss() },
        title = { Text(available.release.name) },
        text = {
            Column {
                if (available.release.changelog.isNotBlank())
                    Text(available.release.changelog,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 20, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp))
                if (available.asset == null)
                    Text("No Android package attached to this release.",
                        style = MaterialTheme.typography.bodySmallMuted)
            }
        },
        confirmButton = {
            TextButton(onClick = { updates.download(available.release, available.asset) },
                enabled = available.asset != null) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = { updates.dismiss() }) { Text("Later") } })
}
