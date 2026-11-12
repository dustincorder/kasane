package cord.kasane.music

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.provider.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RadioAndLikesRequestTest {
    @Test fun radioUsesSelectedAccountDeduplicatesAndRequestsContinuation() = runTest {
        MockWebServer().use { server ->
            server.start()
            val session = AccountSession("SAPISID=mock-only", channelId = "UCmock")
            val catalog = OnlineCatalog(OkHttpClient(), { session }, server.url("/"))
            val renderer = """{"playlistPanelVideoRenderer":{"videoId":"wzpAehzNCU8","title":{"simpleText":"Brand New Day"}}}"""
            server.enqueue(MockResponse().setBody("""{"items":[$renderer,$renderer],"continuations":[{"nextRadioContinuationData":{"continuation":"page-two"}}]}"""))
            val first = catalog.radioPage("wzpAehzNCU8")
            assertEquals(1, first.tracks.size); assertEquals("page-two", first.continuation)
            val request = server.takeRequest()
            assertEquals(session.cookie, request.getHeader("Cookie"))
            assertEquals("UCmock", request.getHeader("X-Goog-PageId"))
            assertEquals("RDAMVMwzpAehzNCU8", JSONObject(request.body.readUtf8()).getString("playlistId"))
            server.enqueue(MockResponse().setBody("{}"))
            catalog.radioPage("wzpAehzNCU8", first.continuation)
            val next = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("page-two", next.getString("continuation")); assertFalse(next.has("videoId"))
        }
    }
    @Test fun guestRadioNeverCarriesAccountHeaders() = runTest {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{}"))
            OnlineCatalog(OkHttpClient(), apiBaseUrl = server.url("/")).radioPage("wzpAehzNCU8")
            val request = server.takeRequest()
            assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
        }
    }
    @Test fun accountLikeReadAndMutationUseExactVideoId() = runTest {
        MockWebServer().use { server ->
            server.start()
            val account = AccountSession("SAPISID=mock-only")
            val catalog = OnlineCatalog(OkHttpClient(), { account }, server.url("/"))
            server.enqueue(MockResponse().setBody("""{"likeButtonRenderer":{"target":{"videoId":"wzpAehzNCU8"},"likeStatus":"LIKE"}}"""))
            assertEquals(CloudLikeState.LIKED, catalog.likeState(account, "wzpAehzNCU8"))
            assertTrue(server.takeRequest().path!!.contains("next"))
            for (disliked in listOf(true, false)) {
                server.enqueue(MockResponse().setBody("""{"status":"STATUS_SUCCEEDED"}"""))
                catalog.setDislikedFor(account, "wzpAehzNCU8", disliked)
                assertTrue(server.takeRequest().path!!.contains(if (disliked) "like/dislike" else "like/removelike"))
            }
            for (liked in listOf(true, false)) {
                server.enqueue(MockResponse().setBody("""{"status":"STATUS_SUCCEEDED"}"""))
                catalog.setLikedFor(account, "wzpAehzNCU8", liked)
                val request = server.takeRequest()
                assertTrue(request.path!!.contains(if (liked) "like/like" else "like/removelike"))
                assertEquals("wzpAehzNCU8", JSONObject(request.body.readUtf8()).getJSONObject("target").getString("videoId"))
            }
        }
    }
}
