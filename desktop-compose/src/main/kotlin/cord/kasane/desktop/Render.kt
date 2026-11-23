package cord.kasane.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import cord.kasane.desktop.ui.DesktopArtworkLoader
import cord.kasane.desktop.ui.ScaledDensity
import cord.kasane.desktop.ui.KasaneTheme
import cord.kasane.desktop.ui.KasaneWindow
import cord.kasane.desktop.ui.Nav
import cord.kasane.desktop.ui.Tab
import cord.kasane.ui.settings.ThemeMode
import cord.kasane.music.model.CatalogKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `--render DIR`: draws the real window composition offscreen (no window, no
 * sound) after starting real playback, and writes one PNG per screen. For
 * reviewing layout without opening the app.
 */
fun runRender(out: File, scale: Double = 1.0): Int = DesktopApp(audioOutput = "null", root = kotlin.io.path.createTempDirectory("kasane-render").toFile(), autoStartConnect = false).use { app ->
    out.mkdirs()
    val track = runBlocking { app.catalog.search("Massive Attack Teardrop", CatalogKind.SONG, null).tracks.first() }
    app.player.playTrack(track)
    runBlocking { withTimeout(30_000) { app.player.state.first { it.isPlaying && it.queue.size > 5 } } }
    app.library.toggleFavorite(track)
    val nav = Nav()
    val loader = DesktopArtworkLoader(app.http)
    val scene = ImageComposeScene((1320 * scale).toInt(), (860 * scale).toInt(), Density(1f)) {
        KasaneTheme(ThemeMode.DARK, Color(0xFF4F8BE8), loader) { ScaledDensity(detected = scale, user = 1f) { KasaneWindow(app, nav) } }
    }
    var t = 0L
    fun settle(ms: Long) { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { scene.render(t); t += 16_000_000; Thread.sleep(16) } }
    fun shot(name: String, ms: Long = 5_000) {
        settle(ms)
        val png = scene.render(t).encodeToData(EncodedImageFormat.PNG) ?: return
        File(out, "$name.png").writeBytes(png.bytes)
        println("wrote ${File(out, "$name.png")}")
    }
    shot("1-home", 8_000)
    nav.queueOpen = true; shot("2-home-queue", 2_000); nav.queueOpen = false
    nav.tab = Tab.LIBRARY; shot("3-library", 2_000)
    nav.tab = Tab.SETTINGS; shot("4-settings", 2_000)
    nav.tab = Tab.HOME; nav.queueOpen = true; shot("6-queue-panel", 2_000); nav.queueOpen = false
    nav.tab = Tab.HOME
    nav.nowPlaying = true; shot("5-now-playing", 4_000)
    scene.close()
    0
}
