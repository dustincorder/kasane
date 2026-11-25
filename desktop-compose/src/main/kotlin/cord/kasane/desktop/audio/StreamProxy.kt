package cord.kasane.desktop.audio

import cord.kasane.music.playback.AudioRange
import cord.kasane.music.playback.CdnRecoveryBudget
import cord.kasane.music.provider.GuestPlayerResolver
import cord.kasane.music.provider.ResolvedAudioStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Loopback HTTP endpoint for mpv. YouTube's direct audio URLs only answer
 * bounded `&range=` reads (the same rule the Android data source follows), so
 * each Range request from mpv is served as a sequence of [AudioRange] chunks,
 * refreshing the signed URL once when the CDN refuses it. Only 127.0.0.1 is
 * bound and every stream has an unguessable token; signed URLs never leave
 * this process.
 */
class StreamProxy(private val http: OkHttpClient, private val resolver: GuestPlayerResolver) : AutoCloseable {
    private class Entry(val videoId: String, @Volatile var stream: ResolvedAudioStream, @Volatile var total: Long,
        val refresh: (suspend () -> ResolvedAudioStream)?)

    private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    private val entries = ConcurrentHashMap<String, Entry>()
    private val cutOff: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** True once if YouTube stopped serving [videoId] part-way; mpv then reports a normal end of file. */
    fun consumeCutOff(videoId: String): Boolean = cutOff.remove(videoId)
    private val random = SecureRandom()

    init {
        thread(name = "stream-proxy", isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(name = "stream-proxy-conn", isDaemon = true) { socket.use { serve(it) } }
            }
        }
    }

    /**
     * Optional write-through cache sink: every byte mpv pulls is also offered to the offline
     * store, so a fully streamed song replays without the network — Android's CacheDataSource semantic.
     */
    @Volatile
    var streamSink: ((videoId: String, itag: Int, mimeType: String, contentLength: Long,
        position: Long, bytes: ByteArray, offset: Int, count: Int) -> Unit)? = null

    /** Register [stream] and return the loopback URL mpv should open. */
    fun register(videoId: String, stream: ResolvedAudioStream, refresh: (suspend () -> ResolvedAudioStream)? = null): String {
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        if (entries.size > 8) entries.keys.take(entries.size - 8).forEach(entries::remove)
        entries[token] = Entry(videoId, stream, stream.contentLength, refresh)
        return "http://127.0.0.1:${server.localPort}/a/$token"
    }

    private val debug = System.getenv("KASANE_PROXY_DEBUG") == "1"
    private fun log(msg: String) { if (debug) System.err.println("proxy: $msg") }

    private fun serve(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }
            .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }
            .toMap()
        val parts = requestLine.split(' ')
        val out = socket.getOutputStream()
        val entry = parts.getOrNull(1)?.removePrefix("/a/")?.let(entries::get)
        if (parts.size < 2 || entry == null || parts[0] !in setOf("GET", "HEAD")) return out.status(404, "Not Found")
        try {
            if (entry.total <= 0) entry.total = probeLength(entry)
            val (start, endInclusive) = parseRange(headers["range"], entry.total) ?: return out.status(416, "Range Not Satisfiable")
            val partial = headers.containsKey("range")
            log("${parts[0]} range=${headers["range"]} -> $start-$endInclusive of ${entry.total}")
            val head = buildString {
                append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: ${entry.stream.mimeType.substringBefore(';')}\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: ${endInclusive - start + 1}\r\n")
                if (partial) append("Content-Range: bytes $start-$endInclusive/${entry.total}\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            if (parts[0] == "GET") copyRange(entry, start, endInclusive, out)
            out.flush()
        } catch (e: IOException) {
            // mpv closed the connection (seek or stop), or the CDN refused the rest of the stream.
            if (e is CdnRefusedException) cutOff.add(entry.videoId)
            log("connection ended: $e")
        }
    }

    private fun copyRange(entry: Entry, start: Long, endInclusive: Long, out: OutputStream) {
        val budget = CdnRecoveryBudget()
        var position = start
        val buffer = ByteArray(64 * 1024)
        while (position <= endInclusive) {
            val length = minOf(AudioRange.length(endInclusive - position + 1), endInclusive - position + 1)
            val request = Request.Builder().url(AudioRange.url(entry.stream.url, position, length))
                .apply { entry.stream.headers.forEach { (k, v) -> header(k, v) } }.build()
            http.newCall(request).execute().use { response ->
                if (response.code == 403 || response.code == 410) {
                    try { budget.beforeRefresh() } catch (e: IOException) { throw CdnRefusedException() }
                    entry.stream = runBlocking { entry.refresh?.invoke() ?: resolver.resolve(entry.videoId, forceRefresh = true, requiredItag = entry.stream.itag) }
                    return@use
                }
                log("chunk $position+$length -> ${response.code} len=${response.header("Content-Length")}")
                if (!response.isSuccessful) throw IOException("Audio server returned ${response.code}.")
                val body = response.body?.byteStream() ?: throw IOException("Empty audio response.")
                while (true) {
                    val n = body.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    streamSink?.let { sink -> runCatching { sink(entry.videoId, entry.stream.itag,
                        entry.stream.mimeType, entry.total, position, buffer, 0, n) } }
                    position += n
                    budget.onBytesRead(n)
                }
            }
        }
    }

    /** The full size, from the stream's `clen` or the first chunk's Content-Range. */
    private fun probeLength(entry: Entry): Long {
        val request = Request.Builder().url(AudioRange.url(entry.stream.url, 0, 1))
            .apply { entry.stream.headers.forEach { (k, v) -> header(k, v) } }.build()
        return http.newCall(request).execute().use { r ->
            AudioRange.totalLength(r.header("Content-Range")) ?: r.header("Content-Length")?.toLongOrNull()?.takeIf { it > 1 }
                ?: throw IOException("Audio server did not report the stream length.")
        }
    }

    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header == null) return 0L to total - 1
        val spec = header.removePrefix("bytes=").substringBefore(',')
        val from = spec.substringBefore('-')
        val to = spec.substringAfter('-', "")
        val (start, end) = if (from.isEmpty()) (total - (to.toLongOrNull() ?: return null)).coerceAtLeast(0) to total - 1
            else (from.toLongOrNull() ?: return null) to (to.toLongOrNull() ?: (total - 1)).coerceAtMost(total - 1)
        return if (start in 0..end) start to end else null
    }

    private fun OutputStream.status(code: Int, text: String) {
        write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        flush()
    }

    override fun close() { runCatching { server.close() } }
}

private class CdnRefusedException : IOException("YouTube refused the rest of this stream.")
