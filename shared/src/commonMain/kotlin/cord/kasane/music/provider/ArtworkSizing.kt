package cord.kasane.music.provider

/**
 * YouTube Music returns artwork at the size of the row that asked for it: search rows carry `=w120-h120-l90-rj`,
 * even though the same image is served at any size from the same host. Requesting the size that will actually be
 * drawn keeps list thumbnails small and makes full-screen covers sharp, without scaling or filtering anything
 * client-side. URLs that are not Google-hosted, or that carry no size parameter, are returned untouched.
 */
object ArtworkSizing {
    /** Discrete sizes so equivalent requests share one image-cache entry instead of one per pixel count. */
    val BUCKETS = listOf(120, 240, 360, 544, 800, 1200)
    const val MAX_PX = 1200

    private val SQUARE = Regex("=w(\\d+)-h(\\d+)((?:-[A-Za-z0-9]+)*)$")
    private val SINGLE = Regex("=s(\\d+)((?:-[A-Za-z0-9]+)*)$")

    fun isResizable(url: String): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()
        return (host.endsWith(".googleusercontent.com") || host.endsWith(".ggpht.com")) &&
            (SQUARE.containsMatchIn(url) || SINGLE.containsMatchIn(url))
    }

    fun bucketFor(pixels: Int): Int = BUCKETS.firstOrNull { it >= pixels } ?: MAX_PX

    /** The URL to request when the image will be drawn about [pixels] wide. Never asks for less than the URL already has. */
    fun sized(url: String, pixels: Int): String {
        if (!isResizable(url)) return url
        val target = bucketFor(pixels.coerceAtLeast(1))
        SQUARE.find(url)?.let { m ->
            val current = maxOf(m.groupValues[1].toInt(), m.groupValues[2].toInt())
            val size = maxOf(target, current.coerceAtMost(MAX_PX))
            return url.replaceRange(m.range, "=w$size-h$size${m.groupValues[3]}")
        }
        SINGLE.find(url)?.let { m ->
            val size = maxOf(target, m.groupValues[1].toInt().coerceAtMost(MAX_PX))
            return url.replaceRange(m.range, "=s$size${m.groupValues[2]}")
        }
        return url
    }
}
