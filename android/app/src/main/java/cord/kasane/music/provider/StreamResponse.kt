package cord.kasane.music.provider

import org.json.JSONObject
import java.io.IOException
import java.net.URI

class StreamUnavailableException(val status: String) : IOException(
    when (status) {
        "LOGIN_REQUIRED" -> "YouTube rejected the guest session or this track requires an account. Try again or choose another track."
        "AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED" -> "This track requires age verification on YouTube."
        "UNPLAYABLE" -> "This track is unavailable for this client or region."
        "EXPIRED_STREAM" -> "YouTube returned an expired audio link. Try again."
        "INVALID_VIDEO_ID" -> "This item is not a playable YouTube track."
        "ID_MISMATCH" -> "YouTube returned audio metadata for a different track."
        "NO_DIRECT_STREAM" -> "YouTube did not provide a supported audio stream for this track."
        else -> "This track is currently unavailable on YouTube."
    }
)

object StreamResponse {
    fun isAnonymousSessionChallenge(root: JSONObject): Boolean {
        val playability = root.optJSONObject("playabilityStatus") ?: return false
        val reason = playability.optString("reason").lowercase(java.util.Locale.ROOT)
        return playability.optString("status") == "LOGIN_REQUIRED" && "confirm" in reason && "bot" in reason
    }

    fun audioUrl(root: JSONObject): String = audioStream(root, System.currentTimeMillis()).url

    fun audioStream(root: JSONObject, nowMs: Long, requiredItag: Int? = null): ResolvedAudioStream {
        val status = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        if (status.isNotBlank() && status != "OK") throw StreamUnavailableException(status)
        val data = root.optJSONObject("streamingData") ?: throw StreamUnavailableException("NO_DIRECT_STREAM")
        val formats = data.optJSONArray("adaptiveFormats") ?: throw StreamUnavailableException("NO_DIRECT_STREAM")
        val selected = (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }
            .filter {
                it.optString("mimeType").startsWith("audio/") &&
                    runCatching { URI(it.optString("url")) }.getOrNull()?.let { uri ->
                        uri.scheme == "https" && !uri.host.isNullOrBlank()
                    } == true &&
                    it.optJSONArray("drmFamilies").let { families -> families == null || families.length() == 0 } &&
                    it.optString("drmTrackType").isBlank() &&
                    (requiredItag == null || it.optInt("itag") == requiredItag)
            }
            .maxByOrNull { it.optLong("bitrate") } ?: throw StreamUnavailableException("NO_DIRECT_STREAM")
        val url = selected.getString("url")
        val signedExpiry = URI(url).rawQuery?.split('&')?.firstOrNull { it.startsWith("expire=") }
            ?.substringAfter('=')?.toLongOrNull()?.takeIf { it > 0 && it < Long.MAX_VALUE / 1000 }?.times(1000)
        val ttl = data.optLong("expiresInSeconds", 300).coerceIn(0, 86400) * 1000
        return ResolvedAudioStream(url, selected.optInt("itag"), selected.optString("mimeType"),
            minOf(signedExpiry ?: Long.MAX_VALUE, nowMs + ttl), selected.optLong("contentLength", -1))
    }
}
