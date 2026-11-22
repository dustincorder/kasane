package cord.kasane.music.provider.verification

import cord.kasane.music.provider.PlaybackStream
import cord.kasane.music.provider.ProgressiveStream
import cord.kasane.music.provider.ResolvedAudioStream
import cord.kasane.music.provider.UnavailableStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** A browser engine that has loaded `kasane-verifier.js`. */
interface VerificationRuntime {
    suspend fun call(method: String, args: JSONArray): Any?

    /** User-Agent of the engine that answers the BotGuard challenge; sent with its Create/GenerateIT requests. */
    val userAgent: String
}

class VerificationException(message: String) : java.io.IOException("YouTube playback verification failed: $message")

/**
 * Anonymous WEB_REMIX playback with a legitimate, video-bound BotGuard proof and
 * the official player-script URL transform. Mirrors
 * `crates/vibe-providers/src/verification.rs` on Linux.
 */
class VerifiedPlayerResolver(
    private val client: OkHttpClient,
    private val runtime: VerificationRuntime,
    private val youtubeBase: String = "https://www.youtube.com",
    private val musicBase: String = "https://music.youtube.com",
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var protocolChecked = false
    private var integrityExpiresAt = 0L
    private var playerId: String? = null
    private var signatureTimestamp = 0L
    private var playerLoadedAt = 0L

    suspend fun resolve(videoId: String): PlaybackStream = withContext(Dispatchers.IO) {
        try {
            lock.withLock { resolveLocked(videoId) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: UnplayableException) {
            UnavailableStream(e.message.orEmpty(), e.status)
        } catch (e: java.io.IOException) {
            UnavailableStream(e.message ?: "YouTube playback verification failed.", "VERIFICATION_FAILED")
        } catch (e: org.json.JSONException) {
            UnavailableStream("YouTube playback verification failed: invalid response", "VERIFICATION_FAILED")
        }
    }

    private suspend fun resolveLocked(videoId: String): PlaybackStream {
        if (!protocolChecked) {
            if ((runtime.call("protocol", JSONArray()) as? Number)?.toInt() != PROTOCOL) throw VerificationException("verification runtime version mismatch")
            protocolChecked = true
        }
        val sts = ensurePlayer()
        ensureIntegrity()
        val root = post("$musicBase/youtubei/v1/player?prettyPrint=false", JSONObject()
            .put("videoId", videoId)
            .put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB_REMIX")
                .put("clientVersion", WEB_REMIX_VERSION).put("hl", "en").put("gl", "US")))
            .put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject().put("signatureTimestamp", sts))),
            mapOf("X-YouTube-Client-Name" to "67", "X-YouTube-Client-Version" to WEB_REMIX_VERSION, "Origin" to "https://music.youtube.com"),
            "player") as JSONObject
        val format = selectAudioFormat(root, videoId)
        val n = format.url.queryParameter("n")
        val prepared = if (n == null && format.signature == null) JSONObject() else
            runtime.call("decipher", JSONArray().put(n ?: JSONObject.NULL).put(format.signatureParam).put(format.signature ?: JSONObject.NULL)) as? JSONObject
                ?: throw VerificationException("player script returned no result")
        val token = (runtime.call("mint", JSONArray().put(videoId)) as? String)?.takeIf { it.isNotEmpty() }
            ?: throw VerificationException("BotGuard returned no proof")
        val url = applyVerification(format, prepared, token)
        val expires = url.queryParameter("expire")?.toLongOrNull()?.times(1000) ?: (now() + 5 * 60 * 60 * 1000)
        return ProgressiveStream(ResolvedAudioStream(url.toString(), AUDIO_ITAG, format.mimeType, expires, format.contentLength), PROFILE)
    }

    private suspend fun ensurePlayer(): Long {
        if (playerId != null && now() - playerLoadedAt < PLAYER_REFRESH_MS) return signatureTimestamp
        val id = playerId(get("$youtubeBase/iframe_api", 1L shl 20)) ?: throw VerificationException("player version not found")
        if (id == playerId) { playerLoadedAt = now(); return signatureTimestamp }
        val source = get("$youtubeBase/s/player/$id/player_es6.vflset/en_US/base.js", MAX_PLAYER_JS_BYTES)
        val sts = signatureTimestamp(source) ?: throw VerificationException("player script is not supported")
        runtime.call("loadPlayer", JSONArray().put(source))
        playerId = id; signatureTimestamp = sts; playerLoadedAt = now()
        return sts
    }

    private suspend fun ensureIntegrity() {
        if (now() < integrityExpiresAt) return
        integrityExpiresAt = 0L
        val challenge = waa("Create", JSONArray().put(REQUEST_KEY))
        val snapshot = (runtime.call("attest", JSONArray().put(challenge)) as? String)?.takeIf { it.isNotEmpty() }
            ?: throw VerificationException("BotGuard produced no response")
        val issued = waa("GenerateIT", JSONArray().put(REQUEST_KEY).put(snapshot)) as? JSONArray
            ?: throw VerificationException("integrity token was not issued")
        val token = issued.optString(0).takeIf { it.isNotEmpty() && !issued.isNull(0) }
            ?: throw VerificationException("integrity token was not issued")
        val ttlSeconds = issued.optLong(1)
        if (ttlSeconds * 1000 <= INTEGRITY_MARGIN_MS) throw VerificationException("integrity token expires too soon")
        runtime.call("integrity", JSONArray().put(token))
        integrityExpiresAt = now() + ttlSeconds * 1000 - INTEGRITY_MARGIN_MS
    }

    private fun waa(method: String, body: JSONArray): Any = post("$youtubeBase/api/jnn/v1/$method", body,
        mapOf("User-Agent" to runtime.userAgent, "X-Goog-Api-Key" to WAA_API_KEY, "X-User-Agent" to "grpc-web-javascript/0.1"), "BotGuard $method",
        "application/json+protobuf")

    private fun post(url: String, body: Any, headers: Map<String, String>, what: String, type: String = "application/json"): Any {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toString().toRequestBody(type.toMediaType())).build()
        val text = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw VerificationException("$what returned HTTP ${response.code}")
                response.body?.string() ?: throw VerificationException("empty $what response")
            }
        } catch (e: VerificationException) { throw e
        } catch (e: java.io.IOException) { throw VerificationException("could not reach YouTube ($what)") }
        return org.json.JSONTokener(text).nextValue()
    }

    private fun get(url: String, limit: Long): String = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw VerificationException("YouTube player download returned HTTP ${response.code}")
            val source = response.body?.source() ?: throw VerificationException("empty YouTube player")
            source.request(limit + 1)
            if (source.buffer.size > limit) throw VerificationException("YouTube player is too large")
            source.readUtf8()
        }
    } catch (e: VerificationException) { throw e
    } catch (e: java.io.IOException) { throw VerificationException("could not download the YouTube player") }

    class UnplayableException(val status: String, reason: String) : Exception("Unplayable ($status): $reason")

    internal class AudioFormat(val url: okhttp3.HttpUrl, val signature: String?, val signatureParam: String, val mimeType: String, val contentLength: Long)

    companion object {
        const val PROFILE = "WEB_REMIX_VERIFIED"
        const val PROTOCOL = 1
        const val WEB_REMIX_VERSION = "1.20250310.01.00"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val WAA_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val AUDIO_ITAG = 140
        private const val PLAYER_REFRESH_MS = 6L * 60 * 60 * 1000
        private const val INTEGRITY_MARGIN_MS = 10L * 60 * 1000
        private const val MAX_PLAYER_JS_BYTES = 8L * 1024 * 1024

        internal fun playerId(iframeApi: String): String? {
            val match = Regex("""player\\?/([0-9a-fA-F]{8})\\?/""").find(iframeApi) ?: return null
            return match.groupValues[1]
        }

        internal fun signatureTimestamp(source: String): Long? =
            Regex("""signatureTimestamp["']?\s*[:=]\s*(\d+)""").find(source)?.groupValues?.get(1)?.toLongOrNull()

        internal fun selectAudioFormat(root: JSONObject, videoId: String): AudioFormat {
            val returned = root.optJSONObject("videoDetails")?.optString("videoId").orEmpty()
            if (returned.isNotEmpty() && returned != videoId) throw VerificationException("player returned a different video")
            val status = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
            if (status != "OK") throw UnplayableException(status.ifEmpty { "UNKNOWN" },
                root.optJSONObject("playabilityStatus")?.optString("reason").orEmpty().ifEmpty { "no reason given" })
            val formats = root.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: JSONArray()
            val format = (0 until formats.length()).map { formats.getJSONObject(it) }.firstOrNull { it.optInt("itag") == AUDIO_ITAG }
                ?: throw VerificationException("no AAC audio format in the player response")
            val direct = format.optString("url").takeIf { it.isNotEmpty() }
            var signature: String? = null
            var signatureParam = "sig"
            val raw = direct ?: run {
                val cipher = (format.optString("signatureCipher").ifEmpty { format.optString("cipher") }).takeIf { it.isNotEmpty() }
                    ?: throw VerificationException("audio format has no URL")
                val fields = "https://cipher.invalid/?$cipher".toHttpUrl()
                signature = fields.queryParameter("s") ?: throw VerificationException("invalid signature cipher")
                signatureParam = fields.queryParameter("sp") ?: "signature"
                fields.queryParameter("url") ?: throw VerificationException("invalid signature cipher")
            }
            val url = raw.toHttpUrlOrNull() ?: throw VerificationException("invalid audio URL")
            if (!url.isHttps || !url.host.endsWith(".googlevideo.com")) throw VerificationException("unexpected audio host")
            return AudioFormat(url, signature, signatureParam,
                format.optString("mimeType").substringBefore(';').trim().ifEmpty { "audio/mp4" },
                format.optString("contentLength").toLongOrNull() ?: -1L)
        }

        internal fun applyVerification(format: AudioFormat, prepared: JSONObject, token: String): okhttp3.HttpUrl {
            val builder = format.url.newBuilder()
            if (format.signature != null) {
                val sig = prepared.optString("sig").takeIf { it.isNotEmpty() && !prepared.isNull("sig") }
                    ?: throw VerificationException("player script did not produce a signature")
                builder.setQueryParameter(format.signatureParam, sig)
            }
            if (format.url.queryParameter("n") != null) {
                val n = prepared.optString("n").takeIf { it.isNotEmpty() && !prepared.isNull("n") }
                    ?: throw VerificationException("player script did not transform the URL")
                builder.setQueryParameter("n", n)
            }
            return builder.setQueryParameter("pot", token).build()
        }
    }
}
