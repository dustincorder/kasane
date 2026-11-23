package cord.kasane.music.ui

import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import cord.kasane.music.settings.AccentSource
import cord.kasane.music.settings.ThemeMode
import cord.kasane.music.ui.theme.*
import cord.kasane.ui.theme.AccentPresets

/** The Appearance screen section is common code (:ui); Android only says what "Automatic" means here. */
@Composable
fun AppearanceSection(
    mode: ThemeMode, source: AccentSource, accent: Color,
    onMode: (ThemeMode) -> Unit, onAuto: () -> Unit, onAccent: (Color) -> Unit,
) {
    val wallpaperColors = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    cord.kasane.ui.screens.AppearanceSection(mode, source, accent,
        automaticHint = if (wallpaperColors) "Automatic follows your wallpaper (Material You). Pick an accent to override it."
            else "Automatic uses Kasane's built-in palette on this Android version. Pick an accent to override it.",
        onMode = onMode, onAuto = onAuto, onAccent = onAccent)
}

@Preview(showBackground = true, widthDp = 390)
@Composable
fun AppearancePreview() {
    KasaneTheme(ThemeMode.DARK, AccentSource.MANUAL, AccentPresets[1].second) {
        Surface(color = MaterialTheme.colorScheme.background) { AppearanceSection(ThemeMode.DARK, AccentSource.MANUAL, AccentPresets[1].second, {}, {}, {}) }
    }
}
