package cord.kasane.music.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * One anonymous iOS player request, the full-length HLS manifest YouTube gives that client. Guest direct
 * (ANDROID_VR) URLs now stop after the first range, so this is the guest path for full tracks. Same client
 * profile as Android's HlsPlayerResolver; no cookies, no attestation.
 */
class IosHlsResolver(private val client: OkHttpClient, private val endpoint: String = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false") {
    class Manifest(val url: String, val playbackStatsUrl: String? = null, val watchtimeUrl: String? = null) {
        val userAgent: String get() = USER_AGENT
        override fun toString() = "Manifest(url=[redacted])"
    }

    suspend fun resolve(videoId: String): Manifest = withContext(Dispatchers.IO) {
        if (!Regex("[A-Za-z0-9_-]{11}").matches(videoId)) throw StreamUnavailableException("INVALID_VIDEO_ID")
        val body = JSONObject().put("videoId", videoId).put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "IOS").put("clientVersion", VERSION).put("deviceModel", "iPhone16,2")
            .put("osName", "iPhone").put("osVersion", "18.5.0.22F76").put("hl", "en").put("gl", "US")))
        val request = Request.Builder().url(endpoint)
            .header("User-Agent", USER_AGENT).header("X-Youtube-Client-Name", "5").header("X-Youtube-Client-Version", VERSION)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val root = try {
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw PlayerHttpException(r.code)
                JSONObject(r.body?.string() ?: throw IOException("Empty player response."))
            }
        } catch (e: PlayerHttpException) { throw e
        } catch (e: org.json.JSONException) { throw IOException("YouTube returned an unreadable player response.")
        } catch (e: IOException) { throw IOException("Could not reach YouTube. Check your connection.") }
        val returned = root.optJSONObject("videoDetails")?.optString("videoId").orEmpty()
        if (returned.isNotEmpty() && returned != videoId) throw StreamUnavailableException("ID_MISMATCH")
        val status = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        if (status != "OK") throw StreamUnavailableException(status)
        val url = root.optJSONObject("streamingData")?.optString("hlsManifestUrl").orEmpty()
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        val host = uri?.host.orEmpty()
        if (uri?.scheme != "https" || uri.userInfo != null || !(host == "googlevideo.com" || host.endsWith(".googlevideo.com")))
            throw StreamUnavailableException("NO_DIRECT_STREAM")
        val (stats, watchtime) = StreamResponse.playbackTracking(root)
        Manifest(url, stats, watchtime)
    }

    companion object {
        const val VERSION = "21.26.4"
        const val USER_AGENT = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_5 like Mac OS X;)"
    }
}
