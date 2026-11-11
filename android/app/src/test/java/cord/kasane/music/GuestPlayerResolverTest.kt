package cord.kasane.music

import cord.kasane.music.provider.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GuestPlayerResolverTest {
    private val id = "kIft-LUHHVA"
    private val now = 1_800_000_000_000L
    private fun ok(url: String = "https://audio.example.test/audio", expires: Int = 300, itag: Int = 140) =
        """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"$id"},"streamingData":{"expiresInSeconds":"$expires","adaptiveFormats":[{"itag":$itag,"mimeType":"audio/mp4","bitrate":128000,"url":"$url"}]}}"""
    private fun resolver(responses: List<String>, requests: MutableList<Request>, clock: () -> Long = { now }): GuestPlayerResolver {
        var index = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            check(index < responses.size) { "Unbounded player retries" }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(responses[index++].toResponseBody()).build()
        }.build()
        return GuestPlayerResolver(client, clock)
    }
    @Test fun successfulGuestRequestUsesMatchingProfileAndNoAccountHeaders() = runTest {
        val requests = mutableListOf<Request>()
        val stream = resolver(listOf(ok()), requests).resolve(id)
        assertEquals(140, stream.itag)
        assertEquals(GuestPlayerResolver.USER_AGENT, stream.headers["User-Agent"])
        assertEquals("music.youtube.com", requests.single().url.host)
        assertNull(requests.single().header("Cookie"))
        assertNull(requests.single().header("Authorization"))
        val body = Buffer().also { requests.single().body!!.writeTo(it) }.readUtf8()
        assertEquals(id, JSONObject(body).getString("videoId"))
    }
    @Test fun botSessionChallengeRetriesOnceWithServerIssuedAnonymousContext() = runTest {
        val requests = mutableListOf<Request>()
        val challenge = """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you are not a bot"},"responseContext":{"visitorData":"anonymous-test-context"}}"""
        resolver(listOf(challenge, ok()), requests).resolve(id)
        assertEquals(2, requests.size)
        assertEquals("anonymous-test-context", requests[1].header("X-Goog-Visitor-Id"))
        val body = Buffer().also { requests[1].body!!.writeTo(it) }.readUtf8()
        assertEquals("anonymous-test-context", JSONObject(body).getJSONObject("context").getJSONObject("client").getString("visitorData"))
    }
    @Test fun actualLoginRequirementDoesNotRetry() = runTest {
        val requests = mutableListOf<Request>()
        try {
            resolver(listOf("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"This is a private video"}}"""), requests).resolve(id)
            fail("Private track must remain restricted")
        } catch (e: StreamUnavailableException) { assertEquals("LOGIN_REQUIRED", e.status) }
        assertEquals(1, requests.size)
    }
    @Test fun unresolvedChallengeCannotLoop() = runTest {
        val requests = mutableListOf<Request>()
        val challenge = """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you are not a bot"},"responseContext":{"visitorData":"anonymous-test-context"}}"""
        try { resolver(listOf(challenge, challenge), requests).resolve(id); fail() }
        catch (e: StreamUnavailableException) { assertEquals("LOGIN_REQUIRED", e.status) }
        assertEquals(2, requests.size)
    }
    @Test fun unplayableTrackIsNotSubstitutedOrRetried() = runTest {
        val requests = mutableListOf<Request>()
        try { resolver(listOf("""{"playabilityStatus":{"status":"UNPLAYABLE"}}"""), requests).resolve(id); fail() }
        catch (e: StreamUnavailableException) { assertEquals("UNPLAYABLE", e.status) }
        assertEquals(1, requests.size)
    }
    @Test fun emptyFormatsProduceExplicitFailure() = runTest {
        val requests = mutableListOf<Request>()
        try { resolver(listOf("""{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[]}}"""), requests).resolve(id); fail() }
        catch (e: StreamUnavailableException) { assertEquals("NO_DIRECT_STREAM", e.status) }
    }
    @Test fun expiredPlayerUrlIsResolvedOnceAgain() = runTest {
        val requests = mutableListOf<Request>()
        val stream = resolver(listOf(ok("https://audio.example.test/old?expire=1"), ok()), requests).resolve(id)
        assertEquals("https://audio.example.test/audio", stream.url)
        assertEquals(2, requests.size)
    }
    @Test fun expiredCacheIsNeverReused() = runTest {
        var time = now
        val requests = mutableListOf<Request>()
        val resolver = resolver(listOf(ok(), ok("https://audio.example.test/new")), requests) { time }
        resolver.resolve(id); time += 300_000
        assertEquals("https://audio.example.test/new", resolver.resolve(id).url)
        assertEquals(2, requests.size)
    }
    @Test fun freshCacheIsReusedForRangeOpens() = runTest {
        val requests = mutableListOf<Request>()
        val resolver = resolver(listOf(ok()), requests)
        resolver.resolve(id); resolver.resolve(id)
        assertEquals(1, requests.size)
    }
    @Test fun cdnForbiddenRefreshesOnceAndPreservesFormat() = runTest {
        val requests = mutableListOf<Request>()
        val resolver = resolver(listOf(ok(), ok("https://audio.example.test/new")), requests)
        var opens = 0
        val result = resolver.open(id) { stream -> if (opens++ == 0) throw AudioHttpException(403); stream.url }
        assertEquals("https://audio.example.test/new", result)
        assertEquals(2, opens)
        assertEquals(2, requests.size)
    }
    @Test fun repeatedCdnRefusalIsNotHiddenOrLooped() = runTest {
        val requests = mutableListOf<Request>()
        var opens = 0
        try {
            resolver(listOf(ok(), ok()), requests).open(id) { opens++; throw AudioHttpException(410) }
            fail()
        } catch (e: AudioHttpException) { assertEquals(410, e.code) }
        assertEquals(2, opens)
    }
    @Test fun networkErrorDoesNotExposeUrlOrCauseUnboundedRetry() = runTest {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { calls++; throw IOException("sensitive transport detail") }.build()
        try { GuestPlayerResolver(client).resolve(id); fail() }
        catch (e: IOException) { assertFalse(e.message.orEmpty().contains("sensitive")) }
        assertEquals(1, calls)
    }
    @Test fun drmAudioIsNotChosenEvenIfHighestBitrate() {
        val root = JSONObject(ok())
        root.getJSONObject("streamingData").getJSONArray("adaptiveFormats").put(JSONObject(
            """{"itag":999,"mimeType":"audio/mp4","bitrate":999999,"url":"https://audio.example.test/drm","drmFamilies":["WIDEVINE"]}"""))
        assertEquals(140, StreamResponse.audioStream(root, now).itag)
    }
    @Test fun formatCannotChangeOnRangeRetry() = runTest {
        val requests = mutableListOf<Request>()
        var opens = 0
        try {
            resolver(listOf(ok(), ok(itag = 251)), requests).open(id) { opens++; throw AudioHttpException(403) }
            fail()
        } catch (e: StreamUnavailableException) { assertEquals("NO_DIRECT_STREAM", e.status) }
        assertEquals(1, opens)
    }
}
