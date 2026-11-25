package cord.kasane.music.lyrics

import kotlinx.serialization.Serializable

/** How precisely the timing in a lyrics document can drive playback highlighting. */
enum class LyricsSyncKind { UNSYNCED, LINE, WORD }

@Serializable
data class LyricWord(val text: String, val startMs: Long, val endMs: Long, val isBackground: Boolean = false)

@Serializable
data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Word-level timing when the source provides it; empty for line-synced and plain lyrics. */
    val words: List<LyricWord> = emptyList(),
    val agent: String? = null,
    val isBackground: Boolean = false,
    val translatedText: String? = null,
    val romanizedText: String? = null,
) {
    val isInstrumentalBreak: Boolean get() = text.isBlank() && words.isEmpty()
}

/** A parsed lyrics document: the only shape UI and storage ever see. */
@Serializable
data class LyricsDocument(
    val lines: List<LyricLine>,
    val syncKind: LyricsSyncKind,
    /** Id of the [LyricsProvider] that produced it ("lrclib", "ytmusic", "amll-db", "user", ...). */
    val providerId: String,
    /** Provider-side identifier of the record (for manual selection & attribution links). */
    val sourceRef: String? = null,
    /** Source attribution ("LRCLIB", "Musixmatch", "Apple Music", "user upload"). */
    val attribution: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** User-applied timing correction, milliseconds; positive shifts lyrics later. */
    val offsetMs: Long = 0,
) {
    val usable: Boolean get() = lines.any { !it.isInstrumentalBreak }
    val hasTiming: Boolean get() = syncKind != LyricsSyncKind.UNSYNCED
}

/** One result a provider can offer for a track — includes the raw payload for offline storage. */
data class LyricsCandidate(
    val providerId: String,
    val providerLabel: String,
    val sourceRef: String?,
    val title: String?, val artist: String?, val album: String?, val durationMs: Long?,
    /** Provider-native payload (LRC / enhanced LRC / TTML / JSON). Parsed lazily by the store. */
    val rawFormat: String,
    val rawPayload: String,
) {
    var parsed: LyricsDocument? = null
}

/** Identity of a track for lyrics lookup; exact ids beat fuzzy matching every time. */
data class LyricsQuery(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
)

/**
 * Minimum HTTP a lyrics provider needs; both clients bind this to their existing client.
 * Contract: `null` means the resource is absent (404/empty); real failures (network, 5xx)
 * throw, so callers can tell "no lyrics exist" apart from "could not check".
 */
fun interface LyricsHttpTransport {
    suspend fun get(url: String, headers: Map<String, String>): String?
}
suspend fun LyricsHttpTransport.get(url: String): String? = get(url, emptyMap())

interface LyricsProvider {
    val id: String
    val label: String
    /** Best match for automatic fetch; null when the provider has nothing usable. */
    suspend fun fetch(query: LyricsQuery, http: LyricsHttpTransport): LyricsCandidate?
    /** All matches for the manual source picker; default reuses [fetch]. */
    suspend fun search(query: LyricsQuery, http: LyricsHttpTransport): List<LyricsCandidate> =
        listOfNotNull(fetch(query, http))

    val supportedSyncKinds: Set<LyricsSyncKind> get() = setOf(LyricsSyncKind.LINE, LyricsSyncKind.UNSYNCED)
}

/** Persistence boundary: Android Room today, Compose Desktop JSON/file store via the same contract. */
interface LyricsStore {
    suspend fun stored(trackId: String): StoredLyrics?
    suspend fun put(trackId: String, entry: StoredLyrics)
    suspend fun setOffset(trackId: String, offsetMs: Long)
    suspend fun setChoice(trackId: String, entry: StoredLyrics)
}

/**
 * Outcome of an automatic lookup, split for the UI: "nothing exists" (all reachable providers
 * answered empty) is not "could not check" (at least one provider errored — retriable).
 */
sealed interface LyricsResolution {
    /** A usable document was found (and persisted by the resolver). */
    data class Found(val document: LyricsDocument) : LyricsResolution
    /** Every reachable provider answered and none had usable lyrics for this track. */
    data object Absent : LyricsResolution
    /** No usable lyrics AND at least one provider failed — a retry may still find them. */
    data class Unavailable(val detail: String? = null) : LyricsResolution
}

/** Full lyrics-pane lifecycle for UI state machines: idle → loading → one terminal state. */
sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data class Ready(val document: LyricsDocument) : LyricsState
    data object Absent : LyricsState
    /** Providers could not be checked — show "try again", not "no lyrics". */
    data class Unavailable(val detail: String? = null) : LyricsState
}

/** What a client stores per track: the raw payload plus the user's selection and offset. */
data class StoredLyrics(
    val providerId: String,
    val sourceRef: String? = null,
    val rawFormat: String,
    val rawPayload: String,
    val offsetMs: Long = 0,
    val fetchedAtMs: Long = 0,
    /** True when the user picked this source manually — never auto-replaced by a better provider. */
    val userPicked: Boolean = false,
)
