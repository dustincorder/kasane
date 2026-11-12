package cord.kasane.music.data

import cord.kasane.music.model.HistoryEntity
import cord.kasane.music.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Portable local data only. Sensitive account/session material has no field in this format. */
@Serializable
data class LocalDataExport(
    val format: String = "kasane-local-data",
    val schemaVersion: Int = 1,
    val exportedAtEpochMs: Long,
    val preferences: Map<String, String> = emptyMap(),
    val favorites: List<ExportedTrack> = emptyList(),
    val history: List<ExportedHistoryEntry> = emptyList(),
    val playlists: List<ExportedPlaylist> = emptyList(),
)

@Serializable
data class ExportedTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSeconds: Double = 0.0,
    val artwork: String? = null,
    val isLocalFile: Boolean = false,
    val sourceProvider: String = "youtube",
)

@Serializable
data class ExportedHistoryEntry(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artwork: String? = null,
    val playedAtEpochMs: Long,
)

@Serializable
data class ExportedPlaylist(
    val id: String,
    val title: String,
    val trackIds: List<String>,
)

object LocalDataExporter {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(
        favorites: List<Track>,
        history: List<HistoryEntity>,
        exportedAtEpochMs: Long = System.currentTimeMillis(),
        preferences: Map<String, String> = emptyMap(),
        playlists: List<ExportedPlaylist> = emptyList(),
    ): String = json.encodeToString(
        LocalDataExport(
            exportedAtEpochMs = exportedAtEpochMs,
            preferences = preferences,
            favorites = favorites.map { it.toExportedTrack() },
            history = history.map { entry ->
                ExportedHistoryEntry(
                    trackId = entry.trackId,
                    title = entry.title,
                    artist = entry.artist,
                    album = entry.album,
                    artwork = entry.thumbnailUri,
                    playedAtEpochMs = entry.playedAt,
                )
            },
            playlists = playlists,
        ),
    )

    private fun Track.toExportedTrack() = ExportedTrack(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationSeconds = durationSeconds,
        artwork = thumbnailUri,
        isLocalFile = isLocal,
        sourceProvider = sourceProvider,
    )
}
