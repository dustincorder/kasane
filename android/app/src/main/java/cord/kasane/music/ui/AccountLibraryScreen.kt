package cord.kasane.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cord.kasane.music.auth.AccountState
import cord.kasane.music.model.*
import cord.kasane.music.provider.*
import cord.kasane.music.ui.components.*
import cord.kasane.music.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AccountLibraryScreen(state: AccountState, provider: AccountLibraryProvider, onSignIn: () -> Unit,
    onOpen: (CatalogItem) -> Unit, onQueue: (Track) -> Unit, onExpired: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(AccountCollection.LIKES) }
    var page by remember { mutableStateOf<CatalogPage?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingLike by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state, selected, revision) {
        generation++
        page = null; error = null; loading = false
        if (state !is AccountState.SignedIn) return@LaunchedEffect
        loading = true
        try { page = provider.collection(selected) }
        catch (e: CancellationException) { throw e }
        catch (e: cord.kasane.music.auth.SessionExpiredException) { onExpired() }
        catch (e: Exception) { error = e.message ?: "Could not load your account library." }
        finally { loading = false }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text("YouTube Music library", style = KasaneTypography.titleLarge, color = White, modifier = Modifier.padding(20.dp))
        if (state !is AccountState.SignedIn) {
            Text("Sign in to access account collections. Your device favorites and history remain separate.", color = Gray400, modifier = Modifier.padding(horizontal = 20.dp))
            TextButton(onClick = onSignIn) { Text("Account sign-in options") }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AccountCollection.entries) { entry -> KasaneChoiceChip(entry.label, selected == entry, { selected = entry }) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = AccentRose)
            error?.let { Text(it, color = Gray400, modifier = Modifier.padding(20.dp)); TextButton(onClick = { revision++ }) { Text("Retry") } }
            val items = page?.shelves?.flatMap { it.items }.orEmpty()
            if (!loading && error == null && items.isEmpty()) Text("No saved items in this collection.", color = Gray400, modifier = Modifier.padding(20.dp))
            LazyColumn(Modifier.heightIn(max = 560.dp)) {
                items(items.distinctBy { it.kind to it.id }, key = { "${it.kind}:${it.id}" }) { item ->
                    val track = item.track()
                    if (track != null) TrackRow(track, onClick = { onOpen(item) },
                        onAddToQueue = { onQueue(track) }, isFavorite = selected == AccountCollection.LIKES,
                        onToggleFavorite = if (selected == AccountCollection.LIKES && pendingLike == null) ({
                            pendingLike = track.id
                            scope.launch {
                                try { provider.setLiked(track.id, false); revision++ }
                                catch (e: CancellationException) { throw e }
                                catch (e: cord.kasane.music.auth.SessionExpiredException) { onExpired() }
                                catch (e: Exception) { error = e.message ?: "Could not remove the like." }
                                finally { pendingLike = null }
                            }
                        }) else null)
                    else TextButton(onClick = { onOpen(item) }) { Text(item.title, color = White) }
                }
                if (page?.continuation != null && items.size < 2000) item {
                    TextButton(enabled = !loading, onClick = {
                        val current = page ?: return@TextButton
                        val ticket = generation
                        val collection = selected
                        loading = true
                        scope.launch {
                            try {
                                val next = provider.collection(collection, current.continuation)
                                if (ticket != generation) return@launch
                                page = current.copy(shelves = current.shelves + next.shelves,
                                    continuation = next.continuation?.takeUnless { it == current.continuation })
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

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 390, heightDp = 500)
@Composable
fun AccountLibraryGuestPreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        Surface(color = Gray950) {
            val previewProvider = remember { object : AccountLibraryProvider {
                override suspend fun collection(collection: AccountCollection, continuation: String?) =
                    CatalogPage("", "", null, emptyList())
                override suspend fun setLiked(videoId: String, liked: Boolean) = Unit
            } }
            AccountLibraryScreen(AccountState.Guest, previewProvider, {}, {}, {}, {})
        }
    }
}
