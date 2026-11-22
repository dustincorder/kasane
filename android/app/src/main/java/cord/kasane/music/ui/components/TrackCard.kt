package cord.kasane.music.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.Track
import cord.kasane.music.ui.theme.*

/**
 * The overflow affordance on artwork keeps a 48dp touch target but draws only a 26dp scrim chip with
 * a 16dp glyph, so it no longer dominates the cover.
 */
@Composable
fun ArtworkMenuButton(onClick: () -> Unit, modifier: Modifier = Modifier, description: String = "Track options") {
    Box(
        modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(26.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MoreVert, description, tint = White, modifier = Modifier.size(16.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackCard(track: Track, width: Dp, onClick: () -> Unit, modifier: Modifier = Modifier,
    onLocalFavorite: (() -> Unit)? = null, localFavorite: Boolean = false) {
    var expanded by remember(track.id) { mutableStateOf(false) }
    Box(modifier.width(width)) {
        Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { expanded = true })) {
            TrackArtwork(track.thumbnailUri, Modifier.size(width))
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            ArtworkMenuButton({ expanded = true })
            TrackContextMenu(track, expanded, { expanded = false }, onClick,
                onLocalFavorite = onLocalFavorite, localFavorite = localFavorite)
        }
    }
}
