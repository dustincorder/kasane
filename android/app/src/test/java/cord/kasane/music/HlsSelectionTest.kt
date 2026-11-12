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
        assertSame(ProgressiveStream, HlsPlayerResolver.select(response(), "dQw4w9WgXcQ"))
    }
    @Test fun failedPlayerStatusDoesNotSelectManifest() {
        assertSame(ProgressiveStream, HlsPlayerResolver.select(response("https://manifest.googlevideo.com/a", "LOGIN_REQUIRED"), "dQw4w9WgXcQ"))
    }
    @Test fun refusesDifferentTrack() {
        assertTrue(HlsPlayerResolver.select(response(id = "kIft-LUHHVA"), "dQw4w9WgXcQ") is UnavailableStream)
    }
    @Test fun refusesInsecureAndUnexpectedManifestHosts() {
        for (url in listOf("http://manifest.googlevideo.com/a", "https://googlevideo.com.attacker.test/a", "file:///a", "https://user@manifest.googlevideo.com/a")) {
            assertSame(ProgressiveStream, HlsPlayerResolver.select(response(url), "dQw4w9WgXcQ"))
        }
    }
}
