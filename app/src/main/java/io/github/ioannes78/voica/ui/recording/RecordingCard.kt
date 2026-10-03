package io.github.ioannes78.voica.ui.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.ble.RecordingCommandState
import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.ble.RecordingFreshness
import io.github.ioannes78.voica.protocol.RecordingGain
import io.github.ioannes78.voica.protocol.RecordingStatus

@Composable
fun RecordingCard(
    state: RecordingDeviceState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSave: () -> Unit,
    onRefresh: () -> Unit,
    onSetGain: (RecordingGain) -> Unit,
) {
    val synchronized = state.freshness == RecordingFreshness.FRESH
    val busy = state.commandState != RecordingCommandState.IDLE
    val controlsEnabled = synchronized && !busy && state.status !is RecordingStatus.UnknownRaw
    var menuExpanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = recordingTitle(state.status),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "更多录音设置",
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "增益：${gainText(state.gain)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                    DropdownMenuItem(
                        text = { Text("低增益") },
                        onClick = {
                            menuExpanded = false
                            onSetGain(RecordingGain.Low)
                        },
                        enabled = synchronized && !busy,
                    )
                    DropdownMenuItem(
                        text = { Text("中增益") },
                        onClick = {
                            menuExpanded = false
                            onSetGain(RecordingGain.Medium)
                        },
                        enabled = synchronized && !busy,
                    )
                    DropdownMenuItem(
                        text = { Text("高增益") },
                        onClick = {
                            menuExpanded = false
                            onSetGain(RecordingGain.High)
                        },
                        enabled = synchronized && !busy,
                    )
                    DropdownMenuItem(
                        text = { Text("刷新录音状态") },
                        onClick = {
                            menuExpanded = false
                            onRefresh()
                        },
                        enabled = !busy,
                    )
                }
            }

            Text(
                text = formatDuration(state.durationSeconds),
                style =
                    MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 36.sp,
                        lineHeight = 42.sp,
                    ),
                fontWeight = FontWeight.Medium,
            )

            if (
                (state.status == RecordingStatus.Recording ||
                    state.status == RecordingStatus.Paused) &&
                !state.filename.isNullOrBlank()
            ) {
                Text(
                    text =
                        state.filename +
                            (state.currentSizeBytes?.let {
                                " · " + formatBytes(it)
                            } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!synchronized) {
                Text(
                    freshnessText(state.freshness),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (state.status != RecordingStatus.Idle) {
                Text(
                    recordingStatusText(state.status),
                    style = MaterialTheme.typography.bodySmall,
                    color = recordingStatusColor(state.status),
                )
            }

            when (state.status) {
                RecordingStatus.Idle -> {
                    Button(
                        onClick = onStart,
                        enabled = controlsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.recording_start))
                    }
                }

                RecordingStatus.Recording -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onPause,
                            enabled = controlsEnabled,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.recording_pause))
                        }
                        Button(
                            onClick = onSave,
                            enabled = controlsEnabled,
                            modifier = Modifier.weight(1f),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                        ) {
                            Text(stringResource(R.string.recording_stop))
                        }
                    }
                }

                RecordingStatus.Paused -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onResume,
                            enabled = controlsEnabled,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.recording_resume))
                        }
                        Button(
                            onClick = onSave,
                            enabled = controlsEnabled,
                            modifier = Modifier.weight(1f),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                        ) {
                            Text(stringResource(R.string.recording_stop))
                        }
                    }
                }

                is RecordingStatus.UnknownRaw,
                null,
                -> Unit
            }

            if (busy) {
                Text(
                    commandText(state.commandState),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            state.lastError?.let { error ->
                Text(
                    error.code.name + (error.detail?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun recordingTitle(status: RecordingStatus?): String =
    when (status) {
        RecordingStatus.Idle -> stringResource(R.string.recording_ready_title)
        RecordingStatus.Recording -> stringResource(R.string.recording_active_title)
        RecordingStatus.Paused -> stringResource(R.string.recording_paused_title)
        is RecordingStatus.UnknownRaw,
        null,
        -> stringResource(R.string.recording_unknown_title)
    }

@Composable
private fun recordingStatusText(status: RecordingStatus?): String =
    when (status) {
        RecordingStatus.Idle -> stringResource(R.string.recording_idle)
        RecordingStatus.Recording -> stringResource(R.string.recording_recording)
        RecordingStatus.Paused -> stringResource(R.string.recording_paused)
        is RecordingStatus.UnknownRaw ->
            stringResource(R.string.recording_unknown_raw, status.rawValue)
        null -> stringResource(R.string.recording_unknown)
    }

@Composable
private fun recordingStatusColor(status: RecordingStatus?) =
    when (status) {
        RecordingStatus.Recording -> MaterialTheme.colorScheme.error
        RecordingStatus.Paused -> MaterialTheme.colorScheme.secondary
        RecordingStatus.Idle -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun freshnessText(freshness: RecordingFreshness): String =
    when (freshness) {
        RecordingFreshness.NOT_SYNCED -> stringResource(R.string.recording_not_synced)
        RecordingFreshness.SYNCING -> stringResource(R.string.recording_syncing)
        RecordingFreshness.FRESH -> stringResource(R.string.recording_synced)
        RecordingFreshness.STALE -> stringResource(R.string.recording_stale)
        RecordingFreshness.FAILED -> stringResource(R.string.recording_sync_failed)
    }

@Composable
private fun commandText(state: RecordingCommandState): String =
    when (state) {
        RecordingCommandState.IDLE -> ""
        RecordingCommandState.STARTING -> stringResource(R.string.recording_starting)
        RecordingCommandState.PAUSING -> stringResource(R.string.recording_pausing)
        RecordingCommandState.RESUMING -> stringResource(R.string.recording_resuming)
        RecordingCommandState.SAVING -> stringResource(R.string.recording_saving)
        RecordingCommandState.SETTING_GAIN -> stringResource(R.string.recording_setting_gain)
        RecordingCommandState.RECONCILING -> stringResource(R.string.recording_syncing)
    }

private fun gainText(gain: RecordingGain?): String =
    when (gain) {
        RecordingGain.Low -> "低"
        RecordingGain.Medium -> "中"
        RecordingGain.High -> "高"
        is RecordingGain.UnknownRaw -> "未知(" + gain.rawValue + ")"
        null -> "未知"
    }

private fun formatDuration(seconds: Int?): String {
    val value = seconds ?: 0
    val hours = value / 3600
    val minutes = (value % 3600) / 60
    val remainingSeconds = value % 60
    return "%02d:%02d:%02d".format(hours, minutes, remainingSeconds)
}

private fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "--"
    return when {
        bytes >= 1024L * 1024L * 1024L ->
            "%.2f GB".format(bytes.toDouble() / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024L ->
            "%.2f MB".format(bytes.toDouble() / 1024.0 / 1024.0)
        bytes >= 1024L ->
            "%.1f KB".format(bytes.toDouble() / 1024.0)
        else -> "$bytes B"
    }
}
