package cord.kasane.music

import cord.kasane.music.auth.*
import cord.kasane.music.model.*
import cord.kasane.music.provider.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountLibraryRequestTest {
    private val session = AccountSession("SAPISID=test-only-cookie")
    private fun catalog(record: MutableList<Request>, body: String = "{}") = OnlineCatalog(
        OkHttpClient.Builder().addInterceptor { chain ->
            record.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(body.toResponseBody()).build()
        }.build()) { session }
    @Test fun openingCollectionsDoesNotMutateRemoteLibrary() = runTest {
        val requests = mutableListOf<Request>()
        catalog(requests).collection(AccountCollection.LIKES)
        assertEquals("/youtubei/v1/browse", requests.single().url.encodedPath)
    }
    @Test fun likeAndUnlikeUseExplicitVideoTargets() = runTest {
        val requests = mutableListOf<Request>()
        val provider = catalog(requests)
        provider.setLiked("test-video", true)
        provider.setLiked("test-video", false)
        assertEquals(listOf("/youtubei/v1/like/like", "/youtubei/v1/like/removelike"),
            requests.map { it.url.encodedPath })
        val buffer = okio.Buffer()
        requests.first().body!!.writeTo(buffer)
        assertEquals("test-video", JSONObject(buffer.readUtf8()).getJSONObject("target").getString("videoId"))
    }
    @Test fun guestCannotMutateAccount() = runTest {
        val catalog = OnlineCatalog(OkHttpClient())
        try { catalog.setLiked("test-video", true); fail("Guest mutation must be rejected") }
        catch (_: SessionExpiredException) { }
    }
    @Test fun headersAreSignedAndRedirectsAreNeverFollowed() = runTest {
        val requests = mutableListOf<Request>()
        catalog(requests).collection(AccountCollection.ALBUMS)
        val request = requests.single()
        assertTrue(request.header("Authorization").orEmpty().startsWith("SAPISIDHASH "))
        assertEquals("0", request.header("X-Goog-AuthUser"))
        assertEquals("music.youtube.com", request.url.host)
    }
    @Test fun rejectedMutationIsNotReportedAsSuccess() = runTest {
        try {
            catalog(mutableListOf(), """{"status":"STATUS_FAILED"}""").setLiked("test-video", true)
            fail("Rejected write")
        } catch (_: java.io.IOException) { }
    }
}
