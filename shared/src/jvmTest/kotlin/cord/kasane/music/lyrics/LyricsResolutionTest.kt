package cord.kasane.music.lyrics

import kotlin.test.*
import kotlinx.coroutines.runBlocking

class LyricsResolutionTest {

    private val store = object : LyricsStore {
        val map = mutableMapOf<String, StoredLyrics>()
        override suspend fun stored(trackId: String) = map[trackId]
        override suspend fun put(trackId: String, entry: StoredLyrics) { map[trackId] = entry }
        override suspend fun setOffset(trackId: String, offsetMs: Long) { map[trackId] = map.getValue(trackId).copy(offsetMs = offsetMs) }
        override suspend fun setChoice(trackId: String, entry: StoredLyrics) { map[trackId] = entry }
    }
    private val noHttp = LyricsHttpTransport { _, _ -> null }

    private fun provider(id: String, result: LyricsCandidate?) = object : LyricsProvider {
        override val id = id; override val label = id
        override suspend fun fetch(q: LyricsQuery, http: LyricsHttpTransport) = result
    }
    private val failing = object : LyricsProvider {
        override val id = "broken"; override val label = "B"
        override suspend fun fetch(q: LyricsQuery, http: LyricsHttpTransport): LyricsCandidate? = throw java.io.IOException("offline")
    }

    @Test fun absentWhenEveryProviderAnswersEmpty() = runBlocking {
        val resolver = LyricsResolver(listOf(provider("a", null), provider("b", null)), store)
        assertEquals(LyricsResolution.Absent, resolver.resolveState(LyricsQuery("id", "T", "A"), noHttp))
    }

    @Test fun unavailableWhenAProviderFailedAndNothingFound() = runBlocking {
        val resolver = LyricsResolver(listOf(provider("a", null), failing), store)
        assertTrue(resolver.resolveState(LyricsQuery("id", "T", "A"), noHttp) is LyricsResolution.Unavailable)
    }

    @Test fun foundStillWinsOverProviderFailures() = runBlocking {
        val hit = LyricsCandidate("good", "G", null, null, null, null, null, "plain", "words\nhere")
        val resolver = LyricsResolver(listOf(failing, provider("good", hit)), store)
        val resolution = resolver.resolveState(LyricsQuery("id", "T", "A"), noHttp)
        assertEquals("good", (resolution as LyricsResolution.Found).document.providerId)
    }

    @Test fun lrclibFallsBackToAlbumLessGet() = runBlocking {
        var requested = mutableListOf<String>()
        val entry = """{"id":7,"trackName":"Song","artistName":"Artist","duration":200.0,"syncedLyrics":"[00:01.00]hi"}"""
        val http = LyricsHttpTransport { url, _ ->
            requested += url
            when {
                "album_name=" in url -> null // the album-specific tuple misses
                "/api/get" in url -> entry
                else -> null
            }
        }
        val candidate = LrcLibProvider().fetch(
            LyricsQuery("id", "Song", "Artist", album = "Different Album", durationMs = 200_000), http)
        assertEquals("7", candidate?.sourceRef)
        assertTrue(requested.any { "album_name=" in it } && requested.any { "album_name=" !in it && "/api/get" in it })
    }

    @Test fun lrclibSearchFallbackNeedsSameRecordingAndCloseDuration() = runBlocking {
        val list = """[
            {"id":3,"trackName":"Song (Live)","artistName":"Artist","duration":410.0,"syncedLyrics":"[00:01.00]c"},
            {"id":1,"trackName":"Song (feat. Y)","artistName":"Artist","duration":203.0,"syncedLyrics":"[00:01.00]a"},
            {"id":2,"trackName":"Song","artistName":"Other Act","duration":200.0,"syncedLyrics":"[00:01.00]b"}
        ]""".trimIndent()
        val http = LyricsHttpTransport { url, _ -> if ("/api/search" in url) list else null }
        val candidate = LrcLibProvider().fetch(
            LyricsQuery("id", "Song", "Artist", album = "X", durationMs = 200_000), http)
        // The live cut is too far off the track's duration; the same-recording remix wins instead.
        assertEquals("1", candidate?.sourceRef)
    }

    @Test fun lrclibSearchFallbackRejectsDifferentArtist() = runBlocking {
        val list = """[{"id":2,"trackName":"Song","artistName":"Other Act","duration":200.0,"syncedLyrics":"[00:01.00]b"}]"""
        val http = LyricsHttpTransport { url, _ -> if ("/api/search" in url) list else null }
        assertNull(LrcLibProvider().fetch(LyricsQuery("id", "Song", "Artist", durationMs = 200_000), http))
    }

    @Test fun normalizationCollapsesFeaturingAndDescriptors() {
        assertEquals("monster", LyricsMatch.normalize("MONSTER (feat. GUMI)"))
        assertEquals("title", LyricsMatch.coreTitle("Title (Live) [Remastered]"))
        assertTrue(LyricsMatch.artistsOverlap("Daft Punk feat. Pharrell", "Daft Punk"))
        assertTrue(LyricsMatch.sameRecording("Title (feat. X)", "Artist", "Title", "artist & friends"))
        assertTrue(LyricsMatch.durationClose(201_000, 200_000))
        assertTrue(!LyricsMatch.durationClose(210_000, 200_000))
    }
}
