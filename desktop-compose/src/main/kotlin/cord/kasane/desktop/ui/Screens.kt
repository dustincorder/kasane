package cord.kasane.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cord.kasane.desktop.DesktopApp
import cord.kasane.desktop.library.groupHistoryByDay
import cord.kasane.music.auth.AccountState
import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.CatalogPage
import cord.kasane.music.model.CatalogShelf
import cord.kasane.music.model.Track
import cord.kasane.music.provider.AccountCollection
import cord.kasane.music.provider.CatalogJsonParser
import cord.kasane.music.provider.PlaylistDestination
import cord.kasane.ui.components.KasaneChoiceChip
import cord.kasane.ui.components.MenuEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
            System.err.println("Catalog request failed: ${e::class.simpleName}")
            Load.Failed("Couldn't load music. Check your connection and try again.")
        }
    }
    return state to { attempt++ }
}

@Composable
fun <T> LoadContent(load: Load<T>, retry: () -> Unit, content: @Composable (T) -> Unit) = when (load) {
    Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    is Load.Failed -> Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(load.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        FilledTonalButton(onClick = retry, shape = MaterialTheme.shapes.small) { Text("Retry") }
    }
    is Load.Ready -> content(load.value)
}

@Composable
fun HomeScreen(app: DesktopApp, actions: Actions, onMore: (String) -> Unit) {
    val history by app.library.history.collectAsState()
    val likedTracks by app.likedSongs.tracks.collectAsState()
    val currentId = app.player.state.collectAsState().value.current?.id
    val accountState by app.account.state.collectAsState()
    val signedIn = accountState is AccountState.SignedIn
    var chip by remember { mutableStateOf<String?>(null) }
    val (load, retry) = rememberLoad(chip, signedIn) {
        chip?.let { app.catalog.homeFiltered(app.account.session(), it) } ?: app.catalog.home(app.account.session())
    }
    LoadContent(load, retry) { page ->
        val sections = remember(page, history, likedTracks, chip) {
            HomeComposer.build(page.shelves, app.library.topPlayed(), app.library.recentTracks(24), likedTracks, local = chip == null)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Home", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = retry) {
                        Icon(Icons.Outlined.Refresh, "Refresh recommendations", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (page.chips.isNotEmpty()) item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(page.chips) { c ->
                        KasaneChoiceChip(
                            label = c.title,
                            selected = chip == c.params,
                            onClick = { chip = if (chip == c.params) null else c.params },
                        )
                    }
                }
            }
            sections.forEach { section ->
                item(key = section.key) {
                    when (section) {
                        is DesktopHomeSection.QuickPicks -> ShelfRow(CatalogShelf(section.title, section.items), actions, onMore)
                        is DesktopHomeSection.Cards -> ShelfRow(CatalogShelf(section.title, section.items, section.more), actions, onMore)
                        is DesktopHomeSection.TrackRows -> TrackRowsSection(section.title, section.tracks, actions, currentId, section.more, onMore)
                        is DesktopHomeSection.RecentTracks -> ShelfRow(CatalogShelf(section.title, section.tracks.map {
                            CatalogItem(it.id, CatalogKind.SONG, it.title, it.artist, it.thumbnailUri, it.durationSeconds, it.album)
                        }), actions)
                        is DesktopHomeSection.RadioSeeds -> RadioSeedsRow(section, actions)
                    }
                }
            }
            if (page.shelves.isEmpty() && sections.isEmpty()) {
                item { Message("Play something — your picks and recommendations appear here.") }
            }
        }
    }
}

/** A dense song shelf: numbered track rows split into two columns, capped to comfortable reading width. */
@Composable
private fun TrackRowsSection(
    title: String,
    tracks: List<Track>,
    actions: Actions,
    currentId: String?,
    more: String?,
    onMore: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (more != null) TextButton(onClick = { onMore(more) }) { Text("See all") }
        }
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(Modifier.widthIn(max = 1280.dp).fillMaxWidth().padding(horizontal = 12.dp)) {
            if (maxWidth >= 760.dp && tracks.size >= 4) {
                val half = (tracks.size + 1) / 2
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    tracks.chunked(half).forEachIndexed { col, part ->
                        Column(Modifier.weight(1f)) {
                            part.forEachIndexed { i, t ->
                                val index = col * half + i
                                TrackRow(t, actions, index = index, current = currentId == t.id, onPlay = { actions.playList(tracks, index) })
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.widthIn(max = 880.dp).fillMaxWidth()) {
                    tracks.forEachIndexed { index, t ->
                        TrackRow(t, actions, index = index, current = currentId == t.id, onPlay = { actions.playList(tracks, index) })
                    }
                }
            }
        }
    }
}

/** Radio seeds from listening habits; each card starts a fresh YouTube Music radio like on Android. */
@Composable
private fun RadioSeedsRow(section: DesktopHomeSection.RadioSeeds, actions: Actions) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(section.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(6.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(section.seeds, key = { "seed:" + it.id }) { seed ->
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Column(
                    Modifier
                        .width(156.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
                        .hoverable(hover)
                        .clickable { actions.startRadio(seed) }
                        .padding(8.dp),
                ) {
                    Box {
                        Artwork(seed.thumbnailUri, 140.dp, MaterialTheme.shapes.medium)
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape,
                            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).size(30.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Radio, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("${seed.artist} radio", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("From ${seed.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun SearchScreen(
    app: DesktopApp,
    actions: Actions,
    focus: FocusRequester,
    query: String = "",
    onQueryChange: ((String) -> Unit)? = null,
    onTyping: (Boolean) -> Unit,
) {
    var localText by remember { mutableStateOf(query) }
    val text = if (onQueryChange != null) query else localText
    val setText: (String) -> Unit = { next ->
        if (onQueryChange != null) onQueryChange(next) else localText = next
    }
    var submitted by remember { mutableStateOf(text.trim()) }
    var kind by remember { mutableStateOf(CatalogKind.SONG) }
    val current by app.player.state.collectAsState()
    val prefs by app.settings.prefs.collectAsState()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    DisposableEffect(Unit) { onDispose { onTyping(false) } }
    LaunchedEffect(text) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            submitted = ""
            return@LaunchedEffect
        }
        delay(320)
        submitted = trimmed
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TextField(
                value = text,
                onValueChange = setText,
                placeholder = {
                    Text(if (prefs.offlineOnly) "Search saved music" else "Search songs, albums, artists, playlists")
                },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { setText(""); submitted = "" }) {
                            Icon(Icons.Filled.Close, "Clear search", Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitted = text.trim() }),
                modifier = Modifier
                    .widthIn(max = 540.dp)
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged { onTyping(it.isFocused) },
            )

            if (!prefs.offlineOnly) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CatalogKind.entries.forEach { k ->
                        KasaneChoiceChip(
                            label = k.label,
                            selected = kind == k,
                            onClick = { kind = k },
                        )
                    }
                }
            }
        }

        if (submitted.isBlank()) {
            EmptyStateBlock(
                icon = Icons.Outlined.Search,
                title = if (prefs.offlineOnly) "Search saved music" else "Search YouTube Music",
                subtitle = if (prefs.offlineOnly) "Offline Only looks through songs saved on this computer."
                else "Find songs, albums, artists, playlists, and videos.",
            )
            return@Column
        }

        if (prefs.offlineOnly) {
            val saved = remember(submitted) { app.offline.searchSaved(submitted) }
            if (saved.isEmpty()) {
                EmptyStateBlock(
                    icon = Icons.Outlined.Search,
                    title = "No saved song matches \"$submitted\"",
                    subtitle = "Turn off Offline Only in Settings to search online.",
                )
            } else {
                LazyColumn(
                    Modifier.widthIn(max = 880.dp).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    itemsIndexed(saved, key = { i, t -> "s:$i:${t.id}" }) { i, track ->
                        TrackRow(track, actions, current = current.current?.id == track.id, onPlay = { actions.playList(saved, i) })
                    }
                }
            }
            return@Column
        }

        var extraItems by remember(submitted, kind) { mutableStateOf<List<CatalogItem>>(emptyList()) }
        var continuation by remember(submitted, kind) { mutableStateOf<String?>(null) }
        var loadingMore by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val (load, retry) = rememberLoad(submitted, kind) { app.catalog.search(submitted, kind, null) }
        LoadContent(load, retry) { page ->
            LaunchedEffect(page.continuation) { continuation = page.continuation }
            val items = (page.shelves.flatMap { it.items } + extraItems).distinctBy { it.kind.name + it.id }
            if (items.isEmpty()) {
                EmptyStateBlock(
                    icon = Icons.Outlined.Search,
                    title = "No results for \"$submitted\"",
                    subtitle = "Check the spelling or switch the category filter above.",
                )
            } else if (kind in setOf(CatalogKind.ALBUM, CatalogKind.ARTIST, CatalogKind.PLAYLIST) && items.all { it.track() == null }) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    modifier = Modifier.widthIn(max = 1240.dp).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items, key = { it.kind.name + it.id }) { item ->
                        CatalogCard(item, actions, width = 160.dp)
                    }
                    if (continuation != null) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "more") {
                            LoadMoreButton(loadingMore) {
                                val cont = continuation ?: return@LoadMoreButton
                                loadingMore = true
                                scope.launch {
                                    try {
                                        val next = app.catalog.search(submitted, kind, cont)
                                        extraItems = extraItems + next.shelves.flatMap { it.items }
                                        continuation = next.continuation?.takeUnless { it == cont }
                                    } catch (e: CancellationException) { throw e
                                    } catch (_: Exception) { app.player.notify("Could not load more results.") }
                                    finally { loadingMore = false }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.widthIn(max = 880.dp).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    items(items, key = { it.kind.name + it.id }) { item ->
                        val track = item.track()
                        if (track != null) TrackRow(track, actions, current = current.current?.id == track.id)
                        else CatalogListRow(item, actions)
                    }
                    if (continuation != null) item(key = "more") {
                        LoadMoreButton(loadingMore) {
                            val cont = continuation ?: return@LoadMoreButton
                            loadingMore = true
                            scope.launch {
                                try {
                                    val next = app.catalog.search(submitted, kind, cont)
                                    extraItems = extraItems + next.shelves.flatMap { it.items }
                                    continuation = next.continuation?.takeUnless { it == cont }
                                } catch (e: CancellationException) { throw e
                                } catch (_: Exception) { app.player.notify("Could not load more results.") }
                                finally { loadingMore = false }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadMoreButton(loading: Boolean, onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        OutlinedButton(onClick = onClick, enabled = !loading, shape = MaterialTheme.shapes.small) {
            Text(if (loading) "Loading…" else "Load more")
        }
    }
}

@Composable
private fun EmptyStateBlock(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.widthIn(max = 880.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp).widthIn(max = 420.dp),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction, shape = MaterialTheme.shapes.small) { Text(actionLabel) }
        }
    }
}

@Composable
fun CatalogListRow(item: CatalogItem, actions: Actions) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .hoverable(hover)
            .clickable { actions.open(item) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(item.artwork, 52.dp, if (item.kind == CatalogKind.ARTIST) CircleShape else MaterialTheme.shapes.small)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.subtitle.ifBlank { item.kind.label },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LibraryScreen(app: DesktopApp, actions: Actions, tab: Int, onTab: (Int) -> Unit) {
    val usage by app.offline.usage.collectAsState()
    val current by app.player.state.collectAsState()
    val accountState by app.account.state.collectAsState()
    val signedIn = accountState is AccountState.SignedIn
    val selected = if (!signedIn && tab > 1) 0 else tab

    // Liked songs is a pinned system playlist inside Playlists — not a standalone tab.
    val tabs = buildList {
        add("History")
        add(if (usage.savedCount > 0) "Saved · ${usage.savedCount}" else "Saved")
        if (signedIn) {
            add("Playlists")
            add("Albums")
            add("Artists")
        }
    }

    Column(Modifier.fillMaxSize()) {
        SectionTitle("Library")
        Box(Modifier.fillMaxWidth()) {
            HorizontalDivider(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            ScrollableTabRow(
                selectedTabIndex = selected.coerceIn(0, tabs.lastIndex),
                edgePadding = 24.dp,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = {},
            ) {
                tabs.forEachIndexed { index, label ->
                    Tab(
                        selected = selected == index,
                        onClick = { onTab(index) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = { Text(label, maxLines = 1, softWrap = false) },
                    )
                }
            }
        }
        when {
            signedIn && selected >= 2 -> AccountCollectionList(app, actions, AccountCollections[selected - 2], signedIn, current.current?.id)
            selected == 0 -> HistoryTab(app, actions, signedIn, current.current?.id)
            else -> DownloadsScreen(app, actions)
        }
    }
}

/** The account's own "Liked songs" playlist, prepended to Playlists like on Android. */
internal val YtmLikedSongs = CatalogItem("VLLM", CatalogKind.PLAYLIST, "Liked songs", "YouTube Music")
/**
 * Listening history: the signed-in YouTube Music feed already arrives grouped into day shelves and
 * includes mixes; the guest session shows this run's local plays grouped by day, memory-only.
 */
@Composable
private fun HistoryTab(app: DesktopApp, actions: Actions, signedIn: Boolean, currentId: String?) {
    val local by app.library.history.collectAsState()
    val (load, retry) = rememberLoad(signedIn) {
        if (!signedIn) null else CatalogJsonParser.page(
            app.catalog.request("browse", buildJsonObject { put("browseId", "FEmusic_history") }, app.account.session()),
        )
    }
    val remote = (load as? Load.Ready)?.value
    when {
        remote != null && remote.shelves.any { it.items.isNotEmpty() } -> LazyColumn(
            Modifier.widthIn(max = 900.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            remote.shelves.filter { it.items.isNotEmpty() }.forEach { shelf ->
                item(key = "day|${shelf.title}") {
                    Text(
                        shelf.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                items(shelf.items, key = { "r|${it.kind}|${it.id}" }) { item ->
                    val track = item.track()
                    if (track != null) TrackRow(track, actions, current = currentId == track.id, onPlay = { actions.play(track) })
                    else MixEntryRow(item.artwork, item.title, item.subtitle) { actions.open(item) }
                }
            }
        }
        local.isEmpty() -> if (load is Load.Failed) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(load.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = retry, shape = MaterialTheme.shapes.small) { Text("Retry") }
            }
        } else {
            EmptyStateBlock(
                icon = Icons.Outlined.History,
                title = "Nothing played yet",
                subtitle = "Songs and mixes you play appear here.",
            )
        }
        else -> {
            val days = groupHistoryByDay(local)
            LazyColumn(
                Modifier.widthIn(max = 900.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                days.forEach { section ->
                    item(key = "day|${section.label}") {
                        Text(
                            section.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                    items(section.entries, key = { "l|${it.kind}|${it.track.id}" }) { entry ->
                        if (entry.kind == "mix") MixEntryRow(entry.track.thumbnailUri, "Radio · ${entry.track.title}", entry.track.artist) { actions.startRadio(entry.track) }
                        else TrackRow(entry.track, actions, current = currentId == entry.track.id, onPlay = { actions.play(entry.track) })
                    }
                }
            }
        }
    }
}

/** A non-song history entry (mix, radio seed, playlist): artwork, title and a chevron that relaunches it. */
@Composable
private fun MixEntryRow(artwork: String?, title: String, subtitle: String, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .hoverable(hover)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(artwork, 44.dp, MaterialTheme.shapes.small)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val AccountCollections = listOf(AccountCollection.PLAYLISTS, AccountCollection.ALBUMS, AccountCollection.ARTISTS)

/** One YouTube Music account collection rendered as an adaptive artwork grid. */
@Composable
private fun AccountCollectionList(app: DesktopApp, actions: Actions, collection: AccountCollection, signedIn: Boolean, currentId: String?) {
    val (load, retry) = rememberLoad(collection, signedIn) { app.catalog.collection(collection, null) }
    LoadContent(load, retry) { page ->
        val server = page.shelves.flatMap { it.items }
        val items = if (collection == AccountCollection.PLAYLISTS && server.none { it.kind == CatalogKind.PLAYLIST && it.id == YtmLikedSongs.id })
            listOf(YtmLikedSongs) + server else server
        val tracks = items.mapNotNull { it.track() }
        if (items.isEmpty()) {
            EmptyStateBlock(
                icon = Icons.Outlined.LibraryMusic,
                title = "No saved items in this collection",
                subtitle = "Items you save on YouTube Music appear here.",
            )
        } else if (tracks.size == items.size) {
            LazyColumn(
                Modifier.widthIn(max = 900.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                itemsIndexed(tracks, key = { i, it -> "$i:${it.id}" }) { i, track ->
                    TrackRow(track, actions, index = i, current = currentId == track.id, onPlay = { actions.playList(tracks, i) })
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.widthIn(max = 1280.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.kind.name + it.id }) { item ->
                    if (item.id == YtmLikedSongs.id && item.artwork.isNullOrBlank()) {
                        LikedSongsTile { actions.open(item) }
                    } else {
                        CatalogCard(item, actions, width = 160.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LikedSongsTile(onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(
        Modifier
            .width(160.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
            .hoverable(hover)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.size(144.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Favorite, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Liked songs", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Playlist · YouTube Music", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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

/**
 * Detail page with responsive 2-pane layout at ≥1020dp content width (≥1100dp window) per SCREEN_SPEC.md §6,
 * plus continuation paging, find-in-page, sorting, and overflow bulk actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowseContent(app: DesktopApp, item: CatalogItem, firstPage: CatalogPage, actions: Actions, currentId: String?, onMore: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val artist = item.kind == CatalogKind.ARTIST
    val playlist = item.kind == CatalogKind.PLAYLIST
    var extraPages by remember { mutableStateOf<List<CatalogPage>>(emptyList()) }
    var continuation by remember { mutableStateOf(firstPage.continuation) }
    var loadingMore by remember { mutableStateOf(false) }
    var bulkStatus by remember { mutableStateOf<String?>(null) }
    var findQuery by remember { mutableStateOf("") }
    var findOpen by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf("default") }
    var sortMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var bulkPlaylist by remember { mutableStateOf<List<PlaylistDestination>?>(null) }

    val albumCover = if (item.kind == CatalogKind.ALBUM) firstPage.artwork ?: item.artwork else null
    val tracks = remember(firstPage, extraPages, albumCover) {
        (listOf(firstPage) + extraPages)
            .flatMap { p -> p.shelves.flatMap { it.items } }
            .mapNotNull { it.track() }
            .distinctBy { it.id }
            .map { t -> if (t.thumbnailUri.isNullOrBlank() && !albumCover.isNullOrBlank()) t.copy(thumbnailUri = albumCover) else t }
    }
    val carousels = firstPage.shelves.filter { s -> s.items.any { it.track() == null } }

    fun loadMore() {
        val cont = continuation ?: return
        if (loadingMore) return
        loadingMore = true
        scope.launch {
            try {
                val next = app.catalog.playlist(item.id, cont)
                extraPages = extraPages + next
                continuation = next.continuation?.takeUnless { it == cont }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { app.player.notify("Could not load more tracks. Check your connection.") }
            finally { loadingMore = false }
        }
    }

    val filterNeedsAll = findQuery.isNotBlank() || (playlist && sortMode != "default")
    LaunchedEffect(filterNeedsAll, continuation) {
        if (filterNeedsAll && continuation != null && !loadingMore) loadMore()
    }
    val displayedTracks = remember(tracks, findQuery, sortMode) {
        var list = tracks
        if (findQuery.isNotBlank()) list = list.filter {
            it.title.contains(findQuery, true) || it.artist.contains(findQuery, true) || it.album.orEmpty().contains(findQuery, true)
        }
        if (playlist) list = when (sortMode) {
            "title" -> list.sortedBy { it.title.lowercase() }
            "artist" -> list.sortedBy { it.artist.lowercase() }
            "album" -> list.sortedBy { it.album.orEmpty().lowercase() }
            else -> list
        }
        list
    }
    fun bulk(verb: String, act: suspend (List<Track>) -> String) {
        scope.launch {
            bulkStatus = "Loading tracks…"
            while (continuation != null) {
                val cont = continuation ?: break
                try {
                    val next = app.catalog.playlist(item.id, cont)
                    extraPages = extraPages + next
                    continuation = next.continuation?.takeUnless { it == cont }
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { bulkStatus = "Could not load all tracks."; return@launch }
            }
            val all = tracks
            bulkStatus = try { act(all) } catch (e: Exception) { e.message ?: "Could not $verb." }
            bulkStatus?.let { app.player.notify(it) }
            bulkStatus = null
        }
    }

    val headerActions: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (tracks.isNotEmpty()) {
                    Button(
                        onClick = { actions.playList(displayedTracks) },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.height(40.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play", maxLines = 1, softWrap = false)
                    }
                    FilledTonalIconButton(
                        onClick = { actions.playList(displayedTracks, shuffle = true) },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.size(40.dp),
                    ) { Icon(Icons.Filled.Shuffle, "Shuffle", Modifier.size(18.dp)) }
                }
                val radioSeed = firstPage.radioVideoId?.let { id -> Track(id, firstPage.title, firstPage.title) } ?: tracks.firstOrNull()
                if (radioSeed != null) {
                    FilledTonalButton(
                        onClick = { actions.startRadio(radioSeed) },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.height(40.dp),
                    ) {
                        Icon(Icons.Outlined.Radio, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (artist) "Mix" else "Radio", maxLines = 1, softWrap = false)
                    }
                }
                if (tracks.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { findOpen = !findOpen; if (!findOpen) findQuery = "" }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.Search, "Find in collection", Modifier.size(20.dp), tint = if (findOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (playlist) {
                            Box {
                                IconButton(onClick = { sortMenu = true }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, "Sort playlist", Modifier.size(20.dp), tint = if (sortMode != "default") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                DropdownMenu(
                                    expanded = sortMenu,
                                    onDismissRequest = { sortMenu = false },
                                    shape = MaterialTheme.shapes.medium,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    listOf("default" to "Playlist order", "title" to "Title", "artist" to "Artist", "album" to "Album").forEach { (mode, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = { sortMode = mode; sortMenu = false },
                                        )
                                    }
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { overflowMenu = true }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Outlined.MoreVert, "Collection options", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val bulkEntries = buildList {
                                add(MenuEntry("Add all to queue", icon = Icons.AutoMirrored.Filled.QueueMusic, group = 0) {
                                    bulk("queue") { all -> all.forEach { actions.addToQueue(it) }; "Added ${all.size} tracks to the queue." }
                                })
                                add(MenuEntry("Save all offline", icon = Icons.Outlined.Download, group = 1) {
                                    bulk("save") { all -> val s = app.offline.save(all); "Queued ${s.queued} tracks for offline." }
                                })
                                if (actions.likesEnabled) {
                                    add(MenuEntry("Add all to playlist…", icon = Icons.AutoMirrored.Outlined.PlaylistAdd, group = 1) {
                                        scope.launch {
                                            val session = app.account.session() ?: return@launch
                                            bulkPlaylist = runCatching {
                                                app.catalog.playlistDestinations(session, tracks.firstOrNull()?.id ?: return@launch)
                                            }.getOrNull()
                                        }
                                    })
                                }
                            }
                            TrackDropdownMenu(bulkEntries, overflowMenu, { overflowMenu = false })
                        }
                    }
                }
            }
            if (findOpen) {
                OutlinedTextField(
                    value = findQuery,
                    onValueChange = { findQuery = it },
                    placeholder = { Text("Find in ${item.kind.label.removeSuffix("s").lowercase()}") },
                    trailingIcon = {
                        IconButton(onClick = { findOpen = false; findQuery = "" }) {
                            Icon(Icons.Filled.Close, "Close find", Modifier.size(18.dp))
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                )
            }
            bulkStatus?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    val metadataBlock: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                firstPage.title.ifBlank { item.title },
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (firstPage.creators.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    firstPage.creators.forEachIndexed { idx, creator ->
                        if (idx > 0) Text("·", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val link = creator.browseId?.let { CatalogItem(it, CatalogKind.ARTIST, creator.name, artwork = creator.avatar) }
                        Text(
                            creator.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (link != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (link != null) Modifier.clip(MaterialTheme.shapes.extraSmall).clickable { actions.open(link) } else Modifier,
                        )
                    }
                }
            } else if (firstPage.subtitle.isNotBlank()) {
                Text(firstPage.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val facts = listOf(firstPage.stats, firstPage.listeners).filter(String::isNotBlank).joinToString(" · ")
            if (facts.isNotBlank()) {
                Text(facts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (firstPage.description.isNotBlank()) {
                Text(
                    firstPage.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp).widthIn(max = 560.dp),
                )
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= 1040.dp && tracks.isNotEmpty()
        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .width(324.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Artwork(firstPage.artwork ?: item.artwork, 220.dp, if (artist) CircleShape else MaterialTheme.shapes.large)
                    metadataBlock()
                    headerActions()
                }
                LazyColumn(
                    Modifier.weight(1f).widthIn(max = 920.dp).fillMaxHeight(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp, start = 8.dp, end = 16.dp),
                ) {
                    itemsIndexed(displayedTracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
                        TrackRow(t, actions, index = i, current = currentId == t.id, onPlay = { actions.playList(displayedTracks, i) })
                    }
                    if (continuation != null && findQuery.isBlank() && sortMode == "default") item {
                        LoadMoreButton(loadingMore) { loadMore() }
                    }
                    items(carousels, key = { "c:" + it.title }) { ShelfRow(it, actions, onMore) }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Artwork(firstPage.artwork ?: item.artwork, 168.dp, if (artist) CircleShape else MaterialTheme.shapes.large)
                            Box(Modifier.weight(1f)) { metadataBlock() }
                        }
                        headerActions()
                    }
                }
                itemsIndexed(displayedTracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
                    Box(Modifier.widthIn(max = 900.dp).padding(horizontal = 12.dp)) {
                        TrackRow(t, actions, index = i, current = currentId == t.id, onPlay = { actions.playList(displayedTracks, i) })
                    }
                }
                if (continuation != null && findQuery.isBlank() && sortMode == "default") item {
                    LoadMoreButton(loadingMore) { loadMore() }
                }
                items(carousels, key = { "c:" + it.title }) { ShelfRow(it, actions, onMore) }
            }
        }
    }

    bulkPlaylist?.let { options ->
        AlertDialog(
            onDismissRequest = { bulkPlaylist = null },
            title = { Text("Add all tracks to playlist") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (options.isEmpty()) item {
                        Text(
                            "No editable playlists were returned.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(options, key = { it.id }) { destination ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable(enabled = !destination.alreadyAdded) {
                                    bulkPlaylist = null
                                    bulk("add") { all ->
                                        val session = app.account.session() ?: throw IllegalStateException("Sign in again to edit playlists.")
                                        all.forEach { app.catalog.addVideoToPlaylist(session, destination.id, it.id) }
                                        "Added ${all.size} tracks to ${destination.title.take(60)}."
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(destination.artwork, 40.dp, MaterialTheme.shapes.extraSmall)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(destination.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (destination.alreadyAdded) {
                                    Text("Some tracks already added", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { bulkPlaylist = null }) { Text("Close") } },
        )
    }
}
