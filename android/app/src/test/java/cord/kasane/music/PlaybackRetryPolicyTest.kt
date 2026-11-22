package cord.kasane.music

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import cord.kasane.music.playback.shouldPauseOnToggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackRetryPolicyTest {
    @Test fun transientMediaFailuresUseFiniteRetryCount() {
        assertEquals(2, DefaultLoadErrorHandlingPolicy(2).getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test fun playIntentDoesNotTurnRetryOrReplayIntoPause() {
        assertFalse(shouldPauseOnToggle(true, Player.STATE_IDLE, true))
        assertFalse(shouldPauseOnToggle(true, Player.STATE_IDLE, false))
        assertFalse(shouldPauseOnToggle(true, Player.STATE_ENDED, false))
        assertFalse(shouldPauseOnToggle(false, Player.STATE_READY, false))
        assertTrue(shouldPauseOnToggle(true, Player.STATE_READY, false))
        assertTrue(shouldPauseOnToggle(true, Player.STATE_BUFFERING, false))
    }
}
