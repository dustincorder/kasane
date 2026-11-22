package cord.kasane.music.offline

import cord.kasane.music.model.Track
import cord.kasane.music.provider.HlsStream
import cord.kasane.music.provider.PlaybackStream
import cord.kasane.music.provider.ProgressiveStream
import cord.kasane.music.provider.SabrStream
import cord.kasane.music.provider.UnavailableStream

/** Pure decisions for caching and saving. Everything here is unit-tested without Android. */
object OfflinePolicy {
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    /** Stable cache identity. Includes the itag so two formats of one video can never share spans. */
    fun cacheKey(videoId: String, itag: Int) = "ytm:$videoId:$itag"

    fun isValidVideoId(id: String) = VIDEO_ID.matches(id)

    /** Local files are already local; only YouTube tracks have a streamed source to save. */
    fun canRequestSave(track: Track): Boolean = !track.isLocal && track.sourceProvider == "youtube" && isValidVideoId(track.id)

    sealed interface Eligibility {
        data object Direct : Eligibility
        data class NotSavable(val reason: String) : Eligibility
    }

    /** Only a plain progressive audio file can be stored byte for byte. HLS/SABR are streaming-only transports. */
    fun eligibility(stream: PlaybackStream): Eligibility = when (stream) {
        is ProgressiveStream ->
            if (stream.audio.contentLength > 0) Eligibility.Direct
            else Eligibility.NotSavable("YouTube did not report a file size for this track, so it cannot be verified as complete.")
        is HlsStream -> Eligibility.NotSavable("This track is delivered as a streaming-only format and cannot be saved offline.")
        is SabrStream -> Eligibility.NotSavable("This track is delivered as a streaming-only format and cannot be saved offline.")
        is UnavailableStream -> Eligibility.NotSavable(stream.reason.ifBlank { "This track is unavailable." })
    }

    /** Whether a pending download should wait instead of starting. */
    fun blockReason(offlineOnly: Boolean, wifiOnly: Boolean, metered: Boolean): String? = when {
        offlineOnly -> "Offline Only mode is on."
        wifiOnly && metered -> "Waiting for an unmetered network."
        else -> null
    }

    /** A cached resource is reusable only if the whole file is present; partial spans never mask a network read. */
    fun playableFromCache(record: AudioFileRecord?, cachedBytesFromStart: Long): Boolean =
        record != null && record.contentLength > 0 && record.cacheKey.isNotEmpty() && cachedBytesFromStart >= record.contentLength

    /** Quick limits offered in settings; anything else is a custom value. */
    val CACHE_PRESETS_MB = listOf(512, 1024, 2048, 5120)

    /** Parses a user-typed size ("1.5" GB or "800" MB) into megabytes, or null when unusable. */
    fun customLimitMb(text: String, gigabytes: Boolean): Int? {
        val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0) return null
        val mb = if (gigabytes) value * 1024 else value
        return mb.toInt().takeIf { it in cord.kasane.music.settings.AppSettings.MIN_CACHE_MB..cord.kasane.music.settings.AppSettings.MAX_CACHE_MB }
    }

    fun limitLabel(mb: Int): String = if (mb % 1024 == 0) "${mb / 1024} GB" else if (mb < 1024) "$mb MB" else "%.1f GB".format(mb / 1024.0)

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "%.0f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
        else -> "%.2f GB".format(bytes / 1073741824.0)
    }
}
