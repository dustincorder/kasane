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

    private fun directResponse() = org.json.JSONObject("""{"playabilityStatus":{"status":"OK"},"streamingData":{"serverAbrStreamingUrl":"https://rr1.googlevideo.com/sabr","adaptiveFormats":[{"itag":251,"mimeType":"audio/webm","url":"https://rr1.googlevideo.com/videoplayback?expire=9999999999","bitrate":128000}]}}""")

    @Test fun rejectedAuthenticatedDirectFallsBackToIosHlsOnce() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"playabilityStatus":{"status":"OK"},"streamingData":{"hlsManifestUrl":"https://manifest.googlevideo.com/test"}}"""))
            var probes = 0
            val resolver = HlsPlayerResolver(OkHttpClient(), server.url("/player").toString(),
                progressiveCheck = { _, _ -> probes++; false }, authenticatedPlayer = { directResponse() })
            val stream = resolver.resolve("dQw4w9WgXcQ") as HlsStream
            assertEquals("IOS", stream.clientProfile)
            assertEquals(1, probes); assertEquals(1, server.requestCount)
        }
    }
    @Test fun rejectedDirectAndSabrOnlyFallbackIsExplicitlyUnavailable() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"playabilityStatus":{"status":"OK"},"streamingData":{"serverAbrStreamingUrl":"https://rr1.googlevideo.com/sabr"}}"""))
            val resolver = HlsPlayerResolver(OkHttpClient(), server.url("/player").toString(),
                progressiveCheck = { _, _ -> false }, authenticatedPlayer = { directResponse() })
            assertEquals("SABR_REQUIRED", (resolver.resolve("dQw4w9WgXcQ") as UnavailableStream).code)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun accessibleDirectDoesNotPerformGuestPlayerRequest() = runBlocking {
        MockWebServer().use { server ->
            val resolver = HlsPlayerResolver(OkHttpClient(), server.url("/player").toString(),
                progressiveCheck = { _, _ -> true }, authenticatedPlayer = { directResponse() })
            assertTrue(resolver.resolve("dQw4w9WgXcQ") is ProgressiveStream)
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun firstByte403RejectsDirectWithoutRetryOrCookieForwarding() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            val client = OkHttpClient.Builder().cookieJar(object : okhttp3.CookieJar {
                override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {}
                override fun loadForRequest(url: okhttp3.HttpUrl) = listOf(okhttp3.Cookie.Builder().name("test-session").value("synthetic").domain(url.host).build())
            }).build()
            val resolver = HlsPlayerResolver(client, server.url("/player").toString())
            assertFalse(resolver.checkProgressive("dQw4w9WgXcQ", ResolvedAudioStream(server.url("/audio").toString(),251,"audio/webm",Long.MAX_VALUE)))
            val request = server.takeRequest()
            assertEquals("bytes=0-0", request.getHeader("Range"))
            assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun chunkBoundary403AlsoRejectsDirect() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setBody("a"))
            server.enqueue(MockResponse().setResponseCode(403))
            val resolver = HlsPlayerResolver(OkHttpClient(), server.url("/player").toString())
            assertFalse(resolver.checkProgressive("dQw4w9WgXcQ", ResolvedAudioStream(server.url("/audio").toString(),251,"audio/webm",Long.MAX_VALUE,1000000)))
            assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
            assertEquals("bytes=524288-524288", server.takeRequest().getHeader("Range"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun directProbeDoesNotFollowRedirects() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/other")))
            val resolver = HlsPlayerResolver(OkHttpClient(), server.url("/player").toString())
            assertFalse(resolver.checkProgressive("dQw4w9WgXcQ", ResolvedAudioStream(server.url("/audio").toString(),251,"audio/webm",Long.MAX_VALUE)))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun emptySuccessfulResponseIsNotAudio() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            val resolver = HlsPlayerResolver(OkHttpClient(), server.url("/player").toString())
            assertFalse(resolver.checkProgressive("dQw4w9WgXcQ", ResolvedAudioStream(server.url("/audio").toString(),251,"audio/webm",Long.MAX_VALUE)))
        }
    }
    @Test fun cancellationOfDirectProbeDoesNotStartAnotherProfile() = runBlocking {
        MockWebServer().use { server ->
            val resolver = HlsPlayerResolver(OkHttpClient(), server.url("/player").toString(),
                progressiveCheck = { _, _ -> throw kotlinx.coroutines.CancellationException("test cancellation") },
                authenticatedPlayer = { directResponse() })
            try { resolver.resolve("dQw4w9WgXcQ"); fail("Expected cancellation") }
            catch (_: kotlinx.coroutines.CancellationException) {}
            assertEquals(0, server.requestCount)
        }
    }

}
