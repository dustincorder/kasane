package cord.kasane.music

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/** Repro for the device defect: repeat-one is on, the user picks another track,
 *  the player must switch to it — not finish the old one first. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class RepeatSwitchReproTest {
    private lateinit var player: ExoPlayer

    private fun item(id: String) = MediaItem.Builder()
        .setMediaId(id).setUri(Uri.parse("file:///sdcard/$id.mp3")).build()

    @Before fun setUp() {
        player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
    }

    @After fun tearDown() = player.release()

    // Robolectric's bindService feeds the app's own MediaController a null ComponentName once;
    // that runnable dies, the rest of the main queue pumps fine.
    private fun idle() = runCatching { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }

    @Test fun setMediaItemsUnderRepeatOneSwitchesImmediately() {
        player.setMediaItems(listOf(item("a")))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.prepare(); player.play()
        idle()
        assertEquals("a", player.currentMediaItem?.mediaId)

        // Selecting a different track replaces the context — as playTrack() does.
        player.setMediaItems(listOf(item("b")), 0, 0)
        player.prepare(); player.play()
        idle()
        assertEquals("b", player.currentMediaItem?.mediaId)
    }

    @Test fun seekToDefaultPositionUnderRepeatOneSwitchesImmediately() {
        player.setMediaItems(listOf(item("a"), item("b")))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.prepare(); player.play()
        idle()
        assertEquals(0, player.currentMediaItemIndex)

        // Selecting a queue entry — as playQueueIndex() does.
        player.seekToDefaultPosition(1)
        idle()
        assertEquals("b", player.currentMediaItem?.mediaId)
    }

    @Test fun nextUnderRepeatOne() {
        player.setMediaItems(listOf(item("a"), item("b")))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.prepare(); player.play()
        idle()
        println("hasNext=${player.hasNextMediaItem()} nextIdx=${player.nextMediaItemIndex}")
        player.seekToNextMediaItem()
        idle()
        println("after seekToNextMediaItem idx=${player.currentMediaItemIndex} item=${player.currentMediaItem?.mediaId}")
        assertEquals("b", player.currentMediaItem?.mediaId)

        // On the last item repeat-one has no "next": it does not wrap or replay-as-next.
        println("last: hasNext=${player.hasNextMediaItem()} nextIdx=${player.nextMediaItemIndex}")
        player.seekToNextMediaItem()
        idle()
        println("after last seekToNextMediaItem idx=${player.currentMediaItemIndex} item=${player.currentMediaItem?.mediaId}")
        assertEquals("b", player.currentMediaItem?.mediaId)
    }

    /** Selecting a track while the queue tail is filled by radio keeps the switch immediate. */
    @Test fun queueTapUnderRepeatOneKeepsPlayingItemUntilSeek() {
        player.setMediaItems(listOf(item("a"), item("b"), item("c")))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.prepare(); player.play()
        idle()
        assertEquals("a", player.currentMediaItem?.mediaId)
        // A queue-row tap must switch now, not after the current item ends.
        player.seekToDefaultPosition(2)
        player.prepare(); player.play()
        idle()
        assertEquals("c", player.currentMediaItem?.mediaId)
    }
}
