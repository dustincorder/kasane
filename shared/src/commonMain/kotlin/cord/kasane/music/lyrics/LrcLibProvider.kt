package cord.kasane.music.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** lrclib.net — free synchronized-lyrics API, no auth. `/api/get` needs an exact
 *  (artist, title, album?, duration?) tuple; `/api/search` powers the manual picker. */
class LrcLibProvider(private val baseUrl: String = "https://lrclib.net") : LyricsProvider {
    override val id = "lrclib"
    override val label = "LRCLIB"
    override val supportedSyncKinds = setOf(LyricsSyncKind.LINE, LyricsSyncKind.UNSYNCED)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(query: LyricsQuery, http: LyricsHttpTransport): LyricsCandidate? {
        // The strict tuple first — YouTube album naming often differs from LRCLIB's
        // ("Album - Single", deluxe tags), so the album-less variant is the next attempt.
        strictGet(query, http, includeAlbum = true)?.let { return it }
        if (query.album != null) strictGet(query, http, includeAlbum = false)?.let { return it }
        // Exact-tuple miss: the search endpoint still knows the track — pick a confident hit
        // (same recording by normalized title+artist, close duration) instead of reporting nothing.
        return search(query, http).firstOrNull {
            LyricsMatch.sameRecording(query.title, query.artist, it.title.orEmpty(), it.artist) &&
                LyricsMatch.durationClose(it.durationMs, query.durationMs)
        }
    }

    private suspend fun strictGet(query: LyricsQuery, http: LyricsHttpTransport, includeAlbum: Boolean): LyricsCandidate? {
        val url = buildString {
            append("$baseUrl/api/get?track_name=").append(enc(query.title))
            append("&artist_name=").append(enc(query.artist))
            if (includeAlbum) query.album?.let { append("&album_name=").append(enc(it)) }
            query.durationMs?.takeIf { it > 0 }?.let { append("&duration=").append(it / 1000) }
        }
        val body = http.get(url) ?: return null
        val entry = json.parseToJsonElement(body) as? JsonObject ?: return null
        return entry.toCandidate()
    }

    override suspend fun search(query: LyricsQuery, http: LyricsHttpTransport): List<LyricsCandidate> {
        val q = listOfNotNull(query.artist.takeIf { it.isNotBlank() }, query.title).joinToString(" ")
        val body = http.get("$baseUrl/api/search?q=" + enc(q)) ?: return emptyList()
        val list = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        return list.mapNotNull { (it as? JsonObject)?.toCandidate() }
            .sortedWith(compareByDescending<LyricsCandidate> { it.rawFormat == "lrc" }
                .thenBy { durationGap(it.durationMs, query.durationMs) })
    }

    private fun JsonObject.toCandidate(): LyricsCandidate? {
        val synced = this["syncedLyrics"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val plain = this["plainLyrics"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val (format, payload) = when {
            synced != null -> "lrc" to synced
            plain != null -> "plain" to plain
            else -> return null
        }
        return LyricsCandidate(id, label, sourceRef = this["id"]?.jsonPrimitive?.longOrNull?.toString(),
            title = this["trackName"]?.jsonPrimitive?.content,
            artist = this["artistName"]?.jsonPrimitive?.content,
            album = this["albumName"]?.jsonPrimitive?.content,
            durationMs = this["duration"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() },
            rawFormat = format, rawPayload = payload)
    }

    private fun durationGap(candidateMs: Long?, queryMs: Long?): Long =
        if (candidateMs == null || queryMs == null) 0 else kotlin.math.abs(candidateMs - queryMs)

    private fun enc(value: String) = urlEncode(value)
}

/** RFC 3986 query-component encoding, multiplatform (java.net is not in commonMain). */
internal fun urlEncode(value: String): String {
    val out = StringBuilder()
    for (b in value.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~') out.append(ch)
        else if (ch == ' ') out.append("%20")
        else out.append('%').append(c.toString(16).uppercase().padStart(2, '0'))
    }
    return out.toString()
}
