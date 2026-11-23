package cord.kasane.music.connect

import androidx.media3.common.Player
import cord.kasane.music.model.Track
import cord.kasane.music.playback.PlaybackManager
import cord.kasane.music.playback.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

/**
 * Spotify-Connect-style playback routing. Exactly one device is "current": the
 * one that most recently started playing (this device or a paired peer). The
 * player UI renders and controls the current device; when this device starts
 * playing, peers that are playing are paused so only one device plays.
 */
class RemoteController(
    val manager: ConnectManager,
    private val playback: PlaybackManager,
    private val scope: CoroutineScope,
) {
    /** Current device id, or null when this device is current. */
    private val _target = MutableStateFlow<String?>(null)
    val target: StateFlow<String?> = _target.asStateFlow()

    /** Local-clock time at which each device was seen starting to play. */
    private val startedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val wasPlaying = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val receivedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        scope.launch {
            playback.uiState.map { it.isPlaying }.distinctUntilChanged().collect { playing ->
                if (playing) {
                    startedAt[LOCAL] = System.currentTimeMillis()
                    pausePlayingPeers()
                }
                recompute()
            }
        }
        scope.launch {
            manager.remoteStates.collect { states ->
                val now = System.currentTimeMillis()
                for ((id, frame) in states) {
                    receivedAt[id] = now
                    val playing = frame.playback == "playing"
                    if (playing && wasPlaying[id] != true) startedAt[id] = now
                    wasPlaying[id] = playing
                }
                (startedAt.keys - states.keys - LOCAL).forEach { startedAt.remove(it); wasPlaying.remove(it) }
                recompute()
            }
        }
    }

    private fun recompute() {
        val online = manager.remoteStates.value.keys
        val best = startedAt.entries.filter { it.key == LOCAL || it.key in online }.maxByOrNull { it.value }?.key
        _target.value = best?.takeIf { it != LOCAL }
    }

    private fun pausePlayingPeers() {
        for ((id, frame) in manager.remoteStates.value) {
            if (frame.playback == "playing") scope.launch { runCatching { manager.sendCommand(id, "pause") } }
        }
    }

    /** Make [deviceId] current right away (used after a successful transfer). */
    private fun markCurrent(deviceId: String?) {
        startedAt[deviceId ?: LOCAL] = System.currentTimeMillis()
        recompute()
    }

    /** Remote position, advanced by the time since the frame arrived while it plays. */
    private fun livePosition(id: String, frame: Frame.State): Long {
        if (frame.playback != "playing") return frame.positionMs
        val elapsed = System.currentTimeMillis() - (receivedAt[id] ?: System.currentTimeMillis())
        return (frame.positionMs + elapsed).let { if (frame.durationMs > 0) it.coerceAtMost(frame.durationMs) else it }
    }

    /**
     * One-tap transfer: move whatever the current device plays to [deviceId]
     * (null = this device) at the same position; the old device stops once
     * the new one has started.
     */
    fun transferTo(deviceId: String?, onDone: (Boolean) -> Unit = {}) {
        val from = _target.value
        if (deviceId == from) return onDone(true)
        scope.launch {
            val ok = if (from == null) pushLocal(deviceId!!) else {
                val frame = remoteFrame(from) ?: return@launch onDone(false)
                if (deviceId == null) pullTo(from, frame) else pushRemote(from, frame, deviceId)
            }
            if (ok) markCurrent(deviceId)
            onDone(ok)
        }
    }

    private suspend fun pushLocal(deviceId: String): Boolean {
        val ui = playback.uiState.value
        if (ui.queue.isEmpty()) return false
        val ok = runCatching { manager.handoff(deviceId, Frame.Handoff(
            transferId = "",
            queue = ui.queue.map { it.toMeta() },
            index = ui.queueIndex.coerceAtLeast(0),
            positionMs = ui.currentPositionMs,
            playing = true,
            shuffle = ui.shuffleEnabled,
            repeat = repeatName(ui.repeatMode),
        )) }.isSuccess
        if (ok) playback.pause()
        return ok
    }

    private suspend fun pullTo(from: String, frame: Frame.State): Boolean {
        val tracks = (frame.queue ?: emptyList()).map { it.toTrack() }
        if (tracks.isEmpty()) return false
        val ok = playback.handoffQueue(
            tracks = tracks,
            index = frame.queueIndex.toInt().coerceIn(0, tracks.size - 1),
            positionMs = livePosition(from, frame),
            playing = true,
            shuffle = frame.shuffle,
            repeatMode = repeatMode(frame.repeat),
        )
        if (ok) runCatching { manager.sendCommand(from, "pause") }
        return ok
    }

    private suspend fun pushRemote(from: String, frame: Frame.State, to: String): Boolean {
        val queue = frame.queue ?: emptyList()
        if (queue.isEmpty()) return false
        val ok = runCatching { manager.handoff(to, Frame.Handoff(
            transferId = "",
            queue = queue,
            index = frame.queueIndex.toInt().coerceIn(0, queue.size - 1),
            positionMs = livePosition(from, frame),
            playing = true,
            shuffle = frame.shuffle,
            repeat = frame.repeat,
        )) }.isSuccess
        if (ok) runCatching { manager.sendCommand(from, "pause") }
        return ok
    }

    fun setVolume(volume: Float) {
        send("setVolume", buildJsonObject { put("volume", volume.coerceIn(0f, 1f)) })
    }

    /** Close the session to [deviceId] until it is announced again. */
    fun disconnect(deviceId: String) {
        scope.launch { runCatching { manager.disconnect(deviceId) } }
    }

    fun pair(target: String) {
        scope.launch { runCatching { manager.pair(target) } }
    }

    fun unpair(deviceId: String) {
        startedAt.remove(deviceId)
        recompute()
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

    private companion object {
        const val LOCAL = ""
        fun repeatName(mode: Int) = when (mode) {
            Player.REPEAT_MODE_ALL -> "all"
            Player.REPEAT_MODE_ONE -> "one"
            else -> "off"
        }
        fun repeatMode(name: String) = when (name) {
            "all" -> Player.REPEAT_MODE_ALL
            "one" -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
}
