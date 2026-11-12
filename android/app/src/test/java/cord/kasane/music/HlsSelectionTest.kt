package cord.kasane.music

import cord.kasane.music.provider.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HlsSelectionTest {
    private fun response(url: String? = null, status: String = "OK", id: String = "dQw4w9WgXcQ") = JSONObject()
        .put("playabilityStatus", JSONObject().put("status", status))
        .put("videoDetails", JSONObject().put("videoId", id))
        .put("streamingData", JSONObject().apply { if (url != null) put("hlsManifestUrl", url) })
    @Test fun hlsTakesPriorityAndSignedUrlIsRedacted() {
        val stream = HlsPlayerResolver.select(response("https://manifest.googlevideo.com/api/manifest/hls/test?secret=abc"), "dQw4w9WgXcQ")
        assertTrue(stream is HlsStream)
        assertFalse(stream.toString().contains("secret"))
    }
    @Test fun absenceDoesNotPretendHlsIsAvailable() {
        assertTrue(HlsPlayerResolver.select(response(), "dQw4w9WgXcQ") is UnavailableStream)
    }
    @Test fun failedPlayerStatusDoesNotSelectManifest() {
        assertTrue(HlsPlayerResolver.select(response("https://manifest.googlevideo.com/a", "LOGIN_REQUIRED"), "dQw4w9WgXcQ") is UnavailableStream)
    }
    @Test fun refusesDifferentTrack() {
        assertTrue(HlsPlayerResolver.select(response(id = "kIft-LUHHVA"), "dQw4w9WgXcQ") is UnavailableStream)
    }
    @Test fun refusesInsecureAndUnexpectedManifestHosts() {
        for (url in listOf("http://manifest.googlevideo.com/a", "https://googlevideo.com.attacker.test/a", "file:///a", "https://user@manifest.googlevideo.com/a")) {
            assertTrue(HlsPlayerResolver.select(response(url), "dQw4w9WgXcQ") is UnavailableStream)
        }
    }
    @Test fun sabrOnlyIsExplicitlyUnavailableInsteadOfVrFallback() {
        val root = response().put("streamingData", JSONObject().put("serverAbrStreamingUrl", "https://example.invalid/redacted"))
        val selected = HlsPlayerResolver.select(root, "dQw4w9WgXcQ") as UnavailableStream
        assertEquals("SABR_REQUIRED", selected.code)
    }
    @Test fun authenticatedDirectFormatIsConcreteAndNeverUsesVrProfile() {
        val root = response().put("streamingData", JSONObject("""{"expiresInSeconds":300,"adaptiveFormats":[{"itag":140,"mimeType":"audio/mp4","url":"https://rr1.googlevideo.com/videoplayback?expire=9999999999","bitrate":128000}]}"""))
        assertTrue(HlsPlayerResolver.select(root, "dQw4w9WgXcQ") is UnavailableStream)
        val selected = HlsPlayerResolver.select(root, "dQw4w9WgXcQ", allowProgressive = true) as ProgressiveStream
        assertEquals("WEB_REMIX", selected.clientProfile)
        assertEquals("audio/mp4", selected.audio.mimeType)
        assertFalse(selected.toString().contains("expire="))
    }

}
