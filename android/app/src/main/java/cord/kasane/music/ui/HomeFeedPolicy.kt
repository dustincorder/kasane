package cord.kasane.music.ui

import cord.kasane.music.model.HistoryEntity
import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.Track

/** Small, deterministic feed rules shared by Home and its regression tests. */
internal object HomeFeedPolicy {
    fun uniqueTracks(tracks: List<Track>): List<Track> = tracks.distinctBy { it.id }

    fun uniqueShelfItems(items: List<CatalogItem>): List<CatalogItem> =
        items.distinctBy { it.kind to it.id }

    /** Room already sorts by listen time; keep that ordering while defending against duplicate rows. */
    fun latestHistory(history: List<HistoryEntity>): List<HistoryEntity> =
        history.sortedWith(compareByDescending<HistoryEntity> { it.playedAt }.thenByDescending { it.id })
            .distinctBy { it.trackId }
}
