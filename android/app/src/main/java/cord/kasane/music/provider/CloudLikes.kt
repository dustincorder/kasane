package cord.kasane.music.provider

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/** Existing Android entrypoint delegates status semantics to common code. */
object CloudLikeParser {
    fun parse(root: JSONObject, videoId: String): CloudLikeState =
        CloudLikeJsonParser.parse(legacyCatalogJson(root) as kotlinx.serialization.json.JsonObject, videoId)
}

/** One instance per account/channel session. No local Favorites, cookies, or global song-title matching. */
class CloudLikes(
    private val read: suspend (String) -> CloudLikeState,
    private val write: suspend (String, Boolean) -> Unit,
    private val writeDislike: (suspend (String, Boolean) -> Unit)? = null,
    private val now: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableStates = MutableStateFlow<Map<String, CloudLikeState>>(emptyMap())
    val states = mutableStates.asStateFlow()
    private val mutablePending = MutableStateFlow<Set<String>>(emptySet())
    val pending = mutablePending.asStateFlow()
    private val mutableErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors = mutableErrors.asSharedFlow()
    private val updated = mutableMapOf<String, Long>()
    private val jobs = mutableMapOf<String, Job>()
    private val permits = Semaphore(3)
    fun refresh(id: String, force: Boolean = false) {
        if (jobs[id]?.isActive == true || (!force && now() - (updated[id] ?: Long.MIN_VALUE / 2) < 300000)) return
        jobs[id] = scope.launch {
            val previous = mutableStates.value[id] ?: CloudLikeState.UNKNOWN
            if (previous == CloudLikeState.UNKNOWN) mutableStates.update { it + (id to CloudLikeState.LOADING) }
            try {
                val state = permits.withPermit { read(id) }
                mutableStates.update { old ->
                    val cached = old[id]
                    val resolved = if (state == CloudLikeState.UNKNOWN && cached in setOf(CloudLikeState.LIKED, CloudLikeState.DISLIKED, CloudLikeState.NEUTRAL)) cached!! else state
                    old + (id to resolved)
                }; updated[id] = now()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                mutableStates.update { old ->
                    val cached = old[id]
                    old + (id to if (cached in setOf(CloudLikeState.LIKED, CloudLikeState.DISLIKED, CloudLikeState.NEUTRAL)) cached!! else previous)
                }
                updated[id] = now() // Avoid refetch storms on recomposition while offline.
            }
        }
    }
    fun seedLiked(ids: List<String>) {
        mutableStates.update { old -> old + ids.filter { old[it] == null || old[it] in setOf(CloudLikeState.UNKNOWN, CloudLikeState.LOADING) }.associateWith { CloudLikeState.LIKED } }
    }
    val supportsDislike: Boolean get() = writeDislike != null
    fun toggle(id: String) {
        val liked = mutableStates.value[id] != CloudLikeState.LIKED
        mutate(id, if (liked) CloudLikeState.LIKED else CloudLikeState.NEUTRAL) { write(id, liked) }
    }
    fun toggleDislike(id: String) {
        val operation = writeDislike ?: return
        val disliked = mutableStates.value[id] != CloudLikeState.DISLIKED
        mutate(id, if (disliked) CloudLikeState.DISLIKED else CloudLikeState.NEUTRAL) { operation(id, disliked) }
    }
    private fun mutate(id: String, desired: CloudLikeState, operation: suspend () -> Unit) {
        if (id in mutablePending.value) return
        mutablePending.update { it + id }
        val previous = (mutableStates.value[id] ?: CloudLikeState.UNKNOWN).let { if (it == CloudLikeState.LOADING) CloudLikeState.UNKNOWN else it }
        jobs[id]?.cancel()
        mutableStates.update { it + (id to desired) }
        jobs[id] = scope.launch {
            try { operation(); updated[id] = now() }
            catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                mutableStates.update { it + (id to previous) }
                mutableErrors.tryEmit("Could not update your YouTube Music rating. Please retry.")
            } finally { mutablePending.update { it - id } }
        }
    }
    fun close() { scope.cancel() }
}
