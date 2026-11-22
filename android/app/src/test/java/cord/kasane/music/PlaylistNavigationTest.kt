package cord.kasane.music

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import cord.kasane.music.auth.AccountIdentity
import cord.kasane.music.auth.AccountState
import cord.kasane.music.model.*
import cord.kasane.music.provider.AccountCollection
import cord.kasane.music.provider.AccountLibraryProvider
import cord.kasane.music.ui.AccountLibraryScreen
import cord.kasane.music.ui.CatalogDetailContent
import cord.kasane.music.ui.creditsFor
import cord.kasane.music.ui.theme.KasaneTheme
import cord.kasane.music.ui.withCover
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w412dp-h892dp")
class PlaylistNavigationTest {
    @get:Rule val compose = createComposeRule()

    private val personal = CatalogItem("VLPLmine", CatalogKind.PLAYLIST, "My playlist", "You")
    private val libraryProvider = object : AccountLibraryProvider {
        override suspend fun collection(collection: AccountCollection, continuation: String?) =
            CatalogPage("Playlists", "", null, listOf(CatalogShelf("Playlists", listOf(personal))))
        override suspend fun playlist(playlistId: String, continuation: String?) = error("Library must not load playlists inline.")
        override suspend fun setLiked(videoId: String, liked: Boolean) = Unit
    }

    @Test fun openingAPersonalPlaylistFromLibraryNavigatesInsteadOfRenderingInline() {
        val opened = mutableListOf<CatalogItem>()
        compose.setContent { KasaneTheme(dynamicColor = false) { Surface {
            AccountLibraryScreen(AccountState.SignedIn(AccountIdentity("Me", null, "UC1")), libraryProvider, {}, { opened += it }, {}, {})
        } } }
        compose.waitUntil(3000) { compose.onAllNodesWithText("My playlist").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("My playlist").performClick()
        compose.waitForIdle()
        assertEquals(listOf(personal), opened)
        compose.onNodeWithText("Back to playlists").assertDoesNotExist()
        compose.onNodeWithText("My playlist").assertExists() // still the list, not a second UI
    }

    private fun tracks(n: Int, art: String? = null) = CatalogShelf("Tracks", (1..n).map { CatalogItem("trk$it", CatalogKind.SONG, "Song $it", "Artist", art, 200.0) })

    @Test fun personalAndPublicPlaylistsRenderTheSameDetailScreen() {
        val cases = listOf(personal to "My playlist", CatalogItem("VLPLpublic", CatalogKind.PLAYLIST, "PilotRedSky", "Someone") to "PilotRedSky")
        var index by androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent { KasaneTheme(dynamicColor = false) { Surface {
            val (item, title) = cases[index]
            CatalogDetailContent(item, CatalogPage(title, "Someone · 2026", "https://yt3.googleusercontent.com/x=w226-h226", listOf(tracks(5))),
                false, null, false, {}, {}, {}, { _, _ -> }, {}, onLoadMore = {})
        } } }
        for (i in cases.indices) {
            index = i; compose.waitForIdle()
            compose.onNodeWithText("Play").assertExists(); compose.onNodeWithContentDescription("Shuffle play").assertExists()
            compose.onNodeWithText("5 tracks", substring = true).assertExists()
            compose.onNodeWithText("Song 1").assertExists()
        }
    }

    @Test fun largePlaylistsKeepLoadingWhileTheEndIsNear() {
        var loads = 0
        val page = CatalogPage("Big", "", null, listOf(tracks(6)), continuation = "next")
        compose.setContent { KasaneTheme(dynamicColor = false) { Surface {
            CatalogDetailContent(personal, page, false, null, false, {}, {}, {}, { _, _ -> }, {}, onLoadMore = { loads++ })
        } } }
        compose.waitForIdle()
        assertTrue("short list with a continuation must request more", loads >= 1)
    }

    @Test fun albumTracksWithoutArtworkShowAndQueueTheAlbumCover() {
        val cover = "https://lh3.googleusercontent.com/album=w544-h544"
        assertEquals(cover, Track("a", "T", "A").withCover(cover).thumbnailUri)
        assertEquals("own", Track("a", "T", "A", thumbnailUri = "own").withCover(cover).thumbnailUri)
        assertNull(Track("a", "T", "A").withCover(null).thumbnailUri)
    }

    private fun render(item: CatalogItem, page: CatalogPage, owner: AccountIdentity? = null, onOpen: (CatalogItem) -> Unit = {}) {
        compose.setContent { KasaneTheme(dynamicColor = false) { Surface {
            CatalogDetailContent(item, page, false, null, false, {}, {}, onOpen, { _, _ -> }, {}, owner = owner, onLoadMore = {})
        } } }
    }

    @Test fun albumShowsItsLinkedArtistsBelowTheTitleAndOpensThem() {
        val opened = mutableListOf<CatalogItem>()
        val page = CatalogPage("The Introduction", "Album • 2018", null, listOf(tracks(3)), creators = listOf(
            CatalogCreator("KIRA", "UCa", null), CatalogCreator("GUMI", "UCb", null)), stats = "11 songs • 37 minutes")
        render(CatalogItem("MPREb_x", CatalogKind.ALBUM, "The Introduction"), page, onOpen = { opened += it })
        compose.onNodeWithText("KIRA").assertExists(); compose.onNodeWithText("GUMI").assertExists()
        compose.onNodeWithText("Album • 2018").assertExists(); compose.onNodeWithText("11 songs • 37 minutes").assertExists()
        compose.onNodeWithText("GUMI").performClick()
        assertEquals(listOf("UCb"), opened.map { it.id }); assertEquals(CatalogKind.ARTIST, opened.single().kind)
    }

    @Test fun publicPlaylistShowsItsRealOwnerNotATrackArtist() {
        val page = CatalogPage("PilotRedSky", "Playlist • 2026", null, listOf(tracks(3)), creators = listOf(CatalogCreator("Phonk Time", "UC50", null, userChannel = true)))
        render(CatalogItem("VLPLpub", CatalogKind.PLAYLIST, "PilotRedSky"), page)
        compose.onNodeWithText("Phonk Time").assertExists(); compose.onNodeWithText("Made by YouTube Music").assertDoesNotExist()
    }

    @Test fun ownedPlaylistShowsMyProfileAndGeneratedMixesAreLabelled() {
        val me = AccountIdentity("Kasane Listener", null, "UCme")
        render(personal, CatalogPage("My playlist", "Playlist", null, listOf(tracks(3)), owned = true), owner = me)
        compose.onNodeWithText("Kasane Listener").assertExists()
        assertEquals(listOf(CatalogCreator("Kasane Listener", "UCme", null, userChannel = true)), creditsFor(personal, CatalogPage("x", "", null, emptyList(), owned = true), me).creators)
        assertTrue(creditsFor(CatalogItem("VLRDCLAK5uy_x", CatalogKind.PLAYLIST, "Mix"), CatalogPage("Mix", "", null, emptyList()), me).generated)
        assertTrue(creditsFor(personal, CatalogPage("x", "", null, emptyList()), me).creators.isEmpty())
        assertTrue("never invent: not owned and no creators", !creditsFor(personal, CatalogPage("x", "", null, emptyList()), me).generated)
    }
}
