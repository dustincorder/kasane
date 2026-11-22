package cord.kasane.music

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import cord.kasane.music.auth.AccountState
import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.Track
import cord.kasane.music.playback.PlaybackUiState
import cord.kasane.music.settings.AccentSource
import cord.kasane.music.settings.ThemeMode
import cord.kasane.music.ui.*
import cord.kasane.music.ui.components.QueueContent
import cord.kasane.music.ui.theme.FallbackSeed
import cord.kasane.music.ui.theme.KasaneTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renders the main screens with awkward real-world data (duplicates, empty states) to catch runtime layout crashes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ScreensSmokeTest {
    @get:Rule val compose = createComposeRule()
    private fun t(i: Int) = Track("id$i", "Track $i", "Artist ${i % 3}", durationSeconds = 200.0)
    private val actions = HomeActions({}, { _, _ -> }, {}, {}, {}, {}, {}, {})

    @Test fun homeRendersEverySectionKindAndScrolls() {
        val sections = HomeComposer.build(
            remote = listOf(
                cord.kasane.music.model.CatalogShelf("Quick picks", (1..9).map { CatalogItem("id$it", CatalogKind.SONG, "Track $it", "Artist") }),
                cord.kasane.music.model.CatalogShelf("Mixed for you", listOf(CatalogItem("p1", CatalogKind.PLAYLIST, "Mix"), CatalogItem("p1", CatalogKind.PLAYLIST, "Mix"), CatalogItem("a1", CatalogKind.ALBUM, "Album"))),
                cord.kasane.music.model.CatalogShelf("Artists", listOf(CatalogItem("u1", CatalogKind.ARTIST, "A"), CatalogItem("u2", CatalogKind.ARTIST, "B"))),
                cord.kasane.music.model.CatalogShelf("Videos", listOf(CatalogItem("v1", CatalogKind.VIDEO, "V1"), CatalogItem("v2", CatalogKind.VIDEO, "V2")))),
            topPlayed = (1..8).map { cord.kasane.music.data.PlayStat("s$it", "Top $it", "Artist $it", null, null, 10 - it, it.toLong()) },
            history = (1..6).map { cord.kasane.music.model.HistoryEntity(trackId = "h$it", title = "H$it", artist = "X", playedAt = it.toLong()) },
            saved = listOf(t(50), t(51)), offlineOnly = false)
        compose.setContent { KasaneTheme(dynamicColor = false) { Surface { HomeScreen(sections, false, false, null, false, "id2", actions) } } }
        compose.onNodeWithText("Speed dial").assertExists()
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
    }

    @Test fun homeEmptyLoadingAndErrorStatesRender() {
        var state by androidx.compose.runtime.mutableStateOf(0)
        compose.setContent {
            KasaneTheme(dynamicColor = false) { Surface {
                HomeScreen(emptyList(), loading = state == 0, refreshing = false, error = if (state == 1) "Network unavailable" else null,
                    offlineOnly = state == 2, currentTrackId = null, actions = actions)
            } }
        }
        compose.onNodeWithContentDescription("Loading Home").assertExists()
        state = 1; compose.waitForIdle(); compose.onNodeWithText("Home couldn't load").assertExists()
        state = 2; compose.waitForIdle(); compose.onNodeWithText("Nothing saved yet").assertExists()
        state = 3; compose.waitForIdle(); compose.onNodeWithText("Start listening").assertExists()
    }

    @Test fun queueWithDuplicateEntriesDoesNotCrash() {
        val queue = listOf(t(1), t(2), t(2), t(3))
        compose.setContent { KasaneTheme(dynamicColor = false) { Surface {
            QueueContent(PlaybackUiState(currentTrack = queue[1], queue = queue, queueIndex = 1, upcomingOrder = listOf(2, 3)), {}, { _, _ -> }, {})
        } } }
        compose.onNodeWithText("Up next").assertExists()
    }

    @Test fun settingsRendersInBothThemes() {
        var mode by androidx.compose.runtime.mutableStateOf(ThemeMode.LIGHT)
        compose.setContent { KasaneTheme(mode, AccentSource.AUTO) { Surface(color = MaterialTheme.colorScheme.background) {
            SettingsContent(AccountState.Guest, {}, {}, {}, {}, {}, {}, mode, AccentSource.AUTO, FallbackSeed, { mode = it }, {}, {})
        } } }
        compose.onNodeWithText("Dark").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Settings").assertExists()
    }
}
