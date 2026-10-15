package io.github.ioannes78.voica.ui.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.ui.status.AppActivityAction
import io.github.ioannes78.voica.ui.status.AppActivityFact
import io.github.ioannes78.voica.ui.status.AppActivityKind
import io.github.ioannes78.voica.ui.status.AppActivityProjection

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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "开始",
                style = MaterialTheme.typography.titleMedium,
            )

            if (recorderAvailable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SourceChoiceButton(
                        label = "录音卡",
                        selected = sourcePreference == HomeActivitySourcePreference.RECORDER_CARD,
                        onClick = {
                            onSourcePreferenceChanged(HomeActivitySourcePreference.RECORDER_CARD)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    SourceChoiceButton(
                        label = "手机",
                        selected = sourcePreference == HomeActivitySourcePreference.PHONE_MICROPHONE,
                        onClick = {
                            onSourcePreferenceChanged(HomeActivitySourcePreference.PHONE_MICROPHONE)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Text(
                    "来源 · 手机麦克风",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onStartRecording,
                    enabled = canStartRecording,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("录音")
                }
                OutlinedButton(
                    onClick = onStartRealtime,
                    enabled = canStartRealtime,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("实时转写")
                }
            }

            if (!permissionMessage.isNullOrBlank()) {
                Text(
                    permissionMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SourceChoiceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(label)
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
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                activity.title,
                style = MaterialTheme.typography.titleLarge,
            )
            activity.subtitle?.let { subtitle ->
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    ) {
        ListItem(
            headlineContent = { Text(activity.title, maxLines = 1) },
            supportingContent = {
                activity.subtitle?.let { Text(it, maxLines = 1) }
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
    Card(modifier = modifier) {
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
