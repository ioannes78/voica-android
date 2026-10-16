package io.github.ioannes78.voica.ui.device

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.ui.realtime.RealtimeAudioActivityWaveform
import io.github.ioannes78.voica.ui.status.AppActivityAction
import io.github.ioannes78.voica.ui.status.AppActivityFact
import io.github.ioannes78.voica.ui.status.AppActivityKind
import io.github.ioannes78.voica.ui.status.AppActivityProjection
import java.util.Locale

private enum class HomeModePreference {
    RECORDING,
    REALTIME,
}

@Composable
internal fun HomeActivityPanel(
    projection: AppActivityProjection,
    sourcePreference: HomeActivitySourcePreference,
    recorderAvailable: Boolean,
    canStartRecording: Boolean,
    canStartRealtime: Boolean,
    permissionMessage: String?,
    onSourcePreferenceChanged: (HomeActivitySourcePreference) -> Unit,
    onStartRecording: () -> Unit,
    onStartRealtime: () -> Unit,
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
                onSourcePreferenceChanged = onSourcePreferenceChanged,
                onStartRecording = onStartRecording,
                onStartRealtime = onStartRealtime,
            )
        } else {
            PrimaryHomeActivityCard(
                activity = primary,
                onAction = onAction,
                onOpen = { onOpenActivity(primary) },
            )
        }

        projection.secondaryActivities.forEach { activity ->
            SecondaryHomeActivityRow(
                activity = activity,
                onSelect = { onSelectSecondaryActivity(activity) },
            )
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
    onSourcePreferenceChanged: (HomeActivitySourcePreference) -> Unit,
    onStartRecording: () -> Unit,
    onStartRealtime: () -> Unit,
) {
    var mode by remember { mutableStateOf(HomeModePreference.RECORDING) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeading(
            title = "录音来源",
            hint = "选择录音来源",
        )
        SourceSelector(
            sourcePreference = sourcePreference,
            recorderAvailable = recorderAvailable,
            onSourcePreferenceChanged = onSourcePreferenceChanged,
        )

        SectionHeading(
            title = "模式选择",
            hint = "选择功能模式",
        )
        ModeSelector(
            mode = mode,
            onModeChanged = { mode = it },
        )

        IdleMainActivityCard(
            sourcePreference = sourcePreference,
            mode = mode,
            enabled = if (mode == HomeModePreference.RECORDING) canStartRecording else canStartRealtime,
            permissionMessage = permissionMessage,
            onStart = if (mode == HomeModePreference.RECORDING) onStartRecording else onStartRealtime,
        )
    }
}

@Composable
private fun SectionHeading(
    title: String,
    hint: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceSelector(
    sourcePreference: HomeActivitySourcePreference,
    recorderAvailable: Boolean,
    onSourcePreferenceChanged: (HomeActivitySourcePreference) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(21.dp),
                )
                .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SegmentedChoice(
            label = "手机",
            selected = sourcePreference == HomeActivitySourcePreference.PHONE_MICROPHONE,
            enabled = true,
            onClick = { onSourcePreferenceChanged(HomeActivitySourcePreference.PHONE_MICROPHONE) },
            modifier = Modifier.weight(1f),
        )
        SegmentedChoice(
            label = "录音卡",
            selected = sourcePreference == HomeActivitySourcePreference.RECORDER_CARD,
            enabled = recorderAvailable,
            onClick = { onSourcePreferenceChanged(HomeActivitySourcePreference.RECORDER_CARD) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModeSelector(
    mode: HomeModePreference,
    onModeChanged: (HomeModePreference) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(21.dp),
                )
                .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SegmentedChoice(
            label = "录音",
            selected = mode == HomeModePreference.RECORDING,
            enabled = true,
            onClick = { onModeChanged(HomeModePreference.RECORDING) },
            modifier = Modifier.weight(1f),
        )
        SegmentedChoice(
            label = "实时转写",
            selected = mode == HomeModePreference.REALTIME,
            enabled = true,
            onClick = { onModeChanged(HomeModePreference.REALTIME) },
            modifier = Modifier.weight(1f),
        )
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
        modifier =
            modifier
                .height(40.dp)
                .clickable(enabled = enabled, onClick = onClick),
        color =
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.35f)
            } else {
                Color.Transparent
            },
        shape = RoundedCornerShape(19.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color =
                    when {
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
    onStart: () -> Unit,
) {
    val sourceTitle =
        if (sourcePreference == HomeActivitySourcePreference.PHONE_MICROPHONE) {
            "手机"
        } else {
            "录音卡"
        }
    val sourceSubtitle =
        if (sourcePreference == HomeActivitySourcePreference.PHONE_MICROPHONE) {
            "手机麦克风"
        } else {
            "QS668 / CB08"
        }
    val modeTitle = if (mode == HomeModePreference.RECORDING) "录音" else "实时转写"
    val readyText = if (mode == HomeModePreference.RECORDING) "准备录音" else "准备实时转写"
    val actionText = if (mode == HomeModePreference.RECORDING) "开始录音" else "开始实时转写"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "$sourceTitle$modeTitle",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    sourceSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(9.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
                )
                Text(
                    readyText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Text(
                "00:00:00",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Medium,
            )

            DecorativeWaveform()

            if (!permissionMessage.isNullOrBlank()) {
                Text(
                    permissionMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = onStart,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
            ) {
                Text(
                    actionText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun DecorativeWaveform() {
    val bars =
        remember {
            listOf(
                4, 5, 7, 10, 15, 9, 18, 28, 18, 10, 7, 12, 20, 11, 6, 5, 9, 16, 23, 15, 8, 6, 10,
                17, 27, 18, 11, 7, 5, 9, 14, 20, 13, 8, 5, 4,
            )
        }
    val color = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier.fillMaxWidth().height(40.dp),
    ) {
        val step = size.width / (bars.size + 1)
        bars.forEachIndexed { index, value ->
            val x = step * (index + 1)
            val halfHeight = value.dp.toPx() / 2f
            drawLine(
                color = color.copy(alpha = if (index % 5 == 0) 0.88f else 0.62f),
                start = Offset(x, size.height / 2f - halfHeight),
                end = Offset(x, size.height / 2f + halfHeight),
                strokeWidth = if (index % 5 == 0) 2.5.dp.toPx() else 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun PrimaryHomeActivityCard(
    activity: AppActivityFact,
    onAction: (AppActivityFact, AppActivityAction) -> Unit,
    onOpen: () -> Unit,
) {
    val clickable = activity.destination != null
    Card(
        modifier =
            if (clickable) {
                Modifier.fillMaxWidth().clickable(onClick = onOpen)
            } else {
                Modifier.fillMaxWidth()
            },
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        activity.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    liveMediaSummary(activity)?.let { summary ->
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (clickable) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = "查看",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                formatElapsed(activity.elapsedMs),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Medium,
            )

            if (activity.audioActivityLevels.isNotEmpty()) {
                RealtimeAudioActivityWaveform(
                    levels = activity.audioActivityLevels,
                    active = activity.waveformActive,
                )
            } else {
                DecorativeWaveform()
            }

            val transportActions =
                listOf(
                    AppActivityAction.PAUSE,
                    AppActivityAction.RESUME,
                    AppActivityAction.STOP,
                ).filter(activity.availableActions::contains)
            if (transportActions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    transportActions.forEach { action ->
                        val label =
                            when (action) {
                                AppActivityAction.PAUSE -> "暂停"
                                AppActivityAction.RESUME -> "继续"
                                AppActivityAction.STOP -> "停止"
                                else -> ""
                            }
                        if (action == AppActivityAction.STOP) {
                            Button(
                                onClick = { onAction(activity, action) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(label)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onAction(activity, action) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(label)
                            }
                        }
                    }
                }
            }

            if (AppActivityAction.OPEN in activity.availableActions && clickable) {
                TextButton(onClick = onOpen) {
                    Text(
                        if (activity.kind == AppActivityKind.REALTIME_TRANSCRIPTION) {
                            "查看实时文字"
                        } else {
                            "查看"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SecondaryHomeActivityRow(
    activity: AppActivityFact,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        shape = RoundedCornerShape(18.dp),
    ) {
        ListItem(
            headlineContent = { Text(activity.title, maxLines = 1) },
            supportingContent = {
                Text(liveMediaSummary(activity) ?: activity.subtitle.orEmpty(), maxLines = 1)
            },
            trailingContent = {
                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            },
        )
    }
}

@Composable
private fun HomeAttentionRow(
    activity: AppActivityFact,
    additionalCount: Int,
    onOpen: () -> Unit,
) {
    val modifier =
        if (activity.destination != null) {
            Modifier.fillMaxWidth().clickable(onClick = onOpen)
        } else {
            Modifier.fillMaxWidth()
        }
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        ListItem(
            headlineContent = { Text(activity.title, maxLines = 1) },
            supportingContent = {
                Text(
                    buildString {
                        append(activity.subtitle ?: "需要处理")
                        if (additionalCount > 0) append(" · 另有 $additionalCount 项")
                    },
                    maxLines = 1,
                )
            },
            trailingContent = {
                if (activity.destination != null) {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                }
            },
        )
    }
}

private fun liveMediaSummary(activity: AppActivityFact): String? {
    val filename = activity.mediaDisplayName?.trim()?.takeIf { it.isNotEmpty() }
    val size = activity.mediaSizeBytes?.let(::formatBytes)
    return when {
        filename != null && size != null -> "$filename · $size"
        filename != null -> filename
        size != null -> size
        else -> activity.subtitle?.trim()?.takeIf { it.isNotEmpty() }
    }
}

private fun formatElapsed(elapsedMs: Long?): String {
    val totalSeconds = ((elapsedMs ?: 0L).coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", safe.toDouble() / 1024.0 / 1024.0)
        safe >= 1024L -> String.format(Locale.US, "%.1f KB", safe.toDouble() / 1024.0)
        else -> "$safe B"
    }
}
