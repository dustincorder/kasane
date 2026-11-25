package cord.kasane.music

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.room.Room
import cord.kasane.music.data.KasaneDatabase
import cord.kasane.music.model.Track
import cord.kasane.music.offline.AudioFileRecord
import cord.kasane.music.offline.AudioState
import cord.kasane.music.offline.AudioStores
import cord.kasane.music.offline.OfflineManager
import cord.kasane.music.provider.PlaybackStream
import cord.kasane.music.provider.ProgressiveStream
import cord.kasane.music.provider.ResolvedAudioStream
import cord.kasane.music.provider.UnavailableStream
import cord.kasane.music.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo
import java.io.IOException
import java.util.concurrent.CountDownLatch

/**
 * The whole offline pipeline on real Media3 caches and a real Room database: queueing, byte-exact
 * downloads, the "never offer partial audio" guarantee, network-loss behaviour and removal.
 * What a test leaves in the saved store is exactly what tomorrow's no-network playback reads.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineManagerTest {
    private val videoId = "dQw4w9WgXcQ"
    private val audio = ByteArray(3000) { (it % 251).toByte() }
    private val track = Track(videoId, "Title", "Artist")

    private lateinit var app: Application
    private lateinit var db: KasaneDatabase
    private lateinit var settings: AppSettings
    private lateinit var stores: AudioStores
    private lateinit var scope: CoroutineScope
    private var resolveResult: PlaybackStream? = null
    private var upstream: DataSource.Factory = DataSource.Factory { ByteArrayDataSource(audio) }
    private val dynamicFactory = DataSource.Factory { upstream.createDataSource() }

    @Before fun setUp() {
        @Suppress("DEPRECATION")
        app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, KasaneDatabase::class.java).build()
        settings = AppSettings(app).apply {
            setOfflineOnly(false); setWifiOnlyDownloads(false); setCacheEnabled(true)
        }
        setNetwork(true)
        stores = AudioStores(app, settings)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        resolveResult = ProgressiveStream(ResolvedAudioStream("https://x/v", 140, "audio/mp4", Long.MAX_VALUE, audio.size.toLong()), "TEST")
        upstream = DataSource.Factory { ByteArrayDataSource(audio) }
    }

    @After fun tearDown() {
        scope.cancel()
        runCatching { stores.temp.release() }
        runCatching { stores.saved.release() }
        stores.tempDirectory.deleteRecursively()
        stores.savedDirectory.deleteRecursively()
        db.close()
    }

    private fun manager() = OfflineManager(app, db.audioDao(), db.trackDao(), stores, settings,
        resolve = { resolveResult ?: throw IllegalStateException("resolve must not be called") },
        network = dynamicFactory, http = OkHttpClient(), scope = scope)

    private fun setNetwork(up: Boolean) {
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = shadowOf(connectivity)
        if (up) {
            val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI, 0, true, true)
            shadow.addNetwork(ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI), info)
            shadow.setActiveNetworkInfo(info)
            shadow.setDefaultNetworkActive(true)
        } else {
            shadow.setActiveNetworkInfo(null)
            shadow.setDefaultNetworkActive(false)
        }
    }

    private suspend fun awaitRow(timeout: Long = 30_000, predicate: (AudioFileRecord?) -> Boolean): AudioFileRecord? {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val row = db.audioDao().get(videoId)
            if (predicate(row)) return row
            delay(25)
        }
        return null
    }

    private suspend fun awaitDeleted(timeout: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (db.audioDao().get(videoId) == null) return true
            delay(25)
        }
        return false
    }

    private fun cacheOnlyBytes(key: String): ByteArray {
        val source = stores.cacheOnlyFactory().createDataSource()
        source.open(DataSpec.Builder().setUri("kasane-cache://audio/$videoId").setKey(key).build())
        val out = java.io.ByteArrayOutputStream()
        try {
            val buf = ByteArray(256)
            while (true) { val n = source.read(buf, 0, buf.size); if (n < 0) break; out.write(buf, 0, n) }
        } catch (e: IOException) { throw IOException("hole after ${out.size()} bytes of key=$key", e) }
        finally { source.close() }
        return out.toByteArray()
    }

    /** One blocking data source lets a test observe the DOWNLOADING state deterministically. */
    private class LatchedDataSource(private val latch: CountDownLatch, bytes: ByteArray) : BaseDataSource(false) {
        private val delegate = ByteArrayDataSource(bytes)
        override fun open(dataSpec: DataSpec): Long { latch.await(); return delegate.open(dataSpec) }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate.read(buffer, offset, length)
        override fun getUri() = delegate.uri
        override fun close() = delegate.close()
    }

    @Test fun savedTrackPlaysEntirelyWithoutANetworkAndCanBeRemoved() = runBlocking {
        val manager = manager()
        assertEquals(1, manager.save(listOf(track)).queued)
        val saved = awaitRow { it?.audioState == AudioState.SAVED }
        assertNotNull("download never finished", saved)
        assertTrue(saved!!.complete)

        // What plays tomorrow in airplane mode: no resolver, no network, byte for byte.
        resolveResult = null
        upstream = DataSource.Factory { error("offline playback must not touch the network") }
        val local = manager.local(videoId)
        assertNotNull(local)
        assertArrayEquals(audio, cacheOnlyBytes(local!!.cacheKey))

        manager.remove(videoId)
        assertTrue("the saved row was not removed", awaitDeleted())
        assertNull(manager.local(videoId))
        assertEquals(0, stores.saved.getCachedBytes(local.cacheKey, 0, audio.size.toLong()))
    }

    @Test fun queuedDownloadWaitsWithoutANetworkAndCompletesWhenItReturns() = runBlocking {
        setNetwork(false)
        val manager = manager()
        manager.save(listOf(track))
        val waiting = awaitRow { it?.audioState == AudioState.QUEUED && it.failure != null }
        assertNotNull("the row was neither queued nor annotated", waiting)
        assertEquals("Waiting for a connection.", waiting!!.failure)

        setNetwork(true)
        settings.setOfflineOnly(true); settings.setOfflineOnly(false) // wake the parked worker
        assertNotNull(awaitRow { it?.audioState == AudioState.SAVED })
        assertNotNull(manager.local(videoId))
    }

    @Test fun offlineOnlyModeHoldsDownloadsUntilReleased() = runBlocking {
        settings.setOfflineOnly(true)
        val manager = manager()
        manager.save(listOf(track))
        val held = awaitRow { it?.audioState == AudioState.QUEUED && it.failure != null }
        assertNotNull(held)
        assertEquals("Offline Only mode is on.", held!!.failure)
        assertNull(manager.local(videoId))

        settings.setOfflineOnly(false)
        assertNotNull(awaitRow { it?.audioState == AudioState.SAVED })
    }

    @Test fun aPartialDownloadIsNeverOfferedAsOfflineAudio() = runBlocking {
        val latch = CountDownLatch(1)
        upstream = DataSource.Factory { LatchedDataSource(latch, audio) }
        val manager = manager()
        manager.save(listOf(track))
        val downloading = awaitRow { it?.audioState == AudioState.DOWNLOADING }
        assertNotNull("download never started", downloading)
        assertNull("an in-flight download must not look playable", manager.local(videoId))

        latch.countDown()
        assertNotNull(awaitRow { it?.audioState == AudioState.SAVED })
        assertNotNull(manager.local(videoId))
    }

    @Test fun permanentFailuresStayFailedAndAreNotRetriedSilently() = runBlocking {
        resolveResult = UnavailableStream("This track is unavailable in this country.", "GEO_BLOCKED")
        val manager = manager()
        manager.save(listOf(track))
        val failed = awaitRow { it?.audioState == AudioState.FAILED }
        assertNotNull(failed)
        assertNull(manager.local(videoId))

        manager.resumeTransientFailures()
        delay(200)
        assertEquals(AudioState.FAILED, db.audioDao().get(videoId)!!.audioState)
    }

    @Test fun connectionFailuresResumeAutomaticallyWhenTheNetworkReturns() = runBlocking {
        resolveResult = UnavailableStream("Could not reach the audio server.", "NETWORK_ERROR")
        val manager = manager()
        manager.save(listOf(track))
        val failed = awaitRow(timeout = 45_000) { it?.audioState == AudioState.FAILED }
        assertNotNull("transient failures must still end as FAILED rows", failed)
        assertNull(manager.local(videoId))

        resolveResult = ProgressiveStream(ResolvedAudioStream("https://x/v", 140, "audio/mp4", Long.MAX_VALUE, audio.size.toLong()), "TEST")
        manager.resumeTransientFailures()
        assertNotNull(awaitRow { it?.audioState == AudioState.SAVED })
    }

    @Test fun savedRowsWhoseBytesVanishedAreMarkedFailedOnStartup() = runBlocking {
        val manager = manager()
        manager.save(listOf(track))
        val saved = awaitRow { it?.audioState == AudioState.SAVED }!!
        stores.saved.removeResource(saved.cacheKey)
        assertNull(manager.local(videoId))

        // A cold start re-verifies every saved row against the bytes actually on disk.
        manager()
        assertNotNull(awaitRow { it?.audioState == AudioState.FAILED })
        assertEquals("Saved audio is missing. Save it again.", db.audioDao().get(videoId)!!.failure)
    }

    @Test fun parallelRangeDownloadAssemblesByteExactAudio() = runBlocking {
        // Above MIN_RANGE_BYTES the file is fetched in several Range requests and committed span by span.
        val big = ByteArray(1_500_000) { ((it * 31 + 7) % 251).toByte() }
        resolveResult = ProgressiveStream(ResolvedAudioStream("https://x/v", 140, "audio/mp4", Long.MAX_VALUE, big.size.toLong()), "TEST")
        upstream = DataSource.Factory { ByteArrayDataSource(big) }
        val manager = manager()
        assertEquals(1, manager.save(listOf(track)).queued)
        val saved = awaitRow { it?.audioState == AudioState.SAVED }
        assertNotNull("parallel download never finished", saved)

        upstream = DataSource.Factory { error("offline playback must not touch the network") }
        assertArrayEquals(big, cacheOnlyBytes(saved!!.cacheKey))
    }

    @Test fun interruptedDownloadsResumeIntoSavedRows() = runBlocking {
        // Simulate a killed process: bytes already written, row left mid-flight, new manager instance.
        val latch = CountDownLatch(1)
        upstream = DataSource.Factory { LatchedDataSource(latch, audio) }
        manager().save(listOf(track))
        awaitRow { it?.audioState == AudioState.DOWNLOADING }
        db.audioDao().resetInterrupted()
        assertEquals(AudioState.QUEUED, db.audioDao().get(videoId)!!.audioState)

        upstream = DataSource.Factory { ByteArrayDataSource(audio) }
        val second = manager()
        latch.countDown()
        assertNotNull(awaitRow { it?.audioState == AudioState.SAVED })
        assertNotNull(second.local(videoId))
    }
}
