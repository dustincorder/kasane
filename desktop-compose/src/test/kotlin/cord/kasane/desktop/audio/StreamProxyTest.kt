package cord.kasane.desktop.audio

import com.sun.net.httpserver.HttpServer
import cord.kasane.music.provider.GuestPlayerResolver
import cord.kasane.music.provider.ResolvedAudioStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The proxy's HTTP contract against a local stand-in for googlevideo — no YouTube, real sockets.
 * Covers the bounded `&range=` reads YouTube requires, the signed-URL refresh on 403/410 and the
 * cut-off marker the player turns into an end-of-stream message.
 */
class StreamProxyTest {
    private val videoId = "abcdefghij1"

    private fun bytes(size: Int) = ByteArray(size) { (it * 31 % 251).toByte() }

    /** A CDN that serves `?range=START-END` chunks, refusing every offset at or past [refuseFrom]. */
    private class AudioServer(val bytes: ByteArray, val refuseFrom: Long = Long.MAX_VALUE) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
        val url = "http://127.0.0.1:${server.address.port}/audio"
        val requests = AtomicInteger()
        init {
            server.createContext("/audio") { ex ->
                requests.incrementAndGet()
                val spec = ex.requestURI.rawQuery?.split('&')
                    ?.firstOrNull { it.startsWith("range=") }?.removePrefix("range=")
                val (from, to) = spec?.split('-')?.let { it[0].toLong() to it[1].toLong() } ?: (0L to bytes.size - 1L)
                if (from >= refuseFrom) {
                    ex.sendResponseHeaders(403, -1); ex.close(); return@createContext
                }
                val slice = bytes.copyOfRange(from.toInt(), (to + 1).toInt().coerceAtMost(bytes.size))
                ex.responseHeaders.add("Content-Range", "bytes $from-${from + slice.size - 1}/${bytes.size}")
                ex.sendResponseHeaders(200, slice.size.toLong())
                ex.responseBody.write(slice)
                ex.close()
            }
        }
        override fun close() = server.stop(0)
    }

    private inner class Rig : AutoCloseable {
        val http = OkHttpClient()
        val proxy = StreamProxy(http, GuestPlayerResolver(http))
        override fun close() = proxy.close()

        fun get(url: String, range: String? = null) = http.newCall(
            Request.Builder().url(url).apply { range?.let { header("Range", it) } }.build()).execute()

        fun stream(url: String, contentLength: Long) =
            ResolvedAudioStream(url, 140, "audio/mp4", System.currentTimeMillis() + 3_600_000, contentLength)
    }

    @Test fun `get without range returns the whole stream`() = Rig().use { rig ->
        AudioServer(bytes(800)).use { cdn ->
            val url = rig.proxy.register(videoId, rig.stream(cdn.url, 800))
            rig.get(url).use { r ->
                assertEquals(200, r.code)
                assertEquals("800", r.header("Content-Length"))
                assertEquals("bytes", r.header("Accept-Ranges"))
                assertTrue(r.body!!.bytes().contentEquals(bytes(800)))
            }
        }
    }

    @Test fun `range request returns the requested slice`() = Rig().use { rig ->
        val data = bytes(800)
        AudioServer(data).use { cdn ->
            val url = rig.proxy.register(videoId, rig.stream(cdn.url, 800))
            rig.get(url, "bytes=100-199").use { r ->
                assertEquals(206, r.code)
                assertEquals("bytes 100-199/800", r.header("Content-Range"))
                assertTrue(r.body!!.bytes().contentEquals(data.copyOfRange(100, 200)))
            }
        }
    }

    @Test fun `suffix range returns the tail`() = Rig().use { rig ->
        val data = bytes(800)
        AudioServer(data).use { cdn ->
            val url = rig.proxy.register(videoId, rig.stream(cdn.url, 800))
            rig.get(url, "bytes=-20").use { r ->
                assertEquals(206, r.code)
                assertTrue(r.body!!.bytes().contentEquals(data.copyOfRange(780, 800)))
            }
        }
    }

    @Test fun `range beyond the stream is rejected`() = Rig().use { rig ->
        AudioServer(bytes(800)).use { cdn ->
            val url = rig.proxy.register(videoId, rig.stream(cdn.url, 800))
            rig.get(url, "bytes=900-").use { r -> assertEquals(416, r.code) }
        }
    }

    @Test fun `stream length is probed when the resolver did not report it`() = Rig().use { rig ->
        val data = bytes(800)
        AudioServer(data).use { cdn ->
            val url = rig.proxy.register(videoId, rig.stream(cdn.url, -1))
            rig.get(url).use { r ->
                assertEquals(200, r.code)
                assertEquals("800", r.header("Content-Length"))
                assertTrue(r.body!!.bytes().contentEquals(data))
            }
        }
    }

    @Test fun `a refused chunk refreshes the signed url and continues`() = Rig().use { rig ->
        val data = bytes(1_100_000)
        AudioServer(data, refuseFrom = 600_000).use { bad ->
            AudioServer(data).use { good ->
                val refreshes = AtomicInteger()
                val url = rig.proxy.register(videoId, rig.stream(bad.url, data.size.toLong())) {
                    refreshes.incrementAndGet(); rig.stream(good.url, data.size.toLong())
                }
                rig.get(url).use { r ->
                    assertEquals(200, r.code)
                    assertTrue(r.body!!.bytes().contentEquals(data))
                }
                assertEquals(1, refreshes.get())
            }
        }
    }

    @Test fun `repeated refusal marks the stream as cut off, once`() = Rig().use { rig ->
        AudioServer(bytes(800), refuseFrom = 0).use { bad ->
            val url = rig.proxy.register(videoId, rig.stream(bad.url, 800)) { rig.stream(bad.url, 800) }
            rig.get(url).use { r ->
                assertEquals(200, r.code)
                // The headers promise the full length but the CDN never serves a byte.
                runCatching { r.body!!.bytes() }
            }
            assertTrue(rig.proxy.consumeCutOff(videoId))
            assertFalse(rig.proxy.consumeCutOff(videoId))
        }
    }

    @Test fun `streamed bytes are mirrored into the cache sink in order`() = Rig().use { rig ->
        val data = bytes(800)
        AudioServer(data).use { cdn ->
            val chunks = mutableListOf<Pair<Long, ByteArray>>()
            rig.proxy.streamSink = { _, _, _, _, position, bytes, offset, count ->
                chunks += position to bytes.copyOfRange(offset, offset + count)
            }
            val url = rig.proxy.register(videoId, rig.stream(cdn.url, 800))
            rig.get(url).use { r -> r.body!!.bytes() }
            // The proxy thread mirrors bytes after writing them; the client may finish first.
            val deadline = System.currentTimeMillis() + 5_000
            while (chunks.sumOf { it.second.size } < data.size && System.currentTimeMillis() < deadline) Thread.sleep(10)
            var position = 0L
            val received = chunks.fold(ByteArray(0)) { acc, (pos, chunk) ->
                assertEquals(position, pos); position += chunk.size; acc + chunk
            }
            assertTrue(received.contentEquals(data))
        }
    }
}
