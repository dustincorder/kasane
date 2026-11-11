package cord.kasane.music

import cord.kasane.music.provider.ThumbnailParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThumbnailParserTest {
    @Test fun handlesRepeatedThumbnailWrappers() {
        val node = JSONObject("""{"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.test/song.jpg","width":120,"height":120}]}}}}""")
        assertEquals("https://example.test/song.jpg", ThumbnailParser.bestUrl(node))
    }
    @Test fun handlesTwoRowWrapper() {
        assertEquals("https://example.test/list.jpg", ThumbnailParser.bestUrl(JSONObject(
            """{"thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.test/list.jpg"}]}}}}""")))
    }
    @Test fun handlesDirectThumbnailObjectAndArray() {
        val array = JSONArray("""[{"url":"https://example.test/a.jpg"}]""")
        assertEquals("https://example.test/a.jpg", ThumbnailParser.bestUrl(array))
        assertEquals("https://example.test/a.jpg", ThumbnailParser.bestUrl(array.getJSONObject(0)))
    }
    @Test fun choosesLargestValidSizeInsteadOfLastEntry() {
        val array = JSONArray("""[{"url":"https://example.test/large.jpg","width":544,"height":544},{"url":"https://example.test/small.jpg","width":60,"height":60},{"url":"","width":1000,"height":1000}]""")
        assertEquals("https://example.test/large.jpg", ThumbnailParser.bestUrl(array))
    }
    @Test fun skipsNullAndMalformedUrls() {
        val array = JSONArray("""[null,{"url":null},{"url":"null"},{"url":"data:image/png;base64,abcd"},{"url":"/relative.jpg"},{"url":"https://"},{"url":" https://example.test/real.jpg "}]""")
        assertEquals("https://example.test/real.jpg", ThumbnailParser.bestUrl(array))
    }
    @Test fun normalizesProtocolRelativeAndCleartextUrlsToHttps() {
        assertEquals("https://example.test/a.jpg?size=120", ThumbnailParser.bestUrl(JSONObject("""{"url":"//example.test/a.jpg?size=120"}""")))
        assertEquals("https://example.test/a.jpg", ThumbnailParser.bestUrl(JSONObject("""{"url":"http://example.test/a.jpg"}""")))
    }
    @Test fun noThumbnailsMeansNoInventedArtwork() {
        assertNull(ThumbnailParser.bestUrl(JSONObject("{}")))
        assertNull(ThumbnailParser.bestUrl(null))
        assertNull(ThumbnailParser.bestUrl(JSONObject("""{"thumbnails":[]}""")))
    }
    @Test fun doesNotPickUnrelatedMenuImages() {
        assertNull(ThumbnailParser.bestUrl(JSONObject("""{"menu":{"thumbnail":{"thumbnails":[{"url":"https://example.test/unrelated.jpg"}]}}}""")))
    }
    @Test fun preservesSignedImageParameters() {
        assertEquals("https://example.test/a=w120-h120?x=1&y=2", ThumbnailParser.bestUrl(JSONObject("""{"url":"https://example.test/a=w120-h120?x=1&y=2"}""")))
    }
}
