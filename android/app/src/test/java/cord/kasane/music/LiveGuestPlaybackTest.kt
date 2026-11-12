package cord.kasane.music

import cord.kasane.music.provider.GuestPlayerResolver
import cord.kasane.music.provider.AudioHttpException
import cord.kasane.music.playback.AudioRange
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest

class LiveGuestPlaybackTest {
    @Test fun selectedPublicMusicHasGuestAudioBytes() = runTest(timeout = kotlin.time.Duration.parse("120s")) {
        assumeTrue("Explicit live guest playback probe", System.getenv("KASANE_GUEST_PLAYBACK_LIVE") == "1")
        val client = OkHttpClient()
        val resolver = GuestPlayerResolver(client, diagnostic = { println("[GuestPlayback] $it") })
        for (id in listOf("kIft-LUHHVA", "ZVgHPSyEIqk", "kOn-HdEg6AQ", "dQw4w9WgXcQ")) {
            val stream = resolver.resolve(id)
            val request = Request.Builder().url(stream.url).header("Range", "bytes=0-1023")
            stream.headers.forEach { (key, value) -> request.header(key, value) }
            client.newCall(request.build()).execute().use { response ->
                println("[GuestPlayback] videoId=$id cdnHttp=${response.code} itag=${stream.itag}")
                assertTrue("Audio refused for $id: ${response.code}", response.isSuccessful)
                assertEquals(1024, response.body!!.source().readByteArray(1024).size)
            }
        }
    }

    @Test fun consecutiveBoundedQueryRangesCrossMinuteBoundaryOnSeveralPublicTracks() = runTest(timeout = kotlin.time.Duration.parse("8m")) {
        assumeTrue("Explicit multi-chunk live probe", System.getenv("KASANE_GUEST_PLAYBACK_LIVE") == "1")
        val client = OkHttpClient()
        val resolver = GuestPlayerResolver(client)
        val ids = listOf("kIft-LUHHVA", "ZVgHPSyEIqk", "kOn-HdEg6AQ")
        for (id in ids) {
            val stream = resolver.resolve(id)
            val contentLength = stream.contentLength.takeIf { it > 0 } ?: 3L * 1024 * 1024
            val stopAt = minOf(contentLength, 3L * 1024 * 1024)
            var offset = 0L
            val hashes = mutableListOf<String>()
            while (offset < stopAt) {
                val expected = minOf(AudioRange.CHUNK_BYTES, stopAt - offset)
                val data = resolver.open(id, operation = { resolved ->
                    val request = Request.Builder().url(AudioRange.url(resolved.url, offset, expected))
                    resolved.headers.forEach { (key, value) -> request.header(key, value) }
                    client.newCall(request.build()).execute().use { response ->
                        val rangeHeader = response.header("Content-Range") ?: "none"
                        val declaredLength = response.body?.contentLength() ?: -1
                        println("[GuestRangeProbe] videoId=$id offset=$offset requested=$expected status=${response.code} responseLength=$declaredLength contentRange=$rangeHeader itag=${resolved.itag}")
                        if (!response.isSuccessful) throw AudioHttpException(response.code)
                        val body = response.body ?: throw java.io.IOException("No range body")
                        val source = body.source()
                        source.request(expected + 1)
                        val observed = minOf(source.buffer.size, expected + 1)
                        source.readByteArray(observed)
                    }
                })
                assertEquals("Unexpected range size for $id at $offset", expected, data.size.toLong())
                hashes += MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
                offset += data.size
            }
            assertTrue("Adjacent chunk payloads unexpectedly repeated for $id", hashes.distinct().size > 1)
            println("[GuestRangeProbe] videoId=$id verifiedBytes=$offset chunks=${hashes.size}")
        }
    }

    @Test fun fullPublicTrackCanBeReadThroughBoundedQueryRanges() = runTest(timeout = kotlin.time.Duration.parse("8m")) {
        assumeTrue("Explicit full-track live probe", System.getenv("KASANE_GUEST_PLAYBACK_FULL_LIVE") == "1")
        val id = "dQw4w9WgXcQ"
        val client = OkHttpClient()
        val resolver = GuestPlayerResolver(client)
        val initial = resolver.resolve(id)
        assertTrue("Resolver did not supply resource length needed for a full range check", initial.contentLength > 0)
        var offset = 0L
        while (offset < initial.contentLength) {
            val expected = minOf(AudioRange.CHUNK_BYTES, initial.contentLength - offset)
            val bytes = resolver.open(id, operation = { resolved ->
                val request = Request.Builder().url(AudioRange.url(resolved.url, offset, expected))
                resolved.headers.forEach { (key, value) -> request.header(key, value) }
                client.newCall(request.build()).execute().use { response ->
                    println("[GuestRangeProbe] fullTrack offset=$offset requested=$expected status=${response.code} responseLength=${response.body?.contentLength() ?: -1} contentRange=${response.header("Content-Range") ?: "none"}")
                    if (!response.isSuccessful) throw AudioHttpException(response.code)
                    val source = response.body?.source() ?: throw java.io.IOException("No range body")
                    source.request(expected + 1)
                    source.readByteArray(minOf(source.buffer.size, expected + 1))
                }
            })
            assertEquals("Short or oversized response at byte $offset", expected, bytes.size.toLong())
            offset += bytes.size
        }
        assertEquals(initial.contentLength, offset)
    }
}
