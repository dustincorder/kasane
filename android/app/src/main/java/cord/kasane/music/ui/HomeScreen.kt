package cord.kasane.music.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.CatalogChip
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
    val openShelf: (title: String, browseId: String) -> Unit = { _, _ -> },
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
    chips: List<CatalogChip> = emptyList(),
    selectedChip: String? = null,
    onChip: (CatalogChip?) -> Unit = {},
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = actions.refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Kasane", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = actions.openSettings) { Icon(Icons.Outlined.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (chips.isNotEmpty()) item(key = "chips") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chips, key = { it.params }) { chip ->
                        KasaneChoiceChip(chip.title, selectedChip == chip.params, { onChip(if (selectedChip == chip.params) null else chip) })
                    }
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
                        is HomeSection.QuickPicks -> QuickPicks(section, currentTrackId, actions)
                        is HomeSection.SongRows -> SongRows(section, currentTrackId, actions)
                        is HomeSection.RecentTracks -> Shelf(section.title) {
                            items(section.tracks, key = { it.id }) { ArtworkTile(it, 132.dp, currentTrackId == it.id) { actions.playTrack(it) } }
                        }
                        is HomeSection.SavedOffline -> Shelf(section.title, "See all", actions.openSaved) {
                            items(section.tracks, key = { it.id }) { track ->
                                ArtworkTile(track, 132.dp, currentTrackId == track.id) { actions.playList(section.tracks, section.tracks.indexOf(track)) }
                            }
                        }
                        is HomeSection.Mixed -> Shelf(section.title, more = section.more, onMore = { actions.openShelf(section.title, it) }) {
                            items(section.items, key = { "${it.kind}:${it.id}" }) { item ->
                                val track = item.track()
                                if (track != null) ArtworkTile(track, 132.dp, currentTrackId == track.id) { actions.playTrack(track) }
                                else CollectionCard(item, 132.dp) { actions.open(item) }
                            }
                        }
                        is HomeSection.Collections -> Shelf(section.title, more = section.more, onMore = { actions.openShelf(section.title, it) }) {
                            items(section.items, key = { "${it.kind}:${it.id}" }) { CollectionCard(it, 148.dp) { actions.open(it) } }
                        }
                        is HomeSection.Artists -> Shelf(section.title, more = section.more, onMore = { actions.openShelf(section.title, it) }) {
                            items(section.items, key = { it.id }) { ArtistCircle(it) { actions.open(it) } }
                        }
                        is HomeSection.Videos -> Shelf(section.title, more = section.more, onMore = { actions.openShelf(section.title, it) }) {
                            items(section.items, key = { it.id }) { WideCard(it.artwork, it.title, it.subtitle) { actions.open(it) } }
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
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null && onAction != null) OutlinedButton(onClick = onAction, shape = CircleShape, contentPadding = PaddingValues(horizontal = 14.dp),
            modifier = Modifier.height(32.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Text(action, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Shelf(title: String, action: String? = null, onAction: (() -> Unit)? = null, more: String? = null,
    onMore: ((String) -> Unit)? = null, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        if (more != null && onMore != null) SectionHeader(title, "See all") { onMore(more) } else SectionHeader(title, action, onAction)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** A cover with its title on a scrim: artwork-led, no buttons on the image. Long-press opens the track menu. */
@Composable
private fun ArtworkTile(track: Track, size: Dp, playing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val entries = rememberTrackMenuEntries(track, onClick)
    val source = remember { MutableInteractionSource() }
    Box(modifier.size(size)) {
        Box(Modifier.fillMaxSize().pressScale(source).clip(MaterialTheme.shapes.small)
            .trackGestures(entries, "Play ${track.title}", onClick, { menu = true }, source)) {
            TrackArtwork(track.thumbnailUri, Modifier.fillMaxSize().then(if (playing) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier),
                shape = MaterialTheme.shapes.small, minPixels = 360)
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
                .padding(start = 8.dp, end = 8.dp, top = 22.dp, bottom = 7.dp)) {
                Text(track.title, style = MaterialTheme.typography.labelLarge, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        TrackContextMenu(track, menu, { menu = false }, onClick)
    }
}

@Composable
private fun QuickPicks(section: HomeSection.QuickPicks, currentTrackId: String?, actions: HomeActions) {
    val width = LocalConfiguration.current.screenWidthDp.dp
    val tile = (width - 32.dp - 16.dp) / 3
    val pages = section.tracks.chunked(HomeComposer.PAGE_SIZE)
    val state = rememberLazyListState()
    Column {
        SectionHeader(section.title)
        LazyRow(state = state, flingBehavior = rememberSnapFlingBehavior(state), contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pages, key = { it.first().id }) { page ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(width - 32.dp)) {
                    page.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { track -> ArtworkTile(track, tile, track.id == currentTrackId) { actions.playTrack(track) } }
                        }
                    }
                }
            }
        }
        if (pages.size > 1) {
            val current by remember { derivedStateOf { state.firstVisibleItemIndex } }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
                pages.indices.forEach { i ->
                    Box(Modifier.padding(horizontal = 3.dp).size(6.dp).background(
                        if (i == current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRows(section: HomeSection.SongRows, currentTrackId: String?, actions: HomeActions) {
    val width = LocalConfiguration.current.screenWidthDp.dp
    val state = rememberLazyListState()
    Column {
        if (section.more != null) SectionHeader(section.title, "See all") { actions.openShelf(section.title, section.more) } else SectionHeader(section.title)
        LazyRow(state = state, flingBehavior = rememberSnapFlingBehavior(state), contentPadding = PaddingValues(end = 16.dp)) {
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

/** Large 16:9 card for videos and live stations; sized so the next card peeks in. */
@Composable
private fun WideCard(artwork: String?, title: String, subtitle: String, onClick: () -> Unit) {
    val width = LocalConfiguration.current.screenWidthDp.dp - 56.dp
    val source = remember { MutableInteractionSource() }
    Column(Modifier.width(width).pressScale(source).clip(MaterialTheme.shapes.medium).androidxClickable(source, "Play $title", onClick)) {
        TrackArtwork(artwork, Modifier.fillMaxWidth().aspectRatio(16f / 9f), shape = MaterialTheme.shapes.medium, minPixels = 800)
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmallMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp))
    }
}

@Composable
private fun RadioCard(seed: Track, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Column(Modifier.width(220.dp).pressScale(source).clip(MaterialTheme.shapes.medium).androidxClickable(source, "Start ${seed.artist} radio", onClick)) {
        Box {
            TrackArtwork(seed.thumbnailUri, Modifier.fillMaxWidth().aspectRatio(16f / 9f), shape = MaterialTheme.shapes.medium, minPixels = 544)
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).size(32.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Radio, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        }
        Text("${seed.artist} radio", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
        Text("From ${seed.title}", style = MaterialTheme.typography.bodySmallMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp))
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
    val tile = (LocalConfiguration.current.screenWidthDp.dp - 48.dp) / 3
    Column(Modifier.padding(horizontal = 16.dp).semanticsLoading()) {
        Box(Modifier.padding(top = 22.dp, bottom = 10.dp).size(140.dp, 22.dp).background(block, MaterialTheme.shapes.extraSmall))
        repeat(3) { Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(3) { Box(Modifier.size(tile).background(block, MaterialTheme.shapes.small)) } } }
        Box(Modifier.padding(top = 18.dp, bottom = 10.dp).size(120.dp, 22.dp).background(block, MaterialTheme.shapes.extraSmall))
        repeat(2) {
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(block, MaterialTheme.shapes.small))
                Column(Modifier.padding(start = 12.dp)) {
                    Box(Modifier.size(180.dp, 14.dp).background(block, MaterialTheme.shapes.extraSmall)); Spacer(Modifier.height(6.dp))
                    Box(Modifier.size(110.dp, 12.dp).background(block, MaterialTheme.shapes.extraSmall))
                }
            }
        }
    }
}

private fun Modifier.semanticsLoading() = this.semantics { contentDescription = "Loading Home" }

private val previewActions = HomeActions({}, { _, _ -> }, {}, {}, {}, {}, {}, {})

private fun pt(i: Int, title: String, artist: String) = Track("t$i", title, artist, durationSeconds = 180.0 + i)

/** Full Home at Pixel 6 Pro size with the real navigation chrome, for visual review. */
@Preview(showBackground = true, widthDp = 412, heightDp = 892)
@Composable
fun HomeScreenPreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Box(Modifier.weight(1f)) {
                    HomeScreen(listOf(
                        HomeSection.QuickPicks("Quick picks", (1..9).map { pt(it, listOf("Midnight City Lights", "Subway Melancholy", "Synthetic Dawn", "Neon Drift", "Glass Rooms", "Low Tide", "Flower Castle", "Love Me Not", "Tic Toc")[it - 1], "Artist $it") }),
                        HomeSection.SongRows("mix", "Song mixes", (10..17).map { pt(it, "Mix track $it", "Artist $it") }, more = "FEmusic_mixed"),
                        HomeSection.Collections("alb", "Albums for you", (1..4).map { CatalogItem("m$it", CatalogKind.ALBUM, "Album $it", "Artist $it") }, more = "x"),
                        HomeSection.Videos("live", "Listen together", (1..3).map { CatalogItem("v$it", CatalogKind.VIDEO, "STUDY WITH MIKU STATION", "Hatsune Miku") }),
                        HomeSection.RadioSeeds("Radio from your listening", listOf(pt(1, "Midnight City Lights", "Aether"), pt(3, "Synthetic Dawn", "Kuro"))),
                    ), loading = false, refreshing = false, error = null, offlineOnly = false, currentTrackId = "t2", actions = previewActions,
                        chips = listOf("Energize", "Focus", "Happy", "Relax", "Romance").map { CatalogChip(it, it) }, selectedChip = null)
                }
                NavigationBar { listOf("Home", "Search", "Library").forEachIndexed { i, l -> NavigationBarItem(i == 0, {}, { Icon(Icons.Default.Search, null) }, label = { Text(l) }) } }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 700)
@Composable
fun HomeLoadingPreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            HomeScreen(emptyList(), loading = true, refreshing = false, error = null, offlineOnly = false, currentTrackId = null, actions = previewActions)
        }
    }
}
