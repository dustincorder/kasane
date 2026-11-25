package cord.kasane.desktop.metadata

import cord.kasane.music.metadata.MetadataHttpTransport
import cord.kasane.music.metadata.MetadataQuery
import cord.kasane.music.metadata.MetadataResolver
import cord.kasane.music.metadata.MetadataStore
import cord.kasane.music.metadata.MusicBrainzProvider
import cord.kasane.music.metadata.TrackMetadata
import cord.kasane.music.model.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

@Serializable
private data class MetadataRecord(
    val releaseTitle: String? = null,
    val releaseDate: String? = null,
    val artistDisplay: String? = null,
    val genres: List<String> = emptyList(),
    val coverArtUrl: String? = null,
    val musicBrainzReleaseId: String? = null,
    val enrichedAtMs: Long = 0,
)

/** JSON-file [MetadataStore], mirroring the Android Room table. */
class FileMetadataStore(private val file: File) : MetadataStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()
    private var records: MutableMap<String, MetadataRecord>? = null

    private fun load(): MutableMap<String, MetadataRecord> =
        records ?: runCatching { json.decodeFromString<Map<String, MetadataRecord>>(file.readText()) }
            .getOrDefault(emptyMap()).toMutableMap().also { records = it }

    override suspend fun get(trackId: String): TrackMetadata? = mutex.withLock {
        load()[trackId]?.let {
            TrackMetadata(trackId, it.releaseTitle, it.releaseDate, it.artistDisplay,
                it.genres, it.coverArtUrl, it.musicBrainzReleaseId, it.enrichedAtMs)
        }
    }

    override suspend fun put(metadata: TrackMetadata): Unit = mutex.withLock {
        val map = load()
        map[metadata.trackId] = MetadataRecord(metadata.releaseTitle, metadata.releaseDate, metadata.artistDisplay,
            metadata.genres, metadata.coverArtUrl, metadata.musicBrainzReleaseId,
            if (metadata.enrichedAtMs > 0) metadata.enrichedAtMs else System.currentTimeMillis())
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val text = json.encodeToString<Map<String, MetadataRecord>>(map)
        tmp.writeText(text)
        if (!tmp.renameTo(file)) file.writeText(text)
    }
}

/** JSON store + OkHttp transport for the shared MusicBrainz/CAA resolver (1 req/s throttle). */
class DesktopMetadata(
    client: OkHttpClient,
    store: MetadataStore,
    private val enabled: () -> Boolean = { true },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile private var lastCallMs = 0L
    private val http = MetadataHttpTransport { url, headers ->
        withContext(io) {
            // MusicBrainz allows 1 req/s — enrichment runs sequentially behind the download loop.
            (1100L - (now() - lastCallMs)).let { if (it > 0) delay(it) }
            lastCallMs = now()
            val builder = Request.Builder().url(url)
                .header("User-Agent", "Kasane/1.0 (https://github.com/dustincorder/kasane)")
            headers.forEach { (k, v) -> builder.header(k, v) }
            runCatching {
                client.newCall(builder.build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
            }.getOrNull()
        }
    }
    private val resolver = MetadataResolver(MusicBrainzProvider(), store)

    suspend fun metadataFor(track: Track, refresh: Boolean = false): TrackMetadata? =
        resolver.metadataFor(MetadataQuery(track.id, track.title, track.artist, track.album,
            durationMs = (track.durationSeconds * 1000).toLong()), http, refresh)

    /** Fire-and-forget enrichment after a track lands in the offline library. */
    suspend fun enrichIfMissing(track: Track) {
        if (enabled()) runCatching { metadataFor(track) }
    }
}
