package cord.kasane.music.provider

import cord.kasane.music.auth.AccountChannel
import cord.kasane.music.auth.AccountIdentity
import cord.kasane.music.auth.AccountSession
import cord.kasane.music.auth.SessionExpiredException
import cord.kasane.music.auth.WebSessionParser
import cord.kasane.music.BuildConfig
import cord.kasane.music.model.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OnlineCatalog(
    private val client: OkHttpClient,
    private val session: () -> AccountSession? = { null },
    private val apiBaseUrl: okhttp3.HttpUrl = "https://music.youtube.com/".toHttpUrl(),
) : AccountLibraryProvider, cord.kasane.music.data.CatalogRemote {
    private val mutableTrackLinks = kotlinx.coroutines.flow.MutableStateFlow<Map<String, TrackLinks>>(emptyMap())
    val trackLinks: kotlinx.coroutines.flow.StateFlow<Map<String, TrackLinks>> = mutableTrackLinks
    private val origin = "https://music.youtube.com"

    init {
        val isProductionOrigin = apiBaseUrl.isHttps && apiBaseUrl.host == "music.youtube.com"
        val isLocalMock = BuildConfig.DEBUG && !apiBaseUrl.isHttps && apiBaseUrl.host in setOf("localhost", "127.0.0.1", "::1")
        require(isProductionOrigin || isLocalMock) { "InnerTube credentials may only target YouTube Music." }
    }
    suspend fun request(endpoint: String, payload: JSONObject, account: AccountSession? = null, clientVersion: String = "1.20240101.01.00"): JSONObject {
        require(endpoint.matches(Regex("[a-zA-Z_/]+")))
        val context = JSONObject().put("client", JSONObject().put("clientName", "WEB_REMIX")
            .put("clientVersion", clientVersion).put("hl", "en").put("gl", "US"))
        account?.delegatedSessionId?.takeIf(String::isNotBlank)?.let {
            context.put("user", JSONObject().put("onBehalfOfUser", it))
        }
        payload.put("context", context)
        val url = apiBaseUrl.newBuilder().addPathSegments("youtubei/v1/$endpoint").addQueryParameter("prettyPrint", "false")
        if (endpoint == "browse" && payload.has("continuation")) {
            val token = payload.getString("continuation")
            url.addQueryParameter("ctoken", token).addQueryParameter("continuation", token).addQueryParameter("type", "next")
            payload.remove("continuation")
        }
        val builder = Request.Builder().url(url.build())
            .header("Origin", origin).header("Referer", "$origin/")
            .header("X-Youtube-Client-Name", "67").header("X-Youtube-Client-Version", clientVersion)
            .header("User-Agent", "Mozilla/5.0")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (account != null) {
            val cookies = account.cookie.split(';').mapNotNull { raw ->
                val pair = raw.trim().split('=', limit = 2)
                if (pair.size == 2) pair[0] to pair[1] else null
            }.toMap()
            val secret = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: cookies["__Secure-1PAPISID"]
                ?: throw SessionExpiredException()
            val time = System.currentTimeMillis() / 1000
            val digest = java.security.MessageDigest.getInstance("SHA-1")
                .digest("$time $secret $origin".toByteArray())
                .joinToString("") { "%02x".format(it) }
            builder.header("Cookie", account.cookie)
                .header("Authorization", "SAPISIDHASH ${time}_$digest")
                .header("X-Goog-AuthUser", account.authUser.toString())
            account.visitorData?.let { builder.header("X-Goog-Visitor-Id", it) }
            account.channelId?.let { builder.header("X-Goog-PageId", it) }
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
                            if (it.code == 401 && account != null) throw SessionExpiredException()
                            if (it.code == 403 && endpoint == "player") throw PlayerHttpException(403)
                            if (it.code == 403) throw IOException("YouTube Music refused this request. Check account permissions or regional availability.")
                            if (it.code in 300..399) throw IOException("YouTube Music redirected the account request. Sign in again and retry.")
                            if (!it.isSuccessful) throw IOException("YouTube Music request failed (${it.code}). Try again.")
                            val source = it.body?.source() ?: throw IOException("YouTube Music returned no response.")
                            source.request(8L * 1024 * 1024 + 1)
                            if (source.buffer.size > 8L * 1024 * 1024) throw IOException("Response is too large. Try a smaller list.")
                            val result = JSONObject(source.readUtf8())
                            if (result.optJSONObject("error")?.optInt("code") == 401) throw SessionExpiredException()
                            if (result.has("error")) throw IOException("YouTube Music could not complete this request.")
                            if (account != null && CatalogParser.objects(result, "mainAppWebResponseContext")
                                    .any { it.optBoolean("loggedOut", false) }) throw SessionExpiredException()
                            if (continuation.isActive) {
                                val links = TrackLinksParser.parse(result)
                                if (links.isNotEmpty()) synchronized(mutableTrackLinks) {
                                    mutableTrackLinks.value = (mutableTrackLinks.value + links).entries.toList().takeLast(1000).associate { entry -> entry.key to entry.value }
                                }
                                continuation.resume(result)
                            }
                        }
                    } catch (e: Exception) {
                        val safe = if (e is IOException) e else IOException("YouTube Music returned an unreadable response.")
                        if (continuation.isActive) continuation.resumeWithException(safe)
                    }
                }
            })
        }
    }
    override suspend fun search(query: String, kind: CatalogKind, continuation: String?): CatalogPage {
        if (query.isBlank()) return CatalogPage("", "", null, emptyList())
        val payload = JSONObject().put("query", query.trim()).put("params", kind.filter)
        continuation?.let { payload.put("continuation", it) }
        val response = request("search", payload)
        return withContext(Dispatchers.Default) { CatalogParser.search(response, kind) }
    }
    override suspend fun browse(id: String, continuation: String?): CatalogPage {
        require(id.isNotBlank())
        val body = if (continuation == null) JSONObject().put("browseId", id) else JSONObject().put("continuation", continuation)
        val response = request("browse", body)
        return withContext(Dispatchers.Default) { CatalogParser.page(response) }
    }
    override suspend fun home(account: AccountSession?): CatalogPage {
        val response = request("browse", JSONObject().put("browseId", "FEmusic_home"), account)
        return withContext(Dispatchers.Default) { CatalogParser.page(response) }
    }
    data class RadioPage(val tracks: List<Track>, val continuation: String?)
    suspend fun radio(videoId: String): List<Track> = radioPage(videoId).tracks
    suspend fun radioPage(videoId: String, continuation: String? = null): RadioPage {
        val body = if (continuation != null) JSONObject().put("continuation", continuation)
            else JSONObject().put("videoId", videoId).put("playlistId", "RDAMVM$videoId")
        val response = request("next", body, session())
        return withContext(Dispatchers.Default) {
            val tracks = CatalogParser.objects(response, "playlistPanelVideoRenderer").mapNotNull {
                val id = it.optString("videoId")
                if (id.isBlank()) null else Track(id, CatalogParser.text(it.optJSONObject("title")),
                    CatalogParser.text(it.optJSONObject("longBylineText")),
                    thumbnailUri = ThumbnailParser.bestUrl(it.optJSONObject("thumbnail")))
            }.distinctBy { it.id }
            val token = CatalogParser.objects(response, "nextRadioContinuationData").firstOrNull()
                ?.optString("continuation")?.takeIf { it.isNotBlank() } ?: CatalogParser.continuation(response)
            RadioPage(tracks, token)
        }
    }

    suspend fun identity(account: AccountSession): AccountIdentity {
        val response = request("account/account_menu", JSONObject(), account)
        val header = CatalogParser.objects(response, "activeAccountHeaderRenderer").firstOrNull()
            ?: throw SessionExpiredException()
        val name = CatalogParser.text(header.optJSONObject("accountName"))
        if (name.isBlank()) throw SessionExpiredException()
        val avatar = header.optJSONObject("accountPhoto")?.optJSONArray("thumbnails")?.optJSONObject(0)
            ?.optString("url")?.takeIf(String::isNotBlank)
        return AccountIdentity(name, avatar, account.channelId)
    }

    suspend fun accountChannels(account: AccountSession): List<AccountChannel> {
        val response = request("account/accounts_list", JSONObject()
            .put("requestType", "ACCOUNTS_LIST_REQUEST_TYPE_CHANNEL_SWITCHER")
            .put("callCircumstance", "SWITCHING_USERS_FULL"), account)
        val channels = WebSessionParser.parseChannels(response)
        if (channels.isNotEmpty()) return channels

        // Some valid single-channel accounts omit the switcher list. Require a successful
        // authenticated account_menu identity before offering the currently active channel.
        val active = identity(account)
        return listOf(AccountChannel(
            title = active.name,
            subtitle = "Current YouTube channel",
            pageId = active.channelId,
            dataSyncId = account.dataSyncId,
            avatar = active.avatar,
            selected = true,
        ))
    }

    override suspend fun collection(collection: AccountCollection, continuation: String?): CatalogPage {
        val account = session() ?: throw SessionExpiredException()
        return collectionFor(account, collection, continuation)
    }

    override suspend fun collectionFor(account: AccountSession, collection: AccountCollection, continuation: String?): CatalogPage {
        val body = if (continuation == null) JSONObject().put("browseId", collection.browseId)
        else JSONObject().put("continuation", continuation)
        val response = request("browse", body, account)
        return withContext(Dispatchers.Default) { CatalogParser.page(response) }
    }

    override suspend fun playlist(playlistId: String, continuation: String?): CatalogPage {
        require(playlistId.isNotBlank())
        val account = session()
        return if (account == null) browse(playlistId, continuation) else playlistFor(account, playlistId, continuation)
    }

    override suspend fun playlistFor(account: AccountSession, playlistId: String, continuation: String?): CatalogPage {
        require(playlistId.isNotBlank())
        val body = if (continuation == null) JSONObject().put("browseId", playlistId)
        else JSONObject().put("continuation", continuation)
        val response = request("browse", body, account)
        return withContext(Dispatchers.Default) { CatalogParser.page(response) }
    }

    suspend fun likeState(account: AccountSession, videoId: String): CloudLikeState {
        val response = request("next", JSONObject().put("videoId", videoId), account)
        return withContext(Dispatchers.Default) { CloudLikeParser.parse(response, videoId) }
    }

    override suspend fun setLiked(videoId: String, liked: Boolean) = setLikedFor(session() ?: throw SessionExpiredException(), videoId, liked)

    suspend fun setLikedFor(account: AccountSession, videoId: String, liked: Boolean) =
        setRating(account, videoId, if (liked) "like/like" else "like/removelike")

    suspend fun setDislikedFor(account: AccountSession, videoId: String, disliked: Boolean) =
        setRating(account, videoId, if (disliked) "like/dislike" else "like/removelike")

    private suspend fun setRating(account: AccountSession, videoId: String, endpoint: String) {
        require(videoId.isNotBlank())
        val response = request(endpoint,
            JSONObject().put("target", JSONObject().put("videoId", videoId)), account)
        val status = response.optString("status")
        if (status.isNotBlank() && status != "STATUS_SUCCEEDED")
            throw IOException("YouTube Music did not accept the library change.")
    }
}
