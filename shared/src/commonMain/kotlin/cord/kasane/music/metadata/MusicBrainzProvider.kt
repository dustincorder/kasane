package cord.kasane.music.metadata

import cord.kasane.music.lyrics.urlEncode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * MusicBrainz + Cover Art Archive enrichment. MusicBrainz rate limit is 1 req/s —
 * callers serialize requests (the resolver enforces this via its caller's scope).
 * No code copied: API facts only. Cover Art Archive serves official release art.
 */
class MusicBrainzProvider(
    private val baseUrl: String = "https://musicbrainz.org",
    private val caaUrl: String = "https://coverartarchive.org",
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Best-effort enrichment: recording match → earliest official release → tags → CAA cover. */
    suspend fun enrich(query: MetadataQuery, http: MetadataHttpTransport): TrackMetadata? {
        val recording = findRecording(query, http) ?: return null
        val release = pickRelease(recording) ?: return TrackMetadata(query.trackId,
            releaseTitle = null, releaseDate = null,
            artistDisplay = recording.artistCredit(), genres = recording.tags(),
            coverArtUrl = null, musicBrainzReleaseId = null)
        val releaseId = release["id"]?.jsonPrimitive?.content
        val tags = if (releaseId != null) releaseTags(releaseId, http) else recording.tags()
        return TrackMetadata(
            trackId = query.trackId,
            releaseTitle = release["title"]?.jsonPrimitive?.content,
            releaseDate = release["date"]?.jsonPrimitive?.content,
            artistDisplay = release.artistCredit() ?: recording.artistCredit(),
            genres = tags,
            coverArtUrl = releaseId?.let { frontCover(it, http) },
            musicBrainzReleaseId = releaseId,
        )
    }

    internal suspend fun findRecording(query: MetadataQuery, http: MetadataHttpTransport): JsonObject? {
        val mbQuery = buildString {
            if (query.isrc != null) append("isrc:").append(query.isrc)
            else {
                append("recording:\"").append(query.title.escape()).append("\"")
                if (query.artist.isNotBlank()) append(" AND artist:\"").append(query.artist.escape()).append("\"")
                query.album?.let { append(" AND release:\"").append(it.escape()).append("\"") }
            }
        }
        val body = http.get("$baseUrl/ws/2/recording?query=${urlEncode(mbQuery)}&fmt=json&limit=5", mbHeaders()) ?: return null
        val recordings = (json.parseToJsonElement(body) as? JsonObject)?.get("recordings") as? JsonArray ?: return null
        return recordings.mapNotNull { it as? JsonObject }
            .maxByOrNull { score(it, query) }
            ?.takeIf { score(it, query) > 0 }
    }

    /** Exact-title match beats loose hits; ISRC-identical recordings win outright. */
    private fun score(recording: JsonObject, query: MetadataQuery): Int {
        var s = 0
        if (recording["title"]?.jsonPrimitive?.content?.equals(query.title, ignoreCase = true) == true) s += 2
        if (query.isrc != null && (recording["isrcs"] as? JsonArray)
            ?.any { it.jsonPrimitive.content.equals(query.isrc, ignoreCase = true) } == true) s += 10
        if (((recording["score"]?.jsonPrimitive?.content?.toIntOrNull()) ?: 0) >= 90) s += 1
        return s
    }

    /** Prefer the earliest release carrying a date — usually the original album/single. */
    internal fun pickRelease(recording: JsonObject): JsonObject? {
        val releases = recording["releases"] as? JsonArray ?: return null
        return releases.mapNotNull { it as? JsonObject }
            .filter { it["status"]?.jsonPrimitive?.content.let { s -> s == null || s == "Official" } }
            .minByOrNull { it["date"]?.jsonPrimitive?.content ?: "9999" }
    }

    internal suspend fun releaseTags(releaseId: String, http: MetadataHttpTransport): List<String> {
        val body = http.get("$baseUrl/ws/2/release/$releaseId?inc=tags+genres&fmt=json", mbHeaders()) ?: return emptyList()
        val obj = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        return (obj.tags()) + (obj["genres"] as? JsonArray)
            .orEmpty().mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.content }
    }

    /** Official front cover; 404 means the release has no CAA art — return null, never guess. */
    internal suspend fun frontCover(releaseId: String, http: MetadataHttpTransport): String? {
        val body = http.get("$caaUrl/release/$releaseId", emptyMap()) ?: return null
        val images = (json.parseToJsonElement(body) as? JsonObject)?.get("images") as? JsonArray ?: return null
        val front = images.mapNotNull { it as? JsonObject }
            .firstOrNull { it["front"]?.jsonPrimitive?.content == "true" }
        return front?.get("image")?.jsonPrimitive?.content
    }

    private fun JsonObject.tags(): List<String> = (this["tags"] as? JsonArray)
        .orEmpty().mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.content }.distinct()

    private fun JsonObject.artistCredit(): String? = (this["artist-credit"] as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.content }
        ?.takeIf { it.isNotEmpty() }?.joinToString("")

    private fun String.escape() = replace("\\", " ").replace("\"", " ")
    private fun mbHeaders() = mapOf("Accept" to "application/json")
}
