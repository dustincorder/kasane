package cord.kasane.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import cord.kasane.music.BuildConfig
import cord.kasane.music.provider.AudioHttpException
import cord.kasane.music.provider.GuestPlayerResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** A single logical resource, read in bounded HTTP ranges on Media3's loader thread. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class YouTubeDataSource(private val upstream: DataSource, private val resolver: GuestPlayerResolver) : DataSource {
    private var original: DataSpec? = null
    private var videoId: String? = null
    private var readBytes = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var chunkRemaining = 0L
    private var upstreamOpen = false
    private val recoveryBudget = CdnRecoveryBudget()
    private var chunkIndex = 0L
    private var lastResponseCode: Int? = null
    private var lastContentRange: String? = null
    private var forceRefreshNextChunk = false
    private var lastItag: Int? = null
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        original = dataSpec
        readBytes = 0
        recoveryBudget.reset()
        chunkIndex = 0
        forceRefreshNextChunk = false
        lastItag = null
        videoId = if (dataSpec.uri.scheme == "kasane") dataSpec.uri.lastPathSegment else null
        if (dataSpec.uri.scheme != "kasane") {
            upstreamOpen = true
            return upstream.open(dataSpec)
        }
        if (videoId.isNullOrBlank()) throw java.io.IOException("Missing YouTube video ID.")
        remaining = dataSpec.length
        openChunk()
        return remaining
    }

    private fun openChunk() {
        val spec = original ?: throw java.io.IOException("Audio source is closed.")
        val id = videoId ?: throw java.io.IOException("Missing YouTube video ID.")
        chunkRemaining = AudioRange.length(remaining)
        runBlocking(Dispatchers.IO) {
            val forceRefresh = forceRefreshNextChunk
            forceRefreshNextChunk = false
            resolver.open(id, forceRefresh = forceRefresh,
                requiredItag = if (forceRefresh) lastItag else null,
                operation = { stream ->
                lastItag = stream.itag
                if (remaining == C.LENGTH_UNSET.toLong() && stream.contentLength > 0)
                    remaining = (stream.contentLength - spec.position - readBytes).coerceAtLeast(0)
                chunkRemaining = AudioRange.length(remaining)
                if (chunkRemaining == 0L) return@open 0L
                // Google's media range query selects the slice. HTTP Range must not apply
                // the absolute offset again to that already sliced resource (seek would fail).
                val request = spec.buildUpon()
                    .setUri(Uri.parse(AudioRange.url(stream.url, spec.position + readBytes, chunkRemaining)))
                    .setPosition(0).setLength(C.LENGTH_UNSET.toLong())
                    .setHttpRequestHeaders(spec.httpRequestHeaders.filterKeys { !it.equals("Range", true) } + stream.headers)
                    .build()
                try {
                    upstreamOpen = true
                    val length = upstream.open(request)
                    if (length > chunkRemaining) throw java.io.IOException("Audio server ignored the requested range.")
                    lastResponseCode = (upstream as? HttpDataSource)?.responseCode
                    lastContentRange = upstream.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }
                        ?.value?.firstOrNull()
                    chunkIndex++
                    if (BuildConfig.DEBUG) android.util.Log.d("KasanePlayback",
                        "videoId=$id cdn=OPEN chunk=$chunkIndex code=$lastResponseCode offset=${AudioRange.absoluteOffset(spec.position, readBytes)} requested=$chunkRemaining responseLength=$length contentRange=${lastContentRange ?: "none"}")
                    length
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    lastResponseCode = e.responseCode
                    lastContentRange = e.headerFields.entries.firstOrNull { it.key.equals("Content-Range", true) }
                        ?.value?.firstOrNull()
                    closeUpstream()
                    if (BuildConfig.DEBUG) android.util.Log.d("KasanePlayback",
                        "videoId=$id cdnHttp=${e.responseCode} chunk=$chunkIndex offset=${AudioRange.absoluteOffset(spec.position, readBytes)} requested=$chunkRemaining contentRange=${lastContentRange ?: "none"}")
                    throw AudioHttpException(e.responseCode)
                } catch (e: java.io.IOException) {
                    closeUpstream()
                    throw java.io.IOException("Audio connection failed. Check your connection and retry.")
                }
            }, onRefresh = { markCdnRefresh(id, spec.position + readBytes, chunkRemaining) })
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (videoId == null) return upstream.read(buffer, offset, length)
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (chunkRemaining == 0L) {
            closeUpstream()
            openChunk()
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
        }
        val read = try {
            upstream.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode != 403 && e.responseCode != 410) throw e
            closeUpstream()
            forceRefreshNextChunk = true
            chunkRemaining = 0
            openChunk()
            return read(buffer, offset, length)
        }
        if (read == C.RESULT_END_OF_INPUT) {
            if (remaining > 0) throw java.io.EOFException("Audio range ended before its expected length.")
            remaining = 0
            return C.RESULT_END_OF_INPUT
        }
        readBytes += read
        chunkRemaining -= read
        if (remaining >= 0) remaining -= read
        recoveryBudget.onBytesRead(read)
        return read
    }
    override fun getUri(): Uri? = original?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    private fun closeUpstream() {
        if (upstreamOpen) { upstreamOpen = false; runCatching { upstream.close() } }
    }
    private fun markCdnRefresh(id: String, absoluteOffset: Long, requestedLength: Long) {
        try { recoveryBudget.beforeRefresh() } catch (e: java.io.IOException) {
            if (BuildConfig.DEBUG) android.util.Log.w("KasanePlayback",
                "videoId=$id cdn=REFRESH_LIMIT offset=$absoluteOffset requested=$requestedLength")
            throw e
        }
        if (BuildConfig.DEBUG) android.util.Log.w("KasanePlayback",
            "videoId=$id cdn=REFRESH attempt=${recoveryBudget.consecutiveRefreshes()} offset=$absoluteOffset requested=$requestedLength previousCode=$lastResponseCode previousContentRange=${lastContentRange ?: "none"}")
    }
    override fun close() {
        closeUpstream()
        original = null; videoId = null; readBytes = 0; remaining = -1; chunkRemaining = 0
        recoveryBudget.reset(); chunkIndex = 0; lastResponseCode = null; lastContentRange = null
        forceRefreshNextChunk = false; lastItag = null
    }
}
