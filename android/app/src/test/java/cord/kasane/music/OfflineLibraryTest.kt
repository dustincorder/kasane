package cord.kasane.music

import cord.kasane.music.model.Track
import cord.kasane.music.offline.AudioFileRecord
import cord.kasane.music.offline.AudioState
import cord.kasane.music.offline.LruBudget
import cord.kasane.music.offline.OfflineGateInterceptor
import cord.kasane.music.offline.OfflineModeException
import cord.kasane.music.offline.OfflinePolicy
import cord.kasane.music.provider.HlsStream
import cord.kasane.music.provider.ProgressiveStream
import cord.kasane.music.provider.ResolvedAudioStream
import cord.kasane.music.provider.UnavailableStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class OfflineLibraryTest {
    private data class Span(val key: String, val position: Long, val length: Long, val touched: Long)
    private fun budget() = LruBudget<Span>({ it.touched }, { it.length }, compareBy<Span>({ it.key }, { it.position }))

    @Test fun lruEvictsOldestFirstAndOnlyAsMuchAsNeeded() {
        val b = budget()
        val old = Span("a", 0, 400, 1); val mid = Span("b", 0, 400, 2); val fresh = Span("c", 0, 400, 3)
        listOf(fresh, old, mid).forEach(b::add)
        assertEquals(1200, b.totalBytes)
        assertEquals(listOf(old), b.evictionsFor(0, 1000))
        assertEquals(listOf(old), b.evictionsFor(0, 800))
        assertEquals(listOf(old, mid), b.evictionsFor(0, 700))
        assertEquals(listOf(old, mid), b.evictionsFor(300, 1000))
        assertTrue(b.evictionsFor(0, 1200).isEmpty())
    }

    @Test fun touchingAnItemProtectsItFromEviction() {
        val b = budget()
        val a = Span("a", 0, 500, 1); val c = Span("c", 0, 500, 2)
        b.add(a); b.add(c)
        val touched = a.copy(touched = 9)
        b.touch(a, touched)
        assertEquals(listOf(c), b.evictionsFor(0, 500))
        b.remove(c)
        assertEquals(500, b.totalBytes)
    }

    @Test fun cacheKeysNeverCollideAcrossFormats() {
        assertNotEquals(OfflinePolicy.cacheKey("abcdefghijk", 140), OfflinePolicy.cacheKey("abcdefghijk", 251))
        assertEquals("ytm:abcdefghijk:140", OfflinePolicy.cacheKey("abcdefghijk", 140))
    }

    @Test fun onlyYoutubeTracksWithValidIdsCanBeSaved() {
        assertTrue(OfflinePolicy.canRequestSave(Track("abcdefghijk", "t", "a")))
        assertFalse(OfflinePolicy.canRequestSave(Track("short", "t", "a")))
        assertFalse(OfflinePolicy.canRequestSave(Track("abcdefghijk", "t", "a", isLocal = true)))
        assertFalse(OfflinePolicy.canRequestSave(Track("abcdefghijk", "t", "a", sourceProvider = "local")))
    }

    @Test fun onlyPlainProgressiveAudioIsSavable() {
        val audio = ResolvedAudioStream("https://rr1.googlevideo.com/x", 140, "audio/mp4", Long.MAX_VALUE, 1000)
        assertEquals(OfflinePolicy.Eligibility.Direct, OfflinePolicy.eligibility(ProgressiveStream(audio, "WEB_REMIX")))
        val unknownSize = ResolvedAudioStream("https://rr1.googlevideo.com/x", 140, "audio/mp4", Long.MAX_VALUE, -1)
        assertTrue(OfflinePolicy.eligibility(ProgressiveStream(unknownSize, "WEB_REMIX")) is OfflinePolicy.Eligibility.NotSavable)
        assertTrue(OfflinePolicy.eligibility(HlsStream("https://x")) is OfflinePolicy.Eligibility.NotSavable)
        val unavailable = OfflinePolicy.eligibility(UnavailableStream("Sign in required."))
        assertEquals("Sign in required.", (unavailable as OfflinePolicy.Eligibility.NotSavable).reason)
    }

    @Test fun downloadsWaitForOfflineModeAndMeteredNetworks() {
        assertNotNull(OfflinePolicy.blockReason(offlineOnly = true, wifiOnly = false, metered = false))
        assertNotNull(OfflinePolicy.blockReason(offlineOnly = false, wifiOnly = true, metered = true))
        assertNull(OfflinePolicy.blockReason(offlineOnly = false, wifiOnly = true, metered = false))
        assertNull(OfflinePolicy.blockReason(offlineOnly = false, wifiOnly = false, metered = true))
    }

    @Test fun partialCacheIsNeverTreatedAsPlayable() {
        val row = AudioFileRecord("abcdefghijk", "ytm:abcdefghijk:140", 140, "audio/mp4", 1000, AudioState.CACHED.key, false, 0, null, false, 0, 0)
        assertTrue(OfflinePolicy.playableFromCache(row, 1000))
        assertFalse(OfflinePolicy.playableFromCache(row, 999))
        assertFalse(OfflinePolicy.playableFromCache(row.copy(contentLength = 0), 0))
        assertFalse(OfflinePolicy.playableFromCache(row.copy(cacheKey = ""), 1000))
        assertFalse(OfflinePolicy.playableFromCache(null, 1000))
    }

    @Test fun cacheSizeStepsSnapToOfferedValues() {
        assertEquals(1024, OfflinePolicy.nearestCacheStep(1000))
        assertEquals(256, OfflinePolicy.nearestCacheStep(10))
        assertEquals(16384, OfflinePolicy.nearestCacheStep(99999))
        assertEquals("1.5 MB", OfflinePolicy.formatBytes(1_572_864).replace(',', '.'))
    }

    @Test fun offlineOnlyBlocksEveryRequestBeforeItLeavesTheApp() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            server.start()
            var offline = true
            val client = OkHttpClient.Builder().addInterceptor(OfflineGateInterceptor { offline }).build()
            val request = Request.Builder().url(server.url("/")).build()
            assertThrows(OfflineModeException::class.java) { client.newCall(request).execute() }
            assertEquals(0, server.requestCount)
            offline = false
            client.newCall(request).execute().use { assertEquals("ok", it.body?.string()) }
            assertEquals(1, server.requestCount)
        }
    }
}
