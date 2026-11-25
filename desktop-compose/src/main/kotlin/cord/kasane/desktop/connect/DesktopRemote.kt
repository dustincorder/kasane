package cord.kasane.desktop.connect

import cord.kasane.desktop.playback.DesktopPlayer
import cord.kasane.desktop.playback.PlayerState
import cord.kasane.desktop.playback.RepeatMode
import cord.kasane.music.connect.ConnectManager
import cord.kasane.music.connect.Frame
import cord.kasane.music.connect.toMeta
import cord.kasane.music.connect.toTrack
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Same routing rules as Android's RemoteController: the current device is the
 * one that most recently started playing; when this computer starts, playing
 * peers are paused; one tap transfers at the same position.
 */
class DesktopRemote(val manager: ConnectManager, private val player: DesktopPlayer, private val scope: CoroutineScope) {
    private val _target = MutableStateFlow<String?>(null)
    /** Current device id, or null when this computer is current. */
    val target: StateFlow<String?> = _target.asStateFlow()
    private val startedAt = ConcurrentHashMap<String, Long>()
    private val wasPlaying = ConcurrentHashMap<String, Boolean>()
    private val receivedAt = ConcurrentHashMap<String, Long>()
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        scope.launch {
            player.state.map { it.isPlaying }.distinctUntilChanged().collect { playing ->
                if (playing) {
                    startedAt[LOCAL] = System.currentTimeMillis()
                    if (_busy.value == null) {
                        // §Active device: claim the performer role first — the current performer
                        // gets a `yield` (claim + pause), not just a bare pause.
                        scope.launch { runCatching { manager.claimPerformer() } }
                        for ((id, frame) in manager.remoteStates.value) {
                            if (frame.playback == "playing") scope.launch { runCatching { manager.sendCommand(id, "pause") } }
                        }
                    }
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
        if (_busy.value != null) return
        val online = manager.remoteStates.value.keys
        _target.value = startedAt.entries.filter { it.key == LOCAL || it.key in online }.maxByOrNull { it.value }?.key?.takeIf { it != LOCAL }
    }

    fun frame(id: String): Frame.State? = manager.remoteStates.value[id]

    /** Remote state in the shape the player UI renders, position advanced since the frame arrived. */
    fun project(id: String, frame: Frame.State): PlayerState = PlayerState(
        queue = (frame.queue ?: listOfNotNull(frame.track)).map { it.toTrack() },
        index = if (frame.queue == null) 0 else frame.queueIndex.toInt(),
        isPlaying = frame.playback == "playing",
        isLoading = frame.playback == "buffering",
        positionSec = livePosition(id, frame) / 1000.0,
        durationSec = frame.durationMs / 1000.0,
        volume = frame.volume ?: 1f,
        shuffle = frame.shuffle,
        repeat = when (frame.repeat) { "all" -> RepeatMode.ALL; "one" -> RepeatMode.ONE; else -> RepeatMode.OFF },
        error = frame.error,
    )

    private fun livePosition(id: String, frame: Frame.State): Long {
        if (frame.playback != "playing") return frame.positionMs
        val elapsed = System.currentTimeMillis() - (receivedAt[id] ?: System.currentTimeMillis())
        return (frame.positionMs + elapsed).let { if (frame.durationMs > 0) it.coerceAtMost(frame.durationMs) else it }
    }

    fun send(action: String, args: JsonObject = JsonObject(emptyMap())) {
        val id = _target.value ?: return
        scope.launch { runCatching { manager.sendCommand(id, action, args) }.onFailure { _error.value = it.message } }
    }

    fun queueRev(): Long = _target.value?.let { frame(it)?.queueRev } ?: 0

    /** One tap: move what the current device plays to [deviceId] (null = this computer). */
    fun transferTo(deviceId: String?) {
        val from = _target.value
        if (deviceId == from || _busy.value != null) return
        _busy.value = deviceId ?: LOCAL
        _error.value = null
        scope.launch {
            val ok = runCatching {
                if (from == null) {
                    val s = player.state.value
                    if (s.queue.isEmpty()) error("Nothing is playing on this computer.")
                    // §Active device: the target plays via `takeover` first; the manager pauses
                    // local playback only after its ack — never a double-play gap.
                    manager.transferTo(deviceId!!, Frame.Handoff("", s.queue.map { it.toMeta() }, s.index.coerceAtLeast(0),
                        (s.positionSec * 1000).toLong(), true, s.shuffle, s.repeat.name.lowercase()))
                } else {
                    val frame = frame(from) ?: error("The device went offline.")
                    val queue = frame.queue ?: listOfNotNull(frame.track)
                    if (queue.isEmpty()) error("Nothing is playing there.")
                    val index = frame.queueIndex.toInt().coerceIn(0, queue.lastIndex)
                    if (deviceId == null) {
                        // "Play here": yield the current performer (it pauses on our claim), then start.
                        manager.claimPerformer()
                        player.startHandoff(queue.map { it.toTrack() }, index, livePosition(from, frame) / 1000.0, true, frame.shuffle,
                            when (frame.repeat) { "all" -> RepeatMode.ALL; "one" -> RepeatMode.ONE; else -> RepeatMode.OFF })
                    } else {
                        manager.transferTo(deviceId, Frame.Handoff("", queue, index, livePosition(from, frame), true, frame.shuffle, frame.repeat))
                        // The source yields to the new performer — a claim, not just a pause.
                        runCatching {
                            manager.sendCommand(from, "yield", buildJsonObject { put("activeId", deviceId) })
                        }.recoverCatching { manager.sendCommand(from, "pause") }
                    }
                }
            }.onFailure { _error.value = it.message ?: "Transfer failed." }.isSuccess
            _busy.value = null
            if (ok) startedAt[deviceId ?: LOCAL] = System.currentTimeMillis()
            recompute()
        }
    }

    fun setVolume(v: Float) = send("setVolume", buildJsonObject { put("volume", v.coerceIn(0f, 1f).toDouble()) })

    private companion object { const val LOCAL = "" }
}
