package cord.kasane.music

import cord.kasane.music.model.CatalogKind
import cord.kasane.music.provider.CatalogParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThumbnailRegressionTest {
    private fun fixture() = JSONObject(javaClass.getResource("/thumbnail-renderers.json")!!.readText())
    @Test fun realSearchSongKeepsArtworkThroughTrackConversion() {
        val row = fixture().getJSONObject("songs")
        val expected = row.getJSONObject("musicResponsiveListItemRenderer").getJSONObject("thumbnail")
            .getJSONObject("musicThumbnailRenderer").getJSONObject("thumbnail").getJSONArray("thumbnails")
            .getJSONObject(1).getString("url")
        val page = CatalogParser.search(JSONObject().put("contents", org.json.JSONArray().put(row)), CatalogKind.SONG)
        assertEquals(expected, page.tracks.single().thumbnailUri)
    }
    @Test fun realHomeQuickPickKeepsArtwork() {
        val item = CatalogParser.item(fixture().getJSONObject("home"))!!
        assertNotNull(item.artwork)
        assertEquals(item.artwork, item.track()!!.thumbnailUri)
    }
    @Test fun realPlaylistStillHasArtwork() {
        assertNotNull(CatalogParser.item(fixture().getJSONObject("playlist"))!!.artwork)
    }
}
