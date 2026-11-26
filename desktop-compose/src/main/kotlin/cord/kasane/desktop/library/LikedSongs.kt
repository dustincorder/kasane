package cord.kasane.desktop.library

import cord.kasane.music.model.CatalogPage
import cord.kasane.music.model.Track
import cord.kasane.music.provider.CloudLikeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The account's "Liked Music" playlist (browse id VLLM/LM) with continuation paging. Ordering comes
 * from YouTube Music; local unlikes remove the row, fresh likes prepend it — [DesktopLikes] remains
 * the rating source of truth. Guest sessions expose nothing.
 */
class LikedSongs(
    private val scope: CoroutineScope,
    private val likes: DesktopLikes,
    private val loadPage: suspend (continuation: String?) -> CatalogPage,
    private val enabled: () -> Boolean,
) {
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks = _tracks.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _hasMore = MutableStateFlow(false)
    val hasMore = _hasMore.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var continuation: String? = null
    private var loadJob: Job? = null
    @Volatile private var loaded = false

    init {
        // Ratings changed anywhere (menu, player, another surface) reconcile this list.
        scope.launch {
            likes.states.collect { map ->
                _tracks.update { list ->
                    val kept = list.filter { map[it.id] != CloudLikeState.NEUTRAL && map[it.id] != CloudLikeState.DISLIKED }
                    // A like toggled outside this screen prepends the known track, newest first.
                    val prepended = map.asSequence()
                        .filter { it.value == CloudLikeState.LIKED }
                        .mapNotNull { likes.trackHint(it.key) }
                        .filter { t -> kept.none { it.id == t.id } }
                        .toList()
                    if (!loaded) kept else prepended + kept
                }
            }
        }
    }

    /** Syncs the whole playlist: pages are fetched until the continuation ends so rating hints
     *  and the Home shelf reflect the full liked list, not just the newest screenful. */
    fun refresh() {
        if (!enabled()) { clear(); return }
        loadJob?.cancel()
        loaded = true
        fetch(null, drain = true)
    }

    fun loadMore() {
        val cont = continuation ?: return
        if (!enabled() || _loading.value) return
        fetch(cont, drain = false)
    }

    fun clear() {
        loadJob?.cancel()
        _tracks.value = emptyList()
        continuation = null
        _hasMore.value = false
        _error.value = null
        loaded = false
    }

    private fun fetch(cont: String?, drain: Boolean) {
        loadJob = scope.launch {
            _loading.value = true
            _error.value = null
            try {
                var next = cont
                var pages = 0
                do {
                    val page = loadPage(next)
                    val fresh = page.shelves.flatMap { it.items }.mapNotNull { it.track() }
                    likes.seedLiked(fresh)
                    _tracks.update { old -> ((if (next == null) emptyList() else old) + fresh).distinctBy { it.id } }
                    next = page.continuation?.takeUnless { it == next }
                    _hasMore.value = next != null
                } while (drain && next != null && enabled() && ++pages < MAX_SYNC_PAGES)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                _error.value = "Couldn't load your liked songs. Check your connection and try again."
            } finally { _loading.value = false }
        }
    }

    /** Hard bound on one sync run (~5 000 rows): beyond it the list is either anomalous or
     *  YouTube is looping continuation tokens — a repeated token already ends the drain early. */
    private companion object { const val MAX_SYNC_PAGES = 50 }
}
