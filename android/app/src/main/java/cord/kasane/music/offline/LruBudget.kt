package cord.kasane.music.offline

import java.util.TreeSet

/**
 * Least-recently-used ordering with a byte budget. Generic so the eviction rule is testable
 * without Media3; [ConfigurableLruEvictor] adapts it to cache spans.
 */
class LruBudget<T : Any>(
    private val touchedAt: (T) -> Long,
    private val sizeOf: (T) -> Long,
    tieBreak: Comparator<T>,
) {
    private val ordered = TreeSet<T>(compareBy<T> { touchedAt(it) }.then(tieBreak))
    var totalBytes = 0L
        private set

    fun add(item: T) { if (ordered.add(item)) totalBytes += sizeOf(item) }
    fun remove(item: T) { if (ordered.remove(item)) totalBytes -= sizeOf(item) }
    fun touch(old: T, new: T) { remove(old); add(new) }

    /** Oldest-first items to drop so that `totalBytes + incoming <= limitBytes`. */
    fun evictionsFor(incomingBytes: Long, limitBytes: Long): List<T> {
        var projected = totalBytes + incomingBytes
        if (projected <= limitBytes) return emptyList()
        val out = ArrayList<T>()
        for (item in ordered) {
            if (projected <= limitBytes) break
            out += item
            projected -= sizeOf(item)
        }
        return out
    }
}
