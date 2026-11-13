package cord.kasane.music.provider.sabr

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import kotlin.math.ceil

class SabrException(val code: String, message: String) : IOException(message)

/** Ephemeral transport configuration; never persisted or printed. Only clear AAC VOD is supported. */
class SabrConfig internal constructor(
    val videoId: String, val profile: String, internal val url: String,
    internal val formatId: ByteArray, internal val ustreamer: ByteArray, internal val clientInfo: ByteArray,
    internal val itag: Int,
) {
    override fun toString() = "SabrConfig(profile=$profile, credentials=[redacted])"
    companion object {
        fun parse(root: JSONObject, videoId: String, profile: String, clientVersion: String? = null): SabrConfig? {
            if (profile !in setOf("IOS", "WEB_REMIX", "WEB", "MWEB") || !videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
            if (clientVersion != null && !clientVersion.matches(Regex("[0-9.]{3,40}"))) return null
            if (root.optJSONObject("playabilityStatus")?.optString("status") != "OK" ||
                root.optJSONObject("videoDetails")?.optBoolean("isLiveContent") == true) return null
            val id = root.optJSONObject("videoDetails")?.optString("videoId").orEmpty()
            if (id.isNotEmpty() && id != videoId) return null
            val data = root.optJSONObject("streamingData") ?: return null
            val url = data.optString("serverAbrStreamingUrl")
            if (!allowedUrl(url)) return null
            val encoded = root.optJSONObject("playerConfig")?.optJSONObject("mediaCommonConfig")
                ?.optJSONObject("mediaUstreamerRequestConfig")?.optString("videoPlaybackUstreamerConfig").orEmpty()
            if (encoded.isEmpty() || encoded.length > 128 * 1024) return null
            val ustreamer = runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull() ?: return null
            val formats = data.optJSONArray("adaptiveFormats") ?: return null
            val audio = (0 until formats.length()).mapNotNull(formats::optJSONObject).firstOrNull {
                it.optInt("itag") == 140 && it.optString("mimeType").startsWith("audio/mp4") &&
                    it.optString("mimeType").contains("mp4a.40.2") && it.optString("drmTrackType").isBlank() &&
                    (it.optJSONArray("drmFamilies")?.length() ?: 0) == 0
            } ?: return null
            val modified = audio.optString("lastModified").toLongOrNull()?.takeIf { it > 0 } ?: return null
            val formatId = SabrWire.number(1, 140) + SabrWire.number(2, modified) +
                audio.optString("xtags").takeIf(String::isNotBlank).let { if (it == null) byteArrayOf() else SabrWire.text(3, it) }
            val info = if (profile == "IOS") SabrWire.text(1, "en") + SabrWire.text(13, "iPhone16,2") +
                SabrWire.number(16, 5) + SabrWire.text(17, "21.26.4") + SabrWire.text(18, "iPhone") + SabrWire.text(19, "18.5.0.22F76")
                else SabrWire.text(1, "en") + SabrWire.number(16, when(profile) {"WEB" -> 1; "MWEB" -> 2; else -> 67}) +
                    SabrWire.text(17, clientVersion ?: "1.20260707.12.00")
            return SabrConfig(videoId, profile, url, formatId, ustreamer, info, 140)
        }
        internal fun allowedUrl(url: String): Boolean = runCatching {
            val uri = java.net.URI(url); val host = uri.host.orEmpty()
            uri.scheme == "https" && uri.rawUserInfo == null && uri.port in setOf(-1, 443) &&
                (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) && uri.path == "/videoplayback"
        }.getOrDefault(false)
    }
}

/** Content-bound Web proof. Never valid for native IOS/ANDROID profiles or a different video. */
class WebPlaybackProof(val videoId: String, bytes: ByteArray, val expiresAtMs: Long) {
    internal val bytes = bytes.copyOf()
    init { require(videoId.matches(Regex("[A-Za-z0-9_-]{11}")) && bytes.size in 32..4096) }
    internal fun validFor(config: SabrConfig) = videoId == config.videoId &&
        config.profile in setOf("WEB", "MWEB", "WEB_REMIX") && expiresAtMs > System.currentTimeMillis() + 30000
    override fun toString() = "WebPlaybackProof(value=[redacted])"
}

/** SABR requests/UMP replies are translated into bounded, on-demand fMP4 segments.
 * This does not mint or fake attestation. A server protection rejection is terminal. */
class SabrSession(
    val config: SabrConfig,
    client: OkHttpClient,
    private val diagnostic: (String) -> Unit = {},
    // Only JVM fixtures can use a loopback endpoint. No credential-bearing URL is supplied here.
    internal val testEndpoint: String? = null,
    private val proof: WebPlaybackProof? = null,
) : AutoCloseable {
    private val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false)
        .followSslRedirects(false).retryOnConnectionFailure(false).build()
    @Volatile private var closed = false
    @Volatile private var activeCall: okhttp3.Call? = null
    @Volatile private var reportedPositionMs = -1L
    private val startedAt = System.nanoTime()
    @Volatile private var lastSeekAt = startedAt
    private var url = config.url
    private var requestNumber = 0L
    private var playbackCookie = byteArrayOf()
    private var protection = 0L
    private var initialization: ByteArray? = null
    private val cache = linkedMapOf<Int, Segment>()
    // Transport acknowledgements are metadata, not the raw-byte RAM cache.
    private val received = sortedMapOf<Int, Header>()
    private val contexts = mutableMapOf<Int, Pair<ByteArray, Boolean>>()
    private var totalTicks = 0L
    private var metadataScale = 0L
    private var totalSegments = 0
    private var segmentTicks = 0L
    private var timescale = 0L
    private var ready = false
    private var targetReadaheadMs = 15000L
    private data class Header(val id: Int, val init: Boolean, val number: Int, val ticks: Long,
        val duration: Long, val scale: Long, val length: Long)
    private data class Segment(val header: Header, val bytes: ByteArray)
    init {
        require(proof == null || proof.validFor(config)) { "Playback proof does not match this web stream." }
        require(testEndpoint == null || (cord.kasane.music.BuildConfig.DEBUG &&
            testEndpoint.toHttpUrl().let { it.scheme == "http" && it.host in setOf("localhost", "127.0.0.1") }))
    }
    @Synchronized fun initialize() {
        if (closed) throw IOException("SABR session was closed.")
        if (ready) return
        fetch(0)
        val first = cache[1] ?: throw unavailable()
        segmentTicks = first.header.duration; timescale = first.header.scale
        if (initialization == null || timescale <= 0 || timescale != metadataScale || segmentTicks <= 0 || totalTicks <= 0 ||
            totalSegments !in 1..4096 || ceil(totalTicks.toDouble() / segmentTicks).toInt() != totalSegments ||
            first.header.ticks != 0L) throw SabrException("UNSUPPORTED_LAYOUT", "YouTube returned an unsupported SABR segment layout.")
        cache.values.forEach(::validateSegment)
        ready = true
    }
    /** Eligibility is not full-track proof: a pending cold-start must not be
     * offered as a usable source when no asynchronous attestation provider exists. */
    fun requireVerifiedForPlayback() {
        if (!ready) throw SabrException("NOT_INITIALIZED", "SABR audio has not been prepared.")
        if (protection != 1L) throw SabrException("ATTESTATION_REQUIRED",
            "YouTube requires verified playback attestation for this SABR stream. A compatible verification is not available yet.")
    }
    @Synchronized fun playlist(): ByteArray {
        initialize()
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MEDIA-SEQUENCE:1\n")
            append("#EXT-X-TARGETDURATION:${ceil(segmentTicks.toDouble() / timescale).toInt()}\n")
            append("#EXT-X-MAP:URI=\"init\"\n")
            for (n in 1..totalSegments) {
                val ticks = minOf(segmentTicks, totalTicks - (n - 1L) * segmentTicks)
                append("#EXTINF:${String.format(java.util.Locale.US, "%.9f", ticks.toDouble() / timescale)},\nsegment/$n\n")
            }
            append("#EXT-X-ENDLIST\n")
        }.toByteArray()
    }
    @Synchronized fun initSegment(): ByteArray { initialize(); return requireNotNull(initialization) }
    @Synchronized fun mediaSegment(number: Int): ByteArray {
        initialize()
        if (number !in 1..totalSegments) throw IOException("SABR segment is outside the track.")
        cache[number]?.let { return it.bytes }
        if (number in received) received.clear() // Re-fetch a previously delivered seek target.
        fetch((number - 1L) * segmentTicks * 1000 / timescale)
        val segment = cache[number] ?: throw SabrException("MISSING_SEGMENT", "YouTube returned a different SABR segment than requested.")
        validateSegment(segment)
        return segment.bytes
    }
    private fun validateSegment(segment: Segment) {
        val h = segment.header
        val expected = (h.number - 1L) * segmentTicks
        val duration = minOf(segmentTicks, totalTicks - expected)
        if (h.number !in 1..totalSegments || h.scale != timescale || h.ticks != expected || h.duration != duration)
            throw SabrException("UNSUPPORTED_LAYOUT", "YouTube changed the SABR segment timeline; playback was stopped safely.")
    }
    private fun unavailable(): SabrException = if (protection == 3L)
        SabrException("ATTESTATION_REQUIRED", "YouTube requires playback attestation for this SABR stream. Kasane has no compatible attestation provider.")
        else if (protection == 2L) SabrException("ATTESTATION_PENDING", "YouTube playback attestation is pending and no audio segment was supplied.")
        else SabrException("MISSING_SEGMENT", "YouTube did not provide the requested SABR audio segment.")
    private fun requestBody(positionMs: Long): ByteArray {
        val now = System.nanoTime()
        val abr = SabrWire.number(17, 1) + SabrWire.number(23, 1_000_000) + SabrWire.number(28, reportedPositionMs.takeIf { it >= 0 } ?: positionMs) +
            SabrWire.number(29, (now - lastSeekAt) / 1_000_000) + SabrWire.number(36, (now - startedAt) / 1_000_000) +
            SabrWire.number(39, (now - startedAt) / 1_000_000) +
            SabrWire.number(34, 2) + SabrWire.number(40, 1) // Audio only, not video-only (2).
        var context = SabrWire.bytes(1, config.clientInfo)
        proof?.let { context += SabrWire.bytes(2, it.bytes) }
        if (playbackCookie.isNotEmpty()) context += SabrWire.bytes(3, playbackCookie)
        contexts.forEach { (type, value) ->
            context += if (value.second) SabrWire.bytes(5, SabrWire.number(1, type.toLong()) + SabrWire.bytes(2, value.first))
                else SabrWire.number(6, type.toLong())
        }
        var body = SabrWire.bytes(1, abr) + SabrWire.number(4, positionMs) + SabrWire.bytes(5, config.ustreamer) +
            SabrWire.bytes(16, config.formatId) + SabrWire.bytes(19, context)
        if (initialization != null) body += SabrWire.bytes(2, config.formatId)
        // Merge exact contiguous segment IDs into one interval. Per-segment
        // millisecond rounding otherwise introduces fictitious one-ms gaps.
        val groups = mutableListOf<MutableList<Header>>()
        received.values.forEach { h ->
            val tail = groups.lastOrNull()
            if (tail != null && tail.last().number + 1 == h.number) tail.add(h) else groups.add(mutableListOf(h))
        }
        groups.forEach { group ->
            val first = group.first(); val last = group.last()
            val ticks = last.ticks + last.duration - first.ticks
            val startMs = first.ticks * 1000 / first.scale
            val endMs = (last.ticks + last.duration) * 1000 / last.scale
            body += SabrWire.bytes(3, SabrWire.bytes(1, config.formatId) + SabrWire.number(2, startMs) +
                SabrWire.number(3, endMs - startMs) + SabrWire.number(4, first.number.toLong()) + SabrWire.number(5, last.number.toLong()) +
                SabrWire.bytes(6, SabrWire.number(1, first.ticks) + SabrWire.number(2, ticks) + SabrWire.number(3, first.scale)))
        }
        return body
    }
    fun updatePlaybackPosition(positionMs: Long) {
        val value = positionMs.coerceAtLeast(0)
        if (reportedPositionMs >= 0 && kotlin.math.abs(value - reportedPositionMs) > 3000) lastSeekAt = System.nanoTime()
        reportedPositionMs = value
    }
    private fun fetch(positionMs: Long) {
        if (closed) throw IOException("SABR session was closed.")
        // SABR controls its own readahead budget. Media3 may ask for 50 seconds
        // ahead; wait on its real playback clock instead of treating a pacing
        // response as EOF or pretending those seconds were already played.
        while (reportedPositionMs >= 0 && positionMs > reportedPositionMs + targetReadaheadMs - 1000 && !closed) {
            try { Thread.sleep(100) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); throw IOException("SABR request was canceled.")
            }
        }
        if (closed) throw IOException("SABR session was closed.")
        var redirects = 0
        var emptyAttempts = 0
        while (true) {
            val target = (testEndpoint ?: url).toHttpUrl().newBuilder().setQueryParameter("rn", (requestNumber++).toString()).build()
            val request = Request.Builder().url(target).header("Accept", "application/vnd.yt-ump")
                .header("Accept-Encoding", "identity").post(requestBody(positionMs).toRequestBody("application/x-protobuf".toMediaType())).build()
            val headers = mutableMapOf<Int, Pair<Header, ByteArrayOutputStream>>()
            var redirected: String? = null
            var added = 0
            var backoffMs = 250L
            val call = http.newCall(request); activeCall = call
            try {
                if (closed) { call.cancel(); throw IOException("SABR session was closed.") }
                call.execute().use { response ->
                    diagnostic("videoId=${config.videoId} client=${config.profile} transport=SABR request=${requestNumber - 1} positionMs=$positionMs http=${response.code}")
                    if (!response.isSuccessful) {
                        if (response.code == 429 || response.code >= 500) throw IOException("Temporary SABR server failure.")
                        throw SabrException("HTTP_${response.code}", "YouTube refused the SABR request (HTTP ${response.code}).")
                    }
                    val input = response.body?.byteStream() ?: throw IOException("Empty SABR response.")
                    SabrWire.readParts(input) { type, bytes ->
                        when (type) {
                            12 -> throw SabrException("ENCRYPTED", "Encrypted SABR media is not supported.")
                            20 -> {
                                val p = SabrWire.decode(bytes)
                                if (p.text(2) != config.videoId) throw IOException("SABR returned a different track.")
                                val format = p.message(13)
                                if (p.number(3, format.number(1)).toInt() != config.itag) return@readParts
                                if (p.number(7) != 0L || p.number(17) != 0L) throw IOException("Unsupported SABR media encoding.")
                                val tr = p.message(15)
                                val scale = tr.number(3, 1000)
                                val h = Header(p.number(1).toInt(), p.number(8) == 1L, p.number(9).toInt(),
                                    tr.number(1, p.number(11)), tr.number(2, p.number(12)), scale, p.number(14, -1))
                                SabrWire.requireSafe(h.id in 0..255 && headers.size < 64 && h.length in -1..SabrWire.MAX_PART.toLong() &&
                                    h.scale in 1..1_000_000_000 && h.ticks in 0..100_000_000_000_000L && h.duration in 0..100_000_000_000_000L)
                                SabrWire.requireSafe(h.id !in headers)
                                headers[h.id] = h to ByteArrayOutputStream()
                            }
                            21 -> {
                                SabrWire.requireSafe(bytes.isNotEmpty())
                                headers[bytes[0].toInt() and 255]?.let { (_, buffer) ->
                                    SabrWire.requireSafe(buffer.size() + bytes.size - 1 <= SabrWire.MAX_PART)
                                    buffer.write(bytes, 1, bytes.size - 1)
                                }
                            }
                            22 -> {
                                SabrWire.requireSafe(bytes.isNotEmpty())
                                headers.remove(bytes[0].toInt() and 255)?.let { (h, buffer) ->
                                    val media = buffer.toByteArray()
                                    SabrWire.requireSafe(media.isNotEmpty() && (h.length < 0 || media.size.toLong() == h.length))
                                    if (h.init) initialization = media else {
                                        val segment = Segment(h, media)
                                        if (ready) validateSegment(segment)
                                        cache[h.number] = segment
                                        received[h.number] = h
                                        while (received.size > 64) received.remove(received.keys.first())
                                        while (cache.size > 4) cache.remove(cache.keys.first())
                                        added++
                                        diagnostic("videoId=${config.videoId} client=${config.profile} transport=SABR segment=${h.number} bytes=${media.size} protection=$protection")
                                    }
                                }
                            }
                            35 -> {
                                val policy = SabrWire.decode(bytes)
                                targetReadaheadMs = policy.number(1, 15000).coerceIn(5000, 120000)
                                backoffMs = policy.number(4, 250).coerceIn(250, 2000)
                                policy.data(7)?.let { SabrWire.requireSafe(it.size <= 64 * 1024); playbackCookie = it }
                            }
                            42 -> {
                                val p = SabrWire.decode(bytes)
                                if (p.text(1) != config.videoId) throw IOException("SABR returned a different track.")
                                if (p.message(2).number(1).toInt() == config.itag) {
                                    val scale = p.number(10, 1000)
                                    val end = p.number(9, p.number(3))
                                    SabrWire.requireSafe(scale in 1..1_000_000_000 && end in 1..scale * 86400 && p.number(4) in 1..4096)
                                    totalTicks = end; metadataScale = scale; totalSegments = p.number(4).toInt()
                                }
                            }
                            43 -> {
                                val redirect = SabrWire.decode(bytes).text(1)
                                if (!SabrConfig.allowedUrl(redirect)) throw IOException("Unsafe SABR redirect was blocked.")
                                redirected = redirect
                            }
                            44 -> throw SabrException("SERVER_ERROR", "YouTube returned a SABR protocol error.")
                            46 -> throw SabrException("RELOAD_REQUIRED", "The SABR playback session expired. Select the track again.")
                            57 -> {
                                val p = SabrWire.decode(bytes); val key = p.number(1).toInt(); val value = p.data(3) ?: byteArrayOf()
                                SabrWire.requireSafe(contexts.size < 32 && value.size <= 64 * 1024)
                                if (p.number(5) != 2L || key !in contexts) contexts[key] = value to (p.number(4) == 1L)
                            }
                            58 -> {
                                protection = SabrWire.decode(bytes).number(1)
                                diagnostic("videoId=${config.videoId} client=${config.profile} transport=SABR event=protection status=$protection")
                                if (protection == 3L) throw unavailable()
                            }
                            59 -> {
                                val p = SabrWire.decode(bytes)
                                p.repeatedNumbers(1).forEach { n -> contexts[n.toInt()]?.let { contexts[n.toInt()] = it.first to true } }
                                p.repeatedNumbers(2).forEach { n -> contexts[n.toInt()]?.let { contexts[n.toInt()] = it.first to false } }
                                p.repeatedNumbers(3).forEach { contexts.remove(it.toInt()) }
                            }
                        }
                    }
                }
            } catch (e: SabrException) { throw e
            } catch (_: IOException) { throw IOException("Could not read the SABR audio transport.")
            } finally { activeCall = null }
            if (redirected != null) {
                url = requireNotNull(redirected)
                if (added == 0) {
                    if (++redirects > 2) throw IOException("Too many SABR redirects.")
                    continue
                }
            }
            if (headers.isNotEmpty()) throw IOException("Incomplete SABR media segment.")
            if (added == 0) {
                // A pending proof may be accompanied by a pacing response. Wait
                // once, then stop; never manufacture proof or spin indefinitely.
                if (protection == 2L && emptyAttempts++ == 0 && !closed) {
                    try { Thread.sleep(backoffMs) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt(); throw IOException("SABR request was canceled.")
                    }
                    if (closed) throw IOException("SABR session was closed.")
                    continue
                }
                throw unavailable()
            }
            return
        }
    }
    override fun close() { closed = true; activeCall?.cancel() }
    override fun toString() = "SabrSession(credentials=[redacted])"
}
