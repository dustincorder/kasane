package cord.kasane.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import java.io.File

// Same tokens as android/.../ui/theme/Theme.kt. They are duplicated for this
// prototype only; a shared Compose UI module would own them (see docs).

val FallbackSeed = Color(0xFF4F8BE8)

val AccentPresets: List<Pair<String, Color>> = listOf(
    "Blue" to Color(0xFF4F8BE8), "Teal" to Color(0xFF1FA6A0), "Green" to Color(0xFF4CAF6A), "Amber" to Color(0xFFE0A030),
    "Coral" to Color(0xFFE5685A), "Rose" to Color(0xFFD9548A), "Indigo" to Color(0xFF6577E0), "Slate" to Color(0xFF7A8A99),
)

val KasaneShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun style(size: Int, line: Int, weight: FontWeight, spacing: Double) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.sp,
)

val KasaneTypography = Typography(
    headlineLarge = style(32, 40, FontWeight.Bold, -0.5),
    headlineMedium = style(26, 32, FontWeight.Bold, -0.4),
    headlineSmall = style(22, 28, FontWeight.Bold, -0.3),
    titleLarge = style(20, 26, FontWeight.SemiBold, -0.2),
    titleMedium = style(15, 20, FontWeight.SemiBold, 0.0),
    titleSmall = style(14, 19, FontWeight.Medium, 0.0),
    bodyLarge = style(15, 21, FontWeight.Normal, 0.0),
    bodyMedium = style(13, 18, FontWeight.Normal, 0.1),
    bodySmall = style(12, 16, FontWeight.Normal, 0.1),
    labelLarge = style(13, 18, FontWeight.SemiBold, 0.1),
    labelMedium = style(12, 16, FontWeight.Medium, 0.2),
    labelSmall = style(11, 15, FontWeight.Normal, 0.2),
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

fun seedColorScheme(seed: Color, dark: Boolean): ColorScheme =
    dynamicColorScheme(seedColor = seed, isDark = dark, isAmoled = false, style = PaletteStyle.TonalSpot)

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

@Composable
fun KasaneTheme(mode: ThemeMode, seed: Color, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val desktopDark = remember { desktopPrefersDark() }
    val dark = when (mode) { ThemeMode.SYSTEM -> desktopDark ?: systemDark; ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val scheme = remember(dark, seed) { seedColorScheme(seed, dark) }
    MaterialTheme(colorScheme = scheme, typography = KasaneTypography, shapes = KasaneShapes, content = content)
}
