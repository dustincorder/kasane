package cord.kasane.music.ui

import cord.kasane.music.data.PlayStat
import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.CatalogShelf
import cord.kasane.music.model.HistoryEntity
import cord.kasane.music.model.Track

/** One block of Home. Every section is built from real local history or real YouTube Music shelves. */
sealed interface HomeSection {
    val key: String
    val title: String

    /** Most-played tracks of the last 30 days: one tap to the music this person actually returns to. */
    data class SpeedDial(override val title: String, val tracks: List<Track>) : HomeSection { override val key = "speed-dial" }
    /** A song shelf (YouTube Music "Quick picks" style): compact rows, four per column, paged horizontally. */
    data class SongGrid(override val key: String, override val title: String, val tracks: List<Track>) : HomeSection
    data class RecentTracks(override val title: String, val tracks: List<Track>) : HomeSection { override val key = "recent" }
    /** Albums, playlists and mixes: square covers. */
    data class Collections(override val key: String, override val title: String, val items: List<CatalogItem>) : HomeSection
    data class Artists(override val key: String, override val title: String, val items: List<CatalogItem>) : HomeSection
    /** Music videos keep their 16:9 frame instead of being cropped square. */
    data class Videos(override val key: String, override val title: String, val items: List<CatalogItem>) : HomeSection
    /** Radios seeded from favourite artists in the history; each starts a fresh YouTube Music radio. */
    data class RadioSeeds(override val title: String, val seeds: List<Track>) : HomeSection { override val key = "radio-seeds" }
    data class SavedOffline(override val title: String, val tracks: List<Track>) : HomeSection { override val key = "saved" }
}

/**
 * Builds Home from what is available, in a stable order, without repeating a song across sections:
 * personal shortcuts first, then the first YouTube Music song shelf, then the rest of the feed with local
 * radio and saved-music sections slotted between remote shelves. Sections too small to be useful are dropped.
 */
internal object HomeComposer {
    const val SPEED_DIAL_SIZE = 6
    private const val MIN_ROW_ITEMS = 3

    fun build(
        remote: List<CatalogShelf>,
        topPlayed: List<PlayStat>,
        history: List<HistoryEntity>,
        saved: List<Track>,
        offlineOnly: Boolean,
    ): List<HomeSection> {
        val shownSongs = mutableSetOf<String>()
        fun fresh(tracks: List<Track>) = tracks.distinctBy { it.id }.filter { it.id !in shownSongs }
        fun claim(tracks: List<Track>) = tracks.also { list -> list.forEach { shownSongs += it.id } }
        val out = mutableListOf<HomeSection>()

        // Repeat listens only: a single play is not yet a habit worth a shortcut.
        val dial = topPlayed.filter { it.plays >= 2 }.map(PlayStat::track).distinctBy { it.id }.take(SPEED_DIAL_SIZE)
        if (dial.size >= 4) out += HomeSection.SpeedDial("Speed dial", claim(dial))

        val remoteShelves = if (offlineOnly) emptyList() else remote.filter { it.items.isNotEmpty() }
        val firstSongShelf = remoteShelves.indexOfFirst { shelf -> shelf.items.all { it.kind == CatalogKind.SONG } }
        if (firstSongShelf >= 0) {
            val shelf = remoteShelves[firstSongShelf]
            val tracks = fresh(shelf.items.mapNotNull { it.track() })
            if (tracks.size >= MIN_ROW_ITEMS) out += HomeSection.SongGrid("remote:$firstSongShelf", shelf.title.ifBlank { "Quick picks" }, claim(tracks.take(20)))
        }

        val recent = fresh(history.sortedByDescending { it.playedAt }.map { Track(it.trackId, it.title, it.artist, it.album, thumbnailUri = it.thumbnailUri) }).take(12)
        if (recent.size >= MIN_ROW_ITEMS) out += HomeSection.RecentTracks("Recently played", claim(recent))

        // One radio per artist, preferring artists not already represented on screen.
        val seedPool = topPlayed.map(PlayStat::track).filter { it.artist.isNotBlank() }.distinctBy { it.artist.lowercase() }
        val seeds = (seedPool.filter { it.id !in shownSongs } + seedPool).distinctBy { it.id }.take(4)
        val radio = if (seeds.size >= 2) HomeSection.RadioSeeds("Radio from your listening", seeds) else null
        val savedSection = fresh(saved).take(12).takeIf { it.size >= 2 || (offlineOnly && it.isNotEmpty()) }
            ?.let { HomeSection.SavedOffline("Saved for offline", claim(it)) }

        var remoteAdded = 0
        remoteShelves.forEachIndexed { index, shelf ->
            if (index == firstSongShelf) return@forEachIndexed
            classify(index, shelf, ::fresh)?.let { section ->
                if (section is HomeSection.SongGrid) claim(section.tracks)
                out += section; remoteAdded++
                if (remoteAdded == 2 && radio != null) out += radio
            }
        }
        if (radio != null && radio !in out) out += radio
        if (offlineOnly && savedSection != null) out.add(0, savedSection) else if (savedSection != null) out += savedSection
        return out
    }

    private fun classify(index: Int, shelf: CatalogShelf, fresh: (List<Track>) -> List<Track>): HomeSection? {
        val items = shelf.items.distinctBy { it.kind to it.id }
        val title = shelf.title.ifBlank { return null }
        val key = "remote:$index"
        val dominant = items.groupingBy { it.kind }.eachCount().maxByOrNull { it.value }?.key ?: return null
        return when (dominant) {
            CatalogKind.SONG -> fresh(items.mapNotNull { it.track() }).takeIf { it.size >= MIN_ROW_ITEMS }?.let { HomeSection.SongGrid(key, title, it.take(20)) }
            CatalogKind.ARTIST -> items.filter { it.kind == CatalogKind.ARTIST }.takeIf { it.size >= 2 }?.let { HomeSection.Artists(key, title, it) }
            CatalogKind.VIDEO -> items.filter { it.kind == CatalogKind.VIDEO }.filter { v -> fresh(listOfNotNull(v.track())).isNotEmpty() }
                .takeIf { it.size >= 2 }?.let { HomeSection.Videos(key, title, it) }
            CatalogKind.ALBUM, CatalogKind.PLAYLIST -> items.filter { it.kind == CatalogKind.ALBUM || it.kind == CatalogKind.PLAYLIST }
                .takeIf { it.size >= 2 }?.let { HomeSection.Collections(key, title, it) }
        }
    }
}
