package cord.kasane.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.CatalogPage
import cord.kasane.music.model.HistoryEntity
import cord.kasane.music.model.Track
import cord.kasane.music.ui.components.TrackArtwork
import cord.kasane.music.ui.components.TrackRow
import cord.kasane.music.ui.theme.*
import cord.kasane.ui.theme.*

/**
 * Listening history. The signed-in YouTube Music feed ([remote]) already arrives grouped into
 * day shelves and includes mixes; local plays are grouped into days by [groupHistoryByDay].
 * Mix rows relaunch their source instead of replaying one entry.
 */
@Composable
fun HistoryScreen(
    local: List<HistoryEntity>,
    remote: CatalogPage?,
    currentTrackId: String?,
    isPlaying: Boolean,
    isLoading: Boolean,
    favoriteIds: Set<String>,
    onPlay: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onOpenCatalog: (CatalogItem) -> Unit,
    onRelaunchMix: (HistoryEntity) -> Unit,
) {
    val remoteShelves = remote?.shelves?.filter { it.items.isNotEmpty() }.orEmpty()
    when {
        remoteShelves.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
            remoteShelves.forEach { shelf ->
                item(key = "day|${shelf.title}") { HistoryDayHeader(shelf.title) }
                items(shelf.items, key = { "r|${it.kind}|${it.id}" }) { item ->
                    val track = item.track()
                    if (track != null) {
                        TrackRow(
                            track = track,
                            isPlaying = currentTrackId == track.id && isPlaying,
                            isLoading = currentTrackId == track.id && isLoading,
                            onClick = { onPlay(track) },
                            onAddToQueue = { onAddToQueue(track) },
                            onToggleFavorite = { onToggleFavorite(track) },
                            isFavorite = favoriteIds.contains(track.id))
                    } else MixEntryRow(item.artwork, item.title, item.subtitle) { onOpenCatalog(item) }
                }
            }
        }
        local.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No listening history yet", style = MaterialTheme.typography.bodyMediumMuted,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            val days = remember(local) { groupHistoryByDay(local) }
            LazyColumn(Modifier.fillMaxSize()) {
                days.forEach { section ->
                    item(key = "day|${section.label}") { HistoryDayHeader(section.label) }
                    items(section.entries, key = { "l|${it.kind}|${it.id}" }) { entry ->
                        if (entry.kind == "mix") {
                            MixEntryRow(entry.thumbnailUri, entry.title, entry.artist) { onRelaunchMix(entry) }
                        } else {
                            val track = Track(entry.trackId, entry.title, entry.artist, entry.album,
                                thumbnailUri = entry.thumbnailUri)
                            TrackRow(
                                track = track,
                                isPlaying = currentTrackId == track.id && isPlaying,
                                isLoading = currentTrackId == track.id && isLoading,
                                onClick = { onPlay(track) },
                                onAddToQueue = { onAddToQueue(track) },
                                onToggleFavorite = { onToggleFavorite(track) },
                                isFavorite = favoriteIds.contains(track.id))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryDayHeader(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}

@Composable
private fun HistoryDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp,
        modifier = Modifier.padding(horizontal = 16.dp))
}

/** A non-song history entry (mix, radio seed, playlist): artwork, title and a chevron that opens/relaunches it. */
@Composable
private fun MixEntryRow(artwork: String?, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1) },
        supportingContent = if (subtitle.isNotBlank()) ({ Text(subtitle, style = MaterialTheme.typography.bodySmallMuted, maxLines = 1) }) else null,
        leadingContent = { TrackArtwork(artwork, Modifier.size(44.dp), shape = RoundedCornerShape(6.dp)) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.heightIn(min = 56.dp).clickable(onClickLabel = "Open $title", onClick = onClick))
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun HistoryLocalPreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            HistoryScreen(
                local = listOf(
                    HistoryEntity(id = 1, kind = "mix", trackId = "seed1", title = "Mix — Midnight City", artist = "M83"),
                    HistoryEntity(id = 2, trackId = "t1", title = "Midnight City", artist = "M83"),
                    HistoryEntity(id = 3, trackId = "t2", title = "Everything Now", artist = "Arcade Fire",
                        playedAt = System.currentTimeMillis() - 2 * 24 * 3600 * 1000L)),
                remote = null, currentTrackId = "t1", isPlaying = true, isLoading = false,
                favoriteIds = setOf("t2"), onPlay = {}, onAddToQueue = {}, onToggleFavorite = {},
                onOpenCatalog = {}, onRelaunchMix = {})
        }
    }
}
