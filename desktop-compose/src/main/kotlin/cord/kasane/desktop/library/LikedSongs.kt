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

    /** Loads the first page. Safe to call again after sign-in/out. */
    fun refresh() {
        if (!enabled()) { clear(); return }
        loadJob?.cancel()
        loaded = true
        fetch(null)
    }

    fun loadMore() {
        val cont = continuation ?: return
        if (!enabled() || _loading.value) return
        fetch(cont)
    }

    fun clear() {
        loadJob?.cancel()
        _tracks.value = emptyList()
        continuation = null
        _hasMore.value = false
        _error.value = null
        loaded = false
    }

    private fun fetch(cont: String?) {
        loadJob = scope.launch {
            _loading.value = true
            _error.value = null
            try {
                val page = loadPage(cont)
                val fresh = page.shelves.flatMap { it.items }.mapNotNull { it.track() }
                likes.seedLiked(fresh)
                _tracks.update { old -> ((if (cont == null) emptyList() else old) + fresh).distinctBy { it.id } }
                continuation = page.continuation?.takeUnless { it == cont }
                _hasMore.value = continuation != null
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                _error.value = "Couldn't load your liked songs. Check your connection and try again."
            } finally { _loading.value = false }
        }
    }
}
