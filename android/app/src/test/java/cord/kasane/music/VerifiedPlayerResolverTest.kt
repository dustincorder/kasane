package cord.kasane.music

import cord.kasane.music.provider.HlsPlayerResolver
import cord.kasane.music.provider.ProgressiveStream
import cord.kasane.music.provider.UnavailableStream
import cord.kasane.music.provider.verification.VerificationRuntime
import cord.kasane.music.provider.verification.VerifiedPlayerResolver
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedPlayerResolverTest {
    private class FakeRuntime(private val sig: Any = "SIGNED") : VerificationRuntime {
        val calls = mutableListOf<Pair<String, String>>()
        override val userAgent = "TestEngine/1.0"
        override suspend fun call(method: String, args: JSONArray): Any? {
            calls += method to args.toString()
            return when (method) {
                "protocol" -> 1
                "attest" -> "snapshot"
                "integrity", "loadPlayer" -> true
                "decipher" -> JSONObject().put("sig", sig).put("n", "fast")
                "mint" -> "video-proof"
                else -> error(method)
            }
        }
    }

    private val player = """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"JALbemLw3G4"},
        "streamingData":{"adaptiveFormats":[{"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","contentLength":"3362210",
        "signatureCipher":"s=SCRAMBLED&sp=sig&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fexpire%3D1900000000%26itag%3D140%26n%3Dslow"}]}}"""

    private fun server(playerBody: String = player) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(when {
                request.path!!.startsWith("/iframe_api") -> """var u='https:\/\/www.youtube.com\/s\/player\/6b3d0364\/www-widgetapi.vflset\/x.js';"""
                request.path!!.startsWith("/s/player/6b3d0364/") -> "var a={signatureTimestamp:20774};"
                request.path!!.endsWith("/Create") -> """["challenge"]"""
                request.path!!.endsWith("/GenerateIT") -> """["integrity-token",43200]"""
                else -> playerBody
            })
        }
        start()
    }

    @Test fun resolvesDecipheredUrlBoundToTheVideo() = runBlocking {
        val server = server()
        val runtime = FakeRuntime()
        val base = server.url("").toString().trimEnd('/')
        val stream = VerifiedPlayerResolver(OkHttpClient(), runtime, base, base).resolve("JALbemLw3G4")
        assertTrue(stream is cord.kasane.music.provider.verification.VerifiedResult.Verified)
        val audio = (stream as cord.kasane.music.provider.verification.VerifiedResult.Verified).audio
        val url = audio.url.toHttpUrl()
        assertEquals("SIGNED", url.queryParameter("sig"))
        assertEquals("fast", url.queryParameter("n"))
        assertEquals("video-proof", url.queryParameter("pot"))
        assertEquals("audio/mp4", audio.mimeType)
        assertEquals(1_900_000_000_000L, audio.expiresAtMs)
        assertEquals(VerifiedPlayerResolver.PROFILE, stream.clientProfile)
        assertEquals(listOf("protocol", "loadPlayer", "attest", "integrity", "decipher", "mint"), runtime.calls.map { it.first })
        assertEquals("""["slow","sig","SCRAMBLED"]""", runtime.calls[4].second)
        assertEquals("""["JALbemLw3G4"]""", runtime.calls[5].second)
        val requests = (0 until server.requestCount).map { server.takeRequest() }
        val body = JSONObject(requests.first { it.path!!.startsWith("/youtubei/v1/player") }.body.readUtf8())
        assertEquals("WEB_REMIX", body.getJSONObject("context").getJSONObject("client").getString("clientName"))
        assertEquals(20774, body.getJSONObject("playbackContext").getJSONObject("contentPlaybackContext").getInt("signatureTimestamp"))
        val challenges = requests.filter { it.path!!.startsWith("/api/jnn/v1/") }
        assertEquals(2, challenges.size)
        assertTrue(challenges.all { it.getHeader("User-Agent") == "TestEngine/1.0" })
        server.shutdown()
    }

    @Test fun missingSignatureAndUnplayableAreExplicit() = runBlocking {
        val server = server()
        val base = server.url("").toString().trimEnd('/')
        val noSig = VerifiedPlayerResolver(OkHttpClient(), FakeRuntime(JSONObject.NULL), base, base).resolve("JALbemLw3G4")
        assertEquals("VERIFICATION_FAILED", (noSig as cord.kasane.music.provider.verification.VerifiedResult.Unavailable).code)
        assertTrue(noSig.reason.contains("did not produce a signature"))
        server.shutdown()
        val login = server("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in"}}""")
        val loginBase = login.url("").toString().trimEnd('/')
        val unplayable = VerifiedPlayerResolver(OkHttpClient(), FakeRuntime(), loginBase, loginBase).resolve("JALbemLw3G4")
        assertEquals("LOGIN_REQUIRED", (unplayable as cord.kasane.music.provider.verification.VerifiedResult.Unavailable).code)
        login.shutdown()
    }

    @Test fun parsesPlayerVersionAndRejectsForeignHosts() {
        assertEquals("6b3d0364", VerifiedPlayerResolver.playerId("""s\/player\/6b3d0364\/www"""))
        assertEquals(20774L, VerifiedPlayerResolver.signatureTimestamp("x={signatureTimestamp:20774}"))
        val foreign = JSONObject("""{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":140,"url":"https://evil.example/v"}]}}""")
        val error = runCatching { VerifiedPlayerResolver.selectAudioFormat(foreign, "JALbemLw3G4") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("unexpected audio host"))
    }

    @Test fun verifiedStreamWinsAndFailuresReportBothSources() = runBlocking {
        val ios = MockWebServer().apply { enqueue(MockResponse().setResponseCode(500)); enqueue(MockResponse().setResponseCode(500)); start() }
        val notices = cord.kasane.music.provider.PlaybackNotices()
        notices.post("JALbemLw3G4", "stale")
        val verified = HlsPlayerResolver(OkHttpClient(), ios.url("/player").toString(), notices = notices, verified = {
            ProgressiveStream(cord.kasane.music.provider.ResolvedAudioStream("https://rr1.googlevideo.com/v", 140, "audio/mp4", 0), VerifiedPlayerResolver.PROFILE)
        }).resolve("JALbemLw3G4")
        assertTrue(verified is ProgressiveStream)
        assertEquals(0, ios.requestCount)
        assertEquals(null, notices.current.value)
        val failed = HlsPlayerResolver(OkHttpClient(), ios.url("/player").toString(), notices = notices, verified = {
            UnavailableStream("YouTube playback verification failed: x.", "VERIFICATION_FAILED")
        }).resolve("JALbemLw3G4")
        assertEquals("VERIFICATION_FAILED", (failed as UnavailableStream).code)
        assertTrue(failed.reason.contains("verification failed") && failed.reason.contains("iOS:"))
        assertEquals(1, ios.requestCount)
        assertEquals(null, notices.current.value)
        ios.shutdown()
    }

    @Test fun iosFallbackIsAnnouncedToTheUser() = runBlocking {
        val manifest = "https://manifest.googlevideo.com/api/manifest/hls_variant/id/1/index.m3u8"
        val ios = MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"playabilityStatus":{"status":"OK"},"streamingData":{"hlsManifestUrl":"$manifest"}}"""))
            start()
        }
        val notices = cord.kasane.music.provider.PlaybackNotices()
        val stream = HlsPlayerResolver(OkHttpClient(), ios.url("/player").toString(), notices = notices, verified = {
            UnavailableStream("YouTube playback verification failed: player script is not supported.", "VERIFICATION_FAILED")
        }).resolve("JALbemLw3G4")
        assertTrue(stream.toString(), stream is cord.kasane.music.provider.HlsStream)
        val notice = notices.current.value!!
        assertEquals("JALbemLw3G4", notice.videoId)
        assertTrue(notice.message.contains("player script is not supported") && notice.message.contains("iOS stream"))
        ios.shutdown()
    }
}
