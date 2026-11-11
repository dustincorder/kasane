package cord.kasane.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Neutral, quiet dark theme: Deep slate and onyx, no neon purple or loud gradients
val Gray950 = Color(0xFF0C0D10) // App base background
val Gray900 = Color(0xFF13151A) // Elevated surfaces (navigation bar, app bar, bottom sheets)
val Gray850 = Color(0xFF1B1E26) // Player bar & secondary containers
val Gray800 = Color(0xFF262A35) // Outlines, borders, subtle separators
val Gray700 = Color(0xFF383E4E) // Subtle divider lines
val Gray500 = Color(0xFF7E859C) // Tertiary metadata & icons (WCAG AA compliant: 5.29:1 on bg, 4.97:1 on surface)
val Gray400 = Color(0xFF9FA6B7) // Secondary metadata (artist, duration, hints: 7.96:1 on bg, 7.48:1 on surface)
val Gray200 = Color(0xFFD6DAE3) // Primary text & active icons
val White = Color(0xFFF4F5F8)   // Titles & prominent typography

// Kasane Signature Palette:
// Derived from the official Kasane logo spiral:
// - KasaneRose (#EF5C7D): lighter rose face / primary accent (6.01:1 contrast on onyx bg)
// - KasaneCrimson (#D52F58): rich raspberry crimson ribbon
// - KasaneWine (#790F32): deep wine return surfaces
// - KasaneWineSubtle (#31101B): dark container tint
val KasaneRose = Color(0xFFEF5C7D)
val KasaneCrimson = Color(0xFFD52F58)
val KasaneWine = Color(0xFF790F32)
val KasaneWineSubtle = Color(0xFF2B121B)

// Accent tokens aliasing the signature rose palette
val AccentRose = KasaneRose
val AccentRoseSubtle = KasaneWineSubtle

val KasaneDarkColorScheme = darkColorScheme(
    primary = KasaneRose,
    secondary = KasaneRose,
    onSecondary = White,
    secondaryContainer = KasaneWineSubtle,
    onSecondaryContainer = Color(0xFFFFD9E2),
    tertiary = KasaneRose,
    onPrimary = White,
    primaryContainer = KasaneWineSubtle,
    onPrimaryContainer = Color(0xFFFFD9E2),
    surface = Gray900,
    onSurface = Gray200,
    surfaceVariant = Gray850,
    onSurfaceVariant = Gray400,
    background = Gray950,
    onBackground = White,
    outline = Gray800,
    outlineVariant = Color(0xFF1E212A)
)

val KasaneTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp,
        color = White
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        color = White
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
        color = Gray400
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp,
        color = Gray400
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
        color = Gray500
    )
)
