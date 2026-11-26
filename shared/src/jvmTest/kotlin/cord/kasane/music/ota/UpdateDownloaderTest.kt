package cord.kasane.music.ota

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateDownloaderTest {
    private val client = OkHttpClient()

    private fun asset(url: String, size: Long = -1) =
        ReleaseAsset("kasane-desktop-linux-x86_64-0.2.1.tar.gz", url, size, null)

    private fun sha(body: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }

    @Test fun downloadVerifiesSha256AndRenames() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val payload = ByteArray(300_000) { (it % 251).toByte() }
            server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
            val dest = createTempDirectory("ota").toFile()
            val result = UpdateDownloader(client).download(
                asset(server.url("/release/a.tar.gz").toString(), payload.size.toLong()),
                dest, expectedSha256 = sha(payload))
            assertIs<UpdateDownloader.Result.Done>(result)
            assertEquals("kasane-desktop-linux-x86_64-0.2.1.tar.gz", result.file.name)
            assertTrue(result.file.exists())
            assertEquals(payload.size.toLong(), result.file.length())
            assertEquals(sha(payload), result.sha256)
        }
    }

    @Test fun checksumMismatchDiscardsTheFile() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(10_000) { 1 })))
            val dest = createTempDirectory("ota").toFile()
            val result = UpdateDownloader(client).download(
                asset(server.url("/release/b.tar.gz").toString()),
                dest, expectedSha256 = "00".repeat(32))
            assertIs<UpdateDownloader.Result.Failed>(result)
            assertTrue(result.reason.contains("Checksum"))
            assertFalse(dest.listFiles()?.any { it.name.endsWith(".part") } == true)
        }
    }

    @Test fun httpErrorFailsCleanly() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val dest = createTempDirectory("ota").toFile()
            val result = UpdateDownloader(client).download(
                asset(server.url("/release/missing.tar.gz").toString()), dest, expectedSha256 = null)
            assertIs<UpdateDownloader.Result.Failed>(result)
        }
    }

    @Test fun transportReturnsBodyOnlyOn2xx() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[{\"tag_name\":\"v1.0\"}]"))
            server.enqueue(MockResponse().setResponseCode(403))
            val t = OkHttpUpdateTransport(client)
            assertEquals("[{\"tag_name\":\"v1.0\"}]", t.get(server.url("/ok").toString()))
            assertEquals(null, t.get(server.url("/denied").toString()))
        }
    }
}
