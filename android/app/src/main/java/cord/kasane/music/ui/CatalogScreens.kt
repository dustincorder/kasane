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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.*
import cord.kasane.music.provider.OnlineCatalog
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
        if (shelf.title.isNotBlank()) Text(shelf.title, style = KasaneTypography.titleLarge, color = White,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(HomeFeedPolicy.uniqueShelfItems(shelf.items), key = { "${it.kind}:${it.id}" }) { item ->
                Column(Modifier.width(148.dp).clickable { onOpen(item) }) {
                    TrackArtwork(item.artwork, Modifier.size(148.dp))
                    Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = White,
                        style = KasaneTypography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                    Text(item.subtitle.ifBlank { item.kind.label }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Gray400)
                }
            }
        }
    }
}

@Composable
fun CatalogSearchScreen(catalog: OnlineCatalog, onOpen: (CatalogItem) -> Unit,
    currentTrackId: String?, favoriteIds: Set<String>, onFavorite: (Track) -> Unit, onQueue: (Track) -> Unit, onPlayNext: ((Track) -> Unit)? = null,
    cachedCatalog: CachedCatalogRepository? = null, cacheScope: String = "guest") {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(CatalogKind.SONG) }
    var revision by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var continuation by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val requester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var loadingMore by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var handledRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { requester.requestFocus() }
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
    Column(Modifier.fillMaxSize().background(Gray950)) {
        OutlinedTextField(query, { query = it }, singleLine = true,
            placeholder = { Text("Search YouTube Music") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear search") } },
            modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(requester),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { revision++; focus.clearFocus() }))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CatalogKind.entries) { kind ->
                KasaneChoiceChip(kind.label, category == kind, { category = kind })
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = AccentRose)
        if (query.isBlank()) Text("Songs, albums, artists and playlists. Find your next listen.", color = Gray400, modifier = Modifier.padding(24.dp))
        else if (!loading && error == null && items.isEmpty()) Text("No results for “$query”. Try another search.", color = Gray400, modifier = Modifier.padding(24.dp))
        error?.let { message ->
            Text(message, color = Gray400, modifier = Modifier.padding(16.dp))
            TextButton(onClick = { revision++ }) { Text("Retry search") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { "${it.kind}:${it.id}" }) { item ->
                val track = item.track()
                if (track != null) TrackRow(track, isPlaying = track.id == currentTrackId,
                    onClick = { onOpen(item); focus.clearFocus() }, onPlayNext = onPlayNext?.let { action -> { action(track) } }, onAddToQueue = { onQueue(track) },
                    onToggleFavorite = { onFavorite(track) }, isFavorite = track.id in favoriteIds)
                else CatalogResult(item) { onOpen(item); focus.clearFocus() }
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

@Composable
private fun CatalogResult(item: CatalogItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        TrackArtwork(item.artwork, Modifier.size(56.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = White)
            Text(item.subtitle.ifBlank { item.kind.label }, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Gray400)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Gray400)
    }
}

@Composable
fun CatalogDetailScreen(item: CatalogItem, catalog: OnlineCatalog, onBack: () -> Unit,
    onOpen: (CatalogItem) -> Unit, onPlay: (List<Track>, Boolean) -> Unit, onQueue: (Track) -> Unit,
    cachedCatalog: CachedCatalogRepository? = null, cacheScope: String = "guest") {
    var page by remember(item.id) { mutableStateOf<CatalogPage?>(null) }
    var revision by remember(item.id) { mutableIntStateOf(0) }
    var loading by remember(item.id) { mutableStateOf(true) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var loadingMore by remember(item.id) { mutableStateOf(false) }
    var handledRevision by remember(item.id) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(item.id, revision, cachedCatalog, cacheScope) {
        error = null
        val saved = cachedCatalog?.cached(cacheScope, "browse:${item.id}")
        page = saved?.page
        val force = revision != handledRevision
        val stale = saved == null || System.currentTimeMillis() - saved.updatedAt >= CachedCatalogRepository.LIBRARY_TTL_MS
        if (!stale && !force) { loading = false; return@LaunchedEffect }
        loading = saved == null
        try { page = if (cachedCatalog != null) cachedCatalog.browse(cacheScope, item.id, force = force) else catalog.browse(item.id) }
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
                val next = cachedCatalog?.browse(cacheScope, item.id, token) ?: catalog.browse(item.id, token)
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
fun CatalogDetailContent(item: CatalogItem, page: CatalogPage?, loading: Boolean, error: String?,
    loadingMore: Boolean, onBack: () -> Unit, onRetry: () -> Unit, onOpen: (CatalogItem) -> Unit,
    onPlay: (List<Track>, Boolean) -> Unit, onQueue: (Track) -> Unit, onLoadMore: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Gray950)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = White) }
            Text(item.kind.label, style = KasaneTypography.titleMedium)
        }
        LazyColumn(state = rememberLazyListState(), modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TrackArtwork(page?.artwork ?: item.artwork, Modifier.widthIn(max = 240.dp).fillMaxWidth().aspectRatio(1f))
                    Text(page?.title?.ifBlank { item.title } ?: item.title, style = KasaneTypography.titleLarge, color = White)
                    Text(page?.subtitle?.ifBlank { item.subtitle } ?: item.subtitle, color = Gray400)
                    if (!page?.description.isNullOrBlank()) Text(page!!.description, color = Gray400)
                    val tracks = page?.tracks.orEmpty()
                    if (tracks.isNotEmpty()) {
                        val duration = tracks.sumOf { it.durationSeconds }
                        Text("${tracks.size} loaded tracks" + if (duration > 0) " · ${formatDuration(duration)}" else "", color = Gray400)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onPlay(tracks, false) }) { Text("Play", color = Gray950) }
                            OutlinedButton(onClick = { onPlay(tracks, true) }) { Text("Shuffle", color = AccentRose) }
                        }
                        TextButton(onClick = { tracks.forEach(onQueue) }) { Text("Add loaded tracks to queue") }
                    }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp), color = AccentRose) }
            if (error != null) item {
                Text(error, color = Gray400, modifier = Modifier.padding(20.dp))
                TextButton(onClick = if (page != null && page.continuation != null) onLoadMore else onRetry, enabled = !loadingMore) { Text("Retry") }
            }
            if (!loading && page != null && page.shelves.isEmpty() && error == null) item {
                Text("No available items in this collection.", color = Gray400, modifier = Modifier.padding(20.dp))
            }
            page?.shelves?.forEachIndexed { index, shelf ->
                val playable = shelf.items.all { it.track() != null }
                if (playable) {
                    if (shelf.title.isNotBlank()) item { Text(shelf.title, style = KasaneTypography.titleMedium, modifier = Modifier.padding(20.dp)) }
                    items(shelf.items, key = { "$index:${it.kind}:${it.id}" }) { entry ->
                        val track = entry.track() ?: return@items
                        TrackRow(track, onClick = { onPlay(page.tracks.dropWhile { it.id != track.id }, false) },
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

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun CatalogDetailPreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        val item = CatalogItem("preview-album", CatalogKind.ALBUM, "Evening sessions", "Kasane Preview")
        val page = CatalogPage(item.title, item.subtitle, null, listOf(CatalogShelf("Tracks", listOf(
            CatalogItem("preview-track", CatalogKind.SONG, "A very long song title across the evening", "Preview artist", durationSeconds = 180.0)))))
        Surface(color = Gray950) { CatalogDetailContent(item, page, false, null, false, {}, {}, {}, { _, _ -> }, {}, {}) }
    }
}
