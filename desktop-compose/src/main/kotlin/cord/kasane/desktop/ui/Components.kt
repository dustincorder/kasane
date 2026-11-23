package cord.kasane.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.CatalogShelf
import cord.kasane.music.model.Track

/** What a track or catalog entry can do; one implementation routes it to the player or navigation. */
interface Actions {
    fun play(track: Track)
    fun playNext(track: Track)
    fun addToQueue(track: Track)
    fun startRadio(track: Track)
    fun toggleFavorite(track: Track)
    fun isFavorite(id: String): Boolean
    fun open(item: CatalogItem)
    /** A list in its own order (album, playlist, liked songs); shuffle picks the start. */
    fun playList(tracks: List<Track>, index: Int = 0, shuffle: Boolean = false)
}

fun formatTime(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackMenu(track: Track, actions: Actions, content: @Composable () -> Unit) {
    ContextMenuArea(items = {
        listOf(
            ContextMenuItem("Play") { actions.play(track) },
            ContextMenuItem("Play next") { actions.playNext(track) },
            ContextMenuItem("Add to queue") { actions.addToQueue(track) },
            ContextMenuItem("Start radio") { actions.startRadio(track) },
            ContextMenuItem(if (actions.isFavorite(track.id)) "Remove from liked songs" else "Add to liked songs") { actions.toggleFavorite(track) },
        )
    }, content = content)
}

/** A song row: double-click or the play button plays, right-click opens the menu. */
@Composable
fun TrackRow(track: Track, actions: Actions, index: Int? = null, current: Boolean = false, onPlay: () -> Unit = { actions.play(track) }) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    TrackMenu(track, actions) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .background(if (current) MaterialTheme.colorScheme.secondaryContainer else if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
                .hoverable(hover).clickable(onClick = onPlay).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index != null) {
                Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                    if (hovered) Icon(Icons.Filled.PlayArrow, "Play", Modifier.size(18.dp))
                    else Text("${index + 1}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
            }
            Artwork(track.thumbnailUri, 44.dp, MaterialTheme.shapes.extraSmall)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
                Text(listOfNotNull(track.artist.takeIf(String::isNotBlank), track.album?.takeIf(String::isNotBlank)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val liked = actions.isFavorite(track.id)
            if (hovered || liked) IconButton(onClick = { actions.toggleFavorite(track) }) {
                Icon(if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (liked) "Unlike" else "Like",
                    tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            if (track.durationSeconds > 0) Text(formatTime(track.durationSeconds), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** A catalog card for shelves: square for songs/albums/playlists, round for artists. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CatalogCard(item: CatalogItem, actions: Actions, width: androidx.compose.ui.unit.Dp = 168.dp) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val track = item.track()
    val body: @Composable () -> Unit = {
        Column(Modifier.width(width).clip(MaterialTheme.shapes.medium).hoverable(hover)
            .clickable { if (track != null) actions.play(track) else actions.open(item) }.padding(6.dp)) {
            Box {
                Artwork(item.artwork, width - 12.dp, if (item.kind == CatalogKind.ARTIST) CircleShape else MaterialTheme.shapes.medium)
                if (hovered && item.kind != CatalogKind.ARTIST) FilledIconButton(
                    onClick = { if (track != null) actions.play(track) else actions.open(item) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(40.dp)) { Icon(Icons.Filled.PlayArrow, "Play") }
            }
            Spacer(Modifier.height(8.dp))
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (track != null) TrackMenu(track, actions, body) else body()
}

@Composable
fun ShelfRow(shelf: CatalogShelf, actions: Actions, onMore: ((String) -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(shelf.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val more = shelf.moreBrowseId
            if (more != null && onMore != null) androidx.compose.material3.TextButton(onClick = { onMore(more) }) { Text("More") }
        }
        Spacer(Modifier.height(4.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(shelf.items, key = { it.kind.name + it.id }) { CatalogCard(it, actions) }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = modifier.padding(horizontal = 24.dp, vertical = 16.dp))

@Composable
fun Message(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(24.dp))
