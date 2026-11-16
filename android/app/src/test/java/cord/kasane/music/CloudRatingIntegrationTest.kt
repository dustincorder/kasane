package cord.kasane.music

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.Assert.*

/** Controller + Android facade + shared client + real loopback HTTP, no account mutation. */
class CloudRatingIntegrationTest {
    @Test fun unconfirmedServerRatingRollsBackOptimisticUiAndDoesNotRepeatPost() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val id = "fixture0001"
            server.enqueue(MockResponse().setBody("""{"likeButtonRenderer":{"target":{"videoId":"fixture0001"},"likeStatus":"INDIFFERENT"}}"""))
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("{}"))
            val account = AccountSession("SAPISID=fixture-secret")
            val catalog = OnlineCatalog(OkHttpClient(), { account }, server.url("/"))
            val ratings = CloudLikes({ catalog.likeState(account, it) }, { track, liked -> catalog.setLikedFor(account, track, liked) }, dispatcher = Dispatchers.Default)
            try {
                ratings.refresh(id)
                withTimeout(5000) { ratings.states.first { it[id] == CloudLikeState.NEUTRAL } }
                val error = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5000) { ratings.errors.first() } }
                ratings.toggle(id)
                withTimeout(5000) { ratings.pending.first { id !in it } }
                assertEquals(CloudLikeState.NEUTRAL, ratings.states.value[id])
                assertTrue(error.await().contains("Could not update"))
                assertEquals(3, server.requestCount)
                assertTrue(server.takeRequest().path.orEmpty().contains("next"))
                assertTrue(server.takeRequest().path.orEmpty().contains("like/like"))
                assertTrue(server.takeRequest().path.orEmpty().contains("next"))
            } finally { ratings.close() }
        }
    }
}
