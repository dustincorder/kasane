package cord.kasane.music
import cord.kasane.music.provider.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class StreamResponseTest {
    @Test fun selectsBestSupportedAudioAndNeverVideo() {
        val root = JSONObject("""{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"mimeType":"video/mp4","bitrate":900000,"url":"https://example.test/video"},{"mimeType":"audio/mp4","bitrate":120000,"url":"https://example.test/audio"},{"mimeType":"audio/webm","bitrate":150000,"url":"https://example.test/better"}]}}""")
        assertEquals("https://example.test/better", StreamResponse.audioUrl(root))
    }
    @Test fun loginRestrictionsArePreserved() {
        try {
            StreamResponse.audioUrl(JSONObject("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"private response text"}}"""))
            fail("Blocked track cannot become a playable stream")
        } catch (e: StreamUnavailableException) {
            assertEquals("LOGIN_REQUIRED", e.status)
            assertFalse(e.message.orEmpty().contains("private response"))
        }
    }
    @Test(expected = StreamUnavailableException::class) fun encryptedOnlyFormatsAreNotPretendedPlayable() {
        StreamResponse.audioUrl(JSONObject("""{"streamingData":{"adaptiveFormats":[{"mimeType":"audio/mp4","signatureCipher":"not-a-direct-url"}]}}"""))
    }
}
