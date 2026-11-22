package cord.kasane.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.tooling.preview.Preview
import cord.kasane.music.model.Track
import cord.kasane.music.ui.theme.*

fun formatDuration(seconds: Double): String {
    val totalSecs = seconds.toInt()
    if (totalSecs < 0) return "--:--"
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return "%d:%02d".format(mins, secs)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    isPlaying: Boolean = false,
    isLoading: Boolean = false,
    onClick: () -> Unit,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    modifier: Modifier = Modifier,
    useCloudFavorite: Boolean = true,
    showLike: Boolean = true,
    showMenuButton: Boolean = true,
    extraMenu: List<MenuEntry> = emptyList(),
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val offline = LocalOfflineBadges.current[track.id]
    val entries = rememberTrackMenuEntries(track, onClick, onPlayNext, onAddToQueue, onToggleFavorite, isFavorite, extraMenu)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .trackGestures(entries, "Play ${track.title}", onClick, { menuExpanded = true })
            .padding(start = if (leading == null) 16.dp else 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            TrackArtwork(track.thumbnailUri, Modifier.fillMaxSize())
            if (isLoading || isPlaying) Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.small).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) CircularProgressIndicator(color = colors.primary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Icon(Icons.Default.GraphicEq, "Playing", tint = colors.primary, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.titleSmall,
                color = if (isPlaying || isLoading) colors.primary else colors.onBackground,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (offline != null) OfflineBadgeIcon(offline)
                Text(
                    listOfNotNull(track.artist.takeIf { it.isNotBlank() },
                        track.durationSeconds.takeIf { it > 0 }?.let(::formatDuration)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (showLike && (onToggleFavorite != null || LocalCloudLikes.current != null)) {
            TrackLikeButton(track, isFavorite, onToggleFavorite ?: {}, useCloud = useCloudFavorite)
        }
        if (showMenuButton) Box {
            // A 20dp glyph inside the standard 48dp touch target: compact to look at, comfortable to hit.
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, "More options for ${track.title}", tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            TrackContextMenu(track, menuExpanded, { menuExpanded = false }, onClick,
                onPlayNext, onAddToQueue, onToggleFavorite, isFavorite, extraMenu)
        }
        trailing?.invoke()
    }
}

@Preview(name = "TrackRow", showBackground = true, backgroundColor = 0xFF0F1012)
@Composable
fun TrackRowPreview() {
    KasaneTheme(dynamicColor = false) {
        Column {
            TrackRow(Track("mock-1", "Beat It", "Michael Jackson", "Thriller", 258.0), onClick = {}, isFavorite = true)
            TrackRow(Track("mock-2", "Espresso", "Sabrina Carpenter", "Short n' Sweet", 175.0), isPlaying = true, onClick = {})
            TrackRow(Track("mock-3", "Subway Melancholy with an unusually long title that must truncate", "Dustin Corder", "Echoes", 188.0),
                isLoading = true, onClick = {})
        }
    }
}
