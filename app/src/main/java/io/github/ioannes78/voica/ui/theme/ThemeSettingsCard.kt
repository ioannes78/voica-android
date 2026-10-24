package io.github.ioannes78.voica.ui.theme

import android.graphics.Color as AndroidColor
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R
import kotlin.math.abs
import kotlin.math.roundToInt

private data class AccentChoice(
    @StringRes val labelRes: Int,
    val argb: Int,
)

private val accentChoices =
    listOf(
        AccentChoice(R.string.theme_color_ink, 0xFF343434.toInt()),
        AccentChoice(R.string.theme_color_warm_brown, 0xFF776244.toInt()),
        AccentChoice(R.string.theme_color_tea_green, 0xFF63745A.toInt()),
        AccentChoice(R.string.theme_color_blue_gray, 0xFF5E7478.toInt()),
        AccentChoice(R.string.theme_color_indigo, 0xFF4F638A.toInt()),
        AccentChoice(R.string.theme_color_purple_gray, 0xFF75677F.toInt()),
        AccentChoice(R.string.theme_color_terracotta, 0xFFA65D4E.toInt()),
        AccentChoice(R.string.theme_color_amber, 0xFFA77A36.toInt()),
        AccentChoice(R.string.theme_color_pine, 0xFF3F6D5E.toInt()),
        AccentChoice(R.string.theme_color_lake, 0xFF3F7D8F.toInt()),
        AccentChoice(R.string.theme_color_blue_violet, 0xFF625CA0.toInt()),
    )

@Composable
fun ThemeSettingsCard(
    store: ThemeSettingsStore,
    modifier: Modifier = Modifier,
) {
    val settings by store.settings.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val previewDark =
        when (settings.mode) {
            VoicaThemeMode.SYSTEM -> systemDark
            VoicaThemeMode.LIGHT -> false
            VoicaThemeMode.DARK -> true
        }
    var customHex by rememberSaveable { mutableStateOf(formatRgb(settings.customAccentArgb)) }
    var invalidCustomColor by rememberSaveable { mutableStateOf(false) }
    val initialHsl = remember(settings.customAccentArgb) { rgbToHsl(settings.customAccentArgb) }
    var hue by rememberSaveable { mutableFloatStateOf(initialHsl[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(initialHsl[1]) }
    var lightness by rememberSaveable { mutableFloatStateOf(initialHsl[2]) }

    LaunchedEffect(settings.customAccentArgb) {
        customHex = formatRgb(settings.customAccentArgb)
        val hsl = rgbToHsl(settings.customAccentArgb)
        hue = hsl[0]
        saturation = hsl[1]
        lightness = hsl[2]
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.theme_settings_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.theme_mode_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = settings.mode == VoicaThemeMode.SYSTEM,
                    onClick = { store.setMode(VoicaThemeMode.SYSTEM) },
                    label = { Text(stringResource(R.string.theme_mode_system)) },
                )
                FilterChip(
                    selected = settings.mode == VoicaThemeMode.LIGHT,
                    onClick = { store.setMode(VoicaThemeMode.LIGHT) },
                    label = { Text(stringResource(R.string.theme_mode_light)) },
                )
                FilterChip(
                    selected = settings.mode == VoicaThemeMode.DARK,
                    onClick = { store.setMode(VoicaThemeMode.DARK) },
                    label = { Text(stringResource(R.string.theme_mode_dark)) },
                )
            }

            Text(stringResource(R.string.theme_brand_section), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemePreviewCard(
                    label = stringResource(R.string.theme_palette_wenzhi),
                    badge = stringResource(R.string.theme_default_badge),
                    scheme = resolveVoicaColorScheme(VoicaColorPreset.WENZHI_INK, previewDark),
                    selected = settings.preset == VoicaColorPreset.WENZHI_INK,
                    onClick = { store.setPreset(VoicaColorPreset.WENZHI_INK) },
                    modifier = Modifier.weight(1f),
                )
                ThemePreviewCard(
                    label = stringResource(R.string.theme_palette_wenzhi_paper),
                    scheme =
                        resolveVoicaColorScheme(
                            preset = VoicaColorPreset.WENZHI_PAPER,
                            darkTheme = previewDark,
                            paperTone = settings.paperTone,
                        ),
                    selected = settings.preset == VoicaColorPreset.WENZHI_PAPER,
                    onClick = { store.setPreset(VoicaColorPreset.WENZHI_PAPER) },
                    modifier = Modifier.weight(1f),
                )
            }

            if (settings.preset == VoicaColorPreset.WENZHI_PAPER) {
                Text(stringResource(R.string.theme_paper_tone_title), style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    PaperToneSwatch(
                        tone = WenzhiPaperTone.PAPER_WHITE,
                        label = stringResource(R.string.theme_paper_tone_white),
                        selected = settings.paperTone == WenzhiPaperTone.PAPER_WHITE,
                        onClick = { store.setPaperTone(WenzhiPaperTone.PAPER_WHITE) },
                    )
                    PaperToneSwatch(
                        tone = WenzhiPaperTone.XUAN_WARM,
                        label = stringResource(R.string.theme_paper_tone_xuan),
                        selected = settings.paperTone == WenzhiPaperTone.XUAN_WARM,
                        onClick = { store.setPaperTone(WenzhiPaperTone.XUAN_WARM) },
                    )
                    PaperToneSwatch(
                        tone = WenzhiPaperTone.RICE_APRICOT,
                        label = stringResource(R.string.theme_paper_tone_apricot),
                        selected = settings.paperTone == WenzhiPaperTone.RICE_APRICOT,
                        onClick = { store.setPaperTone(WenzhiPaperTone.RICE_APRICOT) },
                    )
                    PaperToneSwatch(
                        tone = WenzhiPaperTone.ANTIQUE,
                        label = stringResource(R.string.theme_paper_tone_antique),
                        selected = settings.paperTone == WenzhiPaperTone.ANTIQUE,
                        onClick = { store.setPaperTone(WenzhiPaperTone.ANTIQUE) },
                    )
                }
            }

            Text(stringResource(R.string.theme_classic_section), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ThemePreviewCard(
                    label = stringResource(R.string.theme_palette_mint),
                    scheme = resolveVoicaColorScheme(VoicaColorPreset.MINT, previewDark),
                    selected = settings.preset == VoicaColorPreset.MINT,
                    onClick = { store.setPreset(VoicaColorPreset.MINT) },
                    modifier = Modifier.weight(1f),
                )
                ThemePreviewCard(
                    label = stringResource(R.string.theme_palette_blue),
                    scheme = resolveVoicaColorScheme(VoicaColorPreset.BLUE, previewDark),
                    selected = settings.preset == VoicaColorPreset.BLUE,
                    onClick = { store.setPreset(VoicaColorPreset.BLUE) },
                    modifier = Modifier.weight(1f),
                )
                ThemePreviewCard(
                    label = stringResource(R.string.theme_palette_purple),
                    scheme = resolveVoicaColorScheme(VoicaColorPreset.PURPLE, previewDark),
                    selected = settings.preset == VoicaColorPreset.PURPLE,
                    onClick = { store.setPreset(VoicaColorPreset.PURPLE) },
                    modifier = Modifier.weight(1f),
                )
            }

            Text(stringResource(R.string.theme_custom_palette_title), style = MaterialTheme.typography.labelLarge)
            accentChoices.chunked(4).forEach { rowChoices ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rowChoices.forEach { choice ->
                        AccentSwatch(
                            choice = choice,
                            selected =
                                settings.preset == VoicaColorPreset.CUSTOM &&
                                    settings.customAccentArgb == choice.argb,
                            onClick = { store.setCustomAccent(choice.argb) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(4 - rowChoices.size) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }

            if (settings.preset == VoicaColorPreset.CUSTOM) {
                Text(stringResource(R.string.theme_custom_advanced_title), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = customHex,
                    onValueChange = {
                        customHex = it
                        invalidCustomColor = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.theme_custom_accent_hint)) },
                    isError = invalidCustomColor,
                    supportingText = {
                        if (invalidCustomColor) {
                            Text(stringResource(R.string.theme_custom_accent_error))
                        }
                    },
                )
                Button(
                    onClick = {
                        val parsed = parseRgb(customHex)
                        if (parsed == null) {
                            invalidCustomColor = true
                        } else {
                            invalidCustomColor = false
                            store.setCustomAccent(parsed)
                        }
                    },
                ) {
                    Text(stringResource(R.string.theme_custom_accent_apply))
                }

                HslSlider(
                    label = stringResource(R.string.theme_custom_hue, hue.roundToInt()),
                    value = hue,
                    range = 0f..360f,
                    onValueChange = { value ->
                        hue = value
                        store.setCustomAccent(hslToArgb(hue, saturation, lightness))
                    },
                )
                HslSlider(
                    label = stringResource(R.string.theme_custom_saturation, (saturation * 100f).roundToInt()),
                    value = saturation,
                    range = 0f..1f,
                    onValueChange = { value ->
                        saturation = value
                        store.setCustomAccent(hslToArgb(hue, saturation, lightness))
                    },
                )
                HslSlider(
                    label = stringResource(R.string.theme_custom_lightness, (lightness * 100f).roundToInt()),
                    value = lightness,
                    range = 0.12f..0.88f,
                    onValueChange = { value ->
                        lightness = value
                        store.setCustomAccent(hslToArgb(hue, saturation, lightness))
                    },
                )
            }

            Text(
                stringResource(R.string.theme_settings_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThemePreviewCard(
    label: String,
    scheme: androidx.compose.material3.ColorScheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = onClick,
        modifier =
            modifier.border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) scheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                )
                badge?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.primary,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PreviewDot(scheme.background)
                PreviewDot(scheme.surfaceContainerHigh)
                PreviewDot(scheme.primary)
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(scheme.primaryContainer, RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(
                    "Aa",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun PreviewDot(color: Color) {
    Box(
        modifier =
            Modifier
                .size(22.dp)
                .background(color, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

@Composable
private fun PaperToneSwatch(
    tone: WenzhiPaperTone,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = resolveVoicaColorScheme(VoicaColorPreset.WENZHI_PAPER, false, paperTone = tone).background
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(if (selected) 36.dp else 32.dp)
                    .background(color, CircleShape)
                    .border(
                        if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AccentSwatch(
    choice: AccentChoice,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(if (selected) 34.dp else 30.dp)
                    .background(Color(choice.argb), CircleShape)
                    .border(
                        if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
        )
        Text(stringResource(choice.labelRes), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HslSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
        )
    }
}

private fun parseRgb(value: String): Int? {
    val normalized = value.trim().removePrefix("#")
    if (normalized.length != 6 || normalized.any { it.digitToIntOrNull(16) == null }) {
        return null
    }
    return runCatching {
        (0xFF000000L or normalized.toLong(16)).toInt()
    }.getOrNull()
}

private fun formatRgb(argb: Int): String =
    "#%06X".format(argb and 0x00FFFFFF)

private fun rgbToHsl(argb: Int): FloatArray {
    val r = AndroidColor.red(argb) / 255f
    val g = AndroidColor.green(argb) / 255f
    val b = AndroidColor.blue(argb) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val lightness = (max + min) / 2f
    val saturation =
        if (delta == 0f) {
            0f
        } else {
            delta / (1f - abs(2f * lightness - 1f))
        }
    val hue =
        when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }
    return floatArrayOf(hue, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f))
}

private fun hslToArgb(
    hue: Float,
    saturation: Float,
    lightness: Float,
): Int {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)
    val chroma = (1f - abs(2f * l - 1f)) * s
    val x = chroma * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - chroma / 2f
    val (r1, g1, b1) =
        when {
            h < 60f -> Triple(chroma, x, 0f)
            h < 120f -> Triple(x, chroma, 0f)
            h < 180f -> Triple(0f, chroma, x)
            h < 240f -> Triple(0f, x, chroma)
            h < 300f -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
    return AndroidColor.rgb(
        ((r1 + m) * 255f).roundToInt().coerceIn(0, 255),
        ((g1 + m) * 255f).roundToInt().coerceIn(0, 255),
        ((b1 + m) * 255f).roundToInt().coerceIn(0, 255),
    )
}
