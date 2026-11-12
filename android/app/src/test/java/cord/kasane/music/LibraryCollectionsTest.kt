package cord.kasane.music

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.provider.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryCollectionsTest {
    @Test fun allSupportedCollectionsUseTheirOwnAuthenticatedEndpointAndRealParsedItems() = runTest {
        MockWebServer().use { server ->
            server.start()
            val session = AccountSession("SAPISID=mock-only")
            val catalog = OnlineCatalog(OkHttpClient(), { session }, server.url("/"))
            for ((collection, id) in listOf(AccountCollection.PLAYLISTS to "VLPLmock", AccountCollection.ALBUMS to "MPREmock", AccountCollection.ARTISTS to "UCmock")) {
                server.enqueue(MockResponse().setBody("""{"gridRenderer":{"items":[{"musicTwoRowItemRenderer":{"title":{"simpleText":"Saved item"},"navigationEndpoint":{"browseEndpoint":{"browseId":"$id"}}}}]}}"""))
                val page = catalog.collection(collection)
                assertEquals(id, page.shelves.flatMap { it.items }.single().id)
                val request = server.takeRequest()
                assertEquals(session.cookie, request.getHeader("Cookie"))
                assertEquals(collection.browseId, JSONObject(request.body.readUtf8()).getString("browseId"))
            }
            server.enqueue(MockResponse().setBody("""{"musicPlaylistShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"wzpAehzNCU8"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Brand New Day"}]}}}]}}]}}"""))
            val playlist = catalog.playlist("VLPLmock")
            assertEquals("wzpAehzNCU8", playlist.tracks.single().id)
            assertEquals("VLPLmock", JSONObject(server.takeRequest().body.readUtf8()).getString("browseId"))
        }
    }
}
