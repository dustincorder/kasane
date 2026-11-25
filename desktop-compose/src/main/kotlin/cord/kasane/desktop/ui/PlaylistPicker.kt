package cord.kasane.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cord.kasane.desktop.library.DesktopPlaylistPicker

/** The "Add to playlist" dialog: editable account playlists, already-added rows disabled. */
@Composable
fun PlaylistPickerDialog(picker: DesktopPlaylistPicker) {
    val state by picker.state.collectAsState()
    if (!state.visible) return
    AlertDialog(
        onDismissRequest = { picker.dismiss() },
        title = {
            Column {
                Text("Add to playlist", style = MaterialTheme.typography.titleLarge)
                state.track?.let {
                    Text(it.title, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        text = {
            Column(Modifier.heightIn(min = 80.dp, max = 360.dp)) {
                when {
                    state.loading && state.options.isEmpty() ->
                        Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Loading your playlists…", style = MaterialTheme.typography.bodyMedium)
                        }
                    state.options.isEmpty() && state.error == null ->
                        Text("You have no editable playlists.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn {
                        items(state.options, key = { it.id }) { option ->
                            val pending = state.pendingPlaylist == option.id
                            Row(
                                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                                    .clickable(enabled = !option.alreadyAdded && !pending && state.pendingPlaylist == null) { picker.add(option.id) }
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Artwork(option.artwork, 40.dp, MaterialTheme.shapes.extraSmall)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(option.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = if (option.alreadyAdded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                    if (option.privacy.isNotBlank()) Text(option.privacy.lowercase().replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                when {
                                    pending -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    option.alreadyAdded -> Icon(Icons.Filled.Check, "Already in ${option.title}",
                                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (state.error != null || state.refreshRequired) TextButton(onClick = { picker.refresh() }) { Text("Refresh") }
        },
        dismissButton = { TextButton(onClick = { picker.dismiss() }) { Text("Close") } },
    )
}
