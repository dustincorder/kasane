package cord.kasane.music

import cord.kasane.music.data.LocalDataExporter
import cord.kasane.music.data.ExportedPlaylist
import cord.kasane.music.model.HistoryEntity
import cord.kasane.music.model.Track
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDataExporterTest {
    @Test
    fun exportContainsLocalCollectionsAndMetadataButNeverStreamOrSessionSecrets() {
        val streamMarker = "signed-stream-secret-marker"
        val cookieMarker = "SAPISID=account-cookie-marker"
        val encoded = LocalDataExporter.encode(
            favorites = listOf(Track(
                id = "video1234567",
                title = "Saved song",
                artist = "Kasane Artist",
                streamUri = streamMarker,
            )),
            history = listOf(HistoryEntity(
                trackId = "video1234567",
                title = "Saved song",
                artist = "Kasane Artist",
                playedAt = 1234,
            )),
            exportedAtEpochMs = 4321,
            preferences = mapOf("theme" to "dark"),
            playlists = listOf(ExportedPlaylist("local-1", "Road home", listOf("video1234567"))),
        )

        assertTrue(encoded.contains("Saved song"))
        assertTrue(encoded.contains("\"theme\": \"dark\""))
        assertTrue(encoded.contains("Road home"))
        assertTrue(encoded.contains("1234"))
        assertFalse(encoded.contains("streamUri"))
        assertFalse(encoded.contains(streamMarker))
        assertFalse(encoded.contains(cookieMarker))
        assertFalse(encoded.contains("SAPISID"))
        assertFalse(encoded.contains("token"))
        assertFalse(encoded.contains("keystore"))
    }

    @Test
    fun exportWithoutCurrentlyUnsupportedPreferencesAndPlaylistsIsExplicitlyEmpty() {
        val encoded = LocalDataExporter.encode(emptyList(), emptyList(), exportedAtEpochMs = 1)
        assertTrue(encoded.contains("\"preferences\": {}"))
        assertTrue(encoded.contains("\"playlists\": []"))
    }
}
