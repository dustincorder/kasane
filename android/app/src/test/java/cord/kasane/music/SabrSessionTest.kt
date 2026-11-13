package cord.kasane.music

import cord.kasane.music.provider.sabr.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class SabrSessionTest {
    private val id = "JALbemLw3G4"
    private fun config(profile: String = "IOS"): SabrConfig = requireNotNull(SabrConfig.parse(JSONObject("""{
        "videoDetails":{"videoId":"$id"},"playabilityStatus":{"status":"OK"},
        "playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"AQID"}}},
        "streamingData":{"serverAbrStreamingUrl":"https://rr1.googlevideo.com/videoplayback?secret=fixture",
        "adaptiveFormats":[{"itag":140,"lastModified":"12345","mimeType":"audio/mp4; codecs=\"mp4a.40.2\""}]}}"""), id, profile))
    private fun umpInteger(n: Int): ByteArray = when {
        n < 128 -> byteArrayOf(n.toByte())
        n < 16384 -> byteArrayOf(((n and 63) or 128).toByte(), (n ushr 6).toByte())
        else -> byteArrayOf(0xf0.toByte(), n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte())
    }
    private fun part(type: Int, data: ByteArray) = umpInteger(type) + umpInteger(data.size) + data
    private fun frame(n: Int, first: Boolean = false, protection: Int = 1): ByteArray {
        fun header(key: Int, init: Boolean) = SabrWire.number(1,key.toLong()) + SabrWire.text(2,id) + SabrWire.number(3,140) +
            SabrWire.number(8,if(init) 1 else 0) + SabrWire.number(9,n.toLong()) + SabrWire.number(14,3) +
            SabrWire.bytes(15,SabrWire.number(1,(n-1L)*10000)+SabrWire.number(2,10000)+SabrWire.number(3,1000))
        var result = part(58,SabrWire.number(1,protection.toLong()))
        if (first) {
            result += part(42,SabrWire.text(1,id)+SabrWire.bytes(2,SabrWire.number(1,140))+SabrWire.number(9,80000)+SabrWire.number(10,1000)+SabrWire.number(4,8))
            result += part(20,header(0,true))+part(21,byteArrayOf(0,1,2,3))+part(22,byteArrayOf(0))
        }
        result += part(20,header(1,false))+part(21,byteArrayOf(1,4,5,6))+part(22,byteArrayOf(1))
        return result
    }
    private fun MockWebServer.enqueueBytes(data: ByteArray) = enqueue(MockResponse().setBody(Buffer().write(data)))
    @Test fun actualWireBoundsAndPrefixFraming() {
        val parts = mutableListOf<Int>()
        SabrWire.readParts(byteArrayOf(20,3,1,2,3,22,1,7).inputStream()) { type, _ -> parts.add(type) }
        assertEquals(listOf(20,22),parts)
        val payload = ByteArray(17000) { 42 }
        SabrWire.readParts(part(21,payload).inputStream()) { type,data -> assertEquals(21,type); assertArrayEquals(payload,data) }
        try { SabrWire.readParts(byteArrayOf(20,5,1).inputStream()) { _,_ -> }; fail() } catch (_:IOException) {}
    }
    @Test fun sequentialSegmentsCrossMinuteAndSeekWithoutCookiesOrDisk() {
        MockWebServer().use { server ->
            server.enqueueBytes(frame(1,true))
            for(n in 2..8) server.enqueueBytes(frame(n))
            server.enqueueBytes(frame(1)) // Backward seek beyond the bounded cache.
            SabrSession(config(),OkHttpClient(),testEndpoint=server.url("/sabr").toString()).use { session ->
                val playlist=session.playlist().toString(Charsets.UTF_8)
                assertTrue(playlist.contains("#EXT-X-ENDLIST")); assertTrue(playlist.contains("segment/8"))
                assertFalse(playlist.contains("secret")); assertFalse(session.toString().contains("fixture"))
                assertArrayEquals(byteArrayOf(1,2,3),session.initSegment())
                for(n in 1..8) assertArrayEquals(byteArrayOf(4,5,6),session.mediaSegment(n))
                assertArrayEquals(byteArrayOf(4,5,6),session.mediaSegment(1))
                repeat(9) { index ->
                    val request=server.takeRequest()
                    assertEquals("POST",request.method); assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
                    assertEquals("application/vnd.yt-ump",request.getHeader("Accept"))
                    val decoded=SabrWire.decode(request.body.readByteArray())
                    assertEquals(140,decoded.message(16).number(1).toInt())
                    assertNull(decoded.message(19).data(2)) // No invented PoToken.
                    if (index in 1..7) {
                        assertEquals(1,decoded.bytes[3]?.size)
                        assertEquals(1,decoded.message(3).number(4).toInt())
                        assertEquals(index,decoded.message(3).number(5).toInt())
                    }
                }
                assertEquals(9,server.requestCount)
            }
        }
    }
    @Test fun attestationRejectionIsTerminalWithoutRetry() {
        MockWebServer().use { server ->
            server.enqueueBytes(frame(1,true)); server.enqueueBytes(part(58,SabrWire.number(1,3)))
            SabrSession(config(),OkHttpClient(),testEndpoint=server.url("/sabr").toString()).use { session ->
                session.initialize()
                try { session.mediaSegment(2); fail() } catch(e:SabrException) { assertEquals("ATTESTATION_REQUIRED",e.code) }
                assertEquals(2,server.requestCount)
            }
        }
    }
    @Test fun http403DoesNotRetryOrExposeSignedUrl() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            SabrSession(config(),OkHttpClient(),testEndpoint=server.url("/sabr").toString()).use { session ->
                try { session.initialize(); fail() } catch(e:SabrException) { assertEquals("HTTP_403",e.code); assertFalse(e.message.orEmpty().contains("secret")) }
                assertEquals(1,server.requestCount)
            }
        }
    }
    @Test fun noMediaIsNotEofOrSuccessfulPlayback() {
        MockWebServer().use { server ->
            server.enqueueBytes(part(58,SabrWire.number(1,2)))
            server.enqueueBytes(part(58,SabrWire.number(1,2)))
            SabrSession(config(),OkHttpClient(),testEndpoint=server.url("/sabr").toString()).use { session ->
                try { session.initialize(); fail() } catch(e:SabrException) { assertEquals("ATTESTATION_PENDING",e.code) }
                assertEquals(2,server.requestCount)
            }
        }
    }
    @Test fun unsafeRedirectAndMismatchedVideoAreRejected() {
        for(body in listOf(part(43,SabrWire.text(1,"https://googlevideo.com.attacker.test/videoplayback")),
            part(20,SabrWire.text(2,"different01")+SabrWire.number(3,140)))) {
            MockWebServer().use { server ->
                server.enqueueBytes(body)
                SabrSession(config(),OkHttpClient(),testEndpoint=server.url("/sabr").toString()).use { session ->
                    try { session.initialize(); fail() } catch(_:IOException) {}
                    assertEquals(1,server.requestCount)
                }
            }
        }
    }
    @Test fun closePreventsFurtherRequests() {
        MockWebServer().use { server ->
            val session=SabrSession(config(),OkHttpClient(),testEndpoint=server.url("/sabr").toString())
            session.close()
            try { session.initialize(); fail() } catch(_:IOException) {}
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun webProofCannotBeSentToIosOrAnotherVideoOrAfterExpiry() {
        val token=ByteArray(64){42}
        val cases=listOf(config() to WebPlaybackProof(id,token,System.currentTimeMillis()+60000),
            config("WEB") to WebPlaybackProof("dQw4w9WgXcQ",token,System.currentTimeMillis()+60000),
            config("WEB") to WebPlaybackProof(id,token,System.currentTimeMillis()-1))
        for((config,proof) in cases) {
            try {SabrSession(config,OkHttpClient(),proof=proof);fail("Mismatched proof must be rejected before network")}
            catch(_:IllegalArgumentException){}
        }
    }
    @Test fun matchingWebProofIsEncodedOnlyInsideItsSabrRequest() {
        MockWebServer().use { server ->
            server.enqueueBytes(frame(1,true))
            val bytes=ByteArray(64){42}
            val proof=WebPlaybackProof(id,bytes,System.currentTimeMillis()+60000)
            assertFalse(proof.toString().contains("42"))
            SabrSession(config("WEB"),OkHttpClient(),testEndpoint=server.url("/sabr").toString(),proof=proof).use {session->
                session.initialize()
                val request=server.takeRequest()
                assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"))
                val decoded=SabrWire.decode(request.body.readByteArray())
                assertArrayEquals(bytes,decoded.message(19).data(2))
                assertEquals(1,decoded.message(19).message(1).number(16).toInt())
            }
        }
    }
    @Test fun pendingColdStartMediaIsNotExposedAsAPlayableSource() {
        MockWebServer().use {server->
            server.enqueueBytes(frame(1,true,protection=2))
            SabrSession(config(),OkHttpClient(),testEndpoint=server.url("/sabr").toString()).use {session->
                session.initialize()
                try {cord.kasane.music.provider.SabrStream(session);fail("Cold-start media alone must not select SABR")}
                catch(e:SabrException){assertEquals("ATTESTATION_REQUIRED",e.code)}
                assertEquals(1,server.requestCount)
            }
        }
    }

}
