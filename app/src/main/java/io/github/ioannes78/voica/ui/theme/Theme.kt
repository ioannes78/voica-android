package io.github.ioannes78.voica.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val MintDark =
    darkColorScheme(
        primary = Color(0xFF58D6B5),
        onPrimary = Color(0xFF00382D),
        primaryContainer = Color(0xFF0C4F41),
        onPrimaryContainer = Color(0xFFB8F4E2),
        secondary = Color(0xFFB2CCC3),
        onSecondary = Color(0xFF1D352E),
        secondaryContainer = Color(0xFF334B43),
        onSecondaryContainer = Color(0xFFCDE8DE),
        background = Color(0xFF10171B),
        onBackground = Color(0xFFEEF3F2),
        surface = Color(0xFF171F24),
        onSurface = Color(0xFFEEF3F2),
        surfaceVariant = Color(0xFF253139),
        onSurfaceVariant = Color(0xFFAAB6B8),
        outline = Color(0xFF35434A),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    )

private val MintLight =
    lightColorScheme(
        primary = Color(0xFF087D67),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFC9F4E6),
        onPrimaryContainer = Color(0xFF002019),
        secondary = Color(0xFF4B635B),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFCDE8DE),
        onSecondaryContainer = Color(0xFF072019),
        background = Color(0xFFF6F8F8),
        onBackground = Color(0xFF17201E),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF17201E),
        surfaceVariant = Color(0xFFEEF2F1),
        onSurfaceVariant = Color(0xFF5E6B68),
        outline = Color(0xFFD8E0DE),
    )

private val BlueDark =
    darkColorScheme(
        primary = Color(0xFF9FC9FF),
        onPrimary = Color(0xFF00325A),
        primaryContainer = Color(0xFF00497F),
        onPrimaryContainer = Color(0xFFD1E4FF),
        background = Color(0xFF101820),
        surface = Color(0xFF101820),
        surfaceVariant = Color(0xFF202A33),
    )

private val BlueLight =
    lightColorScheme(
        primary = Color(0xFF00639A),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFCDE5FF),
        onPrimaryContainer = Color(0xFF001D32),
        background = Color(0xFFF8F9FF),
        surface = Color(0xFFF8F9FF),
    )

private val PurpleDark =
    darkColorScheme(
        primary = Color(0xFFD0BCFF),
        onPrimary = Color(0xFF381E72),
        primaryContainer = Color(0xFF4F378B),
        onPrimaryContainer = Color(0xFFEADDFF),
        background = Color(0xFF17151D),
        surface = Color(0xFF17151D),
        surfaceVariant = Color(0xFF2A2730),
    )

private val PurpleLight =
    lightColorScheme(
        primary = Color(0xFF6750A4),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFEADDFF),
        onPrimaryContainer = Color(0xFF21005D),
        background = Color(0xFFFFF7FF),
        surface = Color(0xFFFFF7FF),
    )

private val VoicaTypography =
    Typography(
        headlineLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 24.sp,
                lineHeight = 30.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 24.sp,
                lineHeight = 30.sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 20.sp,
                lineHeight = 26.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                lineHeight = 23.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 21.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 21.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
    )

@Composable
fun VoicaTheme(
    settings: VoicaThemeSettings = VoicaThemeSettings(),
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (settings.mode) {
            VoicaThemeMode.SYSTEM -> isSystemInDarkTheme()
            VoicaThemeMode.LIGHT -> false
            VoicaThemeMode.DARK -> true
        }

    val scheme =
        when (settings.preset) {
            VoicaColorPreset.MINT -> if (darkTheme) MintDark else MintLight
            VoicaColorPreset.BLUE -> if (darkTheme) BlueDark else BlueLight
            VoicaColorPreset.PURPLE -> if (darkTheme) PurpleDark else PurpleLight
            VoicaColorPreset.CUSTOM ->
                customColorScheme(
                    darkTheme = darkTheme,
                    accent = Color(settings.customAccentArgb),
                )
        }

    MaterialTheme(
        colorScheme = scheme,
        typography = VoicaTypography,
        content = content,
    )
}

private fun customColorScheme(
    darkTheme: Boolean,
    accent: Color,
): ColorScheme {
    val base = if (darkTheme) MintDark else MintLight
    val onPrimary =
        if (accent.luminance() > 0.48f) {
            Color(0xFF101413)
        } else {
            Color.White
        }
    val container =
        mix(
            accent,
            if (darkTheme) Color.Black else Color.White,
            if (darkTheme) 0.56f else 0.72f,
        )
    val onContainer =
        if (container.luminance() > 0.48f) {
            Color(0xFF101413)
        } else {
            Color.White
        }

    return base.copy(
        primary = accent,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        secondary = mix(accent, base.secondary, 0.45f),
        secondaryContainer = mix(accent, base.secondaryContainer, 0.35f),
    )
}

private fun mix(
    first: Color,
    second: Color,
    secondRatio: Float,
): Color {
    val ratio = secondRatio.coerceIn(0f, 1f)
    val firstRatio = 1f - ratio
    return Color(
        red = first.red * firstRatio + second.red * ratio,
        green = first.green * firstRatio + second.green * ratio,
        blue = first.blue * firstRatio + second.blue * ratio,
        alpha = 1f,
    )
}
