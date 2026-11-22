package cord.kasane.music.model

import kotlinx.serialization.Serializable

@Serializable
enum class CatalogKind(val label: String, val filter: String) {
    SONG("Songs", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"),
    ALBUM("Albums", "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"),
    ARTIST("Artists", "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"),
    PLAYLIST("Playlists", "EgeKAQQoAEABagoQAxAEEAoQCRAF"),
    VIDEO("Videos", "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D")
}
@Serializable
data class CatalogItem(val id: String, val kind: CatalogKind, val title: String,
    val subtitle: String = "", val artwork: String? = null, val durationSeconds: Double = 0.0,
    /** Album title when YouTube names one for this song (its own column, or the album page it is listed on). */
    val album: String? = null) {
    fun track(): Track? = if (kind == CatalogKind.SONG || kind == CatalogKind.VIDEO)
        Track(id, title, subtitle, album, durationSeconds = durationSeconds, thumbnailUri = artwork) else null
}
@Serializable
data class CatalogShelf(val title: String, val items: List<CatalogItem>, val moreBrowseId: String? = null)
/** A mood/activity filter YouTube Music offers on Home; [params] is the opaque token that selects it. */
/**
 * Who made an album or playlist, as YouTube Music reports it. [browseId] is the artist or channel page and is
 * null when YouTube gives no destination (then the name is shown but not linked). [avatar] is the creator's own image.
 */
@Serializable
data class CatalogCreator(val name: String, val browseId: String? = null, val avatar: String? = null, val userChannel: Boolean = false)

@Serializable
data class CatalogChip(val title: String, val params: String)
@Serializable
data class CatalogPage(val title: String, val subtitle: String, val artwork: String?,
    val shelves: List<CatalogShelf>, val continuation: String? = null, val description: String = "",
    val chips: List<CatalogChip> = emptyList(),
    val creators: List<CatalogCreator> = emptyList(),
    /** YouTube's own second subtitle, e.g. "11 songs • 37 minutes" or "107M views • 64 tracks • 3 hours". */
    val stats: String = "",
    /** True when YouTube returned the editable (owner) header: the signed-in account owns this playlist. */
    val owned: Boolean = false) {
    val tracks: List<Track> get() = shelves.flatMap { it.items }.mapNotNull { it.track() }.distinctBy { it.id }
}

/** The header facts of a page that the cache stores next to its shelves. */
@Serializable
data class PageMeta(val creators: List<CatalogCreator> = emptyList(), val stats: String = "", val owned: Boolean = false) {
    companion object {
        fun of(page: CatalogPage) = PageMeta(page.creators, page.stats, page.owned).takeIf { it.creators.isNotEmpty() || it.stats.isNotBlank() || it.owned }
    }
}
