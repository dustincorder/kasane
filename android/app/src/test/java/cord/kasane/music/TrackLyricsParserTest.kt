package cord.kasane.music

import cord.kasane.music.provider.TrackLyricsParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackLyricsParserTest {
    private fun json(raw: String) = Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject

    @Test fun lyricsTabYieldsItsBrowseId() {
        val next = json("""
            {"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":
              {"watchNextTabbedResultsRenderer":{"tabs":[
                {"tabRenderer":{"title":"UP NEXT","pageType":"MUSIC_PAGE_TYPE_TRACK_QUEUE"}},
                {"tabRenderer":{"title":"LYRICS","pageType":"MUSIC_PAGE_TYPE_TRACK_LYRICS",
                  "endpoint":{"browseEndpoint":{"browseId":"MPLYt_AbC123"}}}},
                {"tabRenderer":{"title":"RELATED","pageType":"MUSIC_PAGE_TYPE_TRACK_RELATED"}}
              ]}}}}}
        """)
        assertEquals("MPLYt_AbC123", TrackLyricsParser.lyricsBrowseId(next))
    }

    @Test fun trackWithoutLyricsTabReturnsNull() {
        val next = json("""{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":
            {"watchNextTabbedResultsRenderer":{"tabs":[
              {"tabRenderer":{"title":"UP NEXT","pageType":"MUSIC_PAGE_TYPE_TRACK_QUEUE"}}]}}}}}""")
        assertNull(TrackLyricsParser.lyricsBrowseId(next))
    }

    @Test fun lyricsPageExtractsTextAndSource() {
        val page = json("""
            {"contents":{"sectionListRenderer":{"contents":[
              {"musicDescriptionShelfRenderer":{
                 "description":{"runs":[{"text":"First line\nSecond line"}]},
                 "footer":{"runs":[{"text":"Source: Musixmatch"}]}}}
            ]}}}
        """)
        val lyrics = TrackLyricsParser.lyrics(page)
        assertEquals("First line\nSecond line", lyrics?.text)
        assertEquals("Musixmatch", lyrics?.source)
    }

    @Test fun blankDescriptionIsNoLyrics() {
        val page = json("""{"contents":{"sectionListRenderer":{"contents":[
            {"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"  "}]}}}]}}}""")
        assertNull(TrackLyricsParser.lyrics(page))
    }
}
