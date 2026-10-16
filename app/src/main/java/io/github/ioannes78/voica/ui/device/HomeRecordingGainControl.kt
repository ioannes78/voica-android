package io.github.ioannes78.voica.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.protocol.RecordingGain

/** Presentation-only entry point for the existing recorder-card SET_GAIN -> GET_GAIN chain. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeRecordingGainControl(
    gain: RecordingGain?,
    interactionEnabled: Boolean,
    busy: Boolean,
    errorMessage: String?,
    onSetGain: (RecordingGain) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sheetVisible by remember { mutableStateOf(false) }
    var pendingTarget by remember { mutableStateOf<RecordingGain?>(null) }

    LaunchedEffect(sheetVisible, pendingTarget, gain, busy, errorMessage) {
        val target = pendingTarget ?: return@LaunchedEffect
        if (sheetVisible && !busy && errorMessage.isNullOrBlank() && gain == target) {
            pendingTarget = null
            sheetVisible = false
        }
    }

    TextButton(
        onClick = {
            pendingTarget = null
            sheetVisible = true
        },
        enabled = interactionEnabled || gain != null,
        modifier = modifier,
    ) {
        Text(
            "增益：${gainLabel(gain)} ›",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
    }

    if (!sheetVisible) return

    fun select(target: RecordingGain) {
        if (busy) return
        if (gain == target) {
            pendingTarget = null
            sheetVisible = false
            return
        }
        pendingTarget = target
        onSetGain(target)
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!busy) {
                pendingTarget = null
                sheetVisible = false
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "录音增益",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                when {
                    busy -> "正在设置并读取设备真实值…"
                    !errorMessage.isNullOrBlank() -> "设备设置未完成，请检查后重试"
                    gain == null -> "尚未取得设备增益值"
                    else -> "当前设备值：${gainLabel(gain)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GainChoiceRow(
                label = "低",
                selected = gain == RecordingGain.Low,
                enabled = interactionEnabled && !busy,
                onClick = { select(RecordingGain.Low) },
            )
            GainChoiceRow(
                label = "中",
                selected = gain == RecordingGain.Medium,
                enabled = interactionEnabled && !busy,
                onClick = { select(RecordingGain.Medium) },
            )
            GainChoiceRow(
                label = "高",
                selected = gain == RecordingGain.High,
                enabled = interactionEnabled && !busy,
                onClick = { select(RecordingGain.High) },
            )

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun GainChoiceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun gainLabel(gain: RecordingGain?): String =
    when (gain) {
        RecordingGain.Low -> "低"
        RecordingGain.Medium -> "中"
        RecordingGain.High -> "高"
        is RecordingGain.UnknownRaw -> "未知(${gain.rawValue})"
        null -> "未知"
    }
