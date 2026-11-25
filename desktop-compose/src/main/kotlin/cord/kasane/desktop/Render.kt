package cord.kasane.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import cord.kasane.desktop.ui.DesktopArtworkLoader
import cord.kasane.desktop.ui.KasaneTheme
import cord.kasane.desktop.ui.KasaneWindow
import cord.kasane.desktop.ui.Nav
import cord.kasane.desktop.ui.ScaledDensity
import cord.kasane.desktop.ui.Tab
import cord.kasane.music.model.CatalogKind
import cord.kasane.ui.settings.ThemeMode
import cord.kasane.ui.theme.AccentPresets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `--render DIR`: draws the real window composition offscreen (no window, no
 * sound) after starting real playback, and writes PNGs across screens, themes,
 * accents, interactive menus, and window sizes.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun runRender(out: File, scale: Double = 1.0): Int = DesktopApp(
    audioOutput = "null",
    root = kotlin.io.path.createTempDirectory("kasane-render").toFile(),
    autoStartConnect = false,
).use { app ->
    out.mkdirs()
    val track = runBlocking { app.catalog.search("Massive Attack Teardrop", CatalogKind.SONG, null).tracks.first() }
    app.player.playTrack(track)
    runBlocking { withTimeout(30_000) { app.player.state.first { it.isPlaying && it.queue.size > 5 } } }
    val nav = Nav()
    val loader = DesktopArtworkLoader(app.http)
    var mode by mutableStateOf(ThemeMode.DARK)
    var seed by mutableStateOf(Color(0xFF4F8BE8))

    val scene = ImageComposeScene((1320 * scale).toInt(), (860 * scale).toInt(), Density(1f)) {
        KasaneTheme(mode, seed, loader) { ScaledDensity(detected = scale, user = 1f) { KasaneWindow(app, nav) } }
    }
    var t = 0L
    fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            scene.render(t)
            t += 16_000_000
            Thread.sleep(16)
        }
    }
    fun shot(name: String, ms: Long = 2_000) {
        settle(ms)
        val png = scene.render(t).encodeToData(EncodedImageFormat.PNG) ?: return
        File(out, "$name.png").writeBytes(png.bytes)
        println("wrote ${File(out, "$name.png")}")
    }

    shot("0-onboarding", 1_500)
    app.settings.update { it.copy(onboardingDone = true) }
    shot("1-home", 8_000)

    // Profile menu open
    scene.sendPointerEvent(PointerEventType.Press, position = Offset((40 * scale).toFloat(), (30 * scale).toFloat()), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
    scene.sendPointerEvent(PointerEventType.Release, position = Offset((40 * scale).toFloat(), (30 * scale).toFloat()), buttons = PointerButtons(), button = PointerButton.Primary)
    shot("1b-profile-menu", 800)
    // Dismiss profile menu by clicking outside
    scene.sendPointerEvent(PointerEventType.Press, position = Offset((600 * scale).toFloat(), (30 * scale).toFloat()), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
    scene.sendPointerEvent(PointerEventType.Release, position = Offset((600 * scale).toFloat(), (30 * scale).toFloat()), buttons = PointerButtons(), button = PointerButton.Primary)
    settle(300)

    nav.queueOpen = true; shot("2-home-queue", 2_000); nav.queueOpen = false
    nav.tab = Tab.LIBRARY; shot("3-library", 2_000)

    // Right-click track context menu in dark theme
    scene.sendPointerEvent(PointerEventType.Press, position = Offset((320 * scale).toFloat(), (220 * scale).toFloat()), buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
    scene.sendPointerEvent(PointerEventType.Release, position = Offset((320 * scale).toFloat(), (220 * scale).toFloat()), buttons = PointerButtons(), button = PointerButton.Secondary)
    shot("3a-track-context-menu-dark", 1_000)
    scene.sendPointerEvent(PointerEventType.Press, position = Offset((900 * scale).toFloat(), (40 * scale).toFloat()), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
    scene.sendPointerEvent(PointerEventType.Release, position = Offset((900 * scale).toFloat(), (40 * scale).toFloat()), buttons = PointerButtons(), button = PointerButton.Primary)
    settle(300)

    nav.libraryTab = 1; shot("3b-library-downloads", 2_000)
    nav.libraryTab = 2; shot("3d-library-playlists", 2_500); nav.libraryTab = 0

    val samplePlaylist = runBlocking {
        runCatching { app.catalog.search("Lofi Girl", CatalogKind.PLAYLIST, null).shelves.flatMap { it.items }.firstOrNull { it.kind == CatalogKind.PLAYLIST } }.getOrNull()
    }
    samplePlaylist?.let { nav.stack = listOf(it); shot("3c-playlist", 3_000); nav.stack = emptyList() }

    nav.tab = Tab.SETTINGS; shot("4-settings", 2_000)
    nav.openProfile(); shot("8-profile", 2_000); nav.back()
    nav.tab = Tab.SEARCH; nav.searchQuery = ""; shot("9-search", 1_500)
    nav.searchQuery = "Massive Attack"; shot("9b-search-results", 3_000)
    nav.tab = Tab.SETTINGS; nav.connectOpen = true; shot("7-connect", 1_500); nav.back()
    nav.tab = Tab.HOME; nav.queueOpen = true; shot("6-queue-panel", 1_500); nav.queueOpen = false
    nav.tab = Tab.HOME; nav.nowPlaying = true; shot("5-now-playing", 3_000); nav.nowPlaying = false
    nav.lyricsOpen = true; shot("5b-lyrics-panel", 20_000); nav.lyricsOpen = false

    // Light theme + Coral/Teal accents verification
    mode = ThemeMode.LIGHT
    nav.tab = Tab.HOME; shot("12-light-home", 1_500)
    nav.tab = Tab.SETTINGS; shot("13-light-settings", 1_500)
    nav.tab = Tab.SEARCH; shot("14-light-search-results", 1_500)
    scene.sendPointerEvent(PointerEventType.Press, position = Offset((320 * scale).toFloat(), (220 * scale).toFloat()), buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
    scene.sendPointerEvent(PointerEventType.Release, position = Offset((320 * scale).toFloat(), (220 * scale).toFloat()), buttons = PointerButtons(), button = PointerButton.Secondary)
    shot("14b-light-context-menu", 800)
    scene.sendPointerEvent(PointerEventType.Press, position = Offset((900 * scale).toFloat(), (40 * scale).toFloat()), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
    scene.sendPointerEvent(PointerEventType.Release, position = Offset((900 * scale).toFloat(), (40 * scale).toFloat()), buttons = PointerButtons(), button = PointerButton.Primary)
    settle(200)
    mode = ThemeMode.DARK
    seed = AccentPresets.first { it.first == "Coral" }.second
    nav.nowPlaying = true; shot("15-coral-now-playing", 1_500); nav.nowPlaying = false
    seed = AccentPresets.first { it.first == "Teal" }.second
    nav.tab = Tab.SETTINGS; shot("16-teal-settings", 1_500)
    seed = Color(0xFF4F8BE8)
    scene.close()

    // Narrow window (760x600)
    nav.nowPlaying = false
    nav.tab = Tab.SETTINGS
    val narrow = ImageComposeScene(760, 600, Density(1f)) {
        KasaneTheme(ThemeMode.DARK, Color(0xFF4F8BE8), loader) { KasaneWindow(app, nav) }
    }
    fun settleScene(s: ImageComposeScene, ms: Long) {
        var nt = 0L
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { s.render(nt); nt += 16_000_000; Thread.sleep(16) }
        s.render(nt)
    }
    settleScene(narrow, 1_200)
    narrow.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)?.let { File(out, "10-settings-narrow.png").writeBytes(it.bytes) }
    samplePlaylist?.let {
        nav.tab = Tab.LIBRARY; nav.stack = listOf(it)
        settleScene(narrow, 2_000)
        narrow.render(4_000_000_000L).encodeToData(EncodedImageFormat.PNG)?.let { png -> File(out, "10b-playlist-narrow.png").writeBytes(png.bytes) }
        nav.stack = emptyList()
    }
    nav.tab = Tab.HOME
    settleScene(narrow, 3_000)
    narrow.render(8_000_000_000L).encodeToData(EncodedImageFormat.PNG)?.let { File(out, "11-home-narrow.png").writeBytes(it.bytes) }
    narrow.close()

    // Wide desktop window (1680x960)
    nav.tab = Tab.SEARCH
    nav.searchQuery = "Massive Attack"
    val ultrawide = ImageComposeScene(1680, 960, Density(1f)) {
        KasaneTheme(ThemeMode.DARK, Color(0xFF4F8BE8), loader) { KasaneWindow(app, nav) }
    }
    settleScene(ultrawide, 2_000)
    ultrawide.render(3_000_000_000L).encodeToData(EncodedImageFormat.PNG)?.let { File(out, "17-ultrawide-search.png").writeBytes(it.bytes) }
    ultrawide.close()
    0
}
