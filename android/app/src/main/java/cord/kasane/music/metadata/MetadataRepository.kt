package cord.kasane.music.metadata

import cord.kasane.music.data.KasaneDatabase
import cord.kasane.music.data.TrackMetadataDao
import cord.kasane.music.data.TrackMetadataEntity
import cord.kasane.music.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Room store + OkHttp transport for the shared MusicBrainz/CAA resolver. */
class RoomMetadataStore(private val dao: TrackMetadataDao) : MetadataStore {
    override suspend fun get(trackId: String) = dao.get(trackId)?.let {
        TrackMetadata(it.trackId, it.releaseTitle, it.releaseDate, it.artistDisplay,
            it.genres.split('|').filter(String::isNotBlank), it.coverArtUrl,
            it.musicBrainzReleaseId, it.enrichedAtMs)
    }
    override suspend fun put(metadata: TrackMetadata) = dao.upsert(TrackMetadataEntity(
        metadata.trackId, metadata.releaseTitle, metadata.releaseDate, metadata.artistDisplay,
        metadata.genres.joinToString("|"), metadata.coverArtUrl,
        metadata.musicBrainzReleaseId, System.currentTimeMillis()))
}

class MetadataRepository(database: KasaneDatabase, client: OkHttpClient,
    private val enabled: () -> Boolean = { true }) {
    @Volatile private var lastCallMs = 0L
    private val http = MetadataHttpTransport { url, headers ->
        withContext(Dispatchers.IO) {
            // MusicBrainz allows 1 req/s — enrichment runs sequentially behind the download loop.
            (1100L - (System.currentTimeMillis() - lastCallMs)).let { if (it > 0) kotlinx.coroutines.delay(it) }
            lastCallMs = System.currentTimeMillis()
            val builder = Request.Builder().url(url)
                .header("User-Agent", "Kasane/1.0 (https://github.com/dustincorder/kasane)")
            headers.forEach { (k, v) -> builder.header(k, v) }
            runCatching { client.newCall(builder.build()).execute().use {
                if (it.isSuccessful) it.body?.string() else null } }.getOrNull()
        }
    }
    private val resolver = MetadataResolver(MusicBrainzProvider(), RoomMetadataStore(database.metadataDao()))

    suspend fun metadataFor(track: Track, refresh: Boolean = false): TrackMetadata? =
        resolver.metadataFor(MetadataQuery(track.id, track.title, track.artist, track.album,
            durationMs = (track.durationSeconds * 1000).toLong()), http, refresh)

    /** Fire-and-forget enrichment after a track lands in the offline library. */
    suspend fun enrichIfMissing(track: Track) {
        if (enabled()) runCatching { metadataFor(track) }
    }
}
