package cord.kasane.desktop.library

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.model.Track
import cord.kasane.music.provider.CloudLikeState
import cord.kasane.music.provider.CloudLikes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Adapter over the shared [CloudLikes] core: YouTube Music ratings as the single source of
 * truth — no local favorites. The shared class owns the memo, permits and rollback logic; this
 * wrapper binds the current [session] into every call, keeps Track hints for surfaces that list
 * liked songs, and can rebuild the core on sign-out. Guest sessions expose no like actions.
 */
class DesktopLikes(
    scope: CoroutineScope,
    private val session: () -> AccountSession?,
    private val read: suspend (AccountSession, String) -> CloudLikeState,
    private val write: suspend (AccountSession, String, Boolean) -> Unit,
    private val writeDislike: suspend (AccountSession, String, Boolean) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob())
    // Mirrored flows stay stable across [clear] rebuilds — collectors never re-subscribe.
    private val mutableStates = MutableStateFlow<Map<String, CloudLikeState>>(emptyMap())
    val states = mutableStates.asStateFlow()
    private val mutablePending = MutableStateFlow<Set<String>>(emptySet())
    val pending = mutablePending.asStateFlow()
    private val mutableErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors = mutableErrors.asSharedFlow()
    /** Last seen Track for a rated id, so surfaces that list liked songs can add a fresh like. */
    private val trackHints = mutableMapOf<String, Track>()
    fun trackHint(id: String): Track? = trackHints[id]

    @Volatile private var impl: CloudLikes = newImpl()
    private var mirrorJob: Job? = attach(impl)

    private fun newImpl() = CloudLikes(
        read = { id -> session()?.let { read(it, id) } ?: CloudLikeState.UNKNOWN },
        write = { id, liked -> session()?.let { write(it, id, liked) } },
        writeDislike = { id, disliked -> session()?.let { writeDislike(it, id, disliked) } },
        now = now,
        dispatcher = dispatcher)

    private fun attach(instance: CloudLikes) = scope.launch {
        launch { instance.states.collect { mutableStates.value = it } }
        launch { instance.pending.collect { mutablePending.value = it } }
        launch { instance.errors.collect { mutableErrors.emit(it) } }
    }

    /** Likes only exist for a signed-in YouTube session. */
    val enabled: Boolean get() = session() != null

    fun refresh(id: String, force: Boolean = false) {
        if (session() == null || !id.matches(Regex("[A-Za-z0-9_-]{11}"))) return
        impl.refresh(id, force)
    }

    /** Marks playlist rows as liked without refetching; keeps Track hints for future inserts. */
    fun seedLiked(tracks: List<Track>) {
        tracks.forEach { trackHints[it.id] = it }
        impl.seedLiked(tracks.map { it.id })
    }

    fun toggle(track: Track) {
        if (session() == null) return
        trackHints[track.id] = track
        impl.toggle(track.id)
    }

    fun toggleDislike(track: Track) {
        if (session() == null) return
        trackHints[track.id] = track
        impl.toggleDislike(track.id)
    }

    /** Sign-out: in-flight writes die with the old core, ratings and hints start empty. */
    fun clear() {
        mirrorJob?.cancel()
        impl.close()
        mutableStates.value = emptyMap()
        mutablePending.value = emptySet()
        trackHints.clear()
        impl = newImpl()
        mirrorJob = attach(impl)
    }

    fun close() {
        impl.close()
        scope.cancel()
    }
}
