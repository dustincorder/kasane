package cord.kasane.music.connect

import androidx.media3.common.Player
import cord.kasane.music.model.Track
import cord.kasane.music.playback.PlaybackManager
import cord.kasane.music.playback.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

/**
 * UI-side remote control: which device (if any) the player surface renders
 * and routes actions to. Local playback stays authoritative when target==null.
 */
class RemoteController(
    val manager: ConnectManager,
    private val playback: PlaybackManager,
    private val scope: CoroutineScope,
) {
    private val _target = MutableStateFlow<String?>(null)
    val target: StateFlow<String?> = _target.asStateFlow()

    fun control(deviceId: String?) {
        _target.value = deviceId
        if (deviceId != null) {
            scope.launch { runCatching { manager.connect(deviceId) } }
        }
    }

    fun pair(target: String) {
        scope.launch { runCatching { manager.pair(target) } }
    }

    fun unpair(deviceId: String) {
        if (_target.value == deviceId) _target.value = null
        scope.launch { runCatching { manager.unpair(deviceId) } }
    }

    fun confirm(promptId: String, accepted: Boolean) = manager.confirmPairing(promptId, accepted)

    fun remoteFrame(deviceId: String): Frame.State? =
        manager.remoteStates.value[deviceId]

    /** Project a remote state frame into the shape the player UI renders. */
    fun project(frame: Frame.State): PlaybackUiState = PlaybackUiState(
        currentTrack = frame.track?.toTrack(),
        isPlaying = frame.playback == "playing",
        isLoading = frame.playback == "buffering",
        currentPositionMs = frame.positionMs,
        durationMs = frame.durationMs,
        queue = (frame.queue ?: emptyList()).map { it.toTrack() },
        queueIndex = frame.queueIndex.toInt(),
        errorMessage = frame.error,
        shuffleEnabled = frame.shuffle,
        repeatMode = when (frame.repeat) {
            "all" -> Player.REPEAT_MODE_ALL
            "one" -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        },
        canSkipNext = frame.queueIndex + 1 < (frame.queue?.size ?: 0).toLong(),
        canSkipPrevious = frame.queueIndex > 0,
    )

    private fun send(action: String, args: JsonObject = JsonObject(emptyMap())) {
        val id = _target.value ?: return
        scope.launch {
            runCatching { manager.sendCommand(id, action, args) }
        }
    }

    fun toggle() = send("toggle")
    fun next() = send("next")
    fun previous() = send("previous")
    fun seekTo(positionMs: Long) = send("seek", buildJsonObject { put("positionMs", positionMs) })

    fun toggleShuffle(current: Boolean) =
        send("setShuffle", buildJsonObject { put("enabled", !current) })

    fun cycleRepeat(currentMode: Int) {
        val mode = when (currentMode) {
            Player.REPEAT_MODE_OFF -> "all"
            Player.REPEAT_MODE_ALL -> "one"
            else -> "off"
        }
        send("setRepeat", buildJsonObject { put("mode", mode) })
    }

    fun playQueueIndex(index: Int) {
        val rev = _target.value?.let { remoteFrame(it)?.queueRev } ?: 0
        send("playQueueIndex", buildJsonObject {
            put("index", index)
            put("queueRev", rev)
        })
    }

    fun moveQueueItem(from: Int, to: Int) {
        val rev = _target.value?.let { remoteFrame(it)?.queueRev } ?: 0
        send("moveQueueItem", buildJsonObject {
            put("from", from); put("to", to); put("queueRev", rev)
        })
    }

    fun removeQueueItem(index: Int) {
        val rev = _target.value?.let { remoteFrame(it)?.queueRev } ?: 0
        send("removeQueueItem", buildJsonObject {
            put("index", index); put("queueRev", rev)
        })
    }

    fun playTrack(track: Track) =
        send("playTrack", buildJsonObject { put("track", metaJson(track.toMeta())) })

    fun addToQueue(track: Track) =
        send("addToQueue", buildJsonObject { put("track", metaJson(track.toMeta())) })

    fun playNext(track: Track) =
        send("playNext", buildJsonObject { put("track", metaJson(track.toMeta())) })

    private fun metaJson(meta: TrackMeta): kotlinx.serialization.json.JsonElement =
        ConnectJson.json.encodeToJsonElement(TrackMeta.serializer(), meta)

    /** Push the local queue + position to a paired device; pause locally only after ack. */
    fun transferTo(deviceId: String, onDone: (Boolean) -> Unit = {}) {
        val ui = playback.uiState.value
        val request = Frame.Handoff(
            transferId = "",
            queue = ui.queue.map { it.toMeta() },
            index = ui.queueIndex.coerceAtLeast(0),
            positionMs = ui.currentPositionMs,
            playing = ui.isPlaying,
            shuffle = ui.shuffleEnabled,
            repeat = when (ui.repeatMode) {
                Player.REPEAT_MODE_ALL -> "all"
                Player.REPEAT_MODE_ONE -> "one"
                else -> "off"
            },
        )
        scope.launch {
            val ok = runCatching { manager.handoff(deviceId, request) }.isSuccess
            if (ok && ui.isPlaying) playback.pause()
            onDone(ok)
        }
    }

    /** Pull: start the remote snapshot locally, then pause the remote. */
    fun playHere(deviceId: String, onDone: (Boolean) -> Unit = {}) {
        val frame = remoteFrame(deviceId) ?: return onDone(false)
        scope.launch {
            val tracks = (frame.queue ?: emptyList()).map { it.toTrack() }
            val ok = tracks.isNotEmpty() && playback.handoffQueue(
                tracks = tracks,
                index = frame.queueIndex.toInt().coerceIn(0, tracks.size - 1),
                positionMs = frame.positionMs,
                playing = frame.playback == "playing",
                shuffle = frame.shuffle,
                repeatMode = when (frame.repeat) {
                    "all" -> Player.REPEAT_MODE_ALL
                    "one" -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                },
            )
            if (ok) runCatching { manager.sendCommand(deviceId, "pause") }
            onDone(ok)
        }
    }

    /** Collection play-all/shuffle on the remote = a handoff of that queue. */
    fun playTracksRemotely(deviceId: String, tracks: List<Track>, shuffle: Boolean, onDone: (Boolean) -> Unit = {}) {
        scope.launch {
            if (tracks.any { it.isLocal }) { onDone(false); return@launch }
            val startIndex = if (shuffle && tracks.isNotEmpty()) (tracks.indices).random() else 0
            val ok = runCatching {
                manager.handoff(deviceId, Frame.Handoff(
                    transferId = "",
                    queue = tracks.map { it.toMeta() },
                    index = startIndex,
                    positionMs = 0,
                    playing = true,
                    shuffle = shuffle,
                    repeat = "off",
                ))
            }.isSuccess
            onDone(ok)
        }
    }
}
