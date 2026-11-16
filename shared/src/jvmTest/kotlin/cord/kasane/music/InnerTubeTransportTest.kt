package cord.kasane.music

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.provider.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.util.concurrent.TimeUnit
import kotlin.test.*

class InnerTubeTransportTest {
    private val account = AccountSession("SAPISID=fixture-secret", 2, "UCfixture", "delegated", "visitor", "delegated||user")
    private fun client(server: MockWebServer, http: OkHttpClient = OkHttpClient()) = InnerTubeClient(
        OkHttpCatalogTransport(http), { 12345L }, JvmCatalogCrypto::sha1Hex, server.url("/").toString(), true)
    private suspend fun failure(expected: CatalogFailureReason, block: suspend () -> Unit): CatalogFailure {
        try { block(); fail("Expected $expected") } catch (error: CatalogFailure) {
            assertEquals(expected, error.reason)
            assertFalse(error.toString().contains("fixture-secret"))
            assertFalse(error.toString().contains("http://"))
            return error
        }
    }
    @Test fun signedContinuationUsesExactContextAndDoesNotMutateCallerPayload() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val token = "page & +=/"
            val payload = buildJsonObject { put("continuation", token); put("context", "caller-context") }
            client(server).request("browse", payload, account)
            val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals(token, request.requestUrl?.queryParameter("continuation"))
            assertEquals(token, request.requestUrl?.queryParameter("ctoken"))
            assertEquals(account.cookie, request.getHeader("Cookie"))
            assertEquals("2", request.getHeader("X-Goog-AuthUser"))
            assertEquals("UCfixture", request.getHeader("X-Goog-PageId"))
            assertEquals("visitor", request.getHeader("X-Goog-Visitor-Id"))
            assertEquals("SAPISIDHASH 12345_${JvmCatalogCrypto.sha1Hex("12345 fixture-secret https://music.youtube.com")}", request.getHeader("Authorization"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertFalse(body.containsKey("continuation"))
            assertEquals("WEB_REMIX", body["context"]!!.jsonObject["client"]!!.jsonObject["clientName"]!!.jsonPrimitive.content)
            assertEquals("delegated", body["context"]!!.jsonObject["user"]!!.jsonObject["onBehalfOfUser"]!!.jsonPrimitive.content)
            assertEquals(token, payload["continuation"]!!.jsonPrimitive.content)
            assertEquals("caller-context", payload["context"]!!.jsonPrimitive.content)
        }
    }
    @Test fun guestCannotInheritCookiesFromInjectedHttpClient() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val http = OkHttpClient.Builder().cookieJar(object : CookieJar {
                override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("SECRET_COOKIE").value("fixture").domain("localhost").build())
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {}
            }).build()
            client(server, http).request("search", buildJsonObject { put("query", "public music") })
            val request = server.takeRequest()
            assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("X-Goog-AuthUser"))
        }
    }
    @Test fun redirectDoesNotForwardAccountCredentials() = runBlocking<Unit> {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/unexpected")))
            failure(CatalogFailureReason.REDIRECT) { client(server).request("browse", account = account) }
            assertEquals(1, server.requestCount); assertEquals(0, other.requestCount)
        } }
    }
    @Test fun sessionExpiryAndPlayerDenialAreDifferentTerminalFailures() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val api = client(server)
            server.enqueue(MockResponse().setResponseCode(401))
            failure(CatalogFailureReason.SESSION_EXPIRED) { api.request("browse", account = account) }
            server.enqueue(MockResponse().setResponseCode(403))
            failure(CatalogFailureReason.PLAYER_DENIED) { api.request("player", account = account) }
            server.enqueue(MockResponse().setResponseCode(403))
            failure(CatalogFailureReason.ACCESS_DENIED) { api.request("browse", account = account) }
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun apiLoggedOutAndMalformedResponsesNeverBecomeSuccessfulEmptyLibraries() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val api = client(server)
            server.enqueue(MockResponse().setBody("""{"responseContext":{"mainAppWebResponseContext":{"loggedOut":true}}}"""))
            failure(CatalogFailureReason.SESSION_EXPIRED) { api.request("browse", account = account) }
            server.enqueue(MockResponse().setBody("not json: fixture-secret"))
            failure(CatalogFailureReason.INVALID_RESPONSE) { api.request("browse") }
            server.enqueue(MockResponse().setBody("{}"))
            failure(CatalogFailureReason.INVALID_LIBRARY) { CatalogJsonParser.libraryPage(api.request("browse", account = account)) }
        }
    }
    @Test fun oversizedDecodedBodyIsBoundedBeforeJsonParsing() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(InnerTubeClient.MAX_RESPONSE_BYTES + 1) { 'x'.code.toByte() })))
            failure(CatalogFailureReason.RESPONSE_TOO_LARGE) { client(server).request("browse") }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun cancellationStopsTheInFlightCallInsteadOfReturningNetworkFailure() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val api = client(server)
            val job = launch(Dispatchers.Default) { api.request("browse") }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled); assertEquals(1, server.requestCount)
        }
    }
    @Test fun networkFailureIsSanitizedAndNotRetried() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            failure(CatalogFailureReason.NETWORK) { client(server).request("browse") }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun connectionLostDuringBodyIsNetworkFailureNotMalformedCatalogData() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"payload\":\"" + "x".repeat(20000) + "\"}")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            failure(CatalogFailureReason.NETWORK) { client(server).request("browse") }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun productionOriginCannotBeChangedToAnArbitraryCredentialReceiver() {
        for (base in listOf("https://music.youtube.com.attacker.invalid/", "https://user:pass@music.youtube.com/", "http://music.youtube.com/", "http://127.0.0.1:9999/")) {
            assertFailsWith<IllegalArgumentException> { InnerTubeClient(CatalogHttpTransport { CatalogHttpResponse(200, "{}") }, { 1 }, JvmCatalogCrypto::sha1Hex, base) }
        }
    }
    @Test fun requestAndResponseDiagnosticsRedactTheirSensitivePayloads() = runBlocking<Unit> {
        val api = InnerTubeClient(CatalogHttpTransport { request ->
            assertFalse(request.toString().contains("fixture-secret"))
            assertFalse(request.toString().contains("private-query"))
            CatalogHttpResponse(200, "{}").also { assertFalse(it.toString().contains("{}")) }
        }, { 1 }, JvmCatalogCrypto::sha1Hex)
        api.request("search", buildJsonObject { put("query", "private-query") }, account)
        Unit
    }
}
