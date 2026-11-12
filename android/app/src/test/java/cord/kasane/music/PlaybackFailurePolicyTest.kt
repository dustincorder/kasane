package cord.kasane.music

import cord.kasane.music.provider.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackFailurePolicyTest {
    @Test fun accessRefusalIsNotRetriedAndTransientRecoveryIsBounded() {
        for (status in listOf(401,403,404,410)) assertFalse(PlaybackRetryPolicy.canRetry(status, 1))
        assertTrue(PlaybackRetryPolicy.canRetry(503, 1))
        assertTrue(PlaybackRetryPolicy.canRetry(null, 2))
        assertFalse(PlaybackRetryPolicy.canRetry(503, 3))
        assertFalse(PlaybackRetryPolicy.canRetry(null, 100))
    }
    @Test fun networkErrorIsDifferentFromUnsupportedTransportAndDoesNotLeakExceptionText() {
        val error = playbackFailure(java.io.IOException("https://cdn.invalid/audio?token=secret"))
        assertEquals("NETWORK_ERROR", error.code)
        assertFalse(error.reason.contains("token")); assertFalse(error.reason.contains("https://"))
        assertEquals("PLAYER_HTTP_403", playbackFailure(PlayerHttpException(403)).code)
        val malformed = playbackFailure(org.json.JSONException("signed-url-secret"))
        assertEquals("INVALID_PLAYER_RESPONSE", malformed.code)
        assertFalse(malformed.reason.contains("secret"))
    }
}
