package cord.kasane.music

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackRetryPolicyTest {
    @Test fun transientMediaFailuresUseFiniteRetryCount() {
        assertEquals(2, DefaultLoadErrorHandlingPolicy(2).getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }
}
