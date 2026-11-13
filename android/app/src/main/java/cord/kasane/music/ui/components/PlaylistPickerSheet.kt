package cord.kasane.music.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.Track
import cord.kasane.music.provider.PlaylistDestination
import cord.kasane.music.provider.PlaylistPicker
import cord.kasane.music.provider.PlaylistPickerState
import cord.kasane.music.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

val LocalPlaylistPicker = staticCompositionLocalOf<PlaylistPicker?> { null }
private val EmptyPickerState: StateFlow<PlaylistPickerState> = MutableStateFlow(PlaylistPickerState())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPickerHost() {
    val picker = LocalPlaylistPicker.current
    val state by key(picker) { (picker?.state ?: EmptyPickerState).collectAsState() }
    val context = LocalContext.current
    LaunchedEffect(picker) { picker?.messages?.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    if (state.visible && picker != null) {
        ModalBottomSheet(onDismissRequest = picker::dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Gray900, contentColor = White) {
            // Material3's modal handles system insets. Do not double-pad navigation bars.
            PlaylistPickerContent(state, picker::dismiss, picker::refresh, picker::add)
        }
    }
}

@Composable
fun PlaylistPickerContent(state: PlaylistPickerState, onClose: () -> Unit, onRefresh: () -> Unit, onAdd: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Add to playlist", style = KasaneTypography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close playlist picker") }
        }
        state.track?.let { track ->
            Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = KasaneTypography.bodyLarge,
                modifier = Modifier.padding(horizontal = 20.dp))
            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Gray400,
                style = KasaneTypography.bodyMedium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp))
        }
        HorizontalDivider(color = Gray800)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AccentRose)
        state.error?.let { message ->
            Text(message, style = KasaneTypography.bodyMedium, color = AccentRose,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        }
        if (!state.loading && state.error == null && state.options.isEmpty()) {
            Text("No editable playlists were returned. Create a playlist in YouTube Music, then refresh this list.",
                color = Gray400, style = KasaneTypography.bodyMedium, modifier = Modifier.padding(20.dp))
        }
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(state.options, key = { it.id }) { playlist ->
                val busy = state.pendingPlaylist == playlist.id
                val enabled = !state.loading && !state.refreshRequired && state.pendingPlaylist == null && !playlist.alreadyAdded
                Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onAdd(playlist.id) }
                    .heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (playlist.artwork != null) TrackArtwork(playlist.artwork, Modifier.size(44.dp))
                    else Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, tint = Gray400, modifier = Modifier.size(44.dp).padding(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(playlist.title, style = KasaneTypography.bodyLarge, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, color = if (playlist.alreadyAdded) Gray400 else White)
                        Text(when {
                            busy -> "Adding…"
                            playlist.alreadyAdded -> "Already added"
                            playlist.privacy == "PRIVATE" -> "Private · YouTube Music"
                            playlist.privacy == "UNLISTED" -> "Unlisted · YouTube Music"
                            else -> "YouTube Music"
                        }, style = KasaneTypography.labelMedium, color = Gray400)
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = AccentRose, strokeWidth = 2.dp)
                    else if (playlist.alreadyAdded) Icon(Icons.Default.Check, "Already in playlist", tint = AccentRose)
                }
            }
        }
        TextButton(onClick = onRefresh, enabled = !state.loading && state.pendingPlaylist == null,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp).heightIn(min = 48.dp)) {
            Text(if (state.loading) "Loading playlists…" else "Refresh playlists", color = if (state.loading || state.pendingPlaylist != null) Gray500 else AccentRose)
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 570)
@Composable
fun PlaylistPickerPreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        Surface(color = Gray900) {
            PlaylistPickerContent(PlaylistPickerState(visible = true,
                track = Track("preview0001", "A track title with enough words to wrap naturally", "Preview artist"),
                options = listOf(
                    PlaylistDestination("PLpreview1", "Late night listening", "PRIVATE", null, false),
                    PlaylistDestination("PLpreview2", "Favorites for the road — a longer playlist title", "UNLISTED", null, true),
                    PlaylistDestination("PLpreview3", "Soundtracks", "PUBLIC", null, false))), {}, {}, {})
        }
    }
}

@Preview(showBackground = true, widthDp = 320, heightDp = 420)
@Composable
fun PlaylistPickerErrorPreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        Surface(color = Gray900) {
            PlaylistPickerContent(PlaylistPickerState(visible = true, track = Track("preview0001", "Track title", "Preview artist"),
                error = "Could not confirm the addition. Refresh the list before trying again.", refreshRequired = true), {}, {}, {})
        }
    }
}

@Preview(showBackground = true, widthDp = 320, heightDp = 420)
@Composable
fun PlaylistPickerLoadingPreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        Surface(color = Gray900) {
            PlaylistPickerContent(PlaylistPickerState(visible = true, loading = true,
                track = Track("preview0001", "A track", "Preview artist")), {}, {}, {})
        }
    }
}
