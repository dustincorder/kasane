package cord.kasane.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import cord.kasane.music.data.TrackDao
import cord.kasane.music.model.Track
import cord.kasane.music.provider.MusicProvider
import cord.kasane.music.provider.YouTubeMusicProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackUiState(
    val currentTrack: Track? = null, val isPlaying: Boolean = false, val isLoading: Boolean = false,
    val currentPositionMs: Long = 0, val durationMs: Long = 0,
    val queue: List<Track> = emptyList(), val queueIndex: Int = -1, val errorMessage: String? = null,
    val shuffleEnabled: Boolean = false, val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val canSkipNext: Boolean = false, val canSkipPrevious: Boolean = false
)

/** A projection of MediaController: no second queue, resolver job, or playback engine. */
class PlaybackManager(context: Context, private val trackDao: TrackDao,
    @Suppress("UNUSED_PARAMETER") ytProvider: MusicProvider = YouTubeMusicProvider()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableState = MutableStateFlow(PlaybackUiState())
    val uiState = mutableState.asStateFlow()
    private var dismissedError: androidx.media3.common.PlaybackException? = null
    private var controller: MediaController? = null
    private val pendingCommands = ArrayDeque<(Player) -> Unit>()
    init {
        val future = MediaController.Builder(context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        future.addListener({
            try {
                val connected = future.get() // Completed future; never blocks on a network request.
                controller = connected
                connected.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { publish(player) }
                })
                while (pendingCommands.isNotEmpty()) pendingCommands.removeFirst()(connected)
                publish(connected)
            } catch (e: Exception) {
                pendingCommands.clear()
                mutableState.value = mutableState.value.copy(isLoading = false,
                    errorMessage = "Could not connect to the playback service. Restart Kasane and try again.")
            }
        }, MoreExecutors.directExecutor())
        scope.launch {
            while (isActive) { controller?.let(::publish); delay(400) }
        }
    }
    private fun publish(player: Player) {
        val queue = (0 until player.mediaItemCount).map { PlaybackService.mediaItemToTrack(player.getMediaItemAt(it)) }
        mutableState.value = PlaybackUiState(
            currentTrack = player.currentMediaItem?.let(PlaybackService::mediaItemToTrack),
            isPlaying = player.isPlaying, isLoading = player.playbackState == Player.STATE_BUFFERING,
            currentPositionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.coerceAtLeast(0), queue = queue,
            queueIndex = if (queue.isEmpty()) -1 else player.currentMediaItemIndex,
            errorMessage = player.playerError?.takeUnless { it === dismissedError }?.let {
                val streamError = generateSequence<Throwable>(it) { cause -> cause.cause }
                    .firstOrNull { cause -> cause is cord.kasane.music.provider.StreamUnavailableException ||
                        cause is cord.kasane.music.provider.sabr.SabrException ||
                        cause is cord.kasane.music.provider.AudioHttpException || cause is cord.kasane.music.provider.TransportUnavailableException || cause is cord.kasane.music.provider.PlayerHttpException }
                if (streamError != null) streamError.message
                else if (it.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
                    "The audio server rejected this stream. This does not mean the track or your account is unavailable."
                else "Could not play this track. Check your connection or try another track."
            },
            shuffleEnabled = player.shuffleModeEnabled, repeatMode = player.repeatMode,
            canSkipNext = player.hasNextMediaItem(), canSkipPrevious = player.hasPreviousMediaItem() || player.currentPosition > 3000)
    }
    private fun command(action: (Player) -> Unit) {
        val player = controller
        if (player != null) { action(player); publish(player) }
        else if (pendingCommands.size < 20) pendingCommands.addLast(action)
    }
    fun playTrack(track: Track) = playSingle(track, "single")
    /** Selecting an existing queue entry must preserve its collection/radio context. */
    fun playQueueItem(trackId: String) = command { player ->
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == trackId }
        if (index != null) {
            if (index != player.currentMediaItemIndex || player.playbackState == Player.STATE_ENDED || player.playerError != null)
                player.seekToDefaultPosition(index)
            player.prepare(); player.play()
        }
    }
    fun startRadio(track: Track) = playSingle(track, "forced")
    private fun playSingle(track: Track, radioMode: String) = command { player ->
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == track.id }
        if (index != null) {
            player.replaceMediaItem(index, PlaybackService.trackToMediaItem(track, radioMode = radioMode))
            player.seekToDefaultPosition(index)
        }
        else {
            player.addMediaItem(player.currentMediaItemIndex.plus(1).coerceIn(0, player.mediaItemCount),
                PlaybackService.trackToMediaItem(track, radioMode = radioMode))
            val added = (0 until player.mediaItemCount).first { player.getMediaItemAt(it).mediaId == track.id }
            player.seekToDefaultPosition(added)
        }
        player.prepare(); player.play()
    }
    fun playTracks(tracks: List<Track>, shuffle: Boolean = false) {
        val prepared = QueuePolicy.prepare(tracks)
        if (prepared.isEmpty()) return
        val collectionContext = java.util.UUID.randomUUID().toString()
        command { player ->
            player.setMediaItems(prepared.map { PlaybackService.trackToMediaItem(it, radioMode = "collection", collectionContext = collectionContext) }, if (shuffle) prepared.indices.random() else 0, 0)
            player.shuffleModeEnabled = shuffle
            player.prepare(); player.play()
        }
    }
    private fun promoteManual(player: Player, index: Int) {
        if (index == player.currentMediaItemIndex) return
        val item = player.getMediaItemAt(index)
        if (item.mediaMetadata.extras?.getString("radioMode") != "radio") return
        val extras = android.os.Bundle(item.mediaMetadata.extras).apply { putString("radioMode", "manual") }
        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build())
    }
    fun addToQueue(track: Track) = command { player ->
        val existing = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == track.id }
        if (existing != null) promoteManual(player, existing)
        else if (player.mediaItemCount < QueuePolicy.LIMIT) player.addMediaItem(PlaybackService.trackToMediaItem(track))
    }
    fun playNextInQueue(track: Track) = command { player ->
        val existing = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == track.id }
        val target = QueuePolicy.nextInsertionIndex(player.currentMediaItemIndex, existing, player.mediaItemCount)
        if (existing != null) {
            promoteManual(player, existing)
            if (existing != player.currentMediaItemIndex) player.moveMediaItem(existing, target.coerceAtMost(player.mediaItemCount - 1))
        } else if (player.mediaItemCount < QueuePolicy.LIMIT) player.addMediaItem(target, PlaybackService.trackToMediaItem(track))
    }
    fun retryPlayback() = command { dismissedError = null; it.prepare(); it.play() }
    fun togglePlayPause() = command {
        if (it.playWhenReady && it.playbackState != Player.STATE_ENDED) it.pause()
        else {
            if (it.playbackState == Player.STATE_ENDED) {
                if (it.hasNextMediaItem()) it.seekToNextMediaItem() else it.seekToDefaultPosition()
            }
            if (it.playbackState == Player.STATE_IDLE || it.playbackState == Player.STATE_ENDED) it.prepare()
            it.play()
        }
    }
    fun playNext() = command { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    fun playPrevious() = command { it.seekToPrevious() }
    fun seekTo(positionMs: Long) = command { it.seekTo(positionMs.coerceIn(0, it.duration.coerceAtLeast(0))) }
    fun toggleShuffle() = command { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = command { it.repeatMode = when (it.repeatMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    } }
    fun clearError() { dismissedError = controller?.playerError; mutableState.value = mutableState.value.copy(errorMessage = null) }
}
