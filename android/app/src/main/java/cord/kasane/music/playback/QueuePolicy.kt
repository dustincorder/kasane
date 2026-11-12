package cord.kasane.music.playback

import cord.kasane.music.model.Track

/** Input validation only. The authoritative queue is the Media3 timeline. */
object QueuePolicy {
    const val LIMIT = 2000
    fun nextInsertionIndex(currentIndex: Int, existingIndex: Int?, count: Int): Int {
        val target = currentIndex + if (existingIndex != null && existingIndex < currentIndex) 0 else 1
        return target.coerceIn(0, if (existingIndex == null) count else (count - 1).coerceAtLeast(0))
    }
    fun shouldPrefetchRadio(nearEnd: Boolean, initial: Boolean, collection: Boolean, repeat: Boolean, autoplay: Boolean, forced: Boolean): Boolean =
        !repeat && (autoplay || forced) && (nearEnd || (initial && !collection))
    fun radioAppend(existingIds: Set<String>, candidates: List<Track>, capacity: Int): List<Track> =
        prepare(candidates).filter { it.id !in existingIds }.take(capacity.coerceAtLeast(0))
    fun prepare(tracks: List<Track>): List<Track> =
        tracks.filter { it.id.isNotBlank() }.distinctBy { it.id }.take(LIMIT)
}
