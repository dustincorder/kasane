package cord.kasane.music.playback

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Native YouTube audio endpoints require a finite Range end, including at offset zero. */
object AudioRange {
    const val CHUNK_BYTES = 512 * 1024L
    fun length(remaining: Long): Long = if (remaining < 0) CHUNK_BYTES else minOf(CHUNK_BYTES, remaining)
    fun url(url: String, position: Long, length: Long): String {
        require(position >= 0 && length > 0 && position <= Long.MAX_VALUE - length)
        return url.toHttpUrl().newBuilder().setQueryParameter("range", "$position-${position + length - 1}").build().toString()
    }
    fun totalLength(contentRange: String?): Long? =
        contentRange?.substringAfterLast('/')?.toLongOrNull()?.takeIf { it > 0 }
}
