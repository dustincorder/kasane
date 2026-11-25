package cord.kasane.desktop.offline

import com.sun.net.httpserver.HttpServer
import cord.kasane.desktop.DesktopSettings
import cord.kasane.music.model.Track
import cord.kasane.music.provider.ResolvedAudioStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end save/download/resume against a real local HTTP server that honours Range requests —
 * no YouTube, no mocks of the byte path. The worker runs on Dispatchers.IO just like in the app.
 */
class DesktopOfflineTest {
    private fun track(id: String, title: String = "Song $id") = Track(id, title, "Artist")

    /** Serves `bytes` with Range support, like googlevideo does for progressive audio. */
    private class AudioServer(val bytes: ByteArray) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
        val url = "http://127.0.0.1:${server.address.port}/audio"
        init {
            server.createContext("/audio") { ex ->
                val range = ex.requestHeaders.getFirst("Range")
                val (from, to) = range?.removePrefix("bytes=")?.split('-')
                    ?.let { it[0].toLong() to it[1].toLong() } ?: (0L to bytes.size - 1L)
                val body = bytes.copyOfRange(from.toInt(), (to + 1).toInt().coerceAtMost(bytes.size))
                ex.sendResponseHeaders(if (range != null) 206 else 200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
                ex.close()
            }
        }
        override fun close() = server.stop(0)
    }

    private class Rig(server: AudioServer, root: File) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = DesktopSettings(File(root, "prefs.json"))
        val offline = DesktopOffline(root, OkHttpClient(), scope, settings, resolveForSave = { id ->
            if (id == "missing0000") null
            else ResolvedAudioStream(server.url, itag = 140, mimeType = "audio/mp4",
                expiresAtMs = Long.MAX_VALUE, contentLength = server.bytes.size.toLong())
        })
        suspend fun awaitState(id: String, state: String) = withTimeout(15_000) {
            while (offline.record(id)?.state != state) delay(25)
        }
        fun done() = scope.cancel()
    }

    private fun rig(body: ByteArray = ByteArray(600 * 1024) { (it * 31 % 251).toByte() }): Pair<AudioServer, Rig> {
        val server = AudioServer(body)
        return server to Rig(server, createTempDirectory("offline").toFile())
    }

    @Test fun saveDownloadsTheWholeFileAndReportsIt() = runBlocking {
        val (server, r) = rig(); server.use {
            val t = track("abcdefghijk")
            val summary = r.offline.save(listOf(t))
            assertEquals(1, summary.queued)
            r.awaitState("abcdefghijk", "saved")
            val file = r.offline.localFile("abcdefghijk")
            assertNotNull(file)
            assertEquals(server.bytes.size.toLong(), file.length())
            assertEquals(server.bytes.toList(), file.readBytes().toList())
            assertEquals(OfflineBadge.SAVED, r.offline.badge(t))
            assertEquals(listOf("abcdefghijk"), r.offline.searchSaved("song").map { it.id })
        }
        r.done()
    }

    @Test fun indexAndBytesSurviveARestart() = runBlocking {
        val (server, r) = rig(); val root = createTempDirectory("offline2").toFile()
        try {
            val first = DesktopOffline(root, OkHttpClient(), r.scope, r.settings, resolveForSave = { id ->
                ResolvedAudioStream(server.url, 140, "audio/mp4", Long.MAX_VALUE, server.bytes.size.toLong()) })
            first.save(listOf(track("abcdefghijk")))
            withTimeout(15_000) { while (first.record("abcdefghijk")?.state != "saved") delay(25) }

            val second = DesktopOffline(root, OkHttpClient(), CoroutineScope(SupervisorJob() + Dispatchers.IO), r.settings, resolveForSave = { null })
            val record = second.record("abcdefghijk")
            assertEquals("saved", record?.state)
            assertNotNull(second.localFile("abcdefghijk")) // replay offline with no resolver at all
            Unit
        } finally { r.done() }
    }

    @Test fun dedupAndRejectionsAreCountedLikeAndroid() = runBlocking {
        val (server, r) = rig(); server.use {
            val t = track("abcdefghijk")
            r.offline.save(listOf(t))
            r.awaitState("abcdefghijk", "saved")
            val second = r.offline.save(listOf(t, track("bad", "Bad"), track("local", "L")))
            assertEquals(1, second.alreadySaved)
            assertTrue(second.rejected >= 1) // short ids can never resolve
        }
        r.done()
    }

    @Test fun anUnresolvableTrackFailsWithAnHonestReason() = runBlocking {
        val (server, r) = rig(); server.use {
            r.offline.save(listOf(track("missing0000")))
            r.awaitState("missing0000", "failed")
            assertTrue(r.offline.record("missing0000")!!.failure!!.contains("streaming-only"))
        }
        r.done()
    }

    @Test fun removeDeletesBytesAndTheRecord() = runBlocking {
        val (server, r) = rig(); server.use {
            r.offline.save(listOf(track("abcdefghijk")))
            r.awaitState("abcdefghijk", "saved")
            r.offline.remove("abcdefghijk")
            withTimeout(5_000) { while (r.offline.record("abcdefghijk") != null) delay(25) }
            assertNull(r.offline.localFile("abcdefghijk"))
        }
        r.done()
    }

    @Test fun badgeTransitionsMatchAndroid() = runBlocking {
        val (server, r) = rig(); server.use {
            val t = track("abcdefghijk")
            assertNull(r.offline.badge(t))
            r.offline.save(listOf(t))
            assertTrue(setOf(OfflineBadge.QUEUED, OfflineBadge.DOWNLOADING).contains(r.offline.badge(t)))
            r.awaitState("abcdefghijk", "saved")
            assertEquals(OfflineBadge.SAVED, r.offline.badge(t))
        }
        r.done()
    }

    @Test fun canSaveGatesLikeTheSavedStore() {
        assertTrue(DesktopOffline.canSave(track("abcdefghijk")))
        assertTrue(!DesktopOffline.canSave(track("short")))
        assertTrue(!DesktopOffline.canSave(track("abcdefghijk").copy(isLocal = true)))
        assertTrue(!DesktopOffline.canSave(track("abcdefghijk").copy(sourceProvider = "local")))
    }
}
