package cord.kasane.music.offline

import cord.kasane.music.model.Track

/**
 * Pure offline rules shared by Android and Compose Desktop: store identity,
 * save eligibility, blocked-request reasons and label formatting.
 * Stream-type checks (`eligibility`/`transient`) stay in the Android layer —
 * they need the PlaybackStream sealed types which carry platform transports.
 */
object OfflinePolicy {
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    const val MIN_CACHE_MB = 128
    const val MAX_CACHE_MB = 65536

    /** Stable cache identity. Includes the itag so two formats of one video can never share spans. */
    fun cacheKey(videoId: String, itag: Int) = "ytm:$videoId:$itag"

    fun isValidVideoId(id: String) = VIDEO_ID.matches(id)

    /** Local files are already local; only YouTube tracks have a streamed source to save. */
    fun canRequestSave(track: Track): Boolean = !track.isLocal && track.sourceProvider == "youtube" && isValidVideoId(track.id)

    /** Whether a pending download should wait instead of starting. */
    fun blockReason(offlineOnly: Boolean, wifiOnly: Boolean, metered: Boolean, networkUp: Boolean = true): String? = when {
        offlineOnly -> "Offline Only mode is on."
        !networkUp -> "Waiting for a connection."
        wifiOnly && metered -> "Waiting for an unmetered network."
        else -> null
    }

    /** A cached resource is reusable only if the whole file is present; partial spans never mask a network read. */
    fun playableFromCache(cacheKey: String, contentLength: Long, cachedBytesFromStart: Long): Boolean =
        cacheKey.isNotEmpty() && contentLength > 0 && cachedBytesFromStart >= contentLength

    /** Quick limits offered in settings; anything else is a custom value. */
    val CACHE_PRESETS_MB = listOf(512, 1024, 2048, 5120)

    /** Parses a user-typed size ("1.5" GB or "800" MB) into megabytes, or null when unusable. */
    fun customLimitMb(text: String, gigabytes: Boolean, minMb: Int = MIN_CACHE_MB, maxMb: Int = MAX_CACHE_MB): Int? {
        val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0) return null
        val mb = if (gigabytes) value * 1024 else value
        return mb.toInt().takeIf { it in minMb..maxMb }
    }

    fun limitLabel(mb: Int): String = if (mb % 1024 == 0) "${mb / 1024} GB" else if (mb < 1024) "$mb MB" else "%.1f GB".format(mb / 1024.0)

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "%.0f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
        else -> "%.2f GB".format(bytes / 1073741824.0)
    }
}

/**
 * Persistent Online/Offline mode. Each client stores it in its own settings and enforces it
 * on every network path — catalog, streams, metadata, lyrics — via [blockReason].
 */
enum class ConnectivityMode { ONLINE, OFFLINE_ONLY }

/** Download schedule shared by both clients' workers (Desktop copied these verbatim). */
object DownloadPlan {
    const val MAX_ATTEMPTS = 3
    const val PARALLEL_RANGES = 6
    const val MIN_RANGE_BYTES = 512L * 1024
    const val BLOCK_RECHECK_MS = 15_000L
    const val MAX_ARTWORK_BYTES = 2L * 1024 * 1024

    /** Splits a file into up to [PARALLEL_RANGES] byte ranges of at least [MIN_RANGE_BYTES]. */
    fun chunks(contentLength: Long): List<LongArray> {
        if (contentLength <= MIN_RANGE_BYTES) return listOf(longArrayOf(0, contentLength))
        val count = minOf(PARALLEL_RANGES, (contentLength / MIN_RANGE_BYTES).toInt())
        val size = (contentLength + count - 1) / count
        return (0 until count).map { longArrayOf(it * size, minOf((it + 1) * size, contentLength) - it * size) }
    }
}

/** Record states; UI badge mapping is `state→badge` on each client (see handoff). */
enum class AudioState(val key: String) {
    CACHED("cached"), QUEUED("queued"), DOWNLOADING("downloading"), SAVED("saved"), FAILED("failed");
    companion object { fun fromKey(key: String) = entries.firstOrNull { it.key == key } ?: FAILED }
}
