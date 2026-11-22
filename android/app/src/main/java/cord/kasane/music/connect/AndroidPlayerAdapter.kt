package cord.kasane.music.connect

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.Player
import cord.kasane.music.playback.PlaybackManager
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Maps Connect protocol actions/state onto the app's PlaybackManager. */
class AndroidPlayerAdapter(
    context: Context,
    private val playback: PlaybackManager,
) : ConnectPlayerAdapter {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val counter = AtomicLong(0)
    private val _changed = MutableStateFlow(0L)
    override val changed: StateFlow<Long> = _changed

    init {
        scope.launch {
            playback.uiState.collect { _changed.value = counter.incrementAndGet() }
        }
    }

    override suspend fun snapshot(): PlayerSnapshot {
        val ui = playback.uiState.value
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volume = if (max > 0) audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max else null
        return PlayerSnapshot(
            playback = when {
                ui.errorMessage != null -> "error"
                ui.isLoading -> "buffering"
                ui.isPlaying -> "playing"
                ui.currentTrack != null -> "paused"
                else -> "idle"
            },
            track = ui.currentTrack?.toMeta(),
            positionMs = ui.currentPositionMs,
            durationMs = ui.durationMs,
            volume = volume,
            shuffle = ui.shuffleEnabled,
            repeat = when (ui.repeatMode) {
                Player.REPEAT_MODE_ALL -> "all"
                Player.REPEAT_MODE_ONE -> "one"
                else -> "off"
            },
            queueIndex = ui.queueIndex.toLong(),
            queue = ui.queue.map { it.toMeta() },
            error = ui.errorMessage,
        )
    }

    private fun argLong(args: JsonObject, key: String): Long =
        args[key]?.jsonPrimitive?.longOrNull
            ?: cmdFail("invalid_args", "missing or bad $key")

    private fun argInt(args: JsonObject, key: String): Int {
        val v = argLong(args, key)
        if (v > Int.MAX_VALUE || v < Int.MIN_VALUE) cmdFail("invalid_args", "$key out of range")
        return v.toInt()
    }

    private fun argTrack(args: JsonObject) = args["track"]?.let {
        try {
            ConnectJson.json.decodeFromJsonElement(TrackMeta.serializer(), it)
        } catch (e: Exception) {
            cmdFail("invalid_args", "bad track")
        }
    } ?: cmdFail("invalid_args", "missing track")

    private suspend fun main(block: suspend () -> Unit) =
        kotlinx.coroutines.withContext(Dispatchers.Main) { block() }

    override suspend fun execute(action: String, args: JsonObject) = main {
        when (action) {
            "play" -> playback.resume()
            "pause" -> playback.pause()
            "toggle" -> playback.togglePlayPause()
            "next" -> playback.playNext()
            "previous" -> playback.playPrevious()
            "seek" -> playback.seekTo(argLong(args, "positionMs"))
            "setVolume" -> {
                val v = args["volume"]?.jsonPrimitive?.doubleOrNull
                    ?: cmdFail("invalid_args", "missing or bad volume")
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                    (v.coerceIn(0.0, 1.0) * max).toInt().coerceIn(0, max), 0)
            }
            "setShuffle" -> {
                val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull
                    ?: cmdFail("invalid_args", "missing or bad enabled")
                playback.setShuffle(enabled)
            }
            "setRepeat" -> {
                playback.setRepeat(
                    when (args["mode"]?.jsonPrimitive?.content) {
                        "off" -> Player.REPEAT_MODE_OFF
                        "all" -> Player.REPEAT_MODE_ALL
                        "one" -> Player.REPEAT_MODE_ONE
                        else -> cmdFail("invalid_args", "missing or bad mode")
                    }
                )
            }
            "playQueueIndex" -> playback.playQueueIndex(argInt(args, "index"))
            "removeQueueIndex" -> playback.removeQueueItem(argInt(args, "index"))
            "moveQueue" -> playback.moveQueueItem(argInt(args, "from"), argInt(args, "to"))
            "playTrack" -> {
                val meta = argTrack(args)
                if (meta.source == "local") cmdFail("unsupported_media", "local tracks cannot be resolved remotely")
                playback.playTrack(meta.toTrack())
            }
            "addToQueue" -> playback.addToQueue(argTrack(args).toTrack())
            "playNext" -> playback.playNextInQueue(argTrack(args).toTrack())
            else -> cmdFail("unsupported", "unknown action $action")
        }
    }

    override suspend fun handoff(request: Frame.Handoff) = main {
        if (request.queue.any { it.source == "local" }) {
            cmdFail("unsupported_media", "local tracks cannot be resolved remotely")
        }
        if (request.index !in request.queue.indices) {
            cmdFail("invalid_args", "handoff index out of bounds")
        }
        val repeat = when (request.repeat) {
            "all" -> Player.REPEAT_MODE_ALL
            "one" -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        val ok = playback.handoffQueue(
            tracks = request.queue.map { it.toTrack() },
            index = request.index,
            positionMs = request.positionMs,
            playing = request.playing,
            shuffle = request.shuffle,
            repeatMode = repeat,
        )
        if (!ok) cmdFail("failed", "could not start the handoff item")
    }

    override fun capabilities(): List<String> = listOf("seek", "volume", "queue", "handoff")
}
