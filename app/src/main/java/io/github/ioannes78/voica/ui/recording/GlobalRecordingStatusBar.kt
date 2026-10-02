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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.protocol.RecordingStatus

@Composable
fun GlobalRecordingStatusBar(
    state: RecordingDeviceState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = state.status
    if (status != RecordingStatus.Recording && status != RecordingStatus.Paused) {
        return
    }

    val recording = status == RecordingStatus.Recording
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color =
            if (recording) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        contentColor =
            if (recording) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector =
                    if (recording) {
                        Icons.Filled.FiberManualRecord
                    } else {
                        Icons.Filled.PauseCircle
                    },
                contentDescription = null,
            )
            Text(
                text =
                    stringResource(
                        if (recording) {
                            R.string.global_recording_active
                        } else {
                            R.string.global_recording_paused
                        },
                        formatDuration(state.durationSeconds),
                    ),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.global_recording_open),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private fun formatDuration(seconds: Int?): String {
    val value = seconds ?: 0
    val hours = value / 3600
    val minutes = (value % 3600) / 60
    val remainingSeconds = value % 60
    return "%02d:%02d:%02d".format(hours, minutes, remainingSeconds)
}
