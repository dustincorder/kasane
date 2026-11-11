package cord.kasane.music

import cord.kasane.music.model.*
import cord.kasane.music.provider.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogParserTest {
    private fun song(id: String) = """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song $id","navigationEndpoint":{"watchEndpoint":{"videoId":"$id"}}}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"}]}}}],"playlistItemData":{"videoId":"$id"}}}"""
    @Test fun parsesPlayableSongsAndDeduplicates() {
        val root = JSONObject("""{"contents":[${song("one")},${song("one")},${song("two")}]}""")
        val page = CatalogParser.search(root, CatalogKind.SONG)
        assertEquals(listOf("one", "two"), page.tracks.map { it.id })
    }
    @Test fun artistBrowseIdIsNeverPlayable() {
        val root = JSONObject("""{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Artist"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCexample","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}}""")
        val artist = CatalogParser.item(root)!!
        assertEquals(CatalogKind.ARTIST, artist.kind)
        assertNull(artist.track())
    }
    @Test fun subtitleArtistDoesNotReplaceSongIdentity() {
        val row = JSONObject(song("actual-song"))
        val body = row.getJSONObject("musicResponsiveListItemRenderer")
        body.getJSONArray("flexColumns").getJSONObject(1).getJSONObject("musicResponsiveListItemFlexColumnRenderer")
            .getJSONObject("text").getJSONArray("runs").getJSONObject(0)
            .put("navigationEndpoint", JSONObject("""{"browseEndpoint":{"browseId":"UCrelated"}}"""))
        assertEquals("actual-song", CatalogParser.item(row)!!.track()!!.id)
    }
    @Test fun preservesServerShelfTitlesAndSkipsEmptyShelves() {
        val page = CatalogParser.page(JSONObject("""{"contents":[{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Your daily music"}]}}},"contents":[${song("a")}]}},{"musicShelfRenderer":{"title":{"runs":[{"text":"Empty"}]},"contents":[]}}]}"""))
        assertEquals(1, page.shelves.size)
        assertEquals("Your daily music", page.shelves.single().title)
    }
    @Test fun continuationIsExtractedWithoutTreatingItAsTrack() {
        val root = JSONObject("""{"continuations":[{"nextContinuationData":{"continuation":"next-page"}}]}""")
        assertEquals("next-page", CatalogParser.continuation(root))
        assertTrue(CatalogParser.page(root).tracks.isEmpty())
    }
    @Test fun requestedVideosStayVideoType() {
        assertEquals(CatalogKind.VIDEO, CatalogParser.search(JSONObject("""{"contents":[${song("video")}]}"""), CatalogKind.VIDEO).shelves.single().items.single().kind)
    }
    @Test fun playlistDoesNotUseRecommendationContinuation() {
        val root = JSONObject("""{"contents":[{"musicPlaylistShelfRenderer":{"contents":[${song("a")}]}},{"continuations":[{"nextContinuationData":{"continuation":"recommendations-only"}}]}]}""")
        assertNull(CatalogParser.page(root).continuation)
    }
    @Test fun playlistUsesItsOwnContinuation() {
        val root = JSONObject("""{"contents":[{"musicPlaylistShelfRenderer":{"contents":[${song("a")}],"continuations":[{"nextContinuationData":{"continuation":"actual-tracks"}}]}}]}""")
        assertEquals("actual-tracks", CatalogParser.page(root).continuation)
    }
    @Test fun unknownBrowseTypesAreNotInvented() {
        val node = JSONObject("""{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Unknown"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"UNKNOWN"}}}}""")
        assertNull(CatalogParser.item(node))
    }
}
