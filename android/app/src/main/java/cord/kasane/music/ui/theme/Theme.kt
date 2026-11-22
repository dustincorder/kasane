package cord.kasane.music.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Charcoal surfaces with a single cold-blue accent. Neutrals carry a slight blue cast so they sit
// with the accent; contrast ratios are measured against Gray950/Gray900.
val Gray950 = Color(0xFF0F1012) // App base background
val Gray900 = Color(0xFF151619) // Surfaces (navigation bar, sheets)
val Gray850 = Color(0xFF1B1D21) // Player bar & secondary containers
val Gray800 = Color(0xFF26292E) // Outlines, borders, separators
val Gray700 = Color(0xFF3A3E45) // Dividers, disabled content
val Gray500 = Color(0xFF858B96) // Tertiary metadata & icons (>= 5:1 on Gray950)
val Gray400 = Color(0xFFA6ACB6) // Secondary metadata (>= 7.5:1 on Gray950)
val Gray200 = Color(0xFFD9DCE2) // Primary text & active icons
val White = Color(0xFFF2F4F7)   // Titles & prominent typography

val KasaneBlue = Color(0xFF8AB6F9)          // Primary accent
val KasaneBlueStrong = Color(0xFF5B8FE0)    // Pressed / emphasis
val KasaneBlueContainer = Color(0xFF1C2E47) // Selected & tonal containers
val OnKasaneBlue = Color(0xFF07203F)
val OnKasaneBlueContainer = Color(0xFFD3E3FD)
val KasaneSlate = Color(0xFFA9BFD8)         // Secondary accent
val KasaneIce = Color(0xFF7FD3D8)           // Tertiary accent (cold cyan, used sparingly)

val KasaneDarkColorScheme = darkColorScheme(
    primary = KasaneBlue,
    onPrimary = OnKasaneBlue,
    primaryContainer = KasaneBlueContainer,
    onPrimaryContainer = OnKasaneBlueContainer,
    secondary = KasaneSlate,
    onSecondary = Color(0xFF0E2236),
    secondaryContainer = Color(0xFF232C38),
    onSecondaryContainer = Color(0xFFD5E3F5),
    tertiary = KasaneIce,
    onTertiary = Color(0xFF00363A),
    tertiaryContainer = Color(0xFF16383B),
    onTertiaryContainer = Color(0xFFBFF0F3),
    background = Gray950,
    onBackground = White,
    surface = Gray950,
    onSurface = Gray200,
    surfaceVariant = Gray850,
    onSurfaceVariant = Gray400,
    surfaceTint = KasaneBlue,
    surfaceDim = Gray950,
    surfaceBright = Color(0xFF2B2E34),
    surfaceContainerLowest = Color(0xFF0B0C0E),
    surfaceContainerLow = Gray900,
    surfaceContainer = Color(0xFF181A1E),
    surfaceContainerHigh = Gray850,
    surfaceContainerHighest = Color(0xFF22252A),
    outline = Gray700,
    outlineVariant = Gray800,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Restrained shape scale: small radii for content, larger only for sheets and the player. */
val KasaneShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private fun style(size: Int, line: Int, weight: FontWeight, spacing: Double, color: Color) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp,
    lineHeight = line.sp, letterSpacing = spacing.sp, color = color,
)

val KasaneTypography = Typography(
    headlineMedium = style(26, 32, FontWeight.Bold, -0.4, White),
    headlineSmall = style(22, 28, FontWeight.Bold, -0.3, White),
    titleLarge = style(20, 26, FontWeight.SemiBold, -0.2, White),
    titleMedium = style(15, 20, FontWeight.SemiBold, 0.0, White),
    titleSmall = style(14, 19, FontWeight.Medium, 0.0, White),
    bodyLarge = style(15, 21, FontWeight.Normal, 0.0, Gray200),
    bodyMedium = style(13, 18, FontWeight.Normal, 0.1, Gray400),
    bodySmall = style(12, 16, FontWeight.Normal, 0.1, Gray500),
    labelLarge = style(13, 18, FontWeight.SemiBold, 0.1, Gray200),
    labelMedium = style(12, 16, FontWeight.Medium, 0.2, Gray400),
    labelSmall = style(11, 15, FontWeight.Normal, 0.2, Gray500),
)

/**
 * Material You is optional. Only the accent roles follow the wallpaper; surfaces stay charcoal so
 * contrast and hierarchy do not depend on the user's wallpaper.
 */
@Composable
fun KasaneTheme(dynamicColor: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context).copy(
            background = Gray950, surface = Gray950, onSurface = Gray200, onBackground = White,
            surfaceVariant = Gray850, onSurfaceVariant = Gray400, surfaceContainerLow = Gray900,
            surfaceContainer = KasaneDarkColorScheme.surfaceContainer, surfaceContainerHigh = Gray850,
            surfaceContainerHighest = KasaneDarkColorScheme.surfaceContainerHighest,
            outline = Gray700, outlineVariant = Gray800,
        )
    } else KasaneDarkColorScheme
    MaterialTheme(colorScheme = scheme, typography = KasaneTypography, shapes = KasaneShapes, content = content)
}
