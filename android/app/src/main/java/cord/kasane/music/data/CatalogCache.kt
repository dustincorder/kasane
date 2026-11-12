package cord.kasane.music.data

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.auth.AccountIdentity
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.CatalogPage
import cord.kasane.music.model.CatalogShelf
import cord.kasane.music.provider.AccountCollection
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persistent catalog metadata only; audio stream URLs are never part of CatalogPage. */
interface CatalogCacheStore {
    suspend fun read(scope: String, key: String): CacheEntry?
    suspend fun write(scope: String, key: String, page: CatalogPage, now: Long)
    suspend fun clearScope(scope: String)
    suspend fun evict(olderThan: Long, keep: Int)
}

class CatalogCache(private val store: CatalogCacheStore) {
    private val memory = object : LinkedHashMap<String, CacheEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean = size > MEMORY_ENTRIES
    }

    suspend fun read(scope: String, key: String): CacheEntry? {
        val memoryKey = "$scope|$key"
        synchronized(memory) { memory[memoryKey]?.let { return it } }
        val loaded = store.read(scope, key) ?: return null
        synchronized(memory) { memory[memoryKey] = loaded }
        return loaded
    }

    suspend fun write(scope: String, key: String, page: CatalogPage, now: Long = System.currentTimeMillis()) {
        val estimated = page.shelves.sumOf { shelf -> shelf.title.length + shelf.items.sumOf { it.title.length + it.subtitle.length + (it.artwork?.length ?: 0) + 64 } }
        if (estimated > MAX_PAGE_CHARS) return
        synchronized(memory) { memory["$scope|$key"] = CacheEntry(page, now) }
        store.write(scope, key, page, now)
        store.evict(now - MAX_AGE_MS, MAX_ENTRIES)
    }

    suspend fun clearAccount(session: AccountSession) {
        val scope = accountScope(session)
        synchronized(memory) { memory.keys.removeAll { it.startsWith("$scope|") } }
        store.clearScope(scope)
    }

    companion object {
        const val MEMORY_ENTRIES = 64
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_ENTRIES = 100
        const val MAX_PAGE_CHARS = 512 * 1024
        fun accountScope(session: AccountSession?, identity: AccountIdentity? = null): String {
            if (session == null) return "guest"
            val raw = listOf(session.channelId.orEmpty(), session.dataSyncId.orEmpty(), session.authUser.toString(),
                identity?.name.orEmpty(), identity?.avatar.orEmpty(), identity?.channelId.orEmpty()).joinToString("|")
            return "account:" + sha256(raw)
        }
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        internal fun itemKey(scope: String, item: cord.kasane.music.model.CatalogItem): String = sha256("$scope|${item.kind.name}|${item.id}")
    }
}

class RoomCatalogCacheStore(private val dao: KasaneDatabase.CatalogCacheDao) : CatalogCacheStore {
    override suspend fun read(scope: String, key: String): CacheEntry? {
        val page = dao.getPage(scope, key) ?: return null
        val shelves = dao.getShelves(scope, key)
        val links = dao.getShelfItems(scope, key)
        val items = if (links.isEmpty()) emptyMap() else dao.getItems(links.map { it.itemKey }.distinct())
            .associateBy { it.itemKey }
        val resolved = shelves.map { shelf ->
            val rows = links.filter { it.shelfIndex == shelf.shelfIndex }
            val content = rows.mapNotNull { row -> items[row.itemKey]?.let { item ->
                runCatching { CatalogKind.valueOf(item.kind) }.getOrNull()?.let { kind ->
                    cord.kasane.music.model.CatalogItem(item.id, kind, item.title, item.subtitle, item.artwork, item.durationSeconds)
                }
            } }
            CatalogShelf(shelf.title, content)
        }
        return CacheEntry(CatalogPage(page.title, page.subtitle, page.artwork, resolved,
            page.continuation, page.description), page.updatedAt)
    }

    override suspend fun write(scope: String, key: String, page: CatalogPage, now: Long) = dao.save(scope, key, page, now)
    override suspend fun clearScope(scope: String) = dao.clearScope(scope)
    override suspend fun evict(olderThan: Long, keep: Int) = dao.evictAndLimit(olderThan, keep)
}

data class CacheEntry(val page: CatalogPage, val updatedAt: Long)

interface CatalogRemote {
    suspend fun home(account: AccountSession? = null): CatalogPage
    suspend fun browse(id: String, continuation: String? = null): CatalogPage
    suspend fun search(query: String, kind: CatalogKind, continuation: String? = null): CatalogPage
    suspend fun collectionFor(account: AccountSession, collection: AccountCollection, continuation: String? = null): CatalogPage
    suspend fun playlistFor(account: AccountSession, playlistId: String, continuation: String? = null): CatalogPage
}

/** Aggregate counters intentionally contain no query, account, track, or cookie data. */
class CatalogRequestDiagnostics {
    private val counts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private data class Timing(val count: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(),
                             val totalMs: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(),
                             val maxMs: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong())
    private val timings = java.util.concurrent.ConcurrentHashMap<String, Timing>()
    fun record(category: String, elapsedMs: Long? = null) {
        counts.computeIfAbsent(category) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        if (elapsedMs != null) {
            val timing = timings.computeIfAbsent(category) { Timing() }
            timing.count.incrementAndGet(); timing.totalMs.addAndGet(elapsedMs)
            timing.maxMs.updateAndGet { maxOf(it, elapsedMs) }
        }
    }
    fun snapshot(): Map<String, Long> = counts.mapValues { it.value.get() }.toSortedMap()
    fun timingSnapshot(): Map<String, Map<String, Long>> = timings.mapValues { (_, value) ->
        val n = value.count.get().coerceAtLeast(1)
        mapOf("count" to value.count.get(), "averageMs" to value.totalMs.get() / n, "maxMs" to value.maxMs.get())
    }.toSortedMap()
}

/** Shared cache policy used by screens. Network work is always explicitly requested. */
class CachedCatalogRepository(
    private val cache: CatalogCache,
    private val catalog: CatalogRemote,
    val diagnostics: CatalogRequestDiagnostics = CatalogRequestDiagnostics(),
) {
    private val flightLock = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<CatalogPage>>()

    private suspend fun fetchOnce(key: String, category: String, block: suspend () -> CatalogPage): CatalogPage {
        val (deferred, leader) = flightLock.withLock {
            inFlight[key]?.let { return@withLock it to false }
            CompletableDeferred<CatalogPage>().also { inFlight[key] = it } to true
        }
        if (!leader) return deferred.await()
        val startedAt = System.nanoTime()
        try {
            val result = block()
            deferred.complete(result)
            return result
        } catch (failure: Throwable) {
            deferred.completeExceptionally(failure)
            throw failure
        } finally {
            diagnostics.record(category, (System.nanoTime() - startedAt) / 1_000_000)
            flightLock.withLock { if (inFlight[key] === deferred) inFlight.remove(key) }
        }
    }

    suspend fun cached(scope: String, key: String) = cache.read(scope, key)

    suspend fun refresh(scope: String, key: String, fetch: suspend () -> CatalogPage): CatalogPage {
        val page = fetch()
        cache.write(scope, key, page)
        return page
    }

    suspend fun browse(scope: String, id: String, continuation: String? = null, force: Boolean = false): CatalogPage {
        val key = "browse:$id"
        if (continuation == null) {
            val saved = cache.read(scope, key)
            if (!force && saved != null && System.currentTimeMillis() - saved.updatedAt < LIBRARY_TTL_MS) return saved.page
        }
        val flight = "browse:$scope:$id:${continuation?.let(::tokenKey).orEmpty()}"
        val fetched = fetchOnce(flight, "catalog") { catalog.browse(id, continuation) }
        val merged = if (continuation == null) fetched else {
            val old = cache.read(scope, key)?.page
            if (old == null) fetched else old.copy(
                title = fetched.title.ifBlank { old.title }, subtitle = fetched.subtitle.ifBlank { old.subtitle },
                artwork = fetched.artwork ?: old.artwork, description = fetched.description.ifBlank { old.description },
                shelves = (old.shelves + fetched.shelves).groupBy { it.title }.map { (title, shelves) ->
                    CatalogShelf(title, shelves.flatMap { it.items }.distinctBy { it.kind to it.id })
                }, continuation = fetched.continuation
            )
        }
        cache.write(scope, key, merged)
        return merged
    }

    suspend fun home(scope: String, account: AccountSession? = null, force: Boolean = false, now: Long = System.currentTimeMillis()): CatalogPage? {
        val saved = cache.read(scope, HOME_KEY)
        if (!force && saved != null && now - saved.updatedAt < HOME_TTL_MS) return saved.page
        return fetchOnce("home:$scope", "home") {
            refresh(scope, HOME_KEY) { catalog.home(account) }
        }
    }

    suspend fun library(scope: String, session: AccountSession, collection: AccountCollection,
                        playlistId: String? = null, continuation: String? = null, force: Boolean = false): CatalogPage {
        val key = if (playlistId == null) "library:${collection.name}" else "playlist:$playlistId"
        if (continuation == null) {
            val saved = cache.read(scope, key)
            if (!force && saved != null && System.currentTimeMillis() - saved.updatedAt < LIBRARY_TTL_MS) return saved.page
        }
        val flight = "library:$scope:$key:${continuation?.let(::tokenKey).orEmpty()}"
        val fetched = fetchOnce(flight, "library") {
            if (playlistId == null) catalog.collectionFor(session, collection, continuation)
            else catalog.playlistFor(session, playlistId, continuation)
        }
        val merged = if (continuation == null) fetched else {
            val old = cache.read(scope, key)?.page
            if (old == null) fetched else old.copy(
                shelves = (old.shelves + fetched.shelves).groupBy { it.title }.map { (title, shelves) ->
                    CatalogShelf(title, shelves.flatMap { it.items }.distinctBy { it.kind to it.id })
                }, continuation = fetched.continuation
            )
        }
        cache.write(scope, key, merged)
        return merged
    }

    suspend fun search(scope: String, query: String, kind: CatalogKind,
                       continuation: String? = null, force: Boolean = false): CatalogPage {
        val key = "search:${kind.name}:${sha256(query.trim().lowercase())}"
        if (continuation == null && !force) cache.read(scope, key)?.let {
            if (System.currentTimeMillis() - it.updatedAt < SEARCH_TTL_MS) return it.page
        }
        val flight = "search:$scope:$key:${continuation?.let(::tokenKey).orEmpty()}"
        val fetched = fetchOnce(flight, "search") { catalog.search(query, kind, continuation) }
        if (continuation == null) {
            cache.write(scope, key, fetched)
            return fetched
        }
        val old = cache.read(scope, key)?.page
        val merged = if (old == null) fetched else old.copy(
            shelves = (old.shelves + fetched.shelves).groupBy { it.title }.map { (title, shelves) ->
                CatalogShelf(title, shelves.flatMap { it.items }.distinctBy { it.kind to it.id })
            }, continuation = fetched.continuation
        )
        cache.write(scope, key, merged)
        return merged
    }

    companion object {
        const val HOME_KEY = "home"
        const val HOME_TTL_MS = 6L * 60 * 60 * 1000
        const val LIBRARY_TTL_MS = 12L * 60 * 60 * 1000
        const val SEARCH_TTL_MS = 24L * 60 * 60 * 1000
        private fun tokenKey(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
