package cord.kasane.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cord.kasane.music.auth.AccountState
import cord.kasane.music.auth.AccountSession
import cord.kasane.music.data.CachedCatalogRepository
import cord.kasane.music.data.CatalogCache
import cord.kasane.music.model.*
import cord.kasane.music.provider.*
import cord.kasane.music.ui.components.TrackArtwork
import cord.kasane.music.ui.components.*
import cord.kasane.music.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val YtmLikedSongs = CatalogItem("VLLM", CatalogKind.PLAYLIST, "Liked Songs", "YouTube Music")

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AccountLibraryScreen(state: AccountState, provider: AccountLibraryProvider, onSignIn: () -> Unit,
    onOpen: (CatalogItem) -> Unit, onQueue: (Track) -> Unit, onExpired: () -> Unit,
    modifier: Modifier = Modifier, cachedCatalog: CachedCatalogRepository? = null,
    session: AccountSession? = null, onRefreshState: ((Boolean) -> Unit)? = null,
    selected: AccountCollection = AccountCollection.PLAYLISTS, onRefresh: (() -> Unit)? = null) {
    var page by remember { mutableStateOf<CatalogPage?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var handledRevision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val scopeKey = remember(session, state) {
        CatalogCache.accountScope(session, (state as? AccountState.SignedIn)?.identity)
    }
    // Opening a playlist navigates to the shared detail page; this screen only ever lists collections.
    LaunchedEffect(state, selected, revision, scopeKey) {
        generation++
        error = null
        if (state !is AccountState.SignedIn) { page = null; loading = false; return@LaunchedEffect }
        val key = "library:${selected.name}"
        val forceRefresh = revision != handledRevision
        val saved = cachedCatalog?.cached(scopeKey, key)
        page = saved?.page
        val stale = saved == null || System.currentTimeMillis() - saved.updatedAt >= CachedCatalogRepository.LIBRARY_TTL_MS || forceRefresh
        if (!stale) { loading = false; onRefreshState?.invoke(false); return@LaunchedEffect }
        loading = true
        onRefreshState?.invoke(true)
        try {
            page = if (cachedCatalog != null && session != null)
                cachedCatalog.library(scopeKey, session, selected, null, force = forceRefresh)
            else provider.collection(selected)
        }
        catch (e: CancellationException) { throw e }
        catch (e: cord.kasane.music.auth.SessionExpiredException) { onExpired() }
        catch (e: Exception) { error = e.message ?: "Could not load your account library." }
        finally { loading = false; handledRevision = revision; onRefreshState?.invoke(false) }
    }
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing = loading && page != null, onRefresh = { revision++ }, modifier = modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        if (state !is AccountState.SignedIn) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                Text("Sign in to see your YouTube Music library", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
                Text("Your on-device favorites and listening history stay separate.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMediumMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick = onSignIn, modifier = Modifier.padding(top = 8.dp)) { Text("Sign in") }
            }
        } else {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.primary)
            error?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)); TextButton(onClick = { revision++ }) { Text("Retry") } }
            val serverEntries = page?.shelves?.flatMap { it.items }.orEmpty()
            val entries = buildList {
                addAll(serverEntries)
                if (page != null && selected == AccountCollection.PLAYLISTS &&
                    serverEntries.none { it.kind == CatalogKind.PLAYLIST && it.id == YtmLikedSongs.id }) add(YtmLikedSongs)
            }.distinctBy { it.kind to it.id }
            if (!loading && error == null && entries.isEmpty()) Text("No saved items in this collection.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            LazyColumn(state = androidx.compose.foundation.lazy.rememberLazyListState(), modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(entries, key = { "${it.kind}:${it.id}" }) { item ->
                    val track = item.track()
                    if (track != null) TrackRow(track, onClick = { onOpen(item) },
                        onAddToQueue = { onQueue(track) }, isFavorite = false, onToggleFavorite = null)
                    else Row(
                        Modifier.fillMaxWidth().clickable { onOpen(item) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            if (!item.artwork.isNullOrBlank()) TrackArtwork(item.artwork, Modifier.fillMaxSize())
                            else Icon(if (item.id == YtmLikedSongs.id) Icons.Default.Favorite else Icons.Default.LibraryMusic,
                                contentDescription = null, tint = if (item.id == YtmLikedSongs.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(if (item.id == YtmLikedSongs.id) "Playlist · YouTube Music" else item.subtitle.ifBlank { item.kind.label },
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmallMuted, maxLines = 1)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (page?.continuation != null && entries.size < 2000) item {
                    TextButton(enabled = !loading, onClick = {
                        val current = page ?: return@TextButton
                        val ticket = generation
                        val collection = selected
                        loading = true
                        scope.launch {
                            try {
                                val next = if (cachedCatalog != null && session != null)
                                    cachedCatalog.library(scopeKey, session, collection, null, current.continuation)
                                else provider.collection(collection, current.continuation)
                                if (ticket != generation) return@launch
                                page = if (cachedCatalog != null) next else current.copy(
                                    shelves = (current.shelves + next.shelves).groupBy { it.title }.map { (title, shelves) ->
                                        CatalogShelf(title, shelves.flatMap { it.items }.distinctBy { it.kind to it.id })
                                    }, continuation = next.continuation?.takeUnless { it == current.continuation })
                            } catch (e: CancellationException) { throw e }
                            catch (e: cord.kasane.music.auth.SessionExpiredException) { onExpired() }
                            catch (e: Exception) { if (ticket == generation) error = e.message ?: "Could not load more items." }
                            finally { if (ticket == generation) loading = false }
                        }
                    }) { Text("Load more") }
                }
            }
        }
    }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 390, heightDp = 500)
@Composable
fun AccountLibraryGuestPreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            val previewProvider = remember { object : AccountLibraryProvider {
                override suspend fun collection(collection: AccountCollection, continuation: String?) =
                    CatalogPage("", "", null, emptyList())
                override suspend fun playlist(playlistId: String, continuation: String?) = CatalogPage("", "", null, emptyList())
                override suspend fun setLiked(videoId: String, liked: Boolean) = Unit
            } }
            AccountLibraryScreen(AccountState.Guest, previewProvider, {}, {}, {}, {}, Modifier.fillMaxSize())
        }
    }
}
