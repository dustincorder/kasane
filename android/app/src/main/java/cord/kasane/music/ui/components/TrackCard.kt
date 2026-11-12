package cord.kasane.music.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.Track
import cord.kasane.music.ui.theme.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackCard(track: Track, width: Dp, onClick: () -> Unit, modifier: Modifier = Modifier,
    onLocalFavorite: (() -> Unit)? = null, localFavorite: Boolean = false) {
    var expanded by remember(track.id) { mutableStateOf(false) }
    Box(modifier.width(width)) {
        Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { expanded = true })) {
            TrackArtwork(track.thumbnailUri, Modifier.size(width))
            Text(track.title, color = White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = KasaneTypography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            Text(track.artist, color = Gray400, maxLines = 1, overflow = TextOverflow.Ellipsis, style = KasaneTypography.bodyMedium)
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { expanded = true }, modifier = Modifier.background(Gray950, RoundedCornerShape(8.dp))) {
                Icon(Icons.Default.MoreVert, "Track options", tint = Gray200)
            }
            TrackContextMenu(track, expanded, { expanded = false }, onClick,
                onLocalFavorite = onLocalFavorite, localFavorite = localFavorite)
        }
    }
}
