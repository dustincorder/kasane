package cord.kasane.music.provider

import cord.kasane.music.auth.AccountSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Ephemeral request values. Neither headers nor bodies are diagnostic strings. */
class CatalogHttpRequest internal constructor(val baseUrl: String, val endpoint: String,
    query: Map<String, String>, headers: Map<String, String>, val body: String) {
    val query = query.toMap()
    val headers = headers.toMap()
    override fun toString() = "CatalogHttpRequest(endpoint=$endpoint, credentials=[redacted])"
}
class CatalogHttpResponse(val status: Int, val body: String) {
    override fun toString() = "CatalogHttpResponse(status=$status, body=[redacted])"
}
fun interface CatalogHttpTransport {
    /** Implementations must close responses, cancel requests and bound decoded body bytes. */
    suspend fun execute(request: CatalogHttpRequest): CatalogHttpResponse
}
enum class CatalogFailureReason(val safeMessage: String) {
    SESSION_EXPIRED("Your YouTube Music session expired. Sign in again."),
    PLAYER_DENIED("YouTube refused this player request."),
    ACCESS_DENIED("YouTube Music refused this request. Check account permissions or regional availability."),
    REDIRECT("YouTube Music redirected the account request. Sign in again and retry."),
    HTTP_ERROR("YouTube Music request failed. Try again."),
    NETWORK("YouTube Music is unavailable. Check your connection."),
    EMPTY_RESPONSE("YouTube Music returned no response."),
    RESPONSE_TOO_LARGE("Response is too large. Try a smaller list."),
    INVALID_RESPONSE("YouTube Music returned an unreadable response."),
    API_ERROR("YouTube Music could not complete this request."),
    INVALID_PLAYLIST_OPTIONS("YouTube Music did not return a supported playlist picker."),
    TOO_MANY_PLAYLISTS("YouTube Music returned too many playlist choices to display safely."),
    PLAYLIST_REFUSED("YouTube Music refused the playlist change."),
    PLAYLIST_UNCONFIRMED("YouTube Music did not confirm the addition. Refresh the playlist before retrying."),
    RATING_UNCONFIRMED("YouTube Music did not confirm the rating. Refresh before retrying."),
    RATING_REFUSED("YouTube Music did not accept the library change."),
    INVALID_LIBRARY("YouTube Music returned an unsupported library response. Refresh to try again."),
}
class CatalogFailure(val reason: CatalogFailureReason, val status: Int? = null) : Exception(reason.safeMessage)

/** Shared request/context/auth/error policy. Transport and hashing are platform ports. */
class InnerTubeClient(
    private val transport: CatalogHttpTransport,
    private val nowSeconds: () -> Long,
    private val sha1Hex: (String) -> String,
    private val baseUrl: String = "https://music.youtube.com/",
    allowLocalTestOrigin: Boolean = false,
) {
    companion object { const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024 }
    init {
        val loopback = Regex("http://(?:localhost|127\\.0\\.0\\.1|\\[::1\\]):([0-9]{1,5})/").matchEntire(baseUrl)
        val testPort = loopback?.groupValues?.get(1)?.toIntOrNull()
        require(baseUrl == "https://music.youtube.com/" ||
            (allowLocalTestOrigin && testPort != null && testPort in 1..65535)) {
            "InnerTube credentials may only target YouTube Music."
        }
    }
    suspend fun request(endpoint: String, payload: JsonObject = JsonObject(emptyMap()),
        account: AccountSession? = null, clientVersion: String = "1.20240101.01.00"): JsonObject = withContext(Dispatchers.Default) {
        require(endpoint.matches(Regex("[a-zA-Z_/]+")))
        require(clientVersion.matches(Regex("[0-9.]{3,40}")))
        val origin = "https://music.youtube.com"
        val context = buildJsonObject {
            put("client", buildJsonObject { put("clientName", "WEB_REMIX"); put("clientVersion", clientVersion); put("hl", "en"); put("gl", "US") })
            account?.delegatedSessionId?.takeIf { it.isNotBlank() }?.let { delegated ->
                put("user", buildJsonObject { put("onBehalfOfUser", delegated) })
            }
        }
        val body = payload.toMutableMap().apply { put("context", context) }
        val query = mutableMapOf("prettyPrint" to "false")
        if (endpoint == "browse" && body.containsKey("continuation")) {
            val token = (body.remove("continuation") as? JsonPrimitive)?.contentOrNull
                ?: throw CatalogFailure(CatalogFailureReason.INVALID_RESPONSE)
            query.putAll(mapOf("ctoken" to token, "continuation" to token, "type" to "next"))
        }
        val headers = mutableMapOf("Origin" to origin, "Referer" to "$origin/",
            "X-Youtube-Client-Name" to "67", "X-Youtube-Client-Version" to clientVersion, "User-Agent" to "Mozilla/5.0")
        if (account != null) {
            val cookies = account.cookie.split(';').mapNotNull { value ->
                val pair = value.trim().split('=', limit = 2)
                if (pair.size == 2) pair[0] to pair[1] else null
            }.toMap()
            val secret = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: cookies["__Secure-1PAPISID"]
                ?: throw CatalogFailure(CatalogFailureReason.SESSION_EXPIRED)
            val time = nowSeconds()
            val hash = sha1Hex("$time $secret $origin")
            check(hash.matches(Regex("[0-9a-f]{40}"))) { "Invalid platform digest." }
            headers["Cookie"] = account.cookie
            headers["Authorization"] = "SAPISIDHASH ${time}_$hash"
            headers["X-Goog-AuthUser"] = account.authUser.toString()
            account.visitorData?.let { headers["X-Goog-Visitor-Id"] = it }
            account.channelId?.let { headers["X-Goog-PageId"] = it }
        }
        if (headers.values.any { value -> value.any { it.code !in 32..126 } })
            throw CatalogFailure(CatalogFailureReason.INVALID_RESPONSE)
        val response = try { transport.execute(CatalogHttpRequest(baseUrl, endpoint, query, headers, JsonObject(body).toString())) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: CatalogFailure) { throw failure }
            catch (_: Exception) { throw CatalogFailure(CatalogFailureReason.NETWORK) }
        when {
            response.status == 401 && account != null -> throw CatalogFailure(CatalogFailureReason.SESSION_EXPIRED, 401)
            response.status == 403 && endpoint == "player" -> throw CatalogFailure(CatalogFailureReason.PLAYER_DENIED, 403)
            response.status == 403 -> throw CatalogFailure(CatalogFailureReason.ACCESS_DENIED, 403)
            response.status in 300..399 -> throw CatalogFailure(CatalogFailureReason.REDIRECT, response.status)
            response.status !in 200..299 -> throw CatalogFailure(CatalogFailureReason.HTTP_ERROR, response.status)
        }
        if (response.body.isEmpty()) throw CatalogFailure(CatalogFailureReason.EMPTY_RESPONSE)
        if (response.body.length > MAX_RESPONSE_BYTES) throw CatalogFailure(CatalogFailureReason.RESPONSE_TOO_LARGE)
        val root = try { Json.parseToJsonElement(response.body) as? JsonObject ?: throw CatalogFailure(CatalogFailureReason.INVALID_RESPONSE) }
            catch (failure: CatalogFailure) { throw failure }
            catch (_: Exception) { throw CatalogFailure(CatalogFailureReason.INVALID_RESPONSE) }
        if (root.optJsonObject("error")?.optLong("code") == 401L) throw CatalogFailure(CatalogFailureReason.SESSION_EXPIRED)
        if (root.containsKey("error")) throw CatalogFailure(CatalogFailureReason.API_ERROR)
        if (account != null && CatalogJsonParser.objects(root, "mainAppWebResponseContext").any { it.optBoolean("loggedOut") })
            throw CatalogFailure(CatalogFailureReason.SESSION_EXPIRED)
        root
    }
}
