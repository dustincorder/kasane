package cord.kasane.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cord.kasane.desktop.DesktopApp
import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.CatalogPage
import cord.kasane.music.model.Track
import kotlinx.coroutines.CancellationException

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/** Runs [block] when [key] or the retry counter changes; failures become a message with Retry. */
@Composable
fun <T> rememberLoad(vararg key: Any?, block: suspend () -> T): Pair<Load<T>, () -> Unit> {
    var attempt by remember { mutableIntStateOf(0) }
    val state by produceState<Load<T>>(Load.Loading, *key, attempt) {
        value = Load.Loading
        value = try { Load.Ready(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Load.Failed(e.message ?: "Something went wrong.")
        }
    }
    return state to { attempt++ }
}

@Composable
fun <T> LoadContent(load: Load<T>, retry: () -> Unit, content: @Composable (T) -> Unit) = when (load) {
    Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    is Load.Failed -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(load.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = retry) { Text("Retry") }
    }
    is Load.Ready -> content(load.value)
}

@Composable
fun HomeScreen(app: DesktopApp, actions: Actions, onMore: (String) -> Unit) {
    val history by app.library.history.collectAsState()
    var chip by remember { mutableStateOf<String?>(null) }
    val (load, retry) = rememberLoad(chip) { chip?.let { app.catalog.homeFiltered(null, it) } ?: app.catalog.home(null) }
    LoadContent(load, retry) { page ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionTitle("Home") }
            if (page.chips.isNotEmpty()) item {
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(page.chips) { c -> FilterChip(selected = chip == c.params, onClick = { chip = if (chip == c.params) null else c.params }, label = { Text(c.title) }) }
                }
            }
            if (history.isNotEmpty() && chip == null) item {
                ShelfRow(cord.kasane.music.model.CatalogShelf("Listen again", history.take(20).map {
                    CatalogItem(it.id, CatalogKind.SONG, it.title, it.artist, it.thumbnailUri, it.durationSeconds, it.album)
                }), actions)
            }
            items(page.shelves, key = { it.title }) { ShelfRow(it, actions, onMore) }
            if (page.shelves.isEmpty()) item { Message("YouTube Music returned no recommendations right now.") }
        }
    }
}

@Composable
fun SearchScreen(app: DesktopApp, actions: Actions, focus: FocusRequester, onTyping: (Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(CatalogKind.SONG) }
    val current by app.player.state.collectAsState()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onTyping(false) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.widthIn(max = 640.dp).weight(1f).focusRequester(focus).onFocusChanged { onTyping(it.isFocused) },
                placeholder = { Text("Songs, albums, artists, playlists") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true, shape = CircleShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submitted = text.trim() }))
            Spacer(Modifier.width(12.dp))
            Button(onClick = { submitted = text.trim() }, enabled = text.isNotBlank()) { Text("Search") }
        }
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CatalogKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
        }
        if (submitted.isBlank()) { Message("Search YouTube Music. Press Enter to search; right-click a song for more."); return@Column }
        val (load, retry) = rememberLoad(submitted, kind) { app.catalog.search(submitted, kind, null) }
        LoadContent(load, retry) { page ->
            val items = page.shelves.flatMap { it.items }.distinctBy { it.kind.name + it.id }
            if (items.isEmpty()) Message("No results for \"$submitted\".")
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                items(items, key = { it.kind.name + it.id }) { item ->
                    val track = item.track()
                    if (track != null) TrackRow(track, actions, current = current.current?.id == track.id)
                    else CatalogListRow(item, actions)
                }
            }
        }
    }
}

@Composable
fun CatalogListRow(item: CatalogItem, actions: Actions) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { actions.open(item) }.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Artwork(item.artwork, 56.dp, if (item.kind == CatalogKind.ARTIST) CircleShape else MaterialTheme.shapes.small)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(app: DesktopApp, actions: Actions) {
    val favorites by app.library.favorites.collectAsState()
    val history by app.library.history.collectAsState()
    val current by app.player.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SectionTitle("Library")
        PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.widthIn(max = 480.dp).padding(horizontal = 24.dp)) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Liked songs · ${favorites.size}") })
            Tab(tab == 1, { tab = 1 }, text = { Text("History") })
        }
        val list = if (tab == 0) favorites else history
        if (tab == 0 && list.isNotEmpty()) Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { actions.playList(list) }) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play") }
            OutlinedButton(onClick = { actions.playList(list, shuffle = true) }) { Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            if (list.isEmpty()) item { Message(if (tab == 0) "Songs you like on this computer appear here. Right-click a song or use the heart in the player." else "Songs you play appear here.") }
            itemsIndexed(list, key = { _, t -> t.id }) { i, t ->
                TrackRow(t, actions, index = i, current = current.current?.id == t.id,
                    onPlay = { if (tab == 0) actions.playList(list, i) else actions.play(t) })
            }
            item { Message("Your YouTube Music library (playlists, saved albums) needs sign-in, which this prototype does not have yet.") }
        }
    }
}

/** Album, playlist, artist or "more" page, rendered from the shared catalog model. */
@Composable
fun BrowseScreen(app: DesktopApp, item: CatalogItem, actions: Actions, onMore: (String) -> Unit) {
    val current by app.player.state.collectAsState()
    val (load, retry) = rememberLoad(item.id) {
        if (item.kind == CatalogKind.PLAYLIST) app.catalog.playlist(item.id, null) else app.catalog.browse(item.id, null)
    }
    LoadContent(load, retry) { page -> BrowseContent(app, item, page, actions, current.current?.id, onMore) }
}

@Composable
private fun BrowseContent(app: DesktopApp, item: CatalogItem, page: CatalogPage, actions: Actions, currentId: String?, onMore: (String) -> Unit) {
    val tracks = page.shelves.firstOrNull { s -> s.items.isNotEmpty() && s.items.all { it.track() != null } }?.items?.mapNotNull { it.track() }.orEmpty()
    val carousels = page.shelves.filter { s -> s.items.any { it.track() == null } }
    val artist = item.kind == CatalogKind.ARTIST
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.Bottom) {
                Artwork(page.artwork ?: item.artwork, 220.dp, if (artist) CircleShape else MaterialTheme.shapes.large)
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(page.title.ifBlank { item.title }, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val byline = page.creators.joinToString(", ") { it.name }.ifBlank { page.subtitle }
                    if (byline.isNotBlank()) Text(byline, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    val facts = listOf(page.stats, page.listeners).filter(String::isNotBlank).joinToString(" · ")
                    if (facts.isNotBlank()) Text(facts, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (page.description.isNotBlank()) Text(page.description, style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp).widthIn(max = 720.dp))
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (tracks.isNotEmpty()) {
                            Button(onClick = { actions.playList(tracks) }) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play") }
                            OutlinedButton(onClick = { actions.playList(tracks, shuffle = true) }) { Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle") }
                        }
                        val radioSeed = page.radioVideoId?.let { id -> Track(id, page.title, page.title) } ?: tracks.firstOrNull()
                        if (radioSeed != null) TextButton(onClick = { actions.startRadio(radioSeed) }) {
                            Icon(Icons.Outlined.Radio, null); Spacer(Modifier.width(6.dp)); Text(if (artist) "Mix" else "Radio")
                        }
                    }
                }
            }
        }
        itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
            Box(Modifier.padding(horizontal = 12.dp)) {
                TrackRow(t, actions, index = i, current = currentId == t.id, onPlay = { actions.playList(tracks, i) })
            }
        }
        items(carousels, key = { "c:" + it.title }) { ShelfRow(it, actions, onMore) }
    }
}
