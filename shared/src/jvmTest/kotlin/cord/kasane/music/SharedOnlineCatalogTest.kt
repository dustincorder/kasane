package cord.kasane.music

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.provider.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import kotlin.test.*

class SharedOnlineCatalogTest {
    private val account = AccountSession("SAPISID=fixture-secret", channelId = "UCfixture")
    private fun catalog(server: MockWebServer, session: AccountSession? = account) = SharedOnlineCatalog(
        InnerTubeClient(OkHttpCatalogTransport(OkHttpClient()), { 12345 }, JvmCatalogCrypto::sha1Hex, server.url("/").toString(), true), { session })
    @Test fun libraryReadDoesNotCreateOrModifyAnythingAndKeepsAccountContext() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"contents":{"sectionListRenderer":{"contents":[]}}}"""))
            catalog(server).collection(AccountCollection.ALBUMS)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertTrue(request.path.orEmpty().contains("browse"))
            assertEquals(account.cookie, request.getHeader("Cookie"))
            assertEquals(AccountCollection.ALBUMS.browseId, Json.parseToJsonElement(request.body.readUtf8()).jsonObject["browseId"]!!.jsonPrimitive.content)
        }
    }
    @Test fun guestCannotFetchPersonalCollections() = runBlocking<Unit> {
        MockWebServer().use { server ->
            try { catalog(server, null).collection(AccountCollection.PLAYLISTS); fail("Guest must not read account collections") }
            catch (error: CatalogFailure) { assertEquals(CatalogFailureReason.SESSION_EXPIRED, error.reason) }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun signedRadioUsesSelectedAccountAndContinuation() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"continuations":[{"nextRadioContinuationData":{"continuation":"page-2"}}]}"""))
            server.enqueue(MockResponse().setBody("{}"))
            val api = catalog(server)
            assertEquals("page-2", api.radioPage("fixture0001").continuation)
            api.radioPage("fixture0001", "page-2")
            val first = server.takeRequest(); val second = server.takeRequest()
            assertEquals(account.cookie, first.getHeader("Cookie"))
            assertEquals(account.cookie, second.getHeader("Cookie"))
            assertEquals("RDAMVMfixture0001", Json.parseToJsonElement(first.body.readUtf8()).jsonObject["playlistId"]!!.jsonPrimitive.content)
            assertEquals("page-2", Json.parseToJsonElement(second.body.readUtf8()).jsonObject["continuation"]!!.jsonPrimitive.content)
        }
    }
    @Test fun blankRatingAcknowledgementRequiresMatchingServerStateWithoutRepeatingWrite() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"likeButtonRenderer":{"target":{"videoId":"fixture0001"},"likeStatus":"LIKE"}}"""))
            catalog(server).setLikedFor(account, "fixture0001", true)
            assertEquals(2, server.requestCount)
            assertTrue(server.takeRequest().path.orEmpty().contains("like/like"))
            assertTrue(server.takeRequest().path.orEmpty().contains("next"))
        }
    }
    @Test fun unknownRatingAfterBlankAcknowledgementIsNotReportedAsSuccess() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}")); server.enqueue(MockResponse().setBody("{}"))
            try { catalog(server).setLikedFor(account, "fixture0001", true); fail("Unknown is not a confirmed rating") }
            catch (error: CatalogFailure) { assertEquals(CatalogFailureReason.RATING_UNCONFIRMED, error.reason) }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun explicitRatingReceiptDoesNotFetchTheWholeLikedSongsList() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":"STATUS_SUCCEEDED"}"""))
            catalog(server).setDislikedFor(account, "fixture0001", true)
            assertEquals(1, server.requestCount)
            assertTrue(server.takeRequest().path.orEmpty().contains("like/dislike"))
        }
    }
}
