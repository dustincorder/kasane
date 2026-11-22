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

    /** A 3-column grid of large covers, paged horizontally: the first thing Home offers to play. */
    data class QuickPicks(override val title: String, val tracks: List<Track>) : HomeSection { override val key = "quick-picks" }
    /** A song shelf as compact rows, four per column, paged horizontally. */
    data class SongRows(override val key: String, override val title: String, val tracks: List<Track>, val more: String? = null) : HomeSection
    data class RecentTracks(override val title: String, val tracks: List<Track>) : HomeSection { override val key = "recent" }
    /** Albums, playlists and mixes: square covers. */
    data class Collections(override val key: String, override val title: String, val items: List<CatalogItem>, val more: String? = null) : HomeSection
    data class Artists(override val key: String, override val title: String, val items: List<CatalogItem>, val more: String? = null) : HomeSection
    /** Music videos and live stations keep their 16:9 frame, drawn large. */
    data class Videos(override val key: String, override val title: String, val items: List<CatalogItem>, val more: String? = null) : HomeSection
    /** Radios seeded from favourite artists in the history; each starts a fresh YouTube Music radio. */
    data class RadioSeeds(override val title: String, val seeds: List<Track>) : HomeSection { override val key = "radio-seeds" }
    data class SavedOffline(override val title: String, val tracks: List<Track>) : HomeSection { override val key = "saved" }
}

/**
 * Builds Home from what is available, in a stable order, without repeating a song across sections.
 * Quick picks come first (YouTube Music's personalised picks topped up with what this device plays most and
 * the favourites), then recently played, then the rest of the YouTube Music feed with the local radio and
 * saved-music sections slotted between remote shelves. Sections too small to be useful are dropped.
 * With [local] false (a mood chip is active) only the remote feed for that mood is shown.
 */
internal object HomeComposer {
    const val PAGE_SIZE = 9
    const val MAX_QUICK_PICKS = 18
    private const val MIN_ROW_ITEMS = 3

    fun build(
        remote: List<CatalogShelf>,
        topPlayed: List<PlayStat>,
        history: List<HistoryEntity>,
        saved: List<Track>,
        offlineOnly: Boolean,
        favorites: List<Track> = emptyList(),
        local: Boolean = true,
    ): List<HomeSection> {
        val shown = mutableSetOf<String>()
        fun fresh(tracks: List<Track>) = tracks.distinctBy { it.id }.filter { it.id !in shown }
        fun claim(tracks: List<Track>) = tracks.also { list -> list.forEach { shown += it.id } }
        val out = mutableListOf<HomeSection>()

        val remoteShelves = if (offlineOnly) emptyList() else remote.filter { it.items.isNotEmpty() }
        val firstSongShelf = remoteShelves.indexOfFirst { shelf -> shelf.items.all { it.kind == CatalogKind.SONG } }
        val picks = mutableListOf<Track>()
        if (firstSongShelf >= 0) picks += remoteShelves[firstSongShelf].items.mapNotNull { it.track() }.distinctBy { it.id }.take(PAGE_SIZE)
        if (local) {
            // Habits first (played more than once), then favourites; a single play is not yet a preference.
            picks += topPlayed.filter { it.plays >= 2 }.map(PlayStat::track)
            picks += favorites
        }
        val quick = picks.distinctBy { it.id }.take(MAX_QUICK_PICKS)
        if (quick.size >= MIN_ROW_ITEMS) {
            val title = if (firstSongShelf >= 0) remoteShelves[firstSongShelf].title.ifBlank { "Quick picks" } else "Quick picks"
            out += HomeSection.QuickPicks(title, claim(quick))
        }

        if (local) {
            val recent = fresh(history.sortedByDescending { it.playedAt }
                .map { Track(it.trackId, it.title, it.artist, it.album, thumbnailUri = it.thumbnailUri) }).take(12)
            if (recent.size >= MIN_ROW_ITEMS) out += HomeSection.RecentTracks("Recently played", claim(recent))
        }

        val seedPool = if (local) topPlayed.map(PlayStat::track).filter { it.artist.isNotBlank() }.distinctBy { it.artist.lowercase() } else emptyList()
        val seeds = (seedPool.filter { it.id !in shown } + seedPool).distinctBy { it.id }.take(4)
        val radio = if (seeds.size >= 2 && !offlineOnly) HomeSection.RadioSeeds("Radio from your listening", seeds) else null
        val savedSection = if (!local) null else fresh(saved).take(12).takeIf { it.size >= 2 || (offlineOnly && it.isNotEmpty()) }
            ?.let { HomeSection.SavedOffline("Saved for offline", claim(it)) }

        var remoteAdded = 0
        remoteShelves.forEachIndexed { index, shelf ->
            if (index == firstSongShelf) return@forEachIndexed
            classify(index, shelf, ::fresh)?.let { section ->
                when (section) {
                    is HomeSection.SongRows -> claim(section.tracks)
                    else -> Unit
                }
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
            CatalogKind.SONG -> fresh(items.mapNotNull { it.track() }).takeIf { it.size >= MIN_ROW_ITEMS }
                ?.let { HomeSection.SongRows(key, title, it.take(20), shelf.moreBrowseId) }
            CatalogKind.ARTIST -> items.filter { it.kind == CatalogKind.ARTIST }.takeIf { it.size >= 2 }?.let { HomeSection.Artists(key, title, it, shelf.moreBrowseId) }
            CatalogKind.VIDEO -> items.filter { it.kind == CatalogKind.VIDEO }.filter { v -> fresh(listOfNotNull(v.track())).isNotEmpty() }
                .takeIf { it.size >= 2 }?.let { HomeSection.Videos(key, title, it, shelf.moreBrowseId) }
            CatalogKind.ALBUM, CatalogKind.PLAYLIST -> items.filter { it.kind == CatalogKind.ALBUM || it.kind == CatalogKind.PLAYLIST }
                .takeIf { it.size >= 2 }?.let { HomeSection.Collections(key, title, it, shelf.moreBrowseId) }
        }
    }
}
