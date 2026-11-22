package cord.kasane.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cord.kasane.music.data.TrackDao
import cord.kasane.music.model.Track
import cord.kasane.music.ui.components.TrackRow

internal fun likePattern(query: String): String =
    "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

/** Offline Only replaces catalog search: it only looks through music saved on this device. */
@Composable
fun OfflineSearchScreen(trackDao: TrackDao, currentTrackId: String?, onPlay: (Track) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results by remember(query) { trackDao.searchSaved(likePattern(query)) }.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize()) {
        TextField(query, { query = it }, singleLine = true, shape = MaterialTheme.shapes.medium,
            placeholder = { Text("Search saved music") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear search") } },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
        if (query.isBlank()) Text("Offline Only searches the music you saved on this device.",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
        else if (results.isEmpty()) Text("No saved song matches “$query”.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(results, key = { it.id }) { track -> TrackRow(track, isPlaying = track.id == currentTrackId, onClick = { onPlay(track) }) }
        }
    }
}
