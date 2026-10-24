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
private val WenzhiInk = Color(0xFF171717)
private val WenzhiSecondary = Color(0xFF656565)
private val WenzhiAccent = Color(0xFF315E58)
private val WenzhiAccentContainer = Color(0xFFE5EEEB)
private val WenzhiTranscriptCurrentWordBg = mix(WenzhiAccentContainer, WenzhiAccent, 0.24f)
private val WenzhiRecording = Color(0xFFB94D48)
private val WenzhiRecordingContainer = Color(0xFFF5E8E5)
private val WenzhiWarning = Color(0xFFA87536)
private val WenzhiError = Color(0xFFA94442)
private val WenzhiInfo = Color(0xFF536A78)

private val WenzhiDarkPrimaryText = Color(0xFFF2EFE7)
private val WenzhiDarkSecondaryText = Color(0xFFB4B0A7)
private val WenzhiDarkAccent = Color(0xFF79A69D)
private val WenzhiDarkAccentContainer = Color(0xFF233632)
private val WenzhiDarkTranscriptCurrentWordBg = mix(WenzhiDarkAccentContainer, WenzhiDarkAccent, 0.26f)
private val WenzhiDarkRecording = Color(0xFFD36A64)
private val WenzhiDarkRecordingContainer = Color(0xFF3A2321)
private val WenzhiDarkWarning = Color(0xFFC6965A)
private val WenzhiDarkError = Color(0xFFD1706C)
private val WenzhiDarkInfo = Color(0xFF7F98A6)

@Immutable
private data class SurfacePalette(
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceDim: Color,
    val surfaceBright: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
)

private val WenzhiLightSurfaces =
    SurfacePalette(
        background = WenzhiPaper,
        onBackground = WenzhiInk,
        surface = Color(0xFFFBFAF6),
        onSurface = WenzhiInk,
        surfaceDim = Color(0xFFDED9CF),
        surfaceBright = Color(0xFFFFFDF8),
        surfaceContainerLowest = Color(0xFFFFFDF9),
        surfaceContainerLow = Color(0xFFF7F4ED),
        surfaceContainer = Color(0xFFF2EFE7),
        surfaceContainerHigh = Color(0xFFECE8DF),
        surfaceContainerHighest = Color(0xFFE6E1D7),
        surfaceVariant = Color(0xFFF0EDE6),
        onSurfaceVariant = WenzhiSecondary,
        outline = Color(0xFF817B70),
        outlineVariant = Color(0xFFD9D3C9),
        inverseSurface = Color(0xFF31312E),
        inverseOnSurface = Color(0xFFF7F3EA),
    )

private val WenzhiDarkSurfaces =
    SurfacePalette(
        background = Color(0xFF111210),
        onBackground = WenzhiDarkPrimaryText,
        surface = Color(0xFF191A18),
        onSurface = WenzhiDarkPrimaryText,
        surfaceDim = Color(0xFF111210),
        surfaceBright = Color(0xFF383936),
        surfaceContainerLowest = Color(0xFF0C0D0B),
        surfaceContainerLow = Color(0xFF171815),
        surfaceContainer = Color(0xFF1D1E1B),
        surfaceContainerHigh = Color(0xFF252622),
        surfaceContainerHighest = Color(0xFF2D2E2A),
        surfaceVariant = Color(0xFF252622),
        onSurfaceVariant = WenzhiDarkSecondaryText,
        outline = Color(0xFF938F86),
        outlineVariant = Color(0xFF454640),
        inverseSurface = Color(0xFFE8E4DC),
        inverseOnSurface = Color(0xFF2B2B28),
    )

private val MintLightSurfaces =
    SurfacePalette(
        background = Color(0xFFF5F9F8),
        onBackground = Color(0xFF17201E),
        surface = Color(0xFFFBFDFC),
        onSurface = Color(0xFF17201E),
        surfaceDim = Color(0xFFDDE5E3),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF3F8F6),
        surfaceContainer = Color(0xFFEDF4F2),
        surfaceContainerHigh = Color(0xFFE6EFEC),
        surfaceContainerHighest = Color(0xFFDDE9E5),
        surfaceVariant = Color(0xFFE7EFEC),
        onSurfaceVariant = Color(0xFF53635E),
        outline = Color(0xFF75857F),
        outlineVariant = Color(0xFFCCD8D4),
        inverseSurface = Color(0xFF26332F),
        inverseOnSurface = Color(0xFFEDF5F2),
    )

private val MintDarkSurfaces =
    SurfacePalette(
        background = Color(0xFF0F1715),
        onBackground = Color(0xFFEEF3F2),
        surface = Color(0xFF151E1B),
        onSurface = Color(0xFFEEF3F2),
        surfaceDim = Color(0xFF0F1715),
        surfaceBright = Color(0xFF35413D),
        surfaceContainerLowest = Color(0xFF0A100E),
        surfaceContainerLow = Color(0xFF141C19),
        surfaceContainer = Color(0xFF1A2421),
        surfaceContainerHigh = Color(0xFF222D29),
        surfaceContainerHighest = Color(0xFF2A3632),
        surfaceVariant = Color(0xFF26332F),
        onSurfaceVariant = Color(0xFFAAB8B3),
        outline = Color(0xFF84938E),
        outlineVariant = Color(0xFF3C4945),
        inverseSurface = Color(0xFFDCE7E3),
        inverseOnSurface = Color(0xFF26322F),
    )

private val BlueLightSurfaces =
    SurfacePalette(
        background = Color(0xFFF5F8FC),
        onBackground = Color(0xFF17212B),
        surface = Color(0xFFFAFCFF),
        onSurface = Color(0xFF17212B),
        surfaceDim = Color(0xFFDCE4EC),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF2F6FA),
        surfaceContainer = Color(0xFFECF2F7),
        surfaceContainerHigh = Color(0xFFE4EBF2),
        surfaceContainerHighest = Color(0xFFDCE5ED),
        surfaceVariant = Color(0xFFE8EFF5),
        onSurfaceVariant = Color(0xFF566575),
        outline = Color(0xFF778696),
        outlineVariant = Color(0xFFCBD6E0),
        inverseSurface = Color(0xFF28343E),
        inverseOnSurface = Color(0xFFEEF4F9),
    )

private val BlueDarkSurfaces =
    SurfacePalette(
        background = Color(0xFF0E161E),
        onBackground = Color(0xFFEAF1F7),
        surface = Color(0xFF131C25),
        onSurface = Color(0xFFEAF1F7),
        surfaceDim = Color(0xFF0E161E),
        surfaceBright = Color(0xFF34424E),
        surfaceContainerLowest = Color(0xFF091017),
        surfaceContainerLow = Color(0xFF121A22),
        surfaceContainer = Color(0xFF18232D),
        surfaceContainerHigh = Color(0xFF202C37),
        surfaceContainerHighest = Color(0xFF293642),
        surfaceVariant = Color(0xFF24313C),
        onSurfaceVariant = Color(0xFFAEBCC8),
        outline = Color(0xFF8795A1),
        outlineVariant = Color(0xFF3A4853),
        inverseSurface = Color(0xFFDCE6EE),
        inverseOnSurface = Color(0xFF26323C),
    )

private val PurpleLightSurfaces =
    SurfacePalette(
        background = Color(0xFFFAF8FC),
        onBackground = Color(0xFF211E24),
        surface = Color(0xFFFDFBFE),
        onSurface = Color(0xFF211E24),
        surfaceDim = Color(0xFFE2DDE5),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF7F3F9),
        surfaceContainer = Color(0xFFF1EDF4),
        surfaceContainerHigh = Color(0xFFEBE5EE),
        surfaceContainerHighest = Color(0xFFE4DDE8),
        surfaceVariant = Color(0xFFEEE8F1),
        onSurfaceVariant = Color(0xFF625B67),
        outline = Color(0xFF847A89),
        outlineVariant = Color(0xFFD7CEDB),
        inverseSurface = Color(0xFF322E35),
        inverseOnSurface = Color(0xFFF5EFF7),
    )

private val PurpleDarkSurfaces =
    SurfacePalette(
        background = Color(0xFF17151A),
        onBackground = Color(0xFFF0EAF2),
        surface = Color(0xFF1D1A20),
        onSurface = Color(0xFFF0EAF2),
        surfaceDim = Color(0xFF17151A),
        surfaceBright = Color(0xFF403942),
        surfaceContainerLowest = Color(0xFF100E12),
        surfaceContainerLow = Color(0xFF1B181E),
        surfaceContainer = Color(0xFF231F26),
        surfaceContainerHigh = Color(0xFF2C2730),
        surfaceContainerHighest = Color(0xFF352F39),
        surfaceVariant = Color(0xFF302A34),
        onSurfaceVariant = Color(0xFFC1B7C5),
        outline = Color(0xFF948A99),
        outlineVariant = Color(0xFF4A424E),
        inverseSurface = Color(0xFFE9E1EC),
        inverseOnSurface = Color(0xFF302B33),
    )

private fun ColorScheme.withSurfacePalette(
    palette: SurfacePalette,
    surfaceTint: Color,
    inversePrimary: Color,
): ColorScheme =
    copy(
        background = palette.background,
        onBackground = palette.onBackground,
        surface = palette.surface,
        onSurface = palette.onSurface,
        surfaceDim = palette.surfaceDim,
        surfaceBright = palette.surfaceBright,
        surfaceContainerLowest = palette.surfaceContainerLowest,
        surfaceContainerLow = palette.surfaceContainerLow,
        surfaceContainer = palette.surfaceContainer,
        surfaceContainerHigh = palette.surfaceContainerHigh,
        surfaceContainerHighest = palette.surfaceContainerHighest,
        surfaceVariant = palette.surfaceVariant,
        onSurfaceVariant = palette.onSurfaceVariant,
        surfaceTint = surfaceTint,
        outline = palette.outline,
        outlineVariant = palette.outlineVariant,
        inverseSurface = palette.inverseSurface,
        inverseOnSurface = palette.inverseOnSurface,
        inversePrimary = inversePrimary,
        scrim = Color.Black,
    )

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
        error = WenzhiError,
        onError = Color.White,
        errorContainer = Color(0xFFF4E7E5),
        onErrorContainer = WenzhiError,
    ).withSurfacePalette(
        palette = WenzhiLightSurfaces,
        surfaceTint = WenzhiAccent,
        inversePrimary = WenzhiDarkAccent,
    )

private val WenzhiInkDark =
    darkColorScheme(
        primary = WenzhiDarkAccent,
        onPrimary = Color(0xFF10211E),
        primaryContainer = WenzhiDarkAccentContainer,
        onPrimaryContainer = Color(0xFFE5EEEB),
        secondary = WenzhiDarkSecondaryText,
        onSecondary = Color(0xFF242522),
        secondaryContainer = Color(0xFF2A2B27),
        onSecondaryContainer = WenzhiDarkPrimaryText,
        tertiary = WenzhiDarkInfo,
        onTertiary = Color(0xFF162127),
        tertiaryContainer = Color(0xFF26343B),
        onTertiaryContainer = WenzhiDarkPrimaryText,
        error = WenzhiDarkError,
        onError = Color(0xFF2A1110),
        errorContainer = Color(0xFF452624),
        onErrorContainer = Color(0xFFFFDAD6),
    ).withSurfacePalette(
        palette = WenzhiDarkSurfaces,
        surfaceTint = WenzhiDarkAccent,
        inversePrimary = WenzhiAccent,
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
        tertiary = Color(0xFF496773),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFCDE9F3),
        onTertiaryContainer = Color(0xFF041F28),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    ).withSurfacePalette(
        palette = MintLightSurfaces,
        surfaceTint = Color(0xFF087D67),
        inversePrimary = Color(0xFF58D6B5),
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
        tertiary = Color(0xFFABCBD8),
        onTertiary = Color(0xFF15343F),
        tertiaryContainer = Color(0xFF2D4B56),
        onTertiaryContainer = Color(0xFFC7E7F4),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    ).withSurfacePalette(
        palette = MintDarkSurfaces,
        surfaceTint = Color(0xFF58D6B5),
        inversePrimary = Color(0xFF087D67),
    )

private val BlueLight =
    lightColorScheme(
        primary = Color(0xFF00639A),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFCDE5FF),
        onPrimaryContainer = Color(0xFF001D32),
        secondary = Color(0xFF506579),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDCE7F1),
        onSecondaryContainer = Color(0xFF0D1D2A),
        tertiary = Color(0xFF5F5E7A),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFE5E2F2),
        onTertiaryContainer = Color(0xFF1B1B35),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    ).withSurfacePalette(
        palette = BlueLightSurfaces,
        surfaceTint = Color(0xFF00639A),
        inversePrimary = Color(0xFF9FC9FF),
    )

private val BlueDark =
    darkColorScheme(
        primary = Color(0xFF9FC9FF),
        onPrimary = Color(0xFF00325A),
        primaryContainer = Color(0xFF00497F),
        onPrimaryContainer = Color(0xFFD1E4FF),
        secondary = Color(0xFFB8C9D8),
        onSecondary = Color(0xFF22323F),
        secondaryContainer = Color(0xFF384956),
        onSecondaryContainer = Color(0xFFD4E5F4),
        tertiary = Color(0xFFC7C4EA),
        onTertiary = Color(0xFF302F4C),
        tertiaryContainer = Color(0xFF474663),
        onTertiaryContainer = Color(0xFFE4E0FF),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    ).withSurfacePalette(
        palette = BlueDarkSurfaces,
        surfaceTint = Color(0xFF9FC9FF),
        inversePrimary = Color(0xFF00639A),
    )

private val PurpleLight =
    lightColorScheme(
        primary = Color(0xFF6750A4),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFEADDFF),
        onPrimaryContainer = Color(0xFF21005D),
        secondary = Color(0xFF625B71),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE8DEF8),
        onSecondaryContainer = Color(0xFF1D192B),
        tertiary = Color(0xFF7D5260),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFD8E4),
        onTertiaryContainer = Color(0xFF31111D),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    ).withSurfacePalette(
        palette = PurpleLightSurfaces,
        surfaceTint = Color(0xFF6750A4),
        inversePrimary = Color(0xFFD0BCFF),
    )

private val PurpleDark =
    darkColorScheme(
        primary = Color(0xFFD0BCFF),
        onPrimary = Color(0xFF381E72),
        primaryContainer = Color(0xFF4F378B),
        onPrimaryContainer = Color(0xFFEADDFF),
        secondary = Color(0xFFCCC2DC),
        onSecondary = Color(0xFF332D41),
        secondaryContainer = Color(0xFF4A4458),
        onSecondaryContainer = Color(0xFFE8DEF8),
        tertiary = Color(0xFFEFB8C8),
        onTertiary = Color(0xFF492532),
        tertiaryContainer = Color(0xFF633B48),
        onTertiaryContainer = Color(0xFFFFD8E4),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    ).withSurfacePalette(
        palette = PurpleDarkSurfaces,
        surfaceTint = Color(0xFFD0BCFF),
        inversePrimary = Color(0xFF6750A4),
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
    wenzhiSemanticColors(WenzhiInkLight, darkTheme = false)
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
        resolveVoicaColorScheme(
            preset = settings.preset,
            darkTheme = darkTheme,
            customAccentArgb = settings.customAccentArgb,
        )
    val semanticColors =
        if (settings.preset == VoicaColorPreset.WENZHI_INK) {
            wenzhiSemanticColors(scheme, darkTheme)
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

internal fun resolveVoicaColorScheme(
    preset: VoicaColorPreset,
    darkTheme: Boolean,
    customAccentArgb: Int = VoicaThemeSettings.DEFAULT_CUSTOM_ACCENT,
): ColorScheme =
    when (preset) {
        VoicaColorPreset.WENZHI_INK -> if (darkTheme) WenzhiInkDark else WenzhiInkLight
        VoicaColorPreset.MINT -> if (darkTheme) MintDark else MintLight
        VoicaColorPreset.BLUE -> if (darkTheme) BlueDark else BlueLight
        VoicaColorPreset.PURPLE -> if (darkTheme) PurpleDark else PurpleLight
        VoicaColorPreset.CUSTOM ->
            customColorScheme(
                darkTheme = darkTheme,
                accent = Color(customAccentArgb),
            )
    }

private fun wenzhiSemanticColors(
    scheme: ColorScheme,
    darkTheme: Boolean,
): VoicaSemanticColors =
    VoicaSemanticColors(
        brandPaper = WenzhiPaper,
        brandInk = WenzhiInk,
        brandAccent = WenzhiAccent,
        brandRecording = WenzhiRecording,
        background = scheme.background,
        surface = scheme.surface,
        surfaceElevated = scheme.surfaceContainerHigh,
        surfaceVariant = scheme.surfaceContainer,
        textPrimary = scheme.onSurface,
        textSecondary = scheme.onSurfaceVariant,
        textTertiary = scheme.onSurfaceVariant.copy(alpha = 0.72f),
        divider = scheme.outlineVariant,
        disabled = scheme.onSurfaceVariant.copy(alpha = 0.5f),
        accent = scheme.primary,
        accentContainer = scheme.primaryContainer,
        recording = if (darkTheme) WenzhiDarkRecording else WenzhiRecording,
        recordingContainer = if (darkTheme) WenzhiDarkRecordingContainer else WenzhiRecordingContainer,
        warning = if (darkTheme) WenzhiDarkWarning else WenzhiWarning,
        error = if (darkTheme) WenzhiDarkError else WenzhiError,
        info = if (darkTheme) WenzhiDarkInfo else WenzhiInfo,
        transcriptCurrentWordBg = if (darkTheme) WenzhiDarkTranscriptCurrentWordBg else WenzhiTranscriptCurrentWordBg,
        transcriptCurrentWordText = scheme.onPrimaryContainer,
        transcriptUpcomingText = scheme.onSurfaceVariant.copy(alpha = 0.82f),
        transcriptActiveLineBar = scheme.primary,
        transcriptActiveParagraphBg = scheme.primaryContainer,
        transcriptPlayedText = scheme.onSurface.copy(alpha = if (darkTheme) 0.9f else 0.88f),
    )

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
        surfaceElevated = scheme.surfaceContainerHigh,
        surfaceVariant = scheme.surfaceContainer,
        textPrimary = scheme.onSurface,
        textSecondary = scheme.onSurfaceVariant,
        textTertiary = scheme.onSurfaceVariant.copy(alpha = 0.72f),
        divider = scheme.outlineVariant,
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
        transcriptActiveParagraphBg = scheme.secondaryContainer,
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
