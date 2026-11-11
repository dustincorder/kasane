package cord.kasane.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
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
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail with small indicator when active or loading
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Gray850),
            contentAlignment = Alignment.Center
        ) {
            TrackArtwork(track.thumbnailUri, Modifier.fillMaxSize())

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Gray950.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = AccentRose,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else if (isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AccentRose.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Playing",
                        tint = White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and Artist column
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = track.title,
                style = KasaneTypography.titleMedium,
                color = if (isPlaying || isLoading) AccentRose else White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = track.artist,
                    style = KasaneTypography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (track.durationSeconds > 0) {
                    Text(
                        text = "•",
                        style = KasaneTypography.labelSmall
                    )
                    Text(
                        text = formatDuration(track.durationSeconds),
                        style = KasaneTypography.labelMedium
                    )
                }
            }
        }

        if (onToggleFavorite != null) {
            IconButton(onClick = onToggleFavorite) {
                Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (isFavorite) "Remove favorite" else "Add favorite",
                    tint = if (isFavorite) AccentRose else Gray400)
            }
        }

        // Context menu trigger (minimum 48x48 dp touch target)
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Track options",
                    tint = Gray400,
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(Gray900)
            ) {
                if (onPlayNext != null) {
                    DropdownMenuItem(text = { Text("Play next", color = Gray200) },
                        onClick = { menuExpanded = false; onPlayNext() })
                }
                if (onAddToQueue != null) {
                    DropdownMenuItem(
                        text = { Text("Add to Queue", style = KasaneTypography.bodyMedium, color = Gray200) },
                        leadingIcon = {
                            Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = Gray400, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            menuExpanded = false
                            onAddToQueue()
                        }
                    )
                }
                if (onToggleFavorite != null) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                                style = KasaneTypography.bodyMedium,
                                color = Gray200
                            )
                        },
                        leadingIcon = {
                            Icon(
                                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                tint = if (isFavorite) AccentRose else Gray400,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onToggleFavorite()
                        }
                    )
                }
            }
        }
    }
}

@Preview(name = "TrackRow Normal", showBackground = true, backgroundColor = 0xFF0C0D10)
@Composable
fun TrackRowPreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        TrackRow(
            track = Track(
                id = "mock-1",
                title = "Beat It",
                artist = "Michael Jackson",
                album = "Thriller",
                durationSeconds = 258.0,
                thumbnailUri = null
            ),
            isPlaying = false,
            isLoading = false,
            onClick = {},
            isFavorite = true
        )
    }
}

@Preview(name = "TrackRow Playing", showBackground = true, backgroundColor = 0xFF0C0D10)
@Composable
fun TrackRowPlayingPreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        TrackRow(
            track = Track(
                id = "mock-2",
                title = "Espresso",
                artist = "Sabrina Carpenter",
                album = "Short n' Sweet",
                durationSeconds = 175.0,
                thumbnailUri = null
            ),
            isPlaying = true,
            isLoading = false,
            onClick = {},
            isFavorite = false
        )
    }
}

@Preview(name = "TrackRow Loading", showBackground = true, backgroundColor = 0xFF0C0D10)
@Composable
fun TrackRowLoadingPreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        TrackRow(
            track = Track(
                id = "mock-3",
                title = "Subway Melancholy",
                artist = "Dustin Corder",
                album = "Echoes of Silence",
                durationSeconds = 188.0,
                thumbnailUri = null
            ),
            isPlaying = false,
            isLoading = true,
            onClick = {},
            isFavorite = false
        )
    }
}
