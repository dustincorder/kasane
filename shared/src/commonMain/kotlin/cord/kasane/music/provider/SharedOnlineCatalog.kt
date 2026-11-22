package cord.kasane.music.provider

import cord.kasane.music.auth.*
import cord.kasane.music.data.CatalogRemote
import cord.kasane.music.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

/** One typed catalog implementation. Platform facades only adapt transport/errors/login UI. */
class SharedOnlineCatalog(private val client: InnerTubeClient, private val session: () -> AccountSession? = { null }) :
    CatalogRemote, AccountLibraryProvider, AccountPlaylistProvider {
    private val mutableLinks = MutableStateFlow<Map<String, TrackLinks>>(emptyMap())
    val trackLinks = mutableLinks.asStateFlow()
    suspend fun request(endpoint: String, payload: JsonObject = JsonObject(emptyMap()), account: AccountSession? = null,
        clientVersion: String = "1.20240101.01.00"): JsonObject = withContext(Dispatchers.Default) {
        val response = client.request(endpoint, payload, account, clientVersion)
        val links = TrackLinksJsonParser.parse(response)
        if (links.isNotEmpty()) mutableLinks.update { old -> (old + links).entries.toList().takeLast(1000).associate { it.key to it.value } }
        response
    }
    override suspend fun search(query: String, kind: CatalogKind, continuation: String?): CatalogPage = withContext(Dispatchers.Default) {
        if (query.isBlank()) return@withContext CatalogPage("", "", null, emptyList())
        val body = buildJsonObject { put("query", query.trim()); put("params", kind.filter); continuation?.let { put("continuation", it) } }
        CatalogJsonParser.search(request("search", body), kind)
    }
    override suspend fun browse(id: String, continuation: String?): CatalogPage = withContext(Dispatchers.Default) {
        require(id.isNotBlank())
        CatalogJsonParser.page(request("browse", browseBody(id, continuation)))
    }
    override suspend fun home(account: AccountSession?): CatalogPage = withContext(Dispatchers.Default) {
        CatalogJsonParser.page(request("browse", buildJsonObject { put("browseId", "FEmusic_home") }, account))
    }
    /** Home filtered by a mood/activity chip. The response is a full Home feed for that mood. */
    suspend fun homeFiltered(account: AccountSession?, params: String): CatalogPage = withContext(Dispatchers.Default) {
        require(params.isNotBlank())
        CatalogJsonParser.page(request("browse", buildJsonObject { put("browseId", "FEmusic_home"); put("params", params) }, account))
    }
    /** Typing suggestions from YouTube Music. Empty on any failure: suggestions are a convenience, never an error. */
    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.Default) {
        if (query.isBlank()) return@withContext emptyList()
        runCatching {
            val root = request("music/get_search_suggestions", buildJsonObject { put("input", query.trim().take(100)) }, session())
            CatalogJsonParser.objects(root, "searchSuggestionRenderer").mapNotNull {
                CatalogJsonParser.text(it.optJsonObject("suggestion")).takeIf { s -> s.isNotBlank() }
            }.distinct().take(8)
        }.getOrDefault(emptyList())
    }
    suspend fun radioPage(videoId: String, continuation: String? = null): RadioPage = withContext(Dispatchers.Default) {
        val body = buildJsonObject {
            if (continuation != null) put("continuation", continuation)
            else { put("videoId", videoId); put("playlistId", "RDAMVM$videoId") }
        }
        CatalogJsonParser.radio(request("next", body, session()))
    }
    suspend fun identity(account: AccountSession): AccountIdentity = withContext(Dispatchers.Default) {
        val root = request("account/account_menu", account = account)
        val header = CatalogJsonParser.objects(root, "activeAccountHeaderRenderer").firstOrNull()
            ?: throw CatalogFailure(CatalogFailureReason.SESSION_EXPIRED)
        val name = CatalogJsonParser.text(header.optJsonObject("accountName"))
        if (name.isBlank()) throw CatalogFailure(CatalogFailureReason.SESSION_EXPIRED)
        val avatar = header.optJsonObject("accountPhoto")?.optJsonArray("thumbnails")?.optJsonObject(0)
            ?.optString("url")?.takeIf { it.isNotBlank() }
        AccountIdentity(name, avatar, account.channelId)
    }
    override suspend fun collection(collection: AccountCollection, continuation: String?): CatalogPage =
        collectionFor(session() ?: throw CatalogFailure(CatalogFailureReason.SESSION_EXPIRED), collection, continuation)
    override suspend fun collectionFor(account: AccountSession, collection: AccountCollection, continuation: String?): CatalogPage = withContext(Dispatchers.Default) {
        CatalogJsonParser.libraryPage(request("browse", browseBody(collection.browseId, continuation), account))
    }
    override suspend fun playlist(playlistId: String, continuation: String?): CatalogPage {
        val account = session()
        return if (account == null) browse(playlistBrowseId(playlistId), continuation) else playlistFor(account, playlistId, continuation)
    }
    override suspend fun playlistFor(account: AccountSession, playlistId: String, continuation: String?): CatalogPage = withContext(Dispatchers.Default) {
        CatalogJsonParser.libraryPage(request("browse", browseBody(playlistBrowseId(playlistId), continuation), account))
    }
    private fun playlistBrowseId(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_-]{2,258}"))) { "Invalid playlist identifier." }
        return if (value.startsWith("VL")) value else "VL$value"
    }
    private fun browseBody(id: String, continuation: String?) = buildJsonObject {
        if (continuation == null) put("browseId", id) else put("continuation", continuation)
    }
    suspend fun likeState(account: AccountSession, videoId: String): CloudLikeState = withContext(Dispatchers.Default) {
        CloudLikeJsonParser.parse(request("next", buildJsonObject { put("videoId", videoId) }, account), videoId)
    }
    override suspend fun setLiked(videoId: String, liked: Boolean) = setLikedFor(session() ?: throw CatalogFailure(CatalogFailureReason.SESSION_EXPIRED), videoId, liked)
    suspend fun setLikedFor(account: AccountSession, videoId: String, liked: Boolean) = setRating(account, videoId, if (liked) "like/like" else "like/removelike")
    suspend fun setDislikedFor(account: AccountSession, videoId: String, disliked: Boolean) = setRating(account, videoId, if (disliked) "like/dislike" else "like/removelike")
    private suspend fun setRating(account: AccountSession, videoId: String, endpoint: String) = withContext(Dispatchers.Default) {
        require(videoId.isNotBlank())
        val response = request(endpoint, buildJsonObject { put("target", buildJsonObject { put("videoId", videoId) }) }, account)
        val status = response.optString("status")
        if (status.isNotBlank() && status != "STATUS_SUCCEEDED") throw CatalogFailure(CatalogFailureReason.RATING_REFUSED)
        if (status.isBlank()) {
            val expected = when (endpoint) { "like/like" -> CloudLikeState.LIKED; "like/dislike" -> CloudLikeState.DISLIKED; else -> CloudLikeState.NEUTRAL }
            if (likeState(account, videoId) != expected) throw CatalogFailure(CatalogFailureReason.RATING_UNCONFIRMED)
        }
    }
    override suspend fun playlistDestinations(account: AccountSession, videoId: String): List<PlaylistDestination> = withContext(Dispatchers.Default) {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
        val root = request("playlist/get_add_to_playlist", buildJsonObject { put("videoIds", buildJsonArray { add(videoId) }); put("excludeWatchLater", true) }, account)
        PlaylistJsonParser.destinations(root, videoId)
    }
    override suspend fun addVideoToPlaylist(account: AccountSession, playlistId: String, videoId: String) = withContext(Dispatchers.Default) {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
        val id = PlaylistJsonParser.playlistId(playlistId)
        val root = request("browse/edit_playlist", buildJsonObject {
            put("playlistId", id); put("actions", buildJsonArray { add(buildJsonObject { put("action", "ACTION_ADD_VIDEO"); put("addedVideoId", videoId) }) })
        }, account)
        PlaylistJsonParser.requireAddReceipt(root, videoId)
    }
}
