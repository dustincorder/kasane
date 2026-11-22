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
    val noticeMessage: String? = null,
    val shuffleEnabled: Boolean = false, val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val canSkipNext: Boolean = false, val canSkipPrevious: Boolean = false,
    /** Queue indices in the order they will actually play after the current item (follows shuffle). */
    val upcomingOrder: List<Int> = emptyList()
)

internal fun shouldPauseOnToggle(playWhenReady: Boolean, state: Int, hasError: Boolean): Boolean =
    playWhenReady && state != Player.STATE_IDLE && state != Player.STATE_ENDED && !hasError

/** A projection of MediaController: no second queue, resolver job, or playback engine. */
class PlaybackManager(context: Context, private val trackDao: TrackDao,
    @Suppress("UNUSED_PARAMETER") ytProvider: MusicProvider = YouTubeMusicProvider()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableState = MutableStateFlow(PlaybackUiState())
    val uiState = mutableState.asStateFlow()
    private var dismissedError: androidx.media3.common.PlaybackException? = null
    private var dismissedNotice: cord.kasane.music.provider.PlaybackNotices.Notice? = null
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
        val notice = cord.kasane.music.provider.PlaybackNotices.shared.current.value
            ?.takeIf { it.videoId == player.currentMediaItem?.mediaId && it != dismissedNotice }
        mutableState.value = PlaybackUiState(
            noticeMessage = notice?.message,
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
            upcomingOrder = upcoming(player),
            canSkipNext = player.hasNextMediaItem(), canSkipPrevious = player.hasPreviousMediaItem() || player.currentPosition > 3000)
    }
    private fun upcoming(player: Player): List<Int> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val order = ArrayList<Int>()
        var index = player.currentMediaItemIndex
        while (order.size < player.mediaItemCount) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (index == androidx.media3.common.C.INDEX_UNSET) break
            order += index
        }
        return order
    }
    private fun command(action: (Player) -> Unit) {
        val player = controller
        if (player != null) { action(player); publish(player) }
        else if (pendingCommands.size < 20) pendingCommands.addLast(action)
    }
    fun playTrack(track: Track) = startContext(track, "single")
    /** Selecting an existing queue entry must preserve its collection/radio context. */
    fun playQueueItem(trackId: String) = command { player ->
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == trackId }
        if (index != null) playQueueIndex(player, index)
    }
    fun playQueueIndex(index: Int) = command { player -> if (index in 0 until player.mediaItemCount) playQueueIndex(player, index) }
    private fun playQueueIndex(player: Player, index: Int) {
        if (index != player.currentMediaItemIndex || player.playbackState == Player.STATE_ENDED || player.playerError != null)
            player.seekToDefaultPosition(index)
        player.prepare(); player.play()
    }
    fun startRadio(track: Track) = startContext(track, "forced")
    /**
     * A track chosen from search, home or a list outside the queue starts a new playback context: the queue becomes
     * just that track, it is the anchor of a fresh radio, and nothing from the previous queue or ordering is kept.
     * Choosing the track that is already playing only resumes it; pause, resume and seek never rebuild the queue.
     */
    private fun startContext(track: Track, radioMode: String) = command { player ->
        if (!QueuePolicy.startsNewContext(player.currentMediaItem?.mediaId, track.id, radioMode == "forced")) { prepareAndPlay(player); return@command }
        val start = QueuePolicy.newContext(track)
        val context = java.util.UUID.randomUUID().toString()
        player.setMediaItems(start.items.map { PlaybackService.trackToMediaItem(it, radioMode = radioMode, playContext = context) }, start.startIndex, 0)
        player.shuffleModeEnabled = start.shuffle
        prepareAndPlay(player)
    }
    fun playTracks(tracks: List<Track>, shuffle: Boolean = false) {
        val start = QueuePolicy.collectionStart(tracks, shuffle) { size -> (0 until size).random() } ?: return
        val collectionContext = java.util.UUID.randomUUID().toString()
        command { player ->
            player.setMediaItems(start.items.map { PlaybackService.trackToMediaItem(it, radioMode = "collection", collectionContext = collectionContext) }, start.startIndex, 0)
            player.shuffleModeEnabled = start.shuffle
            prepareAndPlay(player)
        }
    }
    fun moveQueueItem(from: Int, to: Int) = command { player ->
        QueuePolicy.validMove(player.mediaItemCount, from, to)?.let { (f, t) -> player.moveMediaItem(f, t) }
    }
    fun removeQueueItem(index: Int) = command { player ->
        if (index in 0 until player.mediaItemCount) {
            val removedCurrent = index == player.currentMediaItemIndex
            player.removeMediaItem(index)
            if (removedCurrent && player.mediaItemCount > 0 && player.playWhenReady) { player.prepare(); player.play() }
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
    private fun prepareAndPlay(player: Player) {
        dismissedError = null
        player.prepare()
        player.play()
    }

    /**
     * Handoff target: replace the whole queue and start `tracks[index]` at
     * `positionMs`. Resolves to true once the new item is ready; on failure
     * (or timeout) the previous queue is restored so nothing is lost.
     */
    suspend fun handoffQueue(tracks: List<Track>, index: Int, positionMs: Long,
        playing: Boolean, shuffle: Boolean, repeatMode: Int): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(15_000) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                command { player ->
                    if (index !in tracks.indices) {
                        if (cont.isActive) cont.resume(false, {})
                        return@command
                    }
                    val oldItems = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                    val oldIndex = player.currentMediaItemIndex
                    val oldPos = player.currentPosition
                    val oldShuffle = player.shuffleModeEnabled
                    val oldRepeat = player.repeatMode
                    val listener = object : Player.Listener {
                        private fun finish(ok: Boolean) {
                            player.removeListener(this)
                            if (cont.isActive) cont.resume(ok, {})
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) finish(true)
                        }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            player.setMediaItems(oldItems, oldIndex.coerceAtLeast(0), oldPos)
                            player.shuffleModeEnabled = oldShuffle
                            player.repeatMode = oldRepeat
                            player.prepare()
                            finish(false)
                        }
                    }
                    player.addListener(listener)
                    val context = java.util.UUID.randomUUID().toString()
                    player.setMediaItems(tracks.map {
                        PlaybackService.trackToMediaItem(it, radioMode = "handoff", playContext = context)
                    }, index, positionMs)
                    player.shuffleModeEnabled = shuffle
                    player.repeatMode = repeatMode
                    player.prepare()
                    if (playing) player.play() else player.pause()
                }
            }
        } ?: false

    fun retryPlayback() = command { prepareAndPlay(it) }
    fun togglePlayPause() = command {
        if (it.mediaItemCount == 0) return@command
        if (shouldPauseOnToggle(it.playWhenReady, it.playbackState, it.playerError != null)) it.pause()
        else {
            val needsPrepare = it.playerError != null || it.playbackState == Player.STATE_IDLE ||
                it.playbackState == Player.STATE_ENDED
            if (it.playbackState == Player.STATE_ENDED) {
                if (it.hasNextMediaItem()) it.seekToNextMediaItem() else it.seekToDefaultPosition()
            }
            if (needsPrepare) prepareAndPlay(it) else it.play()
        }
    }
    fun playNext() = command { player ->
        if (!player.hasNextMediaItem()) return@command
        skip(player) { player.seekToNextMediaItem() }
    }
    fun playPrevious() = command { player ->
        if (player.mediaItemCount == 0) return@command
        skip(player) {
            if (player.playerError != null && player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
            else player.seekToPrevious()
        }
    }

    private inline fun skip(player: Player, seek: () -> Unit) {
        val recover = player.playerError != null || player.playbackState == Player.STATE_ENDED
        seek()
        if (recover) prepareAndPlay(player)
    }
    fun seekTo(positionMs: Long) = command { it.seekTo(positionMs.coerceIn(0, it.duration.coerceAtLeast(0))) }
    fun pause() = command { it.pause() }
    fun resume() = command { if (it.mediaItemCount > 0) it.play() }
    fun setShuffle(enabled: Boolean) = command { it.shuffleModeEnabled = enabled }
    fun setRepeat(mode: Int) = command { it.repeatMode = mode }
    fun toggleShuffle() = command { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = command { it.repeatMode = when (it.repeatMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    } }
    fun clearNotice() {
        dismissedNotice = cord.kasane.music.provider.PlaybackNotices.shared.current.value
        mutableState.value = mutableState.value.copy(noticeMessage = null)
    }
    fun clearError() { dismissedError = controller?.playerError; mutableState.value = mutableState.value.copy(errorMessage = null) }
}
