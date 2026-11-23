package cord.kasane.music.playback

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Native YouTube audio endpoints require a finite Range end, including at offset zero. */
object AudioRange {
    const val CHUNK_BYTES = 512 * 1024L
    fun length(remaining: Long): Long = if (remaining < 0) CHUNK_BYTES else minOf(CHUNK_BYTES, remaining)
    fun absoluteOffset(startPosition: Long, bytesRead: Long): Long {
        require(startPosition >= 0 && bytesRead >= 0 && startPosition <= Long.MAX_VALUE - bytesRead)
        return startPosition + bytesRead
    }
    fun url(url: String, position: Long, length: Long): String {
        require(position >= 0 && length > 0 && position <= Long.MAX_VALUE - length)
        return url.toHttpUrl().newBuilder().setQueryParameter("range", "$position-${position + length - 1}").build().toString()
    }
    fun totalLength(contentRange: String?): Long? =
        contentRange?.substringAfterLast('/')?.toLongOrNull()?.takeIf { it > 0 }
}

/** Bounds repeated CDN refreshes until the source makes forward progress. */
class CdnRecoveryBudget(private val maxConsecutiveRefreshes: Int = 2) {
    private var consecutiveRefreshes = 0

    @Synchronized
    fun beforeRefresh() {
        if (consecutiveRefreshes >= maxConsecutiveRefreshes) {
            throw java.io.IOException("Audio server repeatedly refused this part of the stream. Try the track again.")
        }
        consecutiveRefreshes++
    }

    @Synchronized
    fun onBytesRead(bytes: Int) {
        if (bytes > 0) consecutiveRefreshes = 0
    }

    @Synchronized
    fun reset() { consecutiveRefreshes = 0 }

    @Synchronized
    fun consecutiveRefreshes(): Int = consecutiveRefreshes
}
