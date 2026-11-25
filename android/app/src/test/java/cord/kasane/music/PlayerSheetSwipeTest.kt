package cord.kasane.music

import cord.kasane.music.ui.components.swipeSkipDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/** The mini-player must switch only on a confirmed gesture and must always allow a cancelled swipe. */
class PlayerSheetSwipeTest {
    private val min = 300f   // distance threshold in px
    private val fling = 800f // velocity threshold in px/s

    @Test fun aShortDragIsNeverASwitch() {
        assertEquals(0, swipeSkipDirection(60f, 0f, min, fling))
        assertEquals(0, swipeSkipDirection(-60f, 0f, min, fling))
    }

    @Test fun pastTheDistanceThresholdConfirms() {
        assertEquals(-1, swipeSkipDirection(-301f, 0f, min, fling))
        assertEquals(1, swipeSkipDirection(301f, 0f, min, fling))
    }

    @Test fun aFlingConfirmsOnlyInTheDirectionDragged() {
        // Dragged partly left then released fast: next track.
        assertEquals(-1, swipeSkipDirection(-150f, -1200f, min, fling))
        // Dragged left but flung back right: the user cancelled the gesture — no switch.
        assertEquals(0, swipeSkipDirection(-150f, 1200f, min, fling))
        assertEquals(0, swipeSkipDirection(150f, -1200f, min, fling))
    }

    @Test fun aFlingFromRestIsNotEnough() {
        // Barely any travel: an accidental twitch, even at high speed, is not a swipe.
        assertEquals(0, swipeSkipDirection(-10f, -5000f, min, fling))
        assertEquals(0, swipeSkipDirection(10f, 5000f, min, fling))
    }
}
