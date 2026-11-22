package cord.kasane.music

import cord.kasane.music.data.PlayStat
import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.CatalogShelf
import cord.kasane.music.model.HistoryEntity
import cord.kasane.music.model.Track
import cord.kasane.music.ui.HomeComposer
import cord.kasane.music.ui.HomeSection
import org.junit.Assert.*
import org.junit.Test

class HomeComposerTest {
    private fun song(id: String) = CatalogItem(id, CatalogKind.SONG, "Song $id", "Artist $id")
    private fun album(id: String) = CatalogItem(id, CatalogKind.ALBUM, "Album $id", "Artist")
    private fun artist(id: String) = CatalogItem(id, CatalogKind.ARTIST, "Artist $id")
    private fun stat(id: String, plays: Int, artist: String = "Artist $id") = PlayStat(id, "Song $id", artist, null, null, plays, plays.toLong())
    private fun play(id: String, at: Long) = HistoryEntity(trackId = id, title = "Song $id", artist = "Artist $id", playedAt = at)
    private fun songIds(sections: List<HomeSection>) = sections.flatMap { s ->
        when (s) {
            is HomeSection.SpeedDial -> s.tracks.map { it.id }; is HomeSection.SongGrid -> s.tracks.map { it.id }
            is HomeSection.RecentTracks -> s.tracks.map { it.id }; is HomeSection.SavedOffline -> s.tracks.map { it.id }
            else -> emptyList()
        }
    }

    @Test fun aSongNeverAppearsInTwoSongSections() {
        val remote = listOf(CatalogShelf("Quick picks", listOf("a", "b", "c", "d").map(::song)),
            CatalogShelf("More songs", listOf("a", "e", "f", "g").map(::song)))
        val top = listOf(stat("a", 9), stat("x", 5), stat("y", 4), stat("z", 3))
        val history = listOf(play("b", 10), play("q", 9), play("r", 8), play("s", 7))
        val saved = listOf(Track("x", "Song x", "A"), Track("t", "Song t", "A"), Track("u", "Song u", "A"))
        val ids = songIds(HomeComposer.build(remote, top, history, saved, offlineOnly = false))
        assertEquals("duplicates: $ids", ids.size, ids.toSet().size)
    }

    @Test fun personalShortcutsComeFirstAndNeedRepeatListens() {
        val top = listOf(stat("a", 9), stat("b", 5), stat("c", 4), stat("d", 2), stat("once", 1))
        val sections = HomeComposer.build(emptyList(), top, emptyList(), emptyList(), false)
        val dial = sections.first() as HomeSection.SpeedDial
        assertEquals(listOf("a", "b", "c", "d"), dial.tracks.map { it.id })
        assertTrue(HomeComposer.build(emptyList(), top.take(3), emptyList(), emptyList(), false).none { it is HomeSection.SpeedDial })
    }

    @Test fun emptyHistoryFallsBackToTheYoutubeMusicFeedOnly() {
        val remote = listOf(CatalogShelf("Quick picks", listOf("a", "b", "c").map(::song)),
            CatalogShelf("Albums for you", listOf("m", "n").map(::album)), CatalogShelf("Artists", listOf("p", "q").map(::artist)))
        val sections = HomeComposer.build(remote, emptyList(), emptyList(), emptyList(), false)
        assertEquals(listOf(HomeSection.SongGrid::class, HomeSection.Collections::class, HomeSection.Artists::class), sections.map { it::class })
        assertTrue(HomeComposer.build(emptyList(), emptyList(), emptyList(), emptyList(), false).isEmpty())
    }

    @Test fun offlineOnlyShowsLocalMusicAndNoRemoteShelves() {
        val remote = listOf(CatalogShelf("Quick picks", listOf("a", "b", "c").map(::song)))
        val sections = HomeComposer.build(remote, emptyList(), listOf(play("h", 1), play("i", 2), play("j", 3)), listOf(Track("s", "Saved", "A")), offlineOnly = true)
        assertTrue(sections.first() is HomeSection.SavedOffline)
        assertTrue(sections.none { it is HomeSection.SongGrid || it is HomeSection.Collections })
    }

    @Test fun radioSeedsUseDistinctArtistsAndSmallShelvesAreDropped() {
        val top = listOf(stat("a", 9, "Same"), stat("b", 8, "Same"), stat("c", 7, "Other"), stat("d", 6, "Third"))
        val sections = HomeComposer.build(listOf(CatalogShelf("Tiny", listOf(song("z")))), top, emptyList(), emptyList(), false)
        val radio = sections.filterIsInstance<HomeSection.RadioSeeds>().single()
        assertEquals(radio.seeds.size, radio.seeds.map { it.artist }.toSet().size)
        assertTrue(sections.none { it.title == "Tiny" })
    }

    @Test fun videosKeepTheirOwnSectionAndUntitledShelvesAreSkipped() {
        val videos = listOf("v1", "v2", "v3").map { CatalogItem(it, CatalogKind.VIDEO, "Video $it") }
        val sections = HomeComposer.build(listOf(CatalogShelf("Music videos", videos), CatalogShelf("", listOf("m", "n").map(::album))),
            emptyList(), emptyList(), emptyList(), false)
        assertEquals(1, sections.size); assertTrue(sections.single() is HomeSection.Videos)
    }
}

class SearchHistoryPolicyTest {
    @Test fun recentSearchesAreNewestFirstDeduplicatedAndBounded() {
        val p = cord.kasane.music.settings.SearchHistoryPolicy
        var list = emptyList<String>()
        (1..10).forEach { list = p.add(list, "query $it") }
        assertEquals(p.LIMIT, list.size); assertEquals("query 10", list.first())
        list = p.add(list, "QUERY 5")
        assertEquals("QUERY 5", list.first()); assertEquals(1, list.count { it.equals("query 5", true) })
        assertEquals(list, p.add(list, "   ")); assertFalse(p.add(list, "a\nb").first().contains('\n'))
    }
}
