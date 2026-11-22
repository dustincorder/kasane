package cord.kasane.music.offline

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan

/** Media3's LRU evictor is final and fixes its limit at construction; this one reads the user's limit live. */
@OptIn(UnstableApi::class)
class ConfigurableLruEvictor(private val limitBytes: () -> Long) : CacheEvictor {
    private val budget = LruBudget<CacheSpan>(
        touchedAt = { it.lastTouchTimestamp },
        sizeOf = { it.length },
        tieBreak = compareBy<CacheSpan>({ it.key }, { it.position }),
    )

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() {}

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }
    override fun onSpanAdded(cache: Cache, span: CacheSpan) { budget.add(span); evict(cache, 0) }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) { budget.remove(span) }
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) { budget.touch(oldSpan, newSpan) }

    /** Apply a lowered limit immediately. */
    fun trim(cache: Cache) = evict(cache, 0)

    private fun evict(cache: Cache, incoming: Long) {
        for (span in budget.evictionsFor(incoming, limitBytes())) {
            try { cache.removeSpan(span) } catch (_: Cache.CacheException) { /* The span is already gone. */ }
        }
    }
}
