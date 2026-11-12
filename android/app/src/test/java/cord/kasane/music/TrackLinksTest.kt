package cord.kasane.music

import cord.kasane.music.provider.TrackLinksParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrackLinksTest {
    @Test fun navigationUsesOnlyEndpointsBelongingToThatVideo() {
        val root = JSONObject("""{"rows":[{"playlistPanelVideoRenderer":{"videoId":"wzpAehzNCU8","title":{"simpleText":"Brand New Day"},"longBylineText":{"runs":[{"text":"BLits Games","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}},{"text":"Album","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREalbum"}}}]} }},{"playlistPanelVideoRenderer":{"videoId":"dQw4w9WgXcQ","navigationEndpoint":{"browseEndpoint":{"browseId":"UCdifferent"}}}}]}""")
        val links = TrackLinksParser.parse(root)
        assertEquals("UCartist", links["wzpAehzNCU8"]?.artist?.id)
        assertEquals("MPREalbum", links["wzpAehzNCU8"]?.album?.id)
        assertEquals("UCdifferent", links["dQw4w9WgXcQ"]?.artist?.id)
    }
    @Test fun missingEndpointNeverBecomesTitleBasedGuess() {
        val root = JSONObject("""{"playlistPanelVideoRenderer":{"videoId":"wzpAehzNCU8","title":{"simpleText":"Brand New Day"},"longBylineText":{"simpleText":"BLits Games"}}}""")
        assertTrue(TrackLinksParser.parse(root).isEmpty())
    }
}
