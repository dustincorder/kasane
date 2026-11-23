package cord.kasane.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import cord.kasane.ui.components.ArtworkLoader
import cord.kasane.ui.components.LocalArtworkLoader
import cord.kasane.ui.settings.ThemeMode
import cord.kasane.ui.theme.KasaneMaterialTheme
import cord.kasane.ui.theme.seedColorScheme
import java.io.File

/** KDE and GNOME expose dark mode differently from what the JVM can see. */
private fun desktopPrefersDark(): Boolean? {
    val kde = File(System.getProperty("user.home"), ".config/kdeglobals")
    if (kde.isFile) runCatching {
        val scheme = kde.readLines().firstOrNull { it.startsWith("ColorScheme=") }?.substringAfter('=')
        if (scheme != null) return scheme.contains("dark", ignoreCase = true)
    }
    return runCatching {
        val p = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "color-scheme").start()
        p.inputStream.bufferedReader().readText().contains("dark")
    }.getOrNull()
}

/** The shared Kasane theme with the desktop's colour source (the user's accent; the built-in seed for Automatic). */
@Composable
fun KasaneTheme(mode: ThemeMode, seed: Color, artwork: ArtworkLoader, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val desktopDark = remember { desktopPrefersDark() }
    val dark = when (mode) { ThemeMode.SYSTEM -> desktopDark ?: systemDark; ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val scheme = remember(dark, seed) { seedColorScheme(seed, dark) }
    CompositionLocalProvider(LocalArtworkLoader provides artwork) { KasaneMaterialTheme(scheme, content) }
}

/**
 * Interface scale. The window density already includes the display scale when the JVM applied it (then
 * [detected] is ignored, so the scale is never doubled); otherwise [detected] supplies it. [user] is the
 * preference on top. Every dp and sp in shared and desktop code scales together: layout, type and hit targets.
 */
@Composable
fun ScaledDensity(detected: Double, user: Float, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val density = remember(base, detected, user) { Density(base.density * effectiveMultiplier(base.density, detected, user), base.fontScale) }
    CompositionLocalProvider(LocalDensity provides density, content = content)
}

fun effectiveMultiplier(baseDensity: Float, detected: Double, user: Float): Float =
    (if (baseDensity > 1.01f) 1.0 else detected).toFloat() * user
