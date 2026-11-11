package cord.kasane.music

import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.HistoryEntity
import cord.kasane.music.model.Track
import cord.kasane.music.ui.HomeFeedPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFeedPolicyTest {
    @Test fun quickPicksDoNotRepeatTheSameTrack() {
        val first = Track("same", "First title", "Artist")
        val duplicate = first.copy(title = "Duplicate metadata")
        assertEquals(listOf(first), HomeFeedPolicy.uniqueTracks(listOf(first, duplicate)))
    }

    @Test fun shelfRemovesDuplicatesButKeepsDifferentCatalogKinds() {
        val song = CatalogItem("same", CatalogKind.SONG, "Song")
        val duplicate = song.copy(title = "Duplicate song")
        val album = CatalogItem("same", CatalogKind.ALBUM, "Album")
        assertEquals(listOf(song, album), HomeFeedPolicy.uniqueShelfItems(listOf(song, duplicate, album)))
    }

    @Test fun duplicateAcrossDifferentShelvesRemainsEligibleInEachShelf() {
        val track = CatalogItem("same", CatalogKind.SONG, "Song")
        val quickPicks = HomeFeedPolicy.uniqueShelfItems(listOf(track))
        val listenAgain = HomeFeedPolicy.uniqueShelfItems(listOf(track))
        assertEquals(listOf(track), quickPicks)
        assertEquals(listOf(track), listenAgain)
    }

    @Test fun listenAgainKeepsNewestHistoryRowForEachVideoId() {
        val newest = HistoryEntity(id = 3, trackId = "same", title = "New", artist = "Artist", playedAt = 300)
        val olderDuplicate = HistoryEntity(id = 2, trackId = "same", title = "Old", artist = "Artist", playedAt = 200)
        val other = HistoryEntity(id = 1, trackId = "other", title = "Other", artist = "Artist", playedAt = 250)
        assertEquals(listOf(newest, other), HomeFeedPolicy.latestHistory(listOf(olderDuplicate, other, newest)))
    }
}
