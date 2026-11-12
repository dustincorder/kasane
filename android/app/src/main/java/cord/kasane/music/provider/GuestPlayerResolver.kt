package cord.kasane.music.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** Anonymous context issued by YouTube itself; never persisted or logged. No account cookies. */
class GuestPlayerResolver(
    private val client: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val diagnostic: (String) -> Unit = {}
) {
    private val mutex = Mutex()
    private var visitorData: String? = null
    private val streams = linkedMapOf<String, ResolvedAudioStream>()
    companion object {
        const val CLIENT_NAME = "ANDROID_VR"
        const val CLIENT_VERSION = "1.65.10"
        const val USER_AGENT = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }

    suspend fun resolve(videoId: String, forceRefresh: Boolean = false, requiredItag: Int? = null): ResolvedAudioStream =
        withContext(Dispatchers.IO) { mutex.withLock {
            if (!VIDEO_ID.matches(videoId)) throw StreamUnavailableException("INVALID_VIDEO_ID")
            if (!forceRefresh) streams[videoId]?.takeIf {
                it.expiresAtMs > now() + 30_000 && (requiredItag == null || it.itag == requiredItag)
            }?.let { return@withLock it }
            streams.remove(videoId)
            // At most two player calls: anonymous context handshake OR a stale URL, then one retry.
            repeat(2) { attempt ->
                val context = JSONObject().put("clientName", CLIENT_NAME).put("clientVersion", CLIENT_VERSION)
                    .put("deviceMake", "Oculus").put("deviceModel", "Quest 3").put("osName", "Android")
                    .put("osVersion", "12L").put("androidSdkVersion", 32).put("hl", "en").put("gl", "US")
                    .put("userAgent", USER_AGENT)
                visitorData?.let { context.put("visitorData", it) }
                val body = JSONObject().put("context", JSONObject().put("client", context)).put("videoId", videoId)
                    .put("playbackContext", JSONObject().put("contentPlaybackContext",
                        JSONObject().put("html5Preference", "HTML5_PREF_WANTS")))
                val request = Request.Builder().url("https://music.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .header("User-Agent", USER_AGENT).header("X-Youtube-Client-Name", "28")
                    .header("X-Youtube-Client-Version", CLIENT_VERSION)
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                visitorData?.let { request.header("X-Goog-Visitor-Id", it) }
                val root = try {
                    client.newCall(request.build()).execute().use { response ->
                        diagnostic("videoId=$videoId client=$CLIENT_NAME/$CLIENT_VERSION attempt=${attempt + 1} playerHttp=${response.code}")
                        if (!response.isSuccessful) throw PlayerHttpException(response.code)
                        JSONObject(response.body?.string() ?: throw IOException("YouTube returned an empty player response."))
                    }
                } catch (e: IOException) {
                    if (e is PlayerHttpException) throw e
                    throw IOException("Could not request audio from YouTube. Check your connection.")
                } catch (e: org.json.JSONException) {
                    throw IOException("YouTube returned an unreadable player response.")
                }
                val previousVisitor = visitorData
                val issuedVisitor = root.optJSONObject("responseContext")?.optString("visitorData")
                    ?.takeIf { it.isNotBlank() && it != "null" && it.all { c -> c.code in 33..126 } }
                if (issuedVisitor != null) visitorData = issuedVisitor
                val status = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                diagnostic("videoId=$videoId client=$CLIENT_NAME/$CLIENT_VERSION status=$status anonymousContext=${previousVisitor != null}")
                if (attempt == 0 && StreamResponse.isAnonymousSessionChallenge(root) &&
                    issuedVisitor != null && issuedVisitor != previousVisitor) return@repeat
                val responseId = root.optJSONObject("videoDetails")?.optString("videoId").orEmpty()
                if (responseId.isNotBlank() && responseId != videoId) throw StreamUnavailableException("ID_MISMATCH")
                val stream = StreamResponse.audioStream(root, now(), requiredItag)
                if (stream.expiresAtMs <= now() + 30_000) {
                    if (attempt == 0) return@repeat
                    throw StreamUnavailableException("EXPIRED_STREAM")
                }
                if (streams.size >= 32) streams.remove(streams.keys.first())
                streams[videoId] = stream
                diagnostic("videoId=$videoId client=$CLIENT_NAME/$CLIENT_VERSION stream=available itag=${stream.itag}")
                return@withLock stream
            }
            throw StreamUnavailableException("NO_DIRECT_STREAM")
        } }

    /** One CDN refresh only, preserving the format when reopening part of a resource. */
    suspend fun <T> open(
        videoId: String,
        forceRefresh: Boolean = false,
        requiredItag: Int? = null,
        onRefresh: (() -> Unit)? = null,
        operation: (ResolvedAudioStream) -> T,
    ): T {
        if (forceRefresh) onRefresh?.invoke()
        val first = resolve(videoId, forceRefresh = forceRefresh, requiredItag = requiredItag)
        return try { operation(first) } catch (e: AudioHttpException) {
            if (e.code != 403 && e.code != 410) throw e
            onRefresh?.invoke()
            val refreshed = resolve(videoId, forceRefresh = true, requiredItag = first.itag)
            operation(refreshed)
        }
    }
}

class AudioHttpException(val code: Int) : IOException("Audio server refused the stream ($code).")

/** No data-class toString: a signed CDN URL must never leak into a log. */
class ResolvedAudioStream(val url: String, val itag: Int, val mimeType: String, val expiresAtMs: Long, val contentLength: Long = -1) {
    val headers: Map<String, String> get() = mapOf("User-Agent" to GuestPlayerResolver.USER_AGENT)
    override fun toString() = "ResolvedAudioStream(itag=$itag, url=[redacted])"
}

class PlayerHttpException(val code: Int) : IOException("YouTube player request failed ($code).")
