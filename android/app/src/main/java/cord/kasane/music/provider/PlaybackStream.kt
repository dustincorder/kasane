package cord.kasane.music.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

sealed interface PlaybackStream
/** Existing progressive resolver remains responsible for URL expiration and byte offsets. */
object ProgressiveStream : PlaybackStream
class HlsStream(val url: String, val clientProfile: String = "IOS") : PlaybackStream {
    override fun toString() = "HlsStream(url=[redacted])"
}
class UnavailableStream(val reason: String) : PlaybackStream

/** One anonymous iOS attempt, never cookies or attestation. No profile switching during reads. */
class HlsPlayerResolver(
    private val client: OkHttpClient,
    private val endpoint: String = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
    private val authenticatedPlayer: suspend (String) -> JSONObject? = { null },
) {
    init {
        val uri = java.net.URI(endpoint)
        require((uri.scheme == "https" && uri.host == "www.youtube.com") ||
            (cord.kasane.music.BuildConfig.DEBUG && uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1")))
    }
    suspend fun resolve(videoId: String): PlaybackStream = withContext(Dispatchers.IO) {
        if (!Regex("[A-Za-z0-9_-]{11}").matches(videoId)) return@withContext UnavailableStream("Invalid track identifier.")
        val authenticated = try { authenticatedPlayer(videoId) } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (_: java.io.IOException) { null }
        if (authenticated != null) {
            val selected = select(authenticated, videoId)
            if (selected is HlsStream) return@withContext HlsStream(selected.url, "WEB_REMIX")
        }
        val body = JSONObject().put("videoId", videoId).put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "IOS").put("clientVersion", "21.26.4").put("deviceModel", "iPhone16,2")
            .put("osName", "iPhone").put("osVersion", "18.5.0.22F76").put("hl", "en").put("gl", "US")))
        val request = Request.Builder().url(endpoint)
            .header("User-Agent", "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_5 like Mac OS X;)")
            .header("X-Youtube-Client-Name", "5").header("X-Youtube-Client-Version", "21.26.4")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        await(client.newCall(request)).use { response ->
            if (!response.isSuccessful) return@withContext UnavailableStream("YouTube player request failed (${response.code}).")
            select(JSONObject(response.body?.source()?.let { source ->
                source.request(2L * 1024 * 1024 + 1)
                if (source.buffer.size > 2L * 1024 * 1024) return@withContext UnavailableStream("Player response is too large.")
                source.readUtf8()
            } ?: return@withContext UnavailableStream("Empty player response.")), videoId)
        }
    }
    private suspend fun await(call: okhttp3.Call): okhttp3.Response = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(java.io.IOException("Could not reach YouTube player (${e.javaClass.simpleName}).")))
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }
    companion object {
        fun select(root: JSONObject, videoId: String): PlaybackStream {
            val returnedId = root.optJSONObject("videoDetails")?.optString("videoId").orEmpty()
            if (returnedId.isNotEmpty() && returnedId != videoId) return UnavailableStream("YouTube returned a different track.")
            if (root.optJSONObject("playabilityStatus")?.optString("status") != "OK") return ProgressiveStream
            val data = root.optJSONObject("streamingData") ?: return ProgressiveStream
            val url = data.optString("hlsManifestUrl")
            val uri = runCatching { java.net.URI(url) }.getOrNull()
            // Credentials are never sent to this URL. Only public HTTPS YouTube CDN manifests are accepted.
            val host = uri?.host.orEmpty()
            if (uri?.scheme != "https" || uri.userInfo != null || !(host == "googlevideo.com" || host.endsWith(".googlevideo.com"))) return ProgressiveStream
            return HlsStream(url)
        }
    }
}
