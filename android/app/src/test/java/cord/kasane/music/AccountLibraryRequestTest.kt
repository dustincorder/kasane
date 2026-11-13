package cord.kasane.music

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.auth.SessionExpiredException
import cord.kasane.music.provider.AccountCollection
import cord.kasane.music.provider.OnlineCatalog
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountLibraryRequestTest {
    private val session = AccountSession("SAPISID=mock-secret; LOGIN_INFO=mock-login", 2, "UCchannel", "delegated", "visitor-data", "delegated||user")
    private fun catalog(server: MockWebServer, account: AccountSession? = session) : OnlineCatalog {
        server.start()
        return OnlineCatalog(OkHttpClient(), { account }, server.url("/"))
    }

    @Test fun authenticatedIdentityUsesInnerTubeSessionAndSelectedChannel() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"activeAccountHeaderRenderer":{"accountName":{"simpleText":"My channel"},"accountPhoto":{"thumbnails":[{"url":"https://img.test/channel.jpg"}]}}}"""))
            val identity = catalog(server).identity(session)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path.orEmpty().contains("account/account_menu"))
            assertTrue(request.getHeader("Authorization").orEmpty().startsWith("SAPISIDHASH "))
            assertEquals(session.cookie, request.getHeader("Cookie"))
            assertEquals("2", request.getHeader("X-Goog-AuthUser"))
            assertEquals("UCchannel", request.getHeader("X-Goog-PageId"))
            assertEquals("visitor-data", request.getHeader("X-Goog-Visitor-Id"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("delegated", body.getJSONObject("context").getJSONObject("user").getString("onBehalfOfUser"))
            assertEquals("My channel", identity.name)
            assertEquals("UCchannel", identity.channelId)
        }
    }

    @Test fun channelListIsReadBeforeSessionAcceptanceAndPreservesChoices() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"items":[{"accountItem":{"accountName":{"simpleText":"Personal"},"isSelected":true,"serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"pageIdToken":{"pageId":"UCpersonal"}},{"datasyncIdToken":{"datasyncIdToken":"personal||user"}}]}}}},{"accountItemRenderer":{"accountName":{"simpleText":"Brand"},"serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"pageIdToken":{"pageId":"UCbrand"}},{"datasyncIdToken":{"datasyncIdToken":"delegated-brand||user"}}]}}}}]}"""))
            val channels = catalog(server).accountChannels(session)
            val request = server.takeRequest()
            assertTrue(request.path.orEmpty().contains("account/accounts_list"))
            assertEquals(2, channels.size)
            assertEquals("UCbrand", channels[1].pageId)
            assertEquals("delegated-brand||user", channels[1].dataSyncId)
            assertTrue(request.body.readUtf8().contains("ACCOUNTS_LIST_REQUEST_TYPE_CHANNEL_SWITCHER"))
        }
    }

    @Test fun emptyChannelSwitcherFallsBackToAuthenticatedCurrentChannel() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"items":[]}"""))
            server.enqueue(MockResponse().setBody("""{"activeAccountHeaderRenderer":{"accountName":{"simpleText":"My current channel"},"accountPhoto":{"thumbnails":[{"url":"https://img.test/me.jpg"}]}}}"""))
            val channels = catalog(server).accountChannels(session)
            assertEquals(2, server.requestCount)
            assertEquals("account/accounts_list", server.takeRequest().requestUrl?.encodedPath?.substringAfter("youtubei/v1/"))
            assertEquals("account/account_menu", server.takeRequest().requestUrl?.encodedPath?.substringAfter("youtubei/v1/"))
            assertEquals(1, channels.size)
            assertEquals("My current channel", channels.single().title)
            assertEquals("UCchannel", channels.single().pageId)
            assertTrue(channels.single().selected)
        }
    }

    @Test fun signedInLibraryUsesInnerTubeBrowseAndPagination() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"header":{"musicDetailHeaderRenderer":{"title":{"runs":[{"text":"Playlists"}]}}},"contents":{"sectionListRenderer":{"contents":[]}},"continuation":"next"}"""))
            catalog(server).collection(AccountCollection.PLAYLISTS)
            val request = server.takeRequest()
            assertTrue(request.path.orEmpty().contains("browse"))
            assertEquals("SAPISID=mock-secret; LOGIN_INFO=mock-login", request.getHeader("Cookie"))
            assertTrue(request.body.readUtf8().contains("FEmusic_liked_playlists"))
        }
    }

    @Test fun malformedLibraryResponseDoesNotMasqueradeAsEmptyCollections() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            try { catalog(server).collection(AccountCollection.PLAYLISTS); fail("Unknown response must be an error") }
            catch (expected: java.io.IOException) { assertTrue(expected.message.orEmpty().contains("library response")) }
        }
    }

    @Test fun privatePlaylistReadsUseExplicitSessionAndRequestedPlaylist() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"contents":{"musicPlaylistShelfRenderer":{"contents":[]}}}"""))
            catalog(server, null).playlistFor(session, "PLprivate")
            val request = server.takeRequest()
            assertEquals(session.cookie, request.getHeader("Cookie"))
            assertEquals("VLPLprivate", JSONObject(request.body.readUtf8()).getString("browseId"))
        }
    }

    @Test fun alreadyNormalizedLikedPlaylistKeepsItsBrowseId() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"contents":{"musicPlaylistShelfRenderer":{"contents":[]}}}"""))
            catalog(server).playlistFor(session, "VLLM")
            assertEquals("VLLM", JSONObject(server.takeRequest().body.readUtf8()).getString("browseId"))
        }
    }

    @Test fun guestCatalogRequestDoesNotInheritAuthenticatedCookies() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            catalog(server).request("search", JSONObject().put("query", "ambient"))
            val request = server.takeRequest()
            assertNull(request.getHeader("Cookie"))
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("X-Goog-AuthUser"))
        }
    }

    @Test fun unauthorizedAuthenticatedRequestExpiresSession() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            try { catalog(server).identity(session); fail("401 must expire the account session") }
            catch (_: SessionExpiredException) { }
        }
    }

    @Test fun explicitLikeActionIsSentOnlyWithAuthenticatedSession() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":"STATUS_SUCCEEDED"}"""))
            catalog(server).setLiked("video123", true)
            val request = server.takeRequest()
            assertTrue(request.path.orEmpty().contains("like/like"))
            assertEquals("SAPISID=mock-secret; LOGIN_INFO=mock-login", request.getHeader("Cookie"))
            assertTrue(request.body.readUtf8().contains("video123"))
        }
    }

    @Test fun networkFailureIsReportedAndDoesNotBecomeGuestSuccess() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            try { catalog(server).identity(session); fail("Network disconnect must be reported") }
            catch (e: java.io.IOException) { assertTrue(e.message.orEmpty().contains("unavailable")) }
        }
    }

    @Test fun guestCannotReadAccountCollections() = runTest {
        MockWebServer().use { server ->
            try { catalog(server, null).collection(AccountCollection.PLAYLISTS); fail("Account collections require a session") }
            catch (_: SessionExpiredException) { }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun productionCatalogCannotBeConfiguredToSendCredentialsToAnotherHost() {
        try {
            OnlineCatalog(OkHttpClient(), { session }, "http://attacker.test/".toHttpUrl())
            fail("Credentials must be restricted to the YouTube Music origin")
        } catch (_: IllegalArgumentException) { }
    }
}
