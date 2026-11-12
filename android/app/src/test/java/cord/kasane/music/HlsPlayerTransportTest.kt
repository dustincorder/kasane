package cord.kasane.music

import cord.kasane.music.provider.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class HlsPlayerTransportTest {
    @Test fun guestRequestCarriesNoCredentialsAndSelectsHls() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"playabilityStatus":{"status":"OK"},"streamingData":{"hlsManifestUrl":"https://manifest.googlevideo.com/test"}}"""))
            val resolver = HlsPlayerResolver(OkHttpClient(), server.url("/player").toString())
            assertTrue(resolver.resolve("dQw4w9WgXcQ") is HlsStream)
            val request = server.takeRequest()
            assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
            assertEquals("5", request.getHeader("X-Youtube-Client-Name"))
            assertTrue(request.body.readUtf8().contains("IOS"))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun forbiddenIsUnavailableWithoutRetryLoop() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            val resolver = HlsPlayerResolver(OkHttpClient(), server.url("/player").toString())
            assertTrue(resolver.resolve("dQw4w9WgXcQ") is UnavailableStream)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun authenticatedManifestPrecedesGuestProbe() = runBlocking {
        MockWebServer().use { server ->
            val resolver = HlsPlayerResolver(OkHttpClient(), server.url("/player").toString()) {
                org.json.JSONObject("""{"playabilityStatus":{"status":"OK"},"streamingData":{"hlsManifestUrl":"https://manifest.googlevideo.com/test"}}""")
            }
            val selected = resolver.resolve("dQw4w9WgXcQ") as HlsStream
            assertEquals("WEB_REMIX", selected.clientProfile)
            assertEquals(0, server.requestCount)
        }
    }

}
