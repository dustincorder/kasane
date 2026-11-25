package cord.kasane.music.metadata

/** Verified release facts for a track, resolvable offline once stored. */
data class TrackMetadata(
    val trackId: String,
    val releaseTitle: String?,
    /** ISO date ("YYYY-MM-DD" or partial "YYYY") from MusicBrainz. */
    val releaseDate: String?,
    val artistDisplay: String?,
    val genres: List<String>,
    /** Official release cover via Cover Art Archive (https URL). */
    val coverArtUrl: String?,
    val musicBrainzReleaseId: String?,
    val enrichedAtMs: Long = 0,
)

interface MetadataStore {
    suspend fun get(trackId: String): TrackMetadata?
    suspend fun put(metadata: TrackMetadata)
}

/** Minimal GET boundary for metadata providers; clients bind their HTTP stack. */
fun interface MetadataHttpTransport {
    suspend fun get(url: String, headers: Map<String, String>): String?
}

data class MetadataQuery(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
)
