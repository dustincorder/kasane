package cord.kasane.music.provider

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.auth.SessionExpiredException
import cord.kasane.music.model.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Transient modal state, bound to one immutable account/channel session. */
data class PlaylistPickerState(
    val visible: Boolean = false,
    val track: Track? = null,
    val options: List<PlaylistDestination> = emptyList(),
    val loading: Boolean = false,
    val pendingPlaylist: String? = null,
    val error: String? = null,
    val refreshRequired: Boolean = false,
)

class PlaylistPicker(
    private val account: AccountSession,
    private val provider: AccountPlaylistProvider,
    private val onAdded: suspend (String) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(PlaylistPickerState())
    val state = mutableState.asStateFlow()
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = mutableMessages.asSharedFlow()
    private var load: Job? = null
    private var write: Job? = null
    private var generation = 0

    fun open(track: Track) {
        if (track.isLocal || track.sourceProvider != "youtube" || !track.id.matches(Regex("[A-Za-z0-9_-]{11}"))) return
        if (write?.isActive == true) { mutableMessages.tryEmit("Finishing the previous playlist change…"); return }
        if (mutableState.value.visible && mutableState.value.track?.id == track.id) return
        load?.cancel(); generation++
        mutableState.value = PlaylistPickerState(visible = true, track = track)
        refresh()
    }
    fun refresh() {
        val current = mutableState.value
        val track = current.track ?: return
        if (!current.visible || current.pendingPlaylist != null || load?.isActive == true) return
        val ticket = ++generation
        mutableState.value = current.copy(loading = true, error = null)
        load = scope.launch {
            try {
                val options = provider.playlistDestinations(account, track.id)
                if (ticket == generation) mutableState.value = mutableState.value.copy(options = options, loading = false, refreshRequired = false)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (ticket == generation) mutableState.value = mutableState.value.copy(loading = false,
                    error = if (e is SessionExpiredException) "Your YouTube Music session expired. Sign in again."
                    else "Could not load editable playlists. Check your connection and refresh.")
            }
        }
    }
    fun add(playlistId: String) {
        val current = mutableState.value
        val track = current.track ?: return
        if (!current.visible || current.loading || current.pendingPlaylist != null || current.refreshRequired || write?.isActive == true) return
        val target = current.options.firstOrNull { it.id == playlistId && !it.alreadyAdded } ?: return
        load?.cancel(); generation++
        mutableState.value = current.copy(pendingPlaylist = playlistId, error = null)
        write = scope.launch {
            var confirmed = false
            var error: Exception? = null
            try {
                provider.addVideoToPlaylist(account, playlistId, track.id)
                confirmed = true
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                error = e
                // A lost response can follow a committed server operation. Read
                // membership once; never automatically repeat a remote write.
                if (e !is SessionExpiredException) {
                    try { confirmed = provider.playlistDestinations(account, track.id).any { it.id == playlistId && it.alreadyAdded } }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (_: Exception) { /* Unconfirmed is an error, not an optimistic success. */ }
                }
            }
            if (confirmed) {
                try { onAdded(playlistId) } catch (e: CancellationException) { throw e
                } catch (_: Exception) { /* A local cache failure cannot undo a confirmed remote addition. */ }
                mutableState.value = mutableState.value.copy(visible = false, pendingPlaylist = null,
                    options = mutableState.value.options.map { if (it.id == playlistId) it.copy(alreadyAdded = true) else it })
                mutableMessages.tryEmit("Added to ${target.title.take(80)}")
            } else {
                mutableState.value = mutableState.value.copy(pendingPlaylist = null, refreshRequired = true,
                    error = if (error is SessionExpiredException) "Your YouTube Music session expired. Sign in again."
                    else "Could not confirm the addition. Refresh the list before trying again.")
            }
        }
    }
    fun dismiss() {
        generation++; load?.cancel(); load = null
        // Dismissing UI does not repeat or cancel an explicitly requested write.
        mutableState.value = mutableState.value.copy(visible = false, loading = false)
    }
    override fun close() { generation++; scope.cancel() }
}
