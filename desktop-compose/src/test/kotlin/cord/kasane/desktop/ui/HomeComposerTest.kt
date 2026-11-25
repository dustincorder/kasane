package cord.kasane.desktop.ui

import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.CatalogShelf
import cord.kasane.music.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Android Home parity rules: lead shelf -> Quick picks, dedup across sections, local blocks after two remote shelves. */
class HomeComposerTest {
    private fun song(id: String, title: String = "S$id") = CatalogItem(id, CatalogKind.SONG, title, "Artist")
    private fun track(id: String, artist: String = "Artist") = Track(id, "S$id", artist)
    private fun shelf(title: String, items: List<CatalogItem>) = CatalogShelf(title, items)

    @Test fun theLeadSongShelfBecomesQuickPicks() {
        val sections = HomeComposer.build(
            listOf(shelf("Your picks", (1..6).map { song("q$it") }), shelf("Albums", (1..3).map { CatalogItem("al$it", CatalogKind.ALBUM, "A$it") })),
            topPlayed = emptyList(), recent = emptyList(), liked = emptyList(), local = true)
        val first = sections.first()
        assertIs<DesktopHomeSection.QuickPicks>(first)
        assertEquals("Your picks", first.title)
        assertEquals(6, first.items.size)
    }

    @Test fun aTrackAppearsInExactlyOneSection() {
        val remote = listOf(
            shelf("Quick picks", listOf(song("x"), song("y"), song("z"))),
            shelf("Song mixes", listOf(song("x"), song("m1"), song("m2"), song("m3"))))
        val sections = HomeComposer.build(remote, emptyList(), emptyList(), emptyList())
        val rows = sections.filterIsInstance<DesktopHomeSection.TrackRows>()
        assertEquals(1, rows.size)
        assertEquals(listOf("m1", "m2", "m3"), rows.single().tracks.map { it.id }) // "x" stayed in Quick picks
    }

    @Test fun localHistoryFeedsQuickPicksAndRecent() {
        val sections = HomeComposer.build(
            listOf(shelf("Picks", (1..4).map { song("p$it") }), shelf("Albums", (1..3).map { CatalogItem("a$it", CatalogKind.ALBUM, "A$it") }), shelf("More albums", (4..6).map { CatalogItem("a$it", CatalogKind.ALBUM, "A$it") })),
            topPlayed = (1..3).map { track("t$it", "Artist $it") },
            recent = (1..4).map { track("r$it") },
            liked = listOf(track("l1")),
            local = true)
        val quick = sections.filterIsInstance<DesktopHomeSection.QuickPicks>().single()
        assertTrue(quick.items.any { it.id == "t1" } && quick.items.any { it.id == "l1" })
        val recent = sections.filterIsInstance<DesktopHomeSection.RecentTracks>().single()
        assertEquals(4, recent.tracks.size)
        // Local sections arrive after the second remote section, like on Android.
        val keys = sections.map { it.key }
        assertTrue(keys.indexOf("recent") > keys.indexOf("remote:1"))
    }

    @Test fun aMoodChipHidesLocalSectionsEntirely() {
        val sections = HomeComposer.build(
            listOf(shelf("Focus", (1..4).map { song("f$it") })),
            topPlayed = listOf(track("t1")), recent = listOf(track("r1")), liked = listOf(track("l1")),
            local = false)
        assertTrue(sections.none { it is DesktopHomeSection.RecentTracks || it is DesktopHomeSection.RadioSeeds })
        val quick = sections.filterIsInstance<DesktopHomeSection.QuickPicks>().single()
        assertTrue(quick.items.none { it.id == "t1" || it.id == "l1" || it.id == "r1" })
    }

    @Test fun mixedShelvesStayCardsNotRows() {
        val remote = listOf(shelf("Listen again", listOf(
            song("s1"), CatalogItem("pl1", CatalogKind.PLAYLIST, "P"), CatalogItem("al1", CatalogKind.ALBUM, "A"))))
        val sections = HomeComposer.build(remote, emptyList(), emptyList(), emptyList())
        // A shelf under half songs is not the lead; it classifies as a mixed cover row.
        assertEquals(1, sections.size)
        assertIs<DesktopHomeSection.Cards>(sections.single())
        assertEquals("Listen again", sections.single().title)
    }

    @Test fun radioSeedsNeedTwoDistinctArtists() {
        val sections = HomeComposer.build(
            listOf(shelf("Picks", (1..3).map { song("p$it") })),
            topPlayed = listOf(track("a", "One"), track("b", "Two"), track("c", "Two")),
            recent = emptyList(), liked = emptyList(), local = true)
        val seeds = sections.filterIsInstance<DesktopHomeSection.RadioSeeds>().single()
        assertEquals(2, seeds.seeds.size)
        assertEquals(listOf("One", "Two"), seeds.seeds.map { it.artist }.distinct())

        val oneArtist = HomeComposer.build(listOf(shelf("Picks", (1..3).map { song("q$it") })),
            topPlayed = listOf(track("a", "One")), recent = emptyList(), liked = emptyList(), local = true)
        assertTrue(oneArtist.none { it is DesktopHomeSection.RadioSeeds })
    }
}
