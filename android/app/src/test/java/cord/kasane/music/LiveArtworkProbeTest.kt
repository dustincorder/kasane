package cord.kasane.music

import cord.kasane.music.model.CatalogKind
import cord.kasane.music.provider.YouTubeMusicProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Guest/read-only probe. No Google credentials or playback endpoint calls. */
class LiveArtworkProbeTest {
    @Test fun freshGuestSearchAndHomeReturnActualArtworkBytes() = runTest(
        timeout = kotlin.time.Duration.parse("90s")
    ) {
        assumeTrue("Opt-in network artwork check", System.getenv("KASANE_ARTWORK_LIVE") == "1")
        val catalog = YouTubeMusicProvider().catalog
        val searchTracks = catalog.search("Radiohead", CatalogKind.SONG).tracks
        val homeTracks = catalog.home().tracks
        assertTrue("No search tracks with artwork", searchTracks.any { !it.thumbnailUri.isNullOrBlank() })
        assertTrue("No home tracks with artwork", homeTracks.any { !it.thumbnailUri.isNullOrBlank() })
        val client = OkHttpClient()
        for (tracks in listOf(searchTracks, homeTracks)) {
            val track = tracks.first { !it.thumbnailUri.isNullOrBlank() }
            client.newCall(Request.Builder().url(track.thumbnailUri!!).build()).execute().use { response ->
                assertTrue("Thumbnail HTTP status ${response.code}", response.isSuccessful)
                assertTrue(response.header("Content-Type").orEmpty().startsWith("image/"))
                val bytes = response.body!!.bytes()
                assertTrue("Empty artwork response", bytes.size > 100)
                val jpeg = bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()
                val png = bytes[0] == 0x89.toByte() && bytes.copyOfRange(1, 4).decodeToString() == "PNG"
                val webp = bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                    bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
                assertTrue("Response is not a supported image payload", jpeg || png || webp)
            }
        }
    }
}
