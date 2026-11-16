package cord.kasane.music

import cord.kasane.music.model.CatalogKind
import cord.kasane.music.provider.CatalogJsonParser
import cord.kasane.music.provider.ThumbnailJsonParser
import kotlinx.serialization.json.*
import kotlin.test.*

class CatalogJsonParserTest {
    private fun node(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun song(id: String) = """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Песня $id","navigationEndpoint":{"watchEndpoint":{"videoId":"$id"}}}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Artist"}}}],"thumbnail":{"thumbnails":[{"url":"//images.invalid/$id=w120-h120?x=1&y=2"}]}}}"""

    @Test fun portableSearchPreservesUnicodeArtworkAndVideoIdentity() {
        val root = node("""{"contents":[${song("one")},${song("one")},${song("two")}]}""")
        val page = CatalogJsonParser.search(root, CatalogKind.VIDEO)
        assertEquals(listOf("one", "two"), page.tracks.map { it.id })
        assertEquals("Песня one", page.tracks.first().title)
        assertEquals("https://images.invalid/one=w120-h120?x=1&y=2", page.tracks.first().thumbnailUri)
        assertTrue(page.shelves.single().items.all { it.kind == CatalogKind.VIDEO })
    }
    @Test fun collectionBrowseEndpointsNeverBecomePlayableRows() {
        for ((id, kind) in listOf("UCartist" to CatalogKind.ARTIST, "MPREalbum" to CatalogKind.ALBUM, "VLplaylist" to CatalogKind.PLAYLIST)) {
            val root = node("""{"musicTwoRowItemRenderer":{"title":{"simpleText":"Collection"},"navigationEndpoint":{"browseEndpoint":{"browseId":"$id"}}}}""")
            val item = assertNotNull(CatalogJsonParser.item(root))
            assertEquals(kind, item.kind)
            assertNull(item.track())
        }
    }
    @Test fun horizontalShelvesKeepServerNamesAndDuplicatesOnlyWithinTheirOwnShelfAreRemoved() {
        val root = node("""{"contents":[{"musicCarouselShelfRenderer":{"title":{"simpleText":"Quick picks"},"contents":[${song("one")},${song("one")}]}},{"musicCarouselShelfRenderer":{"title":{"simpleText":"Listen again"},"contents":[${song("one")}]}},{"musicShelfRenderer":{"title":{"simpleText":"Empty"},"contents":[]}}]}""")
        val page = CatalogJsonParser.page(root)
        assertEquals(listOf("Quick picks", "Listen again"), page.shelves.map { it.title })
        assertEquals(listOf(1, 1), page.shelves.map { it.items.size })
    }
    @Test fun recommendationTokenCannotExtendPlaylistTracks() {
        val root = node("""{"contents":[{"musicPlaylistShelfRenderer":{"contents":[${song("one")}]}},{"continuations":[{"nextContinuationData":{"continuation":"recommendations"}}]}]}""")
        assertNull(CatalogJsonParser.page(root).continuation)
    }
    @Test fun radioRowsCarryArtworkAndTheirOwnContinuation() {
        val root = node("""{"contents":[{"playlistPanelVideoRenderer":{"videoId":"fixture0001","title":{"simpleText":"Song"},"longBylineText":{"simpleText":"Artist"},"thumbnail":{"thumbnails":[{"url":"https://images.invalid/radio.jpg"}]}}}],"continuations":[{"nextRadioContinuationData":{"continuation":"radio-page"}}]}""")
        val radio = CatalogJsonParser.radio(root)
        assertEquals("radio-page", radio.continuation)
        assertEquals("fixture0001", radio.tracks.single().id)
        assertEquals("https://images.invalid/radio.jpg", radio.tracks.single().thumbnailUri)
    }
    @Test fun likeStateIsScopedToTheRequestedVideoAndUnknownIsNotNeutral() {
        val root = node("""{"items":[{"likeButtonRenderer":{"target":{"videoId":"other"},"likeStatus":"LIKE"}},{"likeButtonRenderer":{"target":{"videoId":"selected"},"likeStatus":"DISLIKE"}}]}""")
        assertEquals(cord.kasane.music.provider.CloudLikeState.DISLIKED,
            cord.kasane.music.provider.CloudLikeJsonParser.parse(root, "selected"))
        assertEquals(cord.kasane.music.provider.CloudLikeState.UNKNOWN,
            cord.kasane.music.provider.CloudLikeJsonParser.parse(root, "missing"))
    }
    @Test fun malformedFieldsAndJsonNullDoNotInventMetadata() {
        val root = node("""{"musicTwoRowItemRenderer":{"title":null,"navigationEndpoint":42},"unrelated":{"url":"https://images.invalid/wrong.jpg"}}""")
        assertNull(CatalogJsonParser.item(root))
        assertNull(ThumbnailJsonParser.bestUrl(root))
        assertTrue(CatalogJsonParser.page(root).tracks.isEmpty())
    }
    @Test fun artworkSelectsLargestValidCandidateAndRejectsCredentialBearingUris() {
        val images = Json.parseToJsonElement("""[{"url":"https://user:password@images.invalid/private.jpg","width":1000,"height":1000},{"url":"https://images.invalid/large.jpg","width":544,"height":544},{"url":"https://images.invalid/small.jpg","width":60,"height":60},{"url":null,"width":2000,"height":2000}]""")
        assertEquals("https://images.invalid/large.jpg", ThumbnailJsonParser.bestUrl(images))
        assertNull(ThumbnailJsonParser.bestUrl(node("""{"url":"file:///fixture.jpg"}""")))
        assertNull(ThumbnailJsonParser.bestUrl(node("""{"url":"https://"}""")))
    }
}
