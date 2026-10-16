package io.github.ioannes78.voica.ui.recording

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.AppPhoneMicrophoneNavigation
import io.github.ioannes78.voica.AppRealtimeNavigation
import io.github.ioannes78.voica.PhoneMicrophoneProductTarget
import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.ui.phone.LocalPhoneMicrophoneRealtimeRuntime
import io.github.ioannes78.voica.ui.phone.LocalPhoneMicrophoneRecordingRuntime
import io.github.ioannes78.voica.ui.realtime.LocalStage16ARealtimeRuntime
import io.github.ioannes78.voica.ui.status.AppActivitySource
import io.github.ioannes78.voica.ui.status.AppLiveActivityVisibility
import io.github.ioannes78.voica.ui.status.LiveActivityStripKind
import io.github.ioannes78.voica.ui.status.LiveActivityStripPhase
import io.github.ioannes78.voica.ui.status.resolveLiveActivityStrip
import java.util.Locale

@Composable
fun GlobalRecordingStatusBar(
    state: RecordingDeviceState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deviceRealtime by LocalStage16ARealtimeRuntime.current.state.collectAsState()
    val phoneRecording by LocalPhoneMicrophoneRecordingRuntime.current.state.collectAsState()
    val phoneRealtime by LocalPhoneMicrophoneRealtimeRuntime.current.state.collectAsState()
    val visibleRealtimeSource by AppLiveActivityVisibility.visibleRealtimeSource.collectAsState()
    val item =
        resolveLiveActivityStrip(
            deviceRecording = state,
            deviceRealtime = deviceRealtime,
            phoneRecording = phoneRecording,
            phoneRealtime = phoneRealtime,
            visibleRealtimeSource = visibleRealtimeSource,
        ) ?: return

    val paused = item.phase == LiveActivityStripPhase.PAUSED
    val realtime = item.kind == LiveActivityStripKind.REALTIME
    val stripClick = {
        when {
            realtime && item.source == AppActivitySource.RECORDER_CARD -> {
                AppPhoneMicrophoneNavigation.dismiss()
                AppRealtimeNavigation.publish()
            }
            realtime && item.source == AppActivitySource.PHONE_MICROPHONE -> {
                AppRealtimeNavigation.dismiss()
                AppPhoneMicrophoneNavigation.publish(PhoneMicrophoneProductTarget.REALTIME)
            }
            else -> onClick()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = stripClick),
        color =
            when {
                paused -> MaterialTheme.colorScheme.secondaryContainer
                realtime -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.errorContainer
            },
        contentColor =
            when {
                paused -> MaterialTheme.colorScheme.onSecondaryContainer
                realtime -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onErrorContainer
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (paused) Icons.Filled.PauseCircle else Icons.Filled.FiberManualRecord,
                contentDescription = null,
            )
            Text(
                text = "${item.statusLabel} · ${item.sourceLabel} · ${formatDuration(item.elapsedMs)}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "查看",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private fun formatDuration(elapsedMs: Long): String {
    val value = elapsedMs.coerceAtLeast(0L) / 1_000L
    val hours = value / 3_600L
    val minutes = (value % 3_600L) / 60L
    val seconds = value % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
