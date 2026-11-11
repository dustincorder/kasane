package cord.kasane.music.provider

import cord.kasane.music.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface MusicProvider {
    suspend fun search(query: String, limit: Int = 20): List<Track>
    suspend fun resolveStreamUrl(trackId: String): String?
    suspend fun getHomeRecommendations(limit: Int = 20): List<Track> = emptyList()
}

class YouTubeMusicProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val playerDiagnostic: (String) -> Unit = {}
) : MusicProvider {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    val catalog = OnlineCatalog(client) { accountSession?.invoke() }
    var accountSession: (() -> cord.kasane.music.auth.AccountSession?)? = null
    override suspend fun search(query: String, limit: Int): List<Track> =
        catalog.search(query, cord.kasane.music.model.CatalogKind.SONG).tracks.take(limit)

    val playerResolver = GuestPlayerResolver(client, diagnostic = playerDiagnostic)

    override suspend fun resolveStreamUrl(trackId: String): String? = playerResolver.resolve(trackId).url

    override suspend fun getHomeRecommendations(limit: Int): List<Track> = catalog.home().tracks.take(limit)
}
