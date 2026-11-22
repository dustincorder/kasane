package cord.kasane.music

import cord.kasane.music.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.*

class CatalogModelTest {
    @Test fun nonTrackBrowseIdentifiersNeverBecomePlayableTracks() {
        for (kind in listOf(CatalogKind.ALBUM, CatalogKind.ARTIST, CatalogKind.PLAYLIST)) {
            assertNull(CatalogItem("browse-id", kind, "Collection").track())
        }
        assertEquals("video", CatalogItem("video", CatalogKind.SONG, "Song", "Artist").track()?.id)
    }
    @Test fun replayAndRadioMetadataSurvivePortableSerialization() {
        val track = Track("fixture", "Long title", "Artist", "Album", 213.5, "https://images.invalid/cover.jpg")
        assertEquals(track, Json.decodeFromString<Track>(Json.encodeToString(track)))
        val oldRecord = """{"id":"existing","title":"Stored title","artist":"Stored artist"}"""
        val restored = Json.decodeFromString<Track>(oldRecord)
        assertEquals("youtube", restored.sourceProvider)
        assertFalse(restored.isLocal)
    }
    @Test fun pageTracksDeduplicateIdsWithoutFlatteningItsShelves() {
        val item = CatalogItem("video", CatalogKind.SONG, "Song", "Artist")
        val page = CatalogPage("Home", "", null, listOf(CatalogShelf("First", listOf(item)), CatalogShelf("Second", listOf(item))))
        assertEquals(1, page.tracks.size)
        assertEquals(2, page.shelves.size)
        assertEquals(page, Json.decodeFromString<CatalogPage>(Json.encodeToString(page)))
    }
    @Test fun radioExcludesExistingIdsAndRespectsCapacity() {
        val candidates = listOf(Track("playing", "Playing", "Artist"), Track("next", "Next", "Artist"), Track("other", "Other", "Artist"))
        assertEquals(listOf("next"), cord.kasane.music.playback.QueuePolicy.radioAppend(setOf("playing"), candidates, 1).map { it.id })
    }
}

class HomeChipsParserTest {
    @Test fun parsesMoodChipsAndSeeAllLinksFromAHomeResponse() {
        val json = """{"contents":{"chipCloudRenderer":{"chips":[
            {"chipCloudChipRenderer":{"text":{"runs":[{"text":"Focus"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"FEmusic_home","params":"ggMPOg1"}}}},
            {"chipCloudChipRenderer":{"text":{"runs":[{"text":"NoToken"}]},"navigationEndpoint":{}}},
            {"chipCloudChipRenderer":{"text":{"runs":[{"text":"Focus"}]},"navigationEndpoint":{"browseEndpoint":{"params":"ggMPOg1"}}}}]}}}"""
        val chips = cord.kasane.music.provider.CatalogJsonParser.chips(kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject)
        assertEquals(listOf(cord.kasane.music.model.CatalogChip("Focus", "ggMPOg1")), chips)
    }
}
