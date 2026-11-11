package cord.kasane.music

import cord.kasane.music.model.*
import cord.kasane.music.provider.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit opt-in only. Never uses accounts, cookies, or remote mutations. */
class LiveCatalogProbeTest {
    @Test fun parsesCapturedPublicResponses() {
        val directory = System.getenv("KASANE_LIVE_FIXTURE_DIR")
        assumeTrue("Opt-in guest probes", directory != null)
        listOf("songs" to CatalogKind.SONG, "albums" to CatalogKind.ALBUM,
            "artists" to CatalogKind.ARTIST, "playlists" to CatalogKind.PLAYLIST).forEach { (name, kind) ->
            val root = org.json.JSONObject(File(directory!!, "live-$name.json").readText())
            val items = CatalogParser.search(root, kind).shelves.flatMap { it.items }
            assertTrue("No parsed $name", items.isNotEmpty())
            assertTrue(items.all { it.kind == kind })
        }
        val home = CatalogParser.page(org.json.JSONObject(File(directory!!, "live-home.json").readText()))
        assertTrue(home.shelves.isNotEmpty())
        assertTrue(home.shelves.all { it.title.isNotBlank() })
    }
    @Test fun guestCanBrowseCatalogAndRadio() = runTest(timeout = kotlin.time.Duration.parse("120s")) {
        assumeTrue("Opt-in guest probes", System.getenv("KASANE_LIVE_PROBES") == "1")
        val provider = YouTubeMusicProvider()
        val catalog = provider.catalog
        val album = catalog.search("Radiohead", CatalogKind.ALBUM).shelves.flatMap { it.items }.first()
        val albumPage = catalog.browse(album.id)
        assertTrue("Album has no playable tracks", albumPage.tracks.isNotEmpty())
        val artist = catalog.search("Radiohead", CatalogKind.ARTIST).shelves.flatMap { it.items }.first()
        assertTrue("Artist has no sections", catalog.browse(artist.id).shelves.isNotEmpty())
        val playlist = catalog.search("Radiohead", CatalogKind.PLAYLIST).shelves.flatMap { it.items }.first()
        val rawPlaylist = catalog.request("browse", org.json.JSONObject().put("browseId", playlist.id))
        System.getenv("KASANE_LIVE_FIXTURE_DIR")?.let { File(it, "live-playlist-detail.json").writeText(rawPlaylist.toString()) }
        val playlistPage = CatalogParser.page(rawPlaylist)
        assertTrue("Playlist has no tracks", playlistPage.tracks.isNotEmpty())
        playlistPage.continuation?.let { token ->
            val rawNext = catalog.request("browse", org.json.JSONObject().put("continuation", token))
            System.getenv("KASANE_LIVE_FIXTURE_DIR")?.let { File(it, "live-playlist-next.json").writeText(rawNext.toString()) }
            assertTrue("Playlist continuation has no playable tracks", CatalogParser.page(rawNext).tracks.isNotEmpty())
        }
        val song = albumPage.tracks.first()
        assertTrue("Radio has no recommendations", catalog.radio(song.id).isNotEmpty())
    }
    @Test fun actualAlbumAudioIsAccessible() = runTest(timeout = kotlin.time.Duration.parse("120s")) {
        assumeTrue("Opt-in guest probes", System.getenv("KASANE_LIVE_PROBES") == "1")
        val provider = YouTubeMusicProvider()
        val album = provider.catalog.search("Radiohead", CatalogKind.ALBUM).shelves.flatMap { it.items }.first()
        val song = provider.catalog.browse(album.id).tracks.first()
        val url = provider.resolveStreamUrl(song.id)
        assertNotNull("Guest audio cannot be resolved", url)
        val client = OkHttpClient()
        client.newCall(Request.Builder().url(url!!).header("Range", "bytes=0-1023").build()).execute().use {
            assertTrue("CDN refused guest audio (${it.code})", it.isSuccessful)
            assertTrue(it.body!!.source().readByteArray(1024).isNotEmpty())
        }
    }

    @Test fun publicControlTrackProvidesAudioBytes() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        assumeTrue("Opt-in guest probes", System.getenv("KASANE_LIVE_PROBES") == "1")
        val url = YouTubeMusicProvider().resolveStreamUrl("dQw4w9WgXcQ")
        assertNotNull("No stream for public control track", url)
        OkHttpClient().newCall(Request.Builder().url(url!!).header("Range", "bytes=0-1023").build()).execute().use {
            assertTrue("CDN refused control audio (${it.code})", it.isSuccessful)
            assertEquals(1024, it.body!!.source().readByteArray(1024).size)
        }
    }
}
