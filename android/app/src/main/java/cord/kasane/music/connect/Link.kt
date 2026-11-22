package cord.kasane.music.connect

import java.io.EOFException
import java.io.InputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Newline-delimited UTF-8 JSON frames over a connected socket. A 1 MiB cap,
 * 10 s pings and a 30 s dead-peer cutoff match the Rust Link.
 */
class Link(
    private val socket: Socket,
    private val scope: CoroutineScope,
    private val onFrame: (Frame) -> Unit,
    private val onUnknownType: (String) -> Unit,
    private val onClose: () -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {
    companion object {
        const val MAX_FRAME = 1024 * 1024
        const val PING_INTERVAL_MS = 10_000L
        const val DEAD_AFTER_MS = 30_000L
        private const val OUTBOUND_QUEUE = 128
    }

    private val outbound = Channel<Frame>(OUTBOUND_QUEUE)
    @Volatile private var closed = false
    @Volatile private var lastInbound = System.currentTimeMillis()
    private var writer: Job? = null
    private var reader: Job? = null

    fun start() {
        reader = scope.launch(Dispatchers.IO) { readLoop() }
        writer = scope.launch(Dispatchers.IO) { writeLoop() }
        scope.launch { keepalive() }
    }

    /** Bounded send; a full queue means the peer stalled and we drop the link. */
    fun send(frame: Frame): Boolean {
        if (closed) return false
        if (outbound.trySend(frame).isSuccess) return true
        close()
        return false
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }

    private suspend fun keepalive() {
        while (!closed) {
            delay(PING_INTERVAL_MS)
            if (closed) break
            if (System.currentTimeMillis() - lastInbound > DEAD_AFTER_MS) {
                close()
                break
            }
            if (!send(Frame.Ping)) break
        }
    }

    private suspend fun writeLoop() {
        try {
            val out = socket.getOutputStream()
            for (frame in outbound) {
                val line = (ConnectJson.encode(frame) + "\n").toByteArray(StandardCharsets.UTF_8)
                out.write(line)
                out.flush()
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) onError(e)
        } finally {
            close()
        }
    }

    private suspend fun readLoop() {
        try {
            val input = socket.getInputStream()
            while (!closed) {
                val line = readLine(input) ?: break
                lastInbound = System.currentTimeMillis()
                when (val decoded = ConnectJson.decode(line)) {
                    is ConnectJson.Decoded.Ok -> onFrame(decoded.frame)
                    is ConnectJson.Decoded.UnknownType -> onUnknownType(decoded.type)
                    is ConnectJson.Decoded.Malformed -> {
                        if (!send(Frame.Error("invalid_args", decoded.detail))) break
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) onError(e)
        } finally {
            close()
            onClose()
        }
    }

    /** One \n-terminated frame, capped at MAX_FRAME bytes. */
    private fun readLine(input: InputStream): String? {
        val buf = java.io.ByteArrayOutputStream()
        while (buf.size() <= MAX_FRAME) {
            val b = try {
                input.read()
            } catch (e: Exception) {
                return null
            }
            if (b < 0) {
                if (buf.size() > 0) throw EOFException()
                return null
            }
            if (b == '\n'.code) {
                return String(buf.toByteArray(), StandardCharsets.UTF_8)
            }
            buf.write(b)
        }
        close()
        return null
    }
}
