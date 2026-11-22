package cord.kasane.music.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.Track
import cord.kasane.music.ui.components.*
import cord.kasane.music.ui.theme.*

class HomeActions(
    val playTrack: (Track) -> Unit,
    val playList: (List<Track>, Int) -> Unit,
    val startRadio: (Track) -> Unit,
    val open: (CatalogItem) -> Unit,
    val openSearch: () -> Unit,
    val openSettings: () -> Unit,
    val openSaved: () -> Unit,
    val refresh: () -> Unit,
)

/**
 * Home is a dashboard of real data: shortcuts from this device's listening history, the YouTube Music feed
 * (Quick picks, mixes, albums, artists, videos) and saved music. Shapes follow the content: song shelves are
 * compact paged rows, collections square covers, artists circles, videos 16:9.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    sections: List<HomeSection>,
    loading: Boolean,
    refreshing: Boolean,
    error: String?,
    offlineOnly: Boolean,
    currentTrackId: String?,
    actions: HomeActions,
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = actions.refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Kasane", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = actions.openSearch) { Icon(Icons.Default.Search, "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = actions.openSettings) { Icon(Icons.Outlined.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            when {
                sections.isEmpty() && loading -> item(key = "skeleton") { HomeSkeleton() }
                sections.isEmpty() && error != null -> item(key = "error") {
                    HomeMessage("Home couldn't load", error, "Try again", actions.refresh)
                }
                sections.isEmpty() && offlineOnly -> item(key = "offline-empty") {
                    HomeMessage("Nothing saved yet", "Offline Only shows music saved on this device. Save songs or playlists while online to listen here.", null, null)
                }
                sections.isEmpty() -> item(key = "empty") {
                    HomeMessage("Start listening", "Search for something you like. Home fills with your shortcuts, radios and recommendations as you listen.", "Search", actions.openSearch)
                }
            }
            if (sections.isNotEmpty() && error != null) item(key = "stale") {
                Text("Showing saved recommendations. $error", style = MaterialTheme.typography.bodySmallMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            sections.forEach { section ->
                item(key = section.key, contentType = section::class.simpleName) {
                    when (section) {
                        is HomeSection.SpeedDial -> SpeedDial(section, currentTrackId, actions)
                        is HomeSection.SongGrid -> SongGrid(section, currentTrackId, actions)
                        is HomeSection.RecentTracks -> Shelf(section.title) {
                            items(section.tracks, key = { it.id }) { TrackCard(it, 128.dp, onClick = { actions.playTrack(it) }) }
                        }
                        is HomeSection.SavedOffline -> Shelf(section.title, "See all", actions.openSaved) {
                            items(section.tracks, key = { it.id }) { track ->
                                TrackCard(track, 128.dp, onClick = { actions.playList(section.tracks, section.tracks.indexOf(track)) })
                            }
                        }
                        is HomeSection.Collections -> Shelf(section.title) {
                            items(section.items, key = { "${it.kind}:${it.id}" }) { CollectionCard(it, 148.dp) { actions.open(it) } }
                        }
                        is HomeSection.Artists -> Shelf(section.title) {
                            items(section.items, key = { it.id }) { ArtistCircle(it) { actions.open(it) } }
                        }
                        is HomeSection.Videos -> Shelf(section.title) {
                            items(section.items, key = { it.id }) { VideoCard(it) { actions.open(it) } }
                        }
                        is HomeSection.RadioSeeds -> Shelf(section.title) {
                            items(section.seeds, key = { it.id }) { RadioCard(it) { actions.startRadio(it) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 6.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun Shelf(title: String, action: String? = null, onAction: (() -> Unit)? = null, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        SectionHeader(title, action, onAction)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SpeedDial(section: HomeSection.SpeedDial, currentTrackId: String?, actions: HomeActions) {
    Column {
        SectionHeader(section.title)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            section.tracks.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { track -> SpeedDialTile(track, track.id == currentTrackId, Modifier.weight(1f)) { actions.playTrack(track) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SpeedDialTile(track: Track, playing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val entries = rememberTrackMenuEntries(track, onClick)
    val source = remember { MutableInteractionSource() }
    Box(modifier) {
        Surface(color = if (playing) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().pressScale(source)) {
            Row(Modifier.trackGestures(entries, "Play ${track.title}", onClick, { menu = true }, source).height(56.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TrackArtwork(track.thumbnailUri, Modifier.size(56.dp), shape = MaterialTheme.shapes.small)
                Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp))
            }
        }
        TrackContextMenu(track, menu, { menu = false }, onClick)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongGrid(section: HomeSection.SongGrid, currentTrackId: String?, actions: HomeActions) {
    val width = LocalConfiguration.current.screenWidthDp.dp
    val state = rememberLazyListState()
    Column {
        SectionHeader(section.title)
        LazyRow(state = state, flingBehavior = rememberSnapFlingBehavior(state), contentPadding = PaddingValues(start = 0.dp, end = 16.dp)) {
            items(section.tracks.chunked(4), key = { column -> column.first().id }) { column ->
                Column(Modifier.width(width - 40.dp)) {
                    column.forEach { track ->
                        TrackRow(track, isPlaying = track.id == currentTrackId, onClick = { actions.playTrack(track) }, showLike = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionCard(item: CatalogItem, width: Dp, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Column(Modifier.width(width).pressScale(source).clip(MaterialTheme.shapes.medium)
        .androidxClickable(source, "Open ${item.title}", onClick)) {
        TrackArtwork(item.artwork, Modifier.size(width), shape = MaterialTheme.shapes.medium)
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
        Text(item.subtitle.ifBlank { item.kind.label }, style = MaterialTheme.typography.bodySmallMuted, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
private fun ArtistCircle(item: CatalogItem, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Column(Modifier.width(112.dp).pressScale(source).clip(MaterialTheme.shapes.medium).androidxClickable(source, "Open ${item.title}", onClick),
        horizontalAlignment = Alignment.CenterHorizontally) {
        TrackArtwork(item.artwork, Modifier.size(104.dp), shape = CircleShape)
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun VideoCard(item: CatalogItem, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Column(Modifier.width(224.dp).pressScale(source).clip(MaterialTheme.shapes.medium).androidxClickable(source, "Play ${item.title}", onClick)) {
        TrackArtwork(item.artwork, Modifier.fillMaxWidth().aspectRatio(16f / 9f), shape = MaterialTheme.shapes.medium)
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(item.subtitle, style = MaterialTheme.typography.bodySmallMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RadioCard(seed: Track, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Column(Modifier.width(148.dp).pressScale(source).clip(MaterialTheme.shapes.medium).androidxClickable(source, "Start ${seed.artist} radio", onClick)) {
        Box {
            TrackArtwork(seed.thumbnailUri, Modifier.size(148.dp), shape = MaterialTheme.shapes.medium)
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).size(32.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Radio, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        }
        Text("${seed.artist} radio", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
        Text("From ${seed.title}", style = MaterialTheme.typography.bodySmallMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
private fun Modifier.androidxClickable(source: MutableInteractionSource, label: String, onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = source, indication = ripple(), onClickLabel = label, onClick = onClick)

@Composable
private fun HomeMessage(title: String, body: String, action: String?, onAction: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(if (action == null) Icons.Outlined.CloudOff else Icons.Default.Search, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(body, style = MaterialTheme.typography.bodyMediumMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        if (action != null && onAction != null) FilledTonalButton(onClick = onAction, modifier = Modifier.padding(top = 16.dp), shape = MaterialTheme.shapes.small) { Text(action) }
    }
}

/** Placeholder blocks with the real section geometry, so content does not jump when it arrives. */
@Composable
private fun HomeSkeleton() {
    val block = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(Modifier.padding(horizontal = 16.dp).semanticsLoading()) {
        Box(Modifier.padding(top = 24.dp, bottom = 12.dp, start = 4.dp).size(140.dp, 22.dp).background(block, MaterialTheme.shapes.extraSmall))
        repeat(4) {
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(block, MaterialTheme.shapes.small))
                Column(Modifier.padding(start = 12.dp)) {
                    Box(Modifier.size(180.dp, 14.dp).background(block, MaterialTheme.shapes.extraSmall))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.size(110.dp, 12.dp).background(block, MaterialTheme.shapes.extraSmall))
                }
            }
        }
        Box(Modifier.padding(top = 24.dp, bottom = 12.dp, start = 4.dp).size(120.dp, 22.dp).background(block, MaterialTheme.shapes.extraSmall))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { Box(Modifier.size(148.dp).background(block, MaterialTheme.shapes.medium)) } }
    }
}

private fun Modifier.semanticsLoading() = this.semantics { contentDescription = "Loading Home" }

private val previewActions = HomeActions({}, { _, _ -> }, {}, {}, {}, {}, {}, {})

@Preview(showBackground = true, widthDp = 390, heightDp = 1500)
@Composable
fun HomeScreenPreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            fun t(i: Int, title: String, artist: String) = Track("t$i", title, artist, durationSeconds = 180.0 + i)
            HomeScreen(listOf(
                HomeSection.SpeedDial("Speed dial", listOf(t(1, "Midnight City Lights", "Aether"), t(2, "Subway Melancholy", "Dustin Corder"),
                    t(3, "Synthetic Dawn", "Kuro"), t(4, "Neon Drift", "Aether"), t(5, "Glass Rooms", "Hollow"), t(6, "Low Tide", "Mirei"))),
                HomeSection.SongGrid("qp", "Quick picks", (7..14).map { t(it, "Quick pick $it", "Artist $it") }),
                HomeSection.Collections("mix", "Mixed for you", (1..4).map { CatalogItem("m$it", CatalogKind.PLAYLIST, "My Mix $it", "Playlist · YouTube Music") }),
                HomeSection.Artists("art", "Favourite artists", (1..4).map { CatalogItem("a$it", CatalogKind.ARTIST, "Artist $it") }),
                HomeSection.RadioSeeds("Radio from your listening", listOf(t(1, "Midnight City Lights", "Aether"), t(3, "Synthetic Dawn", "Kuro"))),
            ), loading = false, refreshing = false, error = null, offlineOnly = false, currentTrackId = "t2", actions = previewActions)
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
fun HomeLoadingPreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            HomeScreen(emptyList(), loading = true, refreshing = false, error = null, offlineOnly = false, currentTrackId = null, actions = previewActions)
        }
    }
}
