package cord.kasane.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.Track
import cord.kasane.music.playback.PlaybackUiState
import cord.kasane.music.ui.components.PlayerActions
import cord.kasane.music.ui.components.PlayerGeometry
import cord.kasane.music.ui.components.PlayerSheet
import cord.kasane.music.ui.components.PlayerSheetState
import cord.kasane.music.ui.components.rememberPlayerSheetState
import cord.kasane.music.ui.theme.KasaneTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the Now Playing crash in f363624: the expand spring overshot 1.0, the derived padding went
 * negative and Compose threw "Padding must be non-negative" on the main thread, killing the whole process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlayerSheetRegressionTest {
    @get:Rule val compose = createComposeRule()

    private val track = Track("dQw4w9WgXcQ", "Midnight City Lights", "Aether", durationSeconds = 240.0)
    private val state = PlaybackUiState(currentTrack = track, isPlaying = true, durationMs = 240_000, currentPositionMs = 30_000,
        canSkipNext = true, canSkipPrevious = true, queue = listOf(track), queueIndex = 0)
    private val actions = PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    @Test fun geometryNeverProducesNegativeSizesForAnyProgressOrWindow() {
        val progressValues = listOf(-1f, -0.2f, 0f, 0.37f, 1f, 1.08f, 1.5f, 7f, Float.NaN)
        val windows = listOf(412.dp to 915.dp, 200.dp to 120.dp, 0.dp to 0.dp, 900.dp to 400.dp)
        for (p in progressValues) for ((w, h) in windows) {
            val g = PlayerGeometry.of(p, w, h, statusTop = 40.dp, navBottom = 24.dp, dockedBottom = 80.dp)
            assertTrue("p=$p w=$w h=$h $g", g.progress in 0f..1f)
            listOf(g.sheetHeight, g.sidePadding, g.bottomPadding, g.corner, g.cover, g.coverSize, g.coverTop).forEach {
                assertTrue("negative value $it for p=$p w=$w h=$h", it.value >= 0f)
            }
        }
    }

    @Test fun expandedCoverIsCenteredAndTitleBarHasNoHorizontalOffset() {
        for (w in listOf(360.dp, 412.dp, 600.dp)) {
            val g = PlayerGeometry.of(1f, w, 892.dp, statusTop = 32.dp, navBottom = 24.dp, dockedBottom = 80.dp)
            assertTrue("cover must be centred at $w: x=${g.coverX} size=${g.coverSize}", kotlin.math.abs((g.coverX * 2 + g.coverSize - w).value) < 0.01f)
            assertTrue(g.sidePadding.value == 0f)
        }
    }

    @Test fun openingClosingDraggingAndReopeningNowPlayingDoesNotCrash() {
        lateinit var sheet: PlayerSheetState
        compose.setContent {
            KasaneTheme(dynamicColor = false) {
                sheet = rememberPlayerSheetState()
                Box(Modifier.fillMaxSize()) { PlayerSheet(state, sheet, actions, isFavorite = false, dockedBottom = 80.dp) }
            }
        }
        repeat(2) {
            compose.onNodeWithText(track.title, useUnmergedTree = true).performClick()   // tap the mini-player
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Minimize player").assertExists()
            assertTrue(sheet.progress.value <= 1f)
            compose.onNodeWithContentDescription("Minimize player").performClick()
            compose.waitForIdle()
            assertTrue(sheet.progress.value >= 0f)
        }
        // Fast flings in both directions are where a spring would overshoot.
        compose.onNodeWithText(track.title, useUnmergedTree = true).performTouchInput { swipeUp(durationMillis = 60) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Minimize player").assertExists()
        compose.onRoot().performTouchInput { swipeDown(startY = top + 200f, endY = bottom - 10f, durationMillis = 60) }
        compose.waitForIdle()
        // A slow drag that travels past halfway settles open; a short slow one settles back closed.
        compose.onNodeWithText(track.title, useUnmergedTree = true).performTouchInput {
            down(center); repeat(20) { advanceEventTime(40); moveBy(androidx.compose.ui.geometry.Offset(0f, -90f)) }; up()
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Minimize player").assertExists()
        compose.onNodeWithContentDescription("Minimize player").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(track.title, useUnmergedTree = true).performTouchInput { swipeUp(durationMillis = 400) }
        compose.waitForIdle()
        assertTrue("sheet must rest at a bound", sheet.progress.value == 0f || sheet.progress.value == 1f)
    }
}
