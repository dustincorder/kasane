package cord.kasane.music.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * AMLL TTML DB (github.com/amll-dev/amll-ttml-db, CC0) — word-synced TTML lyrics,
 * strong coverage for Japanese/Chinese/English and growing Russian entries.
 * The repo publishes a flat JSONL index; we fetch it once per provider instance,
 * match by normalized title+artist (no platform ids available for YouTube tracks),
 * then download the raw TTML file. Title must match exactly — fuzzy hits are guesses.
 */
class AmlTtmlProvider(
    private val indexUrl: String =
        "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/metadata/raw-lyrics-index.jsonl",
    private val rawBaseUrl: String =
        "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/raw-lyrics",
) : LyricsProvider {
    override val id = "amll-ttml"
    override val label = "AMLL TTML DB"
    override val supportedSyncKinds = setOf(LyricsSyncKind.WORD, LyricsSyncKind.LINE)

    private val json = Json { ignoreUnknownKeys = true }
    private var index: List<AmlEntry>? = null

    private class AmlEntry(val title: String, val artists: List<String>, val album: String?, val file: String)

    override suspend fun fetch(query: LyricsQuery, http: LyricsHttpTransport): LyricsCandidate? =
        rank(query, http).firstOrNull()?.let { download(it, http) }

    override suspend fun search(query: LyricsQuery, http: LyricsHttpTransport): List<LyricsCandidate> =
        rank(query, http).take(5).mapNotNull { download(it, http) }

    private suspend fun rank(query: LyricsQuery, http: LyricsHttpTransport): List<AmlEntry> {
        val entries = index(http) ?: return emptyList()
        val wanted = LyricsMatch.normalize(query.title)
        val core = LyricsMatch.coreTitle(query.title)
        return entries.mapNotNull { e ->
            // Exact normalized title wins; the weaker core key ("Title (Live)" → "title")
            // still has to pass the artist-overlap gate below.
            if (LyricsMatch.normalize(e.title) != wanted && LyricsMatch.coreTitle(e.title) != core) return@mapNotNull null
            val score = artistScore(query.artist, e.artists)
            // Require at least one shared artist token when the artist is known —
            // a bare title hit on a common word is a guess, not a match.
            if (score <= 0 && query.artist.isNotBlank() && e.artists.isNotEmpty()) null
            else e to score
        }.sortedByDescending { it.second }.map { it.first }
    }

    private suspend fun download(entry: AmlEntry, http: LyricsHttpTransport): LyricsCandidate? {
        val payload = http.get("$rawBaseUrl/${entry.file}") ?: return null
        return LyricsCandidate(id, label, sourceRef = entry.file,
            title = entry.title, artist = entry.artists.joinToString(", "), album = entry.album,
            durationMs = null, rawFormat = "ttml", rawPayload = payload)
    }

    private suspend fun index(http: LyricsHttpTransport): List<AmlEntry>? {
        index?.let { return it }
        val body = http.get(indexUrl) ?: return null
        val parsed = body.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@mapNotNull null
            val file = obj["rawLyricFile"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val meta = (obj["metadata"] as? JsonArray).orEmpty().mapNotNull { pair ->
                val arr = pair as? JsonArray ?: return@mapNotNull null
                val key = arr.getOrNull(0)?.jsonPrimitive?.content ?: return@mapNotNull null
                val values = (arr.getOrNull(1) as? JsonArray).orEmpty().mapNotNull {
                    it.jsonPrimitive.content.takeIf(String::isNotBlank) }
                key to values
            }.toMap()
            AmlEntry(title = meta["musicName"]?.firstOrNull() ?: return@mapNotNull null,
                artists = meta["artists"].orEmpty(), album = meta["album"]?.firstOrNull(), file = file)
        }.toList()
        index = parsed
        return parsed
    }

    private fun artistScore(queryArtist: String, entryArtists: List<String>): Int {
        val tokens = LyricsMatch.artistTokens(queryArtist); if (tokens.isEmpty()) return 1
        val entryTokens = entryArtists.flatMap { LyricsMatch.artistTokens(it) }.toSet()
        return tokens.count { it in entryTokens }
    }
}
