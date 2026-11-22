package cord.kasane.music

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import cord.kasane.music.offline.ConfigurableLruEvictor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** Real Media3 caches on the JVM: proves eviction, pinning and cache-only reads behave as the offline library assumes. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OfflineCacheIntegrationTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var temp: SimpleCache
    private lateinit var saved: SimpleCache
    private var limit = 1_000L
    private val evictor = ConfigurableLruEvictor { limit }

    @Before fun setUp() {
        @Suppress("DEPRECATION")
        temp = SimpleCache(folder.newFolder("temp"), evictor)
        @Suppress("DEPRECATION")
        saved = SimpleCache(folder.newFolder("saved"), NoOpCacheEvictor())
    }
    @After fun tearDown() { temp.release(); saved.release() }

    private fun upstream(bytes: ByteArray) = DataSource.Factory { ByteArrayDataSource(bytes) }
    private fun write(cache: SimpleCache, key: String, bytes: ByteArray) {
        val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream(bytes)).createDataSource()
        CacheWriter(source, DataSpec.Builder().setUri("https://example.invalid/$key").setKey(key).build(), null, null).cache()
    }
    private fun readCacheOnly(cache: SimpleCache, key: String): ByteArray {
        val source = CacheDataSource.Factory().setCache(cache).setCacheWriteDataSinkFactory(null).createDataSource()
        source.open(DataSpec.Builder().setUri("kasane-cache://audio/$key").setKey(key).build())
        return try { source.use { ByteArray(0) + it.readAllBytes() } } finally { source.close() }
    }
    private fun DataSource.readAllBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(256)
        while (true) { val n = read(buf, 0, buf.size); if (n < 0) break; out.write(buf, 0, n) }
        return out.toByteArray()
    }
    private inline fun <R> DataSource.use(block: (DataSource) -> R): R = try { block(this) } finally { close() }

    @Test fun cachedBytesAreReusedWithoutAnyUpstream() {
        val audio = ByteArray(400) { it.toByte() }
        write(temp, "ytm:a:140", audio)
        assertEquals(400, temp.getCachedBytes("ytm:a:140", 0, 400))
        assertArrayEquals(audio, readCacheOnly(temp, "ytm:a:140"))
    }

    @Test fun cacheOnlyReadOfAMissingResourceFailsInsteadOfStreaming() {
        assertThrows(IOException::class.java) { readCacheOnly(temp, "ytm:missing:140") }
    }

    @Test fun lruEvictsLeastRecentlyUsedTrackWhenOverTheLimit() {
        write(temp, "old", ByteArray(400))
        Thread.sleep(5)
        write(temp, "mid", ByteArray(400))
        Thread.sleep(5)
        readCacheOnly(temp, "old") // touch: "old" is now newer than "mid"
        Thread.sleep(5)
        write(temp, "new", ByteArray(400))
        assertTrue("limit respected", temp.cacheSpace <= limit)
        assertEquals("least recently used track was evicted", 0, temp.getCachedBytes("mid", 0, 400))
        assertEquals(400, temp.getCachedBytes("old", 0, 400))
        assertEquals(400, temp.getCachedBytes("new", 0, 400))
    }

    @Test fun loweringTheLimitTrimsImmediately() {
        write(temp, "a", ByteArray(400)); write(temp, "b", ByteArray(400))
        assertEquals(800, temp.cacheSpace)
        limit = 400
        evictor.trim(temp)
        assertTrue(temp.cacheSpace <= 400)
    }

    @Test fun savedCacheIsNeverEvictedAndIsIndependentOfTheTempLimit() {
        write(saved, "pinned", ByteArray(900))
        limit = 100
        write(temp, "streamed", ByteArray(90))
        evictor.trim(temp)
        assertEquals("pinned audio survives any temp pressure", 900, saved.getCachedBytes("pinned", 0, 900))
        write(temp, "more", ByteArray(90))
        assertEquals(900, saved.getCachedBytes("pinned", 0, 900))
    }

    @Test fun interruptedDownloadResumesFromWrittenBytes() {
        val audio = ByteArray(300) { (it % 251).toByte() }
        // Simulate an interrupted transfer: only the first 100 bytes were cached.
        val partial = CacheDataSource.Factory().setCache(saved).setUpstreamDataSourceFactory(upstream(audio)).createDataSource()
        CacheWriter(partial, DataSpec.Builder().setUri("https://example.invalid/r").setKey("r").setLength(100).build(), null, null).cache()
        assertEquals(100, saved.getCachedBytes("r", 0, 300))
        // Resuming the whole resource only fetches what is missing and ends complete.
        write(saved, "r", audio)
        assertEquals(300, saved.getCachedBytes("r", 0, 300))
        assertArrayEquals(audio, readCacheOnly(saved, "r"))
    }
}
