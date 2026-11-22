package cord.kasane.music.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import cord.kasane.music.settings.AccentSource
import cord.kasane.music.settings.ThemeMode

/** Used on Android 11 and older, and whenever the user picks Automatic without wallpaper colours available. */
val FallbackSeed = Color(0xFF4F8BE8)

/** Curated accents shown in Settings. Each is only a seed: Material generates the full tonal palette and contrast. */
val AccentPresets: List<Pair<String, Color>> = listOf(
    "Blue" to Color(0xFF4F8BE8), "Teal" to Color(0xFF1FA6A0), "Green" to Color(0xFF4CAF6A), "Amber" to Color(0xFFE0A030),
    "Coral" to Color(0xFFE5685A), "Rose" to Color(0xFFD9548A), "Indigo" to Color(0xFF6577E0), "Slate" to Color(0xFF7A8A99),
)

/** Restrained shape scale: small radii for content, larger only for sheets and the player. */
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

/** Colours come from the theme, never from the type scale, so every role works in light, dark and dynamic schemes. */
val KasaneTypography = Typography(
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

/** Secondary text: the same sizes, coloured with onSurfaceVariant. */
val Typography.bodyMediumMuted: TextStyle @Composable get() = bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
val Typography.bodySmallMuted: TextStyle @Composable get() = bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
val Typography.labelMediumMuted: TextStyle @Composable get() = labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
val Typography.labelSmallMuted: TextStyle @Composable get() = labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)

fun seedColorScheme(seed: Color, dark: Boolean): ColorScheme =
    dynamicColorScheme(seedColor = seed, isDark = dark, isAmoled = false, style = PaletteStyle.TonalSpot)

/**
 * Material You by default: wallpaper colours on Android 12+, following the system light/dark setting.
 * Android 11 and older, or a manual accent, use a palette generated from a seed colour with the same
 * HCT algorithm, so contrast and tonal surfaces stay correct.
 */
@Composable
fun KasaneTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    accentSource: AccentSource = AccentSource.AUTO,
    accentColor: Color = FallbackSeed,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val context = LocalContext.current
    val scheme = remember(dark, accentSource, accentColor) {
        if (accentSource == AccentSource.AUTO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else seedColorScheme(if (accentSource == AccentSource.MANUAL) accentColor else FallbackSeed, dark)
    }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
    }
    MaterialTheme(colorScheme = scheme, typography = KasaneTypography, shapes = KasaneShapes, content = content)
}

/** Preview helper: a fixed dark fallback scheme. */
@Composable
fun KasaneTheme(dynamicColor: Boolean, content: @Composable () -> Unit) =
    KasaneTheme(mode = ThemeMode.DARK, accentSource = AccentSource.MANUAL, content = content)
