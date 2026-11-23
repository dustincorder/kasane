package cord.kasane.music.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import cord.kasane.music.settings.AccentSource
import cord.kasane.ui.components.LocalArtworkLoader
import cord.kasane.ui.theme.FallbackSeed
import cord.kasane.ui.theme.KasaneMaterialTheme
import cord.kasane.ui.theme.seedColorScheme
import cord.kasane.music.settings.ThemeMode

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
    val artwork = remember(context) { CoilArtworkLoader(context.applicationContext) }
    CompositionLocalProvider(LocalArtworkLoader provides artwork) { KasaneMaterialTheme(scheme, content) }
}

/** Preview helper: a fixed dark fallback scheme. */
@Composable
fun KasaneTheme(dynamicColor: Boolean, content: @Composable () -> Unit) =
    KasaneTheme(mode = ThemeMode.DARK, accentSource = AccentSource.MANUAL, content = content)
