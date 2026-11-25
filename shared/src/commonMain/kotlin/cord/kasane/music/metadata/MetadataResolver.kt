package cord.kasane.music.metadata

/** Cached enrichment: stored metadata wins; otherwise ask providers once and persist. */
class MetadataResolver(
    private val providers: List<suspend (MetadataQuery, MetadataHttpTransport) -> TrackMetadata?>,
    private val store: MetadataStore,
) {
    constructor(provider: MusicBrainzProvider, store: MetadataStore)
        : this(listOf({ q, h -> provider.enrich(q, h) }), store)

    suspend fun metadataFor(query: MetadataQuery, http: MetadataHttpTransport, refresh: Boolean = false): TrackMetadata? {
        if (!refresh) store.get(query.trackId)?.let { return it }
        for (provider in providers) {
            val metadata = runCatching { provider(query, http) }.getOrNull() ?: continue
            store.put(metadata)
            return metadata
        }
        return null
    }
}
