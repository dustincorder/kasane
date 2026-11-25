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
    data class QuickPicks(override val title: String, val items: List<CatalogItem>) : HomeSection {
        override val key = "quick-picks"
        val tracks: List<Track> get() = items.mapNotNull { it.track() }
    }
    /** A song shelf as compact rows, four per column, paged horizontally. */
    data class SongRows(override val key: String, override val title: String, val tracks: List<Track>, val more: String? = null) : HomeSection
    /** A shelf YouTube Music fills with several kinds at once ("Listen again"): each item keeps its own shape. */
    data class Mixed(override val key: String, override val title: String, val items: List<CatalogItem>, val more: String? = null) : HomeSection
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
        // YouTube Music opens the feed with its personal picks ("Quick picks", "Listen again"): the lead shelf is
        // always mostly songs, whatever its exact mix, and that shelf becomes the cover grid. Deeper song shelves
        // ("Song mixes", "More like…") are dense track lists and stay rows; a video-led shelf keeps its 16:9 cards.
        val leadShelf = remoteShelves.indexOfFirst { shelf ->
            shelf.items.count { it.kind == CatalogKind.SONG } * 2 >= shelf.items.size
        }
        val picks = mutableListOf<CatalogItem>()
        if (leadShelf >= 0) picks += remoteShelves[leadShelf].items.distinctBy { it.kind to it.id }.take(PAGE_SIZE)
        if (local) {
            // Habits first (played more than once), then favourites; a single play is not yet a preference.
            picks += topPlayed.filter { it.plays >= 2 }.map(PlayStat::track).map { it.asItem() }
            picks += favorites.map { it.asItem() }
        }
        val quick = picks.distinctBy { it.kind to it.id }.take(MAX_QUICK_PICKS)
        val quickSection = if (quick.count { it.track() != null } >= MIN_ROW_ITEMS) {
            val title = if (leadShelf >= 0) remoteShelves[leadShelf].title.ifBlank { "Quick picks" } else "Quick picks"
            HomeSection.QuickPicks(title, quick)
        } else null
        // Songs claimed by an earlier section are not repeated: Quick picks wins over later shelves.
        if (quickSection != null) claim(quickSection.tracks)
        val recent = if (!local) emptyList() else fresh(history.sortedByDescending { it.playedAt }
            .map { Track(it.trackId, it.title, it.artist, it.album, thumbnailUri = it.thumbnailUri) }).take(12)
            .takeIf { it.size >= MIN_ROW_ITEMS }.orEmpty()
        val recentSection = if (recent.isNotEmpty()) HomeSection.RecentTracks("Recently played", recent) else null
        if (recentSection != null && remoteShelves.isEmpty()) claim(recent)

        val seedPool = if (local) topPlayed.map(PlayStat::track).filter { it.artist.isNotBlank() }.distinctBy { it.artist.lowercase() } else emptyList()
        val seeds = (seedPool.filter { it.id !in shown } + seedPool).distinctBy { it.id }.take(4)
        val radio = if (seeds.size >= 2 && !offlineOnly) HomeSection.RadioSeeds("Radio from your listening", seeds) else null
        val savedSection = if (!local) null else fresh(saved).take(12).takeIf { it.size >= 2 || (offlineOnly && it.isNotEmpty()) }
            ?.let { HomeSection.SavedOffline("Saved for offline", it) }

        // Local sections go after the first remote shelves so YouTube Music's personalised opening is not buried,
        // and lead only when there is nothing remote (guest offline, Offline Only).
        val localBlock = listOfNotNull(recentSection, radio, savedSection)
        var remoteSections = 0
        var localInserted = false
        fun insertLocal() { if (!localInserted) { localInserted = true; localBlock.forEach { if (it is HomeSection.RecentTracks) claim(it.tracks); out += it } } }
        if (offlineOnly) { savedSection?.let { out += it; claim(it.tracks) }; return out + listOfNotNull(recentSection) }
        if (remoteShelves.isEmpty()) { quickSection?.let { out += it }; insertLocal(); return out }
        remoteShelves.forEachIndexed { index, shelf ->
            val section = if (index == leadShelf) quickSection else classify(index, shelf, ::fresh)
            if (section != null) {
                if (section is HomeSection.SongRows) claim(section.tracks)
                out += section; remoteSections++
                if (remoteSections == 2) insertLocal()
            }
        }
        insertLocal()
        return out
    }

    private fun classify(index: Int, shelf: CatalogShelf, fresh: (List<Track>) -> List<Track>): HomeSection? {
        val items = shelf.items.distinctBy { it.kind to it.id }
        val title = shelf.title.ifBlank { return null }
        val key = "remote:$index"
        val dominant = items.groupingBy { it.kind }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val tracks = items.count { it.kind == CatalogKind.SONG || it.kind == CatalogKind.VIDEO }
        val mixed = tracks > 0 && tracks < items.size && items.size - tracks > 0 &&
            items.groupingBy { it.kind }.eachCount().values.max() < items.size * 0.8
        if (mixed && items.size >= 3) return HomeSection.Mixed(key, title, items, shelf.moreBrowseId)
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

internal fun Track.asItem() = CatalogItem(id, CatalogKind.SONG, title, artist, thumbnailUri, durationSeconds, album)
