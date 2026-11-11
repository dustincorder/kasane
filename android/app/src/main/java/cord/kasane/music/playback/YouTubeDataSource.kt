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
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        original = dataSpec
        readBytes = 0
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
            resolver.open(id) { stream ->
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
                    if (BuildConfig.DEBUG) android.util.Log.d("KasanePlayback", "videoId=$id cdn=OPEN boundedRange=$chunkRemaining offset=${spec.position + readBytes}")
                    length
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    closeUpstream()
                    if (BuildConfig.DEBUG) android.util.Log.d("KasanePlayback", "videoId=$id cdnHttp=${e.responseCode} offset=${spec.position + readBytes} length=$chunkRemaining")
                    throw AudioHttpException(e.responseCode)
                } catch (e: java.io.IOException) {
                    closeUpstream()
                    throw java.io.IOException("Audio connection failed. Check your connection and retry.")
                }
            }
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
        val read = upstream.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
        if (read == C.RESULT_END_OF_INPUT) {
            if (remaining > 0) throw java.io.EOFException("Audio range ended before its expected length.")
            remaining = 0
            return C.RESULT_END_OF_INPUT
        }
        readBytes += read
        chunkRemaining -= read
        if (remaining >= 0) remaining -= read
        return read
    }
    override fun getUri(): Uri? = original?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    private fun closeUpstream() {
        if (upstreamOpen) { upstreamOpen = false; runCatching { upstream.close() } }
    }
    override fun close() {
        closeUpstream()
        original = null; videoId = null; readBytes = 0; remaining = -1; chunkRemaining = 0
    }
}
