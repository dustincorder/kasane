package cord.kasane.music

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.provider.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlaylistActionsTest {
    private val id="dQw4w9WgXcQ"
    private val account=AccountSession("SAPISID=synthetic-test-session",1,"UCtest","delegated-test")
    private fun option(playlist:String="PLowned",membership:String="NONE",actionVideo:String=id,action:String="ACTION_ADD_VIDEO") = JSONObject("""{
        "playlistAddToOptionRenderer":{"playlistId":"$playlist","title":{"simpleText":"My real destination"},
        "containsSelectedVideos":"$membership","privacy":"PRIVATE","addToPlaylistServiceEndpoint":{
        "playlistEditEndpoint":{"playlistId":"$playlist","actions":[{"action":"$action","addedVideoId":"$actionVideo"}]}}}}""")
    @Test fun pickerReadsDestinationsWithoutSendingAnAccountMutation() = runTest {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody(JSONObject().put("contents",org.json.JSONArray().put(option())).toString()))
            val catalog=OnlineCatalog(OkHttpClient(),{account},server.url("/"))
            val result=catalog.playlistDestinations(account,id)
            assertEquals("PLowned",result.single().id)
            assertFalse(result.single().alreadyAdded)
            val request=server.takeRequest()
            assertEquals("/youtubei/v1/playlist/get_add_to_playlist?prettyPrint=false",request.path)
            assertEquals(account.cookie,request.getHeader("Cookie"))
            assertEquals("1",request.getHeader("X-Goog-AuthUser"))
            val body=JSONObject(request.body.readUtf8())
            assertEquals(id,body.getJSONArray("videoIds").getString(0))
            assertFalse(body.has("actions"));assertEquals(1,server.requestCount)
        }
    }
    @Test fun parserRejectsWrongVideoAndDangerousOrMissingWriteCommands() {
        val rows=org.json.JSONArray().put(option(actionVideo="different01")).put(option(action="ACTION_REMOVE_VIDEO"))
            .put(option(playlist="LM")).put(option(playlist="PLvalid"))
        val destinations=PlaylistActionParser.destinations(JSONObject().put("contents",rows),id)
        assertEquals(listOf("PLvalid"),destinations.map {it.id})
    }
    @Test fun alreadyAddedDestinationsRemainReadOnlyAndDuplicatesAreCollapsed() {
        val rows=org.json.JSONArray().put(option(membership="ALL")).put(option(membership="ALL"))
        val result=PlaylistActionParser.destinations(JSONObject().put("contents",rows),id)
        assertEquals(1,result.size);assertTrue(result.single().alreadyAdded)
    }
    @Test fun unknownResponseIsAnErrorRatherThanAnEmptyAccountLibrary() {
        try {PlaylistActionParser.destinations(JSONObject("{}"),id);fail()}catch(_:IOException){}
    }
    @Test fun explicitAddSendsOnlyOneScopedVideoActionAndRequiresReceipt() = runTest {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody("""{"status":"STATUS_SUCCEEDED","playlistEditResults":[{"playlistEditVideoAddedResultData":{"videoId":"$id","setVideoId":"entry-instance"}}]}"""))
            OnlineCatalog(OkHttpClient(),{account},server.url("/")).addVideoToPlaylist(account,"VLPLowned",id)
            val request=server.takeRequest()
            assertEquals("/youtubei/v1/browse/edit_playlist?prettyPrint=false",request.path)
            val body=JSONObject(request.body.readUtf8())
            assertEquals("PLowned",body.getString("playlistId"))
            assertEquals(1,body.getJSONArray("actions").length())
            assertEquals("ACTION_ADD_VIDEO",body.getJSONArray("actions").getJSONObject(0).getString("action"))
            assertEquals(id,body.getJSONArray("actions").getJSONObject(0).getString("addedVideoId"))
            assertEquals(account.cookie,request.getHeader("Cookie"));assertEquals(1,server.requestCount)
        }
    }
    @Test fun emptySuccessOrWrongVideoReceiptDoesNotBecomeConfirmedAddition() {
        for(response in listOf("{}","""{"status":"STATUS_SUCCEEDED"}""",
            """{"playlistEditVideoAddedResultData":{"videoId":"different01","setVideoId":"entry"}}""",
            """{"status":"STATUS_FAILED","playlistEditVideoAddedResultData":{"videoId":"$id","setVideoId":"entry"}}""")) {
            try {PlaylistActionParser.requireAddReceipt(JSONObject(response),id);fail("Unconfirmed response")}
            catch(_:IOException){}
        }
    }
    @Test fun likedSongsAndInvalidIdsCannotBeEditedThroughPlaylistAction() = runTest {
        MockWebServer().use {server->
            val catalog=OnlineCatalog(OkHttpClient(),{account},server.url("/"))
            for(playlist in listOf("VLLM","LL","https://attacker.test")) {
                try {catalog.addVideoToPlaylist(account,playlist,id);fail()}catch(_:IllegalArgumentException){}
            }
            assertEquals(0,server.requestCount)
        }
    }
}
