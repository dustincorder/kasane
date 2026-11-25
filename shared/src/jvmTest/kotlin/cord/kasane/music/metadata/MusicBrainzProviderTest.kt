package cord.kasane.music.metadata


import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicBrainzProviderTest {

    private fun transport(routes: Map<String, String>) = MetadataHttpTransport { url, _ -> routes.entries.firstOrNull { url.startsWith(it.key) }?.value }

    private val recordingJson = """{"recordings":[{"id":"r1","title":"Song","score":100,
        |"artist-credit":[{"name":"Artist"}],
        |"tags":[{"name":"rock","count":3}],
        |"releases":[{"id":"rel-new","title":"Reissue","date":"2020-01-01","status":"Official"},
        |{"id":"rel-orig","title":"Album","date":"2005-05-01","status":"Official"}]}]}""".trimMargin()

    @Test fun enrichPicksEarliestOfficialRelease() = kotlinx.coroutines.runBlocking {
        val http = transport(mapOf(
            "https://musicbrainz.org/ws/2/recording" to recordingJson,
            "https://musicbrainz.org/ws/2/release/rel-orig" to """{"tags":[{"name":"j-rock"}],"genres":[{"name":"rock"}]}""",
            "https://coverartarchive.org/release/rel-orig" to """{"images":[{"front":true,"image":"https://caa/front.jpg"}]}"""))
        val meta = MusicBrainzProvider().enrich(MetadataQuery("t1", "Song", "Artist"), http)!!
        assertEquals("rel-orig", meta.musicBrainzReleaseId)
        assertEquals("2005-05-01", meta.releaseDate)
        assertEquals("https://caa/front.jpg", meta.coverArtUrl)
        assertEquals(listOf("j-rock", "rock"), meta.genres)
    }

    @Test fun missingCoverReturnsNullNotGuess() = kotlinx.coroutines.runBlocking {
        val http = transport(mapOf(
            "https://musicbrainz.org/ws/2/recording" to recordingJson,
            "https://musicbrainz.org/ws/2/release/rel-orig" to """{"tags":[]}"""))
        val meta = MusicBrainzProvider().enrich(MetadataQuery("t1", "Song", "Artist"), http)!!
        assertNull(meta.coverArtUrl)
    }

    @Test fun noRecordingMatchReturnsNull() = kotlinx.coroutines.runBlocking {
        val http = transport(mapOf("https://musicbrainz.org/ws/2/recording" to """{"recordings":[]}"""))
        assertNull(MusicBrainzProvider().enrich(MetadataQuery("t1", "X", "Y"), http))
    }

    @Test fun resolverCachesResult() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val http = MetadataHttpTransport { url, _ -> calls++
            if (url.startsWith("https://musicbrainz.org/ws/2/recording")) recordingJson else "{}" }
        val store = object : MetadataStore {
            var saved: TrackMetadata? = null
            override suspend fun get(trackId: String) = saved
            override suspend fun put(metadata: TrackMetadata) { saved = metadata }
        }
        val resolver = MetadataResolver(MusicBrainzProvider(), store)
        val q = MetadataQuery("t1", "Song", "Artist")
        resolver.metadataFor(q, http)
        val callsAfterFirst = calls
        resolver.metadataFor(q, http)
        assertEquals(callsAfterFirst, calls, "second lookup must hit the store, not the network")
    }
}
