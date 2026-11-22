package cord.kasane.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.*
import cord.kasane.music.provider.OnlineCatalog
import cord.kasane.music.auth.AccountSession
import cord.kasane.music.provider.AccountCollection
import cord.kasane.music.data.CachedCatalogRepository
import cord.kasane.music.data.CatalogCache
import cord.kasane.music.ui.components.*
import cord.kasane.music.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.security.MessageDigest

private object MessageDigestCompat {
    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

@Composable
fun CatalogShelfRow(shelf: CatalogShelf, onOpen: (CatalogItem) -> Unit) {
    Column {
        if (shelf.title.isNotBlank()) Text(shelf.title, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(HomeFeedPolicy.uniqueShelfItems(shelf.items), key = { "${it.kind}:${it.id}" }) { item ->
                val track = item.track()
                if (track != null) TrackCard(track, 148.dp, onClick = { onOpen(item) })
                else Column(Modifier.width(148.dp).clickable { onOpen(item) }) {
                    TrackArtwork(item.artwork, Modifier.size(148.dp))
                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Text(item.subtitle.ifBlank { item.kind.label }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun CatalogSearchScreen(catalog: OnlineCatalog, onOpen: (CatalogItem) -> Unit,
    currentTrackId: String?, favoriteIds: Set<String>, onFavorite: (Track) -> Unit, onQueue: (Track) -> Unit, onPlayNext: ((Track) -> Unit)? = null,
    cachedCatalog: CachedCatalogRepository? = null, cacheScope: String = "guest",
    recentSearches: List<String> = emptyList(), onCommitSearch: (String) -> Unit = {},
    onRemoveRecent: (String) -> Unit = {}, onClearRecent: () -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(CatalogKind.SONG) }
    var revision by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var continuation by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var loadingMore by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var handledRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { requester.requestFocus() }
    // Suggestions are debounced and cancelled by the next keystroke; failures simply show none.
    LaunchedEffect(query) {
        suggestions = emptyList()
        if (query.trim().length < 2) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        suggestions = catalog.suggestions(query)
    }
    LaunchedEffect(query, category, revision, cachedCatalog, cacheScope) {
        generation++
        continuation = null; error = null; loading = false
        if (query.isBlank()) return@LaunchedEffect
        val cacheKey = "search:${category.name}:${MessageDigestCompat.sha256(query.trim().lowercase())}"
        val saved = cachedCatalog?.cached(cacheScope, cacheKey)
        items = saved?.page?.shelves?.flatMap { it.items }.orEmpty()
        continuation = saved?.page?.continuation
        val isStale = saved == null || System.currentTimeMillis() - saved.updatedAt >= CachedCatalogRepository.SEARCH_TTL_MS
        if (!isStale && revision == handledRevision) return@LaunchedEffect
        loading = true
        try {
            kotlinx.coroutines.delay(400)
            val page = cachedCatalog?.search(cacheScope, query, category, force = revision != handledRevision) ?: catalog.search(query, category)
            items = page.shelves.flatMap { it.items }; continuation = page.continuation
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { error = e.message ?: "Search unavailable. Try again."
        } finally { loading = false; handledRevision = revision }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TextField(query, { query = it; submitted = false }, singleLine = true, shape = MaterialTheme.shapes.medium,
            placeholder = { Text("Search songs, albums, artists") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear search") } },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).focusRequester(requester).onFocusChanged { focused = it.isFocused },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onCommitSearch(query); submitted = true; revision++; focus.clearFocus() }))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CatalogKind.entries) { kind ->
                KasaneChoiceChip(kind.label, category == kind, { category = kind })
            }
        }
        Spacer(Modifier.height(4.dp))
        val showSuggestions = query.isNotBlank() && focused && !submitted && suggestions.isNotEmpty()
        when {
            query.isBlank() && recentSearches.isEmpty() -> SearchHint("Search YouTube Music", "Songs, albums, artists, playlists and videos.")
            query.isBlank() -> {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent searches", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearRecent) { Text("Clear") }
                }
                recentSearches.forEach { recent ->
                    ListItem(
                        headlineContent = { Text(recent, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = { IconButton(onClick = { onRemoveRecent(recent) }) { Icon(Icons.Default.Close, "Remove $recent from recent searches", Modifier.size(20.dp)) } },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clickable(onClickLabel = "Search for $recent") { query = recent; submitted = true; onCommitSearch(recent); focus.clearFocus() })
                }
            }
            showSuggestions -> suggestions.forEach { suggestion ->
                ListItem(
                    headlineContent = { Text(suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable(onClickLabel = "Search for $suggestion") { query = suggestion; submitted = true; onCommitSearch(suggestion); focus.clearFocus() })
            }
            loading && items.isEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            error != null -> {
                Text(error!!, style = MaterialTheme.typography.bodyMediumMuted, modifier = Modifier.padding(16.dp))
                TextButton(onClick = { revision++ }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Retry search") }
            }
            items.isEmpty() -> SearchHint("No results for “${query.trim()}”", "Check the spelling or try a different ${category.label.lowercase().removeSuffix("s")} search.")
        }
        if (loading && items.isNotEmpty() && !showSuggestions) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!showSuggestions && query.isNotBlank()) LazyColumn(Modifier.weight(1f)) {
            // Continuation pages can repeat items; duplicate keys would crash the list.
            items(items.distinctBy { it.kind to it.id }, key = { "${it.kind}:${it.id}" }) { item ->
                val track = item.track()
                if (track != null) TrackRow(track, isPlaying = track.id == currentTrackId,
                    onClick = { onCommitSearch(query); onOpen(item); focus.clearFocus() }, onPlayNext = onPlayNext?.let { action -> { action(track) } }, onAddToQueue = { onQueue(track) },
                    onToggleFavorite = { onFavorite(track) }, isFavorite = track.id in favoriteIds)
                else CatalogResult(item) { onCommitSearch(query); onOpen(item); focus.clearFocus() }
            }
            if (continuation != null && items.size < 2000) item {
                TextButton(enabled = !loadingMore, onClick = {
                    val token = continuation ?: return@TextButton
                    val expected = generation
                    loadingMore = true
                    scope.launch {
                        try {
                            val next = cachedCatalog?.search(cacheScope, query, category, token) ?: catalog.search(query, category, token)
                            if (expected == generation) {
                                if (cachedCatalog != null) {
                                    items = next.shelves.flatMap { it.items }
                                    continuation = next.continuation?.takeUnless { it == token }
                                } else {
                                    items = (items + next.shelves.flatMap { it.items }).distinctBy { it.kind to it.id }
                                    continuation = next.continuation?.takeUnless { it == token }
                                }
                            }
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) { if (expected == generation) error = e.message
                        } finally { loadingMore = false }
                    }
                }) { Text(if (loadingMore) "Loading…" else "More results") }
            }
        }
    }
}

internal fun Track.withCover(cover: String?): Track = if (thumbnailUri.isNullOrBlank() && !cover.isNullOrBlank()) copy(thumbnailUri = cover) else this

@Composable
private fun SearchHint(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Search, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(body, style = MaterialTheme.typography.bodyMediumMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun CatalogResult(item: CatalogItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TrackArtwork(item.artwork, Modifier.size(52.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(item.subtitle.ifBlank { item.kind.label }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CatalogDetailScreen(item: CatalogItem, catalog: OnlineCatalog, onBack: () -> Unit,
    onOpen: (CatalogItem) -> Unit, onPlay: (List<Track>, Boolean) -> Unit, onQueue: (Track) -> Unit,
    cachedCatalog: CachedCatalogRepository? = null, cacheScope: String = "guest", accountSession: AccountSession? = null) {
    var page by remember(item.id) { mutableStateOf<CatalogPage?>(null) }
    var revision by remember(item.id) { mutableIntStateOf(0) }
    var loading by remember(item.id) { mutableStateOf(true) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var loadingMore by remember(item.id) { mutableStateOf(false) }
    var handledRevision by remember(item.id) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val accountPlaylist = item.kind == CatalogKind.PLAYLIST && accountSession != null
    LaunchedEffect(cachedCatalog, cacheScope, item.id) {
        cachedCatalog?.playlistChanges?.collect { change ->
            if (item.kind == CatalogKind.PLAYLIST && change.scope == cacheScope && item.id.removePrefix("VL") == change.playlistId) revision++
        }
    }
    LaunchedEffect(item.id, revision, cachedCatalog, cacheScope, accountSession) {
        error = null
        val saved = cachedCatalog?.cached(cacheScope, if (accountPlaylist) "playlist:${item.id}" else "browse:${item.id}")
        page = saved?.page
        val force = revision != handledRevision
        val stale = saved == null || System.currentTimeMillis() - saved.updatedAt >= CachedCatalogRepository.LIBRARY_TTL_MS
        if (!stale && !force) { loading = false; return@LaunchedEffect }
        loading = saved == null
        try { page = when {
            accountPlaylist && cachedCatalog != null -> cachedCatalog.library(cacheScope, requireNotNull(accountSession), AccountCollection.PLAYLISTS, item.id, force = force)
            accountPlaylist -> catalog.playlistFor(requireNotNull(accountSession), item.id)
            cachedCatalog != null -> cachedCatalog.browse(cacheScope, item.id, force = force)
            else -> catalog.browse(item.id)
        } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "This page is unavailable." }
        finally { loading = false; handledRevision = revision }
    }
    CatalogDetailContent(item, page, loading, error, loadingMore, onBack, { revision++ }, onOpen, onPlay, onQueue) {
        val current = page ?: return@CatalogDetailContent
        val token = current.continuation ?: return@CatalogDetailContent
        loadingMore = true; error = null
        scope.launch {
            try {
                val next = when {
                    accountPlaylist && cachedCatalog != null -> cachedCatalog.library(cacheScope, requireNotNull(accountSession), AccountCollection.PLAYLISTS, item.id, token)
                    accountPlaylist -> catalog.playlistFor(requireNotNull(accountSession), item.id, token)
                    cachedCatalog != null -> cachedCatalog.browse(cacheScope, item.id, token)
                    else -> catalog.browse(item.id, token)
                }
                page = if (cachedCatalog != null) next else current.copy(
                    shelves = (current.shelves + next.shelves).groupBy { it.title }.map { (title, shelves) ->
                        CatalogShelf(title, shelves.flatMap { it.items }.distinctBy { it.kind to it.id })
                    }, continuation = next.continuation?.takeUnless { it == token })
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { error = e.message ?: "Could not load more tracks."
            } finally { loadingMore = false }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CatalogDetailContent(item: CatalogItem, page: CatalogPage?, loading: Boolean, error: String?,
    loadingMore: Boolean, onBack: () -> Unit, onRetry: () -> Unit, onOpen: (CatalogItem) -> Unit,
    onPlay: (List<Track>, Boolean) -> Unit, onQueue: (Track) -> Unit,
    onLoadMore: () -> Unit) {
    // Album rows carry no artwork of their own, so they show (and queue with) the album cover instead of a placeholder note.
    val albumCover = if (item.kind == CatalogKind.ALBUM) page?.artwork ?: item.artwork else null
    val shownTracks = remember(page, albumCover) { page?.tracks.orEmpty().map { it.withCover(albumCover) } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Back is also the system gesture (either edge), so the top button is a convenience, not the only way out.
        val listState = rememberLazyListState()
        // The header collapses into the app bar: once the hero has scrolled away its title takes the bar's place.
        val heroGone by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        // Large playlists continue loading as the end approaches, so they are never silently cut short.
        val nearEnd by remember { derivedStateOf { val info = listState.layoutInfo; info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4 } }
        LaunchedEffect(nearEnd, page?.continuation, loadingMore, loading) { if (nearEnd && page?.continuation != null && !loadingMore && !loading && error == null) onLoadMore() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(if (heroGone) (page?.title?.ifBlank { item.title } ?: item.title) else item.kind.label.removeSuffix("s"),
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "hero") {
                val tracks = shownTracks
                val title = page?.title?.ifBlank { item.title } ?: item.title
                val subtitle = page?.subtitle?.ifBlank { item.subtitle } ?: item.subtitle
                val offline = cord.kasane.music.ui.components.LocalOfflineActions.current
                var more by remember { mutableStateOf(false) }
                val artwork: @Composable (Modifier) -> Unit = { m ->
                    TrackArtwork(page?.artwork ?: item.artwork, m.aspectRatio(1f), minPixels = 800,
                        shape = if (item.kind == CatalogKind.ARTIST) androidx.compose.foundation.shape.CircleShape else MaterialTheme.shapes.medium)
                }
                val info: @Composable (Boolean) -> Unit = { start ->
                    val align = if (start) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.Center
                    Column(horizontalAlignment = if (start) Alignment.Start else Alignment.CenterHorizontally) {
                        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = align, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMediumMuted, textAlign = align, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (tracks.isNotEmpty()) {
                            val duration = tracks.sumOf { it.durationSeconds }
                            Text(plural(tracks.size, "track") + if (duration > 0) " · ${formatDuration(duration)}" else "", style = MaterialTheme.typography.labelMediumMuted, textAlign = align)
                        }
                    }
                }
                // Playback controls span the full width: equally reachable by either thumb, and the primary action is the widest.
                val actions: @Composable () -> Unit = {
                    if (tracks.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { onPlay(tracks, false) }, modifier = Modifier.weight(1.15f).heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)); Text("Play", style = MaterialTheme.typography.labelLarge)
                        }
                        FilledTonalButton(onClick = { onPlay(tracks, true) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Icon(Icons.Default.Shuffle, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Shuffle", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                        }
                        FilledTonalIconButton(onClick = { more = true }, modifier = Modifier.size(52.dp), shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.Default.MoreVert, "More actions for this ${item.kind.label.removeSuffix("s").lowercase()}")
                        }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val widthDp = maxWidth
                    if (widthDp >= 600.dp) Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        artwork(Modifier.width(240.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { info(true); Box(Modifier.widthIn(max = 480.dp)) { actions() } }
                    } else Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        artwork(Modifier.fillMaxWidth(if (widthDp < 380.dp) 0.62f else 0.68f).widthIn(max = 300.dp))
                        info(false)
                        actions()
                        if (!page?.description.isNullOrBlank()) Text(page!!.description, style = MaterialTheme.typography.bodySmallMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (more) ModalBottomSheet(onDismissRequest = { more = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
                        ListItem(headlineContent = { Text("Add to queue") }, leadingContent = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            modifier = Modifier.heightIn(min = 56.dp).clickable { more = false; tracks.forEach(onQueue) })
                        if (offline != null) ListItem(headlineContent = { Text("Save loaded tracks offline") }, leadingContent = { Icon(Icons.Default.Download, null) },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            modifier = Modifier.heightIn(min = 56.dp).clickable { more = false; offline.save(tracks) })
                    }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp)) }
            if (error != null) item {
                Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
                TextButton(onClick = if (page != null && page.continuation != null) onLoadMore else onRetry, enabled = !loadingMore) { Text("Retry") }
            }
            if (!loading && page != null && page.shelves.isEmpty() && error == null) item {
                Text("No available items in this collection.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
            page?.shelves?.forEachIndexed { index, shelf ->
                val playable = shelf.items.all { it.track() != null }
                if (playable) {
                    if (shelf.title.isNotBlank()) item { Text(shelf.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(20.dp)) }
                    // A playlist may legitimately contain the same video twice, so the position is part of the key.
                    itemsIndexed(shelf.items, key = { position, it -> "$index:$position:${it.kind}:${it.id}" }) { _, entry ->
                        val track = entry.track()?.withCover(albumCover) ?: return@itemsIndexed
                        TrackRow(track, onClick = { onPlay(shownTracks.dropWhile { it.id != track.id }, false) },
                            onAddToQueue = { onQueue(track) })
                    }
                } else item { CatalogShelfRow(shelf, onOpen) }
            }
            if (page?.continuation != null && page.shelves.sumOf { it.items.size } < 2000) item {
                TextButton(enabled = !loadingMore, onClick = onLoadMore) { Text(if (loadingMore) "Loading…" else "Load more") }
            }
        }
    }
}

@Preview(name = "Artist 360", showBackground = true, widthDp = 360, heightDp = 780)
@Preview(name = "Artist 412", showBackground = true, widthDp = 412, heightDp = 892)
@Preview(name = "Artist 700", showBackground = true, widthDp = 700, heightDp = 900)
@Composable
fun CatalogDetailPreview() {
    KasaneTheme(dynamicColor = false) {
        val item = CatalogItem("UCpreview", CatalogKind.ARTIST, "KIRA", "Artist")
        val page = CatalogPage(item.title, "1.2M subscribers", null, listOf(CatalogShelf("Songs", (1..4).map {
            CatalogItem("song$it", CatalogKind.SONG, "Popular song number $it", "KIRA", durationSeconds = 180.0 + it) })))
        Surface(color = MaterialTheme.colorScheme.background) { CatalogDetailContent(item, page, false, null, false, {}, {}, {}, { _, _ -> }, {}, {}) }
    }
}
