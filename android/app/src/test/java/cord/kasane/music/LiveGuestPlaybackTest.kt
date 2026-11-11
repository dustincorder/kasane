package cord.kasane.music

import cord.kasane.music.provider.GuestPlayerResolver
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

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
}
