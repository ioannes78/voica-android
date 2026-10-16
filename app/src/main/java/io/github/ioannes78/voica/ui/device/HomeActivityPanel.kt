package io.github.ioannes78.voica.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.protocol.RecordingGain
import io.github.ioannes78.voica.ui.realtime.RealtimeAudioActivityWaveform
import io.github.ioannes78.voica.ui.status.AppActivityAction
import io.github.ioannes78.voica.ui.status.AppActivityFact
import io.github.ioannes78.voica.ui.status.AppActivityKind
import io.github.ioannes78.voica.ui.status.AppActivityProjection
import io.github.ioannes78.voica.ui.status.AppActivitySource
import io.github.ioannes78.voica.ui.status.LocalProductSpeechSettingsStore
import io.github.ioannes78.voica.ui.status.productDisplayName
import java.util.Locale

private enum class HomeModePreference { RECORDING, REALTIME }

@Composable
internal fun HomeActivityPanel(
    projection: AppActivityProjection,
    sourcePreference: HomeActivitySourcePreference,
    recorderAvailable: Boolean,
    canStartRecording: Boolean,
    canStartRealtime: Boolean,
    permissionMessage: String?,
    recorderGain: RecordingGain?,
    recorderGainInteractionEnabled: Boolean,
    recorderGainBusy: Boolean,
    recorderGainErrorMessage: String?,
    onSourcePreferenceChanged: (HomeActivitySourcePreference) -> Unit,
    onStartRecording: () -> Unit,
    onStartRealtime: () -> Unit,
    onSetRecordingGain: (RecordingGain) -> Unit,
    onAction: (AppActivityFact, AppActivityAction) -> Unit,
    onOpenActivity: (AppActivityFact) -> Unit,
    onSelectSecondaryActivity: (AppActivityFact) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val primary = projection.primaryActivity
        if (primary == null) {
            IdleHomeQuickActions(
                sourcePreference = sourcePreference,
                recorderAvailable = recorderAvailable,
                canStartRecording = canStartRecording,
                canStartRealtime = canStartRealtime,
                permissionMessage = permissionMessage,
                recorderGain = recorderGain,
                recorderGainInteractionEnabled = recorderGainInteractionEnabled,
                recorderGainBusy = recorderGainBusy,
                recorderGainErrorMessage = recorderGainErrorMessage,
                onSourcePreferenceChanged = onSourcePreferenceChanged,
                onStartRecording = onStartRecording,
                onStartRealtime = onStartRealtime,
                onSetRecordingGain = onSetRecordingGain,
            )
        } else {
            PrimaryHomeActivityCard(
                activity = primary,
                recorderGain = recorderGain,
                recorderGainInteractionEnabled = recorderGainInteractionEnabled,
                recorderGainBusy = recorderGainBusy,
                recorderGainErrorMessage = recorderGainErrorMessage,
                onSetRecordingGain = onSetRecordingGain,
                onAction = onAction,
                onOpen = { onOpenActivity(primary) },
            )
        }

        projection.secondaryActivities.forEach { activity ->
            SecondaryHomeActivityRow(activity = activity, onSelect = { onSelectSecondaryActivity(activity) })
        }
        projection.attentionItems.firstOrNull()?.let { attention ->
            HomeAttentionRow(
                activity = attention,
                additionalCount = (projection.attentionItems.size - 1).coerceAtLeast(0),
                onOpen = { onOpenActivity(attention) },
            )
        }
    }
}

@Composable
private fun IdleHomeQuickActions(
    sourcePreference: HomeActivitySourcePreference,
    recorderAvailable: Boolean,
    canStartRecording: Boolean,
    canStartRealtime: Boolean,
    permissionMessage: String?,
    recorderGain: RecordingGain?,
    recorderGainInteractionEnabled: Boolean,
    recorderGainBusy: Boolean,
    recorderGainErrorMessage: String?,
    onSourcePreferenceChanged: (HomeActivitySourcePreference) -> Unit,
    onStartRecording: () -> Unit,
    onStartRealtime: () -> Unit,
    onSetRecordingGain: (RecordingGain) -> Unit,
) {
    var mode by remember { mutableStateOf(HomeModePreference.RECORDING) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading("录音来源", "选择录音来源")
        SourceSelector(sourcePreference, recorderAvailable, onSourcePreferenceChanged)
        SectionHeading("模式选择", "选择功能模式")
        ModeSelector(mode) { mode = it }
        IdleMainActivityCard(
            sourcePreference = sourcePreference,
            mode = mode,
            enabled = if (mode == HomeModePreference.RECORDING) canStartRecording else canStartRealtime,
            permissionMessage = permissionMessage,
            recorderGain = recorderGain,
            recorderGainInteractionEnabled = recorderGainInteractionEnabled,
            recorderGainBusy = recorderGainBusy,
            recorderGainErrorMessage = recorderGainErrorMessage,
            onSetRecordingGain = onSetRecordingGain,
            onStart = if (mode == HomeModePreference.RECORDING) onStartRecording else onStartRealtime,
        )
    }
}

@Composable
private fun SectionHeading(title: String, hint: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SourceSelector(
    sourcePreference: HomeActivitySourcePreference,
    recorderAvailable: Boolean,
    onSourcePreferenceChanged: (HomeActivitySourcePreference) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            RoundedCornerShape(21.dp),
        ).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SegmentedChoice(
            "手机",
            sourcePreference == HomeActivitySourcePreference.PHONE_MICROPHONE,
            true,
            { onSourcePreferenceChanged(HomeActivitySourcePreference.PHONE_MICROPHONE) },
            Modifier.weight(1f),
        )
        SegmentedChoice(
            "录音卡",
            sourcePreference == HomeActivitySourcePreference.RECORDER_CARD,
            recorderAvailable,
            { onSourcePreferenceChanged(HomeActivitySourcePreference.RECORDER_CARD) },
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModeSelector(mode: HomeModePreference, onModeChanged: (HomeModePreference) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            RoundedCornerShape(21.dp),
        ).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SegmentedChoice("录音", mode == HomeModePreference.RECORDING, true, { onModeChanged(HomeModePreference.RECORDING) }, Modifier.weight(1f))
        SegmentedChoice("实时转写", mode == HomeModePreference.REALTIME, true, { onModeChanged(HomeModePreference.REALTIME) }, Modifier.weight(1f))
    }
}

@Composable
private fun SegmentedChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(40.dp).clickable(enabled = enabled, onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.35f) else Color.Transparent,
        shape = RoundedCornerShape(19.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    selected -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun IdleMainActivityCard(
    sourcePreference: HomeActivitySourcePreference,
    mode: HomeModePreference,
    enabled: Boolean,
    permissionMessage: String?,
    recorderGain: RecordingGain?,
    recorderGainInteractionEnabled: Boolean,
    recorderGainBusy: Boolean,
    recorderGainErrorMessage: String?,
    onSetRecordingGain: (RecordingGain) -> Unit,
    onStart: () -> Unit,
) {
    val recorder = sourcePreference == HomeActivitySourcePreference.RECORDER_CARD
    val speechSettings by LocalProductSpeechSettingsStore.current.settings.collectAsState()
    val configuredRealtimeModel = speechSettings.realtimeAsrModel.productDisplayName()
    val sourceTitle = if (recorder) "录音卡" else "手机"
    val sourceName = if (recorder) "QS668 / CB08" else "手机"
    val sourceSubtitle =
        if (mode == HomeModePreference.REALTIME) {
            "$sourceName · $configuredRealtimeModel"
        } else {
            sourceName
        }
    val modeTitle = if (mode == HomeModePreference.RECORDING) "录音" else "实时转写"
    val readyText = if (mode == HomeModePreference.RECORDING) "准备录音" else "准备实时转写"
    val actionText = if (mode == HomeModePreference.RECORDING) "开始录音" else "开始实时转写"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("$sourceTitle$modeTitle", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        sourceSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (recorder) {
                    HomeRecordingGainControl(
                        gain = recorderGain,
                        interactionEnabled = recorderGainInteractionEnabled,
                        busy = recorderGainBusy,
                        errorMessage = recorderGainErrorMessage,
                        onSetGain = onSetRecordingGain,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(9.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
                Text(readyText, style = MaterialTheme.typography.bodyMedium)
            }
            Text("00:00:00", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Medium)
            if (!permissionMessage.isNullOrBlank()) {
                Text(permissionMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = onStart,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
            ) { Text(actionText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun PrimaryHomeActivityCard(
    activity: AppActivityFact,
    recorderGain: RecordingGain?,
    recorderGainInteractionEnabled: Boolean,
    recorderGainBusy: Boolean,
    recorderGainErrorMessage: String?,
    onSetRecordingGain: (RecordingGain) -> Unit,
    onAction: (AppActivityFact, AppActivityAction) -> Unit,
    onOpen: () -> Unit,
) {
    val clickable = activity.destination != null
    val recorder = activity.source == AppActivitySource.RECORDER_CARD
    val realtime = activity.kind == AppActivityKind.REALTIME_TRANSCRIPTION
    Card(
        modifier = if (clickable) Modifier.fillMaxWidth().clickable(onClick = onOpen) else Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(activity.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (realtime) {
                        Text(
                            "${if (recorder) "QS668 / CB08" else "手机"} · ${activity.modelLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "模型加载中…"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (recorder) {
                        Text("录音设备：QS668 / CB08", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("手机", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (recorder) {
                    HomeRecordingGainControl(
                        gain = recorderGain,
                        interactionEnabled = recorderGainInteractionEnabled,
                        busy = recorderGainBusy,
                        errorMessage = recorderGainErrorMessage,
                        onSetGain = onSetRecordingGain,
                    )
                }
                if (clickable) Icon(Icons.Outlined.ChevronRight, contentDescription = "查看", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (!realtime) {
                activity.mediaDisplayName?.trim()?.takeIf { it.isNotEmpty() }?.let { filename ->
                    Text(
                        filename,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                activity.mediaSizeBytes?.let { bytes ->
                    Text(
                        formatBytes(bytes),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(formatElapsed(activity.elapsedMs), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Medium)

            if (realtime || activity.source == AppActivitySource.PHONE_MICROPHONE) {
                RealtimeAudioActivityWaveform(levels = activity.audioActivityLevels, active = activity.waveformActive)
            }

            TransportActions(activity, onAction)

            if (AppActivityAction.OPEN in activity.availableActions && clickable) {
                TextButton(onClick = onOpen) {
                    Text(if (realtime) "查看实时文字" else "查看")
                }
            }
        }
    }
}

@Composable
private fun TransportActions(
    activity: AppActivityFact,
    onAction: (AppActivityFact, AppActivityAction) -> Unit,
) {
    val actions = listOf(AppActivityAction.PAUSE, AppActivityAction.RESUME, AppActivityAction.STOP)
        .filter(activity.availableActions::contains)
    if (actions.isEmpty()) return
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { action ->
            val label = when (action) {
                AppActivityAction.PAUSE -> "暂停"
                AppActivityAction.RESUME -> "继续"
                AppActivityAction.STOP -> "停止"
                else -> ""
            }
            val colors = if (action == AppActivityAction.STOP) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Button(
                onClick = { onAction(activity, action) },
                modifier = Modifier.weight(1f),
                colors = colors,
            ) { Text(label) }
        }
    }
}

@Composable
private fun SecondaryHomeActivityRow(activity: AppActivityFact, onSelect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect), shape = RoundedCornerShape(18.dp)) {
        ListItem(
            headlineContent = { Text(activity.title, maxLines = 1) },
            supportingContent = {
                Text(
                    if (activity.kind == AppActivityKind.REALTIME_TRANSCRIPTION) {
                        "${if (activity.source == AppActivitySource.RECORDER_CARD) "QS668 / CB08" else "手机"} · ${activity.modelLabel ?: "模型加载中…"}"
                    } else {
                        activity.subtitle.orEmpty()
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
        )
    }
}

@Composable
private fun HomeAttentionRow(activity: AppActivityFact, additionalCount: Int, onOpen: () -> Unit) {
    val modifier = if (activity.destination != null) Modifier.fillMaxWidth().clickable(onClick = onOpen) else Modifier.fillMaxWidth()
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        ListItem(
            headlineContent = { Text(activity.title, maxLines = 1) },
            supportingContent = {
                Text(buildString {
                    append(activity.subtitle ?: "需要处理")
                    if (additionalCount > 0) append(" · 另有 $additionalCount 项")
                }, maxLines = 1)
            },
            trailingContent = { if (activity.destination != null) Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
        )
    }
}

private fun formatElapsed(elapsedMs: Long?): String {
    val totalSeconds = ((elapsedMs ?: 0L).coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", safe.toDouble() / 1024.0 / 1024.0)
        safe >= 1024L -> String.format(Locale.US, "%.1f KB", safe.toDouble() / 1024.0)
        else -> "$safe B"
    }
}
