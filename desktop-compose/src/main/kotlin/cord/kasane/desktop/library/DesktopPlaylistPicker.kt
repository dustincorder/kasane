package cord.kasane.desktop.library

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.auth.SessionExpiredException
import cord.kasane.music.model.Track
import cord.kasane.music.provider.AccountPlaylistProvider
import cord.kasane.music.provider.CatalogFailure
import cord.kasane.music.provider.CatalogFailureReason
import cord.kasane.music.provider.PlaylistDestination
import cord.kasane.music.provider.PlaylistPicker
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.ContinuationInterceptor
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

typealias PlaylistPickerState = cord.kasane.music.provider.PlaylistPickerState

/**
 * "Add to playlist" for one track — a session-bound adapter over the shared [PlaylistPicker]
 * state machine (membership is confirmed by read-back, never assumed). The shared picker binds
 * one immutable account; this wrapper captures the session at open time, rebuilds the core when
 * the account changes and translates the desktop catalog's `CatalogFailure(SESSION_EXPIRED)`
 * into the `SessionExpiredException` the shared machine expects.
 */
class DesktopPlaylistPicker(
    scope: CoroutineScope,
    private val session: () -> AccountSession?,
    private val provider: AccountPlaylistProvider,
    private val onAdded: suspend (String) -> Unit = {},
    dispatcher: CoroutineDispatcher? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob())
    private val dispatcher = dispatcher
        ?: scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
        ?: Dispatchers.Default
    private val mutableState = MutableStateFlow(PlaylistPickerState())
    val state = mutableState.asStateFlow()
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = mutableMessages.asSharedFlow()

    /** The desktop catalog reports expiry as [CatalogFailure]; the shared picker wants the exception. */
    private val translated = object : AccountPlaylistProvider {
        override suspend fun playlistDestinations(account: AccountSession, videoId: String): List<PlaylistDestination> =
            try { provider.playlistDestinations(account, videoId) }
            catch (e: CancellationException) { throw e }
            catch (e: CatalogFailure) { throw if (e.reason == CatalogFailureReason.SESSION_EXPIRED) SessionExpiredException() else e }
        override suspend fun addVideoToPlaylist(account: AccountSession, playlistId: String, videoId: String) =
            try { provider.addVideoToPlaylist(account, playlistId, videoId) }
            catch (e: CancellationException) { throw e }
            catch (e: CatalogFailure) { throw if (e.reason == CatalogFailureReason.SESSION_EXPIRED) SessionExpiredException() else e }
    }

    @Volatile private var account: AccountSession? = null
    @Volatile private var impl: PlaylistPicker? = null
    private var mirrorJob: Job? = null

    private fun implFor(account: AccountSession): PlaylistPicker {
        if (this.account != account || impl == null) {
            mirrorJob?.cancel(); impl?.close()
            this.account = account
            val picker = PlaylistPicker(account, translated, onAdded, dispatcher)
            impl = picker
            mirrorJob = scope.launch {
                launch { picker.state.collect { mutableState.value = it } }
                launch { picker.messages.collect { mutableMessages.emit(it) } }
            }
        }
        return impl!!
    }

    fun open(track: Track) {
        if (track.isLocal || track.sourceProvider != "youtube" || !track.id.matches(Regex("[A-Za-z0-9_-]{11}"))) return
        val account = session() ?: run { mutableMessages.tryEmit("Sign in to add songs to playlists."); return }
        implFor(account).open(track)
    }

    fun refresh() { impl?.refresh() }
    fun add(playlistId: String) { impl?.add(playlistId) }
    fun dismiss() { impl?.dismiss() }

    override fun close() {
        mirrorJob?.cancel()
        impl?.close()
        scope.cancel()
    }
}
