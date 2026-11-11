package cord.kasane.music.model

enum class CatalogKind(val label: String, val filter: String) {
    SONG("Songs", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"),
    ALBUM("Albums", "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"),
    ARTIST("Artists", "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"),
    PLAYLIST("Playlists", "EgeKAQQoAEABagoQAxAEEAoQCRAF"),
    VIDEO("Videos", "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D")
}
data class CatalogItem(val id: String, val kind: CatalogKind, val title: String,
    val subtitle: String = "", val artwork: String? = null, val durationSeconds: Double = 0.0) {
    fun track(): Track? = if (kind == CatalogKind.SONG || kind == CatalogKind.VIDEO)
        Track(id, title, subtitle, durationSeconds = durationSeconds, thumbnailUri = artwork) else null
}
data class CatalogShelf(val title: String, val items: List<CatalogItem>)
data class CatalogPage(val title: String, val subtitle: String, val artwork: String?,
    val shelves: List<CatalogShelf>, val continuation: String? = null, val description: String = "") {
    val tracks: List<Track> get() = shelves.flatMap { it.items }.mapNotNull { it.track() }.distinctBy { it.id }
}
