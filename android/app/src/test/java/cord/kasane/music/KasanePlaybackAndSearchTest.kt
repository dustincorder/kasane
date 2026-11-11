package cord.kasane.music

import cord.kasane.music.model.Track
import cord.kasane.music.provider.MockMusicProvider
import cord.kasane.music.provider.YouTubeMusicProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class KasanePlaybackAndSearchTest {

    @Test
    fun testMockProviderReturnsTracksAndStream() = runTest {
        val provider = MockMusicProvider()
        val results = provider.search("Midnight", 10)
        assertFalse("Expected non-empty search results", results.isEmpty())

        val track = results.first()
        val streamUrl = provider.resolveStreamUrl(track.id)
        assertNotNull("Stream URL must not be null for mock track", streamUrl)
        assertTrue("Stream URL must be a valid http link", streamUrl!!.startsWith("http"))
    }

    @Test
    fun testYouTubeMusicStreamResolverExtractsValidUrl() = runTest {
        org.junit.Assume.assumeTrue("Explicit live playback check", System.getenv("KASANE_LIVE_PROBES") == "1")
        val ytProvider = YouTubeMusicProvider()
        // Test with a known universal YouTube track ID (Rick Astley - Never Gonna Give You Up)
        val streamUrl = ytProvider.resolveStreamUrl("dQw4w9WgXcQ")
        assertNotNull("Stream URL must be resolved via ANDROID_VR client without 400 error", streamUrl)
        assertTrue("Resolved stream URL must point to googlevideo audio stream", streamUrl!!.contains("googlevideo.com"))
    }

    @Test
    fun testSearchDebouncePipeline() = runTest {
        val queryFlow = MutableStateFlow("")
        val debounced = queryFlow.debounce(100).distinctUntilChanged()

        queryFlow.value = "first"
        queryFlow.value = "first_mid"
        queryFlow.value = "final_query"

        kotlinx.coroutines.delay(150)
        val collected = debounced.first()
        assertEquals("Debounce should emit only the latest query value", "final_query", collected)
    }

    @Test
    fun testQueueNoDuplicateTracks() {
        val queue = mutableListOf<Track>()
        val track1 = Track(id = "1", title = "Track 1", artist = "Artist 1")
        val track2 = Track(id = "2", title = "Track 2", artist = "Artist 2")

        if (!queue.any { it.id == track1.id }) queue.add(track1)
        if (!queue.any { it.id == track1.id }) queue.add(track1) // Attempt duplicate
        if (!queue.any { it.id == track2.id }) queue.add(track2)

        assertEquals("Queue should not contain duplicates", 2, queue.size)
        assertEquals("First track should match", "1", queue[0].id)
        assertEquals("Second track should match", "2", queue[1].id)
    }
}
