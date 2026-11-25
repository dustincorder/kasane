package cord.kasane.music

import cord.kasane.music.provider.HlsPlayerResolver
import cord.kasane.music.provider.HlsStream
import cord.kasane.music.provider.PlaybackStream
import cord.kasane.music.provider.ProgressiveStream
import cord.kasane.music.provider.ResolvedAudioStream
import cord.kasane.music.provider.UnavailableStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * The offline library lives on plain audio files. Playback prefers a probed direct URL so streamed
 * songs land in the temporary cache; saving resolves only progressive sources because HLS and SABR
 * cannot be stored byte for byte.
 */
class SaveResolutionTest {
    private val id = "dQw4w9WgXcQ"
    private val directUrl = "https://rr1.googlevideo.com/videoplayback?expire=9999999999"
    private val hlsUrl = "https://manifest.googlevideo.com/api/manifest/hls/x"

    private fun playerJson(hls: String? = null, adaptiveUrl: String? = directUrl, sabr: Boolean = false): JSONObject =
        JSONObject()
            .put("playabilityStatus", JSONObject().put("status", "OK"))
            .put("videoDetails", JSONObject().put("videoId", id))
            .put("streamingData", JSONObject().apply {
                if (hls != null) put("hlsManifestUrl", hls)
                if (sabr) put("serverAbrStreamingUrl", "https://example.invalid/sabr")
                if (adaptiveUrl != null) put("adaptiveFormats", org.json.JSONArray().put(JSONObject()
                    .put("itag", 140).put("mimeType", "audio/mp4").put("url", adaptiveUrl)
                    .put("bitrate", 128000).put("contentLength", "1000")))
            })

    private fun resolver(
        authenticated: JSONObject? = null,
        probe: Boolean = true,
        verified: PlaybackStream? = null,
        authCalls: MutableList<String>? = null,
    ) = HlsPlayerResolver(OkHttpClient(), endpoint = "https://www.youtube.com/youtubei/v1/player",
        progressiveCheck = { _, _ -> probe },
        verified = verified?.let { v -> { v } },
        notices = cord.kasane.music.provider.PlaybackNotices(),
        authenticatedPlayer = { videoId -> authCalls?.add(videoId); authenticated })

    // ---- Streaming resolution ---------------------------------------------------------------------

    @Test fun playbackPrefersAProbedDirectFileOverHlsSoItCanBeCached() = runBlocking {
        val stream = resolver(playerJson(hls = hlsUrl), probe = true).resolve(id)
        assertTrue(stream is ProgressiveStream)
        assertEquals("WEB_REMIX", (stream as ProgressiveStream).clientProfile)
    }

    @Test fun aDirectFileRejectedByTheCdnFallsBackToHls() = runBlocking {
        val stream = resolver(playerJson(hls = hlsUrl), probe = false).resolve(id)
        assertTrue(stream is HlsStream)
        assertEquals("WEB_REMIX", (stream as HlsStream).clientProfile)
    }

    @Test fun aDirectFileRejectedByTheCdnStillReachesTheVerifiedResolver() = runBlocking {
        val verifiedStream = ProgressiveStream(ResolvedAudioStream(directUrl, 140, "audio/mp4", Long.MAX_VALUE, 1000), "WEB_REMIX_VERIFIED")
        val stream = resolver(playerJson(sabr = true), probe = false, verified = verifiedStream).resolve(id)
        assertTrue(stream is ProgressiveStream)
        assertEquals("WEB_REMIX_VERIFIED", (stream as ProgressiveStream).clientProfile)
    }

    // ---- Save resolution ----------------------------------------------------------------------------

    @Test fun savingUsesTheSessionDirectFileWhenItIsAccessible() = runBlocking {
        val stream = resolver(playerJson(hls = hlsUrl), probe = true).resolveForSave(id)
        assertTrue(stream is ProgressiveStream)
        assertEquals("WEB_REMIX", (stream as ProgressiveStream).clientProfile)
    }

    @Test fun savingNeverSettlesForAnHlsManifest() = runBlocking {
        // A session response that only offers HLS cannot produce a storable file and must not pretend it can.
        val stream = resolver(playerJson(hls = hlsUrl, adaptiveUrl = null)).resolveForSave(id)
        assertTrue(stream is UnavailableStream)
    }

    @Test fun savingFallsBackToTheVerifiedResolver() = runBlocking {
        val verifiedStream = ProgressiveStream(ResolvedAudioStream(directUrl, 140, "audio/mp4", Long.MAX_VALUE, 1000), "WEB_REMIX_VERIFIED")
        val stream = resolver(playerJson(sabr = true, adaptiveUrl = null), verified = verifiedStream).resolveForSave(id)
        assertTrue(stream is ProgressiveStream)
        assertEquals("WEB_REMIX_VERIFIED", (stream as ProgressiveStream).clientProfile)
    }

    @Test fun savingAlsoUsesVerifiedWhenTheSessionFileIsRejected() = runBlocking {
        val verifiedStream = ProgressiveStream(ResolvedAudioStream(directUrl, 140, "audio/mp4", Long.MAX_VALUE, 1000), "WEB_REMIX_VERIFIED")
        val stream = resolver(playerJson(hls = hlsUrl), probe = false, verified = verifiedStream).resolveForSave(id)
        assertEquals("WEB_REMIX_VERIFIED", (stream as ProgressiveStream).clientProfile)
    }

    @Test fun savingReportsTheVerifiedFailureInsteadOfStreamingAnyway() = runBlocking {
        val failure = UnavailableStream("YouTube playback verification failed.", "VERIFICATION_FAILED")
        val stream = resolver(playerJson(hls = hlsUrl, adaptiveUrl = null), verified = failure).resolveForSave(id)
        assertEquals("VERIFICATION_FAILED", (stream as UnavailableStream).code)
    }

    @Test fun anInvalidTrackIdNeverTouchesTheNetwork() = runBlocking {
        val calls = mutableListOf<String>()
        val stream = resolver(playerJson(), authCalls = calls).resolveForSave("short")
        assertTrue(stream is UnavailableStream)
        assertTrue(calls.isEmpty())
    }
}
