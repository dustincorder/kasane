package cord.kasane.music.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.Track
import cord.kasane.music.ui.theme.*
import cord.kasane.ui.theme.*

/**
 * Grid item: the whole card is the target. Tap plays or opens, long-press opens the menu, and there is no
 * button on the artwork. TalkBack gets every menu entry as an action.
 */
@Composable
fun TrackCard(track: Track, width: Dp, onClick: () -> Unit, modifier: Modifier = Modifier,
    onLocalFavorite: (() -> Unit)? = null, localFavorite: Boolean = false) {
    var expanded by remember(track.id) { mutableStateOf(false) }
    val entries = rememberTrackMenuEntries(track, onPlay = onClick, onLocalFavorite = onLocalFavorite, localFavorite = localFavorite)
    val source = remember { MutableInteractionSource() }
    Box(modifier.width(width)) {
        Column(Modifier.fillMaxWidth().pressScale(source).clip(MaterialTheme.shapes.medium)
            .trackGestures(entries, "Play ${track.title}", onClick, { expanded = true }, source)) {
            TrackArtwork(track.thumbnailUri, Modifier.size(width), shape = MaterialTheme.shapes.medium)
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmallMuted,
                modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 2.dp))
        }
        TrackContextMenu(track, expanded, { expanded = false }, onClick, onLocalFavorite = onLocalFavorite, localFavorite = localFavorite)
    }
}
