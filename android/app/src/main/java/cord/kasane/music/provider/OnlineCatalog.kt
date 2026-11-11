package cord.kasane.music.provider

import cord.kasane.music.auth.*
import cord.kasane.music.model.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OnlineCatalog(private val client: OkHttpClient, private val session: () -> AccountSession? = { null }) : AccountLibraryProvider {
    var onSessionExpired: (() -> Unit)? = null
    private val origin = "https://music.youtube.com"
    suspend fun request(endpoint: String, payload: JSONObject, account: AccountSession? = session()): JSONObject {
        require(endpoint.matches(Regex("[a-zA-Z_/]+")))
        val context = JSONObject().put("client", JSONObject().put("clientName", "WEB_REMIX")
            .put("clientVersion", "1.20240101.01.00").put("hl", "en").put("gl", "US"))
        account?.delegatedSessionId?.let { context.put("user", JSONObject().put("onBehalfOfUser", it)) }
        payload.put("context", context)
        val url = "$origin/youtubei/v1/$endpoint".toHttpUrl().newBuilder().addQueryParameter("prettyPrint", "false")
        if (endpoint == "browse" && payload.has("continuation")) {
            val token = payload.getString("continuation")
            url.addQueryParameter("ctoken", token).addQueryParameter("continuation", token).addQueryParameter("type", "next")
            payload.remove("continuation")
        }
        val builder = Request.Builder().url(url.build())
            .header("Origin", origin).header("Referer", "$origin/")
            .header("X-Youtube-Client-Name", "67").header("X-Youtube-Client-Version", "1.20240101.01.00")
            .header("User-Agent", "Mozilla/5.0")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (account != null) {
            val cookies = account.cookie.split(';').mapNotNull {
                val pair = it.trim().split('=', limit = 2)
                if (pair.size == 2) pair[0] to pair[1] else null
            }.toMap()
            val secret = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: throw SessionExpiredException()
            val time = System.currentTimeMillis() / 1000
            val hash = MessageDigest.getInstance("SHA-1").digest("$time $secret $origin".toByteArray())
                .joinToString("") { "%02x".format(it) }
            builder.header("Cookie", account.cookie).header("Authorization", "SAPISIDHASH ${time}_$hash")
                .header("X-Goog-AuthUser", account.authUser.toString())
        }
        val call = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build().newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("YouTube Music is unavailable. Check your connection."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (it.code == 401) throw SessionExpiredException()
                            if (it.code == 403) throw IOException("YouTube Music refused this request. Check account permissions or regional availability.")
                            if (!it.isSuccessful) throw IOException("YouTube Music request failed (${it.code}). Try again.")
                            val source = it.body?.source() ?: throw IOException("YouTube Music returned no response.")
                            source.request(8L * 1024 * 1024 + 1)
                            if (source.buffer.size > 8L * 1024 * 1024) throw IOException("Response is too large. Try a smaller list.")
                            val result = JSONObject(source.readUtf8())
                            if (result.optJSONObject("error")?.optInt("code") == 401) throw SessionExpiredException()
                            if (result.has("error")) throw IOException("YouTube Music could not complete this request.")
                            if (account != null && CatalogParser.objects(result, "mainAppWebResponseContext").any {
                                it.optBoolean("loggedOut", false) }) throw SessionExpiredException()
                            if (continuation.isActive) continuation.resume(result)
                        }
                    } catch (e: Exception) {
                        if (e is SessionExpiredException && account != null) onSessionExpired?.invoke()
                        val safe = if (e is IOException) e else IOException("YouTube Music returned an unreadable response.")
                        if (continuation.isActive) continuation.resumeWithException(safe)
                    }
                }
            })
        }
    }
    suspend fun search(query: String, kind: CatalogKind, continuation: String? = null): CatalogPage {
        if (query.isBlank()) return CatalogPage("", "", null, emptyList())
        val payload = JSONObject().put("query", query.trim()).put("params", kind.filter)
        continuation?.let { payload.put("continuation", it) }
        return CatalogParser.search(request("search", payload), kind)
    }
    suspend fun browse(id: String, continuation: String? = null): CatalogPage {
        require(id.isNotBlank())
        val body = if (continuation == null) JSONObject().put("browseId", id) else JSONObject().put("continuation", continuation)
        return CatalogParser.page(request("browse", body))
    }
    suspend fun home(): CatalogPage = browse("FEmusic_home")
    suspend fun radio(videoId: String): List<Track> {
        val response = request("next", JSONObject().put("videoId", videoId).put("playlistId", "RDAMVM$videoId"))
        // Watch-next uses playlistPanelVideoRenderer, not browse rows.
        return CatalogParser.objects(response, "playlistPanelVideoRenderer").mapNotNull {
            val id = it.optString("videoId")
            if (id.isBlank()) null else Track(id, CatalogParser.text(it.optJSONObject("title")),
                CatalogParser.text(it.optJSONObject("longBylineText")),
                thumbnailUri = ThumbnailParser.bestUrl(it.optJSONObject("thumbnail")))
        }.distinctBy { it.id }
    }
    override suspend fun collection(collection: AccountCollection, continuation: String?): CatalogPage {
        if (session() == null) throw SessionExpiredException()
        return browse(collection.browseId, continuation)
    }
    override suspend fun setLiked(videoId: String, liked: Boolean) {
        require(videoId.isNotBlank())
        val account = session() ?: throw SessionExpiredException()
        val response = request(if (liked) "like/like" else "like/removelike",
            JSONObject().put("target", JSONObject().put("videoId", videoId)), account)
        val status = response.optString("status")
        if (status.isNotBlank() && status != "STATUS_SUCCEEDED") throw IOException("YouTube Music did not accept the library change.")
    }
    suspend fun identity(account: AccountSession): AccountIdentity {
        val response = request("account/account_menu", JSONObject(), account)
        val header = CatalogParser.objects(response, "activeAccountHeaderRenderer").firstOrNull()
            ?: throw SessionExpiredException()
        val name = CatalogParser.text(header.optJSONObject("accountName"))
        if (name.isBlank()) throw SessionExpiredException()
        return AccountIdentity(name, header.optJSONObject("accountPhoto")?.optJSONArray("thumbnails")
            ?.optJSONObject(0)?.optString("url"), account.channelId)
    }
}
