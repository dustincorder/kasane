package cord.kasane.desktop.metadata

import cord.kasane.music.metadata.TrackMetadata
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.io.File

/** JSON-file metadata store: round-trip and restart persistence. */
class FileMetadataStoreTest {
    private val dir = createTempDirectory("metadata-store").toFile()

    @Test fun putGetAndReopen() = runBlocking {
        val file = File(dir, "metadata.json")
        FileMetadataStore(file).put(TrackMetadata("t1", "Album", "1998-04-20", "Artist & Co",
            listOf("trip-hop", "electronic"), "https://coverartarchive.org/x/front", "mb-1"))
        FileMetadataStore(file).put(TrackMetadata("t2", null, null, "Solo",
            emptyList(), null, null))

        FileMetadataStore(file).get("t1")!!.let {
            assertEquals("Album", it.releaseTitle)
            assertEquals("1998-04-20", it.releaseDate)
            assertEquals(listOf("trip-hop", "electronic"), it.genres)
            assertEquals("mb-1", it.musicBrainzReleaseId)
        }
        FileMetadataStore(file).get("t2")!!.let {
            assertNull(it.coverArtUrl)
            assertEquals("Solo", it.artistDisplay)
        }
        assertNull(FileMetadataStore(file).get("missing"))
        Unit
    }
}
