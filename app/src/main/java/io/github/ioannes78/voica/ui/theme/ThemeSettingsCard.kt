package io.github.ioannes78.voica.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R

@Composable
fun ThemeSettingsCard(
    store: ThemeSettingsStore,
    modifier: Modifier = Modifier,
) {
    val settings by store.settings.collectAsState()
    var customHex by rememberSaveable(settings.customAccentArgb) {
        mutableStateOf(formatRgb(settings.customAccentArgb))
    }
    var invalidCustomColor by rememberSaveable { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.theme_settings_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.theme_mode_title),
                style = MaterialTheme.typography.labelLarge,
            )

            ThemeModeOption(
                label = stringResource(R.string.theme_mode_system),
                selected = settings.mode == VoicaThemeMode.SYSTEM,
                onClick = { store.setMode(VoicaThemeMode.SYSTEM) },
            )
            ThemeModeOption(
                label = stringResource(R.string.theme_mode_light),
                selected = settings.mode == VoicaThemeMode.LIGHT,
                onClick = { store.setMode(VoicaThemeMode.LIGHT) },
            )
            ThemeModeOption(
                label = stringResource(R.string.theme_mode_dark),
                selected = settings.mode == VoicaThemeMode.DARK,
                onClick = { store.setMode(VoicaThemeMode.DARK) },
            )

            Text(
                stringResource(R.string.theme_palette_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = settings.preset == VoicaColorPreset.MINT,
                    onClick = { store.setPreset(VoicaColorPreset.MINT) },
                    label = { Text(stringResource(R.string.theme_palette_mint)) },
                )
                FilterChip(
                    selected = settings.preset == VoicaColorPreset.BLUE,
                    onClick = { store.setPreset(VoicaColorPreset.BLUE) },
                    label = { Text(stringResource(R.string.theme_palette_blue)) },
                )
                FilterChip(
                    selected = settings.preset == VoicaColorPreset.PURPLE,
                    onClick = { store.setPreset(VoicaColorPreset.PURPLE) },
                    label = { Text(stringResource(R.string.theme_palette_purple)) },
                )
            }

            Text(
                stringResource(R.string.theme_custom_accent_title),
                style = MaterialTheme.typography.labelLarge,
            )
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
                        customHex = formatRgb(parsed)
                        store.setCustomAccent(parsed)
                    }
                },
            ) {
                Text(stringResource(R.string.theme_custom_accent_apply))
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
private fun ThemeModeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Text(label)
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
