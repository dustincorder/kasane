package cord.kasane.music.lyrics

/**
 * Orchestrates providers: automatic resolution prefers the most precise timing
 * (word-synced → line-synced → plain), manual search returns every candidate for the picker.
 */
class LyricsResolver(
    private val providers: List<LyricsProvider>,
    private val store: LyricsStore,
) {
    /** Order for AUTO: providers that can yield word timing first, preserving the given order. */
    private val ordered get() = providers.sortedByDescending {
        (LyricsSyncKind.WORD in it.supportedSyncKinds).compareTo(false)
    }

    /** Stored lyrics if usable, else the best provider candidate; persists the winner. */
    suspend fun resolve(query: LyricsQuery, http: LyricsHttpTransport, refresh: Boolean = false): LyricsDocument? =
        (resolveState(query, http, refresh) as? LyricsResolution.Found)?.document

    /**
     * Like [resolve], but reports why nothing came back: [LyricsResolution.Absent] when every
     * reachable provider answered empty, [LyricsResolution.Unavailable] when at least one
     * provider failed — the UI can then offer retry instead of a wrong "no lyrics" message.
     */
    suspend fun resolveState(query: LyricsQuery, http: LyricsHttpTransport, refresh: Boolean = false): LyricsResolution {
        if (!refresh) {
            val stored = store.stored(query.trackId)?.let(::parseStored)
            if (stored != null && stored.usable) return LyricsResolution.Found(stored)
        }
        var best: Pair<LyricsCandidate, LyricsDocument>? = null
        var failed = 0
        for (provider in ordered) {
            val candidate = try { provider.fetch(query, http) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { failed++; null } ?: continue
            val doc = parse(candidate) ?: continue
            if (!doc.usable) continue
            if (best == null || doc.syncKind.ordinal > best!!.second.syncKind.ordinal) best = candidate to doc
            if (doc.syncKind == LyricsSyncKind.WORD) break
        }
        val winner = best ?: return if (failed > 0) LyricsResolution.Unavailable() else LyricsResolution.Absent
        store.put(query.trackId, winner.first.toStored())
        return LyricsResolution.Found(winner.second)
    }

    /** Every candidate every provider can name — the source picker shows these verbatim. */
    suspend fun candidates(query: LyricsQuery, http: LyricsHttpTransport): List<Pair<LyricsCandidate, LyricsDocument?>> =
        providers.flatMap { provider ->
            try { provider.search(query, http) } catch (_: Exception) { emptyList() }
                .map { it to parse(it) }
        }

    /** The user picked a specific source: store it marked so AUTO never replaces it. */
    suspend fun choose(trackId: String, candidate: LyricsCandidate) {
        store.setChoice(trackId, candidate.toStored().copy(userPicked = true))
    }

    suspend fun adjustOffset(trackId: String, deltaMs: Long) {
        val stored = store.stored(trackId) ?: return
        store.setOffset(trackId, stored.offsetMs + deltaMs)
    }

    fun parse(candidate: LyricsCandidate): LyricsDocument? =
        candidate.parsed ?: parsePayload(candidate.rawFormat, candidate.rawPayload, candidate.providerId,
            candidate.sourceRef, candidate.title, candidate.artist, candidate.album)?.also { candidate.parsed = it }

    fun parseStored(stored: StoredLyrics): LyricsDocument? =
        parsePayload(stored.rawFormat, stored.rawPayload, stored.providerId, stored.sourceRef)
            ?.copy(offsetMs = stored.offsetMs)

    private fun parsePayload(format: String, payload: String, providerId: String, sourceRef: String?,
        title: String? = null, artist: String? = null, album: String? = null): LyricsDocument? = when (format.lowercase()) {
        "lrc", "elrc" -> LrcParser.parse(payload, providerId, sourceRef, title, artist, album)
        "ttml" -> TtmlParser.parse(payload, providerId, sourceRef, title, artist, album)
        "plain" -> LyricsDocument(
            payload.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                .map { LyricLine(0, 0, it) }.toList(),
            LyricsSyncKind.UNSYNCED, providerId, sourceRef, title = title, artist = artist, album = album)
        else -> null
    }

    private fun LyricsCandidate.toStored() = StoredLyrics(providerId, sourceRef, rawFormat, rawPayload,
        fetchedAtMs = 0)
}
