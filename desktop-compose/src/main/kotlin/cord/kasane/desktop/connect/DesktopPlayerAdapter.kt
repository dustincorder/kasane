package cord.kasane.desktop.connect

import cord.kasane.desktop.playback.DesktopPlayer
import cord.kasane.desktop.playback.RepeatMode
import cord.kasane.music.connect.CmdException
import cord.kasane.music.connect.ConnectJson
import cord.kasane.music.connect.ConnectPlayerAdapter
import cord.kasane.music.connect.Frame
import cord.kasane.music.connect.PlayerSnapshot
import cord.kasane.music.connect.TrackMeta
import cord.kasane.music.connect.cmdFail
import cord.kasane.music.connect.toMeta
import cord.kasane.music.connect.toTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.atomic.AtomicLong

/** The same command set as Android's AndroidPlayerAdapter, against the desktop player. */
class DesktopPlayerAdapter(private val player: DesktopPlayer, scope: CoroutineScope) : ConnectPlayerAdapter {
    private val counter = AtomicLong()
    private val _changed = MutableStateFlow(0L)
    override val changed: StateFlow<Long> = _changed

    init { scope.launch { player.state.collect { _changed.value = counter.incrementAndGet() } } }

    override suspend fun snapshot(): PlayerSnapshot {
        val s = player.state.value
        return PlayerSnapshot(
            playback = when {
                s.error != null -> "error"
                s.isLoading -> "buffering"
                s.isPlaying -> "playing"
                s.current != null -> "paused"
                else -> "idle"
            },
            track = s.current?.toMeta(),
            positionMs = (s.positionSec * 1000).toLong(),
            durationMs = (s.durationSec * 1000).toLong(),
            volume = s.volume,
            shuffle = s.shuffle,
            repeat = s.repeat.name.lowercase(),
            queueIndex = s.index.toLong(),
            queue = s.queue.map { it.toMeta() },
            error = s.error,
        )
    }

    private fun argLong(args: JsonObject, key: String): Long =
        args[key]?.jsonPrimitive?.longOrNull ?: cmdFail("invalid_args", "missing or bad $key")

    private fun argInt(args: JsonObject, key: String): Int =
        argLong(args, key).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: cmdFail("invalid_args", "$key out of range")

    private fun argTrack(args: JsonObject): TrackMeta = args["track"]?.let {
        try { ConnectJson.json.decodeFromJsonElement(TrackMeta.serializer(), it) } catch (_: Exception) { cmdFail("invalid_args", "bad track") }
    } ?: cmdFail("invalid_args", "missing track")

    @Throws(CmdException::class)
    override suspend fun execute(action: String, args: JsonObject) {
        when (action) {
            "play" -> if (!player.state.value.isPlaying) player.toggle()
            "pause" -> player.pause()
            "toggle" -> player.toggle()
            "next" -> player.next()
            "previous" -> player.previous()
            "seek" -> player.seek(argLong(args, "positionMs") / 1000.0)
            "setVolume" -> {
                val v = (args["volume"]?.jsonPrimitive?.doubleOrNull ?: cmdFail("invalid_args", "missing or bad volume")).toFloat().coerceIn(0f, 1f)
                player.setVolume(v)
                // Never change loudness silently: say so on this computer too.
                player.notify("Volume set to ${(v * 100).toInt()} % from another device")
            }
            "setShuffle" -> {
                val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull ?: cmdFail("invalid_args", "missing or bad enabled")
                if (enabled != player.state.value.shuffle) player.toggleShuffle()
            }
            "setRepeat" -> {
                val mode = when (args["mode"]?.jsonPrimitive?.content) {
                    "off" -> RepeatMode.OFF; "all" -> RepeatMode.ALL; "one" -> RepeatMode.ONE
                    else -> cmdFail("invalid_args", "missing or bad mode")
                }
                while (player.state.value.repeat != mode) player.cycleRepeat()
            }
            "playQueueIndex" -> player.playIndex(argInt(args, "index"))
            "removeQueueIndex" -> player.removeAt(argInt(args, "index"))
            "moveQueue" -> player.move(argInt(args, "from"), argInt(args, "to"))
            "playTrack" -> {
                val meta = argTrack(args)
                if (meta.source == "local") cmdFail("unsupported_media", "local tracks cannot be resolved remotely")
                player.playTrack(meta.toTrack())
            }
            "addToQueue" -> player.addToQueue(argTrack(args).toTrack())
            "playNext" -> player.playNext(argTrack(args).toTrack())
            else -> cmdFail("unsupported", "unknown action $action")
        }
    }

    @Throws(CmdException::class)
    override suspend fun handoff(request: Frame.Handoff) {
        if (request.queue.any { it.source == "local" }) cmdFail("unsupported_media", "local tracks cannot be resolved remotely")
        if (request.index !in request.queue.indices) cmdFail("invalid_args", "handoff index out of bounds")
        player.startHandoff(request.queue.map { it.toTrack() }, request.index, request.positionMs / 1000.0, request.playing,
            request.shuffle, when (request.repeat) { "all" -> RepeatMode.ALL; "one" -> RepeatMode.ONE; else -> RepeatMode.OFF })
    }

    override fun capabilities(): List<String> = listOf("seek", "volume", "queue", "handoff")
}
