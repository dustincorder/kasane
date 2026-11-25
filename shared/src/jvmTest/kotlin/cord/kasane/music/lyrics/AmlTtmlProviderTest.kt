package cord.kasane.music.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmlTtmlProviderTest {

    private val index = buildString {
        append("""{"metadata":[["musicName",["Song"]],["artists",["The Artist","Someone"]],["album",["Album"]]],"rawLyricFile":"a1.ttml"}""").append('\n')
        append("""{"metadata":[["musicName",["Song"]],["artists",["Other Band"]],["album",["X"]]],"rawLyricFile":"a2.ttml"}""").append('\n')
        append("""{"metadata":[["musicName",["Песня"]],["artists",["КИНО"]]],"rawLyricFile":"r1.ttml"}""").append('\n')
        append("""{"metadata":[["musicName",["Ballad"]],["artists",["КИНО"]]],"rawLyricFile":"b1.ttml"}""")
    }

    private fun http() = LyricsHttpTransport { url, _ -> when {
        url.endsWith("raw-lyrics-index.jsonl") -> index
        url.endsWith(".ttml") -> "<tt xmlns=\"http://www.w3.org/ns/ttml\"/>"
        else -> null
    } }

    private val provider = AmlTtmlProvider()

    @Test fun exactTitlePlusArtistWins() = kotlinx.coroutines.runBlocking {
        val c = provider.fetch(LyricsQuery("t", "Song", "The Artist"), http())!!
        assertEquals("a1.ttml", c.sourceRef)
        assertEquals("ttml", c.rawFormat)
    }

    @Test fun titleMatchWithoutSharedArtistIsRejected() = kotlinx.coroutines.runBlocking {
        assertNull(provider.fetch(LyricsQuery("t", "Song", "Unrelated Singer"), http()))
    }

    @Test fun russianTitleMatches() = kotlinx.coroutines.runBlocking {
        val c = provider.fetch(LyricsQuery("t", "Песня", "Кино"), http())
        assertEquals("r1.ttml", c?.sourceRef)
    }

    @Test fun descriptorVariantStillNeedsTheSameArtist() = kotlinx.coroutines.runBlocking {
        // "Ballad (Live)" vs the indexed "Ballad": the weaker core-title key works only with the artist overlap.
        val c = provider.fetch(LyricsQuery("t", "Ballad (Live)", "Кино"), http())
        assertEquals("b1.ttml", c?.sourceRef)
        assertNull(provider.fetch(LyricsQuery("t", "Ballad (Live)", "Other Band"), http()))
    }

    @Test fun searchReturnsRankedCandidates() = kotlinx.coroutines.runBlocking {
        val all = provider.search(LyricsQuery("t", "Song", "The Artist"), http())
        assertTrue(all.isNotEmpty() && all.first().sourceRef == "a1.ttml")
    }
}
