package cord.kasane.desktop.ui

import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.CatalogShelf
import cord.kasane.music.model.Track

/** One block of Home, mirroring Android's HomeSection semantics for the desktop layout. */
sealed interface DesktopHomeSection {
    val key: String
    val title: String
    /** Large cover grid: YouTube Music's personal picks topped up with local habits and likes. */
    data class QuickPicks(override val title: String, val items: List<CatalogItem>) : DesktopHomeSection {
        override val key = "quick-picks"
    }
    /** A song shelf as compact rows (e.g. "Song mixes", "More like…"). */
    data class TrackRows(override val key: String, override val title: String, val tracks: List<Track>, val more: String? = null) : DesktopHomeSection
    /** Albums, playlists, mixes, artists, videos: square/round cover cards. */
    data class Cards(override val key: String, override val title: String, val items: List<CatalogItem>, val more: String? = null) : DesktopHomeSection
    /** What this computer played recently. */
    data class RecentTracks(override val title: String, val tracks: List<Track>) : DesktopHomeSection { override val key = "recent" }
    /** Radios seeded from favourite artists in the history. */
    data class RadioSeeds(override val title: String, val seeds: List<Track>) : DesktopHomeSection { override val key = "radio-seeds" }
}

/**
 * Builds Home with Android's ordering rules: the lead personal shelf becomes Quick picks, local
 * sections (recent plays, radio seeds) slot in after the second remote shelf, and no song repeats
 * across sections. [local] false (a mood chip is active) shows only the remote feed.
 */
internal object HomeComposer {
    const val PAGE_SIZE = 9
    const val MAX_QUICK_PICKS = 18
    private const val MIN_ROW_ITEMS = 3

    fun build(
        remote: List<CatalogShelf>,
        topPlayed: List<Track>,
        recent: List<Track>,
        liked: List<Track>,
        local: Boolean = true,
    ): List<DesktopHomeSection> {
        val shown = mutableSetOf<String>()
        fun fresh(tracks: List<Track>) = tracks.distinctBy { it.id }.filter { it.id !in shown }
        fun claim(tracks: List<Track>) = tracks.also { list -> list.forEach { shown += it.id } }
        val out = mutableListOf<DesktopHomeSection>()

        val remoteShelves = remote.filter { it.items.isNotEmpty() }
        // Same lead rule as Android: the shelf that opens the feed is mostly songs and becomes the cover grid.
        val leadShelf = remoteShelves.indexOfFirst { shelf ->
            shelf.items.count { it.kind == CatalogKind.SONG } * 2 >= shelf.items.size
        }
        val picks = mutableListOf<CatalogItem>()
        if (leadShelf >= 0) picks += remoteShelves[leadShelf].items.distinctBy { it.kind to it.id }.take(PAGE_SIZE)
        if (local) {
            picks += topPlayed.map { it.asItem() }
            picks += liked.map { it.asItem() }
        }
        val quick = picks.distinctBy { it.kind to it.id }.take(MAX_QUICK_PICKS)
        val quickSection = if (quick.count { it.track() != null } >= MIN_ROW_ITEMS) {
            val title = if (leadShelf >= 0) remoteShelves[leadShelf].title.ifBlank { "Quick picks" } else "Quick picks"
            DesktopHomeSection.QuickPicks(title, quick)
        } else null
        if (quickSection != null) claim(quick.mapNotNull { it.track() })

        val recentTracks = if (!local) emptyList() else fresh(recent).take(12).takeIf { it.size >= MIN_ROW_ITEMS }.orEmpty()
        val recentSection = if (recentTracks.isNotEmpty()) DesktopHomeSection.RecentTracks("Recently played", recentTracks) else null
        if (recentSection != null && remoteShelves.isEmpty()) claim(recentTracks)

        val seedPool = if (local) topPlayed.filter { it.artist.isNotBlank() }.distinctBy { it.artist.lowercase() } else emptyList()
        val seeds = (seedPool.filter { it.id !in shown } + seedPool).distinctBy { it.id }.take(4)
        val radio = if (seeds.size >= 2) DesktopHomeSection.RadioSeeds("Radio from your listening", seeds) else null

        val localBlock = listOfNotNull(recentSection, radio)
        var remoteSections = 0
        var localInserted = false
        fun insertLocal() { if (!localInserted) { localInserted = true; localBlock.forEach { if (it is DesktopHomeSection.RecentTracks) claim(it.tracks); out += it } } }
        if (remoteShelves.isEmpty()) { quickSection?.let { out += it }; insertLocal(); return out }
        remoteShelves.forEachIndexed { index, shelf ->
            val section = if (index == leadShelf) quickSection else classify(index, shelf, ::fresh)
            if (section != null) {
                if (section is DesktopHomeSection.TrackRows) claim(section.tracks)
                out += section; remoteSections++
                if (remoteSections == 2) insertLocal()
            }
        }
        insertLocal()
        return out
    }

    private fun classify(index: Int, shelf: CatalogShelf, fresh: (List<Track>) -> List<Track>): DesktopHomeSection? {
        val items = shelf.items.distinctBy { it.kind to it.id }
        val title = shelf.title.ifBlank { return null }
        val key = "remote:$index"
        val dominant = items.groupingBy { it.kind }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val tracks = items.count { it.kind == CatalogKind.SONG || it.kind == CatalogKind.VIDEO }
        val mixed = tracks > 0 && tracks < items.size && items.size - tracks > 0 &&
            items.groupingBy { it.kind }.eachCount().values.max() < items.size * 0.8
        // "Listen again" and similar mixed shelves stay cover cards; song-led shelves are track rows.
        if (mixed) return if (items.size >= 3) DesktopHomeSection.Cards(key, title, items, shelf.moreBrowseId) else null
        return when (dominant) {
            CatalogKind.SONG -> fresh(items.mapNotNull { it.track() }).takeIf { it.size >= MIN_ROW_ITEMS }
                ?.let { DesktopHomeSection.TrackRows(key, title, it.take(20), shelf.moreBrowseId) }
            CatalogKind.ARTIST -> items.filter { it.kind == CatalogKind.ARTIST }.takeIf { it.size >= 2 }
                ?.let { DesktopHomeSection.Cards(key, title, it, shelf.moreBrowseId) }
            CatalogKind.VIDEO -> items.filter { it.kind == CatalogKind.VIDEO }.filter { v -> fresh(listOfNotNull(v.track())).isNotEmpty() }
                .takeIf { it.size >= 2 }?.let { DesktopHomeSection.Cards(key, title, it, shelf.moreBrowseId) }
            CatalogKind.ALBUM, CatalogKind.PLAYLIST -> items.filter { it.kind == CatalogKind.ALBUM || it.kind == CatalogKind.PLAYLIST }
                .takeIf { it.size >= 2 }?.let { DesktopHomeSection.Cards(key, title, it, shelf.moreBrowseId) }
        }
    }
}

private fun Track.asItem() = CatalogItem(id, CatalogKind.SONG, title, artist, thumbnailUri, durationSeconds, album)
