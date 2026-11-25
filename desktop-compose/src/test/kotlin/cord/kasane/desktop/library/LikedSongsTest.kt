package cord.kasane.desktop.library

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.CatalogPage
import cord.kasane.music.model.CatalogShelf
import cord.kasane.music.model.Track
import cord.kasane.music.provider.CloudLikeState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Paging and rating-sync for the account's Liked Music playlist, against a fake catalog. */
@OptIn(ExperimentalCoroutinesApi::class)
class LikedSongsTest {
    private fun track(id: String) = Track(id, "Song $id", "Artist")
    private fun page(vararg ids: String, continuation: String? = null) =
        CatalogPage("Liked Music", "", null,
            listOf(CatalogShelf("Songs", ids.map { CatalogItem(it, CatalogKind.SONG, "Song $it", "Artist") })), continuation)

    private fun session() = AccountSession("SAPISID=x")

    private inner class Rig(scope: kotlinx.coroutines.CoroutineScope, var enabled: Boolean = true) {
        val rating = mutableMapOf<String, CloudLikeState>()
        val writes = mutableListOf<Triple<String, Boolean, Boolean>>() // id, like, dislike
        var nextPage: CatalogPage? = null
        val pages = mutableListOf<String?>()
        val likes = DesktopLikes(scope, { AccountSession("SAPISID=x") },
            read = { _, id -> rating[id] ?: CloudLikeState.NEUTRAL },
            write = { _, id, liked -> writes += Triple(id, liked, false); rating[id] = if (liked) CloudLikeState.LIKED else CloudLikeState.NEUTRAL },
            writeDislike = { _, id, disliked -> writes += Triple(id, false, disliked); rating[id] = if (disliked) CloudLikeState.DISLIKED else CloudLikeState.NEUTRAL })
        val liked = LikedSongs(scope, likes, loadPage = { cont -> pages += cont; nextPage ?: page() }, enabled = { enabled })
    }

    @Test fun refreshLoadsTheFirstPageAndLoadMoreAppends() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(backgroundScope)
        r.nextPage = page("a", "b", continuation = "c1")
        r.liked.refresh()
        assertEquals(listOf("a", "b"), r.liked.tracks.value.map { it.id })
        assertTrue(r.liked.hasMore.value)
        r.nextPage = page("c", "a") // a server repeat must not duplicate
        r.liked.loadMore()
        assertEquals(listOf("a", "b", "c"), r.liked.tracks.value.map { it.id })
        assertTrue(!r.liked.hasMore.value) // same continuation returned -> the feed ended
        assertEquals(listOf(null, "c1"), r.pages)
    }

    @Test fun anUnlikedRowLeavesTheListAndANewLikePreprends() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(backgroundScope)
        r.nextPage = page("a", "b")
        r.liked.refresh()
        r.likes.toggle(track("a")) // unlike via the same path the menu uses
        assertEquals(listOf("b"), r.liked.tracks.value.map { it.id })
        r.likes.toggle(track("fresh")) // a like outside the list inserts at the top
        assertEquals(listOf("fresh", "b"), r.liked.tracks.value.map { it.id })
    }

    @Test fun aDislikeRemovesTheRow() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(backgroundScope)
        r.nextPage = page("a", "b")
        r.liked.refresh()
        r.likes.toggleDislike(track("a"))
        assertEquals(listOf("b"), r.liked.tracks.value.map { it.id })
    }

    @Test fun playlistRowsSeedTheRatingMap() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(backgroundScope)
        r.nextPage = page("a", "b")
        r.liked.refresh()
        assertEquals(CloudLikeState.LIKED, r.likes.states.value["a"])
    }

    @Test fun guestsNeverLoadAndClearStaysEmpty() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(backgroundScope, enabled = false)
        r.nextPage = page("a")
        r.liked.refresh()
        assertTrue(r.liked.tracks.value.isEmpty())
        assertTrue(r.pages.isEmpty())
    }

    @Test fun aFailedPageBecomesAnErrorWithRetry() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(backgroundScope)
        var boom = true
        val liked = LikedSongs(backgroundScope, r.likes,
            loadPage = { if (boom) throw java.io.IOException("down") else page("a") }, enabled = { true })
        liked.refresh()
        assertEquals("Couldn't load your liked songs. Check your connection and try again.", liked.error.value)
        boom = false
        liked.refresh()
        assertEquals(null, liked.error.value)
        assertEquals(listOf("a"), liked.tracks.value.map { it.id })
    }

    @Test fun clearThenRefreshReloads() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(backgroundScope)
        r.nextPage = page("a")
        r.liked.refresh()
        r.liked.clear()
        assertTrue(r.liked.tracks.value.isEmpty())
        r.liked.refresh()
        assertEquals(listOf("a"), r.liked.tracks.value.map { it.id })
        assertEquals(listOf<String?>(null, null), r.pages)
    }

    @Test fun signInStateIsReadAtRefreshTime() = runTest(UnconfinedTestDispatcher()) {
        var signedIn = false
        val r = Rig(backgroundScope)
        val liked = LikedSongs(backgroundScope, r.likes, loadPage = { page("a") }, enabled = { signedIn })
        liked.refresh()
        assertTrue(liked.tracks.value.isEmpty())
        signedIn = true
        liked.refresh()
        assertEquals(listOf("a"), liked.tracks.value.map { it.id })
    }
}
