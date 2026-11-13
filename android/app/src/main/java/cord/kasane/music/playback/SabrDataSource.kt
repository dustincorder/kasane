package cord.kasane.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import cord.kasane.music.provider.sabr.SabrSession
import java.io.IOException

/** Internal HLS/fMP4 bridge. No server/proxy, signed URL, audio file, or second player. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SabrDataSource(private val session: SabrSession, private val refreshClock: () -> Unit = {}) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var bytes: ByteArray? = null
    private var offset = 0
    private var limit = 0
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val target = dataSpec.uri
        val prefix = "/${session.config.videoId}/"
        if (target.scheme != "sabr" || target.host != "audio" || !target.path.orEmpty().startsWith(prefix))
            throw IOException("Invalid internal SABR resource.")
        // open() is called by Media3's loader, never by the UI thread.
        val resource = target.path.orEmpty().removePrefix(prefix)
        val data = when {
            resource == "index.m3u8" -> session.playlist()
            resource == "init" -> session.initSegment()
            resource.startsWith("segment/") -> {
                refreshClock()
                session.mediaSegment(resource.removePrefix("segment/").toIntOrNull()
                    ?: throw IOException("Invalid SABR segment number."))
            }
            else -> throw IOException("Unknown SABR resource.")
        }
        if (dataSpec.position !in 0..data.size.toLong()) throw IOException("SABR byte range is outside this segment.")
        offset = dataSpec.position.toInt()
        limit = if (dataSpec.length == C.LENGTH_UNSET.toLong()) data.size else
            (offset.toLong() + dataSpec.length.coerceAtMost(data.size.toLong())).coerceAtMost(data.size.toLong()).toInt()
        bytes = data; uri = target
        transferStarted(dataSpec)
        return (limit - offset).toLong()
    }
    override fun read(buffer: ByteArray, targetOffset: Int, length: Int): Int {
        if (length == 0) return 0
        val data = bytes ?: throw IOException("SABR resource is not open.")
        if (offset >= limit) return C.RESULT_END_OF_INPUT
        val read = minOf(length, limit - offset)
        data.copyInto(buffer, targetOffset, offset, offset + read); offset += read
        bytesTransferred(read); return read
    }
    override fun getUri(): Uri? = uri
    override fun close() {
        val opened = bytes != null
        bytes = null; uri = null; offset = 0; limit = 0
        if (opened) transferEnded()
    }
    class Factory(private val session: SabrSession, private val refreshClock: () -> Unit = {}) : DataSource.Factory {
        override fun createDataSource(): DataSource = SabrDataSource(session, refreshClock)
    }
}
