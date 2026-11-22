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
    private val playerDiagnostic: (String) -> Unit = {},
    accountSession: () -> cord.kasane.music.auth.AccountSession? = { null },
    verificationRuntime: cord.kasane.music.provider.verification.VerificationRuntime? = null,
) : MusicProvider {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    val catalog = OnlineCatalog(client, accountSession)
    override suspend fun search(query: String, limit: Int): List<Track> =
        catalog.search(query, cord.kasane.music.model.CatalogKind.SONG).tracks.take(limit)

    private val verifiedResolver = verificationRuntime?.let {
        cord.kasane.music.provider.verification.VerifiedPlayerResolver(client, it,
            accountHeaders = { accountSession()?.let(::signedInHeaders) })
    }
    val hlsResolver = HlsPlayerResolver(client, diagnostic = { android.util.Log.i("KasanePlayback", it) },
        authenticatedPlayer = { videoId ->
            accountSession()?.let { account -> catalog.request("player", JSONObject().put("videoId", videoId), account,
                clientVersion = "1.20260707.12.00") }
        },
        verified = verifiedResolver?.let { resolver -> resolver::resolve })
    val playerResolver = GuestPlayerResolver(client, diagnostic = playerDiagnostic)

    override suspend fun resolveStreamUrl(trackId: String): String? = playerResolver.resolve(trackId).url

    override suspend fun getHomeRecommendations(limit: Int): List<Track> = catalog.home().tracks.take(limit)
}

/** Same signed-in headers the catalog uses: cookie, SAPISIDHASH for music.youtube.com, auth user. */
internal fun signedInHeaders(account: cord.kasane.music.auth.AccountSession): Map<String, String>? {
    val cookies = account.cookie.split(';').mapNotNull { value ->
        value.trim().split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }.toMap()
    val secret = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: cookies["__Secure-1PAPISID"] ?: return null
    val origin = "https://music.youtube.com"
    val time = System.currentTimeMillis() / 1000
    val hash = java.security.MessageDigest.getInstance("SHA-1").digest("$time $secret $origin".toByteArray())
        .joinToString("") { "%02x".format(it) }
    return buildMap {
        put("Cookie", account.cookie)
        put("Authorization", "SAPISIDHASH ${time}_$hash")
        put("X-Goog-AuthUser", account.authUser.toString())
        account.visitorData?.let { put("X-Goog-Visitor-Id", it) }
        account.channelId?.let { put("X-Goog-PageId", it) }
    }
}
