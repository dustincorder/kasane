package cord.kasane.desktop.audio

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * Audio output through a private `mpv` process driven over its JSON IPC socket.
 * mpv decodes everything YouTube serves (Opus/WebM, AAC/MP4, HLS) and handles
 * HTTP range reads and caching; Kasane owns the queue and only hands it one
 * resolved URL at a time. Nothing here sees account credentials.
 */
class MpvEngine(private val audioOutput: String? = null) : AutoCloseable {
    data class Status(
        val positionSec: Double = 0.0,
        val durationSec: Double = 0.0,
        val paused: Boolean = true,
        val buffering: Boolean = false,
        val idle: Boolean = true,
        val volume: Double = 100.0,
    )

    sealed interface Event {
        data object Ended : Event
        data class Failed(val reason: String) : Event
    }

    private val dir: Path = Files.createTempDirectory("kasane-mpv")
    private val socket: Path = dir.resolve("ipc.sock")
    private val process: Process
    private val channel: SocketChannel
    private val writer: java.io.Writer
    private val json = Json { ignoreUnknownKeys = true }
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()
    @Volatile private var closed = false

    init {
        val args = mutableListOf(
            "mpv", "--idle=yes", "--no-video", "--no-terminal", "--no-config", "--ytdl=no",
            "--audio-display=no", "--input-ipc-server=$socket", "--cache=yes", "--demuxer-max-bytes=64MiB",
            "--network-timeout=20",
        )
        audioOutput?.let { args += "--ao=$it" }
        process = try {
            ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: IOException) {
            throw IOException("Kasane needs mpv for audio. Install the mpv package and start Kasane again.", e)
        }
        channel = connect()
        writer = Channels.newWriter(channel, Charsets.UTF_8)
        thread(name = "mpv-ipc", isDaemon = true) { readLoop() }
        for ((id, name) in listOf("time-pos", "duration", "pause", "paused-for-cache", "idle-active", "volume").withIndex()) {
            send(JsonArray(listOf(JsonPrimitive("observe_property"), JsonPrimitive(id + 1), JsonPrimitive(name))))
        }
    }

    private fun connect(): SocketChannel {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) throw IOException("mpv exited during start (code ${process.exitValue()}).")
            if (Files.exists(socket)) runCatching {
                return SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(UnixDomainSocketAddress.of(socket)) }
            }
            Thread.sleep(50)
        }
        process.destroy()
        throw IOException("mpv did not open its control socket.")
    }

    private fun readLoop() {
        val buffer = ByteBuffer.allocate(64 * 1024)
        val line = StringBuilder()
        try {
            while (!closed && channel.read(buffer) >= 0) {
                buffer.flip()
                line.append(Charsets.UTF_8.decode(buffer))
                buffer.clear()
                while (true) {
                    val nl = line.indexOf('\n')
                    if (nl < 0) break
                    val text = line.substring(0, nl)
                    line.delete(0, nl + 1)
                    runCatching { handle(json.parseToJsonElement(text).jsonObject) }
                }
            }
        } catch (_: IOException) {
            // Socket closed on shutdown or mpv exit.
        }
        if (!closed) _events.tryEmit(Event.Failed("The audio engine stopped."))
    }

    private fun handle(msg: JsonObject) {
        when (msg["event"]?.jsonPrimitive?.contentOrNull) {
            "property-change" -> {
                val data = msg["data"] as? JsonPrimitive
                when (msg["name"]?.jsonPrimitive?.contentOrNull) {
                    "time-pos" -> _status.update { it.copy(positionSec = data?.doubleOrNull ?: 0.0) }
                    "duration" -> _status.update { it.copy(durationSec = data?.doubleOrNull ?: 0.0) }
                    "pause" -> _status.update { it.copy(paused = data?.booleanOrNull ?: true) }
                    "paused-for-cache" -> _status.update { it.copy(buffering = data?.booleanOrNull ?: false) }
                    "idle-active" -> _status.update { it.copy(idle = data?.booleanOrNull ?: true) }
                    "volume" -> _status.update { it.copy(volume = data?.doubleOrNull ?: it.volume) }
                }
            }
            "end-file" -> when (msg["reason"]?.jsonPrimitive?.contentOrNull) {
                "eof" -> _events.tryEmit(Event.Ended)
                "error" -> _events.tryEmit(Event.Failed(msg["file_error"]?.jsonPrimitive?.contentOrNull ?: "playback error"))
            }
        }
    }

    @Synchronized
    private fun send(command: JsonArray) {
        if (closed) return
        writer.write(buildJsonObject { put("command", command) }.toString())
        writer.write("\n")
        writer.flush()
    }

    private fun command(vararg parts: Any) =
        send(JsonArray(parts.map { if (it is Number) JsonPrimitive(it) else if (it is Boolean) JsonPrimitive(it) else JsonPrimitive(it.toString()) }))

    /** Play [url] from [startSec]; [userAgent] must match the client the URL was issued to. */
    fun load(url: String, userAgent: String?, startSec: Double = 0.0, play: Boolean = true) {
        command("set_property", "user-agent", userAgent ?: "")
        command("set_property", "pause", !play)
        _status.update { it.copy(positionSec = startSec, durationSec = 0.0, buffering = true) }
        command("loadfile", url, "replace", -1, "start=${"%.3f".format(java.util.Locale.ROOT, startSec)}")
    }

    fun setPaused(paused: Boolean) = command("set_property", "pause", paused)
    fun seek(sec: Double) = command("seek", sec, "absolute")
    fun setVolume(percent: Double) = command("set_property", "volume", percent.coerceIn(0.0, 100.0))
    fun stop() = command("stop")

    override fun close() {
        if (closed) return
        runCatching { command("quit") }
        closed = true
        runCatching { channel.close() }
        if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
        runCatching { Files.deleteIfExists(socket); Files.deleteIfExists(dir) }
    }
}
