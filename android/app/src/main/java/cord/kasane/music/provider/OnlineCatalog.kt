package cord.kasane.music.provider

import cord.kasane.music.auth.*
import cord.kasane.music.BuildConfig
import cord.kasane.music.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException

/** Android compatibility facade; typed catalog, requests and parsers have one common implementation. */
class OnlineCatalog(
    client: OkHttpClient,
    session: () -> AccountSession? = { null },
    apiBaseUrl: HttpUrl = "https://music.youtube.com/".toHttpUrl(),
) : AccountLibraryProvider, AccountPlaylistProvider, cord.kasane.music.data.CatalogRemote {
    private val shared = SharedOnlineCatalog(InnerTubeClient(OkHttpCatalogTransport(client),
        nowSeconds = { System.currentTimeMillis() / 1000 }, sha1Hex = JvmCatalogCrypto::sha1Hex,
        baseUrl = apiBaseUrl.toString(), allowLocalTestOrigin = BuildConfig.DEBUG), session)
    val trackLinks = shared.trackLinks
    private suspend fun <T> native(block: suspend () -> T): T = try { block() } catch (failure: CatalogFailure) {
        throw when (failure.reason) {
            CatalogFailureReason.SESSION_EXPIRED -> SessionExpiredException()
            CatalogFailureReason.PLAYER_DENIED -> PlayerHttpException(failure.status ?: 403)
            CatalogFailureReason.HTTP_ERROR -> IOException("YouTube Music request failed (${failure.status}). Try again.")
            else -> IOException(failure.message)
        }
    }
    /** Only login and legacy stream-response consumers still require org.json. */
    suspend fun request(endpoint: String, payload: JSONObject, account: AccountSession? = null,
        clientVersion: String = "1.20240101.01.00"): JSONObject = native {
        withContext(Dispatchers.Default) {
            val body = kotlinx.serialization.json.Json.parseToJsonElement(payload.toString()) as kotlinx.serialization.json.JsonObject
            JSONObject(shared.request(endpoint, body, account, clientVersion).toString())
        }
    }
    override suspend fun search(query: String, kind: CatalogKind, continuation: String?) = native { shared.search(query, kind, continuation) }
    override suspend fun browse(id: String, continuation: String?) = native { shared.browse(id, continuation) }
    override suspend fun home(account: AccountSession?) = native { shared.home(account) }
    suspend fun homeFiltered(account: AccountSession?, params: String) = native { shared.homeFiltered(account, params) }
    suspend fun suggestions(query: String): List<String> = shared.suggestions(query)
    suspend fun radio(videoId: String): List<Track> = radioPage(videoId).tracks
    suspend fun radioPage(videoId: String, continuation: String? = null) = native { shared.radioPage(videoId, continuation) }
    suspend fun identity(account: AccountSession) = native { shared.identity(account) }
    suspend fun accountChannels(account: AccountSession): List<AccountChannel> = withContext(Dispatchers.Default) {
        val response = request("account/accounts_list", JSONObject()
            .put("requestType", "ACCOUNTS_LIST_REQUEST_TYPE_CHANNEL_SWITCHER")
            .put("callCircumstance", "SWITCHING_USERS_FULL"), account)
        val channels = WebSessionParser.parseChannels(response)
        if (channels.isNotEmpty()) channels else {
            // A valid single-channel session may omit the switcher list. Confirm identity first.
            val active = identity(account)
            listOf(AccountChannel(active.name, "Current YouTube channel", active.channelId,
                account.dataSyncId, active.avatar, selected = true))
        }
    }
    override suspend fun collection(collection: AccountCollection, continuation: String?) = native { shared.collection(collection, continuation) }
    override suspend fun collectionFor(account: AccountSession, collection: AccountCollection, continuation: String?) =
        native { shared.collectionFor(account, collection, continuation) }
    override suspend fun playlist(playlistId: String, continuation: String?) = native { shared.playlist(playlistId, continuation) }
    override suspend fun playlistFor(account: AccountSession, playlistId: String, continuation: String?) = native { shared.playlistFor(account, playlistId, continuation) }
    suspend fun likeState(account: AccountSession, videoId: String) = native { shared.likeState(account, videoId) }
    override suspend fun setLiked(videoId: String, liked: Boolean) = native { shared.setLiked(videoId, liked) }
    suspend fun setLikedFor(account: AccountSession, videoId: String, liked: Boolean) = native { shared.setLikedFor(account, videoId, liked) }
    suspend fun setDislikedFor(account: AccountSession, videoId: String, disliked: Boolean) = native { shared.setDislikedFor(account, videoId, disliked) }
    override suspend fun playlistDestinations(account: AccountSession, videoId: String) = native { shared.playlistDestinations(account, videoId) }
    override suspend fun addVideoToPlaylist(account: AccountSession, playlistId: String, videoId: String) = native { shared.addVideoToPlaylist(account, playlistId, videoId) }
}
