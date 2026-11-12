package cord.kasane.music.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

sealed interface PlaybackStream
/** A concrete direct format from a compatible profile, never an ANDROID_VR fallback marker. */
class ProgressiveStream(val audio: ResolvedAudioStream, val clientProfile: String) : PlaybackStream {
    override fun toString() = "ProgressiveStream(client=$clientProfile, url=[redacted])"
}
class HlsStream(val url: String, val clientProfile: String = "IOS") : PlaybackStream {
    override fun toString() = "HlsStream(url=[redacted])"
}
class UnavailableStream(val reason: String, val code: String = "NO_COMPATIBLE_STREAM") : PlaybackStream
class TransportUnavailableException(message: String) : java.io.IOException(message)

/** One anonymous iOS attempt, never cookies or attestation. No profile switching during reads. */
class HlsPlayerResolver(
    private val client: OkHttpClient,
    private val endpoint: String = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
    private val diagnostic: (String) -> Unit = {},
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
        } catch (e: java.io.IOException) {
            diagnostic("videoId=$videoId client=WEB_REMIX playerHttp=${(e as? PlayerHttpException)?.code ?: -1} error=${e.javaClass.simpleName}")
            null
        }
        if (authenticated != null) {
            report(videoId, "WEB_REMIX", authenticated)
            val selected = select(authenticated, videoId, allowProgressive = true)
            if (selected is HlsStream) return@withContext HlsStream(selected.url, "WEB_REMIX")
            if (selected is ProgressiveStream) return@withContext selected
        }
        val body = JSONObject().put("videoId", videoId).put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "IOS").put("clientVersion", "21.26.4").put("deviceModel", "iPhone16,2")
            .put("osName", "iPhone").put("osVersion", "18.5.0.22F76").put("hl", "en").put("gl", "US")))
        val request = Request.Builder().url(endpoint)
            .header("User-Agent", "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_5 like Mac OS X;)")
            .header("X-Youtube-Client-Name", "5").header("X-Youtube-Client-Version", "21.26.4")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        await(client.newCall(request)).use { response ->
            if (!response.isSuccessful) return@withContext UnavailableStream("YouTube player request failed (${response.code}).", "PLAYER_HTTP_${response.code}")
            val root = JSONObject(response.body?.source()?.let { source ->
                source.request(2L * 1024 * 1024 + 1)
                if (source.buffer.size > 2L * 1024 * 1024) return@withContext UnavailableStream("Player response is too large.")
                source.readUtf8()
            } ?: return@withContext UnavailableStream("Empty player response."))
            report(videoId, "IOS", root)
            select(root, videoId)
        }
    }
    private fun report(videoId: String, profile: String, root: JSONObject) {
        val rawStatus = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        val status = rawStatus.takeIf { it.matches(Regex("[A-Z_]{1,40}")) } ?: "UNKNOWN"
        val data = root.optJSONObject("streamingData")
        diagnostic("videoId=$videoId client=$profile playerStatus=$status hls=${!data?.optString("hlsManifestUrl").isNullOrBlank()} sabr=${!data?.optString("serverAbrStreamingUrl").isNullOrBlank()}")
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
        fun select(root: JSONObject, videoId: String, allowProgressive: Boolean = false): PlaybackStream {
            val returnedId = root.optJSONObject("videoDetails")?.optString("videoId").orEmpty()
            if (returnedId.isNotEmpty() && returnedId != videoId) return UnavailableStream("YouTube returned a different track.")
            val status = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
            if (status != "OK") return UnavailableStream(StreamUnavailableException(status).message.orEmpty(), status)
            val data = root.optJSONObject("streamingData") ?: return UnavailableStream("YouTube returned no audio formats.")
            fun allowed(url: String): Boolean {
                val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
                val host = uri.host.orEmpty()
                return uri.scheme == "https" && uri.userInfo == null && (host == "googlevideo.com" || host.endsWith(".googlevideo.com"))
            }
            val url = data.optString("hlsManifestUrl")
            if (allowed(url)) return HlsStream(url)
            if (allowProgressive) {
                val audio = runCatching { StreamResponse.audioStream(root, System.currentTimeMillis()) }.getOrNull()
                if (audio != null && allowed(audio.url) && audio.expiresAtMs > System.currentTimeMillis() + 30000)
                    return ProgressiveStream(audio, "WEB_REMIX")
            }
            return UnavailableStream(if (data.optString("serverAbrStreamingUrl").isNotBlank())
                "YouTube returned SABR audio, which Kasane does not support yet. No compatible full-length stream was supplied."
                else "YouTube did not provide a supported full-length stream for this track.",
                if (data.optString("serverAbrStreamingUrl").isNotBlank()) "SABR_REQUIRED" else "NO_COMPATIBLE_STREAM")
        }
    }
}

/** Exception text from network/JSON libraries can contain signed URLs; expose only fixed messages. */
fun playbackFailure(error: Exception): UnavailableStream = when (error) {
    is PlayerHttpException -> UnavailableStream("YouTube player request failed (${error.code}).", "PLAYER_HTTP_${error.code}")
    is StreamUnavailableException -> UnavailableStream(error.message.orEmpty(), "PLAYABILITY_ERROR")
    is org.json.JSONException -> UnavailableStream("YouTube returned an unreadable player response.", "INVALID_PLAYER_RESPONSE")
    is java.io.IOException -> UnavailableStream("Could not reach the audio server. Check your connection and retry.", "NETWORK_ERROR")
    else -> UnavailableStream("Could not prepare this audio source.", "SOURCE_ERROR")
}

object PlaybackRetryPolicy {
    fun canRetry(httpStatus: Int?, errorCount: Int): Boolean = errorCount <= 2 && httpStatus !in setOf(401, 403, 404, 410)
}
