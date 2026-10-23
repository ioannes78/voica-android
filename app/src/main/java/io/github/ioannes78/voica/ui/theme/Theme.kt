package io.github.ioannes78.voica.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val WenzhiPaper = Color(0xFFF5F2EA)
private val WenzhiSurface = Color(0xFFFBFAF6)
private val WenzhiElevated = Color(0xFFFFFFFF)
private val WenzhiInk = Color(0xFF171717)
private val WenzhiSecondary = Color(0xFF656565)
private val WenzhiTertiary = Color(0xFF9A968D)
private val WenzhiDivider = Color(0xFFDDD9D0)
private val WenzhiDisabled = Color(0xFFC5C0B7)
private val WenzhiAccent = Color(0xFF315E58)
private val WenzhiAccentContainer = Color(0xFFE5EEEB)
private val WenzhiRecording = Color(0xFFB94D48)
private val WenzhiRecordingContainer = Color(0xFFF5E8E5)
private val WenzhiWarning = Color(0xFFA87536)
private val WenzhiError = Color(0xFFA94442)
private val WenzhiInfo = Color(0xFF536A78)

private val WenzhiDarkBackground = Color(0xFF111210)
private val WenzhiDarkSurface = Color(0xFF191A18)
private val WenzhiDarkElevated = Color(0xFF212220)
private val WenzhiDarkPrimaryText = Color(0xFFF2EFE7)
private val WenzhiDarkSecondaryText = Color(0xFFB4B0A7)
private val WenzhiDarkTertiaryText = Color(0xFF817E77)
private val WenzhiDarkDivider = Color(0xFF343532)
private val WenzhiDarkAccent = Color(0xFF79A69D)
private val WenzhiDarkAccentContainer = Color(0xFF233632)
private val WenzhiDarkRecording = Color(0xFFD36A64)
private val WenzhiDarkRecordingContainer = Color(0xFF3A2321)
private val WenzhiDarkWarning = Color(0xFFC6965A)
private val WenzhiDarkError = Color(0xFFD1706C)
private val WenzhiDarkInfo = Color(0xFF7F98A6)

private val WenzhiInkLight =
    lightColorScheme(
        primary = WenzhiAccent,
        onPrimary = Color.White,
        primaryContainer = WenzhiAccentContainer,
        onPrimaryContainer = WenzhiInk,
        secondary = WenzhiSecondary,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFF0EEE8),
        onSecondaryContainer = WenzhiInk,
        tertiary = WenzhiInfo,
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFE8EDF0),
        onTertiaryContainer = WenzhiInk,
        background = WenzhiPaper,
        onBackground = WenzhiInk,
        surface = WenzhiSurface,
        onSurface = WenzhiInk,
        surfaceVariant = Color(0xFFF0EDE6),
        onSurfaceVariant = WenzhiSecondary,
        outline = WenzhiDivider,
        outlineVariant = WenzhiDisabled,
        error = WenzhiError,
        onError = Color.White,
        errorContainer = Color(0xFFF4E7E5),
        onErrorContainer = WenzhiError,
    )

private val WenzhiInkDark =
    darkColorScheme(
        primary = WenzhiDarkAccent,
        onPrimary = Color(0xFF10211E),
        primaryContainer = WenzhiDarkAccentContainer,
        onPrimaryContainer = Color(0xFFE5EEEB),
        secondary = WenzhiDarkSecondaryText,
        onSecondary = Color(0xFF242522),
        secondaryContainer = WenzhiDarkElevated,
        onSecondaryContainer = WenzhiDarkPrimaryText,
        tertiary = WenzhiDarkInfo,
        onTertiary = Color(0xFF162127),
        tertiaryContainer = Color(0xFF26343B),
        onTertiaryContainer = WenzhiDarkPrimaryText,
        background = WenzhiDarkBackground,
        onBackground = WenzhiDarkPrimaryText,
        surface = WenzhiDarkSurface,
        onSurface = WenzhiDarkPrimaryText,
        surfaceVariant = WenzhiDarkElevated,
        onSurfaceVariant = WenzhiDarkSecondaryText,
        outline = WenzhiDarkDivider,
        outlineVariant = WenzhiDarkTertiaryText,
        error = WenzhiDarkError,
        onError = Color(0xFF2A1110),
        errorContainer = Color(0xFF452624),
        onErrorContainer = Color(0xFFFFDAD6),
    )

private val MintDark =
    darkColorScheme(
        primary = Color(0xFF58D6B5),
        onPrimary = Color(0xFF00382D),
        primaryContainer = Color(0xFF173F36),
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
        error = Color(0xFFFF5C5C),
        onError = Color.White,
        errorContainer = Color(0xFF5D1F23),
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

@Immutable
data class VoicaSemanticColors(
    val brandPaper: Color,
    val brandInk: Color,
    val brandAccent: Color,
    val brandRecording: Color,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceVariant: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val disabled: Color,
    val accent: Color,
    val accentContainer: Color,
    val recording: Color,
    val recordingContainer: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    val transcriptCurrentWordBg: Color,
    val transcriptCurrentWordText: Color,
    val transcriptUpcomingText: Color,
    val transcriptActiveLineBar: Color,
    val transcriptActiveParagraphBg: Color,
    val transcriptPlayedText: Color,
)

private val LocalVoicaSemanticColors = staticCompositionLocalOf {
    wenzhiSemanticColors(darkTheme = false)
}

object VoicaThemeTokens {
    val colors: VoicaSemanticColors
        @Composable
        get() = LocalVoicaSemanticColors.current
}

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
            VoicaColorPreset.WENZHI_INK -> if (darkTheme) WenzhiInkDark else WenzhiInkLight
            VoicaColorPreset.MINT -> if (darkTheme) MintDark else MintLight
            VoicaColorPreset.BLUE -> if (darkTheme) BlueDark else BlueLight
            VoicaColorPreset.PURPLE -> if (darkTheme) PurpleDark else PurpleLight
            VoicaColorPreset.CUSTOM ->
                customColorScheme(
                    darkTheme = darkTheme,
                    accent = Color(settings.customAccentArgb),
                )
        }
    val semanticColors =
        if (settings.preset == VoicaColorPreset.WENZHI_INK) {
            wenzhiSemanticColors(darkTheme)
        } else {
            legacySemanticColors(scheme, darkTheme)
        }

    CompositionLocalProvider(LocalVoicaSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = VoicaTypography,
            content = content,
        )
    }
}

private fun wenzhiSemanticColors(darkTheme: Boolean): VoicaSemanticColors =
    if (darkTheme) {
        VoicaSemanticColors(
            brandPaper = WenzhiPaper,
            brandInk = WenzhiInk,
            brandAccent = WenzhiAccent,
            brandRecording = WenzhiRecording,
            background = WenzhiDarkBackground,
            surface = WenzhiDarkSurface,
            surfaceElevated = WenzhiDarkElevated,
            surfaceVariant = WenzhiDarkElevated,
            textPrimary = WenzhiDarkPrimaryText,
            textSecondary = WenzhiDarkSecondaryText,
            textTertiary = WenzhiDarkTertiaryText,
            divider = WenzhiDarkDivider,
            disabled = WenzhiDarkTertiaryText,
            accent = WenzhiDarkAccent,
            accentContainer = WenzhiDarkAccentContainer,
            recording = WenzhiDarkRecording,
            recordingContainer = WenzhiDarkRecordingContainer,
            warning = WenzhiDarkWarning,
            error = WenzhiDarkError,
            info = WenzhiDarkInfo,
            transcriptCurrentWordBg = WenzhiDarkAccent,
            transcriptCurrentWordText = WenzhiDarkBackground,
            transcriptUpcomingText = WenzhiDarkSecondaryText.copy(alpha = 0.82f),
            transcriptActiveLineBar = WenzhiDarkAccent,
            transcriptActiveParagraphBg = WenzhiDarkAccentContainer.copy(alpha = 0.76f),
            transcriptPlayedText = WenzhiDarkPrimaryText.copy(alpha = 0.9f),
        )
    } else {
        VoicaSemanticColors(
            brandPaper = WenzhiPaper,
            brandInk = WenzhiInk,
            brandAccent = WenzhiAccent,
            brandRecording = WenzhiRecording,
            background = WenzhiPaper,
            surface = WenzhiSurface,
            surfaceElevated = WenzhiElevated,
            surfaceVariant = Color(0xFFF0EDE6),
            textPrimary = WenzhiInk,
            textSecondary = WenzhiSecondary,
            textTertiary = WenzhiTertiary,
            divider = WenzhiDivider,
            disabled = WenzhiDisabled,
            accent = WenzhiAccent,
            accentContainer = WenzhiAccentContainer,
            recording = WenzhiRecording,
            recordingContainer = WenzhiRecordingContainer,
            warning = WenzhiWarning,
            error = WenzhiError,
            info = WenzhiInfo,
            transcriptCurrentWordBg = WenzhiAccent,
            transcriptCurrentWordText = Color.White,
            transcriptUpcomingText = WenzhiSecondary.copy(alpha = 0.82f),
            transcriptActiveLineBar = WenzhiAccent,
            transcriptActiveParagraphBg = WenzhiAccentContainer.copy(alpha = 0.72f),
            transcriptPlayedText = WenzhiInk.copy(alpha = 0.88f),
        )
    }

private fun legacySemanticColors(
    scheme: ColorScheme,
    darkTheme: Boolean,
): VoicaSemanticColors =
    VoicaSemanticColors(
        brandPaper = scheme.background,
        brandInk = scheme.onBackground,
        brandAccent = scheme.primary,
        brandRecording = scheme.error,
        background = scheme.background,
        surface = scheme.surface,
        surfaceElevated = scheme.surface,
        surfaceVariant = scheme.surfaceVariant,
        textPrimary = scheme.onSurface,
        textSecondary = scheme.onSurfaceVariant,
        textTertiary = scheme.onSurfaceVariant.copy(alpha = 0.72f),
        divider = scheme.outline,
        disabled = scheme.onSurfaceVariant.copy(alpha = 0.5f),
        accent = scheme.primary,
        accentContainer = scheme.primaryContainer,
        recording = scheme.error,
        recordingContainer = scheme.errorContainer,
        warning = if (darkTheme) scheme.tertiary else scheme.secondary,
        error = scheme.error,
        info = scheme.tertiary,
        transcriptCurrentWordBg = scheme.primaryContainer,
        transcriptCurrentWordText = scheme.onPrimaryContainer,
        transcriptUpcomingText = scheme.onSurfaceVariant.copy(alpha = 0.78f),
        transcriptActiveLineBar = scheme.primary,
        transcriptActiveParagraphBg = scheme.secondaryContainer.copy(alpha = 0.62f),
        transcriptPlayedText = scheme.onSurface.copy(alpha = 0.88f),
    )

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
