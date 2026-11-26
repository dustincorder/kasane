package cord.kasane.desktop.lyrics

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.lyrics.AmlTtmlProvider
import cord.kasane.music.lyrics.LrcLibProvider
import cord.kasane.music.lyrics.LyricsCandidate
import cord.kasane.music.lyrics.LyricsDocument
import cord.kasane.music.lyrics.LyricsHttpTransport
import cord.kasane.music.lyrics.LyricsProvider
import cord.kasane.music.lyrics.LyricsQuery
import cord.kasane.music.lyrics.LyricsResolver
import cord.kasane.music.lyrics.LyricsStore
import cord.kasane.music.lyrics.LyricsSyncKind
import cord.kasane.music.lyrics.StoredLyrics
import cord.kasane.music.model.Track
import cord.kasane.music.provider.SharedOnlineCatalog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

@Serializable
private data class LyricsRecord(
    val providerId: String,
    val sourceRef: String? = null,
    val rawFormat: String,
    val rawPayload: String,
    val offsetMs: Long = 0,
    val fetchedAtMs: Long = 0,
    val userPicked: Boolean = false,
)

/** JSON-file [LyricsStore]: raw provider payloads persist offline; parsing stays cheap on load. */
class FileLyricsStore(
    private val file: File,
    private val now: () -> Long = { System.currentTimeMillis() },
) : LyricsStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()
    private var records: MutableMap<String, LyricsRecord>? = null

    private fun load(): MutableMap<String, LyricsRecord> =
        records ?: runCatching { json.decodeFromString<Map<String, LyricsRecord>>(file.readText()) }
            .getOrDefault(emptyMap()).toMutableMap().also { records = it }

    private fun flush() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val text = json.encodeToString<Map<String, LyricsRecord>>(records.orEmpty())
        tmp.writeText(text)
        if (!tmp.renameTo(file)) file.writeText(text)
    }

    override suspend fun stored(trackId: String): StoredLyrics? = mutex.withLock {
        load()[trackId]?.let {
            StoredLyrics(it.providerId, it.sourceRef, it.rawFormat, it.rawPayload, it.offsetMs, it.fetchedAtMs, it.userPicked)
        }
    }

    override suspend fun put(trackId: String, entry: StoredLyrics): Unit = mutex.withLock {
        val map = load()
        val previous = map[trackId]
        val sameSource = previous != null && previous.providerId == entry.providerId && previous.rawPayload == entry.rawPayload
        map[trackId] = LyricsRecord(entry.providerId, entry.sourceRef, entry.rawFormat, entry.rawPayload,
            offsetMs = if (sameSource) previous!!.offsetMs else entry.offsetMs,
            fetchedAtMs = now(), userPicked = if (sameSource) previous!!.userPicked else entry.userPicked)
        flush()
    }

    override suspend fun setOffset(trackId: String, offsetMs: Long): Unit = mutex.withLock {
        val map = load()
        val current = map[trackId] ?: return@withLock
        map[trackId] = current.copy(offsetMs = offsetMs)
        flush()
    }

    override suspend fun setChoice(trackId: String, entry: StoredLyrics): Unit = mutex.withLock {
        load()[trackId] = LyricsRecord(entry.providerId, entry.sourceRef, entry.rawFormat, entry.rawPayload,
            entry.offsetMs, now(), userPicked = true)
        flush()
    }
}

/**
 * YouTube Music's own lyrics tab: static text only, so it ranks below timed sources.
 * Port of Android's `TrackLyrics` — same request path (next → LYRICS browseId → browse)
 * through the shared catalog's `request`.
 */
class DesktopYtmLyricsProvider(
    private val catalog: SharedOnlineCatalog,
    private val session: () -> AccountSession?,
) : LyricsProvider {
    override val id = "ytmusic"
    override val label = "YouTube Music"
    override val supportedSyncKinds = setOf(LyricsSyncKind.UNSYNCED)

    override suspend fun fetch(query: LyricsQuery, http: LyricsHttpTransport): LyricsCandidate? {
        val next = catalog.request("next", buildJsonObject { put("videoId", query.trackId) }, session())
        val browseId = YtmLyricsParser.lyricsBrowseId(next) ?: return null
        val page = catalog.request("browse", buildJsonObject { put("browseId", browseId) }, session())
        val lyrics = YtmLyricsParser.lyrics(page) ?: return null
        return LyricsCandidate(id, label, sourceRef = query.trackId,
            title = query.title, artist = query.artist, album = query.album,
            durationMs = query.durationMs, rawFormat = "plain", rawPayload = lyrics.text)
    }
}

data class YtmLyrics(val text: String, val source: String?)

object YtmLyricsParser {
    fun lyricsBrowseId(next: JsonObject): String? {
        fun tabBrowseId(node: JsonObject): String? {
            val tab = node["tabRenderer"]?.jsonObject ?: return null
            val endpoint = tab["endpoint"]?.jsonObject?.get("browseEndpoint")?.jsonObject ?: return null
            val browseId = endpoint["browseId"]?.jsonPrimitive?.content ?: return null
            // Real `next` payloads leave tabRenderer.pageType null — the lyrics marker lives in
            // the endpoint's browse context config. Lyrics browseIds also all share the MPLY prefix.
            val contextType = endpoint["browseEndpointContextSupportedConfigs"]?.jsonObject
                ?.get("browseEndpointContextMusicConfig")?.jsonObject
                ?.get("pageType")?.jsonPrimitive?.content
            val marked = tab["pageType"]?.jsonPrimitive?.content == "MUSIC_PAGE_TYPE_TRACK_LYRICS" ||
                contextType == "MUSIC_PAGE_TYPE_TRACK_LYRICS" || browseId.startsWith("MPLY")
            return browseId.takeIf { marked }
        }
        val root = next["contents"]?.jsonObject
            ?.get("singleColumnMusicWatchNextResultsRenderer")?.jsonObject
            ?.get("tabbedRenderer")?.jsonObject
            ?.get("watchNextTabbedResultsRenderer")?.jsonObject
        return root?.get("tabs")?.jsonArray?.firstNotNullOfOrNull { (it as? JsonObject)?.let(::tabBrowseId) }
    }

    fun lyrics(page: JsonObject): YtmLyrics? {
        val shelves = page["contents"]?.jsonObject?.get("sectionListRenderer")?.jsonObject
            ?.get("contents")?.jsonArray ?: return null
        for (entry in shelves) {
            val shelf = (entry as? JsonObject)?.get("musicDescriptionShelfRenderer")?.jsonObject ?: continue
            val text = shelf["description"]?.jsonObject?.let(::runsText).orEmpty().trim()
            if (text.isBlank()) continue
            val source = shelf["footer"]?.jsonObject?.let(::runsText)?.trim()
                ?.removePrefix("Source:")?.removePrefix("Source")?.trim()?.takeIf { it.isNotBlank() }
            return YtmLyrics(text, source)
        }
        return null
    }

    private fun runsText(node: JsonObject): String =
        node["runs"]?.jsonArray?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }?.joinToString("")
            ?: node["text"]?.jsonPrimitive?.content.orEmpty()
}

/** Lyrics on desktop: the shared resolver over AMLL TTML, LRCLIB and YouTube Music. */
class DesktopLyrics(
    client: OkHttpClient,
    catalog: SharedOnlineCatalog,
    session: () -> AccountSession?,
    private val store: LyricsStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val http = LyricsHttpTransport { url, headers ->
        withContext(io) {
            val builder = Request.Builder().url(url).header("User-Agent", "Kasane/1.0")
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                // Contract: null = absent (404/empty), throw = a real failure the resolver can report.
                when {
                    response.code == 404 -> null
                    response.isSuccessful -> response.body?.string()
                    else -> throw java.io.IOException("lyrics source returned HTTP ${response.code}")
                }
            }
        }
    }
    private val resolver = LyricsResolver(
        providers = listOf(AmlTtmlProvider(), LrcLibProvider(), DesktopYtmLyricsProvider(catalog, session)),
        store = store)

    private fun Track.query() = LyricsQuery(id, title, artist, album,
        durationMs = durationSeconds.takeIf { it > 0 }?.let { (it * 1000).toLong() })

    suspend fun lyricsFor(track: Track, refresh: Boolean = false): LyricsDocument? =
        resolver.resolve(track.query(), http, refresh)

    /** Same lookup, but keeps absent vs provider-failure apart for the UI state machine. */
    suspend fun lyricsStateFor(track: Track, refresh: Boolean = false): cord.kasane.music.lyrics.LyricsResolution =
        resolver.resolveState(track.query(), http, refresh)

    suspend fun candidatesFor(track: Track): List<Pair<LyricsCandidate, LyricsDocument?>> =
        resolver.candidates(track.query(), http)

    suspend fun choose(trackId: String, candidate: LyricsCandidate) = resolver.choose(trackId, candidate)
    suspend fun adjustOffset(trackId: String, deltaMs: Long) = resolver.adjustOffset(trackId, deltaMs)
    suspend fun stored(trackId: String): LyricsDocument? = store.stored(trackId)?.let(resolver::parseStored)
}
