package cord.kasane.music.lyrics

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.data.KasaneDatabase
import cord.kasane.music.data.LyricsDao
import cord.kasane.music.data.LyricsEntity
import cord.kasane.music.model.Track
import cord.kasane.music.provider.OnlineCatalog
import cord.kasane.music.provider.YtmLyricsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Room-backed [LyricsStore]: raw provider payloads persist offline; parsing stays cheap on load. */
class RoomLyricsStore(private val dao: LyricsDao) : LyricsStore {
    override suspend fun stored(trackId: String) = dao.get(trackId)?.let {
        StoredLyrics(it.providerId, it.sourceRef, it.rawFormat, it.rawPayload, it.offsetMs, it.fetchedAtMs, it.userPicked)
    }
    override suspend fun put(trackId: String, entry: StoredLyrics) =
        dao.upsert(entry.toEntity(trackId))
    override suspend fun setOffset(trackId: String, offsetMs: Long) = dao.setOffset(trackId, offsetMs)
    override suspend fun setChoice(trackId: String, entry: StoredLyrics) =
        dao.upsert(entry.toEntity(trackId).copy(userPicked = true))
    private fun StoredLyrics.toEntity(trackId: String) = LyricsEntity(trackId, providerId, sourceRef,
        rawFormat, rawPayload, offsetMs, System.currentTimeMillis(), userPicked)
}

/** Lyrics on Android: LRCLIB first (timed LRC), YouTube Music static text as fallback. */
class LyricsRepository(
    database: KasaneDatabase,
    client: OkHttpClient,
    catalog: OnlineCatalog,
    session: () -> AccountSession?,
) {
    private val http = LyricsHttpTransport { url, headers ->
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).header("User-Agent", "Kasane/1.0")
            headers.forEach { (k, v) -> builder.header(k, v) }
            // Per LyricsHttpTransport's contract: null means "the provider has nothing
            // at this URL" (non-2xx); a real failure (offline, timeout, TLS) throws so
            // the resolver reports Unavailable — not a silent Absent on every track.
            client.newCall(builder.build()).execute().use {
                if (it.isSuccessful) it.body?.string() else null
            }
        }
    }
    private val store = RoomLyricsStore(database.lyricsDao())
    private val resolver = LyricsResolver(
        providers = listOf(AmlTtmlProvider(), LrcLibProvider(), YtmLyricsProvider(catalog, session)),
        store = store)

    private fun Track.query() = LyricsQuery(id, title, artist, album, durationMs = (durationSeconds * 1000).toLong())

    // Provider CPU work (the AMLL index is a ~1.7 MB JSONL parsed and ranked per query,
    // plus LRC/TTML parsing) must never run on the caller's dispatcher — the UI calls
    // these from composition scopes, i.e. the main thread.
    suspend fun lyricsFor(track: Track, refresh: Boolean = false): LyricsDocument? =
        withContext(Dispatchers.Default) { resolver.resolve(track.query(), http, refresh) }

    /** Detailed outcome: Found / Absent (providers empty) / Unavailable (providers failed). */
    suspend fun lyricsResolutionFor(track: Track, refresh: Boolean = false): LyricsResolution =
        withContext(Dispatchers.Default) { resolver.resolveState(track.query(), http, refresh) }

    suspend fun candidatesFor(track: Track): List<Pair<LyricsCandidate, LyricsDocument?>> =
        withContext(Dispatchers.Default) { resolver.candidates(track.query(), http) }

    suspend fun choose(trackId: String, candidate: LyricsCandidate) = resolver.choose(trackId, candidate)
    suspend fun adjustOffset(trackId: String, deltaMs: Long) = resolver.adjustOffset(trackId, deltaMs)
    suspend fun stored(trackId: String): LyricsDocument? = store.stored(trackId)?.let(resolver::parseStored)
}
