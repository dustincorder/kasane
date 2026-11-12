package cord.kasane.music

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.auth.AccountIdentity
import cord.kasane.music.data.CacheEntry
import cord.kasane.music.data.CachedCatalogRepository
import cord.kasane.music.data.CatalogCache
import cord.kasane.music.data.CatalogCacheStore
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.CatalogPage
import cord.kasane.music.model.CatalogShelf
import cord.kasane.music.model.CatalogItem
import cord.kasane.music.provider.AccountCollection
import cord.kasane.music.data.CatalogRemote
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.*
import org.junit.Test

class CatalogCacheTest {
    private open class MemoryStore : CatalogCacheStore {
        val rows = linkedMapOf<Pair<String, String>, CacheEntry>()
        override suspend fun read(scope: String, key: String) = rows[scope to key]
        override suspend fun write(scope: String, key: String, page: CatalogPage, now: Long) {
            rows[scope to key] = CacheEntry(page, now)
        }
        override suspend fun clearScope(scope: String) { rows.keys.removeAll { it.first == scope } }
        override suspend fun evict(olderThan: Long, keep: Int) {
            rows.entries.removeAll { it.value.updatedAt < olderThan }
            val keepKeys = rows.entries.sortedByDescending { it.value.updatedAt }.take(keep).map { it.key }.toSet()
            rows.keys.retainAll(keepKeys)
        }
    }

    private open class FakeRemote : CatalogRemote {
        var homes = 0
        var searches = 0
        var collections = 0
        var browses = 0
        var failure: Exception? = null
        var response = page("From server")
        override suspend fun home(account: AccountSession?): CatalogPage { homes++; failure?.let { throw it }; return response }
        override suspend fun browse(id: String, continuation: String?) = page("Browse").also { browses++ }
        override suspend fun search(query: String, kind: CatalogKind, continuation: String?) = page("Search").also { searches++ }
        override suspend fun collectionFor(account: AccountSession, collection: AccountCollection, continuation: String?) =
            (if (continuation == null) page("Collection").copy(continuation = "page-2") else CatalogPage("Collection", "", null,
                listOf(CatalogShelf("Items", listOf(
                    CatalogItem("id", CatalogKind.SONG, "Duplicate"), CatalogItem("next", CatalogKind.SONG, "Next")
                ))))).also { collections++ }
        override suspend fun playlistFor(account: AccountSession, playlistId: String, continuation: String?) = page("Playlist")
    }

    private val session get() = AccountSession("SAPISID=secret-fixture", channelId = "channel-a", dataSyncId = "sync-a")

    @Test fun firstHomeOpenFetchesAndStoresMetadata() = runTest {
        val remote = FakeRemote()
        val repo = CachedCatalogRepository(CatalogCache(MemoryStore()), remote)
        assertEquals("From server", repo.home("guest")?.title)
        assertEquals(1, remote.homes)
    }

    @Test fun memoryCacheServesRepeatedReadWithoutRoomRoundTrip() = runTest {
        val store = object : MemoryStore() {
            var reads = 0
            override suspend fun read(scope: String, key: String): CacheEntry? { reads++; return super.read(scope, key) }
        }
        val cache = CatalogCache(store)
        cache.write("guest", "home", page("Cached"), now = 123)
        assertEquals("Cached", cache.read("guest", "home")?.page?.title)
        assertEquals("Cached", cache.read("guest", "home")?.page?.title)
        assertEquals(0, store.reads)
    }

    @Test fun concurrentHomeRefreshesShareOneRemoteRequest() = runTest {
        val remote = object : FakeRemote() {
            override suspend fun home(account: AccountSession?): CatalogPage {
                homes++
                kotlinx.coroutines.delay(100)
                return response
            }
        }
        val repo = CachedCatalogRepository(CatalogCache(MemoryStore()), remote)
        listOf(async { repo.home("guest", force = true) }, async { repo.home("guest", force = true) }).awaitAll()
        assertEquals(1, remote.homes)
    }

    @Test fun repeatedHomeOpenUsesFreshCacheWithoutNetworkRequest() = runTest {
        val remote = FakeRemote()
        val dao = MemoryStore()
        val repo = CachedCatalogRepository(CatalogCache(dao), remote)
        repo.home("guest")
        // A new repository/cache instance represents an application restart; Room data remains.
        val afterRestart = CachedCatalogRepository(CatalogCache(dao), remote)
        assertEquals("From server", afterRestart.home("guest")?.title)
        assertEquals(1, remote.homes)
        assertEquals(0L, afterRestart.diagnostics.snapshot()["home"] ?: 0L)
    }

    @Test fun forceRefreshUpdatesCacheWithoutClearingPreviousFirst() = runTest {
        val remote = FakeRemote()
        val repo = CachedCatalogRepository(CatalogCache(MemoryStore()), remote)
        repo.home("guest")
        remote.response = page("Fresh")
        assertEquals("Fresh", repo.home("guest", force = true)?.title)
        assertEquals(2, remote.homes)
    }

    @Test fun failedRefreshLeavesCachedPageAvailable() = runTest {
        val remote = FakeRemote()
        val repo = CachedCatalogRepository(CatalogCache(MemoryStore()), remote)
        repo.home("guest")
        remote.failure = java.io.IOException("offline")
        runCatching { repo.home("guest", force = true) }
        assertEquals("From server", repo.cached("guest", CachedCatalogRepository.HOME_KEY)?.page?.title)
    }

    @Test fun accountAndChannelCachesAreIsolatedAndDoNotExposeCookies() = runTest {
        val remote = FakeRemote()
        val cache = CatalogCache(MemoryStore())
        val repo = CachedCatalogRepository(cache, remote)
        val scopeA = CatalogCache.accountScope(session)
        val scopeB = CatalogCache.accountScope(AccountSession("SAPISID=another-secret", channelId = "channel-b", dataSyncId = "sync-b"))
        repo.home(scopeA)
        assertNull(repo.cached(scopeB, CachedCatalogRepository.HOME_KEY))
        assertFalse(scopeA.contains("secret"))
    }

    @Test fun accountsWithoutChannelIdsStillGetDistinctHashedScopes() {
        val accountA = AccountSession("SAPISID=first-secret")
        val accountB = AccountSession("SAPISID=second-secret")
        val scopeA = CatalogCache.accountScope(accountA, AccountIdentity("Listener A", null, null))
        val scopeB = CatalogCache.accountScope(accountB, AccountIdentity("Listener B", null, null))
        assertNotEquals(scopeA, scopeB)
        assertFalse(scopeA.contains("Listener A"))
    }

    @Test fun searchCacheIsScopedByQueryAndCategoryAndRepeatedAccessDoesNotFetch() = runTest {
        val remote = FakeRemote()
        val repo = CachedCatalogRepository(CatalogCache(MemoryStore()), remote)
        repo.search("guest", "artist", CatalogKind.SONG)
        repo.search("guest", "artist", CatalogKind.SONG)
        repo.search("guest", "artist", CatalogKind.ALBUM)
        assertEquals(2, remote.searches)
        assertEquals(2L, repo.diagnostics.snapshot()["search"])
    }

    @Test fun browseDetailUsesPersistentCacheOnReopen() = runTest {
        val dao = MemoryStore()
        val remote = FakeRemote()
        val first = CachedCatalogRepository(CatalogCache(dao), remote)
        first.browse("guest", "album-id")
        val afterReopen = CachedCatalogRepository(CatalogCache(dao), remote)
        assertEquals("Browse", afterReopen.browse("guest", "album-id").title)
        assertEquals(1, remote.browses)
    }

    @Test fun libraryFetchIsCachedPerChannelAndDeduplicatesMergedPage() = runTest {
        val remote = FakeRemote()
        val dao = MemoryStore()
        val cache = CatalogCache(dao)
        val repo = CachedCatalogRepository(cache, remote)
        val scope = CatalogCache.accountScope(session)
        val first = repo.library(scope, session, AccountCollection.PLAYLISTS)
        val merged = repo.library(scope, session, AccountCollection.PLAYLISTS, continuation = first.continuation)
        repo.library(scope, session, AccountCollection.PLAYLISTS)
        assertEquals(2, remote.collections)
        assertEquals(listOf("id", "next"), merged.shelves.flatMap { it.items }.map { it.id }.distinct())
        cache.clearAccount(session)
        assertNull(cache.read(scope, "library:PLAYLISTS"))
    }

    private companion object {
        fun page(title: String) = CatalogPage(title, "", null, listOf(CatalogShelf("Items", listOf(
            CatalogItem("id", CatalogKind.SONG, title, artwork = "https://img.example/art.jpg")
        ))))
    }
}
